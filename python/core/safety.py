"""Deterministic safety rules. Same meal type only; P0 is never put in cooldown.

Waste (on Y = log(1 + g per attendee), baseline mu / sigma of that meal type):
  WASTE_SPIKE      Y_t > mu + spike_sigma * sigma
  WASTE_SUSTAINED  mean(Y over last `window` occurrences) > mu + sustained_sigma * sigma / sqrt(window)
Uptake (eligible portions per attendee vs. the frozen neutral prediction):
  UPTAKE_DROP      uptake_t < (1 - immediate_drop) * uptake_ref
  UPTAKE_SUSTAINED at least `required` of the last `window` occurrences have uptake < (1 - sustained_drop) * ref

Action: ROLLBACK if the current deployment includes a non-neutral policy, otherwise ALERT_ONLY.
Cooldown labels: non-P0 labels deployed in the meals that triggered the rule.
"""
from __future__ import annotations

import math

NON_NEUTRAL = ("P1", "P2", "P3")


def _non_neutral(labels) -> list[str]:
    return sorted(set(labels) & set(NON_NEUTRAL))


def _event(rule, observed, threshold, current_labels, window_labels):
    cooldown = _non_neutral(window_labels)
    action = "ROLLBACK" if _non_neutral(current_labels) else "ALERT_ONLY"
    return {"rule": rule, "observed_value": observed, "threshold": threshold,
            "action": action, "cooldown_labels": cooldown}


def evaluate_safety(meal_type: str, eval_status: str, metrics: dict, deployed_labels: list[str],
                    history: list[dict], reference: dict | None, config: dict) -> list[dict]:
    """`history`: previous occurrences of the same meal type, most recent last
    (each with log_waste, eligible_uptake, uptake_ref, deployed_labels)."""
    if eval_status == "EXCLUDED_ZERO_ATTENDANCE" or reference is None:
        return []
    cfg = config["safety"]
    events = []

    # ---- waste rules (apply even when the menu reference is incomplete)
    mu = reference["mu_log_waste"][meal_type]
    sigma = reference["sigma_log_waste"][meal_type]
    y = metrics["log_waste"]
    tol = math.log1p(cfg.get("waste_tolerance", 0.0))   # 10% more waste = +log(1.10) on the log scale
    spike_t = mu + max(cfg["waste_spike_sigma"] * sigma, tol)
    if y > spike_t:
        events.append(_event("WASTE_SPIKE", y, spike_t, deployed_labels, deployed_labels))

    w_window = cfg["waste_sustained_window"]
    recent = history[-(w_window - 1):] if w_window > 1 else []
    if len(recent) == w_window - 1:
        ys = [h["log_waste"] for h in recent] + [y]
        mean_y = sum(ys) / len(ys)
        sust_t = mu + max(cfg["waste_sustained_sigma"] * sigma / math.sqrt(w_window), tol)
        if mean_y > sust_t:
            labels = [lbl for h in recent for lbl in h["deployed_labels"]] + list(deployed_labels)
            events.append(_event("WASTE_SUSTAINED", mean_y, sust_t, deployed_labels, labels))

    # ---- uptake rules (need a complete reference for this menu)
    if eval_status == "OK" and metrics.get("uptake_ref"):
        ref_now = metrics["uptake_ref"]
        drop_t = (1 - cfg["uptake_immediate_drop"]) * ref_now
        if metrics["eligible_uptake"] < drop_t:
            events.append(_event("UPTAKE_DROP", metrics["eligible_uptake"], drop_t, deployed_labels, deployed_labels))

        u_window = cfg["uptake_sustained_window"]
        frac = 1 - cfg["uptake_sustained_drop"]
        window = [h for h in history[-(u_window - 1):]] if u_window > 1 else []
        rows = [(h["eligible_uptake"], h["uptake_ref"], h["deployed_labels"]) for h in window] + \
               [(metrics["eligible_uptake"], ref_now, deployed_labels)]
        low = [(u, r, lbls) for u, r, lbls in rows if r and u < frac * r]
        if len(low) >= cfg["uptake_sustained_required"]:
            labels = [lbl for _, _, lbls in low for lbl in lbls]
            events.append(_event("UPTAKE_SUSTAINED", float(len(low)), float(cfg["uptake_sustained_required"]),
                                 deployed_labels, labels))
    return events
