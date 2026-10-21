"""Run one simulated semester for one strategy, using the same core code the app uses.

Strategies
  FIXED_P0..FIXED_P3  static: same policy every meal after the baseline, no safety rules
  RANDOM              uniform over distinct layouts; safety rules apply
  UCB1                non-contextual bandit over labels; warm-up and safety rules apply
  LINUCB              the proposed method (core.recommend); warm-up and safety rules apply
  LINUCB_NOSAFE       ablation: LINUCB without rollback or cooldown (measures the cost of safety)
  ORACLE              per meal, the layout with the best realised reward (peeks at the simulator)
Every strategy deploys P0 during the baseline, so all runs share the same reference model.
The demand forecaster (context feature 8) is used by the LINUCB strategies only and is refit weekly.
"""
from __future__ import annotations

from collections import defaultdict

from core.evaluate import evaluate
from core.forecast import Forecaster
from core.learners import UCB1Labels, seeded_rng
from core.metrics import compute_metrics
from core.recommend import recommend
from core.reference import fit_reference
from simulator.mess import MessSimulator

STRATEGIES = ("FIXED_P0", "FIXED_P1", "FIXED_P2", "FIXED_P3", "RANDOM", "UCB1",
              "LINUCB", "LINUCB_NOSAFE", "ORACLE")
SAFE = {"RANDOM", "UCB1", "LINUCB"}                     # strategies subject to rollback and cooldown
FORCED = {"FORCED_P0_ROLLBACK", "SINGLE_LAYOUT_ONLY", "ALL_ALTERNATIVES_COOLING_DOWN"}
FORECAST_REFIT_EVERY = 21                               # meals (one week)


def phase_of(t: int, config: dict) -> str:
    s = config["semester"]
    week = t // (s["days_per_week"] * len(s["meal_types"]))
    if week < s["baseline_weeks"]:
        return "BASELINE"
    if week < s["baseline_weeks"] + s["warmup_weeks"]:
        return "WARMUP"
    return "ADAPTIVE"


def _realised_reward(sim, t, spec, cand, reference, config):
    obs, _ = sim.peek(t, cand["ordering"])
    status, m = compute_metrics(spec["meal"], spec["menu_items"], obs, reference, config)
    return m["reward"] if status == "OK" else None


def _with_label(candidates, label):
    return next(c for c in candidates if label in c["generating_labels"])


def run_semester(seed: int, scenario: str, strategy: str, config: dict, compute_regret: bool = True) -> list[dict]:
    if strategy not in STRATEGIES:
        raise ValueError(f"unknown strategy {strategy}")
    sim = MessSimulator(seed, scenario, config)
    window = config["features"]["exposure_window_meals"]
    hist_len = config["safety"]["waste_sustained_window"] - 1
    is_linucb = strategy.startswith("LINUCB")
    safe = strategy in SAFE
    reference, baseline, learner_log, exposure = None, [], [], []
    history = defaultdict(list)          # meal_type -> evaluated entries
    cooldowns = {}                       # (label, meal_type) -> remaining occurrences
    force_p0 = set()                     # meal types whose next occurrence must be P0
    ucb1 = UCB1Labels(clip=config["reward"]["clip"])
    warmup_counts = defaultdict(int)     # meal_type -> warm-up meals of that type so far
    forecaster = Forecaster("RANDOM_FOREST", seed=seed) if is_linucb else None
    forecast_history, last_fit = [], None
    records = []

    for t in range(sim.n_meals):
        spec = sim.meal_spec(t)
        meal, mt, phase = spec["meal"], spec["meal"]["meal_type"], phase_of(t, config)

        # ---- neutral-placement demand forecast (walk-forward, refit weekly)
        forecast = []
        if forecaster is not None and phase != "BASELINE":
            if last_fit is None or t - last_fit >= FORECAST_REFIT_EVERY:
                forecaster.fit(forecast_history)
                last_fit = t
            forecast = forecaster.predict(meal, spec["menu_items"])

        req = {
            "config": config, "phase": phase, "meal": meal, "menu_items": spec["menu_items"], "slots": spec["slots"],
            "forecast": forecast,
            "learner_log": learner_log if is_linucb else [],
            "exposure_history": exposure[-window:],
            "active_cooldowns": [{"policy_label": lbl, "meal_type": m} for (lbl, m), n in cooldowns.items() if n > 0]
            if safe else [],
            "warmup_cursor": warmup_counts[mt],
            "force_p0": (mt in force_p0) if safe else False,
            "catalogue_median_carbon_score": sim.catalogue_median_carbon_score,
        }
        resp = recommend(req)
        cands = resp["candidates"]
        by_hash = {c["ordering_hash"]: c for c in cands}

        # ---- per-candidate realised rewards (for ORACLE and regret); uses simulator ground truth
        cand_rewards = None
        if reference is not None and (compute_regret or strategy == "ORACLE"):
            cand_rewards = [_realised_reward(sim, t, spec, c, reference, config) for c in cands]

        # ---- choose
        if phase == "BASELINE":
            cand = _with_label(cands, "P0")
        elif strategy.startswith("FIXED_"):
            cand = _with_label(cands, strategy.split("_")[1])
        elif strategy == "ORACLE":
            scored = [(r, i) for i, r in enumerate(cand_rewards) if r is not None]
            cand = cands[max(scored)[1]] if scored else _with_label(cands, "P0")
        elif is_linucb or resp["reason_code"] in FORCED or (phase == "WARMUP" and strategy == "UCB1"):
            cand = by_hash[resp["chosen_hash"]]
        elif strategy == "RANDOM":
            available = [c for c in cands if not c["excluded_by_cooldown"]]
            cand = seeded_rng(seed, t, "random-strategy").choice(available)
        else:  # UCB1, adaptive phase
            available = {lbl: c for c in cands if not c["excluded_by_cooldown"] for lbl in c["generating_labels"]}
            scores = ucb1.scores(list(available))
            best = max(scores.values())
            ties = sorted(lbl for lbl, s in scores.items() if s == best)
            cand = available[seeded_rng(seed, t, "ucb1-tie").choice(ties)]

        # ---- deploy and evaluate
        obs, truth = sim.step(t, cand["ordering"])
        if phase == "BASELINE":
            baseline.append({"meal": meal, "menu_items": spec["menu_items"], "observation": obs})
        if forecaster is not None:
            forecast_history.append({"meal": meal, "menu_items": spec["menu_items"],
                                     "deployed_ordering": cand["ordering"], "observation": obs})
        ev = evaluate({
            "config": config, "meal": meal, "menu_items": spec["menu_items"],
            "deployed_candidate": {"ordering_hash": cand["ordering_hash"],
                                   "generating_labels": cand["generating_labels"], "phi": cand["phi"]},
            "observation": obs, "reference_model": reference,
            "same_type_history": history[mt][-hist_len:] if hist_len > 0 else [],
        })
        m = ev["metrics"]

        # ---- safety bookkeeping (safe strategies only; events are still recorded for all)
        if safe and ev["eval_status"] != "EXCLUDED_ZERO_ATTENDANCE":
            for key in cooldowns:
                if key[1] == mt and cooldowns[key] > 0:
                    cooldowns[key] -= 1
            force_p0.discard(mt)
            for e in ev["safety_events"]:
                for lbl in e["cooldown_labels"]:
                    cooldowns[(lbl, mt)] = config["safety"]["cooldown_occurrences"]
            if ev["force_p0_next"]:
                force_p0.add(mt)

        # ---- learning
        if ev["learner_update"]:
            if is_linucb:
                learner_log.append({"meal_id": meal["id"], "phi": cand["phi"], "reward": m["reward"],
                                    "deployed_labels": cand["generating_labels"]})
            elif strategy == "UCB1":
                for lbl in cand["generating_labels"]:
                    ucb1.update(lbl, m["reward"])
        if m["log_waste"] is not None:
            history[mt].append({"meal_id": meal["id"], "log_waste": m["log_waste"],
                                "eligible_uptake": m["eligible_uptake"], "uptake_ref": m["uptake_ref"],
                                "deployed_labels": cand["generating_labels"]})
        exposure.append(cand["d"])
        if phase == "WARMUP":
            warmup_counts[mt] += 1

        # ---- fit the frozen reference at the end of the baseline
        if phase == "BASELINE" and (t + 1 >= sim.n_meals or phase_of(t + 1, config) != "BASELINE"):
            fit = fit_reference(baseline, config, f"ref-{scenario}-{seed}")
            if fit["status"] != "FITTED":
                raise RuntimeError(f"reference fit failed: {fit['missing_coverage']}")
            reference = fit["reference_model"]

        regret = None
        if cand_rewards is not None and m["reward"] is not None:
            valid = [r for r in cand_rewards if r is not None]
            regret = max(valid) - m["reward"] if valid else None
        records.append({
            "t": t, "meal_type": mt, "phase": phase, "labels": "+".join(cand["generating_labels"]),
            "d": cand["d"], "attendance": obs["actual_attendance"],
            "carbon": m["carbon_per_attendee"], "waste_g": m["waste_g_per_attendee"],
            "uptake": m["eligible_uptake"], "reward": m["reward"], "regret": regret,
            "pred_lowcarbon_share": cand["phi"][8],
            "n_events": len(ev["safety_events"]),
            "rollback": safe and any(e["action"] == "ROLLBACK" for e in ev["safety_events"]),
            "dissatisfaction": truth["dissatisfaction"],      # ground truth: evaluation only
            "opt_out_rate": truth["opt_out_rate"],            # ground truth: evaluation only
        })
    return records


def _mean(values):
    vals = [v for v in values if v is not None]
    return sum(vals) / len(vals) if vals else None


def summarise(records: list[dict]) -> dict:
    """Averages over everything after the baseline (warm-up included, so its cost counts)."""
    rows = [r for r in records if r["phase"] != "BASELINE"]
    out = {
        "carbon": _mean(r["carbon"] for r in rows),
        "waste_g": _mean(r["waste_g"] for r in rows),
        "uptake": _mean(r["uptake"] for r in rows),
        "reward": _mean(r["reward"] for r in rows),
        "dissatisfaction": _mean(r["dissatisfaction"] for r in rows),
        "opt_out_rate": _mean(r["opt_out_rate"] for r in rows),
        "regret_total": sum(r["regret"] for r in rows if r["regret"] is not None),
        "rollbacks": sum(r["rollback"] for r in rows),
        "safety_events": sum(r["n_events"] for r in rows),
    }
    for lbl in ("P0", "P1", "P2", "P3"):
        out[f"share_{lbl}"] = sum(lbl in r["labels"].split("+") for r in rows) / len(rows) if rows else 0.0
    return out