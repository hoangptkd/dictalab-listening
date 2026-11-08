"""Offline relation/contract tests; passing mocked relations are not model quality evidence."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a
from app import scenario_assessment_audit as audit
from app import scenario_workflow as w
from app.scenario_harness import digest
from app.providers.base import ProviderHttpError
from test_scenario_assessment import execute
from test_scenario_assessment_parts import parts_fixtures, parts_payload, two_part_check

CASES = json.loads((Path(__file__).parent / "fixtures/scenario-independent-audit-regressions.json").read_text(encoding="utf-8"))


def ledgers(responses=None):
    responses = responses or parts_fixtures()
    meanings, questions, comparison = [], [], copy.deepcopy(responses[2])
    for act in responses[1]["activities"]:
        for m in act["assessment"]["meanings"]:
            meanings.append(dict(activityId=act["id"], meaningId=m["id"], claims=[dict(id="c1", textEn=m["meaningEn"])]))
        for q in act["questions"]:
            questions.append(dict(activityId=act["id"], questionId=q["id"], requestedEn=q["factEn"], givenEn=""))
    by_meaning = {(act["id"], m["id"]): m for act in responses[1]["activities"] for m in act["assessment"]["meanings"]}
    by_question = {(act["id"], q["id"]): q for act in responses[1]["activities"] for q in act["questions"]}
    for row in comparison["meaningChecks"]:
        m = by_meaning[(row["activityId"], row["meaningId"])]
        row.update(claimChecks=[dict(claimId="c1", sourceSupport="SUPPORTED", evidence=m["evidence"])],
                   partChecks=[dict(partId=p["id"], claimIds=["c1"], relation="EQUIVALENT") for p in m["requiredParts"]])
    for row in comparison["questionChecks"]:
        q = by_question[(row["activityId"], row["questionId"])]
        row["partChecks"] = [dict(meaningId=mid, partId=p["id"], requestStatus="REQUESTED", answerStatus="COMPLETE")
                             for mid in q["meaningIds"] for p in by_meaning[(row["activityId"], mid)]["requiredParts"]]
    return dict(meanings=meanings), dict(questions=questions), comparison


def normalize(m, q, c, responses=None):
    responses = responses or parts_fixtures()
    return audit.to_review(audit.AuditedQuestionReview.model_validate(c), audit.MeaningAudit.model_validate(m),
        audit.QuestionAudit.model_validate(q), a.PartsQuestions.model_validate(responses[1]), w.Dialogue.model_validate(responses[0]))


def candidate():
    return parts_payload(promptHash=a.prompt_hash(a.independent_audit_candidate))


def test_candidate_hash_policy_schema_and_old_grade_are_frozen():
    assert a.prompt_hash(a.independent_audit_candidate) == "60b2c0ca9be856e3e5f7aa8f2f9626f305d7772e7908e0860cd126e7a3574868"
    assert digest(a.independent_audit_candidate.HARNESS_POLICY) == "6feb75d68b466277e3bc3a9b1c636716569a0b21b55b3aa44a143b18f06d817d"
    assert a.independent_audit_candidate.INSTRUCTIONS["GRADE"] == a.projected_parts_candidate.INSTRUCTIONS["GRADE"]
    for model in (audit.MeaningAudit, audit.QuestionAudit, audit.AuditedQuestionReview):
        schema = json.dumps(a.response_schema(model))
        assert '"maxItems"' not in schema and '"minItems"' not in schema
        assert '"additionalProperties": false' in schema
    value, grade = two_part_check()
    value = a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "promptHash": a.prompt_hash(a.independent_audit_candidate)})
    result, calls = execute(value, [grade])
    assert len(calls) == 1 and result["task"]["meanings"][0]["status"] == "PARTIAL"
    projected = json.loads(calls[0][1][1]["content"])
    assert '"evidence"' not in json.dumps(projected) and '"answerVi"' not in json.dumps(projected)


def test_seven_calls_capture_blind_whitelists_and_comparison_ledgers():
    base = parts_fixtures()
    result, calls = execute(candidate(), [*base[:2], *ledgers(base), *base[3:]])
    assert result["task"]["qualityPassed"] and len(calls) == 7
    assert result["qualityHarness"]["extraCalls"] == 0
    meaning_input, question_input, comparison = [json.loads(call[1][1]["content"]) for call in calls[2:5]]
    assert set(meaning_input) == {"meanings"} and set(question_input) == {"questions"}
    assert all(set(r) == {"activityId", "meaningId", "meaningEn"} for r in meaning_input["meanings"])
    assert all(set(r) == {"activityId", "questionId", "promptVi"} for r in question_input["questions"])
    assert set(comparison) == {"questions", "targets", "learningContext", "meaningAudit", "questionAudit"}
    assert comparison["meaningAudit"] == ledgers(base)[0]
    assert len([k for k in result["qualityHarness"]["artifacts"] if k.startswith("AUDIT_")]) == 2


@pytest.mark.parametrize("case", CASES["cases"], ids=lambda c: c["id"])
def test_frozen_failure_relations_lower_exact_expected_flag(case):
    m, q, c = ledgers()
    if case["id"] in ("F1", "F2"):
        c["questionChecks"][0]["partChecks"][0]["requestStatus"] = case["relation"]
        row = lambda r: r.questionChecks[0]
    else:
        row = lambda r: r.meaningChecks[0]
        if case["id"] == "F3":
            c["meaningChecks"][0]["claimChecks"][0]["sourceSupport"] = "UNSUPPORTED"
        else:
            m["meanings"][0]["claims"].append(dict(id="c2", textEn="An independently omitted claim."))
            c["meaningChecks"][0]["claimChecks"].append(dict(claimId="c2", sourceSupport="SUPPORTED", evidence=c["meaningChecks"][0]["claimChecks"][0]["evidence"]))
            if case["id"] == "F5": c["meaningChecks"][0]["partChecks"][0]["claimIds"].append("c2")
    result = normalize(m, q, c)
    assert getattr(row(result), case["expectedFlag"]) is False
    assert not result.valid and "AUDIT_" in row(result).reasonVi
    assert "Có nguồn" not in row(result).reasonVi


@pytest.mark.parametrize("case", CASES["adjacentPositives"], ids=lambda c: c["label"])
def test_adjacent_positive_relation_shapes(case):
    base = parts_fixtures()
    meaning = base[1]["activities"][0]["assessment"]["meanings"][0]
    meaning["requiredParts"] = [{**copy.deepcopy(meaning["requiredParts"][0]), "id": f"p{i+1}"} for i in range(case["parts"])]
    m, q, c = ledgers(base)
    texts = [case["meaning"]] if case["claims"] == 1 else ["Mira prefers short stories.", "Short stories fit the lunch break."]
    m["meanings"][0]["claims"] = [dict(id=f"c{i+1}", textEn=text) for i, text in enumerate(texts)]
    c["meaningChecks"][0]["claimChecks"] = [dict(claimId=f"c{i+1}", sourceSupport="SUPPORTED", evidence=meaning["evidence"]) for i in range(case["claims"])]
    for i, part in enumerate(c["meaningChecks"][0]["partChecks"]): part["claimIds"] = [f"c{i+1}"]
    assert normalize(m, q, c, base).valid


@pytest.mark.parametrize("fault", ["missing", "duplicate", "cross_activity", "claim_id", "blank", "missing_question", "quote", "supported_empty", "foreign_claim", "duplicate_claim", "missing_part", "foreign_question_part"])
def test_contract_rejects_coverage_reference_and_evidence_gaps(fault):
    m, q, c = ledgers()
    if fault == "missing": m["meanings"].pop()
    elif fault == "duplicate": m["meanings"][1] = copy.deepcopy(m["meanings"][0])
    elif fault == "cross_activity": m["meanings"][0]["activityId"] = "a2"
    elif fault == "claim_id": m["meanings"][0]["claims"][0]["id"] = "c2"
    elif fault == "blank": q["questions"][0]["requestedEn"] = " "
    elif fault == "missing_question": q["questions"].pop()
    elif fault == "quote": c["meaningChecks"][0]["claimChecks"][0]["evidence"][0]["quoteEn"] = "invented"
    elif fault == "supported_empty": c["meaningChecks"][0]["claimChecks"][0]["evidence"] = []
    elif fault == "foreign_claim": c["meaningChecks"][0]["partChecks"][0]["claimIds"] = ["c6"]
    elif fault == "duplicate_claim": c["meaningChecks"][0]["partChecks"][0]["claimIds"] *= 2
    elif fault == "missing_part": c["meaningChecks"][0]["partChecks"] = []
    else: c["questionChecks"][0]["partChecks"][0]["meaningId"] = "m3"
    with pytest.raises(ValueError): normalize(m, q, c)


@pytest.mark.parametrize("status", ["UNSUPPORTED", "UNCERTAIN"])
def test_quote_does_not_upgrade_non_supported_and_false_flags_survive(status):
    m, q, c = ledgers()
    c["meaningChecks"][0]["claimChecks"][0]["sourceSupport"] = status
    c["questionChecks"][1]["answerSupported"] = False
    q["questions"][0]["requestedEn"] = "Frozen interpretation"
    result = normalize(m, q, c)
    assert not result.meaningChecks[0].meaningSupported and not result.meaningChecks[0].groundingSupported
    assert not result.questionChecks[1].answerSupported and not result.valid
    assert result.questionChecks[0].requestedMeaningEn == "Frozen interpretation"
    fresh = ledgers()
    fresh[2]["valid"] = False
    assert not normalize(*fresh).valid


@pytest.mark.parametrize("status", ["INCOMPLETE", "CONTRADICTED", "UNCERTAIN"])
def test_incomplete_answers_lower_match_and_contradiction_lowers_support(status):
    m, q, c = ledgers()
    c["questionChecks"][0]["partChecks"][0]["answerStatus"] = status
    result = normalize(m, q, c)
    assert not result.questionChecks[0].questionMatchesFact
    assert result.questionChecks[0].answerSupported == (status != "CONTRADICTED")


@pytest.mark.parametrize("ordinal", [0, 1, 2])
def test_one_format_retry_repeats_only_failed_stage_and_keeps_exact_feedback(ordinal):
    base = parts_fixtures()
    audits = list(ledgers(base))
    responses = [*base[:2], *audits[:ordinal], {}, *audits[ordinal:], *base[3:]]
    result, calls = execute(candidate(), responses)
    assert len(calls) == 8 and result["task"]["qualityPassed"]
    before, after = [json.loads(calls[i][1][1]["content"]) for i in (ordinal+2, ordinal+3)]
    feedback = after.pop("formatFeedback")
    assert before == after and feedback["failedOutput"] == {}
    assert feedback["schemaIssues"] and result["qualityHarness"]["extraCalls"] == 1


def test_second_bad_stage_fails_contract_instead_of_advisory_or_partial_pass():
    base = parts_fixtures()
    with pytest.raises(w.QualityRejected, match="independent audit contract"):
        execute(candidate(), [*base[:2], {}, ledgers()[0], {}])


@pytest.mark.parametrize("error", [TimeoutError("unknown"), ProviderHttpError(503, server_failure=True)])
def test_provider_unknown_does_not_retry_or_become_pass(error):
    with pytest.raises(type(error)): execute(candidate(), [*parts_fixtures()[:2], error])


def test_semantic_repair_has_eleven_calls_and_rebuilds_both_ledgers_with_dependent_questions():
    base = parts_fixtures()
    initial = list(ledgers(base))
    initial[2]["meaningChecks"][0]["claimChecks"][0]["sourceSupport"] = "UNSUPPORTED"
    fixed = copy.deepcopy(base)
    fixed[1]["activities"][0]["assessment"]["meanings"][0]["meaningEn"] += " Source-grounded repair."
    fixed[1]["activities"][0]["questions"][0]["promptVi"] += " Câu hỏi đã sửa."
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=fixed[1]["activities"][0]["assessment"]["meanings"][0])],
                 questionReplacements=[dict(activityId="a1", question=fixed[1]["activities"][0]["questions"][0])])
    result, calls = execute(candidate(), [*base[:2], *initial, patch, *ledgers(fixed), *base[3:]])
    assert len(calls) == 11 and result["qualityHarness"]["extraCalls"] == 4
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    targets = json.loads(calls[5][1][1]["content"])["allowedTargets"]
    assert {(t["itemType"], t["activityId"], t["itemId"]) for t in targets} == {("meaning", "a1", "m1"), ("question", "a1", "q1")}
    assert json.loads(calls[6][1][1]["content"])["meanings"][0]["meaningEn"].endswith("Source-grounded repair.")
    assert "repairContext" not in json.loads(calls[6][1][1]["content"])
    assert "repairContext" in json.loads(calls[8][1][1]["content"])
    assert len([k for k in result["qualityHarness"]["artifacts"] if k.startswith("AUDIT_MEANINGS:")]) == 2


def test_format_retry_leaves_insufficient_budget_for_question_patch():
    base = parts_fixtures()
    m, q, c = ledgers()
    c["questionChecks"][0]["partChecks"][0]["requestStatus"] = "GIVEN"
    result, calls = execute(candidate(), [*base[:2], {}, m, q, c, *base[3:]])
    assert len(calls) == 8 and not result["task"]["qualityPassed"]
    assert result["qualityHarness"]["attempts"][0]["reason"] == "budget_or_deadline"
    assert not result["qualityHarness"]["attempts"][0]["attempted"]


def test_structural_repair_uses_four_extras_without_duplicate_initial_audit():
    base = parts_fixtures()
    bad = copy.deepcopy(base[1])
    bad["activities"][0]["assessment"]["meanings"][0]["requiredParts"][0]["evidence"][0]["quoteEn"] = "invented"
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=base[1]["activities"][0]["assessment"]["meanings"][0])],
                 questionReplacements=[dict(activityId="a1", question=base[1]["activities"][0]["questions"][0])])
    result, calls = execute(candidate(), [base[0], bad, patch, *ledgers(base), *base[3:]])
    assert len(calls) == 8 and result["qualityHarness"]["extraCalls"] == 4
    assert result["qualityHarness"]["attempts"][0]["accepted"] and result["task"]["qualityPassed"]


@pytest.mark.parametrize("relation", ["MISMATCH", "UNCERTAIN"])
def test_part_relation_does_not_pass_only_because_parent_coverage_is_complete(relation):
    m, q, c = ledgers()
    c["meaningChecks"][0]["partChecks"][0]["relation"] = relation
    assert not normalize(m, q, c).meaningChecks[0].groundingSupported


def test_repeated_claim_text_is_contract_error_and_duplicate_scoring_lowers_distinct():
    m, q, c = ledgers()
    m["meanings"][0]["claims"].append(dict(id="c2", textEn=m["meanings"][0]["claims"][0]["textEn"].upper()))
    with pytest.raises(ValueError, match="claim IDs or text"): normalize(m, q, c)
    base = parts_fixtures()
    parent = base[1]["activities"][0]["assessment"]["meanings"][0]
    parent["requiredParts"].append({**copy.deepcopy(parent["requiredParts"][0]), "id": "p2"})
    result = normalize(*ledgers(base), base)
    assert not result.meaningChecks[0].distinctMeaning


def test_out_of_scope_patch_retains_original_and_prior_failure():
    base = parts_fixtures()
    m, q, c = ledgers()
    c["questionChecks"][0]["partChecks"][0]["requestStatus"] = "GIVEN"
    patch = dict(meaningReplacements=[dict(activityId="a2", meaning=base[1]["activities"][1]["assessment"]["meanings"][0])], questionReplacements=[])
    result, calls = execute(candidate(), [*base[:2], m, q, c, patch, *base[3:]])
    assert len(calls) == 8 and not result["task"]["qualityPassed"]
    assert not result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["reviewAdvisory"]["questions"]["questionChecks"][0]["questionMatchesFact"] is False
    assert result["task"]["activities"][0]["questions"][0]["promptVi"] == base[1]["activities"][0]["questions"][0]["promptVi"]


def test_recheck_cannot_erase_prior_failure_on_unchanged_item():
    base = parts_fixtures()
    m, q, c = ledgers()
    c["meaningChecks"][0]["importantMeaning"] = False
    c["questionChecks"][0]["partChecks"][0]["requestStatus"] = "GIVEN"
    fixed = copy.deepcopy(base)
    fixed[1]["activities"][0]["questions"][0]["promptVi"] += " Đã sửa."
    # Include unchanged meaning because repair scope requires every flagged item.
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=base[1]["activities"][0]["assessment"]["meanings"][0])],
                 questionReplacements=[dict(activityId="a1", question=fixed[1]["activities"][0]["questions"][0])])
    result, calls = execute(candidate(), [*base[:2], m, q, c, patch, *ledgers(fixed), *base[3:]])
    assert len(calls) == 11 and result["qualityHarness"]["attempts"][0]["accepted"]
    assert not result["reviewAdvisory"]["questions"]["meaningChecks"][0]["importantMeaning"]
    assert not result["task"]["qualityPassed"]
