"""WorkflowState — immutable-style typed state passed between workflow nodes.

Each node receives the current state and returns a *new* state (dict-copy pattern).
No global mutable state is used outside the idempotency cache in main.py.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class WorkflowState:
    """State threaded through each node of the conversation generation workflow.

    Attributes
    ----------
    request_id:
        Matches the caller's requestId; used in every log entry.
    trace_id:
        Internal UUID generated once per request; included in error responses
        without leaking raw transcript or prompt content.
    turn_number:
        Integer 1–4 forwarded from the request.
    deadline:
        Monotonic timestamp (time.monotonic()) by which the entire workflow
        must finish. Each node checks remaining time before starting an LLM call.
    prompt_version:
        Version string forwarded from the request; written to every log entry.
    model:
        Model identifier used for the LLM call (e.g. "qwen3.5:9b").
    attempt:
        Number of generation attempts so far; starts at 1, incremented by repair_once.
    content:
        Raw output from generate_content node (English-first task dict).
        None until generate_content succeeds.
    translation:
        Raw output from translate node (translationVi + facts[].textVi).
        None until translate succeeds.
    merged:
        Final merged public task dict. None until merge succeeds.
    validation_errors:
        List of human-readable validation error strings produced by the validate node.
        Empty list means the output passed all automated checks.
    completed_node:
        Name of the last successfully completed node; used for timeout attribution.
    """
    request_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    turn_number: int = 1
    deadline: float = field(default_factory=lambda: time.monotonic() + 180.0)
    prompt_version: str = "unknown"
    model: str = "unknown"
    attempt: int = 1
    content: dict[str, Any] | None = None
    translation: dict[str, Any] | None = None
    merged: dict[str, Any] | None = None
    validation_errors: tuple[str, ...] = field(default_factory=tuple)
    completed_node: str = ""

    def remaining_seconds(self) -> float:
        """Seconds left before the deadline. Negative means already past."""
        return self.deadline - time.monotonic()

    def replace(self, **changes) -> "WorkflowState":
        """Return a new state with the given fields changed."""
        import dataclasses
        return dataclasses.replace(self, **changes)
