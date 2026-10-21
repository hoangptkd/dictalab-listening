"""Abstract ModelProvider interface.

Each concrete provider implements ``complete(messages, schema, deadline, purpose)``
and returns a parsed dict. The model gateway selects and calls the right provider;
workflow nodes never import a provider directly.
"""
from __future__ import annotations

import abc
from typing import Any


class ModelProvider(abc.ABC):
    """Interface every provider must implement."""

    @abc.abstractmethod
    async def complete(
        self,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        deadline: float,
        purpose: str,
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
