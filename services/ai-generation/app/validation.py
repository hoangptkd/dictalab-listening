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
import re

# Standalone requests use these defaults; Java sends authoritative bounds, including legacy 20-65.
LEVEL_BOUNDS = {"A1": (8, 35), "A2": (18, 55), "B1": (30, 90)}
DIRECT_TASK_TYPES = {"direct_detail", "gist", "reason_purpose", "change_revision", "instruction_sequence", "condition"}
B1_TASK_TYPES = DIRECT_TASK_TYPES | {"opinion_attitude", "inference_prediction"}


def _str(value: Any) -> str:
    return value if isinstance(value, str) else ""


def validate_task(task: dict[str, Any], *, level: str | None = None,
                  level_profile: dict[str, Any] | None = None,
                  shadow_profile: dict[str, Any] | None = None) -> list[str]:
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
    shadow = (level in ("B2", "C1") and isinstance(shadow_profile, dict)
              and shadow_profile.get("status") == "SHADOW")
    allowed_types = (set(shadow_profile["taskTypes"]) if shadow else
                     {"direct_detail"} if level == "A1" else DIRECT_TASK_TYPES if level == "A2" else B1_TASK_TYPES)
    reasoning_types = {"inference_prediction", "opinion_attitude", "gist"}
    required_reasoning = {"inference_prediction"}
    if shadow:
        reasoning_types |= {"argument_relation", "implicit_intention"}
        required_reasoning |= {"argument_relation", "implicit_intention"}

    if level is not None:
        if level not in LEVEL_BOUNDS and not shadow:
            errors.append("level:unsupported")
        else:
            bounds = shadow_profile["wordBounds"] if shadow else None
            minimum, maximum = (bounds["minWords"], bounds["maxWords"]) if shadow else LEVEL_BOUNDS[level]
            if level_profile is not None:
                minimum = level_profile.get("minWords", minimum)
                maximum = level_profile.get("maxWords", maximum)
            if type(minimum) is not int or type(maximum) is not int or not 8 <= minimum <= maximum <= 90:
                errors.append("levelProfile:invalid_word_bounds")
            else:
                # Match Java trim().split("\\s+") for the English transcript.
                stripped = utterance.strip(" \t\n\r\f\v")
                words = len(re.split(r"[ \t\n\r\f\v]+", stripped)) if stripped else 0
                if not minimum <= words <= maximum:
                    errors.append(f"utteranceEn:word_count={words},min={minimum},max={maximum}")
            if not isinstance(facts, list) or not 2 <= len(facts) <= (2 if level == "A1" else 3):
                errors.append("facts:invalid_count_for_level")

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
        if not isinstance(fact, dict):
            continue
        task_type = fact.get("taskType")
        if shadow and "taskType" not in fact:
            errors.append(f"fact[{i}].taskType:required")
        if "taskType" in fact:
            if not isinstance(task_type, str) or task_type not in allowed_types:
                errors.append(f"fact[{i}].taskType:unsupported_for_level")
        reasoning = fact.get("reasoningEvidence")
        if isinstance(task_type, str) and task_type in required_reasoning and reasoning is None:
            errors.append(f"fact[{i}].reasoningEvidence:required")
        if "reasoningEvidence" in fact:
            if (level not in (None, "B1") and not shadow) or not isinstance(task_type, str) or task_type not in reasoning_types:
                errors.append(f"fact[{i}].reasoningEvidence:unsupported_for_task")
            if not isinstance(reasoning, dict) or set(reasoning) != {"segmentsEn", "rationaleEn"}:
                errors.append(f"fact[{i}].reasoningEvidence:invalid_shape")
                continue
            rationale = _str(reasoning.get("rationaleEn"))
            if not rationale.strip() or len(rationale) > 300:
                errors.append(f"fact[{i}].reasoningEvidence:invalid_rationale")
            segments = reasoning.get("segmentsEn")
            if not isinstance(segments, list) or not 1 <= len(segments) <= 4:
                errors.append(f"fact[{i}].reasoningEvidence:invalid_segments")
                continue
            seen = set()
            for segment in segments:
                quote = _str(segment)
                normalized = " ".join(re.findall(r"[^\W_]+", quote.lower()))
                if not quote.strip() or len(quote) > 300 or quote not in utterance or normalized in seen:
                    errors.append(f"fact[{i}].reasoningEvidence:ungrounded_or_duplicate_segment")
                seen.add(normalized)
            if not any(isinstance(segment, str) and evidence in segment for segment in segments):
                errors.append(f"fact[{i}].reasoningEvidence:primary_evidence_missing")

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
    if shadow and skill not in shadow_profile["skills"].values():
        errors.append("skill:unsupported_for_level")
    if skill == "Suy luận và dự đoán có căn cứ" and not any(
            isinstance(f, dict) and f.get("taskType") == "inference_prediction"
            for f in (facts if isinstance(facts, list) else [])):
        errors.append("skill:inference_requires_evidence")
    if not shadow and sum(isinstance(f, dict) and f.get("taskType") == "inference_prediction"
           for f in (facts if isinstance(facts, list) else [])) > 1:
        errors.append("facts:at_most_one_inference_target")

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
