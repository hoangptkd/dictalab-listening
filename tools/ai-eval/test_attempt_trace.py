"""Offline attempt observer: sidecar, one-call delegation, no behaviour change."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

import attempt_trace
import generate
from app import workflow
from app.ollama import OllamaError


def _request():
    return SimpleNamespace(requestId="case:17", turnNumber=2, promptVersion="test-version",
                           context=SimpleNamespace(history=[], recentPractice=[]))


def _settings():
    return SimpleNamespace(timeout_seconds=600, model="test-model")


def _fakes(calls, texts, *, valid_attempts):
    async def generate_content(state, request, settings, client):
        calls.append(("generate_content", state.attempt))
        return state.replace(content={"utteranceEn": texts[state.attempt - 1]},
                             completed_node="generate_content")

    async def translate(state, request, settings, client):
        calls.append(("translate", state.attempt))
        merged = {"utteranceEn": state.content["utteranceEn"]}
        return state.replace(translation={}, merged=merged, completed_node="translate")

    def validate(state, request=None):
        calls.append(("validate", state.attempt))
        errors = () if state.attempt in valid_attempts else (
            f"utteranceEn:word_count={attempt_trace.word_count(state.merged['utteranceEn'])},min=30,max=90",)
        return state.replace(validation_errors=tuple(errors), completed_node="validate")

    return generate_content, translate, validate


@pytest.fixture
def observed(monkeypatch, tmp_path):
    """Wrap fake nodes once, then restore the real workflow for other test files."""
    saved = (workflow.node_generate_content, workflow.node_translate, workflow.node_validate)
    state = {"calls": [], "generator": None}

    def activate(texts, *, valid_attempts):
        state["calls"].clear()
        state["generator"] = _fakes(state["calls"], texts, valid_attempts=valid_attempts)
        monkeypatch.setattr(workflow, "node_generate_content", state["generator"][0])
        monkeypatch.setattr(workflow, "node_translate", state["generator"][1])
        monkeypatch.setattr(workflow, "node_validate", state["generator"][2])
        attempt_trace.install()

    yield activate, state, tmp_path

    workflow.node_generate_content, workflow.node_translate, workflow.node_validate = saved
    workflow.__dict__.pop("_attempt_observation_installed", None)


def _rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line]


def test_sidecar_keeps_first_attempt_and_repair(observed):
    activate, state, tmp_path = observed
    activate(["one two three four", "one two three four five six"], valid_attempts={2})
    sidecar = tmp_path / "attempts.jsonl"
    with attempt_trace.observe(sidecar, request_id="case:17", level="B1", turn=2, seed=43):
        result = asyncio.run(workflow.run_workflow(_request(), _settings(), None))

    rows = _rows(sidecar)
    assert result == {"utteranceEn": "one two three four five six"}
    assert [(row["attempt"], row["status"]) for row in rows] == [(1, "invalid"), (2, "valid")]
    assert rows[0]["validationErrors"] == ["utteranceEn:word_count=4,min=30,max=90"]
    assert rows[0]["wordCount"] == 4 and rows[1]["wordCount"] == 6
    assert rows[0]["merged"]["utteranceEn"] == "one two three four"
    assert {row["requestId"] for row in rows} == {"case:17"}
    assert {row["seed"] for row in rows} == {43}
    # One wrapper call per real node call, no extra generation or repair.
    assert state["calls"] == [("generate_content", 1), ("translate", 1), ("validate", 1),
                              ("generate_content", 2), ("translate", 2), ("validate", 2)]


def test_observation_does_not_change_workflow_result_or_call_count(observed):
    activate, state, tmp_path = observed
    activate(["alpha beta gamma", "alpha beta gamma delta"], valid_attempts={2})
    clean = asyncio.run(workflow.run_workflow(_request(), _settings(), None))
    clean_calls = list(state["calls"])

    activate(["alpha beta gamma", "alpha beta gamma delta"], valid_attempts={2})
    with attempt_trace.observe(tmp_path / "a.jsonl", request_id="case:17", level="B1", turn=2, seed=17):
        observed_result = asyncio.run(workflow.run_workflow(_request(), _settings(), None))

    assert observed_result == clean
    assert state["calls"] == clean_calls


def test_rejected_repair_is_recorded_and_exception_propagates(observed):
    activate, _state, tmp_path = observed
    activate(["one two three", "one two three"], valid_attempts=set())
    sidecar = tmp_path / "blocked.jsonl"
    with attempt_trace.observe(sidecar, request_id="case:17", level="B1", turn=2, seed=71):
        with pytest.raises(OllamaError):
            asyncio.run(workflow.run_workflow(_request(), _settings(), None))

    rows = _rows(sidecar)
    assert [row["status"] for row in rows] == ["invalid", "invalid"]
    assert all(row["validationErrors"] for row in rows)


def test_transport_failure_keeps_phase_and_reraises(observed, monkeypatch):
    activate, _state, tmp_path = observed

    async def broken(state, request, settings, client):
        raise ConnectionError("provider_down")

    monkeypatch.setattr(workflow, "node_generate_content", broken)
    attempt_trace.install()
    sidecar = tmp_path / "transport.jsonl"
    with attempt_trace.observe(sidecar, request_id="case:9", level="A2", turn=1, seed=17):
        with pytest.raises(ConnectionError):
            asyncio.run(workflow.run_workflow(_request(), _settings(), None))
        attempt_trace.record_failure("timeout", "deadline_before_node:translate")

    rows = _rows(sidecar)
    assert rows[0]["phase"] == "generate_content" and rows[0]["status"] == "ConnectionError"
    assert rows[1]["status"] == "timeout"
    assert "word_count=" not in json.dumps(rows)


@pytest.mark.parametrize("text,expected", [("", 0), ("   ", 0), ("a b\tc\n d", 4), ("a  b", 2), ("a\u00a0b", 1)])
def test_word_count_matches_validator_whitespace_rule(text, expected):
    assert attempt_trace.word_count(text) == expected


def test_failure_outside_observation_is_loud():
    with pytest.raises(attempt_trace.ObservationInactive):
        attempt_trace.record_failure("timeout")


def test_seeded_client_body_is_unchanged(monkeypatch):
    seen = {}

    async def fake_post(self, url, **kwargs):
        seen.update(kwargs)
        return "response"

    monkeypatch.setattr(generate.httpx.AsyncClient, "post", fake_post)

    async def run():
        async with generate.SeededClient(43) as client:
            return await client.post("http://localhost/api/generate",
                                     json={"model": "qwen3.5:9b", "options": {"temperature": 0.3}, "prompt": "p"})

    assert asyncio.run(run()) == "response"
    assert seen["json"] == {"model": "qwen3.5:9b",
                            "options": {"temperature": 0.3, "seed": 43}, "prompt": "p"}
