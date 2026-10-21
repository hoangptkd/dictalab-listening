"""T04 — Unit tests for the model gateway and provider implementations.

No real Ollama or Gemini connections are made.
"""
from __future__ import annotations

import json
import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import httpx

from app.config import Settings
from app.model_gateway import ModelGateway
from app.providers.base import ProviderError
from app.providers.ollama import OllamaProvider
from app.providers.gemini import GeminiProvider
from app.providers.openai_compatible import OpenAiCompatibleProvider, _parse_content


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
# OpenAiCompatibleProvider
# ---------------------------------------------------------------------------

class TestOpenAiCompatibleProvider:
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
        provider = OpenAiCompatibleProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="key123", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
        )
        result = await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")
        assert result == GOOD_RESULT
        headers = client.post.call_args.kwargs["headers"]
        assert headers["Authorization"] == "Bearer key123"
        assert headers["Content-Type"] == "application/json"

    @pytest.mark.asyncio
    async def test_no_api_key_raises_provider_error(self):
        client = MagicMock()
        provider = OpenAiCompatibleProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
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
        provider = OpenAiCompatibleProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="key", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
        )
        with pytest.raises(ProviderError, match="output_truncated"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_4xx_raises_provider_error(self):
        mock_response = MagicMock()
        mock_response.status_code = 400
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = OpenAiCompatibleProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="key", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
        )
        with pytest.raises(ProviderError, match="client_error:400"):
            await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "test")

    @pytest.mark.asyncio
    async def test_deadline_before_call_raises_timeout(self):
        client = MagicMock()
        provider = OpenAiCompatibleProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="key", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
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
# GeminiProvider
# ---------------------------------------------------------------------------

class TestGeminiProvider:
    @pytest.mark.asyncio
    async def test_uses_gemini_openai_compatible_endpoint_and_model(self):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps(GOOD_RESULT)}}]
        }
        mock_response.raise_for_status = MagicMock()
        client = MagicMock()
        client.post = AsyncMock(return_value=mock_response)
        provider = GeminiProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="gemini-key", model="gemini-3.5-flash-lite",
            temperature=0.3, client=client,
        )
        assert provider.name == "gemini"
        assert await provider.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content") == GOOD_RESULT
        assert client.post.call_args.args[0] == (
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        )
        body = client.post.call_args.kwargs["json"]
        assert body["model"] == "gemini-3.5-flash-lite"
        assert body["response_format"] == {
            "type": "json_schema",
            "json_schema": {"name": "content", "strict": True, "schema": SCHEMA},
        }
        headers = client.post.call_args.kwargs["headers"]
        assert headers["Authorization"] == "Bearer gemini-key"
        assert headers["Content-Type"] == "application/json"

    @pytest.mark.asyncio
    async def test_missing_key_reports_gemini_provider(self):
        provider = GeminiProvider(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="", model="gemini-3.5-flash-lite",
            temperature=0.3, client=MagicMock(),
        )
        with pytest.raises(ProviderError, match=r"gemini:no_api_key_configured"):
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
    async def test_explicit_gemini_calls_cloud_without_contacting_local(self):
        settings = _settings(primary_provider="gemini", gemini_api_key="gemini-key",
                             gemini_model="gemini-3.5-flash-lite")
        client = MagicMock()
        gw = ModelGateway(settings, client)
        assert isinstance(gw._primary_cloud, GeminiProvider)
        assert gw._primary_cloud.name == "gemini"
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=AssertionError("must not contact Ollama"))), \
             patch.object(gw._primary_cloud, "complete", new=AsyncMock(return_value=GOOD_RESULT)):
            result, provider = await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        assert result == GOOD_RESULT
        assert provider == "gemini"

    @pytest.mark.asyncio
    async def test_local_timeout_is_not_retried_on_cloud(self):
        settings = _settings()
        client = MagicMock()
        gw = ModelGateway(settings, client)
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=TimeoutError("t"))), \
             patch.object(gw._primary_cloud, "complete", new=AsyncMock(
                 side_effect=AssertionError("must not fall back to Gemini"))):
            with pytest.raises(TimeoutError):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")

    @pytest.mark.asyncio
    async def test_local_validation_error_propagates(self):
        settings = _settings()
        client = MagicMock()
        gw = ModelGateway(settings, client)
        non_transport_err = ProviderError("ollama:content:invalid_json")
        with patch.object(gw._local, "complete", new=AsyncMock(side_effect=non_transport_err)):
            with pytest.raises(ProviderError, match="invalid_json"):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")

    @pytest.mark.asyncio
    async def test_api_key_not_in_logged_metadata(self):
        settings = _settings(primary_provider="gemini", gemini_api_key="supersecretkey123")
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
            with patch.object(gw._primary_cloud, "complete", new=AsyncMock(return_value=GOOD_RESULT)):
                await gw.complete(MESSAGES, SCHEMA, FAR_DEADLINE, "content")
        finally:
            logging.getLogger("dictalab.ai_generation.model_gateway").removeHandler(handler)

        for record in log_records:
            assert "supersecretkey123" not in str(record.getMessage())
            assert "supersecretkey123" not in str(getattr(record, "reason", ""))
