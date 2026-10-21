"""M2 tests for the simulator.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "tools"))

import validate_contracts as vc  # noqa: E402
from core.layout import policy_ordering  # noqa: E402
from core.metrics import observed_metrics  # noqa: E402
from core.reference import fit_reference  # noqa: E402
from simulator.mess import MessSimulator  # noqa: E402

CONFIG = vc.load_json(vc.CONFIG_PATH)
REGISTRY = vc.build_registry()
N = 63  # three weeks of meals is enough for directional checks


def ordering(sim, t, alpha):
    spec = sim.meal_spec(t)
    return policy_ordering(spec["menu_items"], spec["slots"], alpha)


def run(scenario, alpha, n=N, seed=100, meal_type=None):
    """Deploy the same policy every meal; return per-meal observed metrics."""
    sim = MessSimulator(seed, scenario, CONFIG)
    out = []
    for t in range(n):
        obs, _ = sim.step(t, ordering(sim, t, alpha))
        spec = sim.meal_spec(t)
        if meal_type is None or spec["meal"]["meal_type"] == meal_type:
            out.append(observed_metrics(spec["menu_items"], obs))
    return out


def mean(rows, key):
    return sum(r[key] for r in rows) / len(rows)


class TestReproducibility(unittest.TestCase):
    def test_same_seed_same_outcomes(self):
        a, b = MessSimulator(7, "FAILED_SUBSTITUTION", CONFIG), MessSimulator(7, "FAILED_SUBSTITUTION", CONFIG)
        for t in range(30):
            self.assertEqual(a.step(t, ordering(a, t, 1.0)), b.step(t, ordering(b, t, 1.0)))

    def test_different_seeds_differ(self):
        a, b = MessSimulator(1, "SUCCESSFUL_NUDGING", CONFIG), MessSimulator(2, "SUCCESSFUL_NUDGING", CONFIG)
        self.assertNotEqual(a.step(0, ordering(a, 0, 0.0))[0], b.step(0, ordering(b, 0, 0.0))[0])

    def test_meals_must_be_stepped_in_order(self):
        sim = MessSimulator(1, "SUCCESSFUL_NUDGING", CONFIG)
        with self.assertRaises(ValueError):
            sim.step(1, ordering(sim, 1, 0.0))


class TestMatchedConditions(unittest.TestCase):
    def test_exogenous_conditions_do_not_depend_on_policy(self):
        a, b = MessSimulator(5, "AVOIDANCE_STRESS", CONFIG), MessSimulator(5, "AVOIDANCE_STRESS", CONFIG)
        for t in range(60):
            self.assertEqual(a.meal_spec(t), b.meal_spec(t))
            oa, _ = a.step(t, ordering(a, t, 0.0))
            ob, _ = b.step(t, ordering(b, t, 1.0))
            self.assertEqual(oa["actual_attendance"], ob["actual_attendance"])
            fixed = {m["id"] for m in a.meal_spec(t)["menu_items"] if m["role"] == "FIXED"}
            fa = [o for o in oa["item_outcomes"] if o["menu_item_id"] in fixed]
            fb = [o for o in ob["item_outcomes"] if o["menu_item_id"] in fixed]
            self.assertEqual(fa, fb)

    def test_no_placement_effect_gives_identical_outcomes(self):
        sim = MessSimulator(3, "NO_PLACEMENT_EFFECT", CONFIG)
        for t in range(N):
            self.assertEqual(sim.peek(t, ordering(sim, t, 0.0))[0], sim.peek(t, ordering(sim, t, 1.0))[0])

    def test_peek_does_not_change_state(self):
        sim = MessSimulator(3, "NUDGE_FATIGUE", CONFIG)
        before = sim.peek(0, ordering(sim, 0, 1.0))
        sim.peek(0, ordering(sim, 0, 1.0))
        self.assertEqual(sim.peek(0, ordering(sim, 0, 1.0)), before)
        self.assertEqual(sim.exposure, 0.0)


class TestScenarioBehaviour(unittest.TestCase):
    def test_successful_nudging_cuts_carbon_without_much_waste(self):
        p0, p3 = run("SUCCESSFUL_NUDGING", 0.0), run("SUCCESSFUL_NUDGING", 1.0)
        self.assertLess(mean(p3, "carbon_per_attendee"), mean(p0, "carbon_per_attendee"))
        self.assertLess(mean(p3, "waste_g_per_attendee"), 1.10 * mean(p0, "waste_g_per_attendee"))

    def test_failed_substitution_raises_waste(self):
        p0, p3 = run("FAILED_SUBSTITUTION", 0.0), run("FAILED_SUBSTITUTION", 1.0)
        self.assertGreater(mean(p3, "waste_g_per_attendee"), mean(p0, "waste_g_per_attendee"))

    def test_avoidance_lowers_uptake(self):
        p0, p3 = run("AVOIDANCE_STRESS", 0.0), run("AVOIDANCE_STRESS", 1.0)
        self.assertLess(mean(p3, "eligible_uptake"), 0.97 * mean(p0, "eligible_uptake"))

    def test_context_dependent_waste_is_worse_at_dinner(self):
        gap = {}
        for mt in ("LUNCH", "DINNER"):
            p0 = run("CONTEXT_DEPENDENT", 0.0, meal_type=mt)
            p3 = run("CONTEXT_DEPENDENT", 1.0, meal_type=mt)
            gap[mt] = mean(p3, "waste_g_per_attendee") - mean(p0, "waste_g_per_attendee")
        self.assertGreater(gap["DINNER"], gap["LUNCH"])

    def test_fatigue_weakens_placement_effect(self):
        sim = MessSimulator(4, "NUDGE_FATIGUE", CONFIG)
        first = sim.peek(0, ordering(sim, 0, 1.0))[1]["beta_eff"]
        for t in range(15):
            sim.step(t, ordering(sim, t, 1.0))
        self.assertLess(sim.peek(15, ordering(sim, 15, 1.0))[1]["beta_eff"], first)


class TestContractsAndLeakage(unittest.TestCase):
    def test_observation_matches_contract_and_hides_ground_truth(self):
        sim = MessSimulator(9, "FAILED_SUBSTITUTION", CONFIG)
        validator = vc.validator_for(REGISTRY, "simulate_outcome", "response")
        menu_validator = vc.validator_for(REGISTRY, "common", "menu_item")
        for t in range(9):
            obs, truth = sim.step(t, ordering(sim, t, 1.0))
            self.assertEqual(vc.schema_errors(validator, obs), [])
            for m in sim.meal_spec(t)["menu_items"]:
                self.assertEqual(vc.schema_errors(menu_validator, m), [])
            self.assertTrue(set(truth).isdisjoint(obs))
            self.assertNotIn("preference", str(sim.meal_spec(t)))

    def test_baseline_supports_reference_fit(self):
        sim = MessSimulator(11, "SUCCESSFUL_NUDGING", CONFIG)
        baseline = []
        for t in range(42):
            spec = sim.meal_spec(t)
            obs, _ = sim.step(t, ordering(sim, t, 0.0))
            baseline.append({"meal": spec["meal"], "menu_items": spec["menu_items"], "observation": obs})
        resp = fit_reference(baseline, CONFIG, "sim-ref")
        self.assertEqual(resp["status"], "FITTED", resp.get("missing_coverage"))

    def test_ml_service_never_imports_simulator(self):
        for path in (ROOT / "python" / "ml_service").rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("import simulator", text, path)
            self.assertNotIn("from simulator", text, path)


if __name__ == "__main__":
    unittest.main()