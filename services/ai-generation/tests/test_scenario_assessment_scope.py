"""V4.17 transport/repair checks. Mocked providers do not measure semantic improvement."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_harness as h, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_alignment import aligned_rows, grade_payload
from test_scenario_assessment_parts import parts_payload

PIN = "6f81dd0f465c452696b0caf8df466c8603780a9db15ee3fafa47e7bdda4bd41e"
RELEASE = a.scope_candidate


def test_candidate_pin_keeps_active_release_and_unrelated_stages():
    assert a.prompt_hash(RELEASE) == PIN and a.selected_release(PIN) is RELEASE
    assert RELEASE.HARNESS_POLICY == a.alignment_candidate.HARNESS_POLICY
    assert RELEASE.OUTPUT_TOKENS == a.alignment_candidate.OUTPUT_TOKENS
    for stage in ("EXTRACT", "GENERATE", "TRANSLATE", "REVIEW", "REPAIR_TRANSLATIONS"):
        assert RELEASE.INSTRUCTIONS[stage] == a.alignment_candidate.INSTRUCTIONS[stage]
    root = Path(__file__).resolve().parents[3]
    core = (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text()
    assert f'PARTS_V4_17_PROMPT_HASH="{PIN}"' in core
    assert "ACTIVE_PROMPT_HASH=RECOVERY_V5_1_PROMPT_HASH" in core
    assert (root / "src/main/resources/scenario/workflow-v3.sha256").read_text().strip() == a.prompt_hash(a.recovery_candidate)


def test_source_scope_gate_detects_extra_reason_and_action_without_case_specific_rules():
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    meaning = activity["assessment"]["meanings"][0]
    question = activity["questions"][0]
    question["promptVi"] = "Bình sẽ làm việc với bộ phận nào?"
    meaning["requiredParts"] = [dict(id="p1", meaningEn="Binh will sync with the marketing leads.",
                                     evidence=copy.deepcopy(meaning["evidence"])),
                                dict(id="p2", meaningEn="To ensure marketing understands the new boundaries.",
                                     evidence=copy.deepcopy(meaning["evidence"]))]
    findings = a.question_scope_findings(a.FlexibleQuestions.model_validate(rows[1]), w.Dialogue.model_validate(rows[0]))
    assert {(f.activity_id, f.question_id, f.part_id) for f in findings} == {("a1", "q1", "p1"), ("a1", "q1", "p2")}


def test_source_scope_gate_accepts_a_place_answer_with_no_extra_dimension():
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    activity["questions"][0]["promptVi"] = "Mira đã chọn địa điểm nào?"
    activity["assessment"]["meanings"][0]["requiredParts"] = [dict(
        id="p1", meaningEn="Mira chose the riverside cafe.", evidence=copy.deepcopy(activity["assessment"]["meanings"][0]["evidence"]))]
    assert a.question_scope_findings(a.FlexibleQuestions.model_validate(rows[1]), w.Dialogue.model_validate(rows[0])) == []


def test_source_scope_gate_ignores_context_words_inside_an_answer_unit():
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    activity["questions"][0]["promptVi"] = "Vì sao việc dùng bộ lọc nâng cao lại hữu ích?"
    activity["assessment"]["meanings"][0]["requiredParts"] = [dict(
        id="p1", meaningEn="Learning to use advanced filters saves time because students find exact topics.",
        evidence=copy.deepcopy(activity["assessment"]["meanings"][0]["evidence"]))]
    assert a.question_scope_findings(a.FlexibleQuestions.model_validate(rows[1]), w.Dialogue.model_validate(rows[0])) == []


def test_source_scope_gate_does_not_treat_action_target_as_an_extra_person():
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    activity["questions"][0]["promptVi"] = "Linh đề xuất thay đổi cách phân công như thế nào?"
    activity["assessment"]["meanings"][0]["requiredParts"] = [dict(
        id="p1", meaningEn="Assign inquiries by product category to specialized staff.",
        evidence=copy.deepcopy(activity["assessment"]["meanings"][0]["evidence"]))]
    assert a.question_scope_findings(a.FlexibleQuestions.model_validate(rows[1]), w.Dialogue.model_validate(rows[0])) == []


def test_v417_structural_gate_runs_before_review_and_localizes_the_question():
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    activity["questions"][0]["promptVi"] = "Bình sẽ làm việc với bộ phận nào?"
    activity["assessment"]["meanings"][0]["requiredParts"][0]["meaningEn"] = "Binh will sync with the marketing leads."
    questions = a.FlexibleQuestions.model_validate(rows[1])
    payload = parts_payload(promptHash=a.prompt_hash(a.scope_candidate))
    findings = h.structural(questions, w.Dialogue.model_validate(rows[0]), payload, "QUESTIONS")
    assert len(findings) == 1
    assert findings[0]["code"] == "required_part_includes_information_outside_question_scope:_action"
    assert findings[0]["questionId"] == "q1"


@pytest.mark.parametrize("model", [a.FlexibleQuestions, a.AlignedQuestionReview, a.PartsQuestionPatch, a.AlignedPartsGrade])
def test_wire_order_does_not_remove_fields_bounds_or_validation(model):
    old = a.response_schema(model, minimum_targets=True)
    current = a.response_schema(model, minimum_targets=True, answer_first=True)
    # required order is irrelevant to validation; all keys and constraints must remain identical.
    def canonical(value):
        if isinstance(value, dict):
            return {k: sorted(v) if k == "required" else canonical(v) for k, v in value.items()}
        return [canonical(v) for v in value] if isinstance(value, list) else value
    assert canonical(old) == canonical(current)


def test_real_call_path_sends_answer_first_schema_without_more_calls():
    result, calls = execute(parts_payload(promptHash=PIN), aligned_rows())
    assert result["task"]["qualityPassed"]
    assert result["qualityHarness"]["extraCalls"] == 0
    assert len(calls) == 5
    bank = calls[1][2]["$defs"]
    assert list(bank["FlexibleActivityDraft"]["properties"]) == ["id", "questions", "assessment"]
    assert list(bank["QuestionDraft"]["properties"])[:3] == ["id", "promptVi", "answerVi"]
    review = calls[2][2]
    assert list(review["properties"])[:2] == ["questionChecks", "meaningChecks"]
    assert list(review["$defs"]["AlignedQuestionCheck"]["properties"])[4] == "partChecks"
    assert list(review["$defs"]["PartScopeCheck"]["properties"])[-1] == "requiredByQuestion"


def test_repair_receives_scoped_independent_answer_and_can_remove_and_renumber_parts():
    rows = aligned_rows()
    act = rows[1]["activities"][0]
    meaning, question = act["assessment"]["meanings"][0], act["questions"][0]
    fixed = copy.deepcopy(meaning)
    extra = dict(segmentId="s1", quoteEn="The bread costs six pounds.")
    meaning["evidence"] = [*meaning["evidence"], extra]
    question["evidence"] = [*question["evidence"], copy.deepcopy(extra)]
    meaning["requiredParts"][0]["id"] = "p2"
    meaning["requiredParts"].insert(0, dict(id="p1", meaningEn="The bread costs six pounds.", evidence=[extra]))
    review = copy.deepcopy(rows[2])
    bad = rows[2]["questionChecks"][0]
    bad.update(requestedMeaningEn="Opening time only.", answerUnitsVi=["Chín giờ."])
    bad["partChecks"] = [dict(meaningId="m1", partId="p1", requiredByQuestion=False, reasonVi="Câu hỏi không hỏi giá."),
                         dict(meaningId="m1", partId="p2", requiredByQuestion=True, reasonVi="Câu hỏi hỏi giờ mở cửa.")]
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=fixed)],
                 questionReplacements=[dict(activityId="a1", question=copy.deepcopy(question))])
    original = copy.deepcopy(rows[1])
    result, calls = execute(parts_payload(promptHash=PIN), [*rows[:3], patch, review, *rows[3:]])
    data = json.loads(calls[3][1][1]["content"])
    assert data["questionReviews"] == [bad]
    assert data["questionReviews"][0]["answerUnitsVi"] == ["Chín giờ."]
    assert list(calls[3][2]["properties"]) == ["questionReplacements", "meaningReplacements"]
    assert len(calls) == 7 and result["qualityHarness"]["attempts"][0]["accepted"]
    repaired = result["task"]["activities"][0]
    assert repaired["assessment"]["meanings"][0]["requiredParts"] == fixed["requiredParts"]
    assert repaired["questions"][0]["promptVi"] == question["promptVi"]
    assert repaired["assessment"]["meanings"][1] == original["activities"][0]["assessment"]["meanings"][1]
    assert rows[1] == original


def test_candidate_patch_still_rejects_foreign_targets():
    rows = aligned_rows()
    original = a.FlexibleQuestions.model_validate(rows[1])
    act = rows[1]["activities"][0]
    patch = a.PartsQuestionPatch.model_validate(dict(
        meaningReplacements=[dict(activityId="a1", meaning=act["assessment"]["meanings"][1])],
        questionReplacements=[dict(activityId="a1", question=act["questions"][0])]))
    with pytest.raises(ValueError, match="patch targets mismatch"):
        h.merge_patch(original, patch, {("meaning", "a1", "m1"), ("question", "a1", "q1")}, "QUESTIONS")


@pytest.mark.parametrize("issue,scores", [("m1/p1 is outside q1 scope", []),
    ("m1/p1 is outside q1 scope", [dict(meaningId="m1", parts=[dict(partId="p1", status="ABSENT", evidence=[])])])])
def test_invalid_or_conflicting_grade_never_returns_a_learner_score(issue, scores):
    payload = a.ScenarioAssessmentInput.model_validate({**grade_payload().model_dump(), "promptHash": PIN})
    with pytest.raises(w.QualityRejected):
        execute(payload, [dict(assessmentIssue=issue, meanings=scores)])


def test_candidate_examples_are_consistent_and_include_both_invalid_key_directions():
    for stage in ("QUESTIONS", "REVIEW_QUESTIONS", "REPAIR_QUESTIONS", "GRADE"):
        encoded = [json.loads(line) for line in RELEASE.INSTRUCTIONS[stage].splitlines() if line.startswith("{")]
        samples = [e for e in RELEASE.EXAMPLES if e["stage"] == stage]
        assert encoded == [{k: e[k] for k in ("scope", "input", "expectedOutput")} for e in samples]
        for sample in samples:
            if stage == "GRADE":
                a.AlignedPartsGrade.model_validate(sample["expectedOutput"])
                assert list(sample["expectedOutput"])[0] == "assessmentIssue"
            elif stage == "REVIEW_QUESTIONS":
                for row in sample["expectedOutput"]["questionChecks"]:
                    a.AlignedQuestionCheck.model_validate(row)
                    if any(not p["requiredByQuestion"] for p in row["partChecks"]):
                        assert not row["questionMatchesFact"]
    examples = {e["id"]: e for e in RELEASE.EXAMPLES}
    for suffix in ("extra-target", "missing-target"):
        assert examples["grade-invalid-key-" + suffix]["expectedOutput"]["meanings"] == []
    source = examples["grade-place-only-given-reason"]["input"]["activity"]["segments"][0]["textEn"]
    assert "because it is quiet" in source


def test_valid_candidate_grade_keeps_source_projection_and_server_aggregation():
    payload = a.ScenarioAssessmentInput.model_validate({**grade_payload().model_dump(), "promptHash": PIN})
    output = dict(assessmentIssue="", meanings=[dict(meaningId="m1", parts=[dict(partId="p1", status="CAPTURED",
        evidence=[dict(responseId="q1", quote="At nine.")])])])
    result, calls = execute(payload, [output])
    assert len(calls) == 1 and result["task"]["meanings"][0]["status"] == "CAPTURED"
    activity = json.loads(calls[0][1][1]["content"])["activity"]
    assert activity["segments"] == [segment.model_dump() for segment in payload.activity.segments]
    assert "answerVi" not in activity["questions"][0]
