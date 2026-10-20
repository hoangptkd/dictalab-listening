"""Automated validation (Cổng 1 + partial Cổng 2) for generated conversation tasks.

All checks are deterministic (no LLM calls). The validate node runs these against
a merged task dict and returns a list of human-readable error strings.
An empty list means the task passed all automated checks.

Checks performed
----------------
1. Required fields present and non-empty.
2. Fact count equals slot count.
3. evidenceEn is an exact substring of utteranceEn (no fabrication, no ellipsis).
4. Answer not leaked: answer values must not appear verbatim in questionVi or taskVi.
5. Skill present and non-empty string.
6. Slot kinds present and non-empty.
7. translationVi and textVi fields present and non-empty.
"""
from __future__ import annotations

from typing import Any


def _str(value: Any) -> str:
    return value if isinstance(value, str) else ""


def validate_task(task: dict[str, Any]) -> list[str]:
    """Return a list of error strings; empty list means the task passed all checks."""
    errors: list[str] = []

    # 1. Top-level required fields
    for field in ("slots", "utteranceEn", "facts", "questionVi", "taskVi", "skill",
                  "translationVi"):
        if not task.get(field):
            errors.append(f"missing_or_empty_field:{field}")

    slots = task.get("slots") or []
    facts = task.get("facts") or []
    utterance = _str(task.get("utteranceEn"))
    question = _str(task.get("questionVi"))
    task_text = _str(task.get("taskVi"))

    # 2. Fact count must equal slot count
    if isinstance(slots, list) and isinstance(facts, list) and len(slots) != len(facts):
        errors.append(
            f"fact_slot_count_mismatch:slots={len(slots)},facts={len(facts)}"
        )

    # 3. Each evidenceEn must be an exact non-empty substring of utteranceEn
    for i, fact in enumerate(facts if isinstance(facts, list) else []):
        evidence = _str(fact.get("evidenceEn")) if isinstance(fact, dict) else ""
        if not evidence:
            errors.append(f"fact[{i}].evidenceEn:empty")
        elif utterance and evidence not in utterance:
            errors.append(
                f"fact[{i}].evidenceEn:not_substring_of_utteranceEn"
            )

    # 4. Answer leak: answer values (textVi) must not appear verbatim in questionVi/taskVi
    for i, fact in enumerate(facts if isinstance(facts, list) else []):
        if not isinstance(fact, dict):
            continue
        text_vi = _str(fact.get("textVi"))
        if text_vi and len(text_vi) >= 4:  # ignore very short values (numbers etc.)
            if text_vi in question:
                errors.append(f"fact[{i}].textVi:leaked_in_questionVi")
            if text_vi in task_text:
                errors.append(f"fact[{i}].textVi:leaked_in_taskVi")

    # 5. Skill non-empty string
    skill = task.get("skill")
    if not isinstance(skill, str) or not skill.strip():
        errors.append("skill:empty_or_not_string")

    # 6. Slot kind and askVi non-empty
    for i, slot in enumerate(slots if isinstance(slots, list) else []):
        if not isinstance(slot, dict):
            errors.append(f"slot[{i}]:not_object")
            continue
        if not _str(slot.get("kind")).strip():
            errors.append(f"slot[{i}].kind:empty")
        if not _str(slot.get("askVi")).strip():
            errors.append(f"slot[{i}].askVi:empty")

    # 7. translationVi and textVi non-empty
    if not _str(task.get("translationVi")).strip():
        errors.append("translationVi:empty")
    for i, fact in enumerate(facts if isinstance(facts, list) else []):
        if isinstance(fact, dict) and not _str(fact.get("textVi")).strip():
            errors.append(f"fact[{i}].textVi:empty")

    return errors
