"""M4 tests for the demand forecaster.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import json
import random
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "tools"))

import validate_contracts as vc  # noqa: E402
from core.forecast import Forecaster, forecast  # noqa: E402
from core.layout import candidate_layouts  # noqa: E402
from eval_harness.runner import run_semester  # noqa: E402
from simulator.mess import MessSimulator  # noqa: E402

CONFIG = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
REGISTRY = vc.build_registry()


def simulated_history(n, seed=3, scenario="SUCCESSFUL_NUDGING"):
    """P0 for the baseline, then random distinct layouts, so placement varies in the training data."""
    sim, rng, hist = MessSimulator(seed, scenario, CONFIG), random.Random(seed), []
    for t in range(n):
        spec = sim.meal_spec(t)
        cands = candidate_layouts(spec["menu_items"], spec["slots"], CONFIG["layout"]["policy_alphas"])
        cand = cands[0] if t < 42 else rng.choice(cands)
        obs, _ = sim.step(t, cand["ordering"])
        hist.append({"meal": spec["meal"], "menu_items": spec["menu_items"],
                     "deployed_ordering": cand["ordering"], "observation": obs})
    return hist


HIST = simulated_history(64)


def request(model, history, target):
    return {"model": model, "history": history, "target_meal": target["meal"], "target_menu_items": target["menu_items"]}


class TestForecast(unittest.TestCase):
    def test_contract_valid_and_deterministic(self):
        for model in ("RANDOM_FOREST", "SEASONAL_NAIVE"):
            req = request(model, HIST[:63], HIST[63])
            resp = forecast(req)
            self.assertEqual(vc.validate_payload(REGISTRY, "forecast", req, resp), [])
            self.assertEqual(resp, forecast(req))

    def test_future_history_is_rejected(self):
        with self.assertRaises(ValueError):
            forecast(request("RANDOM_FOREST", HIST[:64], HIST[63]))

    def test_predict_refuses_meals_inside_training_window(self):
        fc = Forecaster("RANDOM_FOREST").fit(HIST[:63])
        with self.assertRaises(ValueError):
            fc.predict(HIST[10]["meal"], HIST[10]["menu_items"])

    def test_naive_uses_most_recent_occurrence(self):
        fc = Forecaster("SEASONAL_NAIVE").fit(HIST[:63])
        target = HIST[63]
        preds = {p["menu_item_id"]: p["predicted_per_100"] for p in fc.predict(target["meal"], target["menu_items"])}
        mt = target["meal"]["meal_type"]
        for item in target["menu_items"]:
            past = [h for h in HIST[:63] if h["meal"]["meal_type"] == mt
                    and any(m["dish_id"] == item["dish_id"] for m in h["menu_items"])]
            if not past:
                continue
            h = past[-1]
            mid = next(m["id"] for m in h["menu_items"] if m["dish_id"] == item["dish_id"])
            portions = next(o["portions_taken"] for o in h["observation"]["item_outcomes"] if o["menu_item_id"] == mid)
            self.assertAlmostEqual(preds[item["id"]], 100 * portions / h["observation"]["actual_attendance"])

    def test_forest_falls_back_with_little_data(self):
        fc = Forecaster("RANDOM_FOREST").fit(HIST[:3])
        self.assertIsNone(fc.forest)
        self.assertEqual(len(fc.predict(HIST[3]["meal"], HIST[3]["menu_items"])), len(HIST[3]["menu_items"]))

    def test_harness_fills_forecast_feature_for_linucb(self):
        rows = run_semester(100, "SUCCESSFUL_NUDGING", "LINUCB", CONFIG, compute_regret=False)
        self.assertTrue(all(r["pred_lowcarbon_share"] == 0 for r in rows[:42]))
        self.assertTrue(any(r["pred_lowcarbon_share"] > 0 for r in rows[42:]))


if __name__ == "__main__":
    unittest.main()