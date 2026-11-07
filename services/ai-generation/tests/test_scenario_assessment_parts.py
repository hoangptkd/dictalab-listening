"""Part completeness is server-owned; these tests do not assert model-quality accuracy."""
import copy
import itertools
import json

import pytest

from app import scenario_assessment as a
from app import scenario_workflow as w
from test_scenario_assessment import execute, fixtures, payload


def parts_fixtures():
    responses = fixtures()
    for activity in responses[1]["activities"]:
        activity["assessment"]["policyVersion"] = a.parts_release.POLICY_VERSION
        for meaning in activity["assessment"]["meanings"]:
            meaning["requiredParts"] = [dict(id="p1", meaningEn=meaning["meaningEn"], evidence=copy.deepcopy(meaning["evidence"]))]
    return responses


def parts_payload(phase="GENERATE", **changes):
    data = payload(phase).model_dump()
    data["promptHash"] = a.prompt_hash(a.parts_release)
    if phase == "GRADE":
        responses = parts_fixtures()
        content = a.attach_questions(w.Dialogue.model_validate(responses[0]), a.PartsQuestions.model_validate(responses[1]),
                                     a.AssessmentContext.model_validate(data["learningContext"]))
        data["activity"] = content.activities[0].model_dump()
    return a.ScenarioAssessmentInput.model_validate({**data, **changes})


def two_part_check():
    data = parts_payload("GRADE").model_dump()
    meaning = data["activity"]["assessment"]["meanings"][0]
    source = "Novels take too long to read, and some members might fall behind."
    data["activity"]["segments"][0]["textEn"] += " " + source
    evidence = [dict(segmentId="s1", quoteEn=source)]
    meaning.update(meaningEn=source, evidence=evidence, requiredParts=[
        dict(id="p1", meaningEn="Novels take too long to read.", evidence=[dict(segmentId="s1", quoteEn="Novels take too long to read")]),
        dict(id="p2", meaningEn="Some members might fall behind.", evidence=[dict(segmentId="s1", quoteEn="some members might fall behind")])])
    data["assessmentSubmission"] = dict(mode="CHECKS", recapText="", answers=[dict(questionId="q1", textVi="Novels take too long to read.")], meaningIds=["m1"])
    value = a.ScenarioAssessmentInput.model_validate(data)
    grade = dict(meanings=[dict(meaningId="m1", parts=[
        dict(partId="p1", status="CAPTURED", evidence=[dict(responseId="q1", quote="Novels take too long to read.")]),
        dict(partId="p2", status="ABSENT", evidence=[])])])
    return value, grade


def test_candidate_keeps_all_historical_hashes_and_uses_its_own_strict_schema():
    assert a.prompt_hash(a.parts_release) == "b9be440e03b9b31163f02c75e685092277fb42fb1ecc0156d871d030efa94503"
    assert a.selected_release(a.prompt_hash(a.parts_release)) is a.parts_release
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"
    for model in (a.PartsQuestions, a.PartsQuestionPatch, a.PartsGrade):
        assert '"maxItems"' not in json.dumps(a.response_schema(model))
        assert '"additionalProperties": false' in json.dumps(a.response_schema(model))
    assert "status" not in a.PartsGradeItem.model_json_schema()["properties"]


def test_generate_persists_required_parts_and_repairs_with_the_right_schema():
    responses = parts_fixtures()
    result, calls = execute(parts_payload(), responses)
    assert result["assessmentPolicyVersion"] == "scenario-meaning-assessment.v2"
    assert result["task"]["activities"][0]["assessment"]["meanings"][0]["requiredParts"]
    assert result["task"]["qualityPassed"]
    assert len(calls) == 5
    bad = copy.deepcopy(responses[1])
    bad["activities"][0]["assessment"]["meanings"][0]["requiredParts"][0]["evidence"][0]["quoteEn"] = "invented"
    patch = dict(meaningReplacements=[dict(activityId="a1", meaning=responses[1]["activities"][0]["assessment"]["meanings"][0])], questionReplacements=[])
    result, calls = execute(parts_payload(), [responses[0], bad, patch, *responses[2:]])
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert "requiredParts" in json.dumps(calls[2][2])
    assert json.loads(calls[2][1][1]["content"])["allowedTargets"][0]["meaningId"] == "m1"


@pytest.mark.parametrize("fault", ["empty", "duplicate", "foreign", "blank", "ungrounded", "outside"])
def test_required_parts_are_bounded_distinct_and_anchored_to_the_meaning(fault):
    responses = parts_fixtures()
    meaning = responses[1]["activities"][0]["assessment"]["meanings"][0]
    if fault == "empty": meaning["requiredParts"] = []
    elif fault == "duplicate": meaning["requiredParts"] *= 2
    elif fault == "foreign": meaning["requiredParts"][0]["id"] = "p2"
    elif fault == "blank": meaning["requiredParts"][0]["meaningEn"] = " "
    elif fault == "ungrounded": meaning["requiredParts"][0]["evidence"][0]["quoteEn"] = "invented"
    else: meaning["requiredParts"][0]["evidence"][0]["quoteEn"] = "The bread costs six pounds."
    with pytest.raises(ValueError):
        a.attach_questions(w.Dialogue.model_validate(responses[0]), a.PartsQuestions.model_validate(responses[1]), parts_payload().learningContext)


def test_saved_failure_gives_partial_without_a_model_supplied_aggregate_or_extra_call():
    value, grade = two_part_check()
    result, calls = execute(value, [grade])
    meaning = result["task"]["meanings"][0]
    assert meaning["status"] == "PARTIAL"
    assert meaning["parts"] == grade["meanings"][0]["parts"]
    assert len(calls) == 1
    assert calls[0][1][0]["content"] == a.parts_release.INSTRUCTIONS["GRADE"]


@pytest.mark.parametrize("mode", ["CHECKS", "RECAP"])
def test_all_part_status_pairs_have_deterministic_aggregation(mode):
    with pytest.raises(ValueError):
        a.aggregate_parts([], mode)
    for statuses in itertools.product(("CAPTURED", "PARTIAL", "INCORRECT", "UNCERTAIN", "ABSENT"), repeat=2):
        result = a.aggregate_parts(statuses, mode)
        assert (result == "CAPTURED") == all(s == "CAPTURED" for s in statuses)
        if "UNCERTAIN" in statuses: assert result == "UNCERTAIN"
        if statuses == ("ABSENT", "ABSENT"): assert result == ("UNASSESSED" if mode == "RECAP" else "MISSED")
        if statuses == ("CAPTURED", "ABSENT"): assert result == "PARTIAL"


@pytest.mark.parametrize("mode", ["CHECKS", "RECAP"])
def test_omission_is_derived_from_submission_mode_instead_of_left_to_the_model(mode):
    value, grade = two_part_check()
    if mode == "RECAP":
        value.assessmentSubmission.mode = "RECAP"
        value.assessmentSubmission.recapText = "They are choosing a book."
        value.assessmentSubmission.answers = []
    for part in grade["meanings"][0]["parts"]:
        part.update(status="ABSENT", evidence=[])
    result = a.derive_grade(a.PartsGrade.model_validate(grade), value)
    assert result.meanings[0].status == ("UNASSESSED" if mode == "RECAP" else "MISSED")


@pytest.mark.parametrize("fault", ["aggregate", "missing_part", "duplicate_part", "foreign_part", "absent_evidence", "missing_evidence", "invented", "foreign_response", "missing_meaning"])
def test_grading_cannot_invent_parts_or_evidence_or_choose_its_total(fault):
    value, grade = two_part_check()
    item = grade["meanings"][0]
    if fault == "aggregate": item["status"] = "CAPTURED"
    elif fault == "missing_part": item["parts"].pop()
    elif fault == "duplicate_part": item["parts"][1]["partId"] = "p1"
    elif fault == "foreign_part": item["parts"][1]["partId"] = "p4"
    elif fault == "absent_evidence": item["parts"][1]["evidence"] = copy.deepcopy(item["parts"][0]["evidence"])
    elif fault == "missing_evidence": item["parts"][0]["evidence"] = []
    elif fault == "invented": item["parts"][0]["evidence"][0]["quote"] = "Everyone agrees."
    elif fault == "foreign_response": item["parts"][0]["evidence"][0]["responseId"] = "q2"
    else: grade["meanings"] = []
    with pytest.raises(ValueError):
        a.derive_grade(a.PartsGrade.model_validate(grade), value)


def test_release_and_activity_policy_cannot_be_crossed_or_silently_upgraded():
    old = payload("GRADE").model_dump()
    old["promptHash"] = a.prompt_hash(a.parts_release)
    with pytest.raises(ValueError, match="policy does not match"):
        a.ScenarioAssessmentInput.model_validate(old)
    new = parts_payload("GRADE").model_dump()
    new["promptHash"] = a.prompt_hash()
    with pytest.raises(ValueError, match="policy does not match"):
        a.ScenarioAssessmentInput.model_validate(new)
