"""Synthetic contract tests, not paid model-quality evidence."""
import asyncio
import copy
import json
from unittest.mock import patch

import pytest

from app import scenario_assessment as a
from app import scenario_assessment_harness as harness
from app import scenario_workflow as w
from app.config import Settings
from app.jobs import JobRequest, JobStore, capability_metadata
from app.worker import process
from test_scenario_workflow import learning_context, personal_payload, request


def fixtures(count=3):
    source = ["The shop opens at nine.", "The bread costs six pounds.", "Please collect it tomorrow.",
              "We accept cards.", "Bring your receipt.", "The order has no nuts."]
    activities, drafted, translations = [], [], []
    for ordinal in range(1, 4):
        activity = f"a{ordinal}"
        segments = [dict(id="s1", speakerId="customer", textEn=" ".join(source[:3])),
                    dict(id="s2", speakerId="baker", textEn=" ".join(source[3:]))]
        meanings, questions = [], []
        for index in range(count):
            evidence = [dict(segmentId="s1" if index < 3 else "s2", quoteEn=source[index])]
            support = dict(skill="detail", supportKind="direct", inferenceReasonEn="", evidence=evidence)
            meanings.append(dict(id=f"m{index+1}", meaningEn=source[index], **support))
            questions.append(dict(id=f"q{index+1}", factEn=source[index], speechAct="statement", **support,
                meaningIds=[f"m{index+1}"], promptVi=f"Thông tin số {index+1} trong đoạn này là gì?",
                answerVi=f"Ý tham chiếu {index+1}"))
        activities.append(dict(id=activity, segments=segments))
        drafted.append(dict(id=activity, assessment=dict(policyVersion=a.release.POLICY_VERSION, meanings=meanings), questions=questions))
        translations.extend(dict(activityId=activity, segmentId=s["id"], textVi=f"Bản dịch {activity}/{s['id']}") for s in segments)
    questions = dict(activities=drafted)
    review = dict(valid=True, issues=[], meaningChecks=[], questionChecks=[])
    for activity in drafted:
        for meaning in activity["assessment"]["meanings"]:
            review["meaningChecks"].append(dict(activityId=activity["id"], meaningId=meaning["id"], reasonVi="Có nguồn và hữu ích",
                **{k: True for k in ("meaningSupported", "importantMeaning", "distinctMeaning", "skillAppropriate", "groundingSupported")}))
        for question in activity["questions"]:
            review["questionChecks"].append(dict(activityId=activity["id"], questionId=question["id"], reasonVi="Có nguồn và rõ nghĩa",
                requestedMeaningEn=question["factEn"], answerUnitsVi=[question["answerVi"]],
                **{k: True for k in ("answerSupported", "distinctMeaning", "factSupported", "questionMatchesFact", "speechActSupported",
                    "noAnswerLeak", "vietnameseNatural", "skillAppropriate", "groundingSupported")}))
    translation_review = dict(valid=True, issues=[],
        translationChecks=[dict(activityId=t["activityId"], segmentId=t["segmentId"], reasonVi="Giữ nguyên nghĩa",
            meaningPreserved=True, terminologyCorrect=True, vietnameseOnly=True, vietnameseNatural=True) for t in translations],
        rubricChecks=[dict(category=k, passed=True, reasonVi="Đã kiểm tra")
            for k in ("SOURCE_ROLES", "DIALOGUE", "TRANSLATIONS", "PROFILE", "LEARNING_GOAL")])
    return [dict(activities=activities), questions, review, dict(segments=translations), translation_review]


def payload(phase="GENERATE", **changes):
    old = personal_payload(phase if phase != "GRADE" else "GENERATE").model_dump()
    old.pop("answers", None)
    old.update(phase=phase, contractVersion="scenario-job.v3", promptHash=a.prompt_hash())
    old["learningContext"]["contractVersion"] = "scenario-learning-context.v2"
    old["learningContext"]["profile"]["maxQuestionsPerSession"] = 18
    if phase == "GRADE":
        dialogue, questions, *_ = fixtures()
        context = a.AssessmentContext.model_validate(old["learningContext"])
        content = a.attach_questions(w.Dialogue.model_validate(dialogue), a.Questions.model_validate(questions), context)
        old.update(specification=None, activity=content.activities[0].model_dump(),
            assessmentSubmission=dict(mode="RECAP", recapText="The shop opens at nine.", answers=[], meaningIds=["m1", "m2", "m3"]))
    return a.ScenarioAssessmentInput.model_validate({**old, **changes})


def execute(value, responses, settings=None):
    calls = []
    settings = settings or Settings(scenario_enabled=True)

    class Gateway:
        def __init__(self, settings, client):
            self.last_model = a.metadata(settings)["model"]

        async def complete(self, messages, schema, deadline, purpose, **options):
            calls.append((purpose, messages, schema, options))
            response = responses[len(calls)-1]
            if isinstance(response, Exception):
                raise response
            return copy.deepcopy(response), settings.selected_provider

    with patch.object(a, "ModelGateway", Gateway):
        result = asyncio.run(w.execute(request(value), settings))
    return result, calls


def recap_grade():
    return dict(meanings=[dict(meaningId=f"m{i}", status="CAPTURED" if i == 1 else "UNASSESSED",
        evidence=[dict(responseId="recap", quote="opens at nine")] if i == 1 else []) for i in range(1, 4)])


def test_new_manifest_and_old_hashes_are_independent():
    assert w.prompt_hash() == "d43a3e228449dc64d7b6c103c2013558ac6e9415feabad8ff50e083900b0ae21"
    assert w.personalized_prompt_hash() == "df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303"
    assert w.personalized_prompt_hash(w.harness_release) == "a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b"
    assert w.personalized_prompt_hash(w.personalized) == "bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa"
    assert a.prompt_hash(a.legacy_release) == "ff50fd266eb277d973b8daed92dbf5b7ccb1beb17c28ad6c353fcaaf86c58630"
    assert a.prompt_hash(a.previous_release) == "4693e3c73b820a6eaea53311eaa5d8beaa7269cb9a71089fca6af98c112d31eb"
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"
    assert a.release.OUTPUT_TOKENS["QUESTIONS"] > w.OUTPUT_TOKENS["QUESTIONS"]
    with pytest.raises(ValueError):
        w.ScenarioInput.model_validate(payload().model_dump())
    bad = payload().model_dump()
    bad["learningContext"]["contractVersion"] = "scenario-learning-context.v1"
    with pytest.raises(ValueError):
        a.ScenarioAssessmentInput.model_validate(bad)


def test_job_envelope_uses_contract_discriminator_and_preserves_legacy_serialization():
    for value in (personal_payload(), payload()):
        admitted = request(value)
        assert type(admitted.payload) is type(value)
        assert JobRequest.model_validate_json(admitted.model_dump_json()).payload.model_dump() == value.model_dump()
        assert capability_metadata(value, Settings())["promptHash"] == value.promptHash


def test_wire_schema_avoids_gemini_nested_array_state_explosion_but_local_bounds_stay_strict():
    models = (w.PersonalizedExtraction, w.Dialogue, a.Questions, a.QuestionReview, w.Translations,
              w.PersonalizedReview, a.Grade, a.QuestionPatch, w.TranslationPatch)
    for model in models:
        wire = json.dumps(a.response_schema(model))
        assert '"minItems"' not in wire
        assert '"maxItems"' not in wire
        assert json.loads(wire)["additionalProperties"] is False
        assert '"minItems"' in json.dumps(model.model_json_schema()) or model is a.QuestionPatch
    assert a.Questions.model_json_schema()["properties"]["activities"]["minItems"] == 3
    assert a.Questions.model_json_schema()["$defs"]["Assessment"]["properties"]["meanings"]["maxItems"] == 6
    for count in (2, 7):
        response = fixtures()[1]
        if count == 2:
            response["activities"][0]["assessment"]["meanings"].pop()
        else:
            response["activities"][0]["assessment"]["meanings"] *= 3
        with pytest.raises(ValueError):
            a.Questions.model_validate(response)


def test_historical_v3_job_retains_exact_wire_schema_hash_and_metadata():
    old_hash = a.prompt_hash(a.legacy_release)
    value = payload(promptHash=old_hash)
    result, calls = execute(value, fixtures())
    assert result["promptHash"] == old_hash
    assert result["promptVersion"] == "SCENARIO_MEANING_ASSESSMENT_V1"
    assert calls[1][2] == a.response_schema(a.Questions, legacy=True)
    assert '"maxItems"' in json.dumps(calls[1][2])
    assert capability_metadata(value, Settings())["promptHash"] == old_hash
    new_result, new_calls = execute(payload(), fixtures())
    assert new_result["promptVersion"] == "SCENARIO_MEANING_ASSESSMENT_V3"
    assert '"maxItems"' not in json.dumps(new_calls[1][2])


@pytest.mark.parametrize("selected", [a.legacy_release, a.previous_release, a.release])
def test_each_admitted_release_keeps_its_exact_grader_prompt_and_metadata(selected):
    value = payload("GRADE", promptHash=a.prompt_hash(selected))
    result, calls = execute(value, [recap_grade()])
    assert result["promptHash"] == a.prompt_hash(selected)
    assert result["promptVersion"] == selected.PROMPT_VERSION
    assert calls[0][1][0]["content"] == selected.INSTRUCTIONS["GRADE"]
    assert capability_metadata(value, Settings())["promptVersion"] == selected.PROMPT_VERSION


def test_full_meaning_release_changes_only_the_observed_failure_stages():
    changed = {stage for stage in a.release.INSTRUCTIONS
               if a.release.INSTRUCTIONS[stage] != a.previous_release.INSTRUCTIONS[stage]}
    assert changed == {"QUESTIONS", "REVIEW_QUESTIONS", "REPAIR_QUESTIONS", "GRADE"}
    assert a.release.HARNESS_POLICY == a.previous_release.HARNESS_POLICY
    assert a.release.OUTPUT_TOKENS == a.previous_release.OUTPUT_TOKENS
    assert "questionMatchesFact=false" in a.release.INSTRUCTIONS["REVIEW_QUESTIONS"]
    assert "Multi-detail meanings and questions remain valid" in a.release.INSTRUCTIONS["QUESTIONS"]
    assert "Do not require exact wording or split every" in a.release.INSTRUCTIONS["GRADE"]


@pytest.mark.parametrize("recap,status", [
    ("Cửa hàng mở lúc chín giờ.", "PARTIAL"),
    ("Cửa hàng mở lúc chín giờ vì hàng đến muộn.", "CAPTURED"),
    ("Họ nói về cửa hàng.", "UNASSESSED"),
])
def test_reason_coverage_regression_fixture_reaches_new_grader_with_verbatim_evidence(recap, status):
    # This verifies prompt/evidence plumbing; live model quality is a separate focused probe.
    value = payload("GRADE")
    value.activity.assessment.meanings[0].meaningEn = "The shop opens at nine because the stock arrives late."
    value.assessmentSubmission.recapText = recap
    grade = recap_grade()
    grade["meanings"][0] = dict(meaningId="m1", status=status,
        evidence=[] if status == "UNASSESSED" else [dict(responseId="recap", quote=recap)])
    result, calls = execute(value, [grade])
    assert result["task"] == grade
    data = json.loads(calls[0][1][1]["content"])
    assert data["activity"]["assessment"]["meanings"][0]["meaningEn"] == value.activity.assessment.meanings[0].meaningEn
    assert "essential reason is PARTIAL" in calls[0][1][0]["content"]


@pytest.mark.parametrize("count", [3, 4, 6])
def test_complete_pipeline_uses_five_stages_variable_bank_and_frozen_source(count):
    responses = fixtures(count)
    result, calls = execute(payload(), responses)
    assert [c["purpose"] for c in result["calls"]] == ["GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW"]
    assert result["task"]["qualityPassed"]
    assert all(len(act["questions"]) == count for act in result["task"]["activities"])
    assert all(len(act["assessment"]["meanings"]) == count for act in result["task"]["activities"])
    assert result["qualityHarness"]["extraCalls"] == 0
    assert result["qualityHarness"]["policyHash"] == "56d0d6d5e826e1b46f20b5ab4a3868dada9537cca996cb35999d3886ee3d6fe2"
    assert json.loads(calls[1][1][1]["content"])["dialogue"] == responses[0]
    assert all("factEn" not in q for act in result["task"]["activities"] for q in act["questions"])


@pytest.mark.parametrize("fault", ["meaning_id", "question_id", "foreign_meaning", "duplicate_ref", "uncovered", "meaning_quote", "question_quote",
                                   "inference", "duplicate_meaning", "duplicate_prompt", "answer_leak", "missing_focus", "cap"])
def test_private_content_rejects_forged_references_grounding_and_profile(fault):
    dialogue, drafted, *_ = fixtures()
    context = payload().learningContext.model_copy(deep=True)
    act = drafted["activities"][0]
    if fault == "meaning_id": act["assessment"]["meanings"][1]["id"] = "m1"
    elif fault == "question_id": act["questions"][1]["id"] = "q1"
    elif fault == "foreign_meaning": act["questions"][0]["meaningIds"] = ["m6"]
    elif fault == "duplicate_ref": act["questions"][0]["meaningIds"] *= 2
    elif fault == "uncovered": act["questions"][2]["meaningIds"] = ["m1"]; act["questions"][2]["evidence"] = act["questions"][0]["evidence"]
    elif fault == "meaning_quote": act["assessment"]["meanings"][0]["evidence"][0]["quoteEn"] = "Invented source"
    elif fault == "question_quote": act["questions"][0]["evidence"][0]["segmentId"] = "s2"
    elif fault == "inference": act["assessment"]["meanings"][0]["supportKind"] = "inference"
    elif fault == "duplicate_meaning": act["assessment"]["meanings"][1]["meaningEn"] = act["assessment"]["meanings"][0]["meaningEn"]
    elif fault == "duplicate_prompt": act["questions"][1]["promptVi"] = act["questions"][0]["promptVi"]
    elif fault == "answer_leak": act["questions"][0]["promptVi"] += act["questions"][0]["answerVi"]
    elif fault == "missing_focus": context.focusSkill = "main-idea"
    else: context.profile.maxQuestionsPerSession = 8
    with pytest.raises(ValueError):
        a.attach_questions(w.Dialogue.model_validate(dialogue), a.Questions.model_validate(drafted), context)


def test_word_counts_remain_guidance_with_all_other_dialogue_gates_active():
    value = payload()
    responses = fixtures()
    value.profile.minWordsPerActivity = 140
    value.learningContext.profile.minWordsPerActivity = 140
    result, _ = execute(value, responses)
    assert result["task"]["qualityPassed"]
    responses[0]["activities"][0]["segments"][1]["speakerId"] = "customer"
    with pytest.raises(w.QualityRejected, match="invalid dialogue"):
        execute(value, responses[:1])


def test_question_may_include_longer_exact_quote_covering_its_meaning_source():
    dialogue, drafted, *_ = fixtures()
    drafted["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = dialogue["activities"][0]["segments"][0]["textEn"]
    a.attach_questions(w.Dialogue.model_validate(dialogue), a.Questions.model_validate(drafted), payload().learningContext)


def test_recap_omission_is_unassessed_and_grade_uses_one_provider_call():
    result, calls = execute(payload("GRADE"), [recap_grade()])
    assert result["task"] == recap_grade()
    assert len(calls) == 1
    data = json.loads(calls[0][1][1]["content"])
    assert data["assessmentSubmission"]["mode"] == "RECAP"
    assert "answers" not in data


def test_recap_grade_has_a_hard_six_meaning_boundary():
    rows = [dict(meaningId=f"m{i}", status="UNASSESSED", evidence=[]) for i in range(1, 7)]
    assert len(a.Grade.model_validate(dict(meanings=rows)).meanings) == 6
    with pytest.raises(ValueError):
        a.Grade.model_validate(dict(meanings=rows + [rows[0]]))


@pytest.mark.parametrize("fault", ["missing", "duplicate", "foreign", "invented", "source_quote", "wrong_response", "empty_evidence", "duplicate_evidence", "missed_recap", "evidenced_unassessed"])
def test_grade_rejects_invented_learner_evidence_or_wrong_coverage(fault):
    value, grade = payload("GRADE"), recap_grade()
    if fault == "missing": grade["meanings"].pop()
    elif fault == "duplicate": grade["meanings"][1] = copy.deepcopy(grade["meanings"][0])
    elif fault == "foreign": grade["meanings"][0]["meaningId"] = "m6"
    elif fault == "invented": grade["meanings"][0]["evidence"][0]["quote"] = "I never said this"
    elif fault == "source_quote": grade["meanings"][0]["evidence"][0]["quote"] = "The bread costs six pounds."
    elif fault == "wrong_response": grade["meanings"][0]["evidence"][0]["responseId"] = "q1"
    elif fault == "empty_evidence": grade["meanings"][0]["evidence"] = []
    elif fault == "duplicate_evidence": grade["meanings"][0]["evidence"] *= 2
    elif fault == "missed_recap": grade["meanings"][1]["status"] = "MISSED"
    else: grade["meanings"][1]["evidence"] = grade["meanings"][0]["evidence"]
    with pytest.raises(w.QualityRejected, match="invalid grade evidence"):
        execute(value, [grade])


@pytest.mark.parametrize("answer", ["Mở cửa lúc chín giờ", "It opens at nine", "Mở cửa at nine"])
def test_checks_accept_languages_and_partial_multimeaning_evidence_without_duplicates(answer):
    value = payload("GRADE")
    activity = value.activity.model_dump()
    activity["questions"][0]["meaningIds"] = ["m1", "m2"]
    submission = dict(mode="CHECKS", recapText="", answers=[dict(questionId="q1", textVi=answer)], meaningIds=["m1", "m2"])
    value = payload("GRADE", activity=activity, assessmentSubmission=submission)
    grade = dict(meanings=[dict(meaningId="m1", status="CAPTURED", evidence=[dict(responseId="q1", quote=answer)]),
                          dict(meaningId="m2", status="PARTIAL", evidence=[dict(responseId="q1", quote=answer)])])
    result, _ = execute(value, [grade])
    assert result["task"] == grade


def test_check_cannot_use_unrelated_answer_quote_or_unassessed_status():
    submission = dict(mode="CHECKS", recapText="", answers=[dict(questionId="q1", textVi="nine"), dict(questionId="q2", textVi="six pounds")], meaningIds=["m1", "m2"])
    value = payload("GRADE", assessmentSubmission=submission)
    grade = dict(meanings=[dict(meaningId="m1", status="CAPTURED", evidence=[dict(responseId="q2", quote="six pounds")]),
                          dict(meaningId="m2", status="MISSED", evidence=[])])
    with pytest.raises(w.QualityRejected):
        execute(value, [grade])
    grade["meanings"][0].update(status="UNASSESSED", evidence=[])
    with pytest.raises(w.QualityRejected):
        execute(value, [grade])


@pytest.mark.parametrize("fault", ["wrong_pin", "skip", "missing_recap", "recap_answers", "recap_subset", "checks_recap", "checks_foreign", "checks_duplicate", "checks_coverage", "extra"])
def test_admission_rejects_invalid_modes_or_client_selected_private_ids(fault):
    data = payload("GRADE").model_dump()
    sub = data["assessmentSubmission"]
    if fault == "wrong_pin": data["promptHash"] = w.personalized_prompt_hash()
    elif fault == "skip": sub["mode"] = "SKIP_RECAP"
    elif fault == "missing_recap": sub["recapText"] = " "
    elif fault == "recap_answers": sub["answers"] = [dict(questionId="q1", textVi="nine")]
    elif fault == "recap_subset": sub["meaningIds"] = ["m1"]
    elif fault == "extra": data["answers"] = []
    else:
        sub.update(mode="CHECKS", recapText="", answers=[dict(questionId="q1", textVi="nine")], meaningIds=["m1"])
        if fault == "checks_recap": sub["recapText"] = "nine"
        elif fault == "checks_foreign": sub["answers"][0]["questionId"] = "q6"
        elif fault == "checks_duplicate": sub["answers"] *= 2
        elif fault == "checks_coverage": sub["meaningIds"] = ["m2"]
    with pytest.raises(ValueError):
        a.ScenarioAssessmentInput.model_validate(data)


def test_advisory_reviewer_failure_is_reported_without_forcing_approval():
    responses = fixtures()
    responses[2].update(valid=False, issues=["Global concern, not localizable"])
    result, calls = execute(payload(), responses)
    assert not result["task"]["qualityPassed"]
    assert result["reviewAdvisory"]["questionsStatus"] == "FAIL"
    assert len(calls) == 5
    assert result["qualityHarness"]["attempts"] == []


def test_unknown_reviewer_has_one_format_retry_and_remains_advisory():
    responses = fixtures()
    responses[2:3] = [{}, {}]
    result, calls = execute(payload(), responses)
    assert len(calls) == 6
    assert result["reviewAdvisory"]["questionsStatus"] == "UNKNOWN"
    assert result["qualityHarness"]["extraCalls"] == 1
    assert not result["task"]["qualityPassed"]


def test_scoped_structural_repair_keeps_dialogue_and_untargeted_items_frozen():
    base = fixtures()
    bad = copy.deepcopy(base[1])
    bad["activities"][0]["questions"][0]["meaningIds"] = ["m6"]
    patch = dict(meaningReplacements=[], questionReplacements=[dict(activityId="a1", question=base[1]["activities"][0]["questions"][0])])
    responses = [base[0], bad, patch, base[2], base[3], base[4]]
    result, calls = execute(payload(), responses)
    assert [c["purpose"] for c in result["calls"]] == ["GENERATE", "QUESTIONS", "REPAIR_QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW"]
    assert result["qualityHarness"]["extraCalls"] == 2
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    repair_input = json.loads(calls[2][1][1]["content"])
    assert repair_input["dialogue"] == base[0]
    assert repair_input["allowedTargets"] == [dict(itemType="question", activityId="a1", itemId="q1", questionId="q1")]


def test_patch_cannot_change_untargeted_meaning_or_ids():
    questions = a.Questions.model_validate(fixtures()[1])
    patch_data = dict(meaningReplacements=[dict(activityId="a1", meaning=questions.activities[0].assessment.meanings[0].model_dump())], questionReplacements=[])
    with pytest.raises(ValueError, match="targets mismatch"):
        harness.merge_patch(questions, a.QuestionPatch.model_validate(patch_data), {("question", "a1", "q1")}, "QUESTIONS")


def test_reconcile_retains_failed_unchanged_meanings_and_rejects_changed_target_regression():
    previous = a.QuestionReview.model_validate(fixtures()[2])
    previous.meaningChecks[0].importantMeaning = False
    previous.valid = False
    current = a.QuestionReview.model_validate(fixtures()[2])
    reconciled = harness.reconcile(previous, current, {("question", "a1", "q1")}, "QUESTIONS")
    assert not reconciled.meaningChecks[0].importantMeaning
    assert not reconciled.valid
    current.questionChecks[0].answerSupported = False
    with pytest.raises(ValueError, match="regressed"):
        harness.reconcile(previous, current, {("question", "a1", "q1")}, "QUESTIONS")


def test_provider_unknown_never_replays_a_completed_generation_stage(tmp_path):
    value = request(payload())
    settings = Settings(scenario_enabled=True)
    store = JobStore(tmp_path / "jobs.db")
    store.register(value, settings)
    calls = []

    def unavailable(event):
        calls.append(event.jobId)
        raise TimeoutError("uncertain after provider submission")

    body = store.pending()[0]
    process(body, settings, store, runner=unavailable)
    process(body, settings, store, runner=unavailable)
    assert len(calls) == 1


def test_extraction_uses_v3_context_and_existing_provenance_validation():
    from test_scenario_workflow import extraction_response
    response = {**extraction_response(), "learnerMode": "listener", "learnerCharacterId": None, "genre": "information-exchange"}
    value = payload("EXTRACT")
    result, calls = execute(value, [response])
    assert result["task"]["learnerRoleVi"] == "Người nghe"
    assert json.loads(calls[0][1][1]["content"])["learningContext"]["contractVersion"] == "scenario-learning-context.v2"
