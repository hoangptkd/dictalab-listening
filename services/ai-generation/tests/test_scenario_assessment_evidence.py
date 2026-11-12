"""Candidate contract checks. Mocked verdicts are not evidence of semantic accuracy."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_audit as audit, scenario_workflow as w
from app.providers.base import ProviderHttpError
from test_scenario_assessment import execute
from test_scenario_assessment_audit import ledgers
from test_scenario_assessment_parts import parts_fixtures, parts_payload, two_part_check

PIN = "cc88eb362c99c0af4b480ce14d1caf9c3f990d192b8c970fba0b3c2bc7f0e6cd"


def evidence_ledgers(base=None):
    base = base or parts_fixtures()
    meanings, questions, review = ledgers(base)
    source = {(act["id"], q["id"]): q for act in base[1]["activities"] for q in act["questions"]}
    checks = {(r["activityId"], r["questionId"]): r for r in review["questionChecks"]}
    for row in questions["questions"]:
        key = (row["activityId"], row["questionId"])
        row["partChecks"] = checks[key].pop("partChecks")
        for part in row["partChecks"]:
            part.update(reasonVi="Đáp án nêu phần được hỏi.", answerQuotesVi=[source[key]["answerVi"]])
    for row in review["meaningChecks"]:
        row.update(uncoveredMeaningEn=[], bundledPartIds=[])
        for claim in row["claimChecks"]:
            claim.update(reasonVi="Nguồn giữ đúng ý và mức chắc chắn.", certaintyPreserved=True, speechActPreserved=True)
    return meanings, questions, review


def normalize(meanings, questions, review, base=None):
    base = base or parts_fixtures()
    return audit.to_evidence_review(audit.EvidenceQuestionReview.model_validate(review),
        audit.MeaningAudit.model_validate(meanings), audit.QuestionCoverageAudit.model_validate(questions),
        a.PartsQuestions.model_validate(base[1]), w.Dialogue.model_validate(base[0]))


def candidate():
    return parts_payload(promptHash=PIN)


def test_candidate_pin_matches_core_without_changing_default_grade_or_historical_hashes():
    assert a.prompt_hash(a.evidence_audit_candidate) == PIN
    assert a.selected_release(PIN) is a.evidence_audit_candidate
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"
    assert a.prompt_hash(a.independent_audit_candidate) == "60b2c0ca9be856e3e5f7aa8f2f9626f305d7772e7908e0860cd126e7a3574868"
    assert a.evidence_audit_candidate.INSTRUCTIONS["GRADE"] == a.projected_parts_candidate.INSTRUCTIONS["GRADE"]
    root = Path(__file__).resolve().parents[3]
    assert f'PARTS_V4_11_PROMPT_HASH="{PIN}"' in (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text(encoding="utf-8")


def test_wire_retains_minimum_three_targets_and_never_pads_a_short_bank():
    schema = a.response_schema(a.PartsQuestions, minimum_targets=True)
    for name, field in (("PartsAssessment", "meanings"), ("PartsActivityDraft", "questions")):
        assert schema["$defs"][name]["properties"][field]["minItems"] == 3
    base = parts_fixtures()
    base[1]["activities"][0]["assessment"]["meanings"].pop()
    base[1]["activities"][0]["questions"].pop()
    with pytest.raises(w.QualityRejected):
        execute(candidate(), base[:2])


def test_seven_call_path_keeps_scoped_inputs_and_freezes_audit_artifacts():
    base = parts_fixtures()
    result, calls = execute(candidate(), [*base[:2], *evidence_ledgers(base), *base[3:]])
    assert result["task"]["qualityPassed"] and len(calls) == 7
    assert result["qualityHarness"]["extraCalls"] == 0
    data = json.loads(calls[3][1][1]["content"])
    assert set(data) == {"questions"}
    for row in data["questions"]:
        assert set(row) == {"activityId", "questionId", "promptVi", "answerVi", "requiredParts"}
        assert all(set(p) == {"meaningId", "partId", "meaningEn"} for p in row["requiredParts"])
    comparison = json.loads(calls[4][1][1]["content"])
    assert comparison["questionAudit"] == evidence_ledgers(base)[1]
    assert len([k for k in result["qualityHarness"]["artifacts"] if k.startswith("AUDIT_")]) == 2


@pytest.mark.parametrize("status", ["GIVEN", "NOT_REQUESTED", "UNCERTAIN"])
def test_final_review_cannot_upgrade_frozen_question_scope(status):
    m, q, c = evidence_ledgers()
    q["questions"][0]["partChecks"][0]["requestStatus"] = status
    result = normalize(m, q, c)
    assert not result.valid and not result.questionChecks[0].questionMatchesFact
    assert status in result.questionChecks[0].reasonVi


@pytest.mark.parametrize("fault", ["given_quote", "invented_quote", "no_quote", "duplicate_quote", "missing_part", "foreign_part", "duplicate_question", "blank_reason"])
def test_invalid_coverage_cannot_become_an_advisory_pass(fault):
    m, q, c = evidence_ledgers()
    row = q["questions"][0]
    part = row["partChecks"][0]
    if fault == "given_quote": part["answerQuotesVi"] = [parts_fixtures()[1]["activities"][0]["questions"][0]["promptVi"]]
    elif fault == "invented_quote": part["answerQuotesVi"] = ["Not in the reference answer"]
    elif fault == "no_quote": part["answerQuotesVi"] = []
    elif fault == "duplicate_quote": part["answerQuotesVi"] *= 2
    elif fault == "missing_part": row["partChecks"] = []
    elif fault == "foreign_part": part["meaningId"] = "m3"
    elif fault == "duplicate_question": q["questions"][1] = copy.deepcopy(row)
    else: part["reasonVi"] = " "
    with pytest.raises(ValueError): normalize(m, q, c)


@pytest.mark.parametrize("flag", ["certaintyPreserved", "speechActPreserved"])
def test_source_mismatch_cannot_be_overruled_by_supported_label_or_valid_quote(flag):
    m, q, c = evidence_ledgers()
    c["meaningChecks"][0]["claimChecks"][0][flag] = False
    result = normalize(m, q, c)
    assert not result.valid
    assert not result.meaningChecks[0].meaningSupported
    assert not result.meaningChecks[0].groundingSupported


@pytest.mark.parametrize("field,value,flag", [
    ("uncoveredMeaningEn", ["They agree to the arrangement."], "groundingSupported"),
    ("bundledPartIds", ["p1"], "distinctMeaning"),
])
def test_direct_parent_parts_diagnostic_survives_an_incomplete_blind_ledger(field, value, flag):
    m, q, c = evidence_ledgers()
    c["meaningChecks"][0][field] = value
    result = normalize(m, q, c)
    assert not result.valid and not getattr(result.meaningChecks[0], flag)


@pytest.mark.parametrize("field,value", [("bundledPartIds", ["p4"]), ("bundledPartIds", ["p1", "p1"]), ("uncoveredMeaningEn", [" "])])
def test_diagnostics_keep_exact_part_ids_and_nonblank_claims(field, value):
    m, q, c = evidence_ledgers()
    c["meaningChecks"][0][field] = value
    with pytest.raises(ValueError): normalize(m, q, c)


def test_invalid_coverage_retries_only_that_stage_with_original_input_and_feedback():
    base = parts_fixtures()
    m, q, c = evidence_ledgers(base)
    bad = copy.deepcopy(q)
    bad["questions"][0]["partChecks"][0]["answerQuotesVi"] = []
    result, calls = execute(candidate(), [*base[:2], m, bad, q, c, *base[3:]])
    assert len(calls) == 8 and result["task"]["qualityPassed"]
    first, retry = [json.loads(calls[i][1][1]["content"]) for i in (3, 4)]
    feedback = retry.pop("formatFeedback")
    assert first == retry and feedback["failedOutput"] == bad
    assert result["qualityHarness"]["extraCalls"] == 1


@pytest.mark.parametrize("error", [TimeoutError("unknown"), ProviderHttpError(503, server_failure=True)])
def test_provider_failure_is_not_replayed_as_a_format_retry(error):
    base = parts_fixtures()
    with pytest.raises(type(error)):
        execute(candidate(), [*base[:2], evidence_ledgers(base)[0], error])


def test_repair_rebuilds_coverage_for_changed_question_within_eleven_calls():
    base = parts_fixtures()
    m, q, c = evidence_ledgers(base)
    q["questions"][0]["partChecks"][0]["requestStatus"] = "GIVEN"
    fixed = copy.deepcopy(base)
    fixed[1]["activities"][0]["questions"][0]["promptVi"] += " Hãy nêu đủ thông tin được hỏi."
    patch = dict(meaningReplacements=[], questionReplacements=[
        dict(activityId="a1", question=fixed[1]["activities"][0]["questions"][0])])
    result, calls = execute(candidate(), [*base[:2], m, q, c, patch, *evidence_ledgers(fixed), *base[3:]])
    assert len(calls) == 11 and result["qualityHarness"]["extraCalls"] == 4
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["task"]["qualityPassed"]
    fresh = json.loads(calls[7][1][1]["content"])
    assert fresh["questions"][0]["promptVi"] == fixed[1]["activities"][0]["questions"][0]["promptVi"]
    assert "repairContext" not in fresh


@pytest.mark.parametrize("answer", ["Novels take too long to read.", "Tiểu thuyết mất quá nhiều thời gian đọc.", "Novels mất quá nhiều thời gian đọc."])
def test_parts_aggregation_preserves_partial_credit_and_bilingual_evidence(answer):
    value, grade = two_part_check()
    data = value.model_dump()
    data["promptHash"] = PIN
    data["assessmentSubmission"]["answers"][0]["textVi"] = answer
    grade["meanings"][0]["parts"][0]["evidence"][0]["quote"] = answer
    result, calls = execute(a.ScenarioAssessmentInput.model_validate(data), [grade])
    assert len(calls) == 1 and result["task"]["meanings"][0]["status"] == "PARTIAL"
    data = json.loads(calls[0][1][1]["content"])
    assert '"answerVi"' not in json.dumps(data) and '"segments"' not in json.dumps(data)
