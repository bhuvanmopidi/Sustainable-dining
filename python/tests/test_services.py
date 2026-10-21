"""M5 tests: both HTTP services, plus an end-to-end baseline run through them.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "tools"))

from fastapi.testclient import TestClient  # noqa: E402

import validate_contracts as vc  # noqa: E402
from ml_service.app import app as ml_app  # noqa: E402
from sim_service.app import app as sim_app  # noqa: E402

CONFIG = vc.load_json(vc.CONFIG_PATH)
FIXTURES = vc.load_json(vc.SAMPLE_DIR / "_fixtures.json")
REGISTRY = vc.build_registry()
ml, sim = TestClient(ml_app), TestClient(sim_app)


def sample_request(name):
    _, req, _ = vc.load_sample(vc.SAMPLE_DIR / name, CONFIG, FIXTURES)
    return req


class TestMLService(unittest.TestCase):
    def test_health(self):
        r = ml.get("/health")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["feature_schema_version"], "v1")

    def test_recommend_sample(self):
        req = sample_request("recommend.sample.json")
        r = ml.post("/recommend", json=req)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(vc.validate_payload(REGISTRY, "recommend", req, r.json()), [])

    def test_evaluate_samples(self):
        for name, rollback in (("evaluate.sample.json", False), ("evaluate_rollback.sample.json", True)):
            req = sample_request(name)
            r = ml.post("/evaluate", json=req)
            self.assertEqual(r.status_code, 200, r.text)
            self.assertEqual(vc.validate_payload(REGISTRY, "evaluate", req, r.json()), [])
            self.assertEqual(r.json()["force_p0_next"], rollback)

    def test_reference_fit_rejects_insufficient_coverage(self):
        r = ml.post("/reference/fit", json=sample_request("reference_fit.sample.json"))
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json()["status"], "REJECTED_INSUFFICIENT_COVERAGE")

    def test_forecast_sample(self):
        req = sample_request("forecast.sample.json")
        r = ml.post("/forecast", json=req)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(vc.validate_payload(REGISTRY, "forecast", req, r.json()), [])

    def test_invalid_request_is_422_with_details(self):
        req = sample_request("recommend.sample.json")
        req["meal"]["meal_type"] = "SNACK"
        r = ml.post("/recommend", json=req)
        self.assertEqual(r.status_code, 422)
        self.assertEqual(r.json()["error"], "INVALID_REQUEST")
        self.assertTrue(any("meal_type" in d["path"] for d in r.json()["details"]))

    def test_future_forecast_history_is_rejected(self):
        req = sample_request("forecast.sample.json")
        req["history"][0]["meal"]["date"] = "2026-10-15"
        r = ml.post("/forecast", json=req)
        self.assertEqual(r.status_code, 422)
        self.assertEqual(r.json()["error"], "REJECTED")


class TestSimService(unittest.TestCase):
    SEED, SCENARIO = 1, "FAILED_SUBSTITUTION"

    def setUp(self):
        sim.post("/simulate/reset", json={"seed": self.SEED, "scenario": self.SCENARIO})

    def spec(self, t):
        r = sim.post("/simulate/meal-spec", json={"seed": self.SEED, "scenario": self.SCENARIO, "meal_index": t})
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def outcome(self, t, spec, ordering=None):
        if ordering is None:
            ordering = [m["id"] for m in sorted(spec["menu_items"], key=lambda m: m["standard_slot"])
                        if m["role"] == "ELIGIBLE"]
        return sim.post("/simulate/outcome", json={"seed": self.SEED, "scenario": self.SCENARIO, "meal_index": t,
                                                   "meal": spec["meal"], "menu_items": spec["menu_items"],
                                                   "deployed_ordering": ordering})

    def test_outcome_is_a_plain_observation(self):
        r = self.outcome(0, self.spec(0))
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(set(r.json()), {"actual_attendance", "aggregate_waste_g", "item_outcomes"})

    def test_meals_must_be_stepped_in_order(self):
        r = self.outcome(1, self.spec(1))
        self.assertEqual(r.status_code, 409)
        self.assertEqual(r.json()["error"], "OUT_OF_ORDER")

    def test_mismatched_meal_is_rejected(self):
        spec = copy.deepcopy(self.spec(0))
        spec["meal"]["id"] = "11111111-1111-4111-8111-000000000999"
        self.assertEqual(self.outcome(0, spec).status_code, 422)

    def test_bad_ordering_is_rejected(self):
        spec = self.spec(0)
        self.assertEqual(self.outcome(0, spec, ordering=[]).status_code, 422)


class TestEndToEnd(unittest.TestCase):
    """The demo loop over HTTP: meal-spec -> recommend -> outcome -> evaluate for the baseline, then reference fit."""

    def test_baseline_through_services(self):
        seed, scenario = 7, "SUCCESSFUL_NUDGING"
        sim.post("/simulate/reset", json={"seed": seed, "scenario": scenario})
        baseline = []
        for t in range(42):
            spec = sim.post("/simulate/meal-spec", json={"seed": seed, "scenario": scenario, "meal_index": t}).json()
            rec = ml.post("/recommend", json={
                "config": CONFIG, "phase": "BASELINE", "meal": spec["meal"], "menu_items": spec["menu_items"],
                "slots": spec["slots"], "forecast": [], "learner_log": [], "exposure_history": [],
                "active_cooldowns": [], "warmup_cursor": 0, "force_p0": False,
                "catalogue_median_carbon_score": spec["catalogue_median_carbon_score"]})
            self.assertEqual(rec.status_code, 200, rec.text)
            rec = rec.json()
            chosen = next(c for c in rec["candidates"] if c["ordering_hash"] == rec["chosen_hash"])
            self.assertIn("P0", chosen["generating_labels"])
            obs = sim.post("/simulate/outcome", json={
                "seed": seed, "scenario": scenario, "meal_index": t, "meal": spec["meal"],
                "menu_items": spec["menu_items"], "deployed_ordering": chosen["ordering"]})
            self.assertEqual(obs.status_code, 200, obs.text)
            ev = ml.post("/evaluate", json={
                "config": CONFIG, "meal": spec["meal"], "menu_items": spec["menu_items"],
                "deployed_candidate": {"ordering_hash": chosen["ordering_hash"],
                                       "generating_labels": chosen["generating_labels"], "phi": chosen["phi"]},
                "observation": obs.json(), "reference_model": None, "same_type_history": []})
            self.assertEqual(ev.status_code, 200, ev.text)
            self.assertFalse(ev.json()["learner_update"])
            baseline.append({"meal": spec["meal"], "menu_items": spec["menu_items"], "observation": obs.json()})

        fit1 = ml.post("/reference/fit", json={"config": CONFIG, "baseline_meals": baseline})
        fit2 = ml.post("/reference/fit", json={"config": CONFIG, "baseline_meals": baseline})
        self.assertEqual(fit1.status_code, 200, fit1.text)
        self.assertEqual(fit1.json()["status"], "FITTED")
        self.assertEqual(fit1.json()["reference_model"]["version"], fit2.json()["reference_model"]["version"])


if __name__ == "__main__":
    unittest.main()