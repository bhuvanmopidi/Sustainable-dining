"""ML service (M5): a stateless HTTP wrapper around python/core. It never imports the simulator.

Every request is validated against contracts/schemas before core runs; every response is validated
before it is returned (disable with VALIDATE_RESPONSES=0 once the system is stable).

Run from the repo root:  uvicorn ml_service.app:app --app-dir python --port 8001
"""
from __future__ import annotations

import hashlib
import os

from fastapi import Body, FastAPI
from fastapi.responses import JSONResponse

from core.evaluate import evaluate
from core.features import FEATURE_SCHEMA_VERSION
from core.forecast import forecast
from core.recommend import recommend
from core.reference import fit_reference

from . import validation

SERVICE_VERSION = "0.5.0"
VALIDATE_RESPONSES = os.environ.get("VALIDATE_RESPONSES", "1") != "0"

app = FastAPI(title="Sustainable Dining ML service", version=SERVICE_VERSION)


class ContractError(Exception):
    def __init__(self, status: int, kind: str, details: list[dict]):
        super().__init__(kind)
        self.status, self.kind, self.details = status, kind, details


@app.exception_handler(ContractError)
async def _contract_error(_request, exc: ContractError):
    return JSONResponse(status_code=exc.status, content={"error": exc.kind, "details": exc.details})


def _handle(schema: str, payload: dict, fn):
    errs = validation.errors(schema, "request", payload)
    if errs:
        raise ContractError(422, "INVALID_REQUEST", errs)
    try:
        result = fn(payload)
    except ValueError as e:                      # e.g. forecast history containing future meals
        raise ContractError(422, "REJECTED", [{"path": "<root>", "message": str(e)}])
    if VALIDATE_RESPONSES:
        errs = validation.errors(schema, "response", result)
        if errs:
            raise ContractError(500, "INVALID_RESPONSE", errs)
    return result


def _reference_version(payload: dict) -> str:
    """Deterministic: the same baseline meals always give the same reference version."""
    ids = ",".join(sorted(bm["meal"]["id"] for bm in payload["baseline_meals"]))
    return "ref-" + hashlib.sha256(ids.encode("utf-8")).hexdigest()[:12]


@app.get("/health")
def health():
    return {"status": "ok", "service_version": SERVICE_VERSION, "feature_schema_version": FEATURE_SCHEMA_VERSION}


@app.post("/recommend")
def post_recommend(payload: dict = Body(...)):
    return _handle("recommend", payload, recommend)


@app.post("/evaluate")
def post_evaluate(payload: dict = Body(...)):
    return _handle("evaluate", payload, evaluate)


@app.post("/reference/fit")
def post_reference_fit(payload: dict = Body(...)):
    return _handle("reference_fit", payload,
                   lambda p: fit_reference(p["baseline_meals"], p["config"], _reference_version(p)))


@app.post("/forecast")
def post_forecast(payload: dict = Body(...)):
    return _handle("forecast", payload, forecast)