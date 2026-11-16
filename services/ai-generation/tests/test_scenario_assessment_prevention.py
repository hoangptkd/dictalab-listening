"""V4.15 prevention contracts. Mocked outputs do not measure live AI quality."""
import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app import scenario_assessment as a, scenario_assessment_harness as h, scenario_workflow as w
from test_scenario_assessment import execute, fixtures
from test_scenario_assessment_parts import parts_payload


RELEASE = a.prevention_candidate
PIN = "f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89"


def flexible_fixtures(count=2):
    rows = fixtures(count)
    for activity in rows[1]["activities"]:
        activity["assessment"]["policyVersion"] = a.parts_release.POLICY_VERSION
        for meaning in activity["assessment"]["meanings"]:
            meaning["requiredParts"] = [dict(id="p1", meaningEn=meaning["meaningEn"],
                                             evidence=copy.deepcopy(meaning["evidence"]))]
    return rows


def test_approved_release_is_versioned_without_historical_schema_changes():
    assert a.prompt_hash(RELEASE) == PIN
    assert a.selected_release(PIN) is RELEASE
    assert a.prompt_hash(a.scoped_release) == "b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087"
    assert a.prompt_hash(a.scoped_fewshot_candidate) == "66f97b88e087e528f71b6e1f7bcec1224361b2edea94623ce93e4b5c1cf1189b"
    assert {k: v for k, v in RELEASE.HARNESS_POLICY.items() if k != "version"} == {
        k: v for k, v in a.scoped_release.HARNESS_POLICY.items() if k != "version"}
    assert RELEASE.HARNESS_POLICY["reviewPolicy"] == "advisory"
    assert RELEASE.OUTPUT_TOKENS == a.scoped_release.OUTPUT_TOKENS
    root = Path(__file__).resolve().parents[3]
    assert f'PARTS_V4_15_PROMPT_HASH="{PIN}"' in (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text(encoding="utf-8")
    assert (root / "src/main/resources/scenario/workflow-v3.sha256").read_text().strip() == a.prompt_hash(a.recovery_candidate)


@pytest.mark.parametrize("counts", [(2, 2, 2), (2, 3, 6), (6, 6, 6)])
def test_variable_banks_keep_exact_review_coverage_and_same_normal_calls(counts):
    rows = flexible_fixtures(6)
    for activity, count in zip(rows[1]["activities"], counts):
        activity["assessment"]["meanings"] = activity["assessment"]["meanings"][:count]
        activity["questions"] = activity["questions"][:count]
    for key, id_key in (("meaningChecks", "meaningId"), ("questionChecks", "questionId")):
        rows[2][key] = [row for row in rows[2][key] if int(row[id_key][1:]) <= counts[int(row["activityId"][1:]) - 1]]
    result, calls = execute(parts_payload(promptHash=PIN), rows)
    assert [len(act["questions"]) for act in result["task"]["activities"]] == list(counts)
    assert result["task"]["qualityPassed"]
    assert result["qualityHarness"]["extraCalls"] == 0
    assert [c[0] for c in calls] == ["scenario_generate", "scenario_questions", "scenario_review_questions", "scenario_translate", "scenario_review"]
    assert calls[1][2]["$defs"]["FlexibleActivityDraft"]["properties"]["questions"]["minItems"] == 2
    for index in (1, 3):
        data = json.loads(calls[index][1][1]["content"])
        assert data["specification"] == parts_payload(promptHash=PIN).specification.model_dump()
        assert data["learningContext"]
    question_data = json.loads(calls[1][1][1]["content"])
    assert question_data["dialogue"] == rows[0]


def test_two_item_candidate_does_not_relax_saved_release_or_structural_gates():
    rows = flexible_fixtures()
    bank = a.FlexibleQuestions.model_validate(rows[1])
    with pytest.raises(ValueError):
        a.PartsQuestions.model_validate(rows[1])
    context = parts_payload(promptHash=PIN).learningContext
    dialogue = w.Dialogue.model_validate(rows[0])
    for fault in ("missing_evidence", "unknown_meaning", "empty_bank", "single_item"):
        bad = copy.deepcopy(rows[1])
        activity = bad["activities"][0]
        if fault == "missing_evidence":
            activity["questions"][0]["evidence"][0]["quoteEn"] = "Not in the transcript."
        elif fault == "unknown_meaning":
            activity["questions"][0]["meaningIds"] = ["m6"]
        else:
            activity["questions"] = [] if fault == "empty_bank" else activity["questions"][:1]
        with pytest.raises(ValueError):
            a.attach_questions(dialogue, a.FlexibleQuestions.model_validate(bad), context)
    review = a.FlexibleQuestionReview.model_validate(rows[2])
    assert h.review_status(review, bank, "QUESTIONS")[0] == "PASS"
    review.questionChecks[0].questionId = "q6"
    assert h.review_status(review, bank, "QUESTIONS")[0] == "UNKNOWN"


def test_saved_two_item_lesson_can_be_graded_only_under_candidate_pin():
    rows = flexible_fixtures()
    value = parts_payload("GRADE", promptHash=PIN)
    content = a.attach_questions(w.Dialogue.model_validate(rows[0]), a.FlexibleQuestions.model_validate(rows[1]), value.learningContext)
    data = {**value.model_dump(), "activity": content.activities[0].model_dump(),
            "assessmentSubmission": dict(mode="CHECKS", recapText="", meaningIds=["m1"],
                answers=[dict(questionId="q1", textVi="At nine.")])}
    result, calls = execute(a.ScenarioAssessmentInput.model_validate(data), [
        dict(meanings=[dict(meaningId="m1", parts=[dict(partId="p1", status="CAPTURED",
            evidence=[dict(responseId="q1", quote="At nine.")])])])])
    assert result["task"]["meanings"][0]["status"] == "CAPTURED"
    assert len(calls) == 1
    with pytest.raises(ValueError, match="historical assessment"):
        a.ScenarioAssessmentInput.model_validate({**data, "promptHash": a.prompt_hash(a.scoped_release)})


def test_complete_fewshot_teaches_small_bank_without_unanchored_answers():
    sample = next(e for e in RELEASE.EXAMPLES if e["id"] == "questions-complete-small-experiment")
    bank = a.FlexibleQuestions.model_validate(sample["expectedOutput"])
    dialogue = w.Dialogue.model_validate(sample["input"]["dialogue"])
    config = sample["input"]["learningContext"]
    context = SimpleNamespace(focusSkill=config["focusSkill"], profile=SimpleNamespace(**config["profile"]))
    a.validate_questions(bank, dialogue, context)
    assert [len(act.questions) for act in bank.activities] == [2, 2, 2]
    for activity in bank.activities:
        assert all(m.inferenceReasonEn == "" and m.supportKind == "direct" for m in activity.assessment.meanings)
    assert len(bank.activities[0].assessment.meanings[0].requiredParts) == 2


def test_review_example_localizes_translation_error_without_failing_unrelated_rubrics():
    sample = next(e for e in RELEASE.EXAMPLES if e["id"] == "review-translation-localized-wrong-price")
    review = w.PersonalizedReview.model_validate(sample["expectedOutput"])
    assert not review.valid and review.issues
    failed = [row for row in review.translationChecks if not row.meaningPreserved]
    assert [(r.activityId, r.segmentId) for r in failed] == [("a2", "s2")]
    assert [r.category for r in review.rubricChecks if not r.passed] == ["TRANSLATIONS"]


def test_candidate_has_no_new_audit_or_fixed_question_count_instruction():
    assert not getattr(RELEASE, "AUDIT_PROTOCOL", None)
    assert set(RELEASE.INSTRUCTIONS) == set(a.scoped_release.INSTRUCTIONS)
    assert "3-6 meanings/questions" not in RELEASE.INSTRUCTIONS["REPAIR_QUESTIONS"]
    assert "all ten boolean" not in RELEASE.INSTRUCTIONS["REVIEW_QUESTIONS"]
    assert len(RELEASE.EXAMPLES) == len({e["id"] for e in RELEASE.EXAMPLES})


def test_phase2_keeps_questions_examples_and_serializes_review_repair_examples():
    assert [e["id"] for e in RELEASE.EXAMPLES if e["stage"] == "QUESTIONS"] == [
        "questions-given-reason-place-only", "questions-agreement-time-and-place",
        "questions-inference-from-explicit-premises", "questions-complete-small-experiment"]
    for stage in ("QUESTIONS", "REVIEW_QUESTIONS", "REPAIR_QUESTIONS"):
        text = RELEASE.INSTRUCTIONS[stage]
        block = text.split("\n\nEXAMPLES\n", 1)[1].split("\n\nINPUT\n", 1)[0]
        encoded = [json.loads(line) for line in block.splitlines() if line.startswith("{")]
        assert encoded == [{k: e[k] for k in ("scope", "input", "expectedOutput")}
                           for e in RELEASE.EXAMPLES if e["stage"] == stage]
    repair = RELEASE.INSTRUCTIONS["REPAIR_QUESTIONS"].split("\n\nEXAMPLES", 1)[0]
    assert "repairContext" not in repair
    assert "failedOutput" in repair and "issues" in repair
    assert "Echo an allowed item unchanged" in repair


@pytest.mark.parametrize("example_id,valid", [
    ("review-questions-parent-part-mismatch", False),
    ("review-questions-shared-subject-distinct-parts", True)])
def test_restored_review_examples_distinguish_answer_from_target_errors(example_id, valid):
    sample = next(e for e in RELEASE.EXAMPLES if e["id"] == example_id)
    output = sample["expectedOutput"]
    # Excerpts teach individual rows, not the runtime minimum bank size.
    meaning = a.MeaningCheck.model_validate(output["meaningChecks"][0])
    question = w.QuestionEvidenceCheck.model_validate(output["questionChecks"][0])
    assert output["valid"] is valid
    assert question.answerSupported and question.groundingSupported
    assert question.factSupported and question.speechActSupported
    assert question.questionMatchesFact is valid
    assert meaning.meaningSupported is valid
    assert len([v for v in question.model_dump().values() if isinstance(v, bool)]) == 9
    assert bool(output["issues"]) is not valid


@pytest.mark.parametrize("example_id", [
    "repair-question-reference-only", "repair-question-linked-scope"])
def test_repair_examples_are_complete_scoped_patches_with_valid_evidence(example_id):
    sample = next(e for e in RELEASE.EXAMPLES if e["id"] == example_id)
    rows = flexible_fixtures()
    excerpt = sample["input"]["failedOutput"]["activities"][0]
    rows[0]["activities"][0]["segments"][0]["textEn"] += " " + sample["input"]["dialogue"]["activities"][0]["segments"][0]["textEn"]
    rows[1]["activities"][0]["assessment"]["meanings"][0] = copy.deepcopy(excerpt["assessment"]["meanings"][0])
    rows[1]["activities"][0]["questions"][0] = copy.deepcopy(excerpt["questions"][0])
    original = a.FlexibleQuestions.model_validate(rows[1])
    patch = a.PartsQuestionPatch.model_validate(sample["expectedOutput"])
    allowed = {(t["itemType"], t["activityId"], t["itemId"]) for t in sample["input"]["allowedTargets"]}
    merged = h.merge_patch(original, patch, allowed, "QUESTIONS")
    before, after = h.items(original, "QUESTIONS"), h.items(merged, "QUESTIONS")
    changed = {key for key in before if before[key] != after[key]}
    assert changed == ({("question", "a1", "q1")} if example_id.endswith("reference-only") else allowed)
    question = merged.activities[0].questions[0]
    assert question.answerVi == "Phòng số bốn."
    assert question.promptVi == excerpt["questions"][0]["promptVi"]
    assert question.skill == "detail" and question.supportKind == "direct"
    assert question.inferenceReasonEn == ""
    assert question.evidence == original.activities[0].questions[0].evidence
    assert merged.activities[0].assessment.meanings[0].requiredParts[0].meaningEn == "The workshop is in room four."
    assert len(merged.activities[0].assessment.meanings[0].requiredParts) == 1
    assert all(before[k] == after[k] for k in before if k not in allowed)
    a.validate_questions(merged, w.Dialogue.model_validate(rows[0]), parts_payload(promptHash=PIN).learningContext)
    with pytest.raises(ValueError, match="patch targets mismatch"):
        h.merge_patch(original, patch, {("question", "a1", "q1")}, "QUESTIONS")


@pytest.mark.parametrize("source_error", [False, True])
def test_localized_translation_repairs_despite_global_rubric_and_retains_other_failures(source_error):
    rows = flexible_fixtures()
    failed = copy.deepcopy(rows[4])
    failed["valid"] = False
    failed["issues"] = ["a1/s1: sai bản dịch."]
    failed["translationChecks"][0].update(meaningPreserved=False, reasonVi="a1/s1: sai bản dịch.")
    for rubric in failed["rubricChecks"]:
        if rubric["category"] == "TRANSLATIONS" or source_error and rubric["category"] == "SOURCE_ROLES":
            rubric.update(passed=False, reasonVi="Sai nội dung cần được giữ lại.")
    corrected = copy.deepcopy(rows[3]["segments"][0])
    corrected["textVi"] = "Cửa hàng mở lúc chín giờ. Bánh mì giá sáu bảng. Vui lòng đến lấy vào ngày mai."
    patch = dict(replacements=[corrected])
    # The re-review incorrectly clears SOURCE_ROLES: reconciliation must retain it.
    result, calls = execute(parts_payload(promptHash=PIN), [*rows[:4], failed, patch, rows[4]])
    attempt = result["qualityHarness"]["attempts"][0]
    assert attempt["attempted"] and attempt["accepted"]
    assert attempt["unlocalizedFindings"]
    assert result["task"]["translations"][0] == corrected
    assert result["task"]["qualityPassed"] is (not source_error)
    assert result["qualityHarness"]["extraCalls"] == 2
    assert len(calls) == 7
    if source_error:
        verdict = result["reviewAdvisory"]["review"]
        assert not next(r for r in verdict["rubricChecks"] if r["category"] == "SOURCE_ROLES")["passed"]


def test_global_translation_failure_without_local_evidence_is_not_guessed():
    rows = flexible_fixtures()
    rows[4]["valid"] = False
    rows[4]["issues"] = ["Translation needs review."]
    next(r for r in rows[4]["rubricChecks"] if r["category"] == "TRANSLATIONS")["passed"] = False
    result, calls = execute(parts_payload(promptHash=PIN), rows)
    assert not result["task"]["qualityPassed"]
    assert not result["qualityHarness"]["attempts"][0]["attempted"]
    assert result["qualityHarness"]["attempts"][0]["reason"] == "unlocalized_issue"
    assert len(calls) == 5


def test_question_patch_includes_linked_scoring_target_without_opening_unrelated_items():
    rows = flexible_fixtures()
    failed = copy.deepcopy(rows[2])
    failed["valid"] = False
    failed["questionChecks"][0].update(questionMatchesFact=False, reasonVi="Mục tiêu chấm chứa ý ngoài câu hỏi.")
    activity = rows[1]["activities"][0]
    question = copy.deepcopy(activity["questions"][0])
    question["promptVi"] = "Cửa hàng mở lúc mấy giờ?"
    meaning = copy.deepcopy(activity["assessment"]["meanings"][0])
    meaning["meaningEn"] = meaning["requiredParts"][0]["meaningEn"] = "The opening time is nine."
    question["factEn"] = meaning["meaningEn"]
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=meaning)],
                 questionReplacements=[dict(activityId="a1", question=question)])
    result, calls = execute(parts_payload(promptHash=PIN), [*rows[:2], failed, patch, rows[2], *rows[3:]])
    targets = json.loads(calls[3][1][1]["content"])["allowedTargets"]
    assert {(t["itemType"], t["activityId"], t["itemId"]) for t in targets} == {
        ("meaning", "a1", "m1"), ("question", "a1", "q1")}
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["task"]["activities"][0]["assessment"]["meanings"][0]["meaningEn"] == meaning["meaningEn"]
    assert result["task"]["activities"][0]["assessment"]["meanings"][1] == activity["assessment"]["meanings"][1]


def question_patch(bank, activity_id=None):
    return dict(
        meaningReplacements=[dict(activityId=act["id"], meaning=copy.deepcopy(m))
            for act in bank["activities"] for m in act["assessment"]["meanings"]
            if activity_id is None or act["id"] == activity_id and m["id"] == "m1"],
        questionReplacements=[dict(activityId=act["id"], question=copy.deepcopy(q))
            for act in bank["activities"] for q in act["questions"]
            if activity_id is None or act["id"] == activity_id and q["id"] == "q1"])


@pytest.mark.parametrize("bad_quote", [False, True])
def test_missing_focus_is_repaired_with_linked_content_not_a_code_relabel(bad_quote):
    rows = flexible_fixtures()
    value = parts_payload(promptHash=PIN)
    value.learningContext.focusSkill = "main-idea"
    fixed = copy.deepcopy(rows[1])
    act = fixed["activities"][0]
    meaning, question = act["assessment"]["meanings"][0], act["questions"][0]
    meaning.update(skill="main-idea", meaningEn="The speaker explains when to collect an order.")
    meaning["evidence"][0]["quoteEn"] = rows[0]["activities"][0]["segments"][0]["textEn"]
    meaning["requiredParts"] = [dict(id="p1", meaningEn=meaning["meaningEn"], evidence=copy.deepcopy(meaning["evidence"]))]
    question.update(skill="main-idea", factEn=meaning["meaningEn"], promptVi="What is the speaker mainly explaining?",
                    answerVi="When to collect an order.", evidence=copy.deepcopy(meaning["evidence"]))
    if bad_quote:
        rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    original = copy.deepcopy(rows[1])
    result, calls = execute(value, [*rows[:2], question_patch(fixed), *rows[2:]])
    data = json.loads(calls[2][1][1]["content"])
    assert "missing_learning_focus_coverage" in {i["code"] for i in data["issues"]}
    if bad_quote:
        assert "unanchored_evidence" in {i["code"] for i in data["issues"]}
    assert len(data["allowedTargets"]) == 12
    assert "not only the skill label" in next(i["constraint"] for i in data["issues"] if i["code"] == "missing_learning_focus_coverage")
    assert result["task"]["qualityPassed"]
    assert result["task"]["activities"][0]["questions"][0]["promptVi"] == question["promptVi"]
    expected = a.attach_questions(w.Dialogue.model_validate(rows[0]), a.FlexibleQuestions.model_validate(fixed), value.learningContext)
    assert result["task"]["activities"][1] == expected.activities[1].model_dump()
    assert result["qualityHarness"]["extraCalls"] == 2
    assert result["qualityHarness"]["implementationVersion"] == h.IMPLEMENTATION_VERSION
    assert rows[1] == original


@pytest.mark.parametrize("draft_kind", ["unchanged", "schema", "scope"])
def test_one_rejected_draft_can_be_corrected_with_same_scope_and_independent_review(draft_kind):
    rows = flexible_fixtures()
    fixed = question_patch(rows[1], "a1")
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    draft = question_patch(rows[1], "a1") if draft_kind == "unchanged" else ({ } if draft_kind == "schema" else question_patch(rows[1]))
    result, calls = execute(parts_payload(promptHash=PIN), [*rows[:2], draft, fixed, *rows[2:]])
    repair_calls = [json.loads(c[1][1]["content"]) for c in calls if c[0] == "scenario_repair_questions"]
    assert len(repair_calls) == 2
    first, second = repair_calls
    assert first["failedOutput"] == second["failedOutput"] == rows[1]
    assert first["allowedTargets"] == second["allowedTargets"]
    assert len(second["allowedTargets"]) == 2
    assert second["formatFeedback"]["failedOutput"] == draft
    assert second["formatFeedback"]["schemaIssues"]
    assert calls[4][0] == "scenario_review_questions"
    assert result["qualityHarness"]["extraCalls"] == 3
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["qualityHarness"]["attempts"][0]["draftIssues"]
    assert result["task"]["qualityPassed"]


def test_repeated_invalid_patch_is_rejected_and_keeps_diagnostics():
    rows = flexible_fixtures()
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    invalid = question_patch(rows[1], "a1")
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=PIN), [*rows[:2], invalid, invalid])
    report = caught.value.quality_harness
    assert report["extraCalls"] == 2
    assert not report["attempts"][0]["accepted"]
    assert report["attempts"][0]["issues"][0]["code"] == "unanchored_evidence"
    assert [v["status"] for v in report["validations"]] == ["FAIL", "FAIL", "FAIL"]


@pytest.mark.parametrize("boundary", ["budget", "deadline", "retry_disabled"])
def test_correction_never_uses_reserved_review_budget_or_expired_deadline(monkeypatch, boundary):
    rows = flexible_fixtures()
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    invalid = question_patch(rows[1], "a1")
    if boundary == "budget":
        monkeypatch.setitem(RELEASE.HARNESS_POLICY, "maxExtraCalls", 2)
    elif boundary == "retry_disabled":
        monkeypatch.setitem(RELEASE.HARNESS_POLICY, "maxFormatRetriesPerStage", 0)
    else:
        available = h.h.State.available
        monkeypatch.setattr(h.h.State, "available", lambda self, count: self.extra_calls == 0 and available(self, count))
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=PIN), [*rows[:2], invalid])
    assert caught.value.quality_harness["extraCalls"] == 1
    assert not caught.value.quality_harness["attempts"][0]["accepted"]


def test_new_global_failure_cannot_expand_original_patch_scope():
    rows = flexible_fixtures()
    for act in rows[1]["activities"][1:]:
        for item in [*act["assessment"]["meanings"], *act["questions"]]:
            item["skill"] = "main-idea"
    for item in [rows[1]["activities"][0]["assessment"]["meanings"][1], rows[1]["activities"][0]["questions"][1]]:
        item["skill"] = "main-idea"
    patch = question_patch(rows[1], "a1")
    for row in patch["meaningReplacements"]:
        row["meaning"]["skill"] = "main-idea"
    for row in patch["questionReplacements"]:
        row["question"]["skill"] = "main-idea"
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=PIN), [*rows[:2], patch])
    assert caught.value.quality_harness["extraCalls"] == 1
    assert caught.value.quality_harness["attempts"][0]["issues"][0]["code"] == "missing_learning_focus_coverage"


@pytest.mark.parametrize("error", [TimeoutError(), RuntimeError("provider unavailable")])
def test_provider_failure_is_not_a_correctable_draft(error):
    rows = flexible_fixtures()
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    with pytest.raises(type(error)):
        execute(parts_payload(promptHash=PIN), [*rows[:2], error])


def test_structurally_valid_repair_is_discarded_when_changed_target_fails_review():
    rows = flexible_fixtures()
    patch = question_patch(rows[1], "a1")
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    rows[2]["valid"] = False
    rows[2]["questionChecks"][0].update(answerSupported=False, reasonVi="Wrong answer.")
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=PIN), [*rows[:2], patch, rows[2]])
    attempt = caught.value.quality_harness["attempts"][0]
    assert not attempt["accepted"]
    assert attempt["reason"] == "review_unknown_or_regression"


def test_repeated_out_of_scope_patch_never_changes_original():
    rows = flexible_fixtures()
    malicious = question_patch(rows[1])
    malicious["questionReplacements"][-1]["question"]["answerVi"] = "Unrelated overwrite."
    rows[1]["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Not spoken."
    original = copy.deepcopy(rows[1])
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=PIN), [*rows[:2], malicious, malicious])
    assert rows[1] == original
    assert caught.value.quality_harness["extraCalls"] == 2
    assert not caught.value.quality_harness["attempts"][0]["accepted"]
