"""Direct audit contract regressions; semantic quality requires saved live evaluation."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_audit as audit, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_evidence import evidence_ledgers
from test_scenario_assessment_parts import parts_fixtures, parts_payload, two_part_check

PIN = "77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1"


def direct_ledgers(base=None):
    base = base or parts_fixtures()
    m, q, review = evidence_ledgers(base)
    targets = {(act["id"], meaning["id"]): meaning for act in base[1]["activities"]
               for meaning in act["assessment"]["meanings"]}
    for row in review["meaningChecks"]:
        row["duplicatePartPairs"] = []
        row["partChecks"] = [dict(partId=p["id"], evidence=p["evidence"],
            reasonVi="Phần chấm khớp nguồn và ý gốc.", sourceSupport="SUPPORTED", parentSupport="SUPPORTED")
            for p in targets[(row["activityId"], row["meaningId"])]["requiredParts"]]
    return m, q, review


def normalize(m, q, review, base=None):
    base = base or parts_fixtures()
    return audit.to_direct_review(audit.DirectQuestionReview.model_validate(review),
        audit.MeaningAudit.model_validate(m), audit.EvidenceFirstCoverageAudit.model_validate(q),
        a.PartsQuestions.model_validate(base[1]), w.Dialogue.model_validate(base[0]))


def two_parts():
    base = parts_fixtures()
    value, _ = two_part_check()
    base[0]["activities"][0]["segments"] = [s.model_dump() for s in value.activity.segments]
    base[1]["activities"][0]["assessment"]["meanings"][0] = value.activity.assessment.meanings[0].model_dump()
    return base


def test_candidate_pin_schemas_and_original_grade_are_consistent():
    selected = a.direct_audit_candidate
    assert a.prompt_hash(selected) == PIN and a.selected_release(PIN) is selected
    assert a.prompt_hash(a.evidence_audit_candidate) == "cc88eb362c99c0af4b480ce14d1caf9c3f990d192b8c970fba0b3c2bc7f0e6cd"
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"
    assert selected.INSTRUCTIONS["GRADE"] == a.evidence_audit_candidate.INSTRUCTIONS["GRADE"]
    assert audit.models_for(selected) == (audit.MeaningAudit, audit.EvidenceFirstCoverageAudit, audit.DirectQuestionReview)
    root = Path(__file__).resolve().parents[3]
    assert f'PARTS_V4_12_PROMPT_HASH="{PIN}"' in (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text(encoding="utf-8")


def test_coarse_claim_does_not_turn_two_distinct_parts_into_duplicates():
    base = two_parts()
    m, q, review = direct_ledgers(base)
    assert len(m["meanings"][0]["claims"]) == 1
    assert len(review["meaningChecks"][0]["partChecks"]) == 2
    assert normalize(m, q, review, base).valid
    # A fine-grained blind ledger must produce the same direct alignment result.
    claim = copy.deepcopy(review["meaningChecks"][0]["claimChecks"][0])
    claim["claimId"] = "c2"
    m["meanings"][0]["claims"].append(dict(id="c2", textEn="Some members might fall behind."))
    m["meanings"][0]["claims"][0]["textEn"] = "Novels take too long to read."
    review["meaningChecks"][0]["claimChecks"].append(claim)
    assert normalize(m, q, review, base).valid


@pytest.mark.parametrize("fault,flag", [("source", "meaningSupported"), ("certainty", "meaningSupported"),
    ("speech_act", "meaningSupported"), ("parent", "groundingSupported"), ("gap", "groundingSupported"),
    ("bundle", "distinctMeaning"), ("duplicate", "distinctMeaning"), ("exact_duplicate", "distinctMeaning")])
def test_real_alignment_failures_cannot_be_upgraded_by_passing_flags(fault, flag):
    base = two_parts()
    m, q, review = direct_ledgers(base)
    row = review["meaningChecks"][0]
    if fault == "source": row["partChecks"][0]["sourceSupport"] = "UNSUPPORTED"
    elif fault == "certainty": row["claimChecks"][0]["certaintyPreserved"] = False
    elif fault == "speech_act": row["claimChecks"][0]["speechActPreserved"] = False
    elif fault == "parent": row["partChecks"][0]["parentSupport"] = "UNSUPPORTED"
    elif fault == "gap": row["uncoveredMeaningEn"] = ["Both people agree to the plan."]
    elif fault == "bundle": row["bundledPartIds"] = ["p1"]
    elif fault == "duplicate": row["duplicatePartPairs"] = [dict(leftPartId="p1", rightPartId="p2", sharedMeaningEn="The same benefit.")]
    else:
        parts = base[1]["activities"][0]["assessment"]["meanings"][0]["requiredParts"]
        parts[1]["meaningEn"] = parts[0]["meaningEn"]
    result = normalize(m, q, review, base)
    assert not result.valid and not getattr(result.meaningChecks[0], flag)


@pytest.mark.parametrize("fault", ["quote", "empty_source", "missing_part", "foreign_part", "missing_claim",
    "missing_meaning", "missing_question", "blank_reason", "foreign_pair", "self_pair", "reversed_pair", "blank_pair",
    "foreign_bundle", "duplicate_bundle", "blank_gap", "empty_answer", "prompt_quote"])
def test_malformed_direct_audit_still_fails_closed(fault):
    base = two_parts()
    m, q, review = direct_ledgers(base)
    row = review["meaningChecks"][0]
    if fault == "quote": row["partChecks"][0]["evidence"] = [dict(segmentId="s1", quoteEn="Not in this source.")]
    elif fault == "empty_source": row["partChecks"][0]["evidence"] = []
    elif fault == "missing_part": row["partChecks"].pop()
    elif fault == "foreign_part": row["partChecks"][0]["partId"] = "p4"
    elif fault == "missing_claim": row["claimChecks"] = []
    elif fault == "missing_meaning": review["meaningChecks"].pop()
    elif fault == "missing_question": review["questionChecks"].pop()
    elif fault == "blank_reason": row["partChecks"][0]["reasonVi"] = " "
    elif fault in ("foreign_pair", "self_pair", "reversed_pair", "blank_pair"):
        pair = dict(leftPartId="p1", rightPartId="p2", sharedMeaningEn="Same assertion.")
        row["duplicatePartPairs"] = [pair]
        if fault == "foreign_pair": pair["rightPartId"] = "p4"
        elif fault == "self_pair": pair["rightPartId"] = "p1"
        elif fault == "blank_pair": pair["sharedMeaningEn"] = " "
        else: row["duplicatePartPairs"].append(dict(leftPartId="p2", rightPartId="p1", sharedMeaningEn="Same assertion."))
    elif fault == "foreign_bundle": row["bundledPartIds"] = ["p4"]
    elif fault == "duplicate_bundle": row["bundledPartIds"] = ["p1", "p1"]
    elif fault == "blank_gap": row["uncoveredMeaningEn"] = [" "]
    elif fault == "empty_answer": q["questions"][0]["partChecks"][0]["answerQuotesVi"] = []
    else: q["questions"][0]["partChecks"][0]["answerQuotesVi"] = [base[1]["activities"][0]["questions"][0]["promptVi"]]
    with pytest.raises(ValueError): normalize(m, q, review, base)


@pytest.mark.parametrize("request_status,answer", [("GIVEN", "INCOMPLETE"), ("NOT_REQUESTED", "INCOMPLETE"),
    ("REQUESTED", "INCOMPLETE"), ("REQUESTED", "CONTRADICTED"), ("UNCERTAIN", "UNCERTAIN")])
def test_frozen_question_coverage_cannot_be_upgraded(request_status, answer):
    m, q, review = direct_ledgers()
    q["questions"][0]["partChecks"][0].update(requestStatus=request_status, answerStatus=answer, answerQuotesVi=[])
    result = normalize(m, q, review)
    assert not result.valid and not result.questionChecks[0].questionMatchesFact
    if answer == "CONTRADICTED": assert not result.questionChecks[0].answerSupported


def test_seven_call_path_and_format_retry_use_candidate_schemas():
    base = parts_fixtures()
    m, q, review = direct_ledgers(base)
    broken = copy.deepcopy(q)
    broken["questions"][0]["partChecks"][0]["answerQuotesVi"] = []
    result, calls = execute(parts_payload(promptHash=PIN), [*base[:2], m, broken, q, review, *base[3:]])
    assert result["task"]["qualityPassed"] and len(calls) == 8
    assert result["qualityHarness"]["extraCalls"] == 1
    first, retry = [json.loads(calls[i][1][1]["content"]) for i in (3, 4)]
    feedback = retry.pop("formatFeedback")
    assert first == retry and feedback["failedOutput"] == broken


def test_repair_rebuilds_direct_audit_without_raising_call_envelope():
    base = parts_fixtures()
    m, q, review = direct_ledgers(base)
    q["questions"][0]["partChecks"][0]["requestStatus"] = "GIVEN"
    fixed = copy.deepcopy(base)
    fixed[1]["activities"][0]["questions"][0]["promptVi"] += " Hãy nêu đủ thông tin được hỏi."
    patch = dict(meaningReplacements=[], questionReplacements=[
        dict(activityId="a1", question=fixed[1]["activities"][0]["questions"][0])])
    result, calls = execute(parts_payload(promptHash=PIN), [*base[:2], m, q, review, patch, *direct_ledgers(fixed), *base[3:]])
    assert len(calls) == 11 and result["qualityHarness"]["extraCalls"] == 4
    assert result["qualityHarness"]["attempts"][0]["accepted"] and result["task"]["qualityPassed"]
