from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DICTALAB_AI_")

    model: str = "qwen3.5:9b"
    ollama_url: str = "http://127.0.0.1:11434"
    temperature: float = 0.3
    translation_temperature: float = 0.1
    num_ctx: int = 8192
    num_predict: int = 1000
    timeout_seconds: float = 180.0
    service_token: str = ""
    prompt_version: str = "CONVERSATION_GENERATION_V6_SLOTS"
    model_digest: str = "unknown"
    max_concurrency: int = 1

    def __init__(self, **values):
        super().__init__(**values)
        if not 0 <= self.temperature <= 2:
            raise ValueError("temperature must be between 0 and 2")
        if not 0 <= self.translation_temperature <= 2:
            raise ValueError("translation_temperature must be between 0 and 2")
        if self.num_ctx < 512 or self.num_predict < 1:
            raise ValueError("invalid Ollama context or output limit")
        if self.timeout_seconds <= 0 or self.timeout_seconds > 600:
            raise ValueError("timeout_seconds must be between 0 and 600")
        if self.max_concurrency < 1 or self.max_concurrency > 32:
            raise ValueError("max_concurrency must be between 1 and 32")
