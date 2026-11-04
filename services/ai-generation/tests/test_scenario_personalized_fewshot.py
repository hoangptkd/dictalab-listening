"""Offline contracts for reference pairs and actual workflow messages; no model calls."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_prompts_personalized_fewshot as fewshot
from app import scenario_prompts_personalized_fewshot_v2 as corrected
from app import scenario_workflow as w
from app.config import Settings
from app.jobs import capability_metadata, execution_fingerprint, digest
from test_scenario_workflow import execute, personal_payload, personal_responses, extraction_response, EXAMPLE

ROOT = Path(__file__).resolve().parents[3]
BASELINE = "bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa"
MODELS = dict(EXTRACT=w.PersonalizedExtraction, GENERATE=w.Dialogue, QUESTIONS=w.PersonalizedQuestions,
              REVIEW_QUESTIONS=w.PersonalizedQuestionReview, TRANSLATE=w.Translations, REVIEW=w.PersonalizedReview)


@pytest.mark.parametrize("example", fewshot.EXAMPLES, ids=lambda e: e["id"])
def test_reference_pair_matches_current_context_schema_and_gate(example):
    data, expected, stage = example["input"], example["expectedOutput"], example["stage"]
    assert example["metadata"]["labelSource"] == "agent-authored-reference-not-human-gold"
    context = w.LearningContext.model_validate(data["learningContext"])
    result = MODELS[stage].model_validate(expected)
    if "profile" in data:
        assert data["profile"] == {k: context.profile.model_dump()[k] for k in data["profile"]}
    if stage == "EXTRACT":
        assert not any(a.startswith("Giả định:") for a in result.assumptionsVi)
        w.validate_extraction(result, data["inputVi"])
    elif stage == "GENERATE":
        w.validate_dialogue(result, w.PersonalizedSpecification.model_validate(data["specification"]),
                            w.PersonalizedProfile.model_validate(data["profile"]))
        target = min((context.profile.minWordsPerActivity + context.profile.maxWordsPerActivity) // 2,
                     context.profile.maxWordsPerSession // 3)
        assert data["writingBudget"]["targetWordsPerActivity"] == target
        assert all(abs(sum(len(s.textEn.split()) for s in a.segments) - target) <= target * .25 for a in result.activities)
    elif stage == "QUESTIONS":
        w.attach_questions(w.Dialogue.model_validate(data["dialogue"]), result, context)
    elif stage == "REVIEW_QUESTIONS":
        content = w.PersonalizedContent.model_validate(data["content"])
        questions = w.PersonalizedQuestions.model_validate(data["groundedQuestions"])
        assert content == w.attach_questions(w.Dialogue(activities=[
            w.DialogueActivity(id=a.id, segments=a.segments) for a in content.activities]), questions, context)
        if result.valid:
            w.validate_question_review(result, content)
        else:
            with pytest.raises(ValueError, match="question quality rejected"):
                w.validate_question_review(result, content)
    elif stage == "TRANSLATE":
        dialogue = w.Dialogue.model_validate({"activities": data["activities"]})
        assert {(a.id, s.id) for a in dialogue.activities for s in a.segments} == {
            (s.activityId, s.segmentId) for s in result.segments}
        assert len(result.segments) == sum(len(a.segments) for a in dialogue.activities)
    else:
        w.PersonalizedSpecification.model_validate(data["specification"])
        w.validate_dialogue(w.Dialogue.model_validate(data["dialogue"]),
                            w.PersonalizedSpecification.model_validate(data["specification"]),
                            w.PersonalizedProfile.model_validate(data["profile"]))
        translations = w.Translations.model_validate(data["translations"])
        if result.valid:
            w.validate_review(result, translations)
        else:
            with pytest.raises(ValueError, match="semantic quality rejected"):
                w.validate_review(result, translations)


def test_examples_are_stage_local_and_baseline_is_unchanged():
    assert w.personalized_prompt_hash(w.personalized) == BASELINE
    assert w.personalized_prompt_hash(fewshot) == "54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1"
    assert w.prompt_hash() == "d43a3e228449dc64d7b6c103c2013558ac6e9415feabad8ff50e083900b0ae21"
    assert len(fewshot.EXAMPLES) == len({e["id"] for e in fewshot.EXAMPLES}) == 13
    evaluation_briefs = set()
    for path in (ROOT / "services/ai-generation/tests/fixtures/evaluation").glob("scenario-*.json"):
        for case in json.loads(path.read_text(encoding="utf-8"))["cases"]:
            evaluation_briefs.add(case.get("inputVi", case.get("input", {}).get("inputVi")))
    assert all(e["input"]["inputVi"] not in evaluation_briefs for e in fewshot.EXAMPLES if "inputVi" in e["input"])
    for stage in fewshot.STAGES:
        before, after = w.personalized.INSTRUCTIONS[stage], fewshot.INSTRUCTIONS[stage]
        prefix, suffix = before.split(fewshot.MARKER)
        assert after.startswith(prefix) and after.endswith(suffix)
        assert fewshot.MARKER not in after
        assert after.count("Example input JSON:") == len(fewshot.EXAMPLE_IDS[stage])
        assert len(after.encode()) < 32000  # Review schemas repeat content; keep growth bounded.
        for e in (e for e in fewshot.EXAMPLES if e["stage"] == stage):
            assert json.dumps(e["expectedOutput"], ensure_ascii=False, separators=(",", ":")) in after


@pytest.mark.parametrize("fault", ["empty", "duplicate", "missing_stage", "grade"])
def test_bad_reference_asset_cannot_silently_fall_back_to_zero_shot(fault):
    examples = copy.deepcopy(fewshot.EXAMPLES)
    if fault == "empty": examples = []
    elif fault == "duplicate": examples.append(examples[0])
    elif fault == "missing_stage": examples = [e for e in examples if e["stage"] != "TRANSLATE"]
    else: examples[0]["stage"] = "GRADE"
    with pytest.raises(ValueError):
        fewshot.compile_instructions(examples)


def test_missing_prompt_marker_fails_closed(monkeypatch):
    monkeypatch.setitem(w.personalized.INSTRUCTIONS, "GENERATE", "no marker")
    with pytest.raises(ValueError, match="examples marker"):
        fewshot.compile_instructions(fewshot.EXAMPLES)


@pytest.mark.parametrize("release", [w.personalized, fewshot, corrected, w.previous_personalized_fewshot_v3,
                                    w.previous_personalized_fewshot_v4, w.previous_personalized_fewshot_v5,
                                    w.previous_personalized_fewshot_v6, w.personalized_fewshot, w.harness_release])
def test_every_actual_stage_message_uses_job_pin_including_grade(release):
    pin = w.personalized_prompt_hash(release)
    extracted = {**extraction_response(), "learnerMode": "listener", "learnerCharacterId": None, "genre": "discussion"}
    for phase, responses in (("EXTRACT", [extracted]), ("GENERATE", personal_responses(release)), ("GRADE", [EXAMPLE["grade"]])):
        payload = personal_payload(phase, promptHash=pin)
        result, calls = execute(payload, responses)
        assert result["promptHash"] == pin and result["promptVersion"] == release.PROMPT_VERSION
        assert capability_metadata(payload, Settings())["promptVersion"] == release.PROMPT_VERSION
        if phase == "GENERATE":
            assert result["task"]["qualityPassed"] is True
        for purpose, messages in calls:
            stage = purpose.removeprefix("scenario_").upper()
            expected = getattr(release, "GRADE", w.V4_INSTRUCTIONS["GRADE"]) if stage == "GRADE" else release.INSTRUCTIONS[stage]
            assert messages[0]["content"] == expected
            if stage == "GRADE":
                assert "Example input JSON:" not in messages[0]["content"]


def test_old_job_execution_fingerprint_is_not_relabelled_and_unknown_pin_fails():
    payload = personal_payload(promptHash=BASELINE)
    settings = Settings()
    expected_metadata = {**w.metadata(settings, True, BASELINE), "promptVersion": "SCENARIO_PERSONALIZED_V1_GRADE_V4", "promptHash": BASELINE}
    assert capability_metadata(payload, settings) == expected_metadata
    # Frozen pre-integration fingerprint formula, without few-shot trace-only metadata.
    assert execution_fingerprint(payload, settings) == digest({"payload": payload.model_dump(), "metadata": expected_metadata,
        "pool": [{k: v for k, v in p.model_dump().items() if k != "api_key"}
                 for p in settings.sources_for(settings.selected_provider)] if settings.gemini_quota_enabled else [],
        "config": {name: getattr(settings, name) for name in ("num_ctx", "num_predict", "ollama_url", "gemini_base_url",
            "gemini_model", "cartesia_url", "cartesia_voice", "cartesia_version", "model_serving_url",
            "demo_daily_budget_microusd", "demo_call_reservation_microusd", "demo_daily_cloud_calls")}})
    with pytest.raises(ValueError, match="scenario prompt mismatch"):
        personal_payload(promptHash="0" * 64)


def test_trace_metadata_lists_only_examples_for_executed_phase():
    pin = w.personalized_prompt_hash()
    assert set(w.example_metadata(pin, "EXTRACT")["exampleIdsByStage"]) == {"EXTRACT"}
    assert set(w.example_metadata(pin, "GENERATE")["exampleIdsByStage"]) == set(fewshot.STAGES) - {"EXTRACT"}
    assert w.example_metadata(pin, "GENERATE")["exampleSetHash"] == w.personalized_fewshot.EXAMPLE_SET_HASH
    assert w.example_metadata(BASELINE, "GENERATE") == w.example_metadata(pin, "GRADE") == {
        "promptMode": "zero-shot", "exampleIdsByStage": {}}


@pytest.mark.parametrize("example", w.personalized_fewshot.EXAMPLES, ids=lambda e: e['id'])
def test_compact_references_keep_complete_outputs_and_valid_labels(example):
    data, stage = example['input'], example['stage']
    reference = next(e for e in fewshot.EXAMPLES if e['id'] == example['metadata']['contextSourceId'])
    context_data = copy.deepcopy(reference['input']['learningContext'])
    if 'learningContext' in data:
        projected = data['learningContext']
        context_data['focusSkill'] = projected['focusSkill']
        assert set(projected) <= {'programCode', 'target', 'focusSkill', 'profile'}
        assert projected['programCode'] == context_data['programCode']
        assert projected['target'] == context_data['target']
        assert all(context_data['profile'][k] == value for k, value in projected.get('profile', {}).items())
    context = w.LearningContext.model_validate(context_data)
    model = w.personalized_question_review_model(w.personalized_fewshot) if stage == 'REVIEW_QUESTIONS' else MODELS[stage]
    result = model.model_validate(example['expectedOutput'])
    if stage == 'EXTRACT':
        w.validate_extraction(result, data['inputVi'])
    elif stage == 'GENERATE':
        w.validate_dialogue(result, w.PersonalizedSpecification.model_validate(data['specification']),
                           w.PersonalizedProfile.model_validate(data['profile']), flexible=True)
    elif stage == 'QUESTIONS':
        w.attach_questions(w.Dialogue.model_validate(data['dialogue']), result, context)
    elif stage == 'REVIEW_QUESTIONS':
        questions = w.PersonalizedQuestions.model_validate(data['groundedQuestions'])
        dialogue = w.Dialogue.model_validate(data['content'])
        if result.valid:
            content = w.attach_questions(dialogue, questions, context)
            w.validate_question_review(result, content, flexible=True)
        else:
            content = w.PersonalizedContent.model_validate({'activities': [
                {**a, 'questions': [{**{k: v for k, v in q.items() if k not in ('factEn', 'speechAct')},
                                     'type': 'MEANING_RECALL_VI'}
                                    for q in draft['questions']]}
                for a, draft in zip(data['content']['activities'], data['groundedQuestions']['activities'])]})
            with pytest.raises(ValueError):
                w.validate_question_review(result, content, flexible=True)
    elif stage == 'TRANSLATE':
        assert {(a['id'], s['id']) for a in data['activities'] for s in a['segments']} == {
            (s.activityId, s.segmentId) for s in result.segments}
    else:
        translations = w.Translations.model_validate(data['translations'])
        if result.valid:
            w.validate_review(result, translations)
        else:
            with pytest.raises(ValueError):
                w.validate_review(result, translations)


def test_compact_review_covers_real_faults_without_reviving_one_detail_rule():
    examples = [e for e in w.personalized_fewshot.EXAMPLES if e['stage'] == 'REVIEW_QUESTIONS']
    accepted, rejected = sorted(examples, key=lambda e: not e['expectedOutput']['valid'])
    assert any(len(c['answerUnitsVi']) == 2
               for c in accepted['expectedOutput']['questionChecks'])
    data = rejected['input']
    quote = data['groundedQuestions']['activities'][0]['questions'][0]['evidence'][0]['quoteEn']
    assert not any(quote in s['textEn'] for s in data['content']['activities'][0]['segments'])
    assert any(quote in s['textEn'] for s in data['content']['activities'][1]['segments'])
    checks = rejected['expectedOutput']['questionChecks']
    assert checks[0]['answerSupported'] and not checks[0]['groundingSupported']
    assert checks[2]['requestedMeaningEn'] == checks[3]['requestedMeaningEn']
    assert not checks[2]['distinctMeaning'] and not checks[3]['distinctMeaning']
    assert not checks[4]['speechActSupported'] and not checks[4]['answerSupported']
    before = sum(len(w.previous_personalized_fewshot_v5.INSTRUCTIONS[s].encode()) for s in fewshot.STAGES)
    after = sum(len(w.personalized_fewshot.INSTRUCTIONS[s].encode()) for s in fewshot.STAGES)
    assert after < before * .8
    assert w.personalized_prompt_hash(w.previous_personalized_fewshot_v5) == '358201f08eb87c7d4d2306220c3828a92cda57ac93d57faed80a508d892fa1c1'


def test_current_review_schema_removes_atomic_flag_without_changing_historical_jobs():
    model = w.personalized_question_review_model(w.personalized_fewshot)
    assert 'atomicMeaning' not in json.dumps(w.response_schema(model))
    assert all('atomicMeaning' not in p for p in w.personalized_fewshot.INSTRUCTIONS.values())
    assert len([f for f in w.QuestionEvidenceCheck.model_fields.values() if f.annotation is bool]) == 9
    response = personal_responses()[2]
    model.model_validate(response)
    response['questionChecks'][0]['atomicMeaning'] = True
    with pytest.raises(ValueError):
        model.model_validate(response)
    assert w.personalized_question_review_model(w.previous_personalized_fewshot_v6) is w.PersonalizedQuestionReview
    assert w.personalized_prompt_hash(w.previous_personalized_fewshot_v6) == 'c1dcc2e3cc29847ba09b6d53a447a45e8bfe48ae13e9dfb4e2e131173f1e6a18'
    assert w.personalized_fewshot.GRADE == w.previous_personalized_fewshot_v6.GRADE


def test_corrected_output_contracts_match_personalized_schemas_and_examples():
    question_contract = corrected.INSTRUCTIONS["REVIEW_QUESTIONS"].split("OUTPUT CONTRACT\n")[1].split("\n\nEXAMPLES")[0]
    checks = {name for name, field in w.PersonalizedQuestionCheck.model_fields.items() if field.annotation is bool}
    assert len(checks) == 10
    assert "all ten boolean checks (" + ",".join(
        name for name in w.PersonalizedQuestionCheck.model_fields if name in checks) + ")" in question_contract
    assert "all eight boolean checks" not in corrected.INSTRUCTIONS["REVIEW_QUESTIONS"]
    review_contract = corrected.INSTRUCTIONS["REVIEW"].split("OUTPUT CONTRACT\n")[1].split("\n\nEXAMPLES")[0]
    rubric_schema = w.PersonalizedReview.model_json_schema()
    categories = rubric_schema["$defs"]["PersonalizedRubricCheck"]["properties"]["category"]["enum"]
    assert len(categories) == 5
    assert "exactly five rubricChecks, one each for " + ",".join(categories) in review_contract
    assert rubric_schema["properties"]["rubricChecks"]["minItems"] == rubric_schema["properties"]["rubricChecks"]["maxItems"] == 5
    assert "exactly SOURCE_ROLES,DIALOGUE,TRANSLATIONS in rubricChecks" not in corrected.INSTRUCTIONS["REVIEW"]
    for example in corrected.EXAMPLES:
        output = example["expectedOutput"]
        if example["stage"] == "REVIEW_QUESTIONS":
            for check in output["questionChecks"]:
                assert {name for name, value in check.items() if type(value) is bool} == checks
        elif example["stage"] == "REVIEW":
            assert len(output["rubricChecks"]) == len(categories)
            assert {check["category"] for check in output["rubricChecks"]} == set(categories)
    assert {stage for stage in corrected.STAGES if corrected.INSTRUCTIONS[stage] != fewshot.INSTRUCTIONS[stage]} == {"REVIEW_QUESTIONS", "REVIEW"}
    assert corrected.EXAMPLE_SET_HASH == fewshot.EXAMPLE_SET_HASH
    assert w.personalized_release(w.personalized_prompt_hash(corrected)) is corrected


@pytest.mark.parametrize("field", [name for name, field in w.PersonalizedQuestionCheck.model_fields.items() if field.annotation is bool])
@pytest.mark.parametrize("fault", ["missing", "false"])
def test_personalized_question_review_requires_all_ten_checks(field, fault):
    responses = personal_responses(w.previous_personalized_fewshot_v3)
    if fault == "missing":
        responses[2]["questionChecks"][0].pop(field)
    else:
        responses[2]["questionChecks"][0][field] = False
    with pytest.raises(w.QualityRejected):
        execute(personal_payload(promptHash=w.personalized_prompt_hash(w.previous_personalized_fewshot_v3)), responses)


@pytest.mark.parametrize("category", ["SOURCE_ROLES", "DIALOGUE", "TRANSLATIONS", "PROFILE", "LEARNING_GOAL"])
@pytest.mark.parametrize("fault", ["missing", "false", "duplicate"])
def test_personalized_review_requires_each_of_five_rubrics(category, fault):
    responses = personal_responses(w.previous_personalized_fewshot_v3)
    checks = responses[4]["rubricChecks"]
    selected = next(check for check in checks if check["category"] == category)
    if fault == "missing":
        checks.remove(selected)
    elif fault == "false":
        selected["passed"] = False
    else:
        selected["category"] = next(check["category"] for check in checks if check["category"] != category)
    with pytest.raises(w.QualityRejected):
        execute(personal_payload(promptHash=w.personalized_prompt_hash(w.previous_personalized_fewshot_v3)), responses)
