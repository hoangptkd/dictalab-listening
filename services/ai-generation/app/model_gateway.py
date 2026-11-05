"""Model gateway — selects the configured provider.

Primary: local Ollama in development, Gemini in production, or an explicit
provider. Scenario jobs may opt into configured Gemini model fallback after an
explicit server rejection. Unknown outcomes and local failures are never replayed.

The gateway writes provider name and call duration to every log entry. API keys
are never written to logs.
"""
from __future__ import annotations

import logging
import time
import asyncio
from uuid import uuid4
from typing import Any

import httpx

from .config import CLOUD_PROVIDERS, Settings
from .providers.base import ModelProvider
from .providers.ollama import OllamaProvider
from .providers.gemini import GeminiProvider
from .providers.openai_compatible import OpenAiCompatibleProvider
from .providers.base import ProviderHttpError
from .gemini_pool import QuotaUnavailable, quota_store, estimate_tokens, retry_seconds
from .observability import trace_generation

logger = logging.getLogger("dictalab.ai_generation.model_gateway")


class ModelGateway:
    """Selects providers; Gemini model fallback requires explicit caller opt-in."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client
        self.last_model = settings.active_cloud_model if settings.selected_provider in CLOUD_PROVIDERS else settings.model
        self.last_source_id = None
        self.model_fallbacks = []
        self._snapshot_loaded = False
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

    @trace_generation
    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
        deepseek_thinking: bool = False,
        deepseek_reasoning_effort: str | None = None,
        deepseek_cache_prefix: bool = False,
        json_object: bool = False,
        allow_model_fallback: bool = False,
    ) -> tuple[dict[str, Any], str]:
        """Call the selected provider.

        Returns
        -------
        (result_dict, provider_name)
        """
        started = time.monotonic()
        self.model_fallbacks = []
        self.last_source_id = None
        if not self._snapshot_loaded and self._settings.selected_provider in CLOUD_PROVIDERS:
            self._settings = await asyncio.wait_for(asyncio.to_thread(self._settings.current_registry),
                max(0, deadline - time.monotonic()))
            self._snapshot_loaded = True
            self.last_model = self._settings.active_cloud_model
        provider = self._settings.selected_provider
        if deepseek_thinking and (provider != "deepseek" or not self._settings.gemini_quota_enabled):
            raise ValueError("thinking opt-in requires the DeepSeek registry provider")
        if (type(deepseek_cache_prefix) is not bool or deepseek_cache_prefix and
                (provider != "deepseek" or not self._settings.gemini_quota_enabled)):
            raise ValueError("cache prefix layout requires the DeepSeek registry provider")
        if deepseek_reasoning_effort is not None and (
                not deepseek_thinking or deepseek_reasoning_effort not in {"low", "high", "max"}):
            raise ValueError("reasoning effort requires DeepSeek thinking and low/high/max")
        if provider in CLOUD_PROVIDERS:
            async with asyncio.timeout(max(0, deadline - time.monotonic())):
                return await self._complete_cloud(messages, schema, deadline, purpose, temperature,
                    deepseek_thinking, deepseek_reasoning_effort, deepseek_cache_prefix, json_object, started,
                    allow_model_fallback)
        result = await self._local.complete(messages, schema, deadline, purpose, temperature=temperature)
        self._log(self._local.name, purpose, started)
        return result, self._local.name

    async def _complete_cloud(self, messages, schema, deadline, purpose, temperature,
                              deepseek_thinking, deepseek_reasoning_effort, deepseek_cache_prefix, json_object, started,
                              allow_model_fallback):
        from .provider_admin import record_attempt, finish_request
        provider = self._settings.selected_provider
        attempted, request_id = set(), uuid4()
        model, preferred_project = self._settings.active_cloud_model, None
        fallbacks = [m for m in self._settings.gemini_fallback_models if m != model] if provider == "gemini" and allow_model_fallback else []
        if self._settings.gemini_quota_enabled:
            fallbacks = [m for m in fallbacks if any(s.model == m for s in self._settings.sources_for(provider))]
        last_rejection = None
        while True:
            if deadline - time.monotonic() < 1:
                if last_rejection:
                    await asyncio.to_thread(finish_request, self._settings, request_id)
                    raise last_rejection
                raise QuotaUnavailable(1)
            selected = self._primary_cloud
            if provider == "gemini" and model != self._settings.active_cloud_model:
                selected = GeminiProvider(base_url=self._settings.active_cloud_base_url,
                    api_key=self._settings.active_cloud_api_key, model=model,
                    temperature=self._settings.temperature, client=self._client, max_tokens=self._settings.num_predict)
            if json_object:
                selected = OpenAiCompatibleProvider(base_url=self._settings.active_cloud_base_url,
                    api_key=self._settings.active_cloud_api_key, model=model,
                    temperature=self._settings.temperature, client=self._client,
                    max_tokens=self._settings.num_predict, provider_name=provider,
                    chat_completions_path="/chat/completions" if provider == "gemini" else None)
            source = None
            store = None
            if self._settings.gemini_quota_enabled:
                if deadline - time.monotonic() < 1:
                    raise QuotaUnavailable(1)
                store = quota_store(self._settings)
                try:
                    source = await asyncio.to_thread(store.select, model,
                        estimate_tokens(messages, self._settings.num_predict, schema), attempted,
                        **({"preferred_project": preferred_project} if preferred_project else {}))
                except QuotaUnavailable:
                    if last_rejection:
                        await asyncio.to_thread(finish_request, self._settings, request_id)
                        raise last_rejection
                    raise
                attempted.add(source.id)
                common = dict(base_url=source.url, api_key=source.api_key, model=source.model,
                    temperature=self._settings.temperature, client=self._client, max_tokens=self._settings.num_predict)
                extra = {"thinking": {"type": "enabled" if deepseek_thinking else "disabled"}} if source.platform == "deepseek" else {}
                if deepseek_reasoning_effort is not None:
                    if source.platform != "deepseek":
                        raise ValueError("reasoning effort requires a DeepSeek source")
                    extra["reasoning_effort"] = deepseek_reasoning_effort
                selected = (GeminiProvider(**common) if source.platform == "gemini" and not json_object else
                    OpenAiCompatibleProvider(**common, provider_name=source.platform, extra_body=extra,
                        chat_completions_path="/chat/completions" if source.platform == "gemini" else None,
                        cache_stable_prefix=deepseek_cache_prefix))
                self.last_model, self.last_source_id = source.model, source.id
            else:
                self.last_model = model
            attempt_started = time.monotonic()
            outcome, done = "failure", True
            try:
                result = await selected.complete(messages, schema, deadline, purpose, temperature=temperature)
                outcome = "success"
            except ProviderHttpError as error:
                outcome = "429" if error.status == 429 else "failure"
                if source:
                    await asyncio.to_thread(store.report, source, outcome,
                        retry_seconds(error.retry_after))
                if source and provider == "gemini" and error.status == 429:
                    remaining = [s for s in self._settings.sources_for(provider)
                        if s.model == source.model and s.id not in attempted]
                    if remaining and deadline - time.monotonic() >= 1:
                        last_rejection, done = error, False
                        continue
                if (provider == "gemini" and error.server_failure and fallbacks
                        and deadline - time.monotonic() >= 1):
                    replacement = fallbacks.pop(0)
                    self.model_fallbacks.append({"fromModel": model, "toModel": replacement, "httpStatus": error.status})
                    logger.warning("gemini_model_fallback purpose=%s from_model=%s to_model=%s http_status=%s",
                                   purpose, model, replacement, error.status)
                    model, preferred_project = replacement, source.project_id if source else None
                    last_rejection, done = error, False
                    continue
                raise
            except (TimeoutError, httpx.TransportError):
                if source:
                    await asyncio.to_thread(store.report, source, "failure")
                raise
            finally:
                if source:
                    await asyncio.to_thread(record_attempt, self._settings, request_id, source, outcome, done,
                        getattr(selected, "last_usage", {}), time.monotonic() - attempt_started)
            if source:
                await asyncio.to_thread(store.report, source, "success")
            self._log(provider, purpose, started)
            return result, provider

    def _log(self, provider: str, purpose: str, started: float) -> None:
        logger.info(
            "model_gateway_complete",
            extra={
                "provider": provider,
                "purpose": purpose,
                "duration_ms": round((time.monotonic() - started) * 1000),
            },
        )
