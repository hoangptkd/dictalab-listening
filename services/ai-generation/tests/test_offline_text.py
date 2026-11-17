import json
import httpx
import pytest
from app.config import Settings
from app.offline_text import run, environment_settings


def test_offline_capture_uses_shared_adapter_and_never_replays_unknown(tmp_path):
    settings = Settings(primary_provider="gemini", gemini_api_key="fake-only-key")
    calls = []
    def fail(request):
        calls.append(request)
        raise httpx.ReadTimeout("unknown after send")
    arguments = (settings, [{"role": "user", "content": "Return JSON"}], {"type": "object"})
    with pytest.raises(TimeoutError):
        run(*arguments, transport=httpx.MockTransport(fail), journal=tmp_path)
    with pytest.raises(ValueError, match="refusing replay"):
        run(*arguments, transport=httpx.MockTransport(fail), journal=tmp_path)
    assert len(calls) == 1
    marker = json.loads(next(tmp_path.glob("*.json")).read_text())
    assert marker["status"] == "UNKNOWN"
    assert "fake-only-key" not in json.dumps(marker)


def test_offline_request_and_redacted_envelope_remain_available_for_existing_journals():
    captured, responses = [], []
    def respond(request):
        assert json.loads(request.content)["max_tokens"] == 240
        return httpx.Response(200, json={"model": "fixture-model", "choices": [
            {"finish_reason": "stop", "message": {"content": '{"ok":true}'}}]})
    result = run(Settings(primary_provider="gemini", gemini_api_key="fake-only-key"),
        [{"role": "user", "content": "Return JSON"}], {"type": "object"}, max_tokens=240,
        transport=httpx.MockTransport(respond), on_request=captured.append, on_response=responses.append)
    assert result.result.value == {"ok": True}
    assert result.request == captured[0] and result.envelope == responses[0]
    assert "fake-only-key" not in repr(result)


def test_offline_configuration_preserves_owner_policy_and_env_aliases():
    settings = environment_settings({"GEMINI_API_KEY": "fake-only", "GEMINI_MODEL": "fixture-model"}, provider="gemini")
    assert settings.selected_provider == "gemini" and settings.active_cloud_model == "fixture-model"
    assert settings.operational_limits_enabled is False
