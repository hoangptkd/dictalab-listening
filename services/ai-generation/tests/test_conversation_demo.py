from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.conversation_demo import (ConversationDemoInput, LocalUnavailable, execute, local_call,
    ADAPTER_SHA, BASE_REVISION)
from app.jobs import JobEvent, JobRequest, JobStore
from app.worker import process

TOKEN = "test-only-demo-token-at-least-32-characters"


def setup(tmp_path, **updates):
    settings = Settings(jobs_enabled=True, model_demo_enabled=True, service_token=TOKEN,
        model_serving_token=TOKEN, jobs_database=str(tmp_path / "jobs.sqlite3"), **updates)
    payload = ConversationDemoInput(level="A1", context={"scenario": "cafe", "turnNumber": 1})
    request = JobRequest(jobId=uuid4(), requestId=uuid4(), userScope="a" * 64, idempotencyKey=str(uuid4()),
        deadline=datetime.now(timezone.utc) + timedelta(seconds=1800), payload=payload)
    store = JobStore(settings.jobs_database)
    store.register(request, settings)
    event = JobEvent.model_validate_json(store.pending()[0])
    return settings, store, request, event


def test_long_cpu_jobs_survive_restart_and_keep_idempotency(tmp_path):
    settings, store, request, event = setup(tmp_path)
    runner = lambda _: {"generationId": "saved", "arms": []}
    assert process(event.model_dump_json(), settings, store, runner) == "ACK"
    restarted = JobStore(settings.jobs_database)
    assert restarted.get(str(event.jobId), event.userScope)["result"]["generationId"] == "saved"
    assert restarted.register(request, settings) == str(event.jobId)
    assert process(event.model_dump_json(), settings, restarted,
        lambda _: pytest.fail("duplicate inference")) == "ACK"


def test_compare_disallows_fallback_and_cloud():
    for updates in ({"allowFallback": True}, {"variant": "cloud"}, {"variant": "base"}):
        with pytest.raises(ValueError):
            ConversationDemoInput(level="A1", mode="compare", context={}, **updates)


async def test_comparison_is_sequential_same_input_and_keeps_failures(tmp_path):
    settings, store, request, event = setup(tmp_path)
    event.payload.mode = "compare"
    calls = []
    async def run(payload, config, frozen, variant, *args):
        calls.append((variant, payload.context.model_dump(), frozen["sha256"]))
        if variant == "base":
            raise LocalUnavailable("INVALID_MODEL_JSON", True, "invalid fixture")
        return {"variant": variant, "valid": True, "task": {}, "calls": []}
    with patch("app.conversation_demo.arm", side_effect=run):
        result = await execute(event, settings, store)
    assert [c[0] for c in calls] == ["base", "full07"]
    assert calls[0][1:] == calls[1][1:]
    assert result["arms"][0]["raw"] == "invalid fixture"
    assert result["semanticReview"] == "not_evaluated"
    assert result["evaluationOnly"] is True


@pytest.mark.parametrize("safe,opted,expected", [(True, True, 2), (True, False, 1), (False, True, 1)])
async def test_only_safe_local_failures_with_opt_in_attempt_fallback(tmp_path, safe, opted, expected):
    settings, store, request, event = setup(tmp_path)
    event.payload.allowFallback = opted
    async def run(payload, config, frozen, variant, *args):
        if variant != "cloud":
            raise LocalUnavailable("LOCAL_OUTCOME_UNKNOWN", safe)
        return {"variant": "cloud", "valid": True}
    with patch("app.conversation_demo.arm", side_effect=run) as mock:
        result = await execute(event, settings, store)
    assert mock.call_count == expected
    if expected == 2:
        assert result["arms"][-1]["fallbackFrom"] == "full07"


def test_budget_is_durable_atomic_and_reserves_both_calls_before_cloud(tmp_path):
    settings, store, request, event = setup(tmp_path, demo_daily_budget_microusd=100,
        demo_call_reservation_microusd=50, demo_daily_cloud_calls=2)
    def reserve(index):
        try:
            JobStore(settings.jobs_database).reserve_demo_cloud(str(index), request.userScope, settings, 2)
            return True
        except OverflowError:
            return False
    with ThreadPoolExecutor(max_workers=4) as workers:
        assert sum(workers.map(reserve, range(4))) == 1
    with store.connect() as db:
        assert [tuple(r) for r in db.execute("SELECT reserved,calls FROM demo_budget ORDER BY scope")] == [(100, 2), (100, 2)]
    with pytest.raises(OverflowError):
        store.reserve_demo_cloud("new", request.userScope, Settings(), 2)


async def test_inference_rejects_false_provenance_and_preserves_invalid_raw(tmp_path):
    settings, store, request, event = setup(tmp_path)
    def response(req):
        incoming = json.loads(req.content)
        return httpx.Response(200, json={"requestId": incoming["requestId"], "provider": "local_cpu",
            "model": "Qwen/Qwen3.5-4B", "baseRevision": BASE_REVISION, "variant": "full07",
            "adapterSha256": ADAPTER_SHA, "raw": "invalid json"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(response)) as client:
        import time
        with pytest.raises(LocalUnavailable) as error:
            await local_call(client, settings, [], {"type": "object"}, "full07", time.monotonic() + 30)
        assert error.value.raw == "invalid json" and error.value.fallback_safe
        with pytest.raises(LocalUnavailable) as error:
            await local_call(client, settings, [], {"type": "object"}, "base", time.monotonic() + 30)
        assert error.value.code == "MODEL_PROVENANCE_MISMATCH" and not error.value.fallback_safe


def test_demo_job_api_is_opt_in_authorized_and_scoped(tmp_path, monkeypatch):
    from app import main
    settings, store, request, event = setup(tmp_path)
    monkeypatch.setattr(main, "settings", settings)
    client = TestClient(main.app)
    headers = {"X-Service-Token": TOKEN, "X-User-Scope": request.userScope,
        "X-Request-Id": str(request.requestId), "Idempotency-Key": request.idempotencyKey}
    path = "/v1/conversation/jobs"
    assert client.post(path, json=request.model_dump(mode="json")).status_code == 401
    assert client.post(path, headers=headers, json=request.model_dump(mode="json")).status_code == 202
    assert client.get("/v1/jobs/" + str(event.jobId), headers={**headers, "X-User-Scope": "b" * 64}).status_code == 404
    settings.model_demo_enabled = False
    assert client.post(path, headers=headers, json=request.model_dump(mode="json")).status_code == 503
