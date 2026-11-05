"""Explicit Gemini server rejection may change model, never replay an uncertain result."""
import json
import time
from uuid import uuid4
from unittest.mock import patch

import httpx
import pytest

from app.config import GeminiSource, Settings
from app.model_gateway import ModelGateway
from app.providers.base import ProviderError, ProviderHttpError

PRIMARY = "gemini-3.5-flash-lite"
FALLBACK = "gemini-3.1-flash-lite"
SECOND = "gemini-2.5-flash"


def config(models=(PRIMARY, FALLBACK), **changes):
    project = uuid4().hex
    sources = [GeminiSource(id=f"{account}-{index}", project_id=project + account,
               api_key="fake-" + account, model=model, enabled=True,
               priority=0 if account == "a" else 1)
               for index, model in enumerate(models) for account in ["a", "b"]]
    return Settings(**(dict(primary_provider="gemini", gemini_quota_enabled=True, gemini_quota_backend="memory",
                operational_limits_enabled=False, gemini_providers=sources, gemini_fallback_models=[FALLBACK]) | changes))


def rejected(status=503, array=False):
    error = {"error": {"code": status, "status": {500: "INTERNAL", 503: "UNAVAILABLE"}[status],
                       "message": "private-server-message"}}
    return httpx.Response(status, json=[error] if array else error)


def success():
    return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}}],
                                   "usage": {"prompt_tokens": 10, "completion_tokens": 3}})


@pytest.mark.parametrize("status,array", [(500, False), (500, True), (503, False), (503, True)])
async def test_server_rejection_changes_model_on_same_project_and_counts_one_request(status, array, monkeypatch, caplog):
    cfg, calls, events = config(), [], []
    monkeypatch.setattr("app.provider_admin.record_attempt", lambda *args: events.append(args))
    def handler(request):
        calls.append(request)
        return rejected(status, array) if len(calls) == 1 else success()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        gateway = ModelGateway(cfg, client)
        assert await gateway.complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=True) == ({"ok": True}, "gemini")
    assert [json.loads(r.content)["model"] for r in calls] == [PRIMARY, FALLBACK]
    assert len({r.headers["Authorization"] for r in calls}) == 1
    assert gateway.last_model == FALLBACK
    assert gateway.model_fallbacks == [{"fromModel": PRIMARY, "toModel": FALLBACK, "httpStatus": status}]
    assert [e[4] for e in events] == [False, True] and events[0][1] == events[1][1]
    assert [e[2].model for e in events] == [PRIMARY, FALLBACK]
    assert "private-server-message" not in caplog.text


@pytest.mark.parametrize("response", [
    httpx.Response(503, text="upstream disconnected"),
    httpx.Response(503, json={"error": {"code": 503, "status": "RESOURCE_EXHAUSTED"}}),
    httpx.Response(503, json={"error": {"code": 500, "status": "UNAVAILABLE"}}),
    httpx.Response(503, json={"error": {"code": 503, "status": "UNAVAILABLE"}, "choices": [{}]}),
    httpx.Response(502, json={"error": {"code": 502, "status": "UNAVAILABLE"}}),
    httpx.Response(504, json={"error": {"code": 504, "status": "DEADLINE_EXCEEDED"}}),
    httpx.Response(400), httpx.Response(401), httpx.Response(403),
    httpx.Response(200, json={"choices": [{"message": {"content": "not-json"}}]}),
])
async def test_unknown_gateway_account_and_invalid_output_errors_do_not_fallback(response):
    calls = []
    def handler(request):
        calls.append(request)
        return response
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderError):
            await ModelGateway(config(), client).complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=True)
    assert len(calls) == 1


async def test_429_rotates_sources_within_model_and_never_selects_fallback():
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(429)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderHttpError) as error:
            await ModelGateway(config(), client).complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=True)
    assert error.value.status == 429 and len(calls) == 2
    assert {json.loads(r.content)["model"] for r in calls} == {PRIMARY}


@pytest.mark.parametrize("opted,configured", [(False, True), (True, False)])
async def test_fallback_requires_both_opt_in_and_configured_enabled_sources(opted, configured):
    cfg = config() if configured else config(models=(PRIMARY,))
    calls = []
    def handler(request):
        calls.append(request)
        return rejected()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderHttpError):
            await ModelGateway(cfg, client).complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=opted)
    assert len(calls) == 1


async def test_fallback_chain_is_ordered_and_bounded_by_original_deadline():
    cfg = config(models=(PRIMARY, FALLBACK, SECOND), gemini_fallback_models=[FALLBACK, SECOND])
    calls, events = [], []
    def handler(request):
        calls.append(request)
        return rejected()
    deadline = time.monotonic()+10
    with patch("app.provider_admin.record_attempt", side_effect=lambda *args: events.append(args)):
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(ProviderHttpError):
                await ModelGateway(cfg, client).complete([], {}, deadline, "scenario_generate", allow_model_fallback=True)
    assert [json.loads(r.content)["model"] for r in calls] == [PRIMARY, FALLBACK, SECOND]
    assert [e[4] for e in events] == [False, False, True]
    assert len({e[1] for e in events}) == 1
    assert all(r.extensions["timeout"]["read"] <= 10 for r in calls)


async def test_global_quota_is_shared_with_fallback_and_not_reset_after_rejection():
    cfg = config(operational_limits_enabled=True, gemini_global_rpm_limit=1)
    calls, finished = [], []
    def handler(request):
        calls.append(request)
        return rejected()
    with patch("app.provider_admin.finish_request", side_effect=lambda *args: finished.append(args)):
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(ProviderHttpError) as error:
                await ModelGateway(cfg, client).complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=True)
    assert error.value.status == 503 and len(calls) == 1 and len(finished) == 1


async def test_read_timeout_never_issues_fallback_request():
    calls = []
    def handler(request):
        calls.append(request)
        raise httpx.ReadTimeout("private-uncertain-outcome")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(TimeoutError):
            await ModelGateway(config(), client).complete([], {}, time.monotonic()+10, "scenario_generate", allow_model_fallback=True)
    assert len(calls) == 1


@pytest.mark.parametrize("models", [[FALLBACK, FALLBACK], ["deepseek-flash"], ["gemini-unsafe\n"], [FALLBACK, SECOND, "gemini-x", "gemini-y"]])
def test_fallback_configuration_rejects_invalid_or_unbounded_chains(models):
    with pytest.raises(ValueError):
        Settings(gemini_fallback_models=models)


async def test_workflow_keeps_successful_steps_and_stays_on_fallback(monkeypatch):
    from app import scenario_workflow as workflow
    from test_scenario_workflow import personal_payload, personal_responses, request
    responses, calls = personal_responses(), []
    real_client = httpx.AsyncClient
    def handler(req):
        body = json.loads(req.content)
        calls.append(body)
        if len(calls) == 2:
            return rejected(array=True)
        index = len(calls) - 1 if len(calls) == 1 else len(calls) - 2
        return httpx.Response(200, json={"model": body["model"], "choices": [{"message": {
            "content": json.dumps(responses[index])}}]})
    monkeypatch.setattr(workflow.httpx, "AsyncClient", lambda **kw: real_client(transport=httpx.MockTransport(handler), **kw))
    result = await workflow.execute(request(personal_payload()), config(scenario_enabled=True))
    assert [c["model"] for c in calls] == [PRIMARY, PRIMARY, FALLBACK, FALLBACK, FALLBACK, FALLBACK]
    assert calls[1]["messages"] == calls[2]["messages"]
    assert [c["purpose"] for c in result["calls"]] == ["GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW"]
    assert [c["model"] for c in result["calls"]] == [PRIMARY] + [FALLBACK] * 4
    assert result["modelRouting"] == {"primaryModel": PRIMARY, "fallbackModels": [FALLBACK]}
    assert result["model"] == PRIMARY


def test_fallback_policy_is_part_of_job_fingerprint_and_disabled_is_legacy():
    from app.jobs import execution_fingerprint
    from app.scenario_workflow import metadata
    from test_scenario_workflow import personal_payload
    cfg = config()
    disabled = cfg.model_copy(update={"gemini_fallback_models": []})
    assert "modelRouting" not in metadata(disabled, True)
    assert execution_fingerprint(personal_payload(), cfg) != execution_fingerprint(personal_payload(), disabled)
    changed = cfg.model_copy(update={"gemini_fallback_models": [SECOND, FALLBACK]})
    assert execution_fingerprint(personal_payload(), cfg) != execution_fingerprint(personal_payload(), changed)
