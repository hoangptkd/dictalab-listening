"""Synchronous tools reuse the application gateway; journals remain with each tool."""
import asyncio
from dataclasses import dataclass, field
import json
import os
import time
from pathlib import Path
import hashlib

import httpx

from .config import Settings, CLOUD_PROVIDERS
from .model_gateway import ModelGateway
from .providers.base import ProviderResult


def environment_settings(values=None, *, provider=None, api_key=None, endpoint=None):
    values = dict(os.environ) if values is None else values
    arguments = {}
    for name, definition in Settings.model_fields.items():
        candidates = ["DICTALAB_AI_" + name.upper()]
        alias = definition.validation_alias
        if isinstance(alias, str):
            candidates.insert(0, alias)
        elif hasattr(alias, "choices"):
            candidates = list(alias.choices) + candidates
        for candidate in candidates:
            if candidate in values:
                value = values[candidate]
                if name in {"gemini_providers", "cloud_providers", "gemini_fallback_models", "model_profiles", "scenario_tts_recipes"} and isinstance(value, str):
                    value = json.loads(value)
                arguments[name] = value
                break
    aliases = {"GEMINI_API_KEY": "gemini_api_key", "GEMINI_MODEL": "gemini_model", "GEMINI_BASE_URL": "gemini_base_url"}
    for old, name in aliases.items():
        if old in values and name not in arguments:
            arguments[name] = values[old]
    if "GEMINI_URL" in values and "gemini_base_url" not in arguments:
        arguments["gemini_base_url"] = values["GEMINI_URL"].removesuffix("/chat/completions")
    arguments["operational_limits_enabled"] = values.get("DICTALAB_OPERATIONAL_LIMITS_ENABLED", "false")
    if provider is not None:
        arguments["primary_provider"] = provider
    if api_key is not None:
        arguments["gemini_api_key"] = api_key
    if endpoint is not None:
        arguments["gemini_base_url"] = endpoint.removesuffix("/chat/completions")
    return Settings(**arguments)


@dataclass(repr=False)
class OfflineResult:
    result: ProviderResult
    request: dict = field(repr=False)
    envelope: dict = field(repr=False)


async def complete(settings, messages, schema, *, model=None, purpose="offline_text", temperature=0,
                   max_tokens=2200, json_object=True, timeout=180, seed=None, context_tokens=None,
                   on_request=None, on_response=None, transport=None, journal=None, frozen_wire=None):
    selected = settings.current_registry().model_copy(deep=True)
    provider = selected.selected_provider
    if model:
        if provider in CLOUD_PROVIDERS:
            if selected.ai_provider_config_source == "database":
                if model not in {s.model for s in selected.sources_for(provider)}:
                    raise ValueError("requested model is not enabled in the provider registry")
                selected.registry_pools[provider]["default_model"] = model
            else:
                selected.gemini_model = model
        else:
            selected.model = model
    selected.num_predict = max_tokens
    if context_tokens is not None:
        selected.num_ctx = context_tokens
    selected._registry_snapshot = True
    wire, envelope = {}, {}
    marker = None
    secrets = [s.api_key for s in selected.enabled_cloud_sources]
    if selected.active_cloud_api_key:
        secrets.append(selected.active_cloud_api_key)

    class RecordedClient(httpx.AsyncClient):
        async def post(self, url, **kwargs):
            nonlocal wire, envelope, marker
            body = kwargs.get("json")
            if seed is not None and provider == "ollama":
                body["options"]["seed"] = seed
            if frozen_wire is not None:
                if body != frozen_wire:
                    raise ValueError("frozen benchmark wire differs from the shared adapter; start a new experiment")
                body = kwargs["json"] = frozen_wire  # Preserve property order of an already frozen benchmark.
            wire = body
            if journal is not None:
                identity = json.dumps({"provider": provider, "url": str(url), "request": body}, sort_keys=True, ensure_ascii=False)
                marker = Path(journal) / (hashlib.sha256(identity.encode()).hexdigest() + ".json")
                marker.parent.mkdir(parents=True, exist_ok=True)
                # Exclusive creation is the durable admission. Unknown outcomes require human review.
                try:
                    with marker.open("x", encoding="utf-8") as saved:
                        json.dump({"status": "UNKNOWN", "provider": provider, "model": selected.active_cloud_model
                            if provider in CLOUD_PROVIDERS else selected.model}, saved)
                except FileExistsError:
                    raise ValueError("offline request already admitted; refusing replay") from None
            if on_request:
                on_request(body)
            response = await super().post(url, **kwargs)
            if len(response.content) > 1_000_000:
                raise ValueError("provider response exceeds offline capture limit")
            if response.status_code == 200:
                serialized = response.text
                for secret in secrets:
                    serialized = serialized.replace(secret, "[redacted]")
                envelope = json.loads(serialized)
                if on_response:
                    on_response(envelope)
            return response

    async with RecordedClient(follow_redirects=False, trust_env=False, transport=transport) as client:
        gateway = ModelGateway(selected, client)
        value, _ = await gateway.complete(messages, schema, time.monotonic() + timeout, purpose,
            temperature=temperature, json_object=json_object, allow_model_fallback=False, allow_account_rotation=False)
    if marker is not None:
        # Do not store responses or private learner content in the admission marker.
        marker.write_text(json.dumps({"status": "RESPONSE_RECEIVED", "provider": provider,
            "model": gateway.last_model}), encoding="utf-8")
    return OfflineResult(gateway.last_result, wire, envelope)


def run(*args, **kwargs):
    return asyncio.run(complete(*args, **kwargs))


def run_internal(values, messages, *, model, purpose, max_tokens, timeout=25):
    """Operator probe through the same authenticated boundary as an isolated Core."""
    from datetime import datetime, timedelta, timezone
    from uuid import uuid4
    from urllib.parse import urlsplit
    token = values.get("DICTALAB_AI_SERVICE_TOKEN", "")
    base = values.get("DICTALAB_AI_PLATFORM_BASE_URL", "http://127.0.0.1:8091").rstrip("/")
    uri = urlsplit(base)
    if (not 32 <= len(token) <= 256 or any(not 33 <= ord(c) <= 126 for c in token)
            or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or uri.path not in ("", "/")
            or not (uri.scheme == "https" or uri.scheme == "http" and uri.hostname in ("localhost", "127.0.0.1", "::1"))):
        raise ValueError("invalid internal text configuration")
    identity = str(uuid4())
    provider = values.get("DICTALAB_AI_TEXT_PROVIDER", "gemini")
    body = {"schemaVersion": "2", "requestId": identity, "provider": provider, "model": model,
        "messages": messages, "purpose": purpose, "schema": {"type": "object"}, "outputMode": "json_object",
        "maxOutputTokens": max_tokens, "temperature": 0, "allowModelFallback": False,
        "deadline": (datetime.now(timezone.utc) + timedelta(seconds=timeout)).isoformat()}
    with httpx.Client(follow_redirects=False, timeout=timeout) as client:
        response = client.post(base + "/v2/text/completions", json=body, headers={"X-Service-Token": token})
        if response.status_code != 200 or len(response.content) > 100000:
            raise ValueError("internal text outcome rejected or unknown; no replay")
        result = response.json()
    if (result.get("schemaVersion") != "2" or result.get("requestId") != identity or result.get("provider") != provider
            or result.get("model") != model or not isinstance(result.get("task"), dict)):
        raise ValueError("internal text result provenance mismatch")
    return result["task"]
