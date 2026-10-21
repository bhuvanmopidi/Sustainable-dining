"""M1 tests for python/core.

Run from the repo root:  python -m unittest discover -s python/tests -v
"""
import copy
import math
import random
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "tools"))

import validate_contracts as vc  # noqa: E402
from core.evaluate import evaluate  # noqa: E402
from core.features import context_vector, phi, recent_exposure  # noqa: E402
from core.layout import action_features, candidate_layouts, ordering_hash, policy_ordering  # noqa: E402
from core.learners import SharedLinUCB, UCB1Labels, argmax_seeded, seeded_rng  # noqa: E402
from core.metrics import compute_metrics, reward  # noqa: E402
from core.recommend import recommend  # noqa: E402
from core.reference import fit_reference, predict_reference  # noqa: E402
from core.safety import evaluate_safety  # noqa: E402

CONFIG = vc.load_json(vc.CONFIG_PATH)
FIXTURES = vc.load_json(vc.SAMPLE_DIR / "_fixtures.json")
REGISTRY = vc.build_registry()
ALPHAS = CONFIG["layout"]["policy_alphas"]


def uid(prefix: int, n: int) -> str:
    return f"{prefix:08d}-0000-4000-8000-{n:012d}"


def item(n, carbon, role="ELIGIBLE", slot=None, category=None, factor=None, dish=None):
    return {"id": uid(2, n), "dish_id": dish or uid(3, n), "category": category or f"cat{n}", "role": role,
            "standard_slot": slot or n, "carbon_score": carbon,
            "carbon_factor_snapshot": factor if factor is not None else round(carbon * 2, 4),
            "portion_mass_snapshot": 150}


def slots(n_eligible, n_fixed=0):
    out, vmax, vmin = [], 1.0, 0.2
    for k in range(n_eligible):
        v = vmax if n_eligible == 1 else vmax - (vmax - vmin) * k / (n_eligible - 1)
        out.append({"slot_index": k + 1, "visibility": round(v, 6), "eligible_slot": True})
    for k in range(n_fixed):
        out.append({"slot_index": n_eligible + k + 1, "visibility": 0.5, "eligible_slot": False})
    return out


def sample_recommend_request():
    _, req, _ = vc.load_sample(vc.SAMPLE_DIR / "recommend.sample.json", CONFIG, FIXTURES)
    return req


def sample_evaluate_request(name="evaluate.sample.json"):
    _, req, _ = vc.load_sample(vc.SAMPLE_DIR / name, CONFIG, FIXTURES)
    return req


def assert_valid(test, schema, req, resp):
    errs = vc.validate_payload(REGISTRY, schema, req, resp)
    test.assertEqual(errs, [], f"core output violates the {schema} contract: {errs}")


# =====================================================================  layout
class TestPolicyMapping(unittest.TestCase):
    def setUp(self):
        self.menu = FIXTURES["menu_items"]
        self.slots = sample_recommend_request()["slots"]
        self.chicken, self.paneer, self.dal = (m["id"] for m in self.menu[:3])

    def test_p0_reproduces_standard_order(self):
        self.assertEqual(policy_ordering(self.menu, self.slots, 0.0), [self.chicken, self.paneer, self.dal])

    def test_p3_orders_by_ascending_carbon(self):
        self.assertEqual(policy_ordering(self.menu, self.slots, 1.0), [self.dal, self.paneer, self.chicken])

    def test_equivalent_policies_are_deduplicated(self):
        cands = candidate_layouts(self.menu, self.slots, ALPHAS)
        self.assertEqual([c["generating_labels"] for c in cands], [["P0", "P1"], ["P2", "P3"]])
        self.assertEqual(cands[0]["ordering_hash"], ordering_hash([self.chicken, self.paneer, self.dal]))

    def test_every_label_appears_exactly_once(self):
        cands = candidate_layouts(self.menu, self.slots, ALPHAS)
        self.assertEqual(sorted(lbl for c in cands for lbl in c["generating_labels"]), ["P0", "P1", "P2", "P3"])

    def test_fixed_items_are_never_moved(self):
        for c in candidate_layouts(self.menu, self.slots, ALPHAS):
            self.assertEqual(set(c["ordering"]), {self.chicken, self.paneer, self.dal})


class TestMenuChanges(unittest.TestCase):
    def test_single_eligible_item_gives_one_layout(self):
        cands = candidate_layouts([item(1, 0.7), item(2, 0.1, role="FIXED", slot=2)], slots(1, 1), ALPHAS)
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["generating_labels"], ["P0", "P1", "P2", "P3"])
        self.assertEqual((cands[0]["d"], cands[0]["m"]), (0.0, 0.0))

    def test_no_eligible_items_gives_one_empty_layout(self):
        cands = candidate_layouts([item(1, 0.5, role="FIXED", slot=1)], [{"slot_index": 1, "visibility": 0.5,
                                                                           "eligible_slot": False}], ALPHAS)
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["ordering"], [])

    def test_tied_carbon_keeps_standard_order(self):
        menu = [item(1, 0.4), item(2, 0.4), item(3, 0.4)]
        cands = candidate_layouts(menu, slots(3), ALPHAS)
        self.assertEqual(len(cands), 1)
        self.assertEqual(cands[0]["ordering"], [m["id"] for m in menu])

    def test_four_dish_menu_reaches_full_reversal_at_p3(self):
        menu = [item(1, 0.9), item(2, 0.6), item(3, 0.3), item(4, 0.05)]
        cands = candidate_layouts(menu, slots(4), ALPHAS)
        p3 = next(c for c in cands if "P3" in c["generating_labels"])
        self.assertEqual(p3["ordering"], [m["id"] for m in reversed(menu)])
        self.assertGreaterEqual(len(cands), 2)

    def test_eligible_item_in_fixed_slot_is_rejected(self):
        with self.assertRaises(ValueError):
            candidate_layouts([item(1, 0.5, slot=2)], slots(1, 1), ALPHAS)


# =====================================================================  features
class TestActionFeatures(unittest.TestCase):
    def setUp(self):
        self.menu = FIXTURES["menu_items"]
        self.slots = sample_recommend_request()["slots"]

    def test_p0_has_zero_d_and_m(self):
        ordering = policy_ordering(self.menu, self.slots, 0.0)
        self.assertEqual(action_features(ordering, self.menu, self.slots), (0.0, 0.0))

    def test_p3_matches_hand_calculation(self):
        d, m = action_features(policy_ordering(self.menu, self.slots, 1.0), self.menu, self.slots)
        self.assertAlmostEqual(d, 0.8 * (0.8158 - 0.1579), places=6)
        self.assertAlmostEqual(m, 0.8, places=6)

    def test_promoting_high_carbon_gives_negative_d(self):
        menu = [item(1, 0.1), item(2, 0.9)]  # standard: low-carbon item already most visible
        d, _ = action_features([menu[1]["id"], menu[0]["id"]], menu, slots(2))
        self.assertLess(d, 0)

    def test_d_ignores_a_constant_carbon_shift(self):
        a = [item(1, 0.6), item(2, 0.2)]
        b = [item(1, 0.7), item(2, 0.3)]
        swap = lambda menu: [menu[1]["id"], menu[0]["id"]]  # noqa: E731
        self.assertAlmostEqual(action_features(swap(a), a, slots(2))[0], action_features(swap(b), b, slots(2))[0])


class TestContext(unittest.TestCase):
    def test_context_matches_feature_schema(self):
        req = sample_recommend_request()
        x = context_vector(req["meal"], req["menu_items"], req["slots"], req["forecast"],
                           req["exposure_history"], req["catalogue_median_carbon_score"], 9)
        self.assertEqual(len(x), 10)
        self.assertEqual(x[:4], [1.0, 1.0, 0.0, 0.0])          # intercept, lunch, not dinner, Wednesday
        self.assertAlmostEqual(x[4], 820 / 1000)                # EXPECTED attendance, not actual
        self.assertAlmostEqual(x[5], 1.0)                       # 3 eligible items / 3 eligible slots
        self.assertAlmostEqual(x[8], 33 / (38 + 25 + 33))       # only dal is below the 0.30 median
        self.assertAlmostEqual(x[9], sum(req["exposure_history"]) / 9)

    def test_weekend_flag(self):
        req = sample_recommend_request()
        req["meal"]["date"] = "2026-10-17"  # Saturday
        x = context_vector(req["meal"], req["menu_items"], req["slots"], req["forecast"], [], 0.3, 9)
        self.assertEqual(x[3], 1.0)

    def test_context_cannot_see_actual_attendance(self):
        req = sample_recommend_request()
        x1 = context_vector(req["meal"], req["menu_items"], req["slots"], req["forecast"], [], 0.3, 9)
        meal = dict(req["meal"], actual_attendance=100)  # even if a caller leaked it, it is ignored
        x2 = context_vector(meal, req["menu_items"], req["slots"], req["forecast"], [], 0.3, 9)
        self.assertEqual(x1, x2)

    def test_exposure_uses_only_last_window(self):
        self.assertAlmostEqual(recent_exposure([5.0] + [1.0] * 9, 9), 1.0)
        self.assertEqual(recent_exposure([], 9), 0.0)

    def test_phi_interactions(self):
        x = [1, 0, 1, 0, 0.8, 1, 0.5, 0.4, 0.3, 0.2]
        p = phi(x, 0.5, 0.6)
        self.assertEqual(len(p), 16)
        self.assertEqual(p[10:], [0.5, 0.25, 0.6, 0.0, 0.5, 0.1])


# =====================================================================  reference
def synthetic_baseline(days=14, noise=0.0, seed=1):
    """42 neutral meals over two weeks with a fixed 3-dish rotation per meal type."""
    rng = random.Random(seed)
    menus = {
        "BREAKFAST": [item(11, 0.10, category="idli", factor=0.20), item(12, 0.40, category="egg", factor=0.80)],
        "LUNCH": [item(1, 0.8158, category="non_veg", factor=1.6), item(2, 0.4474, category="paneer", factor=0.9),
                  item(3, 0.1579, category="dal", factor=0.35)],
        "DINNER": [item(21, 0.70, category="non_veg", factor=1.4), item(22, 0.15, category="dal", factor=0.30)],
    }
    rates = {uid(2, 11): 0.6, uid(2, 12): 0.4, uid(2, 1): 0.39, uid(2, 2): 0.24, uid(2, 3): 0.35,
             uid(2, 21): 0.5, uid(2, 22): 0.45}
    waste_g = {"BREAKFAST": 60.0, "LUNCH": 118.0, "DINNER": 130.0}
    meals = []
    for day in range(days):
        for k, mt in enumerate(("BREAKFAST", "LUNCH", "DINNER")):
            att = 800 + rng.randint(-40, 40)
            menu = menus[mt]
            outcomes = [{"menu_item_id": m["id"],
                         "portions_taken": max(0, round(att * rates[m["id"]] * (1 + rng.uniform(-noise, noise)))),
                         "waste_g": None} for m in menu]
            meals.append({
                "meal": {"id": uid(1, day * 3 + k + 1), "meal_type": mt, "date": f"2026-09-{day + 1:02d}",
                         "expected_attendance": 820, "enrolled": 1000},
                "menu_items": menu,
                "observation": {"actual_attendance": att,
                                "aggregate_waste_g": att * waste_g[mt] * (1 + rng.uniform(-noise, noise)),
                                "item_outcomes": outcomes},
            })
    return meals


class TestReference(unittest.TestCase):
    def test_fit_returns_a_valid_reference(self):
        meals = synthetic_baseline(noise=0.1)
        resp = fit_reference(meals, CONFIG, "ref-test")
        self.assertEqual(resp["status"], "FITTED")
        assert_valid(self, "reference_fit", {"config": CONFIG, "baseline_meals": meals}, resp)

    def test_rates_are_recovered_without_noise(self):
        ref = fit_reference(synthetic_baseline(noise=0.0), CONFIG, "r")["reference_model"]
        dal = next(r for r in ref["params"]["uptake"] if r["dish_id"] == uid(3, 3))
        self.assertAlmostEqual(dal["rate"], 0.35, places=2)
        self.assertAlmostEqual(ref["params"]["w_ref_g"]["LUNCH"], 118.0, places=6)

    def test_insufficient_coverage_is_rejected(self):
        meals = synthetic_baseline()
        meals[1]["menu_items"] = meals[1]["menu_items"] + [item(9, 0.5, category="new")]
        resp = fit_reference(meals, CONFIG, "r")
        self.assertEqual(resp["status"], "REJECTED_INSUFFICIENT_COVERAGE")
        self.assertEqual(resp["missing_coverage"][0]["dish_id"], uid(3, 9))

    def test_scales_come_from_out_of_sample_errors(self):
        ref = fit_reference(synthetic_baseline(noise=0.15), CONFIG, "r")["reference_model"]
        self.assertGreater(ref["s_c"], 1e-3)
        self.assertGreater(ref["s_w"], 1e-3)

    def test_unknown_category_makes_reference_incomplete(self):
        ref = fit_reference(synthetic_baseline(), CONFIG, "r")["reference_model"]
        self.assertIsNone(predict_reference(ref, [item(99, 0.5, category="never_seen")], "LUNCH"))

    def test_new_dish_of_known_category_uses_category_mean(self):
        ref = fit_reference(synthetic_baseline(), CONFIG, "r")["reference_model"]
        new_dal = item(98, 0.15, category="dal", factor=0.3)
        self.assertIsNotNone(predict_reference(ref, [new_dal], "LUNCH"))


# =====================================================================  reward
class TestReward(unittest.TestCase):
    def r(self, c, w):
        return reward(c, w, c_ref=1.5, w_ref=120.0, s_c=0.1, s_w=15.0, lambda_w=1.0, clip=3.0)[3]

    def test_lower_carbon_never_lowers_reward(self):
        for c in (1.6, 1.5, 1.45, 1.3):
            self.assertGreaterEqual(self.r(c - 0.05, 120), self.r(c, 120))

    def test_lower_waste_never_lowers_reward(self):
        for w in (150, 120, 100):
            self.assertGreaterEqual(self.r(1.5, w - 5), self.r(1.5, w))

    def test_reward_is_clipped(self):
        self.assertEqual(self.r(0.0, 120), 3.0)
        self.assertEqual(self.r(1.5, 1000), -3.0)

    def test_lambda_scales_waste_penalty(self):
        r1 = reward(1.5, 135, 1.5, 120, 0.1, 15, 1.0, 3.0)[2]
        r2 = reward(1.5, 135, 1.5, 120, 0.1, 15, 2.0, 3.0)[2]
        self.assertAlmostEqual(r2, 2 * r1)

    def test_evaluate_sample_metrics(self):
        req = sample_evaluate_request()
        status, m = compute_metrics(req["meal"], req["menu_items"], req["observation"], req["reference_model"], CONFIG)
        self.assertEqual(status, "OK")
        self.assertAlmostEqual(m["carbon_per_attendee"], 1173 / 806, places=6)
        self.assertAlmostEqual(m["waste_g_per_attendee"], 97200 / 806, places=6)
        self.assertAlmostEqual(m["c_ref"], 0.39 * 1.6 + 0.24 * 0.9 + 0.35 * 0.35 + 0.94 * 0.25 + 1.82 * 0.15, places=6)
        self.assertAlmostEqual(m["uptake_ref"], 0.98, places=6)

    def test_zero_attendance_is_excluded(self):
        req = sample_evaluate_request()
        req["observation"]["actual_attendance"] = 0
        status, m = compute_metrics(req["meal"], req["menu_items"], req["observation"], req["reference_model"], CONFIG)
        self.assertEqual(status, "EXCLUDED_ZERO_ATTENDANCE")
        self.assertIsNone(m["reward"])
        
    def test_relative_scale_gives_fractions(self):
        req = sample_evaluate_request()
        cfg = copy.deepcopy(CONFIG)
        cfg["reward"]["scale"] = "RELATIVE"
        _, m = compute_metrics(req["meal"], req["menu_items"], req["observation"], req["reference_model"], cfg)
        self.assertAlmostEqual(m["z_c"], (m["c_ref"] - m["carbon_per_attendee"]) / m["c_ref"])
        self.assertAlmostEqual(m["z_w"], (m["waste_g_per_attendee"] - m["w_ref"]) / m["w_ref"])    


# =====================================================================  safety
class TestSafety(unittest.TestCase):
    def setUp(self):
        self.ref = FIXTURES["reference_model"]  # LUNCH: mu 4.78, sigma 0.10
        self.base = {"log_waste": 4.78, "eligible_uptake": 0.98, "uptake_ref": 0.98}

    def events(self, metrics=None, labels=("P2", "P3"), history=(), status="OK"):
        m = dict(self.base, **(metrics or {}))
        return evaluate_safety("LUNCH", status, m, list(labels), list(history), self.ref, CONFIG)

    def hist(self, log_waste=4.78, uptake=0.98, labels=("P0",)):
        return {"log_waste": log_waste, "eligible_uptake": uptake, "uptake_ref": 0.98, "deployed_labels": list(labels)}

    def test_normal_meal_triggers_nothing(self):
        self.assertEqual(self.events(history=[self.hist(), self.hist()]), [])

    def test_spike_triggers_rollback_and_cools_non_neutral_labels(self):
        ev = self.events({"log_waste": 5.09})
        self.assertEqual([(e["rule"], e["action"], e["cooldown_labels"]) for e in ev],
                         [("WASTE_SPIKE", "ROLLBACK", ["P2", "P3"])])

    def test_spike_threshold_is_strict(self):
        self.assertEqual(self.events({"log_waste": 4.78 + 3 * 0.10 - 1e-9}), [])

    def test_p0_spike_is_alert_only_without_cooldown(self):
        ev = self.events({"log_waste": 5.2}, labels=["P0", "P1"])
        self.assertEqual(ev[0]["action"], "ROLLBACK")      # P1 is non-neutral
        ev = self.events({"log_waste": 5.2}, labels=["P0"])
        self.assertEqual((ev[0]["action"], ev[0]["cooldown_labels"]), ("ALERT_ONLY", []))

    def test_sustained_increase_triggers(self):
        threshold = 4.78 + 2 * 0.10 / math.sqrt(3)  # 4.8955
        ev = self.events({"log_waste": 4.91}, history=[self.hist(4.90, labels=["P2"]), self.hist(4.90, labels=["P3"])])
        self.assertEqual(ev[0]["rule"], "WASTE_SUSTAINED")
        self.assertGreater(ev[0]["observed_value"], threshold)
        self.assertEqual(ev[0]["cooldown_labels"], ["P2", "P3"])

    def test_sustained_needs_a_full_window(self):
        self.assertEqual(self.events({"log_waste": 4.95}, history=[self.hist(4.95)]), [])

    def test_uptake_drop(self):
        ev = self.events({"eligible_uptake": 0.98 * 0.79})
        self.assertIn("UPTAKE_DROP", [e["rule"] for e in ev])

    def test_uptake_sustained_two_of_three(self):
        ev = self.events({"eligible_uptake": 0.85}, history=[self.hist(uptake=0.86, labels=["P3"]), self.hist()])
        rules = [e["rule"] for e in ev]
        self.assertIn("UPTAKE_SUSTAINED", rules)
        self.assertNotIn("UPTAKE_DROP", rules)

    def test_reference_incomplete_skips_uptake_but_keeps_waste(self):
        ev = self.events({"log_waste": 5.2, "eligible_uptake": 0.1}, status="REFERENCE_INCOMPLETE")
        self.assertEqual([e["rule"] for e in ev], ["WASTE_SPIKE"])

    def test_excluded_meal_triggers_nothing(self):
        self.assertEqual(self.events({"log_waste": 9.0}, status="EXCLUDED_ZERO_ATTENDANCE"), [])

    def test_no_reference_triggers_nothing(self):
        self.assertEqual(evaluate_safety("LUNCH", "OK", dict(self.base, log_waste=9.0), ["P0"], [], None, CONFIG), [])
    
    def test_waste_tolerance_ignores_small_real_increases(self):
        cfg = copy.deepcopy(CONFIG)
        cfg["safety"]["waste_tolerance"] = 0.50
        m = dict(self.base, log_waste=5.09)   # above 3 sigma, but below the 50% tolerance
        self.assertEqual(evaluate_safety("LUNCH", "OK", m, ["P2", "P3"], [], self.ref, cfg), [])
    


# =====================================================================  learner
class TestLearner(unittest.TestCase):
    def test_rebuild_equals_incremental(self):
        rng = random.Random(0)
        log = [{"phi": [rng.random() for _ in range(16)], "reward": rng.uniform(-1, 1)} for _ in range(30)]
        a = SharedLinUCB.from_log(log, 16, 1.0, 1.0)
        b = SharedLinUCB(16, 1.0, 1.0)
        for e in log:
            b.update(e["phi"], e["reward"])
        probe = [0.5] * 16
        self.assertEqual(a.score(probe), b.score(probe))

    def test_bonus_shrinks_with_data(self):
        model = SharedLinUCB(16, 1.0, 1.0)
        x = [1.0] + [0.0] * 15
        before = model.score(x)[1]
        for _ in range(20):
            model.update(x, 0.0)
        self.assertLess(model.score(x)[1], before)

    def test_learns_that_displacement_helps(self):
        rng = random.Random(3)
        model = SharedLinUCB(16, 1.0, 1.0)
        ctx = [1, 1, 0, 0, 0.8, 1, 0.5, 0.6, 0.4, 0.2]
        for _ in range(200):
            d = rng.choice([0.0, 0.5])
            model.update(phi(ctx, d, d), 0.8 * d + rng.gauss(0, 0.1))
        self.assertGreater(model.score(phi(ctx, 0.5, 0.5))[0], model.score(phi(ctx, 0.0, 0.0))[0])

    def test_seeded_tie_break_is_deterministic_and_fair(self):
        picks = [argmax_seeded([1.0, 1.0], seeded_rng("meal", i)) for i in range(200)]
        self.assertEqual(picks, [argmax_seeded([1.0, 1.0], seeded_rng("meal", i)) for i in range(200)])
        self.assertTrue(60 < sum(picks) < 140)

    def test_ucb1_tries_every_label_first(self):
        ucb = UCB1Labels()
        ucb.update("P0", 0.5)
        scores = ucb.scores(["P0", "P1"])
        self.assertTrue(math.isinf(scores["P1"]))


# =====================================================================  recommend / evaluate end to end
class TestRecommend(unittest.TestCase):
    def setUp(self):
        self.req = sample_recommend_request()

    def run_valid(self, req):
        resp = recommend(req)
        assert_valid(self, "recommend", req, resp)
        return resp

    def chosen_labels(self, resp):
        return next(c for c in resp["candidates"] if c["ordering_hash"] == resp["chosen_hash"])["generating_labels"]

    def test_adaptive_output_matches_contract(self):
        resp = self.run_valid(self.req)
        self.assertEqual(resp["source"], "LEARNER")
        self.assertEqual(resp["reason_code"], "HIGHEST_UCB")
        self.assertTrue(all(c["ucb_score"] is not None for c in resp["candidates"]))

    def test_recommendation_is_reproducible(self):
        self.assertEqual(recommend(copy.deepcopy(self.req)), recommend(copy.deepcopy(self.req)))

    def test_baseline_always_p0(self):
        self.req["phase"] = "BASELINE"
        resp = self.run_valid(self.req)
        self.assertIn("P0", self.chosen_labels(resp))
        self.assertEqual(resp["reason_code"], "BASELINE_NEUTRAL")
        self.assertTrue(all(c["ucb_score"] is None for c in resp["candidates"]))

    def test_force_p0_after_rollback(self):
        self.req["force_p0"] = True
        resp = self.run_valid(self.req)
        self.assertEqual((resp["source"], resp["reason_code"]), ("ROLLBACK", "FORCED_P0_ROLLBACK"))
        self.assertIn("P0", self.chosen_labels(resp))

    def test_cooldown_on_one_label_does_not_exclude_shared_layout(self):
        self.req["active_cooldowns"] = [{"policy_label": "P3", "meal_type": "LUNCH"}]
        resp = self.run_valid(self.req)
        self.assertFalse(any(c["excluded_by_cooldown"] for c in resp["candidates"]))  # P2 still generates it

    def test_all_alternatives_cooling_down_falls_back_to_p0(self):
        self.req["active_cooldowns"] = [{"policy_label": p, "meal_type": "LUNCH"} for p in ("P2", "P3")]
        resp = self.run_valid(self.req)
        self.assertEqual(resp["reason_code"], "ALL_ALTERNATIVES_COOLING_DOWN")
        self.assertIn("P0", self.chosen_labels(resp))

    def test_cooldown_for_other_meal_type_is_ignored(self):
        self.req["active_cooldowns"] = [{"policy_label": p, "meal_type": "DINNER"} for p in ("P2", "P3")]
        self.assertEqual(self.run_valid(self.req)["reason_code"], "HIGHEST_UCB")

    def test_warmup_round_robin_includes_p0(self):
        # Standard order not aligned with carbon, so P0..P3 give four distinct layouts.
        menu = [item(1, 0.6), item(2, 0.9), item(3, 0.05), item(4, 0.3)]
        self.req.update(phase="WARMUP", menu_items=menu, slots=slots(4), forecast=[])
        chosen = [self.chosen_labels(recommend(dict(self.req, warmup_cursor=k))) for k in range(5)]
        self.assertEqual(chosen[:4], [["P0"], ["P1"], ["P2"], ["P3"]])
        self.assertEqual(chosen[4], chosen[0])  # wraps around

    def test_learner_prefers_layout_with_better_history(self):
        good = recommend(self.req)["candidates"][1]["phi"]
        neutral = recommend(self.req)["candidates"][0]["phi"]
        self.req["learner_log"] = ([{"meal_id": uid(1, k), "phi": good, "reward": 1.0, "deployed_labels": ["P2", "P3"]}
                                    for k in range(15)] +
                                   [{"meal_id": uid(1, 100 + k), "phi": neutral, "reward": -0.5,
                                     "deployed_labels": ["P0", "P1"]} for k in range(15)])
        self.assertEqual(self.chosen_labels(self.run_valid(self.req)), ["P2", "P3"])


class TestEvaluate(unittest.TestCase):
    def test_normal_meal(self):
        req = sample_evaluate_request()
        resp = evaluate(req)
        assert_valid(self, "evaluate", req, resp)
        self.assertEqual(resp["safety_events"], [])
        self.assertTrue(resp["learner_update"])

    def test_rollback_case(self):
        req = sample_evaluate_request("evaluate_rollback.sample.json")
        resp = evaluate(req)
        assert_valid(self, "evaluate", req, resp)
        self.assertTrue(resp["force_p0_next"])
        self.assertLess(resp["metrics"]["reward"], 0)
        self.assertTrue(resp["learner_update"])  # the bad reward still trains the learner

    def test_baseline_meal_does_not_train_learner(self):
        req = sample_evaluate_request()
        req["reference_model"] = None
        resp = evaluate(req)
        assert_valid(self, "evaluate", req, resp)
        self.assertFalse(resp["learner_update"])
        self.assertIsNone(resp["metrics"]["reward"])


if __name__ == "__main__":
    unittest.main()
