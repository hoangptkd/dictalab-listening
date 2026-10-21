"""Conversation generation workflow with explicit node-by-node state transitions.

Pipeline
--------
normalize_context → generate_content → translate → validate → return or reject

Each node:
- Receives a WorkflowState and an httpx.AsyncClient.
- Returns a new WorkflowState (immutable replace pattern).
- Checks remaining deadline before making any LLM call.
- Raises TimeoutError if deadline is already past.
- Raises OllamaError for unrecoverable generation errors.

Repair is disabled by default to measure first-pass prompt quality. When explicitly
enabled, repair_once runs at most once; invalid output is always rejected.
"""
from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from .config import Settings
from .ollama import OllamaError, merge_content_and_translation
from .prompt_versions import generate_content as _generate_content, translate_task, selected_artifact
from .schemas import ConversationContext, ConversationTaskRequest, ShadowConversationTaskRequest
from .state import WorkflowState
from .validation import validate_task

logger = logging.getLogger("dictalab.ai_generation.workflow")

_DEADLINE_BUFFER_SECONDS = 1.0  # minimum headroom before we attempt an LLM call


def _check_deadline(state: WorkflowState, node: str) -> None:
    """Raise TimeoutError if there is not enough time left to attempt this node."""
    if state.remaining_seconds() < _DEADLINE_BUFFER_SECONDS:
        raise TimeoutError(
            f"deadline_before_node:{node} trace_id={state.trace_id}"
        )


# ---------------------------------------------------------------------------
# Node 1 — normalize_context (deterministic, no LLM)
# ---------------------------------------------------------------------------

def normalize_context(state: WorkflowState, request: Any) -> WorkflowState:
    """Truncate oversized history/recentPractice and resolve turn-4 context isolation.

    Turn 4 receives the full context as-is from the caller; the prompt instruction
    is responsible for framing it as a new encounter. This node does NOT strip
    history for turn 4 — that would be a silent semantic change. Instead it logs
    the turn number so the prompt layer can enforce the new-encounter constraint.
    """
    context: ConversationContext = request.context
    MAX_HISTORY = 10
    MAX_RECENT = 20
    MAX_UTTERANCE = 2000

    history = context.history or []
    recent = context.recentPractice or []
    truncated = False

    if len(history) > MAX_HISTORY:
        history = history[-MAX_HISTORY:]
        truncated = True
    history = [
        {**turn, "utteranceEn": turn["utteranceEn"][:MAX_UTTERANCE]}
        if isinstance(turn, dict) and isinstance(turn.get("utteranceEn"), str)
           and len(turn["utteranceEn"]) > MAX_UTTERANCE
        else turn
        for turn in history
    ]

    if len(recent) > MAX_RECENT:
        recent = recent[-MAX_RECENT:]
        truncated = True

    if truncated:
        logger.info(
            "workflow_context_truncated",
            extra={"request_id": state.request_id, "turn_number": state.turn_number},
        )

    return state.replace(completed_node="normalize_context")


# ---------------------------------------------------------------------------
# Node 2 — generate_content (LLM call, English-first)
# ---------------------------------------------------------------------------

async def node_generate_content(
    state: WorkflowState, request: Any, settings: Settings, client: httpx.AsyncClient
) -> WorkflowState:
    _check_deadline(state, "generate_content")
    started = time.monotonic()
    content = await _generate_content(request, settings, client, deadline=state.deadline)
    logger.info(
        "workflow_node_done",
        extra={
            "node": "generate_content",
            "request_id": state.request_id,
            "duration_ms": round((time.monotonic() - started) * 1000),
        },
    )
    return state.replace(content=content, completed_node="generate_content")


# ---------------------------------------------------------------------------
# Node 3 — translate (LLM call, low temperature)
# ---------------------------------------------------------------------------

async def node_translate(
    state: WorkflowState, request: Any, settings: Settings, client: httpx.AsyncClient
) -> WorkflowState:
    if state.content is None:
        raise OllamaError("translate node reached without content")
    _check_deadline(state, "translate")
    started = time.monotonic()
    translated = await translate_task(state.content, request, settings, client, deadline=state.deadline)
    merged = merge_content_and_translation(state.content, translated)
    logger.info(
        "workflow_node_done",
        extra={
            "node": "translate",
            "request_id": state.request_id,
            "duration_ms": round((time.monotonic() - started) * 1000),
        },
    )
    return state.replace(
        translation=translated, merged=merged, completed_node="translate"
    )


# ---------------------------------------------------------------------------
# Node 4 — validate (deterministic, no LLM)
# ---------------------------------------------------------------------------

def node_validate(state: WorkflowState, request: Any = None) -> WorkflowState:
    if state.merged is None:
        raise OllamaError("validate node reached without merged task")
    level = getattr(request, "level", None)
    profile = getattr(getattr(request, "context", None), "levelProfile", None)
    artifact = selected_artifact.get() or {}
    shadow_profile = (artifact.get("levelProfiles", {}).get(level)
                      if isinstance(request, ShadowConversationTaskRequest) and artifact.get("evaluationOnly") is True else None)
    errors = validate_task(state.merged, level=level if isinstance(level, str) else None,
                           level_profile=profile if isinstance(profile, dict) else None, shadow_profile=shadow_profile)
    if errors:
        logger.warning(
            "workflow_validation_errors errors=%s", errors,
            extra={
                "request_id": state.request_id,
                "attempt": state.attempt,
                "errors": errors,
            },
        )
    return state.replace(
        validation_errors=tuple(errors), completed_node="validate"
    )


# ---------------------------------------------------------------------------
# Node 5 — repair_once (LLM call, at most one retry)
# ---------------------------------------------------------------------------

async def node_repair_once(
    state: WorkflowState, request: Any, settings: Settings, client: httpx.AsyncClient
) -> WorkflowState:
    """Re-run generate_content → translate → validate exactly once.

    If the repaired output still has validation errors, raises OllamaError.
    This node must never be called twice for the same request.
    """
    if state.attempt >= 2:
        raise OllamaError(
            f"repair_once already exhausted (attempt={state.attempt}) "
            f"trace_id={state.trace_id}"
        )
    _check_deadline(state, "repair_once")
    logger.info(
        "workflow_repair_attempt",
        extra={"request_id": state.request_id, "errors": list(state.validation_errors)},
    )
    repaired = state.replace(attempt=2, content=None, translation=None, merged=None)
    if isinstance(request, ConversationTaskRequest):
        # Give the single repair its actual defects without mutating the original caller context.
        request = request.model_copy(update={"context": request.context.model_copy(update={
            "validationError": "; ".join(state.validation_errors)[:4000],
            "previousInvalidPrompt": state.merged,
        })})
    repaired = await node_generate_content(repaired, request, settings, client)
    repaired = await node_translate(repaired, request, settings, client)
    repaired = node_validate(repaired, request)
    if repaired.validation_errors:
        raise OllamaError(
            f"repaired task still invalid: {list(repaired.validation_errors)} "
            f"trace_id={state.trace_id}"
        )
    return repaired.replace(completed_node="repair_once")


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

async def run_workflow(
    request: Any, settings: Settings, client: httpx.AsyncClient
) -> dict[str, Any]:
    """Run the full conversation generation workflow and return the merged public task.

    Raises
    ------
    TimeoutError
        If the deadline is exhausted before any node starts.
    OllamaError
        If generation, translation, or repair fails irrecoverably.
    """
    deadline = time.monotonic() + settings.timeout_seconds
    state = WorkflowState(
        request_id=getattr(request, "requestId", "unknown"),
        turn_number=getattr(request, "turnNumber", 1),
        deadline=deadline,
        prompt_version=getattr(request, "promptVersion", "unknown"),
        model=settings.model,
    )

    state = normalize_context(state, request)
    state = await node_generate_content(state, request, settings, client)
    state = await node_translate(state, request, settings, client)
    state = node_validate(state, request)

    if state.validation_errors:
        if not settings.repair_enabled:
            raise OllamaError(
                f"first-attempt task invalid (repair disabled): {list(state.validation_errors)} "
                f"trace_id={state.trace_id}"
            )
        state = await node_repair_once(state, request, settings, client)

    logger.info(
        "workflow_completed",
        extra={
            "request_id": state.request_id,
            "attempt": state.attempt,
            "trace_id": state.trace_id,
            "validation_errors_first_attempt": bool(state.attempt > 1),
        },
    )
    assert state.merged is not None  # guaranteed by node_translate / node_repair_once
    return state.merged
