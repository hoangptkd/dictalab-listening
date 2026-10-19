from app.config import Settings
from app.main import app
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
