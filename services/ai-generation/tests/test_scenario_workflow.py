import asyncio
import copy
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import httpx
import pytest

from app import scenario_workflow as workflow
from app.config import Settings
from app.jobs import JobRequest, JobEvent, JobStore
from app.worker import process

ROOT = Path(__file__).resolve().parents[3]
EXAMPLE = json.loads((ROOT / "src/test/resources/scenario/workflow-example.json").read_text(encoding="utf-8"))


def payload(phase="GENERATE", **changes):
    value = dict(kind="scenario", phase=phase, contractVersion="scenario-job.v1", promptHash=workflow.prompt_hash(),
                 programHash="a" * 64, profile=EXAMPLE["profile"])
    if phase == "GENERATE":
        value["specification"] = EXAMPLE["specification"]
    elif phase == "EXTRACT":
        value["inputVi"] = "Tôi muốn đặt bánh để nhận ngày mai"
    else:
        value.update(activity=EXAMPLE["content"]["activities"][0], answers=EXAMPLE["answers"])
    return workflow.ScenarioInput.model_validate({**value, **changes})


def request(value):
    identifier = uuid4()
    return JobRequest(jobId=identifier, requestId=identifier, userScope="b" * 64, idempotencyKey=str(identifier),
                      deadline=datetime.now(timezone.utc) + timedelta(seconds=220), payload=value)


def execute(value, responses):
    calls = []
    settings = Settings(scenario_enabled=True)

    class Gateway:
        def __init__(self, settings, client):
            self.last_model = workflow.metadata(settings)["model"]

        async def complete(self, messages, schema, deadline, purpose, **kwargs):
            if purpose == "scenario_generate":
                assert schema["$defs"]["Segment"]["properties"]["speakerId"]["enum"] == [c.id for c in value.specification.characters]
            calls.append((purpose, messages))
            response = responses[len(calls) - 1]
            if isinstance(response, Exception):
                raise response
            return response, settings.selected_provider

    with patch.object(workflow, "ModelGateway", Gateway):
        result = asyncio.run(workflow.execute(request(value), settings))
    return result, calls


def generation_responses():
    return [{"learningPointsVi": ["Ý nghe " + str(i) for i in range(6)], "content": {"activities": EXAMPLE["content"]["activities"]}}, {"segments": EXAMPLE["content"]["translations"]},
            {"questionChecks": [{"activityId": a["id"], "questionId": q["id"], "reasonVi": "Đáp án có bằng chứng riêng", "answerSupported": True, "distinctMeaning": True} for a in EXAMPLE["content"]["activities"] for q in a["questions"]], "valid": True, "issues": []}]


def test_cross_language_prompt_pin_and_content_fixture():
    assert workflow.prompt_hash() == (ROOT / "src/main/resources/scenario/workflow-v1.sha256").read_text().strip()
    value = payload()
    workflow.validate_content(workflow.Content(activities=EXAMPLE["content"]["activities"]), value.specification,
                              value.profile, workflow.Translations(segments=EXAMPLE["content"]["translations"]))


def test_generation_requires_three_distinct_stages_with_private_provenance():
    brief = "Đổi lịch nhận xe từ sáng thứ Ba sang chiều thứ Tư"
    result, calls = execute(payload(inputVi=brief), generation_responses())
    assert json.loads(calls[0][1][1]["content"])["inputVi"] == brief
    assert json.loads(calls[2][1][1]["content"])["inputVi"] == brief
    assert [c[0] for c in calls] == ["scenario_generate", "scenario_translate", "scenario_review"]
    assert result["task"] == EXAMPLE["content"]
    assert result["generationId"] == result["requestId"]
    assert result["programHash"] == "a" * 64
    assert [c["purpose"] for c in result["calls"]] == ["GENERATE", "TRANSLATE", "REVIEW"]


@pytest.mark.parametrize("change", ["evidence", "word_limit", "translation", "review", "extra", "speaker"])
def test_unsound_results_are_rejected_without_repair(change):
    responses = copy.deepcopy(generation_responses())
    generated = responses[0]["content"]
    if change == "evidence":
        generated["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "invented"
    elif change == "word_limit":
        generated["activities"][0]["segments"][0]["textEn"] = "word " * 65
    elif change == "translation":
        responses[1]["segments"][-1] = responses[1]["segments"][0]
    elif change == "review":
        responses[2] = {"valid": False, "issues": ["ambiguous question"]}
    elif change == "speaker":
        generated["activities"][0]["segments"][0]["speakerId"] = "unknown"
    else:
        generated["activities"][0]["questions"][0]["url"] = "https://example.test"
    with pytest.raises(workflow.QualityRejected):
        execute(payload(), responses)


def test_grade_requires_exact_learner_evidence_and_all_ids():
    result, calls = execute(payload("GRADE"), [EXAMPLE["grade"]])
    assert result["task"] == EXAMPLE["grade"]
    assert len(calls) == 1
    invented = copy.deepcopy(EXAMPLE["grade"])
    invented["facts"][0]["evidenceVi"] = "This was never submitted"
    with pytest.raises(workflow.QualityRejected):
        execute(payload("GRADE"), [invented])


@pytest.mark.parametrize("fault", ["omitted", "duplicate", "unsupported", "repeated_meaning"])
def test_review_requires_every_question_audited_even_when_overall_verdict_is_true(fault):
    responses = copy.deepcopy(generation_responses())
    checks = responses[-1]["questionChecks"]
    if fault == "omitted": checks.pop()
    elif fault == "duplicate": checks[-1] = checks[0]
    elif fault == "unsupported": checks[-1]["answerSupported"] = False
    else: checks[-1]["distinctMeaning"] = False
    with pytest.raises(workflow.QualityRejected):
        execute(payload(), responses)


def test_extraction_redacts_and_rejects_wrong_prompt_before_call():
    value = payload("EXTRACT", inputVi="Tôi muốn đặt bánh user@example.com")
    assert "user@example.com" not in value.inputVi
    result, calls = execute(value, [EXAMPLE["specification"]])
    assert result["task"] == EXAMPLE["specification"]
    assert len(calls) == 1
    with pytest.raises(ValueError):
        payload(promptHash="b" * 64)


def test_later_transport_failure_is_unknown_and_never_replayed(tmp_path):
    settings = Settings(scenario_enabled=True, jobs_database=str(tmp_path / "scenario.sqlite"))
    store = JobStore(settings.jobs_database)
    req = request(payload())
    store.register(req, settings)
    body = store.pending()[0]
    calls = []

    def partial(event):
        calls.append(event)
        raise httpx.ConnectError("translation connection failed after generation already succeeded")

    assert process(body, settings, store, partial) == "DEAD"
    assert store.get(str(req.jobId), req.userScope)["status"] == "UNKNOWN"
    process(body, settings, JobStore(settings.jobs_database), partial)
    assert len(calls) == 1


def test_worker_checks_disabled_flag_before_provider():
    with patch.object(workflow, "ModelGateway") as gateway:
        with pytest.raises(workflow.QualityRejected):
            asyncio.run(workflow.execute(request(payload()), Settings(scenario_enabled=False)))
        gateway.assert_not_called()


def test_internal_api_requires_token_scope_provider_and_explicit_rollout(tmp_path, monkeypatch):
    from app import main
    from fastapi.testclient import TestClient
    settings = Settings(jobs_enabled=True, scenario_enabled=False, service_token="test-only", jobs_database=str(tmp_path / "api.sqlite"))
    monkeypatch.setattr(main, "settings", settings)
    req = request(payload("EXTRACT"))
    headers = {"X-Service-Token": "test-only", "X-User-Scope": req.userScope, "X-AI-Provider": "ollama",
               "X-Request-Id": str(req.requestId), "Idempotency-Key": req.idempotencyKey}
    client = TestClient(main.app)
    body = req.model_dump(mode="json")
    assert client.post("/v1/scenario/jobs", json=body).status_code == 401
    assert client.post("/v1/scenario/jobs", headers=headers, content=b" " * 64001).status_code == 413
    assert client.post("/v1/scenario/jobs", headers=headers, json=body).status_code == 503
    monkeypatch.setattr(main, "settings", settings.model_copy(update={"scenario_enabled": True}))
    assert client.post("/v1/scenario/jobs", headers={**headers, "X-AI-Provider": "gemini"}, json=body).status_code == 409
    first = client.post("/v1/scenario/jobs", headers=headers, json=body)
    assert first.status_code == 202
    assert client.post("/v1/scenario/jobs", headers=headers, json=body).json()["jobId"] == first.json()["jobId"]
    assert client.get("/v1/jobs/" + str(req.jobId), headers={**headers, "X-User-Scope": "c" * 64}).status_code == 404
