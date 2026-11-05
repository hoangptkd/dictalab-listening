"""Scenario goal boundary v1. No provider calls, score estimation or implicit CEFR mapping.

Core is the authority for stored goals. A future production job must pass an approved,
hash-bound program snapshot; this module does not read the Core database or enable jobs.
"""
import json
import re
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

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


class TargetRange(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scaleId: str
    min: str
    max: str


class TargetScale(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    scaleId: str = Field(min_length=1, max_length=64)
    component: Literal["LISTENING"]
    min: str | None
    max: str | None
    step: str | None
    labels: list[str] = Field(max_length=20)
    sourceUrl: str | None
    reviewedOn: str | None


class PracticeProfile(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: Literal["supported", "standard", "challenge", "a1", "a2", "b1", "b2", "c1", "c2"]
    version: int = Field(ge=2)
    minWordsPerActivity: int = Field(ge=1, le=140)
    maxWordsPerActivity: int = Field(ge=1, le=140)
    maxWordsPerSession: int = Field(ge=1, le=480)
    maxActivities: Literal[3]
    maxSpeakers: Literal[2]
    maxSegmentsPerActivity: Literal[6]
    maxQuestionsPerSession: Literal[6]
    maxFactsPerActivity: int = Field(ge=1, le=4)
    maxDistractors: int = Field(ge=0, le=3)
    maxCorrections: int = Field(ge=0, le=2)
    inferencePolicy: Literal["EXPLICIT_ONLY", "SUPPORTED_INFERENCE"]
    speechRateWpm: int = Field(ge=80, le=200)
    pauseMillis: int = Field(ge=0, le=3000)
    vocabularyGuidance: str = Field(min_length=1, max_length=1000)
    syntaxGuidance: str = Field(min_length=1, max_length=1000)
    informationDensity: str = Field(min_length=1, max_length=1000)
    skills: list[Literal["detail", "main-idea", "reason", "purpose", "speaker-attitude", "supported-inference"]] = Field(min_length=1, max_length=6)
    allowedVoiceProfiles: list[str] = Field(max_length=10)
    targetRange: TargetRange | None = None


class LearningContext(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    contractVersion: Literal["scenario-learning-context.v1"]
    programCode: str = Field(pattern=r"^[a-z][a-z0-9-]{0,63}$")
    programVersion: int = Field(ge=2)
    programHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    practiceMode: Literal["guided-listening"]
    layoutVersion: Literal["three-activities.v1"]
    focusSkill: Literal["detail", "main-idea", "reason", "purpose", "speaker-attitude", "supported-inference"]
    targetProvenance: Literal["USER_DECLARED_GOAL"]
    currentProvenance: Literal["UNKNOWN", "USER_DECLARED"]
    calibrationStatus: Literal["INTERNAL_PRACTICE_UNCALIBRATED"]
    qualityPolicyVersion: str = Field(min_length=1, max_length=64)
    skillTaxonomyVersion: str = Field(min_length=1, max_length=64)
    target: Target
    targetScale: TargetScale
    currentValue: str | None
    profile: PracticeProfile
    answerLanguages: list[Literal["vi", "en"]] = Field(min_length=2, max_length=2)

    @model_validator(mode="after")
    def consistent_context(self):
        if (self.target.component != "LISTENING" or self.focusSkill not in self.profile.skills
                or set(self.answerLanguages) != {"vi", "en"}
                or (self.currentValue is None) != (self.currentProvenance == "UNKNOWN")):
            raise ValueError("inconsistent learning context")
        definition = {"code": self.programCode, "version": self.programVersion,
                      "targetScales": [self.targetScale.model_dump()]}
        request = ScenarioGoalRequest(contractVersion="scenario-goal.v1", programCode=self.programCode,
            programVersion=self.programVersion, target=self.target, expectedVersion=0)
        validate_target(request, definition)
        if self.currentValue is not None:
            validate_target(request.model_copy(update={"target": self.target.model_copy(update={"value": self.currentValue})}), definition)
        level = self.currentValue if self.currentValue is not None else self.target.value
        bounds = self.profile.targetRange
        if bounds is None:
            if self.target.scaleId not in {"practice-support", "cefr-listening"} or level != self.profile.id:
                raise ValueError("practice profile mismatch")
        elif (bounds.scaleId != self.target.scaleId or not Decimal(bounds.min) <= Decimal(level) <= Decimal(bounds.max)):
            raise ValueError("score profile mismatch")
        if self.focusSkill == "supported-inference" and self.profile.inferencePolicy != "SUPPORTED_INFERENCE":
            raise ValueError("inference disabled by profile")
        return self
