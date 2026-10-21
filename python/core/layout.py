"""Policy generation, layout deduplication and action features (D, M).

A policy turns a menu into an ordering of the eligible items over the eligible slots.
Different policies can produce the same physical layout; those are merged into one
candidate that remembers every label that generated it.
"""
from __future__ import annotations

import hashlib

POLICY_LABELS = ("P0", "P1", "P2", "P3")
_ROUND = 9  # decimal places used when comparing priorities, so 1/3 vs 0.3333333333 can't flip an order


def ordering_hash(ordering: list[str]) -> str:
    """Canonical layout id: sha256 of the comma-joined menu-item ids, most visible slot first."""
    return hashlib.sha256(",".join(ordering).encode("utf-8")).hexdigest()


def eligible_slots(slots: list[dict]) -> list[dict]:
    """Eligible slots from most to least visible (ties broken by slot index)."""
    return sorted((s for s in slots if s["eligible_slot"]), key=lambda s: (-s["visibility"], s["slot_index"]))


def eligible_items(menu_items: list[dict]) -> list[dict]:
    return sorted((m for m in menu_items if m["role"] == "ELIGIBLE"), key=lambda m: m["standard_slot"])


def standard_visibility(menu_items: list[dict], slots: list[dict]) -> dict[str, float]:
    """v_i^0: visibility of each eligible item's own standard slot."""
    by_index = {s["slot_index"]: s for s in slots}
    out = {}
    for item in eligible_items(menu_items):
        slot = by_index.get(item["standard_slot"])
        if slot is None or not slot["eligible_slot"]:
            raise ValueError(f"eligible item {item['id']} has standard_slot {item['standard_slot']}, "
                             "which is not an eligible slot")
        out[item["id"]] = slot["visibility"]
    if len(out) > len(eligible_slots(slots)):
        raise ValueError("more eligible items than eligible slots")
    return out


def policy_ordering(menu_items: list[dict], slots: list[dict], alpha: float) -> list[str]:
    """Order eligible items by priority v_i(a) = (1 - alpha) * b_i + alpha * (1 - c_i).

    Higher priority gets a more visible slot. Ties are broken by standard slot, so
    alpha = 0 reproduces the standard layout exactly.
    """
    v0 = standard_visibility(menu_items, slots)
    items = eligible_items(menu_items)

    def key(item):
        priority = (1 - alpha) * v0[item["id"]] + alpha * (1 - item["carbon_score"])
        return (-round(priority, _ROUND), item["standard_slot"])

    return [item["id"] for item in sorted(items, key=key)]


def realised_visibility(ordering: list[str], slots: list[dict]) -> dict[str, float]:
    """v_i^a: visibility each item gets when placed in `ordering`."""
    targets = eligible_slots(slots)
    return {item_id: targets[k]["visibility"] for k, item_id in enumerate(ordering)}


def action_features(ordering: list[str], menu_items: list[dict], slots: list[dict]) -> tuple[float, float]:
    """D = -sum (v^a - v^0) * c  (positive when low-carbon dishes gain visibility)
       M = 0.5 * sum |v^a - v^0|  (total visibility moved)."""
    v0 = standard_visibility(menu_items, slots)
    va = realised_visibility(ordering, slots)
    carbon = {m["id"]: m["carbon_score"] for m in eligible_items(menu_items)}
    d = -sum((va[i] - v0[i]) * carbon[i] for i in ordering)
    m = 0.5 * sum(abs(va[i] - v0[i]) for i in ordering)
    # clean -0.0 and float dust so P0 is exactly zero
    return (0.0 if abs(d) < 1e-12 else d), (0.0 if abs(m) < 1e-12 else m)


def candidate_layouts(menu_items: list[dict], slots: list[dict], alphas: dict[str, float]) -> list[dict]:
    """Distinct layouts produced by P0..P3, in order of first appearance (P0's layout is always first)."""
    candidates: dict[str, dict] = {}
    for label in POLICY_LABELS:
        ordering = policy_ordering(menu_items, slots, alphas[label])
        h = ordering_hash(ordering)
        if h not in candidates:
            d, m = action_features(ordering, menu_items, slots)
            candidates[h] = {"ordering": ordering, "ordering_hash": h, "generating_labels": [], "d": d, "m": m}
        candidates[h]["generating_labels"].append(label)
    return list(candidates.values())
