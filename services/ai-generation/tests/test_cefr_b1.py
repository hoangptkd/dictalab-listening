"""B1 evidence and word limits, including the actual bounded workflow repair."""
import copy
from unittest.mock import AsyncMock, patch

import pytest

from app.config import Settings
from app.ollama import OllamaError, merge_content_and_translation
from app.registry import load_artifact
from app.schemas import ConversationTaskRequest
from app.validation import LEVEL_BOUNDS, validate_task
from app.workflow import run_workflow


def task(words=30):
    return {"slots": [{"kind": "meeting_time", "askVi": "Mấy giờ?"}, {"kind": "meeting_place", "askVi": "Ở đâu?"}],
            "utteranceEn": "Meet at nine in the lobby. " + "please " * (words - 6),
            "facts": [{"textVi": "Chín giờ", "evidenceEn": "at nine"}, {"textVi": "Sảnh", "evidenceEn": "in the lobby"}],
            "questionVi": "Cuộc họp lúc mấy giờ và ở đâu?", "taskVi": "Nghe thông tin cuộc họp.",
            "skill": "Nắm thông tin cụ thể", "translationVi": "Gặp lúc chín giờ tại sảnh."}


def inferred():
    result = task()
    result["utteranceEn"] = ("My morning train has been cancelled. The next train leaves at noon, and my ticket is valid "
                             "for that service too. I still need to reach the city today. Please bring your notebook.")
    result["questionVi"] = "Người nói có thể làm gì tiếp theo và cần mang gì?"
    result["skill"] = "Suy luận và dự đoán có căn cứ"
    result["facts"] = [
        {"textVi": "Có thể đi chuyến tàu lúc trưa", "evidenceEn": "My morning train has been cancelled.",
         "taskType": "inference_prediction", "reasoningEvidence": {
             "segmentsEn": ["My morning train has been cancelled.",
                            "The next train leaves at noon, and my ticket is valid for that service too.",
                            "I still need to reach the city today."],
             "rationaleEn": "A cancelled train, a valid ticket for the next service and the need to arrive today support taking the noon train."}},
        {"textVi": "Sổ ghi chép", "evidenceEn": "your notebook", "taskType": "direct_detail"}]
    return result


@pytest.mark.parametrize("level,minimum,maximum", [("A1", 8, 35), ("A2", 18, 55), ("B1", 30, 90)])
def test_word_boundaries_and_legacy_context(level, minimum, maximum):
    for words in (minimum, maximum):
        assert validate_task(task(words), level=level) == []
    for words in (minimum - 1, maximum + 1):
        assert any("word_count=" in error for error in validate_task(task(words), level=level))
    for words in (20, 65):
        assert validate_task(task(words), level=level, level_profile={"minWords": 20, "maxWords": 65}) == []
    assert any("word_count=" in e for e in validate_task(task(19), level=level, level_profile={"minWords": 20, "maxWords": 65}))


@pytest.mark.parametrize("bounds", [{"minWords": True}, {"maxWords": 91}, {"minWords": 40, "maxWords": 20}, {"minWords": "30"}])
def test_invalid_word_profile_is_rejected(bounds):
    assert "levelProfile:invalid_word_bounds" in validate_task(task(), level="B1", level_profile=bounds)


def test_registered_bundle_matches_standalone_bounds_and_task_types():
    artifact = load_artifact(Settings())
    for level, (minimum, maximum) in LEVEL_BOUNDS.items():
        assert artifact["levelProfiles"][level]["wordBounds"] == {"minWords": minimum, "maxWords": maximum}
    assert artifact["approvalStatus"] == "development"
    assert artifact["metricReport"]["gate"]["releaseAllowed"] is False


def test_grounded_inference_and_single_segment_with_multiple_cues_are_supported():
    result = inferred()
    assert validate_task(result, level="B1") == []
    fact = result["facts"][0]
    fact["evidenceEn"] = "morning train has been cancelled"
    assert validate_task(result, level="B1") == []
    fact["evidenceEn"] = result["utteranceEn"]
    fact["reasoningEvidence"]["segmentsEn"] = [result["utteranceEn"]]
    assert validate_task(result, level="B1") == []
    assert any("unsupported" in e for e in validate_task(result, level="A2"))


@pytest.mark.parametrize("defect", ["missing", "fabricated", "duplicate", "primary", "rationale", "shape", "empty"])
def test_invalid_inference_evidence_is_rejected(defect):
    result = inferred()
    fact = result["facts"][0]
    evidence = fact["reasoningEvidence"]
    if defect == "missing":
        del fact["reasoningEvidence"]
    elif defect == "fabricated":
        evidence["segmentsEn"].append("The train company promised a taxi.")
    elif defect == "duplicate":
        evidence["segmentsEn"].append(evidence["segmentsEn"][0])
    elif defect == "primary":
        evidence["segmentsEn"].pop(0)
    elif defect == "rationale":
        evidence["rationaleEn"] = " "
    elif defect == "shape":
        evidence["extra"] = True
    else:
        evidence["segmentsEn"] = []
    assert any("reasoningEvidence" in e for e in validate_task(result, level="B1"))


def test_translation_preserves_evidence_without_trusting_translator_metadata():
    original = inferred()
    content = copy.deepcopy(original)
    for fact in content["facts"]:
        fact["textEn"] = fact.pop("textVi")
    translated = {"translationVi": original["translationVi"], "facts": [
        {"textVi": fact["textVi"], "reasoningEvidence": {"segmentsEn": ["invented"]}} for fact in original["facts"]]}
    assert merge_content_and_translation(content, translated) == original


def test_b1_does_not_accept_multiple_inference_targets_or_null_task_type():
    result = inferred()
    second = result["facts"][1]
    second["taskType"] = "inference_prediction"
    second["reasoningEvidence"] = {"segmentsEn": [second["evidenceEn"]], "rationaleEn": "Bring the notebook."}
    assert "facts:at_most_one_inference_target" in validate_task(result, level="B1")
    second["taskType"] = None
    assert any("taskType:unsupported_for_level" in e for e in validate_task(result, level="B1"))


@pytest.mark.asyncio
@pytest.mark.parametrize("bounds,target", [({"minWords": 30, "maxWords": 90}, 60), ({"minWords": 20, "maxWords": 65}, 42)])
async def test_server_turn_brief_respects_profile_and_legacy_bounds(bounds, target):
    from app.prompt_versions import generate_content
    request = ConversationTaskRequest(requestId="brief-test", idempotencyKey="brief-test", level="B1", turnNumber=4,
        promptVersion="V1", instruction="ignored", context={"levelProfile": bounds, "partnerRole": "receptionist", "learnerRole": "guest"})
    with patch("app.prompt_versions._complete", new=AsyncMock(return_value={})) as complete:
        await generate_content(request, Settings(), None)
    data = complete.await_args.args[5]
    assert f"about {target} English words" in data["turn_brief"]
    assert "Speaker: receptionist; listener: guest" in data["turn_brief"]
    assert "NEW independent encounter" in data["turn_brief"]


@pytest.mark.asyncio
@pytest.mark.parametrize("repaired_words,succeeds", [(30, True), (29, False)])
async def test_workflow_repairs_short_b1_once_with_actual_errors(repaired_words, succeeds):
    request = ConversationTaskRequest(requestId="length-test", idempotencyKey="length-test", level="B1", turnNumber=2,
        promptVersion="V1", instruction="Data only", context={"levelProfile": {"minWords": 30, "maxWords": 90}})
    contents = []
    for words in (29, repaired_words):
        content = task(words)
        content.pop("translationVi")
        for fact in content["facts"]:
            fact["textEn"] = fact.pop("textVi")
        contents.append(content)
    translation = {"translationVi": "Bản dịch.", "facts": [{"textVi": "Chín giờ"}, {"textVi": "Sảnh"}]}
    with patch("app.workflow._generate_content", new=AsyncMock(side_effect=contents)) as generate, \
         patch("app.workflow.translate_task", new=AsyncMock(return_value=translation)):
        if succeeds:
            result = await run_workflow(request, Settings(repair_enabled=True), None)
            assert validate_task(result, level="B1") == []
        else:
            with pytest.raises(OllamaError, match="repaired task still invalid"):
                await run_workflow(request, Settings(repair_enabled=True), None)
        assert generate.await_count == 2
        repair = generate.await_args_list[1].args[0]
        assert "word_count=29,min=30,max=90" in repair.context.validationError
        assert repair.context.previousInvalidPrompt["utteranceEn"] == contents[0]["utteranceEn"]
        assert request.context.validationError is None
