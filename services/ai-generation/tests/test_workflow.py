"""T03 — Unit tests for workflow nodes and the full run_workflow pipeline.

All LLM calls are mocked. No Ollama connection required.
"""
from __future__ import annotations

import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.ollama import OllamaError
from app.state import WorkflowState
from app.validation import validate_task
from app.workflow import (
    node_generate_content,
    node_repair_once,
    node_translate,
    node_validate,
    normalize_context,
    run_workflow,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _good_content() -> dict[str, Any]:
    return {
        "slots": [
            {"kind": "time", "askVi": "Mấy giờ?"},
            {"kind": "location", "askVi": "Ở đâu?"},
        ],
        "utteranceEn": "The meeting is at nine in the lobby.",
        "facts": [
            {"textEn": "Nine o'clock", "evidenceEn": "at nine"},
            {"textEn": "The lobby", "evidenceEn": "in the lobby"},
        ],
        "questionVi": "Cuộc họp lúc mấy giờ?",
        "taskVi": "Nghe và trả lời.",
        "skill": "Nắm thông tin cụ thể",
    }


def _good_translation() -> dict[str, Any]:
    return {
        "translationVi": "Cuộc họp lúc chín giờ trong sảnh.",
        "facts": [
            {"textVi": "Chín giờ"},
            {"textVi": "Sảnh"},
        ],
    }


def _good_merged() -> dict[str, Any]:
    return {
        "slots": [
            {"kind": "time", "askVi": "Mấy giờ?"},
            {"kind": "location", "askVi": "Ở đâu?"},
        ],
        "utteranceEn": "The meeting is at nine in the lobby.",
        "facts": [
            {"textVi": "Chín giờ", "evidenceEn": "at nine"},
            {"textVi": "Sảnh", "evidenceEn": "in the lobby"},
        ],
        "questionVi": "Cuộc họp lúc mấy giờ?",
        "taskVi": "Nghe và trả lời.",
        "skill": "Nắm thông tin cụ thể",
        "translationVi": "Cuộc họp lúc chín giờ trong sảnh.",
    }


def _make_request(turn_number: int = 1) -> MagicMock:
    req = MagicMock()
    req.requestId = "req-test"
    req.turnNumber = turn_number
    req.promptVersion = "V1"
    ctx = MagicMock()
    ctx.history = []
    ctx.recentPractice = []
    req.context = ctx
    return req


def _make_settings() -> MagicMock:
    s = MagicMock()
    s.model = "qwen3.5:9b"
    s.timeout_seconds = 60.0
    s.temperature = 0.3
    s.translation_temperature = 0.1
    s.num_ctx = 8192
    s.num_predict = 1000
    s.repair_enabled = False
    s.ollama_url = "http://127.0.0.1:11434"
    return s


def _state_with_deadline(seconds: float = 60.0, **kw) -> WorkflowState:
    return WorkflowState(deadline=time.monotonic() + seconds, **kw)


# ---------------------------------------------------------------------------
# validate_task — deterministic unit tests
# ---------------------------------------------------------------------------

class TestValidateTask:
    def test_good_merged_passes(self):
        assert validate_task(_good_merged()) == []

    def test_missing_utterance(self):
        task = {**_good_merged(), "utteranceEn": ""}
        errors = validate_task(task)
        assert any("utteranceEn" in e for e in errors)

    def test_evidence_not_substring(self):
        task = {**_good_merged()}
        task["facts"] = [
            {"textVi": "Chín giờ", "evidenceEn": "FABRICATED evidence not in utterance"},
            {"textVi": "Sảnh", "evidenceEn": "in the lobby"},
        ]
        errors = validate_task(task)
        assert any("not_substring" in e for e in errors)

    def test_fact_slot_count_mismatch(self):
        task = {**_good_merged()}
        task["facts"] = [{"textVi": "only one", "evidenceEn": "at nine"}]
        errors = validate_task(task)
        assert any("count_mismatch" in e for e in errors)

    def test_answer_leaked_in_question(self):
        task = {**_good_merged()}
        task["questionVi"] = "Cuộc họp lúc Chín giờ không?"
        errors = validate_task(task)
        assert any("leaked_in_questionVi" in e for e in errors)

    def test_empty_skill(self):
        task = {**_good_merged(), "skill": ""}
        assert any("skill" in e for e in validate_task(task))

    def test_empty_translation_vi(self):
        task = {**_good_merged(), "translationVi": ""}
        assert any("translationVi" in e for e in validate_task(task))

    def test_empty_evidence_en(self):
        task = {**_good_merged()}
        task["facts"] = [
            {"textVi": "Chín giờ", "evidenceEn": ""},
            {"textVi": "Sảnh", "evidenceEn": "in the lobby"},
        ]
        assert any("evidenceEn:empty" in e for e in validate_task(task))


# ---------------------------------------------------------------------------
# normalize_context — deterministic
# ---------------------------------------------------------------------------

class TestNormalizeContext:
    def test_short_history_unchanged(self):
        req = _make_request()
        req.context.history = [{"utteranceEn": "Hello"} for _ in range(5)]
        state = _state_with_deadline()
        result = normalize_context(state, req)
        assert result.completed_node == "normalize_context"

    def test_long_history_is_truncated(self):
        req = _make_request()
        req.context.history = [{"utteranceEn": "x"} for _ in range(15)]
        state = _state_with_deadline()
        result = normalize_context(state, req)
        assert result.completed_node == "normalize_context"

    def test_returns_new_state_not_same_object(self):
        req = _make_request()
        state = _state_with_deadline()
        result = normalize_context(state, req)
        assert result is not state


# ---------------------------------------------------------------------------
# node_generate_content
# ---------------------------------------------------------------------------

class TestNodeGenerateContent:
    @pytest.mark.asyncio
    async def test_success_stores_content(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline()
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())):
            result = await node_generate_content(state, req, settings, MagicMock())
        assert result.content == _good_content()
        assert result.completed_node == "generate_content"

    @pytest.mark.asyncio
    async def test_deadline_exhausted_raises_timeout(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(seconds=-1.0)  # already past
        with pytest.raises(TimeoutError, match="deadline_before_node"):
            await node_generate_content(state, req, settings, MagicMock())

    @pytest.mark.asyncio
    async def test_ollama_error_propagates(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline()
        with patch("app.workflow._generate_content", new=AsyncMock(side_effect=OllamaError("fail"))):
            with pytest.raises(OllamaError):
                await node_generate_content(state, req, settings, MagicMock())


# ---------------------------------------------------------------------------
# node_translate
# ---------------------------------------------------------------------------

class TestNodeTranslate:
    @pytest.mark.asyncio
    async def test_success_stores_merged(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(content=_good_content())
        with patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", return_value=_good_merged()):
            result = await node_translate(state, req, settings, MagicMock())
        assert result.merged == _good_merged()
        assert result.completed_node == "translate"

    @pytest.mark.asyncio
    async def test_no_content_raises_ollama_error(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(content=None)
        with pytest.raises(OllamaError, match="without content"):
            await node_translate(state, req, settings, MagicMock())

    @pytest.mark.asyncio
    async def test_deadline_exhausted_raises_timeout(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(seconds=-1.0, content=_good_content())
        with pytest.raises(TimeoutError):
            await node_translate(state, req, settings, MagicMock())


# ---------------------------------------------------------------------------
# node_validate
# ---------------------------------------------------------------------------

class TestNodeValidate:
    def test_valid_merged_sets_empty_errors(self):
        state = _state_with_deadline(merged=_good_merged())
        result = node_validate(state)
        assert result.validation_errors == ()
        assert result.completed_node == "validate"

    def test_invalid_merged_records_errors(self):
        bad = {**_good_merged(), "utteranceEn": ""}
        state = _state_with_deadline(merged=bad)
        result = node_validate(state)
        assert len(result.validation_errors) > 0

    def test_no_merged_raises(self):
        state = _state_with_deadline(merged=None)
        with pytest.raises(OllamaError):
            node_validate(state)


# ---------------------------------------------------------------------------
# node_repair_once
# ---------------------------------------------------------------------------

class TestNodeRepairOnce:
    @pytest.mark.asyncio
    async def test_repair_succeeds_on_first_retry(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(
            merged=_good_merged(),
            validation_errors=("utteranceEn:empty",),
            attempt=1,
        )
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())), \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", return_value=_good_merged()):
            result = await node_repair_once(state, req, settings, MagicMock())
        assert result.attempt == 2
        assert result.validation_errors == ()

    @pytest.mark.asyncio
    async def test_repair_twice_raises_ollama_error(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(
            merged=_good_merged(),
            validation_errors=("utteranceEn:empty",),
            attempt=2,  # already repaired once
        )
        with pytest.raises(OllamaError, match="already exhausted"):
            await node_repair_once(state, req, settings, MagicMock())

    @pytest.mark.asyncio
    async def test_repair_still_invalid_raises(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(
            merged={**_good_merged(), "utteranceEn": ""},
            validation_errors=("utteranceEn:empty",),
            attempt=1,
        )
        # Both generate and translate succeed but merged is still invalid
        bad_merged = {**_good_merged(), "utteranceEn": ""}
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())), \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", return_value=bad_merged):
            with pytest.raises(OllamaError, match="still invalid"):
                await node_repair_once(state, req, settings, MagicMock())

    @pytest.mark.asyncio
    async def test_repair_deadline_exhausted(self):
        req = _make_request()
        settings = _make_settings()
        state = _state_with_deadline(
            seconds=-1.0,
            merged=_good_merged(),
            validation_errors=("utteranceEn:empty",),
            attempt=1,
        )
        with pytest.raises(TimeoutError):
            await node_repair_once(state, req, settings, MagicMock())


# ---------------------------------------------------------------------------
# run_workflow — integration (all nodes mocked)
# ---------------------------------------------------------------------------

class TestRunWorkflow:
    @pytest.mark.asyncio
    async def test_happy_path_returns_merged(self):
        req = _make_request()
        settings = _make_settings()
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())), \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", return_value=_good_merged()):
            result = await run_workflow(req, settings, MagicMock())
        assert result == _good_merged()

    @pytest.mark.asyncio
    async def test_invalid_output_triggers_repair(self):
        req = _make_request()
        settings = _make_settings()
        settings.repair_enabled = True
        bad_merged = {**_good_merged(), "utteranceEn": ""}
        call_count = {"n": 0}

        async def content_side_effect(*_a, **_kw):
            call_count["n"] += 1
            return _good_content()

        def merge_side_effect(content, translation):
            # First merge produces bad output; second (repair) produces good
            if call_count["n"] == 1:
                return bad_merged
            return _good_merged()

        with patch("app.workflow._generate_content", new=AsyncMock(side_effect=content_side_effect)), \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", side_effect=merge_side_effect):
            result = await run_workflow(req, settings, MagicMock())
        assert call_count["n"] == 2   # generate_content called twice (original + repair)
        assert result == _good_merged()

    @pytest.mark.asyncio
    async def test_invalid_first_attempt_is_rejected_without_repair_by_default(self):
        from app.config import Settings
        settings = Settings()
        assert settings.repair_enabled is False
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())) as generate, \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())) as translate, \
             patch("app.workflow.merge_content_and_translation", return_value={**_good_merged(), "utteranceEn": ""}), \
             patch("app.workflow.node_repair_once", new=AsyncMock()) as repair:
            with pytest.raises(OllamaError, match="first-attempt task invalid.*repair disabled"):
                await run_workflow(_make_request(), settings, MagicMock())
        generate.assert_awaited_once()
        translate.assert_awaited_once()
        repair.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_timeout_at_generate_raises_timeout_error(self):
        req = _make_request()
        settings = _make_settings()
        settings.timeout_seconds = -1.0  # deadline already past at workflow start
        with pytest.raises(TimeoutError):
            await run_workflow(req, settings, MagicMock())

    @pytest.mark.asyncio
    async def test_turn_4_does_not_crash(self):
        """Turn 4 workflow must complete; prompt layer handles new-encounter framing."""
        req = _make_request(turn_number=4)
        settings = _make_settings()
        with patch("app.workflow._generate_content", new=AsyncMock(return_value=_good_content())), \
             patch("app.workflow.translate_task", new=AsyncMock(return_value=_good_translation())), \
             patch("app.workflow.merge_content_and_translation", return_value=_good_merged()):
            result = await run_workflow(req, settings, MagicMock())
        assert result == _good_merged()

    @pytest.mark.asyncio
    async def test_same_deterministic_nodes_give_same_state(self):
        """normalize_context must be deterministic for identical inputs."""
        req = _make_request()
        settings = _make_settings()
        state1 = WorkflowState(request_id="r1", deadline=time.monotonic() + 60)
        state2 = WorkflowState(request_id="r1", deadline=time.monotonic() + 60)
        r1 = normalize_context(state1, req)
        r2 = normalize_context(state2, req)
        assert r1.completed_node == r2.completed_node
