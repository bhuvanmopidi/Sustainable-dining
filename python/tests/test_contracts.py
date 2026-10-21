"""M0 contract tests.

Positive: every sample validates.
Negative: each deliberately broken payload is rejected, proving the checks are not vacuous.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import validate_contracts as vc  # noqa: E402


class ContractTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = vc.build_registry()
        cls.config = vc.load_json(vc.CONFIG_PATH)
        cls.fixtures = vc.load_json(vc.SAMPLE_DIR / "_fixtures.json")

    def sample(self, filename):
        _, req, resp = vc.load_sample(vc.SAMPLE_DIR / filename, self.config, self.fixtures)
        return req, resp

    def assertRejected(self, schema, req, resp, fragment):
        errs = vc.validate_payload(self.registry, schema, req, resp)
        self.assertTrue(errs, "payload was accepted but should have been rejected")
        self.assertTrue(any(fragment in e for e in errs), f"expected an error mentioning {fragment!r}, got {errs}")


class TestPositive(ContractTestBase):
    def test_runner_reports_success(self):
        self.assertEqual(vc.run(), 0)

    def test_feature_schema_has_16_dimensions(self):
        fs = vc.load_json(vc.FEATURE_SCHEMA_PATH)
        self.assertEqual(fs["dimension"], 16)
        self.assertEqual([f["name"] for f in fs["features"]][:3], ["intercept", "is_lunch", "is_dinner"])

    def test_meal_counts_from_config(self):
        s = self.config["semester"]
        meals_per_week = s["days_per_week"] * len(s["meal_types"])
        self.assertEqual(s["weeks"] * meals_per_week, 336)
        self.assertEqual(s["baseline_weeks"] * meals_per_week, 42)
        adaptive = (s["weeks"] - s["baseline_weeks"] - s["warmup_weeks"]) * meals_per_week
        self.assertEqual(adaptive, 273)


class TestSchemaRejections(ContractTestBase):
    def test_simulator_cannot_leak_ground_truth(self):
        req, resp = self.sample("simulate_outcome.sample.json")
        resp["latent_preferences"] = {"dal": 0.4}
        self.assertRejected("simulate_outcome", req, resp, "Additional properties")

    def test_phi_must_have_16_entries(self):
        req, resp = self.sample("recommend.sample.json")
        resp["candidates"][0]["phi"] = resp["candidates"][0]["phi"][:15]
        self.assertRejected("recommend", req, resp, "is too short")

    def test_p0_can_never_be_cooled_down(self):
        req, resp = self.sample("evaluate_rollback.sample.json")
        resp["safety_events"][0]["cooldown_labels"] = ["P0", "P2"]
        self.assertRejected("evaluate", req, resp, "'P0' is not one of")

    def test_negative_waste_rejected(self):
        req, resp = self.sample("evaluate.sample.json")
        req["observation"]["aggregate_waste_g"] = -5
        self.assertRejected("evaluate", req, resp, "less than the minimum")

    def test_unknown_meal_type_rejected(self):
        req, resp = self.sample("recommend.sample.json")
        req["meal"]["meal_type"] = "SNACK"
        self.assertRejected("recommend", req, resp, "'SNACK' is not one of")

    def test_config_change_without_new_field_rejected(self):
        req, resp = self.sample("recommend.sample.json")
        req["config"]["learner"]["temperature"] = 0.5
        self.assertRejected("recommend", req, resp, "Additional properties")


class TestSemanticRejections(ContractTestBase):
    def test_wrong_ordering_hash(self):
        req, resp = self.sample("recommend.sample.json")
        resp["candidates"][1]["ordering_hash"] = "0" * 64
        self.assertRejected("recommend", req, resp, "ordering_hash does not match")

    def test_duplicate_layouts_mean_dedup_failed(self):
        req, resp = self.sample("recommend.sample.json")
        resp["candidates"].append(copy.deepcopy(resp["candidates"][1]))
        self.assertRejected("recommend", req, resp, "dedup failed")

    def test_phi_inconsistent_with_d(self):
        req, resp = self.sample("recommend.sample.json")
        resp["candidates"][1]["d"] = 0.9
        self.assertRejected("recommend", req, resp, "inconsistent with d/m")

    def test_p0_layout_must_have_zero_displacement(self):
        req, resp = self.sample("recommend.sample.json")
        c = resp["candidates"][0]
        c["m"] = 0.4
        c["phi"][12] = 0.4
        self.assertRejected("recommend", req, resp, "P0 layout must have d = 0")

    def test_context_must_match_across_candidates(self):
        req, resp = self.sample("recommend.sample.json")
        resp["candidates"][1]["phi"][4] = 0.99
        self.assertRejected("recommend", req, resp, "identical across candidates")

    def test_force_p0_must_be_respected(self):
        req, resp = self.sample("recommend.sample.json")
        req["force_p0"] = True
        self.assertRejected("recommend", req, resp, "force_p0 was set")

    def test_reward_must_be_clipped(self):
        req, resp = self.sample("evaluate_rollback.sample.json")
        resp["metrics"]["reward"] = -3.3904
        self.assertRejected("evaluate", req, resp, "less than the minimum")

    def test_reward_formula(self):
        req, resp = self.sample("evaluate.sample.json")
        resp["metrics"]["reward_unclipped"] = 0.9
        resp["metrics"]["reward"] = 0.9
        self.assertRejected("evaluate", req, resp, "z_c - lambda_w * z_w")

    def test_rollback_requires_force_p0_next(self):
        req, resp = self.sample("evaluate_rollback.sample.json")
        resp["force_p0_next"] = False
        self.assertRejected("evaluate", req, resp, "force_p0_next must be true")

    def test_p0_deployment_cannot_trigger_rollback(self):
        req, resp = self.sample("evaluate_rollback.sample.json")
        req["deployed_candidate"]["generating_labels"] = ["P0"]
        resp["safety_events"][0]["cooldown_labels"] = []
        self.assertRejected("evaluate", req, resp, "never ROLLBACK")

    def test_excluded_meal_cannot_update_learner(self):
        req, resp = self.sample("evaluate.sample.json")
        resp["eval_status"] = "EXCLUDED_ZERO_ATTENDANCE"
        self.assertRejected("evaluate", req, resp, "learner_update must be false")

    def test_forecast_history_cannot_contain_future_meals(self):
        req, resp = self.sample("forecast.sample.json")
        req["history"][0]["meal"]["date"] = "2026-10-15"
        self.assertRejected("forecast", req, resp, "future data leak")

    def test_expected_attendance_cannot_exceed_enrolled(self):
        req, resp = self.sample("recommend.sample.json")
        req["meal"]["expected_attendance"] = 1200
        self.assertRejected("recommend", req, resp, "exceeds enrolled")


class TestConfigRules(ContractTestBase):
    def test_overlapping_seed_ranges_rejected(self):
        cfg = copy.deepcopy(self.config)
        cfg["evaluation"]["seeds"]["validation"] = [20, 99]
        self.assertTrue(any("must not overlap" in e for e in vc.check_config(cfg)))

    def test_non_increasing_alphas_rejected(self):
        cfg = copy.deepcopy(self.config)
        cfg["layout"]["policy_alphas"]["P2"] = 0.2
        self.assertTrue(any("strictly increasing" in e for e in vc.check_config(cfg)))


if __name__ == "__main__":
    unittest.main()
