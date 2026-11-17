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

    inject_schema = False
    validate_schema = False  # Existing workflows perform their frozen schema/domain validation.

    @classmethod
    def gateway_options(cls, options, json_object):
        options.require_supported()
        return {"use_json_schema": not json_object}

    def server_rejection(self, response):
        if response.status_code not in (500, 503):
            return False
        try:
            error = response.json()
            if isinstance(error, list) and len(error) == 1:
                error = error[0]
            detail = error.get("error", {}) if isinstance(error, dict) and set(error) == {"error"} else {}
            return (isinstance(detail, dict) and detail.get("code") == response.status_code and
                    detail.get("status") == {500: "INTERNAL", 503: "UNAVAILABLE"}[response.status_code])
        except ValueError:
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
        use_json_schema: bool = True,
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
            use_json_schema=use_json_schema,
        )
