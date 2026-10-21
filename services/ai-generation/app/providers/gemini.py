"""Google AI Studio Gemini provider using the OpenAI-compatible endpoint.

Gemini exposes an OpenAI chat-completions surface at
``https://generativelanguage.googleapis.com/v1beta/openai``. The provider keeps
the existing JSON-schema request contract and never logs the API key.
"""
from __future__ import annotations

import httpx

from .openai_compatible import OpenAiCompatibleProvider


class GeminiProvider(OpenAiCompatibleProvider):
    """Gemini model served through Google's OpenAI-compatible endpoint."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.3,
        client: httpx.AsyncClient,
        max_tokens: int = 2200,
    ) -> None:
        super().__init__(
            base_url=base_url,
            api_key=api_key,
            model=model,
            temperature=temperature,
            client=client,
            max_tokens=max_tokens,
            provider_name="gemini",
            chat_completions_path="/chat/completions",
            use_json_schema=True,
        )
