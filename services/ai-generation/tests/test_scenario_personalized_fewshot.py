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


@pytest.mark.parametrize("release", [w.personalized, fewshot, corrected])
def test_every_actual_stage_message_uses_job_pin_including_grade(release):
    pin = w.personalized_prompt_hash(release)
    extracted = {**extraction_response(), "learnerMode": "listener", "learnerCharacterId": None, "genre": "discussion"}
    for phase, responses in (("EXTRACT", [extracted]), ("GENERATE", personal_responses()), ("GRADE", [EXAMPLE["grade"]])):
        payload = personal_payload(phase, promptHash=pin)
        result, calls = execute(payload, responses)
        assert result["promptHash"] == pin and result["promptVersion"] == release.PROMPT_VERSION
        assert capability_metadata(payload, Settings())["promptVersion"] == release.PROMPT_VERSION
        for purpose, messages in calls:
            stage = purpose.removeprefix("scenario_").upper()
            expected = w.V4_INSTRUCTIONS["GRADE"] if stage == "GRADE" else release.INSTRUCTIONS[stage]
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
    assert w.example_metadata(pin, "GENERATE")["exampleSetHash"] == fewshot.EXAMPLE_SET_HASH
    assert w.example_metadata(BASELINE, "GENERATE") == w.example_metadata(pin, "GRADE") == {
        "promptMode": "zero-shot", "exampleIdsByStage": {}}


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
    assert w.personalized_prompt_hash() == w.personalized_prompt_hash(corrected)


@pytest.mark.parametrize("field", [name for name, field in w.PersonalizedQuestionCheck.model_fields.items() if field.annotation is bool])
@pytest.mark.parametrize("fault", ["missing", "false"])
def test_personalized_question_review_requires_all_ten_checks(field, fault):
    responses = personal_responses()
    if fault == "missing":
        responses[2]["questionChecks"][0].pop(field)
    else:
        responses[2]["questionChecks"][0][field] = False
    with pytest.raises(w.QualityRejected):
        execute(personal_payload(), responses)


@pytest.mark.parametrize("category", ["SOURCE_ROLES", "DIALOGUE", "TRANSLATIONS", "PROFILE", "LEARNING_GOAL"])
@pytest.mark.parametrize("fault", ["missing", "false", "duplicate"])
def test_personalized_review_requires_each_of_five_rubrics(category, fault):
    responses = personal_responses()
    checks = responses[4]["rubricChecks"]
    selected = next(check for check in checks if check["category"] == category)
    if fault == "missing":
        checks.remove(selected)
    elif fault == "false":
        selected["passed"] = False
    else:
        selected["category"] = next(check["category"] for check in checks if check["category"] != category)
    with pytest.raises(w.QualityRejected):
        execute(personal_payload(), responses)
