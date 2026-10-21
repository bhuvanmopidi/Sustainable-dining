"""JSON Schema validation against contracts/schemas, the single source of truth shared with the Java backend."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

SCHEMA_DIR = Path(__file__).resolve().parents[2] / "contracts" / "schemas"
BASE_URI = "https://sustainable-dining.local/schemas/"


@lru_cache(maxsize=1)
def _registry() -> Registry:
    resources = []
    for path in sorted(SCHEMA_DIR.glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        resources.append((schema["$id"], Resource.from_contents(schema)))
    return Registry().with_resources(resources)


@lru_cache(maxsize=None)
def _validator(schema: str, part: str) -> Draft202012Validator:
    return Draft202012Validator({"$ref": f"{BASE_URI}{schema}.schema.json#/$defs/{part}"}, registry=_registry())


def errors(schema: str, part: str, instance, limit: int = 20) -> list[dict]:
    """Validation errors as [{path, message}], at most `limit` of them; empty list means valid."""
    out = []
    for e in sorted(_validator(schema, part).iter_errors(instance), key=lambda e: list(e.absolute_path)):
        out.append({"path": "/".join(map(str, e.absolute_path)) or "<root>", "message": e.message})
        if len(out) >= limit:
            break
    return out