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
from typing import Any

import httpx

from .config import CLOUD_PROVIDERS, Settings
from .providers.base import ModelProvider
from .providers.ollama import OllamaProvider
from .providers.gemini import GeminiProvider

logger = logging.getLogger("dictalab.ai_generation.model_gateway")


class ModelGateway:
    """Selects local vs. cloud provider; never falls back automatically."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
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
    ) -> tuple[dict[str, Any], str]:
        """Call the selected provider.

        Returns
        -------
        (result_dict, provider_name)
        """
        started = time.monotonic()
        provider = self._settings.selected_provider
        if provider in CLOUD_PROVIDERS:
            result = await self._primary_cloud.complete(messages, schema, deadline, purpose, temperature=temperature)
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
