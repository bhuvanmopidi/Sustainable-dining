"""Synthetic hostel mess.

Ground truth (dish preferences, per-student tastes, dissatisfaction, counterfactuals)
exists only inside this module. `meal_spec()` and the observation returned by
`step()` / `peek()` contain only what a real manager could see.

Choice model, per attending student s and eligible dish j:
    u_sj = preference_j(t) + taste_s,category(j) + beta_eff * visibility_j + Gumbel
    u_s,opt_out = OPT_OUT_U + avoidance * max(D, 0) + Gumbel
The student takes the option with the highest utility.
Waste per portion = (BASE_WASTE + substitution * (1 - exp(-mismatch))) * noise,
where mismatch = best intrinsic utility on the menu - intrinsic utility of the dish taken.
"""
from __future__ import annotations

import copy
import hashlib
import math
import uuid
from datetime import date, timedelta

import numpy as np

from core.layout import action_features, realised_visibility

from .catalogue import (DISHES, FIXED_BY_MEAL, FIXED_RATES, HIGH_CARBON_CATEGORIES, LOW_CARBON_CATEGORIES,
                        WEEKLY_MENUS, carbon_score, catalogue_median_score, dish_id)
from .rng import keyed_rng
from .scenarios import SCENARIOS

MEAL_TYPES = ("BREAKFAST", "LUNCH", "DINNER")
P_ATTEND = {"BREAKFAST": 0.70, "LUNCH": 0.85, "DINNER": 0.90}
WEEKEND_DROP = 0.10
START_DATE = date(2026, 7, 6)          # a Monday
MAX_ELIGIBLE, N_FIXED = 3, 2
TASTE_SD = 0.8
OPT_OUT_U = -2.5
OPT_OUT_PENALTY = 2.0                  # dissatisfaction units for skipping the main
BASE_WASTE = 0.08
FIXED_WASTE = 0.06
WASTE_SCALE_SD = 0.05                  # measurement noise on the bin weight
EXPOSURE_DECAY = 0.9
NAMESPACE = uuid.UUID("6f1c2a52-8d0e-4c4b-9a54-2f0d3c1e7a11")


def _uuid(*keys) -> str:
    return str(uuid.uuid5(NAMESPACE, "|".join(map(str, keys))))


def make_slots(config: dict) -> list[dict]:
    lay = config["layout"]
    vmax, vmin = lay["eligible_slot_visibility_max"], lay["eligible_slot_visibility_min"]
    out = [{"slot_index": k + 1, "visibility": round(vmax - (vmax - vmin) * k / (MAX_ELIGIBLE - 1), 6),
            "eligible_slot": True} for k in range(MAX_ELIGIBLE)]
    out += [{"slot_index": MAX_ELIGIBLE + k + 1, "visibility": 0.5, "eligible_slot": False} for k in range(N_FIXED)]
    return out


class MessSimulator:
    def __init__(self, seed: int, scenario: str, config: dict):
        self.seed = seed
        self.scenario = SCENARIOS[scenario]
        sem = config["semester"]
        self.enrolled = sem["enrolled_students"]
        self.n_meals = sem["weeks"] * sem["days_per_week"] * len(MEAL_TYPES)
        self.slots = make_slots(config)
        self.categories = sorted({d.category for d in DISHES.values()})
        self.taste = keyed_rng(seed, "taste").normal(0.0, TASTE_SD, size=(self.enrolled, len(self.categories)))
        self.exposure = 0.0
        self._next_t = 0
        self._dishes: list[dict] = []
        self._meals = [self._build_meal(t) for t in range(self.n_meals)]

    # ------------------------------------------------------------ public, observable
    @property
    def catalogue_median_carbon_score(self) -> float:
        return catalogue_median_score()

    def meal_spec(self, t: int) -> dict:
        """Meal, menu and slots for meal t. Independent of any policy decision."""
        return copy.deepcopy(self._meals[t])

    def step(self, t: int, ordering: list[str]) -> tuple[dict, dict]:
        """Deploy `ordering` at meal t. Meals must be stepped in order. Returns (observation, ground_truth)."""
        if t != self._next_t:
            raise ValueError(f"expected meal {self._next_t}, got {t}")
        obs, truth = self.peek(t, ordering)
        self.exposure = EXPOSURE_DECAY * self.exposure + max(truth["d"], 0.0)
        self._next_t += 1
        return obs, truth

    def peek(self, t: int, ordering: list[str]) -> tuple[dict, dict]:
        """Outcome of `ordering` at meal t WITHOUT advancing state (used for oracle counterfactuals)."""
        spec, dishes = self._meals[t], self._dishes[t]
        meal, menu = spec["meal"], spec["menu_items"]
        mt = meal["meal_type"]
        eligible = sorted((m for m in menu if m["role"] == "ELIGIBLE"), key=lambda m: m["standard_slot"])
        fixed = sorted((m for m in menu if m["role"] == "FIXED"), key=lambda m: m["standard_slot"])
        if sorted(ordering) != sorted(m["id"] for m in eligible):
            raise ValueError("ordering must be a permutation of the eligible menu items")
        n_e = len(eligible)
        d, _ = action_features(ordering, menu, self.slots)
        vis = realised_visibility(ordering, self.slots)

        # ---- exogenous draws (same for every policy)
        n_att = int(keyed_rng(self.seed, t, "attendance").binomial(self.enrolled, self._p_attend(t, mt, meal)))
        present = keyed_rng(self.seed, t, "who").permutation(self.enrolled)[:n_att]
        gumbel = keyed_rng(self.seed, t, "choice").gumbel(size=(self.enrolled, MAX_ELIGIBLE + 1))[present]
        waste_noise = keyed_rng(self.seed, t, "waste").uniform(0.8, 1.2, size=self.enrolled)[present]
        fixed_rng = keyed_rng(self.seed, t, "fixed")
        fixed_portions = [int(fixed_rng.poisson(FIXED_RATES[dishes[m["id"]].name] * n_att)) for m in fixed]
        scale_noise = float(keyed_rng(self.seed, t, "scale").lognormal(0.0, WASTE_SCALE_SD))

        # ---- choices
        cat_idx = np.array([self.categories.index(dishes[m["id"]].category) for m in eligible], dtype=int)
        prefs = np.array([self._preference(dishes[m["id"]], t) for m in eligible], dtype=float)
        intrinsic = prefs[None, :] + self.taste[present][:, cat_idx]                  # (n_att, n_e)
        beta = self.scenario.placement_beta * math.exp(-self.scenario.fatigue_rate * self.exposure)
        v = np.array([vis[m["id"]] for m in eligible], dtype=float)
        u_dish = intrinsic + beta * v[None, :] + gumbel[:, :n_e]
        u_out = OPT_OUT_U + self.scenario.avoidance * max(d, 0.0) + gumbel[:, MAX_ELIGIBLE]
        choice = np.column_stack([u_dish, u_out]).argmax(axis=1)                      # n_e means opt out
        counts = np.bincount(choice, minlength=n_e + 1)

        # ---- waste and dissatisfaction
        took = choice < n_e
        idx = choice[took]
        mismatch = np.zeros(0)
        eligible_waste = 0.0
        if n_e > 0 and took.any():
            ic = intrinsic[took]
            mismatch = ic.max(axis=1) - ic[np.arange(len(idx)), idx]
            sub = self.scenario.substitution_waste[mt]
            frac = np.clip((BASE_WASTE + sub * (1.0 - np.exp(-mismatch))) * waste_noise[took], 0.0, 0.9)
            mass = np.array([m["portion_mass_snapshot"] for m in eligible], dtype=float)
            eligible_waste = float((frac * mass[idx]).sum())
        fixed_waste = sum(FIXED_WASTE * m["portion_mass_snapshot"] * p for m, p in zip(fixed, fixed_portions))
        total_waste = (eligible_waste + fixed_waste) * scale_noise

        n_out = int(counts[n_e])
        observation = {
            "actual_attendance": n_att,
            "aggregate_waste_g": round(total_waste, 2),
            "item_outcomes": (
                [{"menu_item_id": m["id"], "portions_taken": int(counts[j]), "waste_g": None}
                 for j, m in enumerate(eligible)] +
                [{"menu_item_id": m["id"], "portions_taken": p, "waste_g": None}
                 for m, p in zip(fixed, fixed_portions)]
            ),
        }
        truth = {
            "d": d,
            "beta_eff": beta,
            "opt_out_rate": (n_out / n_att) if n_att else 0.0,
            "dissatisfaction": ((float(mismatch.sum()) + OPT_OUT_PENALTY * n_out) / n_att) if n_att else 0.0,
        }
        return observation, truth

    # ------------------------------------------------------------ internals
    def _progress(self, t: int) -> float:
        return t / (self.n_meals - 1)

    def _p_attend(self, t: int, meal_type: str, meal: dict) -> float:
        weekend = date.fromisoformat(meal["date"]).weekday() >= 5
        p = P_ATTEND[meal_type] - (WEEKEND_DROP if weekend else 0.0)
        return p * (1.0 - self.scenario.attendance_drift * self._progress(t))

    def _preference(self, dish, t: int) -> float:
        drift = self.scenario.pref_drift * self._progress(t)
        if dish.category in LOW_CARBON_CATEGORIES:
            return dish.preference + drift
        if dish.category in HIGH_CARBON_CATEGORIES:
            return dish.preference - drift
        return dish.preference

    def _build_meal(self, t: int) -> dict:
        day, k = divmod(t, len(MEAL_TYPES))
        mt = MEAL_TYPES[k]
        d = START_DATE + timedelta(days=day)
        # Standard order is fixed per dish name and independent of carbon.
        eligible = sorted(WEEKLY_MENUS[mt][day % 7], key=lambda n: hashlib.sha256(n.encode()).hexdigest())
        menu, dishes = [], {}
        for slot, name in enumerate(eligible + FIXED_BY_MEAL[mt], start=1):
            dish = DISHES[name]
            mid = _uuid(self.seed, "item", t, name)
            dishes[mid] = dish
            menu.append({"id": mid, "dish_id": dish_id(name), "category": dish.category, "role": dish.role,
                         "standard_slot": slot, "carbon_score": carbon_score(dish),
                         "carbon_factor_snapshot": dish.carbon_kg, "portion_mass_snapshot": dish.portion_g})
        meal = {"id": _uuid(self.seed, "meal", t), "meal_type": mt, "date": d.isoformat(),
                "expected_attendance": 0, "enrolled": self.enrolled}
        meal["expected_attendance"] = int(round(self.enrolled * self._p_attend(t, mt, meal)))
        self._dishes.append(dishes)
        return {"index": t, "meal": meal, "menu_items": menu, "slots": self.slots}