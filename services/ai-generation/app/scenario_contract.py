"""Scenario goal boundary v1. No provider calls, score estimation or implicit CEFR mapping.

Core is the authority for stored goals. A future production job must pass an approved,
hash-bound program snapshot; this module does not read the Core database or enable jobs.
"""
import json
import re
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

MAX_REQUEST_BYTES = 32 * 1024


class Target(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scaleId: str = Field(min_length=1, max_length=64)
    component: str = Field(min_length=1, max_length=32)
    value: str = Field(min_length=1, max_length=32)


class ScenarioGoalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    contractVersion: Literal["scenario-goal.v1"]
    programCode: str = Field(pattern=r"^[a-z][a-z0-9-]{0,63}$")
    programVersion: int = Field(ge=1, le=2147483647)
    target: Target
    expectedVersion: int = Field(ge=0, le=9007199254740991)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def parse_goal(raw: bytes) -> ScenarioGoalRequest:
    if len(raw) > MAX_REQUEST_BYTES:
        raise ValueError("Scenario payload too large")
    return ScenarioGoalRequest.model_validate(json.loads(raw, object_pairs_hook=_unique_object))


def validate_target(request: ScenarioGoalRequest, definition: dict) -> None:
    """Validate against an explicit trusted snapshot, never infer LISTENING from OVERALL."""
    if (request.programCode, request.programVersion) != (definition["code"], definition["version"]):
        raise ValueError("Program mismatch")
    target = request.target
    scale = next((s for s in definition["targetScales"]
                  if (s["scaleId"], s["component"]) == (target.scaleId, target.component)), None)
    if scale is None:
        raise ValueError("Scale/component mismatch")
    if scale["labels"]:
        if target.value not in scale["labels"]:
            raise ValueError("Unknown target label")
        return
    if not re.fullmatch(r"(?:0|[1-9][0-9]{0,3})(?:\.[0-9])?", target.value, flags=re.ASCII):
        raise ValueError("Invalid decimal target")
    value, lower, upper, step = map(Decimal, (target.value, scale["min"], scale["max"], scale["step"]))
    if not lower <= value <= upper or (value - lower) % step != 0:
        raise ValueError("Target outside scale")
