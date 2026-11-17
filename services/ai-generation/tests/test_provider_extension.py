"""Extension and model capabilities, without provider accounts or paid network calls."""
import json
import time
from uuid import uuid4

import httpx
import pytest

from app.config import Settings, GeminiSource
from app.model_gateway import ModelGateway
from app.providers import register, Registration, PROVIDERS, CLOUD_PROVIDERS
from app.providers.openai_compatible import OpenAiCompatibleProvider


class FixtureProvider(OpenAiCompatibleProvider):
    def __init__(self, **kwargs):
        super().__init__(provider_name="fixture", **kwargs)


@pytest.fixture
def fixture_provider(monkeypatch):
    # The runtime extension needs only a class and registration; Settings/worker/workflows stay untouched.
    register("fixture", Registration(FixtureProvider))
    source = GeminiSource(id="fixture-source", platform="fixture", projectId="fixture-account", apiKey="fake-only",
        model="fixture-model", url="https://fixture.invalid/v1/chat/completions", enabled=True)
    monkeypatch.setattr("app.provider_registry.load_registry", lambda *a, **kw: (
        {"fixture": {"default_model": "fixture-model", "global_rpm_limit": 0, "global_rpd_limit": 0}}, [source]))
    yield Settings(primary_provider="fixture", ai_provider_config_source="database", gemini_quota_backend="memory")
    PROVIDERS.pop("fixture")
    CLOUD_PROVIDERS.discard("fixture")


async def test_registered_provider_uses_unmodified_gateway_and_private_registry(fixture_provider):
    requests = []
    def handler(request):
        requests.append(request)
        assert request.headers["Authorization"] == "Bearer fake-only"
        return httpx.Response(200, json={"model": "fixture-model-revision", "choices": [
            {"finish_reason": "stop", "message": {"content": '{"ok":true}'}}],
            "usage": {"prompt_tokens": 3, "completion_tokens": 2}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        gateway = ModelGateway(fixture_provider, client)
        result, provider = await gateway.complete([{"role": "user", "content": "Return JSON"}],
            {"type": "object", "required": ["ok"]}, time.monotonic() + 10, "fixture_check")
    assert result == {"ok": True} and provider == "fixture"
    assert len(requests) == 1
    assert gateway.last_source_id == "fixture-source"
    assert gateway.last_result.model == "fixture-model"
    assert gateway.last_result.reported_model == "fixture-model-revision"
    assert gateway.last_result.usage == {"input_tokens": 3, "output_tokens": 2}


async def test_capability_profile_controls_wire_and_refuses_unsupported_mode(fixture_provider):
    fixture_provider.model_profiles = {"fixture/fixture-model": {
        "tokenField": "max_completion_tokens", "temperaturePolicy": "omit", "jsonModes": ["json_object"]}}
    calls = []
    def handler(request):
        calls.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        gateway = ModelGateway(fixture_provider, client)
        await gateway.complete([{"role": "user", "content": "JSON"}], {"type": "object"},
                               time.monotonic() + 10, "fixture_check", temperature=0, json_object=True)
    assert calls[0]["max_completion_tokens"] == fixture_provider.num_predict
    assert "temperature" not in calls[0] and "max_tokens" not in calls[0]
    adapter = FixtureProvider(base_url="https://fixture.invalid", api_key="fake", model="fixture-model", client=None,
                              use_json_schema=True)
    adapter.configure_model_profile(fixture_provider.model_profiles["fixture/fixture-model"])
    with pytest.raises(RuntimeError, match="UNSUPPORTED_CAPABILITY"):
        await adapter.complete([], {}, time.monotonic() + 10, "fixture_check")


def test_unknown_provider_never_becomes_a_gemini_fallback():
    with pytest.raises(ValueError, match="unsupported primary_provider"):
        Settings(primary_provider="unregistered")
    with pytest.raises(ValueError, match="duplicate"):
        register("gemini", Registration(FixtureProvider))


async def test_explicit_native_schema_profile_does_not_assume_json_object(fixture_provider):
    fixture_provider.model_profiles = {"fixture/fixture-model": {"jsonModes": ["json_schema"], "schemaMode": "native"}}
    calls = []
    def respond(request):
        calls.append(json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result, _ = await ModelGateway(fixture_provider, client).complete([{"role": "user", "content": "Return JSON"}],
            {"type": "object"}, time.monotonic() + 10, "fixture_check")
    assert result == {"ok": True}
    assert calls[0]["response_format"]["type"] == "json_schema"
