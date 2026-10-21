"""OpenAI-compatible cloud provider — /v1/chat/completions.

Used by Shop AI (primary cloud or fallback) and OpenCode Console. OpenCode
selects the free DeepSeek model through the same request/response contract.
Only used when DICTALAB_AI_FALLBACK_ENABLED=true and the local Ollama provider
experiences a timeout, connection failure, or model unavailability (5xx).
Never used for 4xx errors or idempotency conflicts.

API key is read from config and never written to logs or exception messages.
"""
from __future__ import annotations

import json
import logging
import re
import time
from typing import Any

import httpx

from ..providers.base import ModelProvider, ProviderError

logger = logging.getLogger("dictalab.ai_generation.providers.shopaikey")

_FENCE_RE = re.compile(r"```(?:json)?\s*([\s\S]*?)```", re.IGNORECASE)
_DEADLINE_BUFFER = 1.0


class ShopAiKeyProvider(ModelProvider):
    """OpenAI-compatible cloud provider used by Shop AI or OpenCode."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.3,
        client: httpx.AsyncClient,
        max_tokens: int = 2200,
        provider_name: str = "shopaikey",
        chat_completions_path: str | None = None,
        extra_headers: dict[str, str] | None = None,
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
        self._provider_name = provider_name
        self._extra_headers = dict(extra_headers or {})

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
        if not self._api_key:
            raise ProviderError(f"{self._provider_name}:no_api_key_configured")
        remaining = deadline - time.monotonic()
        if remaining < _DEADLINE_BUFFER:
            raise TimeoutError(f"{self._provider_name}: deadline exhausted before {purpose}")
        body = {
            "model": self._model,
            "max_tokens": self._max_tokens,
            "temperature": temperature if temperature is not None else self._temperature,
            "messages": messages,
            "response_format": {"type": "json_object"},
        }
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
            if response.status_code in (400, 401, 403, 422):
                raise ProviderError(
                    f"{self._provider_name}:{purpose}:client_error:{response.status_code}"
                )
            response.raise_for_status()
            data = response.json()
            choice = (data.get("choices") or [{}])[0]
            if choice.get("finish_reason") == "length":
                raise ProviderError(f"{self._provider_name}:{purpose}:output_truncated")
            raw = (choice.get("message") or {}).get("content", "")
            parsed = _parse_content(raw, purpose, self._provider_name)
            logger.info(
                f"{self._provider_name}_provider_complete",
                extra={"provider": self._provider_name, "purpose": purpose, "model": self._model},
            )
            return parsed
        except (httpx.TimeoutException, httpx.ConnectError) as exc:
            raise TimeoutError(f"{self._provider_name}:{purpose}:timeout") from exc
        except (httpx.HTTPStatusError, json.JSONDecodeError) as exc:
            raise ProviderError(f"{self._provider_name}:{purpose}:http_or_parse_error") from exc


def _parse_content(raw: str, purpose: str, provider_name: str = "shopaikey") -> dict[str, Any]:
    """Strip optional markdown fences then strict-parse JSON."""
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
