"""POST /evaluate: metrics, reward, safety events and whether the meal enters the learner log."""
from __future__ import annotations

from .metrics import compute_metrics
from .safety import evaluate_safety


def evaluate(request: dict) -> dict:
    cfg, meal = request["config"], request["meal"]
    reference = request["reference_model"]
    deployed_labels = request["deployed_candidate"]["generating_labels"]

    status, metrics = compute_metrics(meal, request["menu_items"], request["observation"], reference, cfg)
    events = evaluate_safety(meal["meal_type"], status, metrics, deployed_labels,
                             request["same_type_history"], reference, cfg)

    return {
        "eval_status": status,
        "metrics": metrics,
        "safety_events": events,
        "force_p0_next": any(e["action"] == "ROLLBACK" for e in events),
        # Baseline meals (no reference yet) and excluded meals never train the learner.
        "learner_update": status == "OK" and reference is not None,
    }
