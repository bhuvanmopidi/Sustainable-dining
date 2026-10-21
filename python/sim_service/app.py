"""Simulator service (M5): DEMO ONLY. It stands in for a real mess so the app can be demonstrated.

Its outcome responses have exactly the shape of a manual observation, and the ground truth
(preferences, dissatisfaction, counterfactuals) never leaves this process.
Stateful: one simulator per (seed, scenario); meals must be stepped in order.

Run from the repo root:  uvicorn sim_service.app:app --app-dir python --port 8002
"""
from __future__ import annotations

import json
import threading
from pathlib import Path

from simulator.catalogue import DISHES, dish_id
from fastapi import Body, FastAPI
from fastapi.responses import JSONResponse

from ml_service import validation
from simulator.mess import MessSimulator
from simulator.scenarios import SCENARIOS

CONFIG = json.loads((Path(__file__).resolve().parents[2] / "config" / "experiment_config.v1.json")
                    .read_text(encoding="utf-8"))

app = FastAPI(title="Sustainable Dining simulator (demo only)")
_sims: dict[tuple[int, str], MessSimulator] = {}
_lock = threading.Lock()


def _error(status: int, kind: str, details: list[dict]) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": kind, "details": details})


def _sim(seed: int, scenario: str) -> MessSimulator:
    key = (seed, scenario)
    if key not in _sims:
        _sims[key] = MessSimulator(seed, scenario, CONFIG)
    return _sims[key]


@app.post("/simulate/reset")
def reset(payload: dict = Body(...)):
    seed, scenario = payload.get("seed"), payload.get("scenario")
    if not isinstance(seed, int) or scenario not in SCENARIOS:
        return _error(422, "INVALID_REQUEST", [{"path": "<root>", "message": "need integer seed and a known scenario"}])
    with _lock:
        _sims.pop((seed, scenario), None)
    return {"status": "reset"}


@app.post("/simulate/meal-spec")
def meal_spec(payload: dict = Body(...)):
    errs = validation.errors("simulate_meal_spec", "request", payload)
    if errs:
        return _error(422, "INVALID_REQUEST", errs)
    with _lock:
        sim = _sim(payload["seed"], payload["scenario"])
        t = payload["meal_index"]
        if t >= sim.n_meals:
            return _error(422, "REJECTED", [{"path": "meal_index", "message": f"semester has {sim.n_meals} meals"}])
        spec = sim.meal_spec(t)
        result = {"meal_index": t, "n_meals": sim.n_meals, "meal": spec["meal"], "menu_items": spec["menu_items"],
                  "slots": spec["slots"], "catalogue_median_carbon_score": sim.catalogue_median_carbon_score,
                  "dish_names": {dish_id(n): n for n in DISHES}}
    errs = validation.errors("simulate_meal_spec", "response", result)
    return _error(500, "INVALID_RESPONSE", errs) if errs else result


@app.post("/simulate/outcome")
def outcome(payload: dict = Body(...)):
    errs = validation.errors("simulate_outcome", "request", payload)
    if errs:
        return _error(422, "INVALID_REQUEST", errs)
    with _lock:
        sim = _sim(payload["seed"], payload["scenario"])
        t = payload["meal_index"]
        if t != sim._next_t:
            return _error(409, "OUT_OF_ORDER", [{"path": "meal_index", "message": f"expected meal {sim._next_t}, got {t}"}])
        if payload["meal"]["id"] != sim.meal_spec(t)["meal"]["id"]:
            return _error(422, "REJECTED", [{"path": "meal/id", "message": "meal does not match the simulator's meal"}])
        try:
            observation, _ground_truth = sim.step(t, payload["deployed_ordering"])   # ground truth is discarded
        except ValueError as e:
            return _error(422, "REJECTED", [{"path": "deployed_ordering", "message": str(e)}])
    errs = validation.errors("simulate_outcome", "response", observation)
    return _error(500, "INVALID_RESPONSE", errs) if errs else observation