import json
from pathlib import Path

import pytest

from app.config import Settings
from app.registry import RegistryError, artifact_hash, load_artifact, resolve_version, verify_model
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch
import httpx


def test_artifact_hash_and_allowlist():
    settings = Settings()
    artifact = load_artifact(settings)
    assert artifact_hash(artifact) == artifact["sha256"]
    assert resolve_version("V1", artifact) == artifact["version"]
    with pytest.raises(RegistryError):
        resolve_version("client-selected-file", artifact)


def test_model_digest_drift_fails():
    artifact = load_artifact(Settings())
    with pytest.raises(RegistryError):
        verify_model(artifact, {"models": [{"name": artifact["model"]["name"], "digest": "wrong"}]})


def test_missing_artifact_fails_readiness():
    with pytest.raises(RegistryError):
        load_artifact(Settings(registry_dir=str(Path("does-not-exist"))))


def test_tampered_hash_and_unapproved_production_are_rejected(tmp_path):
    artifact = load_artifact(Settings())
    artifact["instruction"] += "tampered"
    (tmp_path / (artifact["version"] + ".json")).write_text(json.dumps(artifact), encoding="utf-8")
    with pytest.raises(RegistryError):
        load_artifact(Settings(registry_dir=str(tmp_path)))
    with pytest.raises(RegistryError):
        load_artifact(Settings(production_mode=True, service_token="test-only", artifact_sha256=artifact["sha256"]))
    artifact["approvalStatus"] = "approved"
    artifact["sha256"] = artifact_hash(artifact)
    (tmp_path / (artifact["version"] + ".json")).write_text(json.dumps(artifact), encoding="utf-8")
    with pytest.raises(RegistryError):
        load_artifact(Settings(registry_dir=str(tmp_path), production_mode=True, service_token="test-only", artifact_sha256=artifact["sha256"]))


def test_readiness_model_up_down_missing_and_drift():
    from app.main import app
    artifact = load_artifact(Settings())
    client = TestClient(app)
    for tags, expected in [({"models": [{"name": artifact["model"]["name"], "digest": artifact["model"]["digest"]}]}, 200),
                           ({"models": []}, 503),
                           ({"models": [{"name": artifact["model"]["name"], "digest": "different"}]}, 503)]:
        response = httpx.Response(200, json=tags, request=httpx.Request("GET", "http://fake/api/tags"))
        with patch("httpx.AsyncClient.get", new=AsyncMock(return_value=response)):
            assert client.get("/health/ready").status_code == expected
    with patch("httpx.AsyncClient.get", new=AsyncMock(side_effect=httpx.ConnectError("down"))):
        assert client.get("/health/ready").status_code == 503


def test_real_workflow_uses_registry_gateway_and_metadata():
    from app.main import app, cache
    content = {"slots": [{"kind": "time", "askVi": "When?"}, {"kind": "place", "askVi": "Where?"}],
               "utteranceEn": "Please meet at nine in the main lobby.", "facts": [{"textEn": "nine", "evidenceEn": "at nine"},
               {"textEn": "lobby", "evidenceEn": "in the main lobby"}], "questionVi": "When and where?", "taskVi": "Listen.", "skill": "details"}
    translation = {"translationVi": "At nine in the lobby.", "facts": [{"textVi": "nine"}, {"textVi": "lobby"}]}
    artifact = load_artifact(Settings())
    bodies = []

    async def post(_client, url, **kwargs):
        body = kwargs["json"]
        bodies.append(body)
        assert "CLIENT_INSTRUCTION" not in json.dumps(body)
        assert "OLD_STORY" not in json.dumps(body)
        output = content if len(bodies) == 1 else translation
        return httpx.Response(200, json={"done": True, "done_reason": "stop", "message": {"content": json.dumps(output)}}, request=httpx.Request("POST", url))

    cache.clear()
    payload = {"requestId": "registry-integration", "idempotencyKey": "registry-integration", "level": "A1", "turnNumber": 4,
               "promptVersion": "V1", "instruction": "CLIENT_INSTRUCTION", "context": {"history": [{"utteranceEn": "OLD_STORY"}]}}
    with patch("httpx.AsyncClient.post", new=post):
        response = TestClient(app).post("/v1/conversation/tasks", json=payload,
                                       headers={"X-Request-Id": payload["requestId"], "Idempotency-Key": payload["idempotencyKey"]})
    assert response.status_code == 200, response.text
    assert response.json()["modelDigest"] == artifact["model"]["digest"]
    assert response.json()["promptVersion"] == artifact["version"]
    assert len(bodies) == 2
    assert bodies[0]["format"] == artifact["contentSchema"]
    assert bodies[1]["options"]["temperature"] == 0.1
    assert response.json()["task"]["facts"][0]["evidenceEn"] == content["facts"][0]["evidenceEn"]
    cache.clear()


def test_shadow_disables_paid_fallback_and_unknown_version_rejected():
    from app.main import app, cache
    observed = []

    async def workflow(request, settings, client):
        observed.append(settings.fallback_enabled)
        return {}

    payload = {"requestId": "shadow-test", "idempotencyKey": "shadow-test", "level": "A1", "turnNumber": 1,
               "promptVersion": "V1", "instruction": "test", "context": {}}
    headers = {"X-Request-Id": "shadow-test", "Idempotency-Key": "shadow-test", "X-AI-Mode": "shadow"}
    cache.clear()
    with patch("app.main.run_workflow", new=workflow):
        response = TestClient(app).post("/v1/conversation/tasks", json=payload, headers=headers)
        assert response.status_code == 200
        assert observed == [False]
        payload["promptVersion"] = "untrusted-path"
        assert TestClient(app).post("/v1/conversation/tasks", json=payload, headers=headers).status_code == 400
    text = TestClient(app).get("/metrics").text
    assert "dictalab_ai_generation_active 0" in text
    assert "shadow-test" not in text
    cache.clear()


def test_caller_deadline_bounds_shadow_workflow():
    import asyncio
    from app.main import app, cache

    async def slow_workflow(request, settings, client):
        assert settings.timeout_seconds == 0.01
        assert settings.fallback_enabled is False
        await asyncio.sleep(0.1)
        return {}

    payload = {"requestId": "deadline-test", "idempotencyKey": "deadline-test", "level": "A1", "turnNumber": 1,
               "promptVersion": "V1", "instruction": "test", "context": {}}
    headers = {"X-Request-Id": "deadline-test", "Idempotency-Key": "deadline-test", "X-AI-Mode": "shadow", "X-AI-Timeout-Ms": "10"}
    cache.clear()
    with patch("app.main.run_workflow", new=slow_workflow):
        assert TestClient(app).post("/v1/conversation/tasks", json=payload, headers=headers).status_code == 504
    assert "deadline-test" not in cache
    headers["X-AI-Timeout-Ms"] = "0"
    assert TestClient(app).post("/v1/conversation/tasks", json=payload, headers=headers).status_code == 400
