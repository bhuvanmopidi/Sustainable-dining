"""Walk-forward forecast accuracy against the simulator's TRUE neutral demand (eligible items only).
Tuning seeds 0-4; P0 during the baseline, random layouts afterwards. Weekly refit."""
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from core.forecast import Forecaster  # noqa: E402
from core.layout import candidate_layouts  # noqa: E402
from simulator.mess import MessSimulator  # noqa: E402

cfg = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
REFIT, MODELS = 21, ("RANDOM_FOREST", "SEASONAL_NAIVE")

for scenario in ("NO_PLACEMENT_EFFECT", "SUCCESSFUL_NUDGING", "FAILED_SUBSTITUTION", "CHANGING_PREFERENCES"):
    err = {m: [0.0, 0.0] for m in MODELS}           # [sum |error|, sum truth]
    for seed in range(5):
        sim, rng, hist = MessSimulator(seed, scenario, cfg), random.Random(seed), []
        models = {m: Forecaster(m, seed) for m in MODELS}
        for t in range(sim.n_meals):
            spec = sim.meal_spec(t)
            cands = candidate_layouts(spec["menu_items"], spec["slots"], cfg["layout"]["policy_alphas"])
            p0 = next(c for c in cands if "P0" in c["generating_labels"])
            if t >= 42:
                if (t - 42) % REFIT == 0:
                    for f in models.values():
                        f.fit(hist)
                truth_obs, _ = sim.peek(t, p0["ordering"])           # simulator ground truth: neutral demand
                att = truth_obs["actual_attendance"]
                if att > 0:
                    truth = {o["menu_item_id"]: 100 * o["portions_taken"] / att for o in truth_obs["item_outcomes"]}
                    eligible = {m["id"] for m in spec["menu_items"] if m["role"] == "ELIGIBLE"}
                    for name, f in models.items():
                        for p in f.predict(spec["meal"], spec["menu_items"]):
                            if p["menu_item_id"] in eligible:
                                err[name][0] += abs(p["predicted_per_100"] - truth[p["menu_item_id"]])
                                err[name][1] += truth[p["menu_item_id"]]
            cand = p0 if t < 42 else rng.choice(cands)
            obs, _ = sim.step(t, cand["ordering"])
            hist.append({"meal": spec["meal"], "menu_items": spec["menu_items"],
                         "deployed_ordering": cand["ordering"], "observation": obs})
    print(f"{scenario:<22} " + "  ".join(f"{m}: WAPE {100 * a / b:5.1f}%" for m, (a, b) in err.items()))