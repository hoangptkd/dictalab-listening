from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ConversationTaskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: str = Field(min_length=1, max_length=128)
    idempotencyKey: str = Field(min_length=1, max_length=128)
    level: str = Field(pattern="^(A1|A2|B1)$")
    turnNumber: int = Field(ge=1, le=4)
    promptVersion: str = Field(min_length=1, max_length=128)
    instruction: str = Field(min_length=1, max_length=50000)
    context: dict[str, Any]


class ConversationTaskResponse(BaseModel):
    task: dict[str, Any]
    model: str
    modelDigest: str
    promptVersion: str
    generationId: str
    requestId: str


class ErrorResponse(BaseModel):
    error: str
    requestId: str | None = None
