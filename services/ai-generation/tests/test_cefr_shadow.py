"""Shadow isolation, evidence grounding, frozen profiles and bounded workflow; no model calls."""
import copy
import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.prompt_versions import selected_artifact, generate_content
from app.registry import load_artifact, RegistryError
from app.schemas import ConversationTaskRequest, ShadowConversationTaskRequest
from app.shadow import ARTIFACT_VERSION, shadow_request, run_shadow_workflow
from app.validation import validate_task

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def artifact():
    return load_artifact(Settings(artifact_version=ARTIFACT_VERSION))


def merged(example):
    task = copy.deepcopy(example["output"])
    task["translationVi"] = "Bản dịch tổng hợp chỉ dùng trong test kỹ thuật."
    for index, fact in enumerate(task["facts"]):
        fact["textVi"] = "Kết luận kiểm tra" if index == 0 else "Tài liệu cần gửi"
        fact.pop("textEn")
    return task


def test_public_contracts_stay_closed_and_c2_cannot_generate(artifact):
    for level in ("B2", "C1", "C2"):
        with pytest.raises(ValidationError):
            ConversationTaskRequest(requestId="test", idempotencyKey="test", level=level, turnNumber=2,
                                    promptVersion=ARTIFACT_VERSION, instruction="ignored", context={})
    with pytest.raises(ValueError):
        shadow_request({"difficulty": "C2", "turnNumber": 2}, "planned", artifact)
    assert artifact["levelProfiles"]["C2"]["generationAllowed"] is False
    assert "wordBounds" not in artifact["levelProfiles"]["C2"]
    assert load_artifact(Settings())["version"] != ARTIFACT_VERSION
    with pytest.raises(RegistryError):
        load_artifact(Settings(artifact_version=ARTIFACT_VERSION, artifact_sha256=artifact["sha256"],
                               production_mode=True, primary_provider="ollama", service_token="synthetic-test"))


def test_shadow_request_uses_frozen_profile_not_context_overrides(artifact):
    context = {**artifact["fewShotExamples"][0]["input"], "levelProfile": {"minWords": 1, "maxWords": 1000},
               "allowedTaskTypes": ["implication_tone"], "listeningSkills": {"fake": "Fake"}}
    original = copy.deepcopy(context)
    request = shadow_request(context, "bounds", artifact)
    profile = artifact["levelProfiles"]["B2"]
    assert request.context.levelProfile == profile["wordBounds"]
    assert request.context.allowedTaskTypes == profile["taskTypes"]
    assert context == original
    with pytest.raises(ValidationError):
        ConversationTaskRequest.model_validate(request.model_dump())


def test_every_example_is_grounded_and_no_shadow_task_passes_normal_validation(artifact):
    for example in artifact["fewShotExamples"]:
        level = example["input"]["difficulty"]
        task = merged(example)
        profile = artifact["levelProfiles"][level]
        assert validate_task(task, level=level, shadow_profile=profile) == []
        assert "level:unsupported" in validate_task(task, level=level)
        assert any("unsupported_for_level" in error for error in validate_task(task, level="B1"))
        fact = task["facts"][0]
        del fact["reasoningEvidence"]
        assert any("reasoningEvidence:required" in error for error in validate_task(task, level=level, shadow_profile=profile))


@pytest.mark.parametrize("defect", ["fabricated", "unlinked", "missing_type", "null_type", "wrong_level", "skill", "short", "long"])
def test_shadow_rejects_bad_grounding_task_types_skills_and_lengths(artifact, defect):
    task = merged(artifact["fewShotExamples"][0])
    fact = task["facts"][0]
    if defect == "fabricated":
        fact["reasoningEvidence"]["segmentsEn"].append("A fabricated claim.")
    elif defect == "unlinked":
        fact["reasoningEvidence"]["segmentsEn"] = [task["facts"][1]["evidenceEn"]]
    elif defect == "missing_type":
        del fact["taskType"]
    elif defect == "null_type":
        fact["taskType"] = None
    elif defect == "wrong_level":
        fact["taskType"] = "implicit_intention"
    elif defect == "skill":
        task["skill"] = "Unknown skill"
    else:
        task["utteranceEn"] = "word " * (29 if defect == "short" else 91)
    assert validate_task(task, level="B2", shadow_profile=artifact["levelProfiles"]["B2"])


@pytest.mark.asyncio
@pytest.mark.parametrize("level", ["B2", "C1"])
async def test_shadow_runs_real_pipeline_with_matching_schema_and_resets_context(artifact, level):
    example = next(e for e in artifact["fewShotExamples"] if e["input"]["difficulty"] == level)
    request = shadow_request(example["input"], "synthetic-pipeline", artifact)
    translation = {"translationVi": "Bản dịch để kiểm tra.", "facts": [{"textVi": "Kết luận"}, {"textVi": "Tài liệu"}]}
    previous = {"sentinel": True}
    token = selected_artifact.set(previous)
    try:
        with patch("app.model_gateway.ModelGateway.complete", new=AsyncMock(side_effect=[(example["output"], "ollama"),
                                                                                       (translation, "ollama")])) as gateway:
            result = await run_shadow_workflow(request, Settings(), None, artifact)
        assert gateway.await_count == 2
        messages, schema = gateway.await_args_list[0].args[:2]
        assert schema == artifact["contentSchemas"][level]
        data = json.loads(messages[1]["content"])
        assert data["difficulty"] == level
        assert "about 60 English words" in data["turn_brief"]
        assert result["facts"][0]["reasoningEvidence"] == example["output"]["facts"][0]["reasoningEvidence"]
        assert selected_artifact.get() is previous
    finally:
        selected_artifact.reset(token)


@pytest.mark.asyncio
async def test_shadow_is_local_only_and_does_not_run_with_default_bundle(artifact):
    request = shadow_request(artifact["fewShotExamples"][0]["input"], "isolated", artifact)
    for settings in (Settings(fallback_enabled=True), Settings(primary_provider="shopaikey"),
                     Settings(production_mode=True, service_token="synthetic-test")):
        with pytest.raises(ValueError, match="non-production local"):
            await run_shadow_workflow(request, settings, None, artifact)
    with pytest.raises(ValueError, match="offline evaluation artifact"):
        await generate_content(request, Settings(), None)


@pytest.mark.asyncio
async def test_shadow_failed_repair_also_resets_context(artifact):
    request = shadow_request(artifact["fewShotExamples"][0]["input"], "failure", artifact)
    before = selected_artifact.get()
    with patch("app.workflow._generate_content", new=AsyncMock(side_effect=RuntimeError("synthetic failure"))):
        with pytest.raises(RuntimeError, match="synthetic failure"):
            await run_shadow_workflow(request, Settings(), None, artifact)
    assert selected_artifact.get() is before


@pytest.mark.asyncio
async def test_shadow_repairs_missing_reasoning_once_and_preserves_request_type(artifact):
    example = artifact["fewShotExamples"][0]
    broken = copy.deepcopy(example["output"])
    del broken["facts"][0]["reasoningEvidence"]
    request = shadow_request(example["input"], "repair", artifact)
    translation = {"translationVi": "Bản dịch kiểm tra.", "facts": [{"textVi": "Kết luận"}, {"textVi": "Tài liệu"}]}
    with patch("app.workflow._generate_content", new=AsyncMock(side_effect=[broken, example["output"]])) as generate, \
         patch("app.workflow.translate_task", new=AsyncMock(return_value=translation)):
        result = await run_shadow_workflow(request, Settings(repair_enabled=True), None, artifact)
    assert generate.await_count == 2
    repair = generate.await_args_list[1].args[0]
    assert isinstance(repair, ShadowConversationTaskRequest)
    assert "reasoningEvidence:required" in repair.context.validationError
    assert request.context.validationError is None
    assert result["facts"][0]["reasoningEvidence"]


@pytest.mark.asyncio
@pytest.mark.parametrize("level", ["B2", "C1"])
async def test_shadow_rejects_first_attempt_without_repair_by_default(artifact, level):
    example = next(e for e in artifact["fewShotExamples"] if e["input"]["difficulty"] == level)
    broken = copy.deepcopy(example["output"])
    del broken["facts"][0]["reasoningEvidence"]
    request = shadow_request(example["input"], "no-repair", artifact)
    translation = {"translationVi": "Bản dịch kiểm tra.", "facts": [{"textVi": "Kết luận"}, {"textVi": "Tài liệu"}]}
    before = selected_artifact.get()
    from app.ollama import OllamaError
    with patch("app.workflow._generate_content", new=AsyncMock(return_value=broken)) as generate, \
         patch("app.workflow.translate_task", new=AsyncMock(return_value=translation)) as translate:
        with pytest.raises(OllamaError, match="first-attempt task invalid.*reasoningEvidence:required"):
            await run_shadow_workflow(request, Settings(), None, artifact)
    generate.assert_awaited_once()
    translate.assert_awaited_once()
    assert selected_artifact.get() is before


def test_shadow_dataset_has_ten_cases_per_level_and_three_seeds(artifact):
    dataset = json.loads((ROOT / "tests/fixtures/evaluation/cefr-shadow-v1-10x3.json").read_text(encoding="utf-8"))
    assert dataset["seeds"] == [17, 43, 71]
    for level in ("B2", "C1"):
        cases = [c for c in dataset["cases"] if c["context"]["difficulty"] == level]
        assert len(cases) == 10
        assert {c["context"]["turnNumber"] for c in cases} == {2, 4}
        assert len({c["id"] for c in cases}) == 10
        for case in cases:
            assert set(case["expectedTaskTypes"]) <= set(artifact["levelProfiles"][level]["taskTypes"])
            shadow_request(case["context"], case["id"], artifact)
