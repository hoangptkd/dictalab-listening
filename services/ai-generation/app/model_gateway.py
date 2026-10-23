"""Model gateway — selects the configured provider.

Primary: local Ollama in development, Gemini in production, or an explicit
provider. There is no automatic cloud fallback; a failed local call fails the
task so the caller can retry under its own policy.

The gateway writes provider name and call duration to every log entry. API keys
are never written to logs.
"""
from __future__ import annotations

import logging
import time
import asyncio
from typing import Any

import httpx

from .config import CLOUD_PROVIDERS, Settings
from .providers.base import ModelProvider
from .providers.ollama import OllamaProvider
from .providers.gemini import GeminiProvider
from .providers.openai_compatible import OpenAiCompatibleProvider
from .providers.base import ProviderHttpError
from .gemini_pool import QuotaUnavailable, quota_store, estimate_tokens, retry_seconds

logger = logging.getLogger("dictalab.ai_generation.model_gateway")


class ModelGateway:
    """Selects local vs. cloud provider; never falls back automatically."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client
        self.last_model = settings.active_cloud_model if settings.selected_provider in CLOUD_PROVIDERS else settings.model
        self.last_source_id = None
        self._local = OllamaProvider(
            ollama_url=settings.ollama_url,
            model=settings.model,
            temperature=settings.temperature,
            num_ctx=settings.num_ctx,
            num_predict=settings.num_predict,
            client=client,
            service_token=settings.ollama_service_token,
        )
        self._primary_cloud: ModelProvider = GeminiProvider(
            base_url=settings.active_cloud_base_url,
            api_key=settings.active_cloud_api_key,
            model=settings.active_cloud_model,
            temperature=settings.temperature,
            client=client,
            max_tokens=settings.num_predict,
        )

    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
        deepseek_thinking: bool = False,
    ) -> tuple[dict[str, Any], str]:
        """Call the selected provider.

        Returns
        -------
        (result_dict, provider_name)
        """
        started = time.monotonic()
        provider = self._settings.selected_provider
        if deepseek_thinking and (provider != "deepseek" or not self._settings.gemini_quota_enabled):
            raise ValueError("thinking opt-in requires the DeepSeek registry provider")
        if provider in CLOUD_PROVIDERS:
            selected = self._primary_cloud
            source = None
            store = None
            if self._settings.gemini_quota_enabled:
                if deadline - time.monotonic() < 1:
                    raise QuotaUnavailable(1)
                store = quota_store(self._settings)
                source = await asyncio.to_thread(store.select, self._settings.active_cloud_model,
                    estimate_tokens(messages, self._settings.num_predict, schema))
                common = dict(base_url=source.url, api_key=source.api_key, model=source.model,
                    temperature=self._settings.temperature, client=self._client, max_tokens=self._settings.num_predict)
                selected = GeminiProvider(**common) if source.platform == "gemini" else OpenAiCompatibleProvider(**common,
                    provider_name=source.platform, extra_body={"thinking": {
                        "type": "enabled" if deepseek_thinking else "disabled"}} if source.platform == "deepseek" else {})
                self.last_model, self.last_source_id = source.model, source.id
            try:
                result = await selected.complete(messages, schema, deadline, purpose, temperature=temperature)
            except ProviderHttpError as error:
                if source:
                    await asyncio.to_thread(store.report, source, "429" if error.status == 429 else "failure",
                        retry_seconds(error.retry_after))
                raise
            except (TimeoutError, httpx.TransportError):
                if source:
                    await asyncio.to_thread(store.report, source, "failure")
                raise
            if source:
                await asyncio.to_thread(store.report, source, "success")
            self._log(provider, purpose, started)
            return result, provider
        result = await self._local.complete(messages, schema, deadline, purpose, temperature=temperature)
        self._log(self._local.name, purpose, started)
        return result, self._local.name

    def _log(self, provider: str, purpose: str, started: float) -> None:
        logger.info(
            "model_gateway_complete",
            extra={
                "provider": provider,
                "purpose": purpose,
                "duration_ms": round((time.monotonic() - started) * 1000),
            },
        )
