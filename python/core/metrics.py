"""Observed meal metrics and the reward.

C_t = sum(portions_i * carbon_factor_i) / actual attendance    [kg CO2e per attendee]
W_t = aggregate waste g / actual attendance                    [g per attendee]
z_c = (c_ref - C_t) / s_c        positive = less carbon than the neutral reference
z_w = (W_t - w_ref) / s_w        positive = more waste than the neutral reference
r_t = clip(z_c - lambda_w * z_w, -clip, clip)

Carbon of food taken includes food later discarded; it is not counted twice.
"""
from __future__ import annotations

import math

from .reference import predict_reference

METRIC_KEYS = ("carbon_per_attendee", "waste_g_per_attendee", "log_waste", "eligible_uptake",
               "total_portions_per_attendee", "c_ref", "w_ref", "uptake_ref",
               "z_c", "z_w", "reward_unclipped", "reward")


def empty_metrics() -> dict:
    return {k: None for k in METRIC_KEYS}


def observed_metrics(menu_items: list[dict], observation: dict) -> dict:
    """Metrics that need no reference. Caller guarantees actual_attendance > 0."""
    att = observation["actual_attendance"]
    portions = {o["menu_item_id"]: o["portions_taken"] for o in observation["item_outcomes"]}
    carbon = sum(portions.get(m["id"], 0) * m["carbon_factor_snapshot"] for m in menu_items)
    eligible = sum(portions.get(m["id"], 0) for m in menu_items if m["role"] == "ELIGIBLE")
    waste_pa = observation["aggregate_waste_g"] / att
    return {
        "carbon_per_attendee": carbon / att,
        "waste_g_per_attendee": waste_pa,
        "log_waste": math.log1p(waste_pa),
        "eligible_uptake": eligible / att,
        "total_portions_per_attendee": sum(portions.values()) / att,
    }


def reward(c_obs: float, w_obs: float, c_ref: float, w_ref: float,
           s_c: float, s_w: float, lambda_w: float, clip: float) -> tuple[float, float, float, float]:
    """Returns (z_c, z_w, reward_unclipped, reward)."""
    z_c = (c_ref - c_obs) / s_c
    z_w = (w_obs - w_ref) / s_w
    raw = z_c - lambda_w * z_w
    return z_c, z_w, raw, max(-clip, min(clip, raw))


def compute_metrics(meal: dict, menu_items: list[dict], observation: dict,
                    reference: dict | None, config: dict) -> tuple[str, dict]:
    """Returns (eval_status, metrics).

    - zero attendance          -> EXCLUDED_ZERO_ATTENDANCE, all metrics null
    - no reference (baseline)  -> OK, observed metrics only, reward null
    - unknown dish & category  -> REFERENCE_INCOMPLETE, observed metrics only, reward null
    """
    out = empty_metrics()
    if observation["actual_attendance"] == 0:
        return "EXCLUDED_ZERO_ATTENDANCE", out
    out.update(observed_metrics(menu_items, observation))
    if reference is None:
        return "OK", out

    ref = predict_reference(reference, menu_items, meal["meal_type"])
    if ref is None:
        return "REFERENCE_INCOMPLETE", out
    out.update(ref)
    rcfg = config["reward"]
    if rcfg.get("scale", "BASELINE_RESIDUAL") == "RELATIVE":
        # fractions of the neutral reference: z_c = 0.10 means 10% less carbon than expected
        s_c, s_w = max(ref["c_ref"], 1e-6), max(ref["w_ref"], 1e-6)
    else:
        s_c, s_w = reference["s_c"], reference["s_w"]
    out["z_c"], out["z_w"], out["reward_unclipped"], out["reward"] = reward(
        out["carbon_per_attendee"], out["waste_g_per_attendee"], ref["c_ref"], ref["w_ref"],
        s_c, s_w, rcfg["lambda_w"], rcfg["clip"])
    return "OK", out
