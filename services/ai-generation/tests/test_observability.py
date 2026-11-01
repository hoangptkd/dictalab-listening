"""Tracing must preserve AI results, privacy, concurrency and offline evaluation."""
import asyncio
import json
import time
from unittest.mock import patch
from uuid import uuid4

import httpx
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app import observability as obs
from app.config import Settings
from app.model_gateway import ModelGateway
from app.providers.base import ProviderError


@pytest.fixture
def tracing(monkeypatch):
    from langfuse import Langfuse
    from langfuse._client.span import LangfuseObservationWrapper
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    sdk = Langfuse(public_key="pk-lf-test-" + str(uuid4()), secret_key="test-only", tracer_provider=provider,
                   span_exporter=exporter, flush_at=1000)
    monkeypatch.setenv("LANGFUSE_TRACING_ENABLED", "true")
    monkeypatch.setenv("DICTALAB_AI_TRACE_CONTENT", "true")
    monkeypatch.setattr(obs, "_client", sdk)
    scores = []
    monkeypatch.setattr(LangfuseObservationWrapper, "score", lambda self, **kw: scores.append(kw))
    yield exporter, scores
    provider.shutdown()


async def complete(envelope, settings=None):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=envelope))) as client:
        return await ModelGateway(settings or Settings(), client).complete(
            [{"role": "system", "content": "Return JSON"}, {"role": "user", "content": "test@example.com"}],
            {"type": "object"}, time.monotonic()+30, "scenario_generate", temperature=0)


def ollama(raw='{"hello":"world"}'):
    return {"model": "actual-model", "done": True, "done_reason": "stop", "message": {"content": raw},
            "prompt_eval_count": 21, "eval_count": 8}


def test_real_sdk_hierarchy_model_tokens_redaction_and_gate(tracing):
    exporter, scores = tracing
    @obs.trace_gate
    def validate_example():
        return True
    with obs.evaluation_context(evaluationRun="test", datasetHash="a"*64):
        with obs.operation("ai.scenario.generate", Settings(), {"inputVi": "hello"}, {"promptHash": "b"*64}):
            asyncio.run(complete(ollama()))
            validate_example()
    spans = {s.name: s for s in exporter.get_finished_spans()}
    root, generation = spans["ai.scenario.generate"], spans["scenario_generate"]
    assert generation.parent.span_id == root.context.span_id
    assert generation.context.trace_id == root.context.trace_id
    attrs = generation.attributes
    assert attrs["langfuse.observation.model.name"] == "actual-model"
    assert json.loads(attrs["langfuse.observation.usage_details"]) == {"input": 21, "output": 8}
    assert "test@example.com" not in str(attrs)
    assert "[REDACTED]" in str(attrs)
    assert "a"*64 in str(attrs)  # Trusted metadata hashes must remain comparable.
    assert "evaluation" in str(root.attributes)
    assert scores[-1]["name"] == "gate.validate_example" and scores[-1]["value"] == 1


def test_failed_parse_keeps_raw_response_and_tokens_but_no_exception_text(tracing):
    exporter, _ = tracing
    raw = "invalid json test@example.com Bearer password"
    with pytest.raises(ProviderError):
        asyncio.run(complete(ollama(raw)))
    span = exporter.get_finished_spans()[-1]
    assert "invalid json" in span.attributes["langfuse.observation.output"]
    assert "test@example.com" not in str(span.attributes)
    assert json.loads(span.attributes["langfuse.observation.usage_details"])["output"] == 8
    assert not span.events
    assert span.attributes["langfuse.observation.level"] == "ERROR"


def test_cloud_cached_tokens_are_not_double_counted(tracing):
    exporter, _ = tracing
    settings = Settings(primary_provider="gemini", gemini_api_key="test")
    asyncio.run(complete({"model": "actual-cloud", "choices": [{"finish_reason": "stop", "message": {
        "content": '{"ok":true}', "reasoning_content": "safe reasoning"}}],
        "usage": {"prompt_tokens": 20, "completion_tokens": 3, "prompt_tokens_details": {"cached_tokens": 5}}}, settings))
    attrs = exporter.get_finished_spans()[-1].attributes
    assert json.loads(attrs["langfuse.observation.usage_details"]) == {"input": 15, "input_cached_tokens": 5, "output": 3}
    assert "safe reasoning" in str(attrs)


def test_export_failure_never_repeats_execution(monkeypatch):
    monkeypatch.setenv("LANGFUSE_TRACING_ENABLED", "true")
    class Broken:
        def start_observation(self, **_):
            raise RuntimeError("private provider credential")
    monkeypatch.setattr(obs, "_client", Broken())
    calls = []
    @obs.trace_generation
    async def run(self, *_, **__):
        calls.append(1)
        return {"ok": True}, "ollama"
    gateway = ModelGateway(Settings(), None)
    assert asyncio.run(run(gateway, [], {}, 10, "generate"))[0] == {"ok": True}
    assert calls == [1]


def test_disabled_and_offline_never_initialize_sdk(monkeypatch):
    monkeypatch.setenv("LANGFUSE_TRACING_ENABLED", "false")
    monkeypatch.setattr(obs, "_client", None)
    asyncio.run(complete(ollama()))
    assert obs._client is None
    monkeypatch.setenv("LANGFUSE_TRACING_ENABLED", "true")
    with obs.suspend_tracing():
        asyncio.run(complete(ollama()))
    assert obs._client is None


def test_content_is_opt_in_bounded_and_redacted(monkeypatch):
    monkeypatch.delenv("DICTALAB_AI_TRACE_CONTENT", raising=False)
    assert obs.content({"secret": "hidden"}) == {"capture": "disabled"}
    monkeypatch.setenv("DICTALAB_AI_TRACE_CONTENT", "true")
    assert obs.content({"api_key": "hidden"})["api_key"] == "[REDACTED]"
    assert obs.content("word "*20000)["truncated"] is True


def test_concurrent_requests_do_not_share_roots(tracing):
    exporter, _ = tracing
    async def run(name):
        with obs.operation(name, Settings(), "input", {}):
            await asyncio.sleep(0)
            await complete(ollama())
    async def both():
        await asyncio.gather(run("one"), run("two"))
    asyncio.run(both())
    spans = exporter.get_finished_spans()
    roots = {s.context.trace_id for s in spans if s.name in ("one", "two")}
    assert len(roots) == 2
    assert {s.context.trace_id for s in spans if s.name == "scenario_generate"} == roots


def test_job_root_is_not_duplicated_by_worker_and_rejection_keeps_gate(tracing):
    from test_scenario_workflow import payload, request, generation_responses
    from app.worker import execute
    from app.scenario_workflow import QualityRejected
    exporter, scores = tracing
    # Invalid dialogue length: fail closed after exactly one actual gateway invocation.
    dialogue = generation_responses()[0]
    for segment in dialogue["activities"][0]["segments"]:
        segment["textEn"] = "Hello"
    envelope = ollama(json.dumps(dialogue))
    real_client = httpx.AsyncClient
    def local_client(**options):
        return real_client(**options, transport=httpx.MockTransport(lambda _: httpx.Response(200, json=envelope)))
    with patch("app.scenario_workflow.httpx.AsyncClient", local_client), pytest.raises(QualityRejected):
        asyncio.run(execute(request(payload()), Settings(scenario_enabled=True)))
    spans = exporter.get_finished_spans()
    assert len([s for s in spans if s.name == "ai.scenario.generate"]) == 1
    assert len([s for s in spans if s.name == "scenario_generate"]) == 1
    assert any(s["name"] == "operation_success" and s["value"] == 0 for s in scores)
    assert any(s["name"] == "gate.validate_dialogue" and s["value"] == 0 for s in scores)
    assert "QUALITY_REJECTED" in str(spans[-1].attributes)


def test_configuration_tracks_prompt_and_trained_weights(tracing, monkeypatch):
    exporter, _ = tracing
    for adapter, prompt in (("a"*64, "prompt-1"), ("b"*64, "prompt-1"), ("b"*64, "prompt-2")):
        monkeypatch.setenv("DICTALAB_AI_TRACE_ADAPTER_HASH", adapter)
        with obs.operation("compare", Settings(), "same input", {"promptHash": prompt}):
            pass
    assert len({s.attributes["langfuse.version"] for s in exporter.get_finished_spans()}) == 3


def test_repeated_gate_upserts_one_final_score(tracing):
    _, scores = tracing
    @obs.trace_gate
    def gate(valid):
        if not valid:
            raise ValueError("rejected")
    with obs.operation("test", Settings(), None, {}):
        gate(True)
        with pytest.raises(ValueError):
            gate(False)
    assert scores[0]["score_id"] == scores[1]["score_id"]
    assert [s["value"] for s in scores] == [1, 0]
