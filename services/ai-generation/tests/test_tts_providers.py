"""Provider switching must preserve the private audio contract and frozen lesson recipes."""
import base64
import io
import json
import time
import wave
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from unittest.mock import patch

import httpx
import pytest

from app import tts
from app.config import Settings
from app.jobs import JobEvent, JobRequest, JobStore, capability_metadata, digest, execution_fingerprint
from app.worker import execute


def wav_bytes():
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setparams((1, 2, 24000, 0, "NONE", "NONE"))
        audio.writeframes(b"\x01\x00" * 10)
    return output.getvalue()


def recipe(provider="openai_compatible"):
    return {"id": "speech-pair-v1", "provider": provider, "model": "speech-model", "apiVersion": "",
            "language": "en", "outputFormat": "wav-pcm16-mono-24000", "voices": ["alloy", "nova"]}


def request(payload):
    return JobRequest(jobId=uuid4(), requestId=uuid4(), userScope="a" * 64, idempotencyKey="tts/" + str(uuid4()),
                      deadline=datetime.now(timezone.utc) + timedelta(seconds=60), payload=payload)


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["cartesia", "openai_compatible"])
async def test_provider_selection_has_separate_credentials_and_preserves_audio(provider):
    settings = Settings(tts_provider=provider, conversation_enabled=False, cartesia_api_key="cartesia-test-key",
                        openai_tts_api_key="speech-test-key", openai_tts_model="speech-model", openai_tts_voice="alloy")
    calls = []
    def respond(req):
        calls.append(req)
        body = json.loads(req.content)
        if provider == "cartesia":
            assert req.headers["Authorization"] == "Bearer cartesia-test-key"
            assert body["transcript"] == "At nine."
            assert body["voice"]["mode"] == "id"
            assert "Cartesia-Version" in req.headers
        else:
            assert req.headers["Authorization"] == "Bearer speech-test-key"
            assert body == {"model": "speech-model", "input": "At nine.", "voice": "alloy", "response_format": "wav"}
            assert "Cartesia-Version" not in req.headers
        return httpx.Response(200, content=wav_bytes(), headers={"Content-Type": "audio/wav"})
    payload = tts.TtsInput(text="At nine.")
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result = await tts.synthesize(payload, settings, client, time.monotonic() + 20)
    assert base64.b64decode(result["audioBase64"]) == wav_bytes()
    assert capability_metadata(payload, settings)["provider"] == provider
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_frozen_recipe_survives_default_provider_change_and_has_verifiable_metadata(tmp_path):
    approved = recipe()
    settings = Settings(tts_provider="cartesia", openai_tts_api_key="speech-test-key", scenario_tts_recipes=[approved])
    store = JobStore(tmp_path / "tts.sqlite")
    for voice in approved["voices"]:
        req = request({"kind": "scenario_tts", "text": "At nine.", "speakerId": "c1", "voiceId": voice, "recipe": approved})
        store.register(req, settings)
        event = JobEvent(**req.model_dump(), payloadHash=digest(req.payload.model_dump()))
        def respond(http_request):
            assert json.loads(http_request.content)["voice"] == voice
            assert http_request.headers["Authorization"] == "Bearer speech-test-key"
            return httpx.Response(200, content=wav_bytes(), headers={"Content-Type": "audio/wav"})
        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with patch("app.worker.httpx.AsyncClient", return_value=client):
            result = await execute(event, settings)
        assert result["provider"] == "openai_compatible"
        assert result["recipeHash"] == digest(approved)
        assert result["voiceId"] == voice
        assert result["model"] == approved["model"]
        changed = settings.model_copy(update={"tts_provider": "openai_compatible", "openai_tts_voice": "other"})
        assert execution_fingerprint(req.payload, changed) == execution_fingerprint(req.payload, settings)


def test_provider_and_voice_changes_invalidate_generic_jobs_but_key_rotation_does_not():
    payload = tts.TtsInput(text="At nine.")
    settings = Settings(tts_provider="openai_compatible", openai_tts_model="speech-model", openai_tts_voice="alloy")
    original = execution_fingerprint(payload, settings)
    for changes in ({"tts_provider": "cartesia"}, {"openai_tts_voice": "nova"}, {"openai_tts_model": "another-model"},
                    {"openai_tts_url": "https://example.com/speech"}):
        assert execution_fingerprint(payload, settings.model_copy(update=changes)) != original
    assert execution_fingerprint(payload, settings.model_copy(update={"openai_tts_api_key": "rotated-test-key"})) == original


@pytest.mark.asyncio
@pytest.mark.parametrize("status,content_type,body", [(429, "audio/wav", wav_bytes()),
    (503, "audio/wav", wav_bytes()), (200, "application/json", b"{}"), (200, "audio/wav", b"invalid")])
async def test_alternate_provider_errors_never_retry_or_fall_back(status, content_type, body):
    calls = []
    settings = Settings(tts_provider="openai_compatible", cartesia_api_key="cartesia-test-key",
        openai_tts_api_key="speech-test-key", openai_tts_model="speech-model", openai_tts_voice="alloy")
    def respond(req):
        calls.append(req)
        assert req.headers["Authorization"] == "Bearer speech-test-key"
        return httpx.Response(status, content=body, headers={"Content-Type": content_type})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with pytest.raises((httpx.HTTPStatusError, ValueError)):
            await tts.synthesize(tts.TtsInput(text="At nine."), settings, client, time.monotonic() + 20)
    assert len(calls) == 1


def test_implicit_legacy_recipe_serialization_and_provenance_stay_unchanged():
    old = {"id": "pair-v1", "model": "sonic-3.6", "apiVersion": "2026-08-14", "language": "en",
           "outputFormat": "wav-pcm16-mono-24000",
           "voices": ["00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"]}
    payload = tts.ScenarioTtsInput(text="At nine.", speakerId="c1", voiceId=old["voices"][0], recipe=old)
    assert payload.model_dump()["recipe"] == old
    assert capability_metadata(payload, Settings())["promptHash"] == tts.LEGACY_SCENARIO_PROMPT_HASH
    explicit = {**old, "provider": "cartesia"}
    assert tts.ScenarioTtsRecipe(**explicit).model_dump() == explicit


@pytest.mark.asyncio
async def test_disabled_missing_credentials_and_unapproved_recipe_never_send():
    calls = []
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda req: calls.append(req))) as client:
        for settings in (Settings(tts_enabled=False, cartesia_api_key="test-key"), Settings(tts_provider="openai_compatible")):
            with pytest.raises(ValueError, match="TTS is not configured"):
                await tts.synthesize(tts.TtsInput(text="At nine."), settings, client, time.monotonic() + 20)
        payload = tts.ScenarioTtsInput(text="At nine.", speakerId="c1", voiceId="alloy", recipe=recipe())
        with pytest.raises(ValueError, match="not approved"):
            await tts.synthesize(payload, Settings(openai_tts_api_key="test-key"), client, time.monotonic() + 20)
    assert calls == []


@pytest.mark.parametrize("values", [{"tts_provider": "unknown"}, {"openai_tts_url": "http://example.com/speech"},
    {"openai_tts_url": "https://secret@example.com/speech"}, {"openai_tts_api_key": "bad\nkey"}])
def test_configuration_rejects_unsafe_or_unknown_providers(values):
    with pytest.raises(ValueError):
        Settings(**values)


def test_shared_environment_settings_are_independent_of_text_provider_and_hide_keys(monkeypatch):
    monkeypatch.setenv("DICTALAB_TTS_PROVIDER", "openai_compatible")
    monkeypatch.setenv("DICTALAB_TTS_OPENAI_API_KEY", "speech-test-key")
    monkeypatch.setenv("DICTALAB_TTS_OPENAI_MODEL", "speech-model")
    monkeypatch.setenv("DICTALAB_TTS_OPENAI_VOICE", "alloy")
    settings = Settings(cartesia_api_key="cartesia-test-key")
    assert settings.tts_provider == "openai_compatible"
    assert settings.openai_tts_api_key == "speech-test-key"
    assert "speech-test-key" not in repr(settings)
    assert "cartesia-test-key" not in repr(settings)
