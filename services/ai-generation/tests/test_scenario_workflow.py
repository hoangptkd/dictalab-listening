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
        value["inputVi"] = "Tôi muốn đặt bánh để nhận ngày mai"
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
                budget = json.loads(messages[1]["content"])["writingBudget"]
                assert schema["$defs"]["DialogueActivity"]["properties"]["segments"]["maxItems"] == budget["segmentsPerActivity"]
                assert "pattern" in schema["$defs"]["Segment"]["properties"]["textEn"]
            calls.append((purpose, messages))
            response = responses[len(calls) - 1]
            if isinstance(response, Exception):
                raise response
            return response, settings.selected_provider

    with patch.object(workflow, "ModelGateway", Gateway):
        result = asyncio.run(workflow.execute(request(value), settings))
    return result, calls


def generation_responses():
    activities = EXAMPLE["content"]["activities"]
    return [{"learningPointsVi": ["Ý nghe " + str(i) for i in range(6)],
             "dialogue": {"activities": [{"id": a["id"], "segments": a["segments"]} for a in activities]}},
            {"segments": {f"{t['activityId']}/{t['segmentId']}": t["textVi"] for t in EXAMPLE["content"]["translations"]}},
            *[{"id": a["id"], "questions": [{**{k: v for k, v in q.items() if k != "evidence"},
                "answerVi": {("a1", "q2"): "nhận đơn sáng mai", ("a2", "q2"): "hạt"}.get((a["id"], q["id"]), q["answerVi"]),
                "evidenceSegmentIds": [e["segmentId"] for e in q["evidence"]]} for q in a["questions"]]} for a in activities],
            {"sessionChecks": {key: True for key in workflow.SessionChecks.model_fields},
             "constraintChecks": [{"constraintVi": c, "satisfied": True,
                "evidence": [{"activityId": "a2", "segmentId": "s1", "quoteEn": "no nuts"}]}
                for c in EXAMPLE["specification"]["constraintsVi"]],
             "goalEvidence": [{"activityId": "a1", "segmentId": "s1", "quoteEn": "order some bread today and collect it tomorrow"}],
             "questionChecks": [{"activityId": a["id"], "questionId": q["id"], "reasonVi": "Đáp án có bằng chứng riêng",
                 **{key: True for key, field in workflow.QuestionCheck.model_fields.items() if field.annotation is bool}}
                 for a in activities for q in a["questions"]], "valid": True, "issues": []}]


def extraction_review(brief):
    return {"sourceChecks": [{"quoteVi": brief, "preserved": True, "reasonVi": "Giữ mục tiêu gốc"}],
            "rolesConsistent": True, "constraintsPreserved": True, "assumptionsExplicit": True,
            "naturalVietnamese": True, "valid": True, "issues": []}


def extraction_response():
    return {**EXAMPLE["specification"], "characters": [
        {k: v for k, v in character.items() if k != "id"} for character in EXAMPLE["specification"]["characters"]]}


def test_cross_language_prompt_pin_and_content_fixture():
    assert workflow.prompt_hash() == (ROOT / "src/main/resources/scenario/workflow-v1.sha256").read_text().strip()
    value = payload()
    workflow.validate_content(workflow.Content(activities=EXAMPLE["content"]["activities"]), value.specification,
                              value.profile, workflow.Translations(segments=EXAMPLE["content"]["translations"]))


def test_generation_requires_grounded_questions_after_validated_dialogue():
    brief = "Đổi lịch nhận xe từ sáng thứ Ba sang chiều thứ Tư"
    result, calls = execute(payload(inputVi=brief), generation_responses())
    assert json.loads(calls[0][1][1]["content"])["inputVi"] == brief
    assert json.loads(calls[5][1][1]["content"])["inputVi"] == brief
    assert [c[0] for c in calls] == ["scenario_generate", "scenario_translate", "scenario_questions", "scenario_questions", "scenario_questions", "scenario_review"]
    first_question_input = json.loads(calls[2][1][1]["content"])
    assert [s["textEn"] for s in first_question_input["segments"]] == [s["textEn"] for s in EXAMPLE["content"]["activities"][0]["segments"]]
    assert "specification" not in first_question_input and "inputVi" not in first_question_input
    assert "speakerId" not in first_question_input["segments"][0]
    assert json.loads(calls[0][1][1]["content"])["writingBudget"]["targetWordsPerActivity"] == 37
    expected = copy.deepcopy(EXAMPLE["content"])
    expected["activities"][0]["questions"][1]["answerVi"] = "nhận đơn sáng mai"
    expected["activities"][1]["questions"][1]["answerVi"] = "hạt"
    for activity in expected["activities"]:
        for q in activity["questions"]:
            for e in q["evidence"]:
                e["quoteEn"] = next(s["textEn"] for s in activity["segments"] if s["id"] == e["segmentId"])
    assert result["task"] == expected
    assert result["generationId"] == result["requestId"]
    assert result["programHash"] == "a" * 64
    assert [c["purpose"] for c in result["calls"]] == ["GENERATE", "TRANSLATE", "QUESTIONS", "QUESTIONS", "QUESTIONS", "REVIEW"]


@pytest.mark.parametrize("change", ["evidence", "word_limit", "translation", "review", "extra", "speaker"])
def test_unsound_results_are_rejected_without_repair(change):
    responses = copy.deepcopy(generation_responses())
    generated = responses[0]["dialogue"]
    if change == "evidence":
        responses[2]["questions"][0]["evidenceSegmentIds"] = ["invented"]
    elif change == "word_limit":
        generated["activities"][0]["segments"][0]["textEn"] = "word " * 65
    elif change == "translation":
        del responses[1]["segments"]["a3/s2"]
    elif change == "review":
        responses[-1] = {"valid": False, "issues": ["ambiguous question"]}
    elif change == "speaker":
        generated["activities"][0]["segments"][0]["speakerId"] = "unknown"
    else:
        responses[2]["questions"][0]["url"] = "https://example.test"
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
    result, calls = execute(value, [extraction_response(), extraction_review(value.inputVi)])
    expected = copy.deepcopy(EXAMPLE["specification"])
    expected["characters"][0]["id"], expected["characters"][1]["id"] = "learner", "partner"
    assert result["task"] == expected
    assert len(calls) == 2
    with pytest.raises(ValueError):
        payload(promptHash="b" * 64)


@pytest.mark.parametrize("fault", ["rolesConsistent", "constraintsPreserved", "assumptionsExplicit",
                                  "naturalVietnamese", "unanchored", "missing", "uncertain"])
def test_extraction_audit_fails_closed(fault):
    value = payload("EXTRACT")
    audit = extraction_review(value.inputVi)
    if fault == "unanchored": audit["sourceChecks"][0]["quoteVi"] = "not in the user's brief"
    elif fault == "missing": audit["sourceChecks"] = []
    elif fault == "uncertain": audit["sourceChecks"][0]["preserved"] = False
    else: audit[fault] = False
    with pytest.raises(workflow.QualityRejected):
        execute(value, [extraction_response(), audit])


@pytest.mark.parametrize("field", list(workflow.SessionChecks.model_fields))
def test_review_every_session_dimension_is_required(field):
    replies = copy.deepcopy(generation_responses())
    replies[-1]["sessionChecks"][field] = False
    with pytest.raises(workflow.QualityRejected, match="semantic quality rejected"):
        execute(payload(), replies)


@pytest.mark.parametrize("field", ["questionGrounded", "atomicMeaning", "answerMatchesTranslation"])
def test_review_every_question_dimension_is_required(field):
    replies = copy.deepcopy(generation_responses())
    replies[-1]["questionChecks"][0][field] = False
    with pytest.raises(workflow.QualityRejected, match="semantic quality rejected"):
        execute(payload(), replies)


@pytest.mark.parametrize("prompt,answer,leaked", [
    ("Vé cũ có được phép dùng tiếp hay không?", "Có", False),
    ("Khách có được đổi vé không?", "Không được", False),
    ("Khách có được đổi vé?", "Có", False),
    ("Có mấy chuyến tàu?", "Có", True),
    ("Vé cũ còn dùng được không?", "Vé cũ còn dùng được", True),
    ("Khách đến sân ga số 4 phải không?", "4", True),
    ("Khách thanh toán bằng thẻ phải không?", "Bằng thẻ", True),
])
def test_yes_no_polarity_is_not_an_answer_disclosure(prompt, answer, leaked):
    assert workflow.answer_leaked(prompt, answer) is leaked


def test_metadata_only_name_and_changed_translation_numbers_are_rejected():
    content = workflow.Content(activities=copy.deepcopy(EXAMPLE["content"]["activities"]))
    value = payload()
    content.activities[0].questions[0].answerVi = "Sam"
    with pytest.raises(ValueError, match="name absent from audio"):
        workflow.validate_content(content, value.specification, value.profile)
    content = workflow.Content(activities=EXAMPLE["content"]["activities"])
    translations = workflow.Translations(segments=copy.deepcopy(EXAMPLE["content"]["translations"]))
    content.activities[0].segments[1].textEn = content.activities[0].segments[1].textEn.replace("nine", "9")
    content.activities[0].questions[0].evidence[0].quoteEn = "we open at 9"
    translations.segments[1].textVi = translations.segments[1].textVi.replace("chín", "8")
    with pytest.raises(ValueError, match="numeric literals"):
        workflow.validate_content(content, value.specification, value.profile, translations)


@pytest.mark.parametrize("fault", ["omitted", "duplicate", "unresolved", "invented", "goal"])
def test_source_constraints_and_resolved_goal_require_anchored_review_evidence(fault):
    replies = copy.deepcopy(generation_responses())
    audit = replies[-1]
    if fault == "omitted": audit["constraintChecks"] = []
    elif fault == "duplicate": audit["constraintChecks"] *= 2
    elif fault == "unresolved": audit["constraintChecks"][0]["satisfied"] = False
    elif fault == "invented": audit["constraintChecks"][0]["evidence"][0]["quoteEn"] = "not heard"
    else: audit["goalEvidence"][0]["quoteEn"] = "not heard"
    with pytest.raises(workflow.QualityRejected):
        execute(payload(), replies)


def test_missing_semantic_checks_cannot_be_implicitly_approved():
    replies = copy.deepcopy(generation_responses())
    del replies[-1]["sessionChecks"]
    with pytest.raises(workflow.QualityRejected, match="invalid scenario response"):
        execute(payload(), replies)


def test_article_an_and_translating_number_words_to_digits_are_not_false_positives():
    value = payload()
    value.specification.characters[0].name = "An"
    content = workflow.Content(activities=copy.deepcopy(EXAMPLE["content"]["activities"]))
    content.activities[0].segments[0].textEn += " I have an order."
    translations = workflow.Translations(segments=copy.deepcopy(EXAMPLE["content"]["translations"]))
    translations.segments[0].textVi += " Tôi có đơn hàng."
    translations.segments[1].textVi = translations.segments[1].textVi.replace("chín", "9")
    workflow.validate_content(content, value.specification, value.profile, translations)


def test_quality_rejection_log_is_correlated_without_raw_brief(caplog):
    replies = copy.deepcopy(generation_responses())
    replies[-1]["sessionChecks"]["goalResolved"] = False
    brief = "Đổi lịch phỏng vấn, đây là nội dung riêng tư"
    with pytest.raises(workflow.QualityRejected):
        execute(payload(inputVi=brief), replies)
    record = next(r for r in caplog.records if r.msg.startswith("scenario_quality_rejected"))
    assert record.requestId and record.phase == "GENERATE" and record.gate == "semantic quality rejected"
    assert brief not in caplog.text
    assert record.requestId in caplog.text


@pytest.mark.parametrize("fault", ["unknown", "duplicate", "wrong_activity", "invented_answer", "extra_translation"])
def test_server_owned_evidence_and_translation_keys_fail_closed(fault):
    replies = copy.deepcopy(generation_responses())
    if fault == "unknown": replies[2]["questions"][0]["evidenceSegmentIds"] = ["missing"]
    elif fault == "duplicate": replies[2]["questions"][0]["evidenceSegmentIds"] = ["s2", "s2"]
    elif fault == "wrong_activity": replies[2]["id"] = "a2"
    elif fault == "invented_answer": replies[2]["questions"][0]["answerVi"] = "chín giờ hàng tuần"
    else: replies[1]["segments"]["a1/missing"] = "Câu không có nguồn"
    with pytest.raises(workflow.QualityRejected):
        execute(payload(), replies)


def test_repeated_answer_cannot_retest_a_prior_decision():
    content = workflow.Content(activities=copy.deepcopy(EXAMPLE["content"]["activities"]))
    content.activities[-1].questions[-1].answerVi = "CHÍN GIỜ!"
    value = payload()
    with pytest.raises(ValueError, match="duplicate answer meaning"):
        workflow.validate_content(content, value.specification, value.profile)


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
