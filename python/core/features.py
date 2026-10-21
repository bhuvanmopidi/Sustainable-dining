"""Context vector x_t (10 features) and full feature vector phi (16), schema v1.

Only information available BEFORE the meal is used: expected attendance, the menu,
the neutral-placement forecast and the realised D of earlier meals.
"""
from __future__ import annotations

from datetime import date

FEATURE_SCHEMA_VERSION = "v1"
CONTEXT_DIM = 10
PHI_DIM = 16


def recent_exposure(exposure_history: list[float], window: int) -> float:
    recent = exposure_history[-window:] if window > 0 else []
    return sum(recent) / len(recent) if recent else 0.0


def pred_lowcarbon_share(menu_items: list[dict], forecast: list[dict], catalogue_median: float) -> float:
    """Forecast share of eligible demand on items whose carbon score is below the catalogue median."""
    predicted = {f["menu_item_id"]: f["predicted_per_100"] for f in forecast}
    eligible = [m for m in menu_items if m["role"] == "ELIGIBLE"]
    total = sum(predicted.get(m["id"], 0.0) for m in eligible)
    if total <= 0:
        return 0.0
    low = sum(predicted.get(m["id"], 0.0) for m in eligible if m["carbon_score"] < catalogue_median)
    return low / total


def context_vector(meal: dict, menu_items: list[dict], slots: list[dict], forecast: list[dict],
                   exposure_history: list[float], catalogue_median: float, exposure_window: int) -> list[float]:
    """x_t in the fixed order of contracts/feature_schema.v1.json (indices 0-9)."""
    meal_type = meal["meal_type"]
    is_weekend = date.fromisoformat(meal["date"]).weekday() >= 5
    eligible = [m for m in menu_items if m["role"] == "ELIGIBLE"]
    scores = [m["carbon_score"] for m in eligible]
    n_slots = sum(1 for s in slots if s["eligible_slot"])
    return [
        1.0,
        1.0 if meal_type == "LUNCH" else 0.0,
        1.0 if meal_type == "DINNER" else 0.0,
        1.0 if is_weekend else 0.0,
        meal["expected_attendance"] / meal["enrolled"],
        (len(eligible) / n_slots) if n_slots else 0.0,
        (sum(scores) / len(scores)) if scores else 0.0,
        (max(scores) - min(scores)) if len(scores) >= 2 else 0.0,
        pred_lowcarbon_share(menu_items, forecast, catalogue_median),
        recent_exposure(exposure_history, exposure_window),
    ]


def phi(context: list[float], d: float, m: float) -> list[float]:
    """Append the action block: D, D^2, M, D*lunch, D*dinner, D*exposure."""
    if len(context) != CONTEXT_DIM:
        raise ValueError(f"context must have {CONTEXT_DIM} entries, got {len(context)}")
    is_lunch, is_dinner, exposure = context[1], context[2], context[9]
    return list(context) + [d, d * d, m, d * is_lunch, d * is_dinner, d * exposure]
