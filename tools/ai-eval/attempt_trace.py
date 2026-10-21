"""Attempt-level observation for the offline benchmark runner only.

The public workflow keeps the last accepted task, so a repaired sample hides
whatever attempt 1 produced. This module wraps the existing workflow nodes so
every validation attempt is written to a JSONL sidecar before the workflow
decides to repair or reject. It never changes prompts, seeds, schemas, options,
temperatures or repair counts, and it never swallows exceptions.

Only ``tools/ai-eval`` imports this module; production paths never do.
"""
from __future__ import annotations

import contextvars
import json
import re
import time
from pathlib import Path
from typing import Any

_current: contextvars.ContextVar[dict[str, Any] | None] = contextvars.ContextVar(
    "cefr_attempt_observation", default=None
)


class ObservationInactive(RuntimeError):
    """Raised when a caller emits a row outside ``observe``."""


def word_count(value: Any) -> int:
    """Count words exactly like the Java/Python validator (ASCII whitespace)."""
    stripped = value.strip(" \t\n\r\f\v") if isinstance(value, str) else ""
    return len(re.split(r"[ \t\n\r\f\v]+", stripped)) if stripped else 0


def _append(path: Path, row: dict[str, Any]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def _emit(row: dict[str, Any], *, phase: str, attempt: int, status: str,
          validation_errors: Any = (), merged: Any = None, detail: str = "") -> None:
    _append(Path(row["path"]), {
        "requestId": row["requestId"],
        "level": row["level"],
        "turn": row["turn"],
        "seed": row["seed"],
        "attempt": attempt,
        "phase": phase,
        "status": status,
        "wordCount": word_count(merged.get("utteranceEn")) if isinstance(merged, dict) else None,
        "validationErrors": list(validation_errors),
        "merged": merged,
        "modelCalls": dict(row["modelCalls"].get(str(attempt), {})),
        "detail": detail,
    })


class observe:
    """Activate one sidecar record for a single offline workflow run."""

    def __init__(self, path: Path, *, request_id: str, level: str, turn: int, seed: int):
        self.path = str(path)
        self.initials = {"requestId": request_id, "level": level, "turn": turn, "seed": seed}
        self.token: contextvars.Token | None = None
        self.row: dict[str, Any] | None = None

    def __enter__(self) -> "observe":
        self.row = {**self.initials, "path": self.path, "modelCalls": {}}
        self.token = _current.set(self.row)
        return self

    def __exit__(self, *exc_info: Any) -> bool:
        if self.token is not None:
            _current.reset(self.token)
        self.token = None
        return False


def _note_duration(row: dict[str, Any], node: str, attempt: int,
                   started: float) -> None:
    calls = row["modelCalls"].setdefault(str(attempt), {})
    calls[node] = round(calls.get(node, 0.0) + time.monotonic() - started, 3)


def _active() -> dict[str, Any] | None:
    return _current.get()


def record_failure(kind: str, detail: str = "") -> None:
    """Record a failure that happened outside validation (timeout, transport...)."""
    row = _active()
    if row is None:
        raise ObservationInactive("attempt observation is not active")
    _emit(row, phase=row.get("phase", "workflow"), attempt=int(row.get("attempt", 1)),
          status=kind, detail=detail[:400])


def install() -> None:
    """Wrap the workflow nodes once; each wrapper calls its original exactly once."""
    from app import workflow

    if getattr(workflow, "_attempt_observation_installed", False):
        return
    original_generate = workflow.node_generate_content
    original_translate = workflow.node_translate
    original_validate = workflow.node_validate

    async def _timed(node: str, state: Any, factory: Any) -> Any:
        row = _active()
        if row is None:
            return await factory()
        attempt = int(getattr(state, "attempt", 1))
        row["phase"], row["attempt"] = node, attempt
        started = time.monotonic()
        try:
            result = await factory()
        except BaseException as error:  # never swallowed: the workflow must see it
            _note_duration(row, node, attempt, started)
            _emit(row, phase=node, attempt=attempt, status=type(error).__name__,
                  detail=str(error)[:400])
            raise
        _note_duration(row, node, attempt, started)
        return result

    async def node_generate_content(state: Any, request: Any, settings: Any, client: Any) -> Any:
        return await _timed("generate_content", state,
                            lambda: original_generate(state, request, settings, client))

    async def node_translate(state: Any, request: Any, settings: Any, client: Any) -> Any:
        return await _timed("translate", state,
                            lambda: original_translate(state, request, settings, client))

    def node_validate(state: Any, request: Any = None) -> Any:
        result = original_validate(state, request)
        row = _active()
        if row is not None:
            attempt = int(getattr(state, "attempt", 1))
            row["phase"], row["attempt"] = "validate", attempt
            _emit(row, phase="validate", attempt=attempt,
                  status="valid" if not result.validation_errors else "invalid",
                  validation_errors=result.validation_errors, merged=state.merged)
        return result

    workflow.node_generate_content = node_generate_content
    workflow.node_translate = node_translate
    workflow.node_validate = node_validate
    workflow._attempt_observation_installed = True
