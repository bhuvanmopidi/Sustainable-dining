"""POST /recommend: build the distinct candidate layouts and choose one.

Precedence:
  1. BASELINE phase       -> P0 layout (BASELINE_NEUTRAL)
  2. force_p0 (rollback)  -> P0 layout (FORCED_P0_ROLLBACK)
  3. only one layout      -> that layout (SINGLE_LAYOUT_ONLY)
  4. every non-neutral layout is cooling down -> P0 (ALL_ALTERNATIVES_COOLING_DOWN)
  5. WARMUP               -> round-robin over available non-neutral layouts
  6. ADAPTIVE             -> highest LinUCB score among available layouts (seeded tie-break)
"""
from __future__ import annotations

from .features import FEATURE_SCHEMA_VERSION, PHI_DIM, context_vector, phi
from .layout import candidate_layouts
from .learners import SharedLinUCB, argmax_seeded, seeded_rng


def _p0_index(candidates):
    return next(i for i, c in enumerate(candidates) if "P0" in c["generating_labels"])


def recommend(request: dict) -> dict:
    cfg = request["config"]
    meal, menu, slots = request["meal"], request["menu_items"], request["slots"]

    # ---- candidates and features
    candidates = candidate_layouts(menu, slots, cfg["layout"]["policy_alphas"])
    x = context_vector(meal, menu, slots, request["forecast"], request["exposure_history"],
                       request["catalogue_median_carbon_score"], cfg["features"]["exposure_window_meals"])
    cooling = {c["policy_label"] for c in request["active_cooldowns"] if c["meal_type"] == meal["meal_type"]}
    for c in candidates:
        c["phi"] = phi(x, c["d"], c["m"])
        # A layout is excluded only if EVERY label that generates it is cooling down. P0 is never cooled.
        c["excluded_by_cooldown"] = all(lbl in cooling for lbl in c["generating_labels"])
        c["pred_mean"] = c["bonus"] = c["ucb_score"] = None

    p0 = _p0_index(candidates)
    available = [i for i, c in enumerate(candidates) if not c["excluded_by_cooldown"]]
    non_neutral = [i for i in available if i != p0]
    phase = request["phase"]
    source = {"BASELINE": "BASELINE", "WARMUP": "WARMUP", "ADAPTIVE": "LEARNER"}[phase]

    # ---- score with LinUCB in the adaptive phase (also shown to the manager as the explanation)
    if phase == "ADAPTIVE":
        lcfg = cfg["learner"]
        model = SharedLinUCB.from_log(request["learner_log"], PHI_DIM, lcfg["beta"], lcfg["ridge_lambda"])
        for c in candidates:
            c["pred_mean"], c["bonus"], c["ucb_score"] = model.score(c["phi"])

    # ---- choose
    if phase == "BASELINE":
        chosen, reason = p0, "BASELINE_NEUTRAL"
    elif request["force_p0"]:
        chosen, reason, source = p0, "FORCED_P0_ROLLBACK", "ROLLBACK"
    elif len(candidates) == 1:
        chosen, reason = p0, "SINGLE_LAYOUT_ONLY"
    elif not non_neutral:
        chosen, reason = p0, "ALL_ALTERNATIVES_COOLING_DOWN"
    elif phase == "WARMUP":
        chosen, reason = available[request["warmup_cursor"] % len(available)], "WARMUP_ROUND_ROBIN"
    else:
        rng = seeded_rng(meal["id"], "tie-break")
        pick = argmax_seeded([candidates[i]["ucb_score"] for i in available], rng)
        chosen, reason = available[pick], "HIGHEST_UCB"

    return {
        "candidates": candidates,
        "chosen_hash": candidates[chosen]["ordering_hash"],
        "source": source,
        "reason_code": reason,
        "learner_log_length": len(request["learner_log"]),
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
    }
