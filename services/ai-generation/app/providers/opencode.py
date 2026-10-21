"""OpenCode Console provider — OpenAI-compatible chat completions.

OpenCode Console exposes different endpoints per model family. This provider is
only for models served through the OpenAI chat-completions endpoint, such as
DeepSeek V4.1 Flash Free. Claude Messages, GPT Responses and Gemini endpoints
need a different adapter.
"""
from __future__ import annotations

from uuid import uuid4

import httpx

from .shopaikey import ShopAiKeyProvider


class OpenCodeProvider(ShopAiKeyProvider):
    """OpenCode Console model using the OpenAI chat-completions contract."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.3,
        client: httpx.AsyncClient,
        max_tokens: int = 2200,
        session_id: str | None = None,
    ) -> None:
        super().__init__(
            base_url=base_url,
            api_key=api_key,
            model=model,
            temperature=temperature,
            client=client,
            max_tokens=max_tokens,
            provider_name="opencode",
            chat_completions_path="/openai/v1/chat/completions",
            extra_headers={
                "User-Agent": "dictalab-ai-generation/1.0",
                "x-opencode-session": session_id or "ses_" + uuid4().hex,
            },
        )
