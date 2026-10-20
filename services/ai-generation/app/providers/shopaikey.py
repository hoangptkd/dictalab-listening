"""Shop AI cloud provider — OpenAI-compatible /v1/chat/completions.

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
    """OpenAI-compatible provider used as cloud fallback."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.3,
        client: httpx.AsyncClient,
    ) -> None:
        self._url = base_url.rstrip("/") + "/v1/chat/completions"
        self._api_key = api_key   # never logged
        self._model = model
        self._temperature = temperature
        self._client = client

    @property
    def name(self) -> str:
        return "shopaikey"

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
            raise ProviderError("shopaikey:no_api_key_configured")
        remaining = deadline - time.monotonic()
        if remaining < _DEADLINE_BUFFER:
            raise TimeoutError(f"ShopAiKeyProvider: deadline exhausted before {purpose}")
        body = {
            "model": self._model,
            "temperature": temperature if temperature is not None else self._temperature,
            "messages": messages,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        try:
            timeout = httpx.Timeout(max(remaining - 0.5, 1.0))
            response = await self._client.post(
                self._url, json=body, headers=headers, timeout=timeout
            )
            if response.status_code in (400, 401, 403, 422):
                raise ProviderError(
                    f"shopaikey:{purpose}:client_error:{response.status_code}"
                )
            response.raise_for_status()
            data = response.json()
            choice = (data.get("choices") or [{}])[0]
            if choice.get("finish_reason") == "length":
                raise ProviderError(f"shopaikey:{purpose}:output_truncated")
            raw = (choice.get("message") or {}).get("content", "")
            parsed = _parse_content(raw, purpose)
            logger.info(
                "shopaikey_provider_complete",
                extra={"purpose": purpose, "model": self._model},
            )
            return parsed
        except (httpx.TimeoutException, httpx.ConnectError) as exc:
            raise TimeoutError(f"shopaikey:{purpose}:timeout") from exc
        except (httpx.HTTPStatusError, json.JSONDecodeError) as exc:
            raise ProviderError(f"shopaikey:{purpose}:http_or_parse_error") from exc


def _parse_content(raw: str, purpose: str) -> dict[str, Any]:
    """Strip optional markdown fences then strict-parse JSON."""
    stripped = raw.strip()
    match = _FENCE_RE.search(stripped)
    if match:
        stripped = match.group(1).strip()
    try:
        parsed = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise ProviderError(f"shopaikey:{purpose}:invalid_json") from exc
    if not isinstance(parsed, dict):
        raise ProviderError(f"shopaikey:{purpose}:response_not_object")
    return parsed
