from unittest.mock import AsyncMock, patch
import pytest

from app.config import Settings
from app.main import app, cache
from app.ollama import SCHEMA
from fastapi.testclient import TestClient


def test_schema_and_health_contract():
    assert SCHEMA["required"][:2] == ["slots", "utteranceEn"]
    schema = app.openapi()
    assert set(schema["paths"]) >= {"/health/live", "/health/ready", "/v1/conversation/tasks",
                                           "/v1/tts/jobs", "/v1/coach/reports", "/v1/review/tasks"}
    payload_schema = schema["components"]["schemas"]["JobRequest"]["properties"]["payload"]
    scenario_schema = schema["components"]["schemas"]["ScenarioPayload"]
    assert payload_schema["discriminator"]["mapping"]["scenario"] == "#/components/schemas/ScenarioPayload"
    assert scenario_schema["discriminator"]["mapping"] == {
        "scenario-job.v1": "#/components/schemas/ScenarioInput",
        "scenario-job.v2": "#/components/schemas/ScenarioInput",
        "scenario-job.v3": "#/components/schemas/ScenarioAssessmentInput",
    }
    assert scenario_schema["oneOf"] == [
        {"$ref": "#/components/schemas/ScenarioInput"},
        {"$ref": "#/components/schemas/ScenarioAssessmentInput"},
    ]
    assert Settings().model == "qwen3.5:9b"


def test_job_payload_union_still_validates_both_scenario_contracts():
    from test_scenario_assessment import payload as assessment_payload
    from test_scenario_workflow import payload as workflow_payload, request
    from app.scenario_assessment import ScenarioAssessmentInput
    from app.scenario_workflow import ScenarioInput

    assert isinstance(request(workflow_payload()).payload, ScenarioInput)
    assert isinstance(request(assessment_payload()).payload, ScenarioAssessmentInput)


def test_live_and_invalid_request_contract():
    client = TestClient(app)
    assert client.get("/health/live").json() == {"status": "ok"}
    response = client.post("/v1/conversation/tasks", json={})
    assert response.status_code == 400
    assert response.json()["error"] == "invalid conversation request"


@pytest.mark.parametrize("unrestricted", [False, True])
def test_idempotency_replay_does_not_call_generate_twice(monkeypatch, unrestricted):
    from app import main
    monkeypatch.setattr(main, "settings", main.settings.model_copy(update={"operational_limits_enabled": not unrestricted}))
    if unrestricted:
        monkeypatch.setattr(main, "semaphore", None)
    cache.clear()
    task = {"slots": [], "utteranceEn": "u", "facts": [{"textVi": "x", "evidenceEn": "e"}],
            "questionVi": "q", "translationVi": "d", "taskVi": "t", "skill": "s"}
    payload = {
        "requestId": "req-replay", "idempotencyKey": "key-replay", "level": "A1", "turnNumber": 2,
        "promptVersion": "V1", "instruction": "test", "context": {"turnNumber": 2},
    }
    headers = {"X-Request-Id": "req-replay", "Idempotency-Key": "key-replay"}
    with patch("app.main.run_workflow", new=AsyncMock(return_value=task)) as workflow_mock:
        client = TestClient(app)
        first = client.post("/v1/conversation/tasks", json=payload, headers=headers)
        second = client.post("/v1/conversation/tasks", json=payload, headers=headers)
        assert first.status_code == 200
        assert second.status_code == 200
        assert first.json()["task"] == second.json()["task"]
        assert workflow_mock.await_count == 1
    cache.clear()
