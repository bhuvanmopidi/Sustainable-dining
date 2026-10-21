"""Frozen neutral reference, fitted once on the baseline meals.

- Uptake: portions per attendee for each dish x meal type, shrunk towards the category mean.
- Waste reference: mean waste (g) per attendee for each meal type. No menu adjustment,
  because mixed-bin waste cannot be attributed to dishes.
- Scales s_c, s_w: robust spread (MAD x 1.4826) of leave-one-week-out prediction errors.
- mu / sigma of log(1 + waste g per attendee) per meal type, for the safety rules.
"""
from __future__ import annotations

import math
import statistics
from collections import defaultdict
from datetime import date

MEAL_TYPES = ("BREAKFAST", "LUNCH", "DINNER")
_MIN_SCALE = 1e-6


# ---------------------------------------------------------------- helpers
def _usable(baseline_meals):
    """Meals with at least one attendee; zero-attendance meals carry no rate information."""
    return [bm for bm in baseline_meals if bm["observation"]["actual_attendance"] > 0]


def _week_index(baseline_meals):
    first = min(date.fromisoformat(bm["meal"]["date"]) for bm in baseline_meals)
    return {bm["meal"]["id"]: (date.fromisoformat(bm["meal"]["date"]) - first).days // 7 for bm in baseline_meals}


def robust_scale(values: list[float], mad_scale: float) -> float:
    if not values:
        return _MIN_SCALE
    med = statistics.median(values)
    mad = statistics.median(abs(v - med) for v in values)
    return max(mad * mad_scale, _MIN_SCALE)


def _fit_tables(meals, shrinkage_k):
    """Uptake table, category means and waste reference from a set of meals (no coverage check)."""
    dish_rates = defaultdict(list)       # (dish_id, meal_type) -> [rate]
    cat_rates = defaultdict(list)        # (category, meal_type) -> [rate]
    dish_category = {}
    waste = defaultdict(list)            # meal_type -> [g per attendee]
    for bm in meals:
        mt = bm["meal"]["meal_type"]
        att = bm["observation"]["actual_attendance"]
        portions = {o["menu_item_id"]: o["portions_taken"] for o in bm["observation"]["item_outcomes"]}
        for item in bm["menu_items"]:
            rate = portions.get(item["id"], 0) / att
            dish_rates[(item["dish_id"], mt)].append(rate)
            cat_rates[(item["category"], mt)].append(rate)
            dish_category[item["dish_id"]] = item["category"]
        waste[mt].append(bm["observation"]["aggregate_waste_g"] / att)

    category_means = {key: statistics.fmean(v) for key, v in cat_rates.items()}
    uptake = {}
    for (dish_id, mt), rates in dish_rates.items():
        n = len(rates)
        cat_mean = category_means[(dish_category[dish_id], mt)]
        uptake[(dish_id, mt)] = ((n * statistics.fmean(rates) + shrinkage_k * cat_mean) / (n + shrinkage_k), n)

    all_waste = [w for ws in waste.values() for w in ws]
    overall = statistics.fmean(all_waste) if all_waste else 0.0
    w_ref = {mt: (statistics.fmean(waste[mt]) if waste[mt] else overall) for mt in MEAL_TYPES}
    return uptake, category_means, w_ref


def _lookup_rate(uptake, category_means, dish_id, category, meal_type):
    if (dish_id, meal_type) in uptake:
        return uptake[(dish_id, meal_type)][0]
    return category_means.get((category, meal_type))


# ---------------------------------------------------------------- prediction (used by evaluate)
def reference_rate(reference: dict, item: dict, meal_type: str) -> float | None:
    """Neutral portions per attendee for one menu item; None if neither the dish nor its category was seen."""
    params = reference["params"]
    for row in params["uptake"]:
        if row["dish_id"] == item["dish_id"] and row["meal_type"] == meal_type:
            return row["rate"]
    for row in params["category_means"]:
        if row["category"] == item["category"] and row["meal_type"] == meal_type:
            return row["rate"]
    return None


def predict_reference(reference: dict, menu_items: list[dict], meal_type: str) -> dict | None:
    """c_ref (kg CO2e per attendee), w_ref (g per attendee), uptake_ref (eligible portions per attendee).
    Returns None when any menu item has no dish or category reference (REFERENCE_INCOMPLETE)."""
    c_ref, uptake_ref = 0.0, 0.0
    for item in menu_items:
        rate = reference_rate(reference, item, meal_type)
        if rate is None:
            return None
        c_ref += rate * item["carbon_factor_snapshot"]
        if item["role"] == "ELIGIBLE":
            uptake_ref += rate
    return {"c_ref": c_ref, "w_ref": reference["params"]["w_ref_g"][meal_type], "uptake_ref": uptake_ref}


# ---------------------------------------------------------------- fitting
def coverage_gaps(baseline_meals, min_appearances):
    counts = defaultdict(int)
    for bm in baseline_meals:
        for item in bm["menu_items"]:
            counts[(item["dish_id"], bm["meal"]["meal_type"])] += 1
    return [
        {"dish_id": dish_id, "meal_type": mt, "appearances": n, "required": min_appearances}
        for (dish_id, mt), n in sorted(counts.items())
        if n < min_appearances
    ]


def _loo_week_errors(meals, shrinkage_k):
    """Carbon and waste prediction errors, each held-out week predicted from the other weeks."""
    weeks = _week_index(meals)
    c_err, w_err = [], []
    for held in sorted(set(weeks.values())):
        train = [bm for bm in meals if weeks[bm["meal"]["id"]] != held]
        test = [bm for bm in meals if weeks[bm["meal"]["id"]] == held]
        if not train:
            continue
        uptake, cat_means, w_ref = _fit_tables(train, shrinkage_k)
        for bm in test:
            mt = bm["meal"]["meal_type"]
            att = bm["observation"]["actual_attendance"]
            portions = {o["menu_item_id"]: o["portions_taken"] for o in bm["observation"]["item_outcomes"]}
            c_pred, ok = 0.0, True
            for item in bm["menu_items"]:
                rate = _lookup_rate(uptake, cat_means, item["dish_id"], item["category"], mt)
                if rate is None:
                    ok = False
                    break
                c_pred += rate * item["carbon_factor_snapshot"]
            if ok:
                c_obs = sum(portions.get(i["id"], 0) * i["carbon_factor_snapshot"] for i in bm["menu_items"]) / att
                c_err.append(c_obs - c_pred)
            w_err.append(bm["observation"]["aggregate_waste_g"] / att - w_ref[mt])
    return c_err, w_err


def fit_reference(baseline_meals: list[dict], config: dict, version: str) -> dict:
    """Implements POST /reference/fit. Returns the response object (FITTED or REJECTED)."""
    ref_cfg = config["reference"]
    gaps = coverage_gaps(baseline_meals, ref_cfg["min_baseline_appearances"])
    if gaps:
        return {"status": "REJECTED_INSUFFICIENT_COVERAGE", "missing_coverage": gaps}

    meals = _usable(baseline_meals)
    if not meals:
        raise ValueError("no baseline meal has non-zero attendance")
    k = ref_cfg["shrinkage_k"]
    uptake, category_means, w_ref = _fit_tables(meals, k)
    c_err, w_err = _loo_week_errors(meals, k)
    mad = config["reward"]["mad_scale"]

    log_waste = defaultdict(list)
    for bm in meals:
        log_waste[bm["meal"]["meal_type"]].append(
            math.log1p(bm["observation"]["aggregate_waste_g"] / bm["observation"]["actual_attendance"]))
    mu = {mt: (statistics.fmean(log_waste[mt]) if log_waste[mt] else 0.0) for mt in MEAL_TYPES}
    sigma = {mt: (statistics.stdev(log_waste[mt]) if len(log_waste[mt]) >= 2 else 0.0) for mt in MEAL_TYPES}

    return {
        "status": "FITTED",
        "reference_model": {
            "version": version,
            "fitted_on_meal_ids": [bm["meal"]["id"] for bm in baseline_meals],
            "params": {
                "uptake": [
                    {"dish_id": d, "meal_type": mt, "rate": rate, "n_obs": n}
                    for (d, mt), (rate, n) in sorted(uptake.items())
                ],
                "category_means": [
                    {"category": c, "meal_type": mt, "rate": rate}
                    for (c, mt), rate in sorted(category_means.items())
                ],
                "shrinkage_k": k,
                "w_ref_g": w_ref,
            },
            "s_c": robust_scale(c_err, mad),
            "s_w": robust_scale(w_err, mad),
            "mu_log_waste": mu,
            "sigma_log_waste": sigma,
        },
    }
