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
from .providers.base import ModelProvider, GenerationOptions, ProviderResult
from .providers import get_provider
from .providers.base import ProviderHttpError
from .gemini_pool import QuotaUnavailable, quota_store, estimate_tokens, retry_seconds
from .observability import trace_generation

logger = logging.getLogger("dictalab.ai_generation.model_gateway")


def routing_metadata(settings):
    metadata = {}
    if settings.fallback_on_quota and settings.fallback_provider:
        metadata["providerRouting"] = {"primaryProvider": settings.selected_provider,
            "fallbackProvider": settings.fallback_provider, "fallbackOnQuota": True}
    if get_provider(settings.selected_provider).model_fallback and settings.gemini_fallback_models:
        metadata["modelRouting"] = {"primaryModel": settings.active_cloud_model,
            "fallbackModels": [m for m in settings.gemini_fallback_models if m != settings.active_cloud_model]}
    return metadata




class ModelGateway:
    """Selects providers; Gemini model fallback requires explicit caller opt-in."""

    @staticmethod
    async def local_evaluation(client, settings, messages, schema, variant, deadline):
        from .providers.model_serving import ModelServingProvider
        return await ModelServingProvider.complete(client, settings, messages, schema, variant, deadline)

    @staticmethod
    async def web_evaluation(client, endpoint, body):
        from .providers.web_judge import WebJudgeProvider
        return await WebJudgeProvider.send(client, endpoint, body)

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client
        self.last_model = settings.active_cloud_model if settings.selected_provider in CLOUD_PROVIDERS else settings.model
        self.last_source_id = None
        self.model_fallbacks = []
        self.provider_fallbacks = []
        self.observed_attempts = None
        self._snapshot_loaded = False
        self._local = get_provider("ollama").create(settings, client)
        self._primary_cloud = get_provider(settings.selected_provider if settings.selected_provider in CLOUD_PROVIDERS
                                           else "gemini").create(settings, client)
        self.last_result = None

    def continuation_settings(self, settings):
        if not self.model_fallbacks and not self.provider_fallbacks:
            return settings
        pools = {p: dict(policy) for p, policy in settings.registry_pools.items()}
        provider = (self.provider_fallbacks[-1]["toProvider"] if self.provider_fallbacks
                    else settings.selected_provider)
        if provider in pools:
            pools[provider]["default_model"] = self.last_model
        chain = settings.gemini_fallback_models
        update = {"registry_pools": pools, "primary_provider": provider,
            "gemini_fallback_models": chain[chain.index(self.last_model) + 1:]
                if self.last_model in chain else []}
        if self.provider_fallbacks:
            update.update(fallback_provider="", fallback_on_quota=False)
        if provider == "gemini":
            update["gemini_model"] = self.last_model
        result = settings.model_copy(update=update)
        result._registry_snapshot = True
        return result

    def _result(self, value, adapter):
        self.last_result = ProviderResult(value, adapter.name, self.last_model,
            getattr(adapter, "reported_model", None), dict(getattr(adapter, "last_usage", {})), self.last_source_id)
        return value, adapter.name

    @trace_generation
    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
        json_object: bool = False,
        allow_model_fallback: bool = False,
        options: GenerationOptions | None = None,
        allow_account_rotation: bool = True,
    ) -> tuple[dict[str, Any], str]:
        """Call the selected provider.

        Returns
        -------
        (result_dict, provider_name)
        """
        started = time.monotonic()
        self.model_fallbacks = []
        self.provider_fallbacks = []
        self.last_source_id = None
        self.last_result = None
        options = options or GenerationOptions()
        if not self._snapshot_loaded and self._settings.selected_provider in CLOUD_PROVIDERS:
            self._settings = await asyncio.wait_for(asyncio.to_thread(self._settings.current_registry),
                max(0, deadline - time.monotonic()))
            self._snapshot_loaded = True
            self.last_model = self._settings.active_cloud_model
        provider = self._settings.selected_provider
        registration = get_provider(provider)
        # Validate capabilities before account admission; adapters own vendor-specific options.
        registration.adapter.gateway_options(options, json_object) if registration.cloud else options.require_supported()
        if (options.reasoning or options.cache_stable_prefix) and not self._settings.gemini_quota_enabled:
            raise ValueError("reasoning/cache options require a registry account")
        if provider in CLOUD_PROVIDERS:
            async with asyncio.timeout(max(0, deadline - time.monotonic())):
                return await self._complete_cloud(messages, schema, deadline, purpose, temperature,
                    options, json_object, started,
                    allow_model_fallback, allow_account_rotation)
        async with asyncio.timeout(max(0, deadline - time.monotonic())):
            result = await self._local.complete(messages, schema, deadline, purpose, temperature=temperature)
        self._log(self._local.name, purpose, started)
        return self._result(result, self._local)

    async def _complete_cloud(self, messages, schema, deadline, purpose, temperature,
                              options, json_object, started,
                              allow_model_fallback, allow_account_rotation):
        from .provider_admin import record_attempt, finish_request
        primary_provider = self._settings.selected_provider
        provider = primary_provider
        registration = get_provider(provider)
        # ShopAIKey is a conversation fallback. Other workflows may still use
        # their existing Gemini model chain, but do not cross provider here.
        conversation_fallback = (purpose in {"content", "translation"}
                                 or purpose.startswith("conversation_")
                                 or purpose.startswith("demo_"))
        fallback_provider = (self._settings.fallback_provider
                             if conversation_fallback and (self._settings.fallback_on_quota
                                 or allow_model_fallback) else "")
        fallback_used = False
        attempted, request_id = set(), uuid4()
        model, preferred_project = self._settings.active_cloud_model, None
        fallbacks = [m for m in self._settings.gemini_fallback_models if m != model] if registration.model_fallback and allow_model_fallback else []
        if self._settings.gemini_quota_enabled:
            fallbacks = [m for m in fallbacks if any(s.model == m for s in self._settings.sources_for(provider))]
        last_rejection = None
        while True:
            if deadline - time.monotonic() < 1:
                if last_rejection:
                    await asyncio.to_thread(finish_request, self._settings, request_id)
                    raise last_rejection
                raise QuotaUnavailable(1)
            selected = (self._primary_cloud if provider == primary_provider
                        and model == self._settings.active_cloud_model and not json_object
                        and options == GenerationOptions() else registration.create(self._settings, self._client,
                            model=model, json_object=json_object, options=options))
            source = None
            store = None
            if self._settings.gemini_quota_enabled:
                if deadline - time.monotonic() < 1:
                    raise QuotaUnavailable(1)
                # Keep the one-argument primary path compatible with existing
                # training/evaluation fakes; alternate providers need an explicit pool.
                store = (quota_store(self._settings) if provider == primary_provider
                         else quota_store(self._settings, provider))
                try:
                    source = await asyncio.to_thread(store.select, model,
                        estimate_tokens(messages, self._settings.num_predict, schema), attempted,
                        **({"preferred_project": preferred_project} if preferred_project else {}))
                except QuotaUnavailable as unavailable:
                    # Admission failed before an HTTP request. A configured fallback
                    # may be used; timeouts and unknown outcomes never reach here.
                    if (provider == primary_provider and fallback_provider and not fallback_used
                            and self._settings.sources_for(fallback_provider)):
                        fallback_used = True
                        self.provider_fallbacks.append({"fromProvider": primary_provider,
                            "toProvider": fallback_provider, "reason": "ADMISSION_OR_QUOTA"})
                        provider = fallback_provider
                        registration = get_provider(provider)
                        model = self._settings.active_cloud_model_for(provider)
                        fallbacks = []
                        attempted, preferred_project = set(), None
                        last_rejection = None
                        continue
                    if last_rejection:
                        await asyncio.to_thread(finish_request, self._settings, request_id)
                        raise last_rejection
                    raise
                attempted.add(source.id)
                selected = registration.create(self._settings, self._client, source=source,
                    json_object=json_object, options=options)
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
                if source and allow_account_rotation and registration.rotate_on_429 and error.status == 429:
                    remaining = [s for s in self._settings.sources_for(provider)
                        if s.model == source.model and s.id not in attempted]
                    if remaining and deadline - time.monotonic() >= 1:
                        last_rejection, done = error, False
                        continue
                if (provider == primary_provider and fallback_provider and not fallback_used
                        and error.status == 429 and deadline - time.monotonic() >= 1):
                    fallback_used = True
                    self.provider_fallbacks.append({"fromProvider": primary_provider,
                        "toProvider": fallback_provider, "reason": "HTTP_429"})
                    provider = fallback_provider
                    registration = get_provider(provider)
                    model = self._settings.active_cloud_model_for(provider)
                    fallbacks = []
                    attempted, preferred_project = set(), None
                    last_rejection, done = error, False
                    continue
                if (registration.model_fallback and error.server_failure and fallbacks
                        and deadline - time.monotonic() >= 1):
                    replacement = fallbacks.pop(0)
                    self.model_fallbacks.append({"fromModel": model, "toModel": replacement, "httpStatus": error.status})
                    logger.warning("gemini_model_fallback purpose=%s from_model=%s to_model=%s http_status=%s",
                                   purpose, model, replacement, error.status)
                    model, preferred_project = replacement, source.project_id if source else None
                    last_rejection, done = error, False
                    continue
                if (provider == primary_provider and fallback_provider and not fallback_used
                        and error.server_failure and allow_model_fallback and conversation_fallback
                        and deadline - time.monotonic() >= 1):
                    fallback_used = True
                    self.provider_fallbacks.append({"fromProvider": primary_provider,
                        "toProvider": fallback_provider, "reason": "MODEL_FALLBACK_EXHAUSTED"})
                    provider = fallback_provider
                    registration = get_provider(provider)
                    model = self._settings.active_cloud_model_for(provider)
                    fallbacks = []
                    attempted, preferred_project = set(), None
                    last_rejection, done = error, False
                    continue
                raise
            except (TimeoutError, httpx.TransportError):
                if source:
                    await asyncio.to_thread(store.report, source, "failure")
                raise
            finally:
                if self.observed_attempts is not None:
                    self.observed_attempts.append({"source": source.id if source else "configured",
                        "model": self.last_model, "outcome": outcome,
                        "seconds": time.monotonic() - attempt_started,
                        **getattr(selected, "last_usage", {})})
                if source:
                    await asyncio.to_thread(record_attempt, self._settings, request_id, source, outcome, done,
                        getattr(selected, "last_usage", {}), time.monotonic() - attempt_started)
            if source:
                await asyncio.to_thread(store.report, source, "success")
            self._log(provider, purpose, started)
            return self._result(result, selected)

    def _log(self, provider: str, purpose: str, started: float) -> None:
        logger.info(
            "model_gateway_complete",
            extra={
                "provider": provider,
                "purpose": purpose,
                "duration_ms": round((time.monotonic() - started) * 1000),
            },
        )
