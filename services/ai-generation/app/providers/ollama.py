"""Ollama /api/chat adapter; business workflows supply messages, schema and budget."""
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

    @staticmethod
    def validate_endpoint(endpoint):
        pass  # Registration validates URL security; Ollama config additionally restricts remote access.

    async def probe(self, timeout=3):
        response = await self._client.get(self._url.removesuffix("/api/chat") + "/api/tags",
            headers=self._headers, timeout=timeout)
        response.raise_for_status()
        return response

    @staticmethod
    def model_ids(response):
        return [m.get("name", "") for m in response.json().get("models", []) if isinstance(m, dict)]

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
        self.last_usage = {}
        self.reported_model = None
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
            if not isinstance(envelope, dict):
                raise ProviderError("ollama:invalid_envelope")
            reported = envelope.get("model")
            if isinstance(reported, str) and len(reported) <= 128:
                self.reported_model = reported
            self.last_usage = {target: envelope[field] for target, field in (
                ("input_tokens", "prompt_eval_count"), ("output_tokens", "eval_count"))
                if type(envelope.get(field)) is int and 0 <= envelope[field] < 2**63}
            content = envelope.get("message", {}).get("content")
            from ..observability import record_response
            record_response(content, model=envelope.get("model") or self._model,
                input_tokens=envelope.get("prompt_eval_count"), output_tokens=envelope.get("eval_count"),
                finish_reason=envelope.get("done_reason"))
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
