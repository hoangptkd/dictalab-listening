# providers package - Ollama local plus Gemini cloud implementations.
"""Explicit registrations. Adding an adapter never changes a business workflow."""
from dataclasses import dataclass
import re
from urllib.parse import urlsplit

from .base import GenerationOptions, ProviderError
from .gemini import GeminiProvider
from .deepseek import DeepSeekProvider
from .openai_compatible import OpenAiCompatibleProvider
from .shopaikey import ShopAiKeyProvider
from .ollama import OllamaProvider

PROVIDERS = {}
CLOUD_PROVIDERS = set()


@dataclass(frozen=True)
class Registration:
    adapter: type
    cloud: bool = True
    expected_host: str | None = None
    rotate_on_429: bool = False
    model_fallback: bool = False

    def normalize_model(self, model):
        return model.removeprefix("models/") if self.expected_host == "generativelanguage.googleapis.com" else model

    def validate_endpoint(self, endpoint):
        uri = urlsplit(endpoint)
        if (not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or
                not (uri.scheme == "https" or uri.scheme == "http" and uri.hostname in ("localhost", "127.0.0.1", "::1"))):
            raise ValueError("provider endpoint requires TLS except loopback")
        self.adapter.validate_endpoint(endpoint)
        if self.expected_host and uri.hostname not in (self.expected_host, "localhost", "127.0.0.1", "::1"):
            raise ValueError("Provider endpoint does not match platform")

    def create(self, settings, client, *, source=None, model=None, json_object=False, options=None):
        options = options or GenerationOptions()
        if not self.cloud:
            options.require_supported(reasoning=False, cache=False)
            return self.adapter(ollama_url=settings.ollama_url, model=model or settings.model,
                temperature=settings.temperature, num_ctx=settings.num_ctx, num_predict=settings.num_predict,
                client=client, service_token=settings.ollama_service_token)
        common = dict(base_url=source.url if source else settings.active_cloud_base_url,
            api_key=source.api_key if source else settings.active_cloud_api_key,
            model=source.model if source else model or settings.active_cloud_model,
            temperature=settings.temperature, max_tokens=settings.num_predict, client=client)
        adapter = self.adapter(**common, **self.adapter.gateway_options(options, json_object))
        if PROVIDERS.get(adapter.name) is not self:
            raise ValueError("provider registration identity mismatch")
        self.validate_endpoint(adapter.endpoint)
        adapter.configure_model_profile(settings.model_profiles.get(adapter.name + "/" + common["model"], {}), json_object=json_object)
        return adapter

    async def probe(self, settings, client, *, source=None, timeout=3):
        return await self.create(settings, client, source=source).probe(timeout)

    def model_matches(self, response, model):
        return any(self.normalize_model(model) == self.normalize_model(value)
                   for value in self.adapter.model_ids(response) if isinstance(value, str))


def register(name, registration):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,31}", name) or name in PROVIDERS:
        raise ValueError("invalid or duplicate provider registration")
    PROVIDERS[name] = registration
    if registration.cloud:
        CLOUD_PROVIDERS.add(name)


def get_provider(name):
    try:
        return PROVIDERS[name]
    except KeyError:
        raise ProviderError("UNREGISTERED_PROVIDER") from None


register("gemini", Registration(GeminiProvider, expected_host="generativelanguage.googleapis.com",
                                rotate_on_429=True, model_fallback=True))
register("deepseek", Registration(DeepSeekProvider, expected_host="api.deepseek.com"))
register("openai_compatible", Registration(OpenAiCompatibleProvider))
# Historical ShopAIKey used the same OpenAI chat-completions wire contract.
# Keep it as a named registration so routing and audit metadata remain explicit.
register("shopaikey", Registration(ShopAiKeyProvider))
register("ollama", Registration(OllamaProvider, cloud=False))
