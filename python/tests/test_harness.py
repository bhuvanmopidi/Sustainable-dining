"""M3 tests for the evaluation harness. Each test simulates full semesters, so this file takes ~1 minute.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from eval_harness.experiment import add_best_fixed, paired_ci  # noqa: E402
from eval_harness.runner import run_semester, summarise  # noqa: E402

CONFIG = json.loads((ROOT / "config" / "experiment_config.v1.json").read_text(encoding="utf-8"))
_cache = {}


def run(scenario, strategy, seed=100):
    key = (scenario, strategy, seed)
    if key not in _cache:
        _cache[key] = run_semester(seed, scenario, strategy, CONFIG)
    return _cache[key]


class TestHarness(unittest.TestCase):
    def test_reproducible(self):
        a = run_semester(101, "SUCCESSFUL_NUDGING", "LINUCB", CONFIG, compute_regret=False)
        b = run_semester(101, "SUCCESSFUL_NUDGING", "LINUCB", CONFIG, compute_regret=False)
        self.assertEqual(a, b)

    def test_baseline_identical_across_strategies(self):
        a, b = run("SUCCESSFUL_NUDGING", "FIXED_P3"), run("SUCCESSFUL_NUDGING", "LINUCB")
        self.assertEqual(a[:42], b[:42])
        self.assertTrue(all(r["labels"].startswith("P0") for r in a[:42]))

    def test_null_scenario_has_zero_carbon_and_waste_difference(self):
        a = summarise(run("NO_PLACEMENT_EFFECT", "LINUCB"))
        b = summarise(run("NO_PLACEMENT_EFFECT", "FIXED_P0"))
        self.assertAlmostEqual(a["carbon"], b["carbon"], places=9)
        self.assertAlmostEqual(a["waste_g"], b["waste_g"], places=9)

    def test_fixed_strategy_never_changes_after_baseline(self):
        rows = run("FAILED_SUBSTITUTION", "FIXED_P3")
        self.assertTrue(all("P3" in r["labels"].split("+") for r in rows[42:]))

    def test_oracle_dominates_every_meal_without_fatigue(self):
        oracle, linucb = run("SUCCESSFUL_NUDGING", "ORACLE"), run("SUCCESSFUL_NUDGING", "LINUCB")
        for o, l in zip(oracle[42:], linucb[42:]):
            if o["reward"] is not None and l["reward"] is not None:
                self.assertGreaterEqual(o["reward"], l["reward"] - 1e-9)

    def test_regret_is_non_negative(self):
        for r in run("SUCCESSFUL_NUDGING", "LINUCB")[42:]:
            if r["regret"] is not None:
                self.assertGreaterEqual(r["regret"], -1e-9)

    def test_rollback_forces_p0_at_next_same_meal_type(self):
        rows = run("FAILED_SUBSTITUTION", "RANDOM")
        rollbacks = [r for r in rows if r["rollback"]]
        self.assertTrue(rollbacks, "expected at least one rollback under failed substitution")
        for r in rollbacks:
            nxt = next((x for x in rows[r["t"] + 1:] if x["meal_type"] == r["meal_type"]), None)
            if nxt is not None:
                self.assertIn("P0", nxt["labels"].split("+"))

    def test_summary_shares_sum_to_at_least_one(self):
        s = summarise(run("SUCCESSFUL_NUDGING", "LINUCB"))
        self.assertGreaterEqual(sum(s[f"share_P{k}"] for k in range(4)), 1.0 - 1e-9)

    def test_paired_ci(self):
        mean, lo, hi = paired_ci([1.0, 2.0, 3.0, 4.0])
        self.assertAlmostEqual(mean, 2.5)
        self.assertLess(lo, mean)
        self.assertGreater(hi, mean)

    def test_best_fixed_picks_highest_reward(self):
        rows = [{"seed": 1, "scenario": "S", "lambda_w": 1.0, "strategy": f"FIXED_P{k}", "reward": r}
                for k, r in enumerate([0.1, 0.4, 0.2, -0.3])]
        best = [r for r in add_best_fixed(rows) if r["strategy"] == "BEST_FIXED"]
        self.assertEqual(len(best), 1)
        self.assertEqual(best[0]["reward"], 0.4)


if __name__ == "__main__":
    unittest.main()