"""Candidate contract checks with mocked providers; not evidence of semantic AI quality."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_harness as h, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_parts import parts_payload
from test_scenario_assessment_prevention import flexible_fixtures


RELEASE = a.alignment_candidate
PIN = "ce9b6398767cd1cdc927e0f0e442984373a05d058470e616a7d9a3a758669ab8"


def aligned_rows():
    rows = flexible_fixtures()
    for check in rows[2]["questionChecks"]:
        activity = next(act for act in rows[1]["activities"] if act["id"] == check["activityId"])
        question = next(q for q in activity["questions"] if q["id"] == check["questionId"])
        check["partChecks"] = [dict(meaningId=m["id"], partId=p["id"], requiredByQuestion=True,
                                    reasonVi="Phần này thuộc nội dung câu hỏi.")
            for m in activity["assessment"]["meanings"] if m["id"] in question["meaningIds"]
            for p in m["requiredParts"]]
    return rows


def grade_payload():
    rows = aligned_rows()
    value = parts_payload("GRADE", promptHash=PIN)
    content = a.attach_questions(w.Dialogue.model_validate(rows[0]), a.FlexibleQuestions.model_validate(rows[1]), value.learningContext)
    return a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "activity": content.activities[0].model_dump(),
        "assessmentSubmission": dict(mode="CHECKS", recapText="", meaningIds=["m1"],
                                     answers=[dict(questionId="q1", textVi="At nine.")])})


def test_candidate_is_pinned_but_not_activated():
    assert a.prompt_hash(RELEASE) == PIN and a.selected_release(PIN) is RELEASE
    assert a.prompt_hash(a.prevention_candidate) == "f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89"
    root = Path(__file__).resolve().parents[3]
    core = (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text()
    assert f'PARTS_V4_16_PROMPT_HASH="{PIN}"' in core
    assert "ACTIVE_PROMPT_HASH=RECOVERY_V5_1_PROMPT_HASH" in core
    assert RELEASE.HARNESS_POLICY == a.prevention_candidate.HARNESS_POLICY


def test_same_calls_and_no_new_audit():
    result, calls = execute(parts_payload(promptHash=PIN), aligned_rows())
    assert result["task"]["qualityPassed"]
    assert result["qualityHarness"]["extraCalls"] == 0
    assert [c[0] for c in calls] == ["scenario_generate", "scenario_questions", "scenario_review_questions", "scenario_translate", "scenario_review"]
    assert "AlignedQuestionCheck" in calls[2][2]["$defs"]


def test_false_part_cannot_hide_behind_all_true_summary_flags():
    rows = aligned_rows()
    bank = a.FlexibleQuestions.model_validate(rows[1])
    rows[2]["questionChecks"][0]["partChecks"][0].update(requiredByQuestion=False, reasonVi="Mục tiêu này đã có trong câu hỏi.")
    verdict = a.AlignedQuestionReview.model_validate(rows[2])
    status, issues = h.review_status(verdict, bank, "QUESTIONS")
    assert status == "FAIL"
    assert issues[0]["code"] == "semantic_questionMatchesFact"
    assert "m1/p1" in issues[0]["reasonVi"]
    allowed, blocked = h.repair_scope(bank, issues, "QUESTIONS", flexible=True)
    assert not blocked and allowed == {("meaning", "a1", "m1"), ("question", "a1", "q1")}


@pytest.mark.parametrize("fault", ["duplicate", "missing", "foreign", "blank"])
def test_part_review_requires_exact_inventory_and_reasons(fault):
    rows = aligned_rows()
    checks = rows[2]["questionChecks"][0]["partChecks"]
    if fault == "duplicate":
        checks.append(copy.deepcopy(checks[0]))
    elif fault == "missing":
        checks[0]["partId"] = "p2"
    elif fault == "foreign":
        checks[0]["meaningId"] = "m6"
    else:
        checks[0]["reasonVi"] = " "
    assert h.review_status(a.AlignedQuestionReview.model_validate(rows[2]),
                           a.FlexibleQuestions.model_validate(rows[1]), "QUESTIONS")[0] == "UNKNOWN"


def test_new_grade_has_source_but_saved_grade_projection_is_unchanged():
    payload = grade_payload()
    data = dict(activity=payload.activity.model_dump(), assessmentSubmission=payload.assessmentSubmission.model_dump())
    projected = a.project_input(RELEASE, "GRADE", data)
    assert projected["activity"]["segments"] == data["activity"]["segments"]
    assert len(projected["activity"]["assessment"]["meanings"]) == len(projected["activity"]["questions"]) == 1
    assert "answerVi" not in projected["activity"]["questions"][0]
    assert "segments" not in a.project_input(a.prevention_candidate, "GRADE", data)["activity"]


def test_repair_cannot_clear_part_finding_on_untouched_question():
    rows = aligned_rows()
    current = a.AlignedQuestionReview.model_validate(rows[2])
    previous = current.model_copy(deep=True)
    previous.questionChecks[0].partChecks[0].requiredByQuestion = False
    result = h.reconcile(previous, current, {("question", "a2", "q1")}, "QUESTIONS")
    assert not result.questionChecks[0].partChecks[0].requiredByQuestion
    assert h.review_status(result, a.FlexibleQuestions.model_validate(rows[1]), "QUESTIONS")[0] == "FAIL"


@pytest.mark.parametrize("regression", [False, True])
def test_meaning_only_patch_rechecks_unchanged_linked_question(regression):
    rows = aligned_rows()
    activity = rows[1]["activities"][0]
    meaning, question = activity["assessment"]["meanings"][0], activity["questions"][0]
    extra = dict(segmentId="s1", quoteEn="The bread costs six pounds.")
    meaning["evidence"] = [*meaning["evidence"], extra]
    question["evidence"] = [*question["evidence"], copy.deepcopy(extra)]
    fixed = copy.deepcopy(meaning)
    meaning["requiredParts"].append(dict(id="p2", meaningEn="The bread costs six pounds.", evidence=[extra]))
    bad = copy.deepcopy(rows[2])
    bad["valid"] = False
    bad["issues"] = ["q1 has an extra scoring requirement."]
    bad["questionChecks"][0]["questionMatchesFact"] = False
    bad["questionChecks"][0]["partChecks"].append(dict(meaningId="m1", partId="p2",
        requiredByQuestion=False, reasonVi="Câu hỏi không yêu cầu giá bánh."))
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=fixed)],
                 questionReplacements=[dict(activityId="a1", question=copy.deepcopy(question))])
    review = copy.deepcopy(rows[2])
    if regression:
        review["questionChecks"][0]["answerSupported"] = False
    result, calls = execute(parts_payload(promptHash=PIN), [rows[0], rows[1], bad, patch, review, *rows[3:]])
    assert result["qualityHarness"]["attempts"][0]["accepted"] is not regression
    context = json.loads(calls[4][1][1]["content"])["repairContext"]
    assert {(t["itemType"], t["itemId"]) for t in context["changedTargets"]} == {("meaning", "m1"), ("question", "q1")}
    assert result["task"]["qualityPassed"] is not regression


def test_invalid_key_returns_no_grade_and_no_retry():
    with pytest.raises(w.QualityRejected, match="invalid assessment targets"):
        execute(grade_payload(), [dict(assessmentIssue="q1/m1/p1 requires information not asked.", meanings=[])])


def test_valid_key_keeps_server_aggregation_and_exact_learner_quotes():
    output = dict(assessmentIssue="", meanings=[dict(meaningId="m1", parts=[dict(partId="p1", status="CAPTURED",
        evidence=[dict(responseId="q1", quote="At nine.")])])])
    result, calls = execute(grade_payload(), [output])
    assert len(calls) == 1
    assert result["task"]["meanings"][0]["status"] == "CAPTURED"
    assert "assessmentIssue" not in result["task"]
    output["meanings"][0]["parts"][0]["evidence"][0]["quote"] = "The shop opens at nine."
    with pytest.raises(w.QualityRejected, match="invalid grade evidence"):
        execute(grade_payload(), [output])


@pytest.mark.parametrize("issue,meanings", [("", []), (" ", []), ("invalid key", [{"meaningId": "m1", "parts": [
    {"partId": "p1", "status": "ABSENT", "evidence": []}]}])])
def test_grade_issue_and_scores_are_mutually_exclusive(issue, meanings):
    with pytest.raises(ValueError):
        a.AlignedPartsGrade.model_validate(dict(assessmentIssue=issue, meanings=meanings))


def test_examples_follow_candidate_contract_and_serialization():
    for stage in ("QUESTIONS", "REVIEW_QUESTIONS", "REPAIR_QUESTIONS", "GRADE"):
        block = RELEASE.INSTRUCTIONS[stage].split("\n\nEXAMPLES\n")[1].split("\n\nINPUT\n")[0]
        encoded = [json.loads(line) for line in block.splitlines() if line.startswith("{")]
        samples = [e for e in RELEASE.EXAMPLES if e["stage"] == stage]
        assert encoded == [{k: e[k] for k in ("scope", "input", "expectedOutput")} for e in samples]
        for example in samples:
            if stage == "REVIEW_QUESTIONS":
                for row in example["expectedOutput"]["questionChecks"]:
                    a.AlignedQuestionCheck.model_validate(row)
            if stage == "GRADE":
                assert example["input"]["activity"]["segments"]
                a.AlignedPartsGrade.model_validate(example["expectedOutput"])
