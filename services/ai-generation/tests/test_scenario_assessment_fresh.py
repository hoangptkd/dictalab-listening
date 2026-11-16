"""Fresh prompt transport contracts. Mocked output does not establish learning quality."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_harness as h, scenario_prompts as p, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_alignment import aligned_rows, grade_payload
from test_scenario_assessment_parts import parts_payload

RELEASE = a.fresh_candidate
PIN = "b8026ffc1dabdb09393c3b419b41750ca51ece9a242363482e3a337f4a61697b"


def test_fresh_release_keeps_history_and_active_pin():
    assert RELEASE is p.CURRENT and a.selected_release(PIN) is RELEASE
    assert a.prompt_hash(RELEASE) == PIN
    assert a.prompt_hash(a.scope_candidate) == "6f81dd0f465c452696b0caf8df466c8603780a9db15ee3fafa47e7bdda4bd41e"
    assert RELEASE.HARNESS_POLICY == a.scope_candidate.HARNESS_POLICY
    assert RELEASE.OUTPUT_TOKENS == a.scope_candidate.OUTPUT_TOKENS
    root = Path(__file__).resolve().parents[3]
    assert (root / "src/main/resources/scenario/workflow-v3.sha256").read_text().strip() == PIN


def test_real_generation_path_uses_fresh_instructions_and_scoped_contracts():
    result, calls = execute(parts_payload(promptHash=PIN), aligned_rows())
    assert result["task"]["qualityPassed"] and result["promptHash"] == PIN
    assert len(calls) == 5 and result["qualityHarness"]["extraCalls"] == 0
    for purpose, messages, schema, _ in calls:
        stage = purpose.removeprefix("scenario_").upper()
        assert messages[0]["content"] == RELEASE.INSTRUCTIONS[stage]
        assert messages[0]["content"] != a.scope_candidate.INSTRUCTIONS[stage]
    assert "partChecks" in calls[2][2]["$defs"]["AlignedQuestionCheck"]["properties"]
    assert "specification" in json.loads(calls[1][1][1]["content"])
    assert list(calls[1][2]["$defs"]["FlexibleActivityDraft"]["properties"])[:2] == ["id", "questions"]


def test_new_examples_have_valid_wire_objects_and_are_embedded_exactly_once():
    for stage, instruction in RELEASE.INSTRUCTIONS.items():
        assert instruction != a.scope_candidate.INSTRUCTIONS[stage]
        expected = [{k: e[k] for k in ("scope", "input", "expectedOutput")}
                    for e in RELEASE.EXAMPLES if e["stage"] == stage]
        assert [json.loads(line) for line in instruction.splitlines() if line.startswith("{")] == expected
    for example in RELEASE.EXAMPLES:
        output = example["expectedOutput"]
        if example["stage"] == "GRADE":
            a.AlignedPartsGrade.model_validate(output)
        elif example["stage"] == "QUESTIONS":
            a.QuestionDraft.model_validate(output["questions"][0])
            a.PartsMeaning.model_validate(output["assessment"]["meanings"][0])
        elif example["stage"] == "REVIEW_QUESTIONS":
            a.AlignedQuestionCheck.model_validate(output["questionChecks"][0])
        elif example["stage"] == "TRANSLATE":
            w.Translation.model_validate(output["segments"][0])
        elif example["stage"] == "REPAIR_QUESTIONS":
            a.PartsQuestionPatch.model_validate(output)
        elif example["stage"] == "REPAIR_TRANSLATIONS":
            w.TranslationPatch.model_validate(output)


def test_fresh_grading_projects_source_and_rejects_a_defective_key():
    payload = grade_payload().model_copy(update={"promptHash": PIN})
    data = a.project_input(RELEASE, "GRADE", dict(activity=payload.activity.model_dump(),
        assessmentSubmission=payload.assessmentSubmission.model_dump()))
    assert data["activity"]["segments"] == payload.activity.model_dump()["segments"]
    assert "answerVi" not in data["activity"]["questions"][0]
    with pytest.raises(w.QualityRejected, match="invalid assessment targets"):
        execute(payload, [dict(assessmentIssue="q1/m1/p1 is outside the question scope", meanings=[])])


def test_fresh_scope_is_reviewed_by_answer_units_not_legacy_keyword_collisions():
    rows = aligned_rows()
    act = rows[1]["activities"][0]
    quote = dict(segmentId="s1", quoteEn="The bread costs six pounds.")
    act["questions"][0].update(promptVi="Vì sao cửa hàng cần xem lại giá?", skill="reason")
    meaning = act["assessment"]["meanings"][0]
    meaning["requiredParts"] = [dict(id="p1", meaningEn="Because the cost is high.", evidence=[quote])]
    # A cost can be the substance of a reason; the word alone cannot prove extra scope.
    # Source truth still belongs to the independent reviewer and source-grounded GRADE.
    meaning["evidence"] = [quote]
    act["questions"][0]["evidence"] = [quote]
    questions = a.FlexibleQuestions.model_validate(rows[1])
    assert not h.structural(questions, w.Dialogue.model_validate(rows[0]), parts_payload(promptHash=PIN), "QUESTIONS")
    assert a.question_scope_findings(questions, w.Dialogue.model_validate(rows[0]))
    verdict = a.AlignedQuestionReview.model_validate(rows[2])
    verdict.questionChecks[0].partChecks[0].requiredByQuestion = False
    assert h.review_status(verdict, questions, "QUESTIONS")[0] == "FAIL"


def test_fresh_repair_keeps_part_diagnosis_and_unrelated_artifacts():
    rows = aligned_rows()
    original = copy.deepcopy(rows[1])
    bad = rows[2]["questionChecks"][0]
    bad["questionMatchesFact"] = False
    bad["partChecks"][0].update(requiredByQuestion=False, reasonVi="Mục tiêu yêu cầu thông tin ngoài câu hỏi.")
    rows[2].update(valid=False, issues=["a1/q1: sai phạm vi"])
    fixed_meaning = copy.deepcopy(rows[1]["activities"][0]["assessment"]["meanings"][0])
    fixed_meaning["meaningEn"] += " (opening time only)"
    patch = dict(meaningReplacements=[dict(activityId="a1",meaning=fixed_meaning)],
                 questionReplacements=[dict(activityId="a1",question=original["activities"][0]["questions"][0])])
    clean_review = aligned_rows()[2]
    result, calls = execute(parts_payload(promptHash=PIN), [*rows[:3],patch,clean_review,*rows[3:]])
    repair = next(c for c in calls if c[0] == "scenario_repair_questions")
    data = json.loads(repair[1][1]["content"])
    assert data["questionReviews"][0]["partChecks"][0]["requiredByQuestion"] is False
    assert repair[1][0]["content"] == RELEASE.INSTRUCTIONS["REPAIR_QUESTIONS"]
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["task"]["activities"][1] == a.attach_questions(
        w.Dialogue.model_validate(rows[0]), a.FlexibleQuestions.model_validate(original),
        parts_payload(promptHash=PIN).learningContext).model_dump()["activities"][1]
