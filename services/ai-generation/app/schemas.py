from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ConversationContext(BaseModel):
    """Typed context contract v1.

    Only fields in this allowlist are passed to the model. Unknown fields are dropped
    (`extra="ignore"`) so they never reach the prompt, while remaining backward-compatible
    with callers that send extra keys. The context must never carry credentials or account data.
    """
    model_config = ConfigDict(extra="ignore")

    scenario: str | None = Field(default=None, max_length=200)
    difficulty: str | None = Field(default=None, pattern="^(A1|A2|B1)$")
    turnNumber: int | None = Field(default=None, ge=1, le=4)
    partnerRole: str | None = Field(default=None, max_length=120)
    learnerRole: str | None = Field(default=None, max_length=120)
    levelProfile: dict[str, Any] | None = None
    listeningSkills: dict[str, Any] | None = None
    allowedTaskTypes: list[str] | None = None
    scenarioJourney: str | None = Field(default=None, max_length=4000)
    history: list[dict[str, Any]] | None = None
    recentPractice: list[dict[str, Any]] | None = None
    practiceFocus: str | None = Field(default=None, max_length=2000)
    sessionBlueprint: dict[str, Any] | None = None
    variationSeed: int | None = None
    previouslyTestedDetails: list[dict[str, Any]] | None = None
    weakSkills: list[str] | None = None
    validationError: str | None = Field(default=None, max_length=4000)
    previousInvalidPrompt: dict[str, Any] | None = None


class ConversationTaskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str = Field(min_length=1, max_length=128)
    idempotencyKey: str = Field(min_length=1, max_length=128)
    level: Literal["A1", "A2", "B1"]
    turnNumber: int = Field(ge=1, le=4)
    promptVersion: str = Field(min_length=1, max_length=128)
    instruction: str = Field(min_length=1, max_length=50000)
    context: ConversationContext


class ConversationTaskResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    task: dict[str, Any]
    model: str
    modelDigest: str
    promptVersion: str
    generationId: str
    requestId: str
    contractVersion: str = "1"


# Offline runner only. HTTP routes and queue jobs continue to use ConversationTaskRequest.
class ShadowConversationContext(ConversationContext):
    difficulty: Literal["B2", "C1"]


class ShadowConversationTaskRequest(ConversationTaskRequest):
    level: Literal["B2", "C1"]
    context: ShadowConversationContext


class ErrorResponse(BaseModel):
    error: str
    requestId: str | None = None
