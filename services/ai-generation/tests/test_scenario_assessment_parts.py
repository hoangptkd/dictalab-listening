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


def test_v4_1_is_pinned_and_preserves_v4_schemas_and_default_admission():
    assert a.prompt_hash(a.parts_v4_1) == "ce0a72c6c7616ebfaab979bf3404379d5534ca9e21b4fbf8e4344a0bcea02714"
    assert a.selected_release(a.prompt_hash(a.parts_v4_1)) is a.parts_v4_1
    value = parts_payload(promptHash=a.prompt_hash(a.parts_v4_1))
    result, calls = execute(value, parts_fixtures())
    assert result["promptVersion"] == "SCENARIO_MEANING_ASSESSMENT_V4_1"
    assert result["assessmentPolicyVersion"] == a.parts_release.POLICY_VERSION
    assert calls[1][2] == a.response_schema(a.PartsQuestions)
    value, grade = two_part_check()
    value = a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "promptHash": a.prompt_hash(a.parts_v4_1)})
    result, calls = execute(value, [grade])
    assert result["task"]["meanings"][0]["status"] == "PARTIAL"
    assert len(calls) == 1
    assert calls[0][1][0]["content"] == a.parts_v4_1.INSTRUCTIONS["GRADE"]
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"


def test_compact_v4_3_uses_existing_schemas_and_keeps_v4_1_grading():
    assert a.prompt_hash(a.parts_v4_3) == "139a7d7789923e10af8412d5f23f6fec7577116e4d1c97eb6ce907230a56f0aa"
    assert a.parts_v4_3.INSTRUCTIONS["GRADE"] == a.parts_v4_1.INSTRUCTIONS["GRADE"]
    value = parts_payload(promptHash=a.prompt_hash(a.parts_v4_3))
    result, calls = execute(value, parts_fixtures())
    assert result["promptVersion"] == "SCENARIO_MEANING_ASSESSMENT_V4_3"
    assert result["task"]["qualityPassed"] and len(calls) == 5
    assert calls[1][2] == a.response_schema(a.PartsQuestions)
    assert calls[2][2] == a.response_schema(a.QuestionReview)
    value, response = two_part_check()
    value = a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "promptHash": a.prompt_hash(a.parts_v4_3)})
    result, calls = execute(value, [response])
    assert result["task"]["meanings"][0]["status"] == "PARTIAL"
    assert len(calls) == 1


def test_v4_4_enforces_only_target_minimums_on_wire_without_changing_local_or_historical_schemas():
    assert a.prompt_hash(a.parts_candidate) == "09b222b0c140c0a3b07164639ff020cb272779319e6f79758eac55b058ce13bc"
    assert a.parts_candidate.INSTRUCTIONS["GRADE"] == a.parts_v4_1.INSTRUCTIONS["GRADE"]
    wire = a.response_schema(a.PartsQuestions, minimum_targets=True)
    bounded = []

    def walk(node, path=()):
        if isinstance(node, dict):
            if "minItems" in node or "maxItems" in node:
                bounded.append((path, node.get("minItems"), node.get("maxItems")))
            for key, child in node.items():
                walk(child, (*path, key))
        elif isinstance(node, list):
            for index, child in enumerate(node):
                walk(child, (*path, index))

    walk(wire)
    assert set(bounded) == {
        (("$defs", "PartsAssessment", "properties", "meanings"), 3, None),
        (("$defs", "PartsActivityDraft", "properties", "questions"), 3, None),
    }
    result, calls = execute(parts_payload(promptHash=a.prompt_hash(a.parts_candidate)), parts_fixtures())
    assert result["promptVersion"] == "SCENARIO_MEANING_ASSESSMENT_V4_4"
    assert calls[1][2] == wire
    assert calls[2][2] == a.response_schema(a.QuestionReview)
    bad = parts_fixtures()[1]
    bad["activities"][0]["assessment"]["meanings"].pop()
    with pytest.raises(ValueError):
        a.PartsQuestions.model_validate(bad)
    assert a.PartsAssessment.model_json_schema()["properties"]["meanings"]["maxItems"] == 6


def test_candidate_collects_localized_errors_into_one_scoped_repair_without_changing_old_releases():
    from app import scenario_assessment_harness as harness

    responses = parts_fixtures()
    bad = copy.deepcopy(responses[1])
    for index in (0, 2):
        bad["activities"][index]["assessment"]["meanings"][0]["requiredParts"][0]["evidence"][0]["quoteEn"] = "invented"
    dialogue = w.Dialogue.model_validate(responses[0])
    questions = a.PartsQuestions.model_validate(bad)
    value = parts_payload(promptHash=a.prompt_hash(a.parts_candidate))
    findings = harness.structural(questions, dialogue, value, "QUESTIONS")
    assert [(i["activityId"], i["itemId"]) for i in findings] == [("a1", "m1"), ("a3", "m1")]
    assert len(harness.structural(questions, dialogue, parts_payload(), "QUESTIONS")) == 1
    patch = dict(meaningReplacements=[dict(activityId=f"a{i+1}", meaning=responses[1]["activities"][i]["assessment"]["meanings"][0])
                                     for i in (0, 2)], questionReplacements=[])
    result, calls = execute(value, [responses[0], bad, patch, *responses[2:]])
    assert [c[0] for c in calls].count("scenario_repair_questions") == 1
    assert result["qualityHarness"]["extraCalls"] == 2
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert len(json.loads(calls[2][1][1]["content"])["allowedTargets"]) == 2
    assert bad["activities"][0]["assessment"]["meanings"][0]["requiredParts"][0]["evidence"][0]["quoteEn"] == "invented"


def test_v4_5_consistency_candidate_keeps_part_grading_wire_and_repair_contracts():
    selected = a.parts_consistency_candidate
    assert a.prompt_hash(selected) == "2307e4d21648e6150016e5647ec3b5fbcbb60a5aaa3d6e787bc0cd44a602d5e3"
    assert a.selected_release(a.prompt_hash(selected)) is selected
    assert selected.INSTRUCTIONS["GRADE"] == a.parts_v4_1.INSTRUCTIONS["GRADE"]
    assert selected.MINIMUM_TARGETS and selected.COLLECT_ITEM_ERRORS
    assert selected.HARNESS_POLICY == a.parts_candidate.HARNESS_POLICY
    result, calls = execute(parts_payload(promptHash=a.prompt_hash(selected)), parts_fixtures())
    assert result["task"]["qualityPassed"] and len(calls) == 5
    assert calls[1][2] == a.response_schema(a.PartsQuestions, minimum_targets=True)
    assert calls[2][2] == a.response_schema(a.QuestionReview)
    value, grade = two_part_check()
    value = a.ScenarioAssessmentInput.model_validate({**value.model_dump(), "promptHash": a.prompt_hash(selected)})
    result, _ = execute(value, [grade])
    assert result["task"]["meanings"][0]["status"] == "PARTIAL"


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
