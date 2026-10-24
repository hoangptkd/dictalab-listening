import base64
import json
import time
from unittest.mock import patch, Mock

import httpx
import pytest
from app.config import Settings, GeminiSource
from app.provider_registry import encrypt_key, decrypt_key, validate_endpoint
from app.gemini_pool import quota_store, QuotaUnavailable
from app.model_gateway import ModelGateway

MASTER = base64.b64encode(bytes(range(32))).decode()
ROW = dict(id="ds1",platform="deepseek",account_id="account1",model="deepseek-flash",
           endpoint="https://api.deepseek.com/chat/completions")


def test_crypto_roundtrip_tamper_wrong_master_and_python_java_fixture():
    encrypted = encrypt_key(MASTER,ROW,"fake-test-secret")
    assert "fake-test-secret" not in encrypted
    assert decrypt_key(MASTER,ROW|dict(api_key_ciphertext=encrypted)) == "fake-test-secret"
    fixture = "v1:AAECAwQFBgcICQoLIWO9fuiRp2j5bOTu0psdGf6Oj9LoxIYsut6pnWxuOgE="
    assert decrypt_key(MASTER,ROW|dict(api_key_ciphertext=fixture)) == "fake-test-secret"
    for changed in (dict(endpoint="https://api.deepseek.com/changed"),dict(account_id="other"),dict(api_key_ciphertext="v0:bad")):
        with pytest.raises(ValueError,match="cannot be decrypted") as error:
            decrypt_key(MASTER,ROW|dict(api_key_ciphertext=encrypted)|changed)
        assert error.value.__cause__ is None
    with pytest.raises(ValueError,match="cannot be decrypted"):
        decrypt_key(base64.b64encode(bytes(32)).decode(),ROW|dict(api_key_ciphertext=encrypted))
    with pytest.raises(ValueError):
        validate_endpoint("deepseek","https://generativelanguage.googleapis.com/chat/completions")


def registry_settings(platform="deepseek"):
    sources = [GeminiSource(id=p,platform=p,project_id="account1",api_key="fake-"+p,
        model="deepseek-flash" if p=="deepseek" else "gemini-3.5-flash-lite",
        url="https://api.deepseek.com/chat/completions" if p=="deepseek" else
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        enabled=True,operating_rpm=1) for p in ("gemini","deepseek")]
    pools = {s.platform: dict(default_model=s.model,global_rpm_limit=1,global_rpd_limit=100,enabled=True) for s in sources}
    with patch("app.provider_registry.load_registry",return_value=(pools,sources)):
        return Settings(primary_provider=platform,ai_provider_config_source="database",
            gemini_quota_backend="memory",gemini_api_key="must-not-fallback")


def test_vendor_quota_isolation_and_preserved_gemini_namespace():
    cfg = registry_settings()
    ds,gem = quota_store(cfg,"deepseek"),quota_store(cfg,"gemini")
    source = ds.select("deepseek-flash",1)
    ds.client = Mock()
    ds.client.time.return_value = (1792670400,0)
    assert ds._keys_args(source,1,"reserve",0)[0][0].startswith("{dictalab-ai-quota}:v2:deepseek:")
    with pytest.raises(QuotaUnavailable):
        ds.select("deepseek-flash",1)
    source = gem.select("gemini-3.5-flash-lite",1)
    gem.client = ds.client
    assert gem._keys_args(source,1,"reserve",0)[0][0].startswith("{dictalab-gemini-quota}:v2:")
    assert cfg.active_cloud_api_key == ""


@pytest.mark.asyncio
@pytest.mark.parametrize("cache_prefix", [False, True])
@pytest.mark.parametrize("thinking,effort", [(False, None), (True, None), (True, "low"), (True, "high")])
async def test_deepseek_wire_and_no_cross_platform_fallback(thinking, effort, cache_prefix):
    cfg = registry_settings()
    # Independent fake account avoids sharing the memory counters with the isolation check.
    cfg.cloud_providers[1].project_id = "wire-test-" + str(thinking) + str(effort) + str(cache_prefix)
    cfg.registry_pools["deepseek"]["global_rpm_limit"] = 2
    calls = []
    def handler(request):
        calls.append(request)
        assert request.url.host == "api.deepseek.com"
        assert request.headers["Authorization"] == "Bearer fake-deepseek"
        body = json.loads(request.content)
        assert body["thinking"] == {"type": "enabled" if thinking else "disabled"}
        assert body.get("reasoning_effort") == effort
        assert body["response_format"] == {"type":"json_object"}
        assert "JSON" in body["messages"][-1 if cache_prefix else 0]["content"]
        assert body["messages"][0 if cache_prefix else 1] == {"role":"system", "content":"Stable instructions"}
        return httpx.Response(200,json={"choices":[{"finish_reason":"stop","message":{"content":'{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        gateway = ModelGateway(cfg,client)
        result,platform = await gateway.complete([{"role":"system", "content":"Stable instructions"}],
            {"type":"object"},time.monotonic()+10,"check", deepseek_thinking=thinking,
            deepseek_reasoning_effort=effort, deepseek_cache_prefix=cache_prefix)
        assert result == {"ok":True} and platform == "deepseek"
        with pytest.raises(QuotaUnavailable):
            await gateway.complete([], {"type":"object"},time.monotonic()+10,"check")
    assert len(calls)==1


@pytest.mark.asyncio
@pytest.mark.parametrize("thinking,effort", [(False, "low"), (True, "medium")])
async def test_invalid_reasoning_effort_never_sends(thinking, effort):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: pytest.fail("invalid effort must not send"))) as client:
        with pytest.raises(ValueError, match="reasoning effort"):
            await ModelGateway(registry_settings(), client).complete([], {}, time.monotonic()+10, "check",
                deepseek_thinking=thinking, deepseek_reasoning_effort=effort)


@pytest.mark.asyncio
@pytest.mark.parametrize("platform,cache_prefix", [("gemini", True), ("deepseek", 1)])
async def test_invalid_cache_opt_in_never_sends(platform, cache_prefix):
    async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: pytest.fail("invalid cache opt-in must not send"))) as client:
        with pytest.raises(ValueError, match="cache prefix"):
            await ModelGateway(registry_settings(platform), client).complete([], {}, time.monotonic()+10,
                "check", deepseek_cache_prefix=cache_prefix)


@pytest.mark.asyncio
async def test_thinking_opt_in_cannot_silently_use_another_provider():
    cfg = registry_settings("gemini")
    async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: pytest.fail("invalid opt-in must not send a request"))) as client:
        with pytest.raises(ValueError, match="DeepSeek registry"):
            await ModelGateway(cfg, client).complete([], {}, time.monotonic()+10, "check", deepseek_thinking=True)


def test_database_mode_fails_closed_without_env_fallback():
    with pytest.raises(ValueError,match="environment fallback is disabled") as error:
        Settings(primary_provider="gemini",ai_provider_config_source="database",
            ai_provider_database_url="invalid-secret-url",gemini_api_key="legacy-secret")
    assert "legacy-secret" not in str(error.value) and "invalid-secret-url" not in str(error.value)
