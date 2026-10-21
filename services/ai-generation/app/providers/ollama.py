"""Ollama local provider — wraps /api/chat with structured JSON output.

Uses the existing low-level _chat() helper from ollama.py so the structured
request format stays in one place. This module is the thin adapter that
satisfies the ModelProvider interface.
"""
from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ..providers.base import ModelProvider, ProviderError

logger = logging.getLogger("dictalab.ai_generation.providers.ollama")

_DEADLINE_BUFFER = 1.0  # seconds headroom before we attempt the call


class OllamaProvider(ModelProvider):
    """Calls Ollama /api/chat with structured JSON output (format= field)."""

    def __init__(
        self,
        *,
        ollama_url: str,
        model: str,
        temperature: float = 0.3,
        num_ctx: int = 8192,
        num_predict: int = 1000,
        client: httpx.AsyncClient,
        service_token: str = "",
    ) -> None:
        self._url = ollama_url.rstrip("/") + "/api/chat"
        self._model = model
        self._temperature = temperature
        self._num_ctx = num_ctx
        self._num_predict = num_predict
        self._client = client
        self._headers = {"X-Service-Token": service_token} if service_token else {}

    @property
    def name(self) -> str:
        return "ollama"

    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
    ) -> dict[str, Any]:
        remaining = deadline - time.monotonic()
        if remaining < _DEADLINE_BUFFER:
            raise TimeoutError(
                f"OllamaProvider: deadline exhausted before {purpose}"
            )
        body = {
            "model": self._model,
            "stream": False,
            "think": False,
            "format": schema,
            "keep_alive": "5m",
            "options": {
                "temperature": temperature if temperature is not None else self._temperature,
                "num_ctx": self._num_ctx,
                "num_predict": self._num_predict,
            },
            "messages": messages,
        }
        try:
            timeout = httpx.Timeout(max(remaining - 0.5, 1.0))
            response = await self._client.post(self._url, json=body, timeout=timeout, headers=self._headers)
            response.raise_for_status()
            envelope = response.json()
            content = envelope.get("message", {}).get("content")
            if (
                envelope.get("done") is not True
                or envelope.get("done_reason") != "stop"
                or not isinstance(content, str)
            ):
                raise ProviderError(f"ollama:{purpose}:incomplete_response")
            parsed = json.loads(content)
            if not isinstance(parsed, dict):
                raise ProviderError(f"ollama:{purpose}:response_not_object")
            logger.info(
                "ollama_provider_complete",
                extra={"purpose": purpose, "model": self._model},
            )
            return parsed
        except (httpx.TimeoutException, httpx.ConnectError) as exc:
            raise TimeoutError(f"ollama:{purpose}:timeout") from exc
        except httpx.HTTPStatusError as exc:
            code = exc.response.status_code
            category = "5xx" if code >= 500 else "client_error"
            raise ProviderError(f"ollama:{purpose}:{category}:{code}") from exc
        except (httpx.HTTPError, json.JSONDecodeError) as exc:
            raise ProviderError(f"ollama:{purpose}:http_or_parse_error") from exc
