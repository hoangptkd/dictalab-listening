"""T04 — Unit tests for the model gateway and provider implementations.

No real Ollama or cloud connections are made.
"""
from __future__ import annotations

import json
import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import httpx

from app.config import Settings
from app.model_gateway import ModelGateway, _is_transport_error
from app.providers.base import ProviderError
from app.providers.ollama import OllamaProvider
from app.providers.opencode import OpenCodeProvider
from app.providers.shopaikey import ShopAiKeyProvider, _parse_content


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SCHEMA: dict[str, Any] = {"type": "object", "properties": {}}
MESSAGES = [{"role": "user", "content": "test"}]
GOOD_RESULT = {"utteranceEn": "hello"}
FAR_DEADLINE = time.monotonic() + 300.0
PAST_DEADLINE = time.monotonic() - 1.0


def _settings(**overrides) -> Settings:
    base = dict(
        fallback_enabled=False,
        fallback_api_key="",
        fallback_base_url="https://api.shopai.vn",
        fallback_model="gpt-4o-mini",
        ollama_url="http://127.0.0.1:11434",
        model="qwen3.5:9b",
        temperature=0.3,
        translation_temperature=0.1,
        timeout_seconds=60.0,
        max_concurrency=1,
    )
    base.update(overrides)
    return Settings(**base)


# ---------------------------------------------------------------------------
# OllamaProvider
# ---------------------------------------------------------------------------

class TestOllamaProvider:
    @pytest.mark.asyncio
    async def test_success_returns_parsed_dict(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "done": True,
            "done_reason": "stop",
            "message": {"content": json.dumps(GOOD_RESULT)},
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = OllamaProvider(
            ollama_url="http://localhost:11434", model="q3.5",
            temperature=0.3, num_ctx=512, num_predict=100, client=client
        )
        result = await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")
        assert result == GOOD_RESULT

    @pytest.mark.asyncio
    async def test_timeout_raises_timeout_error(self):
        client = MagicMock()
        client.post = AsyncMock(side_effect=httpx.TimeoutException("t"))
        provider = OllamaProvider(
            ollama_url="http://localhost:11434", model="q3.5",
            temperature=0.3, num_ctx=512, num_predict=100, client=client
        )
        with pytest.raises(TimeoutError):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_deadline_before_call_raises_timeout(self):
        client = MagicMock()
        provider = OllamaProvider(
            ollama_url="http://localhost:11434", model="q3.5",
            temperature=0.3, num_ctx=512, num_predict=100, client=client
        )
        with pytest.raises(TimeoutError, match="deadline exhausted"):
            await provider.complete(MESSAGES, SCHEMA, PAST_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_incomplete_response_raises_provider_error(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {"done": False, "message": {"content": "{}"}}
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = OllamaProvider(
            ollama_url="http://localhost:11434", model="q3.5",
            temperature=0.3, num_ctx=512, num_predict=100, client=client
        )
        with pytest.raises(ProviderError, match="incomplete_response"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")


# ---------------------------------------------------------------------------
# ShopAiKeyProvider
# ---------------------------------------------------------------------------

class TestShopAiKeyProvider:
    @pytest.mark.asyncio
    async def test_success_returns_parsed_dict(self):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(GOOD_RESULT)}}]
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = ShopAiKeyProvider(
            base_url="https://api.shopai.vn", api_key="key123",
            model="gpt-4o-mini", temperature=0.3, client=client
        )
        result = await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")
        assert result == GOOD_RESULT
        # API key must NOT appear in any logged call args
        call_kwargs = client.post.call_args.kwargs or {}
        call_headers = (call_kwargs.get("headers") or {})
        assert "key123" not in json.dumps(call_headers).replace("Bearer key123", "")

    @pytest.mark.asyncio
    async def test_no_api_key_raises_provider_error(self):
        client = MagicMock()
        provider = ShopAiKeyProvider(
            base_url="https://api.shopai.vn", api_key="",
            model="gpt-4o-mini", temperature=0.3, client=client
        )
        with pytest.raises(ProviderError, match="no_api_key"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_finish_reason_length_raises_provider_error(self):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "choices": [{"finish_reason": "length", "message": {"content": "{}"}}]
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = ShopAiKeyProvider(
            base_url="https://api.shopai.vn", api_key="key",
            model="gpt-4o-mini", temperature=0.3, client=client
        )
        with pytest.raises(ProviderError, match="output_truncated"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_4xx_raises_provider_error_no_fallback(self):
        mock_response = MagicMock()
        mock_response.status_code = 400
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = ShopAiKeyProvider(
            base_url="https://api.shopai.vn", api_key="key",
            model="gpt-4o-mini", temperature=0.3, client=client
        )
        with pytest.raises(ProviderError, match="client_error:400"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_deadline_before_call_raises_timeout(self):
        client = MagicMock()
        provider = ShopAiKeyProvider(
            base_url="https://api.shopai.vn", api_key="key",
            model="gpt-4o-mini", temperature=0.3, client=client
        )
        with pytest.raises(TimeoutError):
            await provider.complete(MESSAGES, SCHEMA, PAST_DEADLINE, "test")

    def test_parse_content_strips_markdown_fence(self):
        raw = '```json\n{"key": "value"}\n```'
        assert _parse_content(raw, "test") == {"key": "value"}

    def test_parse_content_strict_no_fence(self):
        raw = '{"key": "value"}'
        assert _parse_content(raw, "test") == {"key": "value"}

    def test_parse_content_invalid_json_raises(self):
        with pytest.raises(ProviderError, match="invalid_json"):
            _parse_content("not json", "test")


# ---------------------------------------------------------------------------
# OpenCodeProvider
# ---------------------------------------------------------------------------

class TestOpenCodeProvider:
    @pytest.mark.asyncio
    async def test_uses_opencode_chat_completions_endpoint_and_model(self):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(GOOD_RESULT)}}]
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = OpenCodeProvider(
            base_url="https://opencode.ai/inference", api_key="oc-key",
            model="deepseek-v4.1-flash-free", temperature=0.3, client=client
        )
        assert provider.name == "opencode"
        assert await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content") == GOOD_RESULT
        assert client.post.call_args.args[0] == "https://opencode.ai/inference/openai/v1/chat/completions"
        assert client.post.call_args.kwargs["json"]["model"] == "deepseek-v4.1-flash-free"
        headers = client.post.call_args.kwargs["headers"]
        assert headers["User-Agent"] == "dictalab-ai-generation/1.0"
        assert headers["x-opencode-session"].startswith("ses_")

    @pytest.mark.asyncio
    async def test_go_base_url_uses_go_chat_endpoint(self):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(GOOD_RESULT)}}]
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = OpenCodeProvider(
            base_url="https://opencode.ai/inference/go", api_key="oc-key",
            model="deepseek-v4.1-flash", temperature=0.3, client=client
        )
        await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        assert client.post.call_args.args[0] == "https://opencode.ai/inference/go/openai/v1/chat/completions"

    @pytest.mark.asyncio
    async def test_missing_key_reports_opencode_provider(self):
        provider = OpenCodeProvider(
            base_url="https://opencode.ai/inference", api_key="",
            model="deepseek-v4.1-flash-free", temperature=0.3, client=MagicMock()
        )
        with pytest.raises(ProviderError, match=r"opencode:no_api_key_configured"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")


# ---------------------------------------------------------------------------
# ModelGateway
# ---------------------------------------------------------------------------

class TestModelGateway:
    @pytest.mark.asyncio
    async def test_local_success_returns_result_and_provider_name(self):
        settings = _settings()
        client = MagicMock()
        gw = ModelGateway(settings, client)
        with patch.object(gw._local, "complete", new=AsyncMock(return_value=GOOD_RESULT)):
            result, provider = await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        assert result == GOOD_RESULT
        assert provider == "ollama"

    @pytest.mark.asyncio
    async def test_explicit_opencode_calls_cloud_without_contacting_local(self):
        settings = _settings(primary_provider="opencode", opencode_api_key="oc-key",
                             opencode_model="deepseek-v4.1-flash-free")
        client = MagicMock()
        gw = ModelGateway(settings, client)
        assert isinstance(gw._primary_cloud, OpenCodeProvider)
        assert gw._primary_cloud.name == "opencode"
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=AssertionError("must not contact Ollama"))), \
             patch.object(gw._primary_cloud, "complete", new=AsyncMock(return_value=GOOD_RESULT)):
            result, provider = await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        assert result == GOOD_RESULT
        assert provider == "opencode"

    @pytest.mark.asyncio
    async def test_local_timeout_fallback_disabled_raises_timeout(self):
        settings = _settings(fallback_enabled=False)
        client = MagicMock()
        gw = ModelGateway(settings, client)
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=TimeoutError("t"))):
            with pytest.raises(TimeoutError):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")

    @pytest.mark.asyncio
    async def test_local_timeout_fallback_enabled_calls_cloud_once(self):
        settings = _settings(fallback_enabled=True, fallback_api_key="cloudkey")
        client = MagicMock()
        gw = ModelGateway(settings, client)
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=TimeoutError("t"))), \
             patch.object(gw._cloud, "complete", new=AsyncMock(return_value=GOOD_RESULT)) as cloud_mock:
            result, provider = await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        assert result == GOOD_RESULT
        assert provider == "shopaikey"
        cloud_mock.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_local_4xx_error_does_not_fallback(self):
        """Non-transport errors from local must not trigger fallback."""
        settings = _settings(fallback_enabled=True, fallback_api_key="cloudkey")
        client = MagicMock()
        gw = ModelGateway(settings, client)
        non_transport_err = ProviderError("ollama:content:invalid_json")
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=non_transport_err)):
            with pytest.raises(ProviderError, match="invalid_json"):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")

    @pytest.mark.asyncio
    async def test_api_key_not_in_logged_metadata(self):
        """Verify gateway logs never expose the fallback API key."""
        settings = _settings(fallback_enabled=True, fallback_api_key="supersecretkey123")
        client = MagicMock()
        gw = ModelGateway(settings, client)
        import logging
        log_records: list[logging.LogRecord] = []

        class Capture(logging.Handler):
            def emit(self, record):
                log_records.append(record)

        handler = Capture()
        logging.getLogger("dictalab.ai_generation.model_gateway").addHandler(handler)
        try:
            with patch.object(gw._local, "complete", new=AsyncMock(side_effect=TimeoutError("t"))), \
                 patch.object(gw._cloud, "complete", new=AsyncMock(return_value=GOOD_RESULT)):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        finally:
            logging.getLogger("dictalab.ai_generation.model_gateway").removeHandler(handler)

        for record in log_records:
            assert "supersecretkey123" not in str(record.getMessage())
            assert "supersecretkey123" not in str(getattr(record, "reason", ""))

    def test_transport_error_detection(self):
        assert _is_transport_error("ollama:content:timeout") is True
        assert _is_transport_error("ollama:content:connect_error") is True
        assert _is_transport_error("ollama:content:incomplete_response") is False
        assert _is_transport_error("ollama:content:invalid_json") is False
        assert _is_transport_error("ollama:content:response_not_object") is False
