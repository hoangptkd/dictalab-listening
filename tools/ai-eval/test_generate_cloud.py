"""Cloud capture stays explicit and never invents a seed or a model digest."""
import asyncio
import hashlib
import json

import httpx
import pytest

import generate
from app.config import Settings
from app.registry import RegistryError


def _settings(**overrides):
    values = {"primary_provider": "shopaikey", "cloud_api_key": "test-only-key",
              "cloud_model": "claude-sonnet-4-5", "production_mode": False,
              "model": "qwen3.5:9b", "repair_enabled": False, "fallback_enabled": False}
    values.update(overrides)
    return Settings(**values)


def _post(body):
    seen = {}

    def handler(request):
        seen["json"] = json.loads(request.content)
        return httpx.Response(200, json={})

    async def run():
        async with generate.SeededClient(17, transport=httpx.MockTransport(handler)) as client:
            await client.post("https://cloud.test/v1/chat/completions", json=body)

    asyncio.run(run())
    return seen["json"]


def test_cloud_body_keeps_no_seed_and_does_not_crash():
    body = {"model": "claude-sonnet-4-5", "messages": [], "response_format": {"type": "json_object"}}
    sent = _post(body)
    assert "options" not in sent
    assert "seed" not in sent


def test_local_body_keeps_the_fixed_seed():
    sent = _post({"model": "qwen3.5:9b", "options": {"temperature": 0.3}})
    assert sent["options"]["seed"] == 17


def test_cloud_digest_pins_provider_endpoint_and_model():
    settings = _settings()
    digest = generate.cloud_model_digest(settings)
    expected = hashlib.sha256(b"shopaikey|https://api.shopaikey.com|claude-sonnet-4-5").hexdigest()
    assert digest == expected and len(digest) == 64
    assert generate.cloud_model_digest(_settings(cloud_model="other-model")) != digest

    gemini = _settings(primary_provider="gemini", gemini_api_key="gemini-key",
                       gemini_model="gemini-3.5-flash-lite")
    expected = hashlib.sha256(
        b"gemini|https://generativelanguage.googleapis.com/v1beta/openai|gemini-3.5-flash-lite"
    ).hexdigest()
    assert generate.cloud_model_digest(gemini) == expected


def _verify(payload, model="claude-sonnet-4-5", **overrides):
    settings = _settings(cloud_model=model, **overrides)

    def handler(request):
        expected_key = settings.active_cloud_api_key
        assert request.headers["authorization"] == "Bearer " + expected_key
        assert str(request.url) == settings.active_cloud_models_url
        return httpx.Response(200, json=payload)

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await generate.verify_cloud_model(settings, client)

    asyncio.run(run())


def test_cloud_model_must_be_listed():
    _verify({"data": [{"id": "claude-sonnet-4-5"}]})
    with pytest.raises(RegistryError):
        _verify({"data": [{"id": "claude-sonnet-4-5"}]}, model="missing-model")


def test_gemini_model_is_verified_with_google_models_prefix():
    _verify({"data": [{"id": "models/gemini-3.5-flash-lite"}]},
            primary_provider="gemini", gemini_api_key="gemini-key",
            gemini_model="gemini-3.5-flash-lite")
    with pytest.raises(RegistryError):
        _verify({"data": [{"id": "other"}]},
                primary_provider="gemini", gemini_api_key="gemini-key",
                gemini_model="gemini-3.5-flash-lite")
