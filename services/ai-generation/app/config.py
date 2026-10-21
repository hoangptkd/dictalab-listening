from pydantic_settings import BaseSettings, SettingsConfigDict
from pathlib import Path


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DICTALAB_AI_")

    model: str = "qwen3.5:9b"
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_service_token: str = ""
    temperature: float = 0.3
    translation_temperature: float = 0.1
    num_ctx: int = 8192
    num_predict: int = 1000
    timeout_seconds: float = 180.0
    service_token: str = ""
    prompt_version: str = "CONVERSATION_GENERATION_V6_SLOTS"
    model_digest: str = "unknown"
    registry_dir: str = str(Path(__file__).resolve().parents[2].parent / "artifacts" / "prompts")
    artifact_version: str = "conversation-platform-r1-20261020"
    artifact_sha256: str = ""
    max_concurrency: int = 1
    primary_provider: str = "auto"
    conversation_enabled: bool = True
    cloud_base_url: str = "https://api.shopaikey.com"
    cloud_api_key: str = ""
    cloud_model: str = "gemini-2.5-flash"
    jobs_enabled: bool = False
    jobs_database: str = "data/ai-jobs.sqlite3"
    rabbitmq_url: str = "amqp://guest:guest@127.0.0.1:5672/%2F"
    job_retention_seconds: int = 900
    cartesia_url: str = "https://api.cartesia.ai/tts/bytes"
    cartesia_api_key: str = ""
    cartesia_model: str = "sonic-3.6"
    cartesia_voice: str = "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4"
    cartesia_version: str = "2026-08-14"
    http_max_connections: int = 10

    # Fallback provider (Shop AI cloud) — off by default
    fallback_enabled: bool = False
    fallback_base_url: str = "https://api.shopaikey.com"
    fallback_api_key: str = ""
    fallback_model: str = "gpt-4o-mini"

    # Production mode: when true, service_token is mandatory and startup
    # fails if it is empty or contains unsafe characters.
    production_mode: bool = False

    # Idempotency cache TTL in seconds (default 15 minutes)
    cache_ttl_seconds: float = 900.0

    def __init__(self, **values):
        super().__init__(**values)
        if self.primary_provider not in ("auto", "ollama", "shopaikey"):
            raise ValueError("unsupported primary_provider")
        if not 60 <= self.job_retention_seconds <= 86400:
            raise ValueError("job retention must be between 60 and 86400 seconds")
        if not 1 <= self.http_max_connections <= 100:
            raise ValueError("invalid HTTP pool limit")
        from urllib.parse import urlsplit
        for value, active in ((self.cloud_base_url, self.selected_provider == "shopaikey"),
                (self.fallback_base_url, self.fallback_enabled), (self.ollama_url, self.selected_provider == "ollama"),
                (self.cartesia_url, bool(self.cartesia_api_key))):
            uri = urlsplit(value)
            if uri.scheme not in ("http", "https") or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment:
                raise ValueError("invalid provider URL")
            if self.production_mode and active and uri.scheme != "https" and uri.hostname not in ("localhost", "127.0.0.1", "ollama"):
                raise ValueError("remote production providers require TLS")
        if self.production_mode and self.selected_provider == "ollama":
            uri = urlsplit(self.ollama_url)
            if uri.hostname not in ("localhost", "127.0.0.1", "ollama") and not self.ollama_service_token:
                raise ValueError("remote Ollama requires a service token")
        if not 0 <= self.temperature <= 2:
            raise ValueError("temperature must be between 0 and 2")
        if not 0 <= self.translation_temperature <= 2:
            raise ValueError("translation_temperature must be between 0 and 2")
        if self.num_ctx < 512 or self.num_predict < 1:
            raise ValueError("invalid Ollama context or output limit")
        if self.timeout_seconds <= 0 or self.timeout_seconds > 600:
            raise ValueError("timeout_seconds must be between 0 and 600")
        if self.max_concurrency < 1 or self.max_concurrency > 5:
            raise ValueError("max_concurrency must be between 1 and 5")
        if "\n" in self.service_token or "\r" in self.service_token:
            raise ValueError("service_token must not contain newline or carriage return")
        if self.production_mode and not self.service_token:
            raise ValueError(
                "DICTALAB_AI_SERVICE_TOKEN must be set in production mode; "
                "set DICTALAB_AI_PRODUCTION_MODE=false for local development"
            )
        if self.cache_ttl_seconds <= 0 or self.cache_ttl_seconds > 86400:
            raise ValueError("cache_ttl_seconds must be between 1 and 86400")

    @property
    def selected_provider(self) -> str:
        if self.primary_provider != "auto":
            return self.primary_provider
        return "shopaikey" if self.production_mode else "ollama"
