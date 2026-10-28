"""Generic text boundary: authenticated, configured routing, one admission, no paid replay."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import time
from unittest.mock import patch
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.jobs import JobStore
from app.model_gateway import ModelGateway
from app.text_gateway import TextRequest, select_model

TOKEN = "text-test-token-at-least-32-characters"


def request(**updates):
    return {"requestId": str(uuid4()), "provider": "ollama", "model": "qwen3.5:9b",
        "instruction": "Return JSON only.", "input": "Private learner text",
        "deadline": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat(), **updates}


@pytest.fixture
def api(tmp_path, monkeypatch):
    from app import main
    settings = Settings(primary_provider="ollama", text_gateway_enabled=True, service_token=TOKEN,
        jobs_database=str(tmp_path / "jobs.sqlite3"))
    monkeypatch.setattr(main, "settings", settings)
    monkeypatch.setattr(main, "semaphore", None)
    with TestClient(main.app) as client:
        yield client, settings, main


def test_auth_disabled_bounds_and_deadlines_never_dispatch(api):
    client, settings, _ = api
    with patch.object(ModelGateway, "complete") as complete:
        assert client.post("/v1/text/completions", json=request()).status_code == 401
        headers = {"X-Service-Token": TOKEN}
        assert client.post("/v1/text/completions", content=b"x" * 256001, headers=headers).status_code == 413
        settings.text_gateway_enabled = False
        assert client.post("/v1/text/completions", json=request(), headers=headers).status_code == 503
        settings.text_gateway_enabled = True
        for deadline in (datetime.now().isoformat(),
                (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat(),
                (datetime.now(timezone.utc) + timedelta(seconds=90)).isoformat()):
            assert client.post("/v1/text/completions", json=request(deadline=deadline), headers=headers).status_code == 400
        for updates in ({"provider": "gemini"}, {"model": "unconfigured"}):
            assert client.post("/v1/text/completions", json=request(**updates), headers=headers).status_code == 409
        complete.assert_not_called()


def test_verified_result_then_restart_blocks_replay_without_storing_text(api):
    client, settings, main = api
    body = request()
    async def complete(gateway, *args, **kwargs):
        assert gateway._settings.num_predict == 2200
        assert kwargs["json_object"] is True
        return {"ok": True}, "ollama"
    with patch.object(ModelGateway, "complete", complete):
        response = client.post("/v1/text/completions", json=body, headers={"X-Service-Token": TOKEN})
    assert response.status_code == 200
    assert response.json() == {"schemaVersion": "1", "requestId": body["requestId"],
        "provider": "ollama", "model": "qwen3.5:9b", "task": {"ok": True}}
    assert settings.num_predict == 1000
    with TestClient(main.app) as restarted, patch.object(ModelGateway, "complete") as complete:
        assert restarted.post("/v1/text/completions", json=body,
            headers={"X-Service-Token": TOKEN}).status_code == 409
        complete.assert_not_called()
    with JobStore(settings.jobs_database).connect() as db:
        row = dict(db.execute("SELECT * FROM text_admissions").fetchone())
        assert set(row) == {"id", "deadline"}
        assert "Private learner text" not in json.dumps(row)


@pytest.mark.parametrize("failure", ["provider", "provenance", "size"])
def test_unknown_or_invalid_results_are_sanitized_and_never_replayed(api, failure):
    client, _, _ = api
    body = request()
    async def complete(gateway, *args, **kwargs):
        if failure == "provider":
            raise RuntimeError("secret-key Private learner text")
        return ({"x": "x" * 12001} if failure == "size" else {}), "gemini" if failure == "provenance" else "ollama"
    with patch.object(ModelGateway, "complete", complete):
        response = client.post("/v1/text/completions", json=body, headers={"X-Service-Token": TOKEN})
        assert response.status_code == 503
        assert "secret-key" not in response.text and "Private learner text" not in response.text
        assert client.post("/v1/text/completions", json=body,
            headers={"X-Service-Token": TOKEN}).status_code == 409


def test_durable_concurrent_admission_has_one_winner(tmp_path):
    path, request_id = str(tmp_path / "jobs.sqlite3"), uuid4()
    JobStore(path)
    with ThreadPoolExecutor(max_workers=8) as pool:
        admitted = list(pool.map(lambda _: JobStore(path).admit_text(request_id, time.time() + 30), range(8)))
    assert sum(admitted) == 1


async def test_generic_gemini_uses_json_object_and_preserves_structured_default():
    calls = []
    def handle(request):
        calls.append(json.loads(request.content))
        assert request.url.path == "/v1beta/openai/chat/completions"
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}, "finish_reason": "stop"}]})
    settings = Settings(primary_provider="gemini", gemini_api_key="test-key")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        gateway = ModelGateway(settings, client)
        for generic in (True, False):
            result, provider = await gateway.complete([{"role": "user", "content": "Return JSON."}],
                {"type": "object", "properties": {"ok": {"type": "boolean"}}},
                time.monotonic() + 30, "text", json_object=generic)
            assert result == {"ok": True} and provider == "gemini"
    assert [c["response_format"]["type"] for c in calls] == ["json_object", "json_schema"]


def test_model_selection_does_not_mutate_pool_and_requires_strong_token():
    settings = Settings(primary_provider="gemini", gemini_quota_enabled=True, gemini_quota_backend="memory",
        gemini_providers=[{"id": "a", "projectId": "test", "apiKey": "test-key", "enabled": True,
            "model": "configured-model"}])
    selected = select_model(settings, TextRequest.model_validate(request(provider="gemini", model="configured-model")))
    assert selected.active_cloud_model == "configured-model"
    assert settings.active_cloud_model == "gemini-3.5-flash-lite"
    with pytest.raises(ValueError, match="strong service token"):
        Settings(text_gateway_enabled=True, service_token="short")


def test_queue_deadline_keeps_admission_and_releases_no_unacquired_permit(api):
    client, _, main = api
    main.semaphore = asyncio.Semaphore(0)
    body = request(deadline=(datetime.now(timezone.utc) + timedelta(milliseconds=60)).isoformat())
    with patch.object(ModelGateway, "complete") as complete:
        assert client.post("/v1/text/completions", json=body,
            headers={"X-Service-Token": TOKEN}).status_code == 503
        complete.assert_not_called()
    assert main.semaphore.locked()
