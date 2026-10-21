import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.config import Settings
from app.ollama import (CONTENT_SCHEMA, TRANSLATION_SCHEMA, OllamaError, generate,
                        merge_content_and_translation, translate_task)


def make_request(context=None, instruction="test instruction"):
    return SimpleNamespace(requestId="req-1", context=context or {"partnerRole": "staff"},
                           instruction=instruction)


def envelope(payload):
    return {"done": True, "done_reason": "stop", "message": {"content": json.dumps(payload)}}


def content_payload():
    return {
        "slots": [{"kind": "place", "askVi": "Ở đâu?"}, {"kind": "time", "askVi": "Mấy giờ?"}],
        "utteranceEn": "Meet me at the window counter at ten.",
        "facts": [
            {"textEn": "the window counter", "evidenceEn": "the window counter"},
            {"textEn": "ten", "evidenceEn": "at ten"},
        ],
        "questionVi": "Ở đâu và mấy giờ?",
        "taskVi": "Nghe địa điểm và giờ.",
        "skill": "Nắm thông tin cụ thể",
    }


def translation_payload():
    return {
        "translationVi": "Gặp tôi tại quầy cạnh cửa sổ lúc mười giờ.",
        "facts": [{"textVi": "Quầy cạnh cửa sổ"}, {"textVi": "Mười giờ"}],
    }


def make_client(*responses):
    client = AsyncMock()
    client.post = AsyncMock(side_effect=responses)
    return client


def ok_response(payload):
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = envelope(payload)
    return response


async def test_generate_makes_two_calls_with_correct_schemas_and_temperatures():
    settings = Settings()
    client = make_client(ok_response(content_payload()), ok_response(translation_payload()))
    request = make_request()

    result = await generate(request, settings, client)

    assert client.post.await_count == 2

    first_call = client.post.await_args_list[0]
    assert first_call.args[0].endswith("/api/chat")
    first_body = first_call.kwargs["json"]
    assert first_body["format"] == CONTENT_SCHEMA
    assert first_body["options"]["temperature"] == settings.temperature

    second_call = client.post.await_args_list[1]
    second_body = second_call.kwargs["json"]
    assert second_body["format"] == TRANSLATION_SCHEMA
    assert second_body["options"]["temperature"] == settings.translation_temperature

    # Merged public task keeps utteranceEn, questionVi, taskVi, skill and drops textEn.
    assert result["utteranceEn"] == "Meet me at the window counter at ten."
    assert result["questionVi"] == "Ở đâu và mấy giờ?"
    assert result["taskVi"] == "Nghe địa điểm và giờ."
    assert result["skill"] == "Nắm thông tin cụ thể"
    assert result["translationVi"] == "Gặp tôi tại quầy cạnh cửa sổ lúc mười giờ."
    assert len(result["facts"]) == 2
    for fact in result["facts"]:
        assert set(fact.keys()) == {"textVi", "evidenceEn"}


async def test_translation_receives_only_english_facts_and_roles():
    settings = Settings()
    client = make_client(ok_response(content_payload()), ok_response(translation_payload()))
    request = make_request({"partnerRole": "staff", "learnerRole": "guest"})

    await generate(request, settings, client)

    second_body = client.post.await_args_list[1].kwargs["json"]
    user_payload = json.loads(second_body["messages"][1]["content"])
    assert user_payload["partnerRole"] == "staff"
    assert user_payload["learnerRole"] == "guest"
    assert user_payload["utteranceEn"] == "Meet me at the window counter at ten."
    assert user_payload["facts"] == [{"textEn": "the window counter"}, {"textEn": "ten"}]


async def test_merge_preserves_evidence_en_byte_for_byte():
    content = {
        "slots": [],
        "utteranceEn": "A B C",
        "facts": [{"textEn": "B", "evidenceEn": "B"}, {"textEn": "C", "evidenceEn": "C"}],
        "questionVi": "q", "taskVi": "t", "skill": "s",
    }
    translated = {"translationVi": "dịch", "facts": [{"textVi": "b"}, {"textVi": "c"}]}
    merged = merge_content_and_translation(content, translated)
    assert merged["facts"] == [
        {"textVi": "b", "evidenceEn": "B"},
        {"textVi": "c", "evidenceEn": "C"},
    ]
    assert merged["utteranceEn"] == "A B C"


def test_merge_raises_on_fact_count_mismatch():
    content = {"slots": [], "utteranceEn": "u", "facts": [{"textEn": "a", "evidenceEn": "a"}],
               "questionVi": "q", "taskVi": "t", "skill": "s"}
    translated = {"translationVi": "d", "facts": [{"textVi": "x"}, {"textVi": "y"}]}
    with pytest.raises(OllamaError):
        merge_content_and_translation(content, translated)


async def test_generate_timeout_raises_timeout_error():
    import httpx
    settings = Settings()
    client = make_client()
    client.post = AsyncMock(side_effect=httpx.ConnectError("down", request=None))
    with pytest.raises(TimeoutError):
        await generate(make_request(), settings, client)


async def test_generate_invalid_json_raises_ollama_error():
    settings = Settings()
    response = Mock()
    response.raise_for_status.return_value = None
    response.json.return_value = {"done": True, "done_reason": "stop", "message": {"content": "{not json"}}
    client = make_client(response)
    with pytest.raises(OllamaError):
        await generate(make_request(), settings, client)


async def test_translate_task_builds_low_temperature_request():
    settings = Settings()
    client = make_client(ok_response(translation_payload()))
    await translate_task(content_payload(), make_request(), settings, client)
    body = client.post.await_args.kwargs["json"]
    assert body["format"] == TRANSLATION_SCHEMA
    assert body["options"]["temperature"] == 0.1
