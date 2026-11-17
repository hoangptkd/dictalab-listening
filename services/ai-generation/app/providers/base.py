"""Abstract ModelProvider interface.

Each concrete provider implements ``complete(messages, schema, deadline, purpose)``
and returns a parsed dict. The model gateway selects and calls the right provider;
workflow nodes never import a provider directly.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class GenerationOptions:
    reasoning: bool = False
    reasoning_effort: str | None = None
    cache_stable_prefix: bool = False

    def require_supported(self, *, reasoning=False, cache=False):
        if (type(self.reasoning) is not bool or type(self.cache_stable_prefix) is not bool or
                (self.reasoning or self.reasoning_effort is not None) and not reasoning or
                self.cache_stable_prefix and not cache):
            raise ValueError("UNSUPPORTED_CAPABILITY: DeepSeek registry thinking or cache prefix options")


@dataclass(frozen=True)
class ProviderResult:
    value: dict[str, Any] = field(repr=False)
    provider: str
    model: str
    reported_model: str | None
    usage: dict[str, int]
    source_id: str | None = None


class ModelProvider(abc.ABC):
    """Interface every provider must implement."""

    @property
    def endpoint(self):
        return self._url

    @abc.abstractmethod
    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
        *,
        temperature: float | None = None,
    ) -> dict[str, Any]:
        """Post a structured-output request and return a parsed dict.

        Parameters
        ----------
        messages:
            OpenAI-style [{"role": "system"|"user", "content": "..."}] list.
        schema:
            JSON Schema for the expected response object.
        deadline:
            Monotonic timestamp by which the call must return.
        purpose:
            Short label for logging (e.g. "content", "translation").

        Raises
        ------
        TimeoutError
            If the remote call exceeds the deadline.
        ProviderError
            For 4xx/5xx or parse failures from the remote.
        """

    @property
    @abc.abstractmethod
    def name(self) -> str:
        """Short provider identifier written to logs and metadata."""


class ProviderError(RuntimeError):
    """Raised by a provider for unrecoverable errors (non-timeout failures)."""


class ProviderHttpError(ProviderError):
    def __init__(self, status, retry_after=None, provider_name="gemini", *, server_failure=False):
        super().__init__(f"{provider_name}:http_error:{status}")
        self.status = status
        self.retry_after = retry_after
        self.server_failure = server_failure
