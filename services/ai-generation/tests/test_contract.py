from unittest.mock import AsyncMock, patch

from app.config import Settings
from app.main import app, cache
from app.ollama import SCHEMA
from fastapi.testclient import TestClient


def test_schema_and_health_contract():
    assert SCHEMA["required"][:2] == ["slots", "utteranceEn"]
    assert {route.path for route in app.routes} >= {"/health/live", "/health/ready", "/v1/conversation/tasks"}
    assert Settings().model == "qwen3.5:9b"


def test_live_and_invalid_request_contract():
    client = TestClient(app)
    assert client.get("/health/live").json() == {"status": "ok"}
    response = client.post("/v1/conversation/tasks", json={})
    assert response.status_code == 400
    assert response.json()["error"] == "invalid conversation request"


def test_idempotency_replay_does_not_call_generate_twice():
    cache.clear()
    task = {"slots": [], "utteranceEn": "u", "facts": [{"textVi": "x", "evidenceEn": "e"}],
            "questionVi": "q", "translationVi": "d", "taskVi": "t", "skill": "s"}
    payload = {
        "requestId": "req-replay", "idempotencyKey": "key-replay", "level": "A1", "turnNumber": 2,
        "promptVersion": "V1", "instruction": "test", "context": {"turnNumber": 2},
    }
    headers = {"X-Request-Id": "req-replay", "Idempotency-Key": "key-replay"}
    with patch("app.main.generate", new=AsyncMock(return_value=task)) as generate_mock:
        client = TestClient(app)
        first = client.post("/v1/conversation/tasks", json=payload, headers=headers)
        second = client.post("/v1/conversation/tasks", json=payload, headers=headers)
        assert first.status_code == 200
        assert second.status_code == 200
        assert first.json()["task"] == second.json()["task"]
        assert generate_mock.await_count == 1
    cache.clear()
