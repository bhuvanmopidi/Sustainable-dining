"""Validate the experiment config, the feature schema and every contract sample.

Usage:  python tools/validate_contracts.py
Exit code 0 when everything is valid, 1 otherwise.

Two layers of checks:
  1. JSON Schema validation (shape, types, enums, ranges, no extra fields).
  2. Semantic checks a schema cannot express (hashes, phi consistency, date order, ...).
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent.parent
SCHEMA_DIR = ROOT / "contracts" / "schemas"
SAMPLE_DIR = ROOT / "contracts" / "samples"
CONFIG_PATH = ROOT / "config" / "experiment_config.v1.json"
FEATURE_SCHEMA_PATH = ROOT / "contracts" / "feature_schema.v1.json"
BASE_URI = "https://sustainable-dining.local/schemas/"

ENDPOINT_SCHEMAS = ["recommend", "evaluate", "reference_fit", "forecast", "simulate_outcome"]
TOL = 1e-3


def load_json(path: Path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- registry
def build_registry() -> Registry:
    resources = []
    for path in sorted(SCHEMA_DIR.glob("*.schema.json")):
        schema = load_json(path)
        resources.append((schema["$id"], Resource.from_contents(schema)))
    return Registry().with_resources(resources)


def validator_for(registry: Registry, schema_name: str, part: str | None) -> Draft202012Validator:
    ref = f"{BASE_URI}{schema_name}.schema.json"
    if part:
        ref += f"#/$defs/{part}"
    return Draft202012Validator({"$ref": ref}, registry=registry)


def schema_errors(validator: Draft202012Validator, instance) -> list[str]:
    errs = sorted(validator.iter_errors(instance), key=lambda e: list(e.absolute_path))
    return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in errs]


# ---------------------------------------------------------------- placeholders
def resolve_placeholders(node, config, fixtures):
    """Replace "@config" and "@fixtures.<key>" strings with deep copies of the real values."""
    if isinstance(node, str):
        if node == "@config":
            return copy.deepcopy(config)
        if node.startswith("@fixtures."):
            key = node[len("@fixtures."):]
            if key not in fixtures:
                raise KeyError(f"unknown fixture '{key}'")
            return copy.deepcopy(fixtures[key])
        return node
    if isinstance(node, list):
        return [resolve_placeholders(x, config, fixtures) for x in node]
    if isinstance(node, dict):
        return {k: resolve_placeholders(v, config, fixtures) for k, v in node.items()}
    return node


# ---------------------------------------------------------------- semantic checks
def ordering_hash(ordering: list[str]) -> str:
    """Canonical layout hash: sha256 of the comma-joined menu-item ids in slot order."""
    return hashlib.sha256(",".join(ordering).encode("utf-8")).hexdigest()


def close(a: float, b: float) -> bool:
    return math.isclose(a, b, abs_tol=TOL)


def check_feature_schema(fs) -> list[str]:
    errs = []
    feats = fs["features"]
    if len(feats) != fs["dimension"]:
        errs.append(f"feature schema: dimension {fs['dimension']} but {len(feats)} features listed")
    if [f["index"] for f in feats] != list(range(len(feats))):
        errs.append("feature schema: indices must be 0..n-1 in order")
    names = [f["name"] for f in feats]
    if len(set(names)) != len(names):
        errs.append("feature schema: duplicate feature names")
    return errs


def check_config(cfg) -> list[str]:
    errs = []
    s = cfg["semester"]
    if s["baseline_weeks"] + s["warmup_weeks"] >= s["weeks"]:
        errs.append("config: baseline + warm-up must leave at least one adaptive week")
    lay = cfg["layout"]
    if lay["eligible_slot_visibility_min"] >= lay["eligible_slot_visibility_max"]:
        errs.append("config: slot visibility min must be below max")
    a = lay["policy_alphas"]
    if not (a["P0"] < a["P1"] < a["P2"] < a["P3"]):
        errs.append("config: policy alphas must be strictly increasing P0 < P1 < P2 < P3")
    seeds = cfg["evaluation"]["seeds"]
    ranges = [seeds["tuning"], seeds["validation"], seeds["test"]]
    for lo, hi in ranges:
        if lo > hi:
            errs.append(f"config: seed range {lo}-{hi} is reversed")
    for (lo1, hi1), (lo2, hi2) in zip(ranges, ranges[1:]):
        if hi1 >= lo2:
            errs.append("config: tuning, validation and test seed ranges must not overlap")
    test_lo, test_hi = seeds["test"]
    if test_hi - test_lo + 1 < cfg["evaluation"]["runs_per_scenario"]:
        errs.append("config: test seed range is smaller than runs_per_scenario")
    if cfg["reward"]["lambda_w"] not in cfg["reward"]["lambda_w_sensitivity"]:
        errs.append("config: primary lambda_w must be one of the sensitivity values")
    return errs


def check_meal(meal, where) -> list[str]:
    if meal["expected_attendance"] > meal["enrolled"]:
        return [f"{where}: expected_attendance exceeds enrolled"]
    return []


def check_menu(menu_items, where) -> list[str]:
    errs = []
    slots = [m["standard_slot"] for m in menu_items]
    if len(set(slots)) != len(slots):
        errs.append(f"{where}: standard_slot values must be unique within a meal")
    ids = [m["id"] for m in menu_items]
    if len(set(ids)) != len(ids):
        errs.append(f"{where}: duplicate menu item ids")
    return errs


def check_recommend(req, resp) -> list[str]:
    errs = check_meal(req["meal"], "request.meal") + check_menu(req["menu_items"], "request.menu_items")
    eligible = {m["id"] for m in req["menu_items"] if m["role"] == "ELIGIBLE"}
    menu_ids = {m["id"] for m in req["menu_items"]}
    n_eligible_slots = sum(1 for s in req["slots"] if s["eligible_slot"])
    if len(eligible) > n_eligible_slots:
        errs.append("request: more eligible items than eligible slots")
    for f in req["forecast"]:
        if f["menu_item_id"] not in menu_ids:
            errs.append(f"request.forecast: unknown menu item {f['menu_item_id']}")
    max_hist = req["config"]["features"]["exposure_window_meals"]
    if len(req["exposure_history"]) > max_hist:
        errs.append(f"request.exposure_history: longer than exposure_window_meals ({max_hist})")

    if resp["learner_log_length"] != len(req["learner_log"]):
        errs.append("response.learner_log_length must equal len(request.learner_log)")

    hashes, contexts, labels_seen = [], [], []
    for i, c in enumerate(resp["candidates"]):
        w = f"response.candidates[{i}]"
        if set(c["ordering"]) != eligible or len(c["ordering"]) != len(eligible):
            errs.append(f"{w}: ordering must be a permutation of the eligible menu items")
        if c["ordering_hash"] != ordering_hash(c["ordering"]):
            errs.append(f"{w}: ordering_hash does not match sha256 of the ordering")
        hashes.append(c["ordering_hash"])
        labels_seen.extend(c["generating_labels"])
        phi = c["phi"]
        contexts.append(phi[:10])
        d, m = c["d"], c["m"]
        expected = {10: d, 11: d * d, 12: m, 13: d * phi[1], 14: d * phi[2], 15: d * phi[9]}
        for idx, val in expected.items():
            if not close(phi[idx], val):
                errs.append(f"{w}: phi[{idx}]={phi[idx]} inconsistent with d/m (expected {round(val, 4)})")
        if "P0" in c["generating_labels"] and not (close(d, 0) and close(m, 0)):
            errs.append(f"{w}: the P0 layout must have d = 0 and m = 0")
        if close(phi[0], 1.0) is False:
            errs.append(f"{w}: phi[0] (intercept) must be 1")
    if len(set(hashes)) != len(hashes):
        errs.append("response: duplicate candidate layouts (dedup failed)")
    if sorted(labels_seen) != ["P0", "P1", "P2", "P3"]:
        errs.append("response: each policy label must appear in exactly one candidate")
    if any(ctx != contexts[0] for ctx in contexts):
        errs.append("response: context block phi[0:10] must be identical across candidates")
    if resp["chosen_hash"] not in hashes:
        errs.append("response.chosen_hash is not one of the candidates")
    else:
        chosen = next(c for c in resp["candidates"] if c["ordering_hash"] == resp["chosen_hash"])
        if chosen["excluded_by_cooldown"]:
            errs.append("response: chosen candidate is excluded by cooldown")
        if req["force_p0"] and "P0" not in chosen["generating_labels"]:
            errs.append("response: force_p0 was set but the chosen layout is not P0")
    if req["phase"] == "BASELINE" and resp["source"] != "BASELINE":
        errs.append("response.source must be BASELINE during the baseline phase")
    return errs


def check_evaluate(req, resp) -> list[str]:
    errs = check_meal(req["meal"], "request.meal") + check_menu(req["menu_items"], "request.menu_items")
    menu_ids = {m["id"] for m in req["menu_items"]}
    out_ids = [o["menu_item_id"] for o in req["observation"]["item_outcomes"]]
    if set(out_ids) != menu_ids or len(out_ids) != len(menu_ids):
        errs.append("request.observation: item_outcomes must cover each menu item exactly once")
    window = req["config"]["safety"]["waste_sustained_window"]
    if len(req["same_type_history"]) > window - 1:
        errs.append(f"request.same_type_history: at most {window - 1} entries")

    m = resp["metrics"]
    clip = req["config"]["reward"]["clip"]
    if m["reward_unclipped"] is not None:
        if not close(m["reward"], max(-clip, min(clip, m["reward_unclipped"]))):
            errs.append("response.metrics: reward must equal clip(reward_unclipped)")
        lam = req["config"]["reward"]["lambda_w"]
        if not close(m["reward_unclipped"], m["z_c"] - lam * m["z_w"]):
            errs.append("response.metrics: reward_unclipped must equal z_c - lambda_w * z_w")
    if resp["eval_status"] != "OK" and resp["learner_update"]:
        errs.append("response: learner_update must be false unless eval_status is OK")
    rollback = any(e["action"] == "ROLLBACK" for e in resp["safety_events"])
    if resp["force_p0_next"] != rollback:
        errs.append("response: force_p0_next must be true exactly when a ROLLBACK event fired")
    deployed = set(req["deployed_candidate"]["generating_labels"])
    for e in resp["safety_events"]:
        if deployed == {"P0"} and e["action"] == "ROLLBACK":
            errs.append("response: a P0-only deployment can raise ALERT_ONLY, never ROLLBACK")
        if not set(e["cooldown_labels"]) <= deployed | {
            lbl for h in req["same_type_history"] for lbl in h["deployed_labels"]
        }:
            errs.append("response: cooldown labels must come from policies deployed in the triggering window")
    return errs


def check_reference_fit(req, resp) -> list[str]:
    errs = []
    for i, bm in enumerate(req["baseline_meals"]):
        errs += check_meal(bm["meal"], f"request.baseline_meals[{i}].meal")
        errs += check_menu(bm["menu_items"], f"request.baseline_meals[{i}].menu_items")
    if resp["status"] == "FITTED":
        ref = resp["reference_model"]
        if any(v < 0 for v in ref["sigma_log_waste"].values()):
            errs.append("response: sigma_log_waste must be non-negative")
    return errs


def check_forecast(req, resp) -> list[str]:
    errs = check_meal(req["target_meal"], "request.target_meal")
    order = {"BREAKFAST": 0, "LUNCH": 1, "DINNER": 2}
    target_key = (req["target_meal"]["date"], order[req["target_meal"]["meal_type"]])
    for i, h in enumerate(req["history"]):
        if (h["meal"]["date"], order[h["meal"]["meal_type"]]) >= target_key:
            errs.append(f"request.history[{i}]: dated on/after the target meal (future data leak)")
    target_ids = {m["id"] for m in req["target_menu_items"]}
    pred_ids = [p["menu_item_id"] for p in resp["predictions"]]
    if set(pred_ids) != target_ids or len(pred_ids) != len(target_ids):
        errs.append("response.predictions must cover each target menu item exactly once")
    return errs


def check_simulate_outcome(req, resp) -> list[str]:
    errs = check_meal(req["meal"], "request.meal")
    eligible = {m["id"] for m in req["menu_items"] if m["role"] == "ELIGIBLE"}
    if set(req["deployed_ordering"]) != eligible:
        errs.append("request.deployed_ordering must be a permutation of the eligible items")
    return errs


SEMANTIC_CHECKS = {
    "recommend": check_recommend,
    "evaluate": check_evaluate,
    "reference_fit": check_reference_fit,
    "forecast": check_forecast,
    "simulate_outcome": check_simulate_outcome,
}


# ---------------------------------------------------------------- driver
def validate_payload(registry, schema_name, request, response) -> list[str]:
    """Validate one request/response pair. Returns a list of error strings (empty = valid)."""
    errs = []
    errs += [f"request {e}" for e in schema_errors(validator_for(registry, schema_name, "request"), request)]
    errs += [f"response {e}" for e in schema_errors(validator_for(registry, schema_name, "response"), response)]
    if not errs:  # semantic checks assume the shape is already valid
        errs += SEMANTIC_CHECKS[schema_name](request, response)
    return errs


def load_sample(path: Path, config, fixtures):
    raw = load_json(path)
    name = raw.get("schema") or path.name.split(".sample")[0]
    if name not in ENDPOINT_SCHEMAS:
        raise ValueError(f"{path.name}: unknown schema '{name}'")
    return name, resolve_placeholders(raw["request"], config, fixtures), resolve_placeholders(raw["response"], config, fixtures)


def run() -> int:
    registry = build_registry()
    config = load_json(CONFIG_PATH)
    feature_schema = load_json(FEATURE_SCHEMA_PATH)
    fixtures = load_json(SAMPLE_DIR / "_fixtures.json")
    failures = 0

    def report(label, errs):
        nonlocal failures
        if errs:
            failures += 1
            print(f"FAIL  {label}")
            for e in errs:
                print(f"      - {e}")
        else:
            print(f"ok    {label}")

    report("config/experiment_config.v1.json",
           schema_errors(validator_for(registry, "experiment_config", None), config) + check_config(config))
    report("contracts/feature_schema.v1.json", check_feature_schema(feature_schema))

    for path in sorted(SAMPLE_DIR.glob("*.sample.json")):
        name, req, resp = load_sample(path, config, fixtures)
        report(f"contracts/samples/{path.name}  [{name}]", validate_payload(registry, name, req, resp))

    print(f"\n{'All contracts valid.' if failures == 0 else f'{failures} file(s) failed.'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(run())
