"""Experiment transport checks. Synthetic provider output is not evidence of AI quality."""
import asyncio
import copy
import json
from uuid import uuid4

import httpx
import pytest

from app import main, scenario_evaluation as e, scenario_assessment as a
from app.config import Settings
from test_scenario_assessment_parts import parts_payload, parts_fixtures
from test_scenario_workflow import extraction_response


@pytest.fixture
def config(monkeypatch, tmp_path):
    settings = Settings(service_token="s" * 40, scenario_enabled=True, evaluation_enabled=True,
                        evaluation_directory=str(tmp_path))
    monkeypatch.setattr(main, "settings", settings)
    return settings


def request(settings):
    catalog = e.catalog(settings)
    payload = parts_payload("EXTRACT", promptHash=a.prompt_hash(a.scoped_release))
    return dict(requestId=str(uuid4()), backendHash=catalog["backendHash"], runtimeHash=e.digest(catalog["runtime"]),
                payload=payload.model_dump(mode="json"))


async def call(method, url, **kw):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
        return await client.request(method, url, **kw)


async def test_auth_disabled_production_bounds_and_catalog(config, monkeypatch):
    assert (await call("GET", "/v1/evaluation/scenario")).status_code == 401
    headers = {"X-Service-Token": config.service_token}
    result = await call("GET", "/v1/evaluation/scenario", headers=headers)
    assert result.status_code == 200
    text = result.text
    assert config.service_token not in text
    assert [r["id"] for r in result.json()["releases"]] == ["v4.13", "v4.14"]
    assert (await call("POST", "/v1/evaluation/scenario", headers=headers, content="x" * 64001)).status_code == 413
    config.production_mode = True
    assert (await call("GET", "/v1/evaluation/scenario", headers=headers)).status_code == 503
    config.production_mode = False
    config.evaluation_enabled = False
    assert (await call("GET", "/v1/evaluation/scenario", headers=headers)).status_code == 503


async def test_changed_sources_require_restart_before_new_admission(config, monkeypatch):
    monkeypatch.setattr(e, "build_hash", lambda: "changed")
    result = await call("GET", "/v1/evaluation/scenario", headers={"X-Service-Token": config.service_token})
    assert result.status_code == 503 and "restart" in result.text


@pytest.mark.parametrize("release", [a.scoped_release, a.scoped_fewshot_candidate])
async def test_real_pipeline_observer_persists_six_stages_and_idempotency(config, monkeypatch, release):
    data = request(config)
    data["payload"]["promptHash"] = a.prompt_hash(release)
    raw_extract = extraction_response()
    raw_extract.update(learnerMode="listener", learnerCharacterId=None, genre="information-exchange")
    responses = [raw_extract, *parts_fixtures()]
    calls = []
    class Gateway:
        def __init__(self, settings, client):
            self.last_model = "test-model"
            self.model_fallbacks = []
        async def complete(self, messages, schema, deadline, purpose, **options):
            calls.append(purpose)
            self.observed_attempts.append(dict(model=self.last_model, outcome="success", prompt_tokens=10, completion_tokens=5))
            return copy.deepcopy(responses[len(calls) - 1]), "ollama"
    monkeypatch.setattr(a, "ModelGateway", Gateway)
    headers = {"X-Service-Token": config.service_token}
    assert (await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)).status_code == 202
    await asyncio.gather(*list(e._tasks))
    result = await call("GET", "/v1/evaluation/scenario/" + data["requestId"], headers=headers)
    record = result.json()
    assert record["status"] == "COMPLETED", record
    assert record["requestId"] == data["requestId"]
    assert record["result"]["requestId"] == data["requestId"]
    assert record["before"]["hardPass"]
    assert len(record["result"]["task"]["activities"]) == 3
    assert len(calls) == 6 and len(record["events"]) == 12
    assert record["events"][1]["output"] == raw_extract
    assert record["events"][1]["attempts"][0]["prompt_tokens"] == 10
    assert (await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)).json()["status"] == "COMPLETED"
    assert len(calls) == 6
    data["payload"]["inputVi"] += "!"
    assert (await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)).status_code == 409


async def test_other_historical_releases_are_not_admitted(config):
    data = request(config)
    data["payload"]["promptHash"] = a.prompt_hash(a.direct_audit_candidate)
    assert (await call("POST", "/v1/evaluation/scenario", json=data,
                       headers={"X-Service-Token": config.service_token})).status_code == 400
    assert not list(e.directory(config).glob("*.json"))


def test_clean_preserves_only_valid_provenance_uuids_and_keeps_redacting_credentials():
    identifier = str(uuid4())
    value = e.clean({"requestId": identifier, "result": {"generationId": identifier},
                     "input": {"requestId": "Bearer " + "x" * 40}, "password": identifier,
                     "api_key": "k" * 40, "source": "owner@example.com"})
    assert value["requestId"] == value["result"]["generationId"] == identifier
    assert value["input"]["requestId"] == "[REDACTED]"
    assert value["password"] == value["api_key"] == value["source"] == "[REDACTED]"


async def test_errors_keep_evidence_and_admitted_unknown_never_replays(config, monkeypatch):
    data = request(config)
    data["backendHash"] = "0" * 64
    headers = {"X-Service-Token": config.service_token}
    assert (await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)).status_code == 409
    data = request(config)
    async def fail(event, settings, *, observer):
        observer({"stage": "EXTRACT", "status": "ERROR", "output": {"private": "saved raw"}})
        raise TimeoutError()
    monkeypatch.setattr(a, "execute", fail)
    await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)
    await asyncio.gather(*list(e._tasks))
    record = (await call("GET", "/v1/evaluation/scenario/" + data["requestId"], headers=headers)).json()
    assert record["status"] == "ERROR" and record["events"][0]["output"] == {"private": "saved raw"}
    assert not record["before"]["hardPass"]
    path = e.directory(config) / (data["requestId"] + ".json")
    record["status"] = "RUNNING"
    e.save(path, record)
    assert (await call("POST", "/v1/evaluation/scenario", json=data, headers=headers)).json()["status"] == "RUNNING"
