"""Model gateway — selects provider and handles controlled fallback.

Primary: Ollama locally, Shop AI in production, or an explicit cloud provider
(Shop AI or OpenCode).
Fallback: Shop AI cloud — only when:
  - DICTALAB_AI_FALLBACK_ENABLED=true, AND
  - local provider raises TimeoutError or ProviderError with a 5xx-like cause
    (connect failure, model unavailable).

Never falls back for:
  - Invalid input (400-style ProviderError from local).
  - Schema/content errors caused by the prompt.
  - Idempotency conflicts.

The gateway writes provider name, fallback reason, and call duration to every
log entry. API keys are never written to logs.
"""
from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from .config import CLOUD_PROVIDERS, Settings
from .providers.base import ModelProvider, ProviderError
from .providers.ollama import OllamaProvider
from .providers.opencode import OpenCodeProvider
from .providers.shopaikey import ShopAiKeyProvider

logger = logging.getLogger("dictalab.ai_generation.model_gateway")


class ModelGateway:
    """Selects local vs. cloud provider and applies the fallback policy."""

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
        self._cloud: ModelProvider | None = None
        if settings.fallback_enabled and settings.fallback_api_key:
            self._cloud = ShopAiKeyProvider(
                base_url=settings.fallback_base_url,
                api_key=settings.fallback_api_key,
                model=settings.fallback_model,
                max_tokens=settings.num_predict,
                temperature=settings.temperature,
                client=client,
            )
        cloud_provider = OpenCodeProvider if settings.selected_provider == "opencode" else ShopAiKeyProvider
        self._primary_cloud = cloud_provider(
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
        """Call the primary provider; fall back to cloud only on transport failures.

        Returns
        -------
        (result_dict, provider_name)
        """
        started = time.monotonic()
        provider = self._settings.selected_provider
        if provider in CLOUD_PROVIDERS:
            result = await self._primary_cloud.complete(messages, schema, deadline, purpose, temperature=temperature)
            self._log(provider, purpose, started, fallback=False)
            return result, provider
        try:
            result = await self._local.complete(
                messages, schema, deadline, purpose, temperature=temperature
            )
            self._log("local", purpose, started, fallback=False)
            return result, self._local.name
        except TimeoutError as exc:
            return await self._try_fallback(
                messages, schema, deadline, purpose, temperature,
                started, reason="local_timeout", failure=exc
            )
        except ProviderError as exc:
            err = str(exc)
            # Only fall back for transport-level errors, not prompt/schema failures
            if _is_transport_error(err):
                return await self._try_fallback(
                    messages, schema, deadline, purpose, temperature,
                    started, reason="local_transport_error", failure=exc
                )
            raise  # re-raise prompt/schema errors

    async def _try_fallback(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        temperature: float | None,
        started: float,
        reason: str,
        failure: Exception,
    ) -> tuple[dict[str, Any], str]:
        if not self._settings.fallback_enabled or self._cloud is None:
            self._log("local", purpose, started, fallback=False, error=reason)
            raise failure
        if not self._settings.fallback_api_key:
            raise ProviderError("fallback_enabled but no api key configured")
        logger.warning(
            "model_gateway_fallback",
            extra={"purpose": purpose, "reason": reason},
        )
        result = await self._cloud.complete(
            messages, schema, deadline, purpose, temperature=temperature
        )
        self._log("cloud", purpose, started, fallback=True, reason=reason)
        return result, self._cloud.name

    def _log(
        self,
        provider: str,
        purpose: str,
        started: float,
        *,
        fallback: bool,
        error: str = "",
        reason: str = "",
    ) -> None:
        logger.info(
            "model_gateway_complete",
            extra={
                "provider": provider,
                "purpose": purpose,
                "duration_ms": round((time.monotonic() - started) * 1000),
                "fallback": fallback,
                "fallback_reason": reason,
                "error": error,
            },
        )


def _is_transport_error(error_str: str) -> bool:
    """Return True only for connection/timeout/5xx-style errors from the local provider."""
    transport_keywords = ("timeout", "connect", "unavailable", "5xx")
    return any(kw in error_str.lower() for kw in transport_keywords)
