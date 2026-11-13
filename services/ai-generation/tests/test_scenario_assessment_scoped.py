"""Direct question/review/grade contracts; mocked verdicts are not model-quality evidence."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_parts import parts_fixtures, parts_payload

PIN = "b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087"


def scoped_bank(*, place_only=False):
    base = parts_fixtures()
    source = "Lan chose the library because it is quiet. They will meet there at nine."
    base[0]["activities"][0]["segments"][0]["textEn"] += " " + source
    evidence = [dict(segmentId="s1", quoteEn=source)]
    meaning = base[1]["activities"][0]["assessment"]["meanings"][0]
    parts = [dict(id="p1", meaningEn="They will meet at the library.", evidence=[dict(segmentId="s1", quoteEn=source)])]
    if not place_only:
        parts.append(dict(id="p2", meaningEn="They will meet at nine.", evidence=[dict(segmentId="s1", quoteEn="They will meet there at nine.")]))
    meaning.update(meaningEn="Lan chose the library." if place_only else "They will meet at the library at nine.",
        evidence=evidence, requiredParts=parts)
    if place_only:
        parts[0]["meaningEn"] = "Lan chose the library."
    question = base[1]["activities"][0]["questions"][0]
    question.update(factEn=meaning["meaningEn"], evidence=evidence,
        promptVi="Lan chọn nơi nào?" if place_only else "Họ gặp lúc mấy giờ và ở đâu?",
        answerVi="Thư viện." if place_only else "Lúc chín giờ tại thư viện.")
    row = base[2]["questionChecks"][0]
    row.update(requestedMeaningEn="Where did Lan choose?" if place_only else "When and where will they meet?",
        answerUnitsVi=[question["answerVi"]])
    return base


def grade_payload(base, text):
    value = parts_payload("GRADE", promptHash=PIN)
    content = a.attach_questions(w.Dialogue.model_validate(base[0]), a.PartsQuestions.model_validate(base[1]), value.learningContext)
    return a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "activity": content.activities[0].model_dump(),
        "assessmentSubmission": dict(mode="CHECKS", recapText="", meaningIds=["m1"], answers=[dict(questionId="q1", textVi=text)])})


def test_new_release_has_no_audit_prompts_schemas_or_ledger_projection():
    selected = a.scoped_release
    assert a.prompt_hash(selected) == PIN and a.selected_release(PIN) is selected
    assert not getattr(selected, "AUDIT_PROTOCOL", None)
    assert set(selected.INSTRUCTIONS) == {"EXTRACT", "GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW", "GRADE", "REPAIR_QUESTIONS", "REPAIR_TRANSLATIONS"}
    assert set(selected.OUTPUT_TOKENS) == set(selected.INSTRUCTIONS) - {"REPAIR_QUESTIONS", "REPAIR_TRANSLATIONS"}
    result, calls = execute(parts_payload(promptHash=PIN), parts_fixtures())
    assert [c[0] for c in calls] == ["scenario_generate", "scenario_questions", "scenario_review_questions", "scenario_translate", "scenario_review"]
    assert result["task"]["qualityPassed"] and result["qualityHarness"]["extraCalls"] == 0
    assert result["qualityHarness"]["policyVersion"] == "scenario-meaning-harness.v1"
    review = json.loads(calls[2][1][1]["content"])
    assert set(review) == {"content", "learningContext"}
    assert review["content"]["activities"][0]["segments"]
    assert review["content"]["activities"][0]["questions"]
    assert review["content"]["activities"][0]["questions"][0]["speechAct"] == "statement"
    assert review["content"]["activities"][0]["questions"][0]["factEn"]
    assert calls[2][2] == a.response_schema(a.QuestionReview, minimum_targets=True)
    assert all("AUDIT" not in key for key in result["qualityHarness"]["artifacts"])
    root = Path(__file__).resolve().parents[3]
    assert f'PARTS_V4_13_PROMPT_HASH="{PIN}"' in (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text(encoding="utf-8")


def test_correct_place_wrong_time_is_partial_using_existing_aggregation():
    base = scoped_bank()
    value = grade_payload(base, "At the library, at ten.")
    grade = dict(meanings=[dict(meaningId="m1", parts=[
        dict(partId="p1", status="CAPTURED", evidence=[dict(responseId="q1", quote="At the library")]),
        dict(partId="p2", status="INCORRECT", evidence=[dict(responseId="q1", quote="at ten")])])])
    result, calls = execute(value, [grade])
    assert len(calls) == 1 and result["task"]["meanings"][0]["status"] == "PARTIAL"
    assert len(result["task"]["meanings"][0]["parts"]) == 2


@pytest.mark.parametrize("answer", ["Ở thư viện.", "At the library.", "Lan chọn the library."])
def test_place_only_does_not_require_reason_and_accepts_learner_language(answer):
    base = scoped_bank(place_only=True)
    value = grade_payload(base, answer)
    grade = dict(meanings=[dict(meaningId="m1", parts=[
        dict(partId="p1", status="CAPTURED", evidence=[dict(responseId="q1", quote=answer)])])])
    result, calls = execute(value, [grade])
    assert result["task"]["meanings"][0]["status"] == "CAPTURED"
    projected = json.loads(calls[0][1][1]["content"])
    assert "because" not in json.dumps(projected) and "quiet" not in json.dumps(projected)
    assert projected["activity"]["questions"][0]["promptVi"] == "Lan chọn nơi nào?"
    assert len(projected["activity"]["assessment"]["meanings"][0]["requiredParts"]) == 1


def test_given_reason_stays_out_of_targets_and_learner_evidence():
    base = scoped_bank(place_only=True)
    base[1]["activities"][0]["questions"][0]["promptVi"] = "Vì muốn nơi yên tĩnh, Lan chọn nơi nào?"
    value = grade_payload(base, "Thư viện.")
    projected = a.project_input(a.scoped_release, "GRADE", {"activity": value.activity.model_dump(), "assessmentSubmission": value.assessmentSubmission.model_dump()})
    target = projected["activity"]["assessment"]["meanings"][0]
    assert target["meaningEn"] == "Lan chose the library."
    assert all("quiet" not in p["meaningEn"] for p in target["requiredParts"])
    grade = a.PartsGrade.model_validate(dict(meanings=[dict(meaningId="m1", parts=[dict(partId="p1", status="CAPTURED", evidence=[dict(responseId="q1", quote="yên tĩnh")])])]))
    with pytest.raises(ValueError, match="invented or duplicate learner evidence"):
        a.derive_grade(grade, value)


def test_wrong_reference_is_reported_at_review_not_sent_to_grade():
    base = scoped_bank(place_only=True)
    base[1]["activities"][0]["questions"][0]["answerVi"] = "Công viên."
    review = base[2]["questionChecks"][0]
    review.update(answerSupported=False, answerUnitsVi=["Công viên."], reasonVi="Hội thoại nói thư viện, không phải công viên.")
    base[2]["valid"] = False
    # Keep the existing advisory policy, but do not hide the review's negative answer verdict.
    responses = [*base[:3], dict(meaningReplacements=[], questionReplacements=[]), *base[3:]]
    result, calls = execute(parts_payload(promptHash=PIN), responses)
    assert not result["task"]["qualityPassed"]
    assert not result["reviewAdvisory"]["questions"]["questionChecks"][0]["answerSupported"]
    assert all(c[0] != "scenario_grade" for c in calls)
    projected = json.loads(calls[2][1][1]["content"])
    assert "the library" in projected["content"]["activities"][0]["segments"][0]["textEn"]
    assert projected["content"]["activities"][0]["questions"][0]["answerVi"] == "Công viên."


def test_review_format_retry_stays_direct_and_preserves_feedback():
    base = parts_fixtures()
    broken = copy.deepcopy(base[2])
    broken["meaningChecks"].pop()
    result, calls = execute(parts_payload(promptHash=PIN), [*base[:2], broken, *base[2:]])
    assert result["task"]["qualityPassed"] and len(calls) == 6
    first = json.loads(calls[2][1][1]["content"])
    retry = json.loads(calls[3][1][1]["content"])
    feedback = retry.pop("formatFeedback")
    assert first == retry and feedback["failedOutput"] == broken
    assert result["qualityHarness"]["extraCalls"] == 1


def test_review_after_question_patch_uses_only_two_extra_calls():
    base = parts_fixtures()
    failed = copy.deepcopy(base[2])
    failed["valid"] = False
    failed["questionChecks"][0].update(questionMatchesFact=False, reasonVi="Câu hỏi chưa rõ phạm vi.")
    fixed = copy.deepcopy(base[1]["activities"][0]["questions"][0])
    fixed["promptVi"] = "Cửa hàng mở cửa lúc mấy giờ?"
    patch = dict(meaningReplacements=[], questionReplacements=[dict(activityId="a1", question=fixed)])
    result, calls = execute(parts_payload(promptHash=PIN), [*base[:2], failed, patch, base[2], *base[3:]])
    assert result["task"]["qualityPassed"] and len(calls) == 7
    assert result["qualityHarness"]["extraCalls"] == 2
    review = json.loads(calls[4][1][1]["content"])
    assert "repairContext" in review and "meaningAudit" not in review and "questionAudit" not in review


@pytest.mark.parametrize("selected,pin", [
    (a.release, "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"),
    (a.direct_audit_candidate, "77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1")])
def test_historical_release_pins_and_saved_grades_still_work(selected, pin):
    assert a.prompt_hash(selected) == pin and a.selected_release(pin) is selected
    if selected is a.release:
        from test_scenario_assessment import payload, recap_grade
        value, grade = payload("GRADE"), recap_grade()
    else:
        from test_scenario_assessment_parts import two_part_check
        value, grade = two_part_check()
        value = a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "promptHash": pin})
    result, calls = execute(value, [grade])
    assert result["promptHash"] == pin and len(calls) == 1
    assert calls[0][1][0]["content"] == selected.INSTRUCTIONS["GRADE"]
