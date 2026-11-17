"""Provider-neutral OpenAI-compatible chat-completions client.

Gemini's OpenAI-compatible endpoint uses this request/response contract. The
client is only selected when the configured provider is a cloud provider.

API key is read from config and never written to logs or exception messages.
"""
from __future__ import annotations

import json
import logging
import re
import time
from typing import Any

import httpx
from jsonschema import Draft202012Validator, ValidationError

from ..providers.base import ModelProvider, ProviderError, ProviderHttpError

logger = logging.getLogger("dictalab.ai_generation.providers.openai_compatible")

_FENCE_RE = re.compile(r"```(?:json)?\s*([\s\S]*?)```", re.IGNORECASE)
_DEADLINE_BUFFER = 1.0


class OpenAiCompatibleProvider(ModelProvider):
    """OpenAI-compatible cloud provider used by Gemini."""

    inject_schema = True
    validate_schema = True

    @staticmethod
    def validate_endpoint(endpoint):
        from urllib.parse import urlsplit
        if not urlsplit(endpoint).path.endswith("/chat/completions"):
            raise ValueError("Provider endpoint must be a full chat completions URL")

    async def probe(self, timeout=3):
        response = await self._client.get(self._url.removesuffix("/chat/completions") + "/models",
            headers={"Authorization": "Bearer " + self._api_key}, timeout=timeout)
        response.raise_for_status()
        return response

    @staticmethod
    def model_ids(response):
        return [m.get("id", "") for m in response.json().get("data", []) if isinstance(m, dict)]

    @classmethod
    def gateway_options(cls, options, json_object):
        options.require_supported()
        return {}

    def configure_model_profile(self, profile, *, json_object=False):
        self.validate_model_profile(profile)
        self._profile = dict(profile)
        if not json_object and "schemaMode" in profile:
            self._use_json_schema = profile["schemaMode"] == "native"

    @staticmethod
    def validate_model_profile(profile):
        if (set(profile) - {"tokenField", "temperaturePolicy", "jsonModes", "schemaMode"} or
                profile.get("schemaMode", "prompt") not in {"native", "prompt"} or
                profile.get("tokenField", "max_tokens") not in {"max_tokens", "max_completion_tokens"} or
                profile.get("temperaturePolicy", "send") not in {"send", "omit"} or
                not isinstance(profile.get("jsonModes", ["json_object", "json_schema"]), list) or
                not set(profile.get("jsonModes", ["json_object", "json_schema"])) <= {"json_object", "json_schema"}):
            raise ValueError("invalid model capability profile")

    def server_rejection(self, response):
        return False

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.3,
        client: httpx.AsyncClient,
        max_tokens: int = 2200,
        provider_name: str = "openai_compatible",
        chat_completions_path: str | None = None,
        extra_headers: dict[str, str] | None = None,
        use_json_schema: bool = False,
        extra_body: dict[str, Any] | None = None,
        cache_stable_prefix: bool = False,
    ) -> None:
        base = base_url.rstrip("/")
        if chat_completions_path:
            self._url = (base if base.endswith("/chat/completions") else
                         base + "/chat/completions" if base.endswith("/v1") else
                         base + "/" + chat_completions_path.lstrip("/"))
        else:
            self._url = base if base.endswith("/chat/completions") else base + ("/chat/completions" if base.endswith("/v1") else "/v1/chat/completions")
        self._api_key = api_key   # never logged
        self._model = model
        self._temperature = temperature
        self._client = client
        self._max_tokens = max_tokens
        self.last_usage = {}
        self._provider_name = provider_name
        self._extra_headers = dict(extra_headers or {})
        self._use_json_schema = use_json_schema
        self._extra_body = dict(extra_body or {})
        if type(cache_stable_prefix) is not bool or cache_stable_prefix and provider_name != "deepseek":
            raise ValueError("cache prefix layout requires DeepSeek")
        self._cache_stable_prefix = cache_stable_prefix
        self._profile = {}
        self.reported_model = None

    @property
    def name(self) -> str:
        return self._provider_name

    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
    ) -> dict[str, Any]:
        self.last_usage = {}
        self.reported_model = None
        if not self._api_key:
            raise ProviderError(f"{self._provider_name}:no_api_key_configured")
        remaining = deadline - time.monotonic()
        if remaining < _DEADLINE_BUFFER:
            raise TimeoutError(f"{self._provider_name}: deadline exhausted before {purpose}")
        response_format = (
            {"type": "json_schema", "json_schema": {
                "name": purpose, "strict": True, "schema": schema,
            }}
            if self._use_json_schema else {"type": "json_object"}
        )
        body = {
            "model": self._model,
            "max_tokens": self._max_tokens,
            "temperature": temperature if temperature is not None else self._temperature,
            "messages": messages,
            "response_format": response_format,
            **self._extra_body,
        }
        mode = "json_schema" if self._use_json_schema else "json_object"
        if mode not in self._profile.get("jsonModes", ["json_object", "json_schema"]):
            raise ProviderError("UNSUPPORTED_CAPABILITY: " + mode)
        if self._profile.get("tokenField") == "max_completion_tokens":
            body["max_completion_tokens"] = body.pop("max_tokens")
        if self._profile.get("temperaturePolicy") == "omit":
            body.pop("temperature", None)
        if self.inject_schema and schema:
            schema_message = {"role": "system", "content": "Return one JSON object matching this JSON schema: " + json.dumps(schema)}
            # Per-request enum IDs must not precede the reusable instructions/payload prefix.
            body["messages"] = ([*messages, schema_message] if self._cache_stable_prefix else
                                [schema_message, *messages])
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
            **self._extra_headers,
        }
        try:
            timeout = httpx.Timeout(max(remaining - 0.5, 1.0))
            response = await self._client.post(
                self._url, json=body, headers=headers, timeout=timeout
            )
            if response.status_code == 429 or response.status_code >= 500:
                raise ProviderHttpError(response.status_code, response.headers.get("Retry-After"), self._provider_name,
                                        server_failure=self.server_rejection(response))
            if response.status_code in (400, 401, 403, 422):
                error = ProviderError(
                    f"{self._provider_name}:{purpose}:client_error:{response.status_code}"
                )
                # Preserve only the numeric status; response bodies can contain private request data.
                error.status = response.status_code
                raise error
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict):
                raise ProviderError("INVALID_OUTPUT: response envelope")
            # A provider may reflect its header credential in an error or response; never persist it.
            if self._api_key and self._api_key in json.dumps(data, ensure_ascii=False):
                data = json.loads(json.dumps(data, ensure_ascii=False).replace(self._api_key, "[redacted]"))
            reported = data.get("model")
            if isinstance(reported, str) and re.fullmatch(r"[a-zA-Z0-9_.:/-]{1,128}", reported):
                self.reported_model = reported
            usage = data.get("usage") or {}
            self.last_usage = {target: usage[name] for target, name in
                (("input_tokens", "prompt_tokens"), ("output_tokens", "completion_tokens"))
                if type(usage.get(name)) is int and 0 <= usage[name] < 2**63}
            choice = (data.get("choices") or [{}])[0]
            from ..observability import record_response
            usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
            details = usage.get("prompt_tokens_details") if isinstance(usage.get("prompt_tokens_details"), dict) else {}
            record_response((choice.get("message") or {}).get("content"), model=self.reported_model or self._model,
                input_tokens=usage.get("prompt_tokens"), output_tokens=usage.get("completion_tokens"),
                cached_tokens=details.get("cached_tokens", usage.get("prompt_cache_hit_tokens")),
                finish_reason=choice.get("finish_reason"),
                reasoning=(choice.get("message") or {}).get("reasoning_content"))
            if choice.get("finish_reason") == "length":
                raise ProviderError(f"{self._provider_name}:{purpose}:output_truncated")
            raw = (choice.get("message") or {}).get("content", "")
            parsed = _parse_content(raw, purpose, self._provider_name)
            if self.validate_schema and schema:
                try:
                    Draft202012Validator(schema).validate(parsed)
                except ValidationError:
                    raise ProviderError(f"{self._provider_name}:{purpose}:schema_mismatch") from None
            logger.info(
                f"{self._provider_name}_provider_complete",
                extra={"provider": self._provider_name, "purpose": purpose, "model": self._model},
            )
            return parsed
        except (httpx.TimeoutException, httpx.ConnectError) as exc:
            raise TimeoutError(f"{self._provider_name}:{purpose}:timeout") from exc
        except (httpx.HTTPStatusError, json.JSONDecodeError) as exc:
            raise ProviderError(f"{self._provider_name}:{purpose}:http_or_parse_error") from exc


def _parse_content(raw: str, purpose: str, provider_name: str = "openai_compatible") -> dict[str, Any]:
    """Strip optional markdown fences then strict-parse JSON."""
    if not isinstance(raw, str) or not raw.strip():
        raise ProviderError(f"{provider_name}:{purpose}:invalid_content")
    stripped = raw.strip()
    match = _FENCE_RE.search(stripped)
    if match:
        stripped = match.group(1).strip()
    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise ProviderError(f"{provider_name}:{purpose}:invalid_json") from exc
    if not isinstance(parsed, dict):
        raise ProviderError(f"{provider_name}:{purpose}:response_not_object")
    return parsed
