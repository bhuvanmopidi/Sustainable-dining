"""Demand forecasting (M4): portions per 100 attendees for each menu item, under NEUTRAL placement.

Models
  SEASONAL_NAIVE  most recent earlier occurrence of the same dish at the same meal type
                  (with a weekly menu rotation this is usually the same weekday last week);
                  falls back to the dish mean, then the category mean, then the global mean.
                  It cannot remove placement effects: if that occurrence was nudged, so is the forecast.
  RANDOM_FOREST   scikit-learn RandomForestRegressor on pre-meal features plus the item's placement shift.
                  Predictions set the shift to 0, i.e. "what would demand be under the standard layout?".
Walk-forward only: every history meal must come strictly before the target meal.
"""
from __future__ import annotations

import statistics
from collections import defaultdict
from datetime import date

import numpy as np
from sklearn.ensemble import RandomForestRegressor

MODEL_VERSION = "forecast-v1"
MEAL_ORDER = {"BREAKFAST": 0, "LUNCH": 1, "DINNER": 2}
MIN_ROWS_FOR_FOREST = 30


def meal_key(meal: dict) -> tuple:
    """Chronological sort key: (date, meal order within the day)."""
    return (meal["date"], MEAL_ORDER[meal["meal_type"]])


def _ranks(menu_items, ordering):
    """Standard and deployed rank (0 = most visible) of each eligible item, plus the number of eligible items."""
    eligible = sorted((m for m in menu_items if m["role"] == "ELIGIBLE"), key=lambda m: m["standard_slot"])
    std = {m["id"]: k for k, m in enumerate(eligible)}
    dep = {mid: k for k, mid in enumerate(ordering)} if ordering else dict(std)
    return std, dep, len(eligible)


def _rates(h):
    att = h["observation"]["actual_attendance"]
    portions = {o["menu_item_id"]: o["portions_taken"] for o in h["observation"]["item_outcomes"]}
    return {m["id"]: 100.0 * portions.get(m["id"], 0) / att for m in h["menu_items"]}


class _Stats:
    """Per-dish / per-category mean rates and the most recent rate, from the training history only."""

    def __init__(self, history):
        dish, cat, everything = defaultdict(list), defaultdict(list), []
        self.last = {}
        for h in sorted(history, key=lambda h: meal_key(h["meal"])):
            if h["observation"]["actual_attendance"] <= 0:
                continue
            mt = h["meal"]["meal_type"]
            rates = _rates(h)
            for m in h["menu_items"]:
                r = rates[m["id"]]
                dish[(m["dish_id"], mt)].append(r)
                cat[(m["category"], mt)].append(r)
                everything.append(r)
                self.last[(m["dish_id"], mt)] = r
        self.dish = {k: statistics.fmean(v) for k, v in dish.items()}
        self.cat = {k: statistics.fmean(v) for k, v in cat.items()}
        self.glob = statistics.fmean(everything) if everything else 0.0

    def cat_mean(self, item, mt):
        return self.cat.get((item["category"], mt), self.glob)

    def dish_mean(self, item, mt):
        return self.dish.get((item["dish_id"], mt), self.cat_mean(item, mt))

    def naive(self, item, mt):
        return self.last.get((item["dish_id"], mt), self.dish_mean(item, mt))


def _features(meal, item, std, dep, n, stats):
    mt = meal["meal_type"]
    eligible = item["role"] == "ELIGIBLE"
    scale = max(n - 1, 1)
    std_rank = std.get(item["id"], 0) / scale if eligible else 0.0
    shift = (std.get(item["id"], 0) - dep.get(item["id"], 0)) / scale if eligible else 0.0  # > 0: moved up
    return [
        float(mt == "LUNCH"), float(mt == "DINNER"),
        float(date.fromisoformat(meal["date"]).weekday() >= 5),
        meal["expected_attendance"] / meal["enrolled"],
        item["carbon_score"], float(eligible), float(n), std_rank, shift,
        stats.dish_mean(item, mt), stats.cat_mean(item, mt),
    ]


class Forecaster:
    def __init__(self, model: str = "RANDOM_FOREST", seed: int = 0, n_estimators: int = 50):
        if model not in ("RANDOM_FOREST", "SEASONAL_NAIVE"):
            raise ValueError(f"unknown model {model}")
        self.model, self.seed, self.n_estimators = model, seed, n_estimators
        self.stats, self.forest, self.fitted_until = None, None, None

    @property
    def version(self) -> str:
        return f"{MODEL_VERSION}-{self.model.lower()}"

    def fit(self, history: list[dict]) -> "Forecaster":
        self.stats = _Stats(history)
        self.forest = None
        self.fitted_until = max((meal_key(h["meal"]) for h in history), default=None)
        if self.model != "RANDOM_FOREST":
            return self
        X, y = [], []
        for h in history:
            if h["observation"]["actual_attendance"] <= 0:
                continue
            std, dep, n = _ranks(h["menu_items"], h["deployed_ordering"])
            rates = _rates(h)
            for m in h["menu_items"]:
                X.append(_features(h["meal"], m, std, dep, n, self.stats))
                y.append(rates[m["id"]])
        if len(y) >= MIN_ROWS_FOR_FOREST:  # otherwise predict() falls back to seasonal-naive
            self.forest = RandomForestRegressor(n_estimators=self.n_estimators, min_samples_leaf=3,
                                                random_state=self.seed, n_jobs=1)
            self.forest.fit(np.asarray(X), np.asarray(y))
        return self

    def predict(self, meal: dict, menu_items: list[dict]) -> list[dict]:
        if self.stats is None:
            raise RuntimeError("call fit() before predict()")
        if self.fitted_until is not None and meal_key(meal) <= self.fitted_until:
            raise ValueError("target meal must come after every training meal (walk-forward)")
        mt = meal["meal_type"]
        if self.forest is None:
            preds = [self.stats.naive(m, mt) for m in menu_items]
        else:
            std, _, n = _ranks(menu_items, None)
            X = [_features(meal, m, std, std, n, self.stats) for m in menu_items]   # neutral: shift = 0
            preds = self.forest.predict(np.asarray(X)).tolist()
        return [{"menu_item_id": m["id"], "predicted_per_100": max(0.0, float(p))} for m, p in zip(menu_items, preds)]


def forecast(request: dict) -> dict:
    """Implements POST /forecast."""
    target = request["target_meal"]
    for h in request["history"]:
        if meal_key(h["meal"]) >= meal_key(target):
            raise ValueError("history contains a meal at or after the target meal (future data leak)")
    fc = Forecaster(request["model"], seed=request.get("random_seed", 0)).fit(request["history"])
    return {"model_version": fc.version, "placement_assumption": "NEUTRAL",
            "predictions": fc.predict(target, request["target_menu_items"])}