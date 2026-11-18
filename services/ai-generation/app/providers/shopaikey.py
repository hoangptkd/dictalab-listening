"""ShopAIKey's historical OpenAI-compatible chat-completions transport."""
from .openai_compatible import OpenAiCompatibleProvider


class ShopAiKeyProvider(OpenAiCompatibleProvider):
    def __init__(self, **kwargs):
        super().__init__(provider_name="shopaikey", **kwargs)
