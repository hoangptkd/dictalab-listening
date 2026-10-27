from pydantic_settings import BaseSettings, SettingsConfigDict
from pathlib import Path
from pydantic import BaseModel, ConfigDict, Field, AliasChoices, model_validator
from pydantic.alias_generators import to_camel
import re
from urllib.parse import urlsplit

CLOUD_PROVIDERS = ("gemini", "deepseek", "openai_compatible")


class GeminiSource(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid", hide_input_in_errors=True)
    id: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,64}$")
    platform: str = "gemini"
    project_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,100}$")
    api_key: str = Field(repr=False, min_length=1, max_length=512)
    model: str = "gemini-3.5-flash-lite"
    url: str = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    rpm_limit: int = Field(default=15, gt=0)
    tpm_limit: int = Field(default=0, ge=0)
    rpd_limit: int = Field(default=0, ge=0)
    operating_rpm: int = Field(default=13, ge=0)
    enabled: bool = False
    priority: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def valid_source(self):
        if self.platform not in CLOUD_PROVIDERS:
            raise ValueError("unsupported cloud platform")
        self.model = self.model.removeprefix("models/")
        uri = urlsplit(self.url)
        if (not re.fullmatch(r"[a-zA-Z0-9_.-]{1,100}", self.model) or
                any(ord(c) < 33 or ord(c) > 126 for c in self.api_key)):
            raise ValueError("invalid Gemini model or key")
        if (not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or
                not (uri.scheme == "https" or uri.scheme == "http" and uri.hostname in ("localhost", "127.0.0.1", "::1"))):
            raise ValueError("Gemini provider URL requires TLS except loopback")
        if self.operating_rpm > self.rpm_limit:
            raise ValueError("operating RPM exceeds provider RPM")
        return self

    @property
    def effective_rpm(self):
        return self.operating_rpm or self.rpm_limit


def cloud_model_id_matches(configured: str, reported: str) -> bool:
    """Google lists model IDs with a ``models/`` prefix; chat accepts both forms."""
    return configured.removeprefix("models/") == reported.removeprefix("models/")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="DICTALAB_AI_", populate_by_name=True, hide_input_in_errors=True)

    model: str = "qwen3.5:9b"
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_service_token: str = ""
    temperature: float = 0.3
    translation_temperature: float = 0.1
    num_ctx: int = 8192
    num_predict: int = 1000
    timeout_seconds: float = 180.0
    repair_enabled: bool = False
    service_token: str = ""
    prompt_version: str = "CONVERSATION_GENERATION_V7_B1_EVIDENCE"
    model_digest: str = "unknown"
    registry_dir: str = str(Path(__file__).resolve().parents[2].parent / "artifacts" / "prompts")
    artifact_version: str = "conversation-cefr-b1-v7-20261021"
    artifact_sha256: str = ""
    max_concurrency: int = 1
    operational_limits_enabled: bool = Field(default=True,
        validation_alias="DICTALAB_OPERATIONAL_LIMITS_ENABLED")
    primary_provider: str = "auto"
    conversation_enabled: bool = True
    gemini_base_url: str = "https://generativelanguage.googleapis.com/v1beta/openai"
    gemini_api_key: str = Field(default="", repr=False)
    gemini_model: str = "gemini-3.5-flash-lite"
    deepseek_model: str = "deepseek-flash"
    ai_provider_config_source: str = Field(default="env", validation_alias="AI_PROVIDER_CONFIG_SOURCE")
    ai_provider_database_url: str = Field(default="", repr=False, validation_alias="AI_PROVIDER_DATABASE_URL")
    ai_provider_encryption_key: str = Field(default="", repr=False, validation_alias="AI_PROVIDER_ENCRYPTION_KEY")
    cloud_providers: list[GeminiSource] = Field(default_factory=list, repr=False)
    registry_pools: dict = Field(default_factory=dict, repr=False, exclude=True)
    gemini_providers: list[GeminiSource] = Field(default_factory=list, repr=False,
        validation_alias=AliasChoices("GEMINI_PROVIDERS_JSON", "DICTALAB_AI_GEMINI_PROVIDERS"))
    gemini_quota_enabled: bool = Field(default=False,
        validation_alias=AliasChoices("GEMINI_QUOTA_ENABLED", "DICTALAB_AI_GEMINI_QUOTA_ENABLED"))
    gemini_quota_backend: str = Field(default="redis",
        validation_alias=AliasChoices("GEMINI_QUOTA_BACKEND", "DICTALAB_AI_GEMINI_QUOTA_BACKEND"))
    gemini_global_rpm_limit: int = Field(default=39, ge=0,
        validation_alias=AliasChoices("GEMINI_GLOBAL_RPM_LIMIT", "DICTALAB_AI_GEMINI_GLOBAL_RPM_LIMIT"))
    gemini_global_rpd_limit: int = Field(default=0, ge=0,
        validation_alias=AliasChoices("GEMINI_GLOBAL_RPD_LIMIT", "DICTALAB_AI_GEMINI_GLOBAL_RPD_LIMIT"))
    gemini_quota_redis_url: str = Field(default="redis://127.0.0.1:16379/0", repr=False,
        validation_alias=AliasChoices("GEMINI_QUOTA_REDIS_URL", "DICTALAB_AI_GEMINI_QUOTA_REDIS_URL"))
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
    model_demo_enabled: bool = False
    model_serving_url: str = "http://127.0.0.1:8093"
    model_serving_token: str = Field(default="", repr=False)
    # Conservative admission reservations, not provider invoices. Zero disables cloud demo.
    demo_daily_budget_microusd: int = Field(default=0, ge=0)
    demo_call_reservation_microusd: int = Field(default=0, ge=0)
    demo_daily_cloud_calls: int = Field(default=0, ge=0)

    # Production mode: when true, service_token is mandatory and startup
    # fails if it is empty or contains unsafe characters.
    production_mode: bool = False

    # Idempotency cache TTL in seconds (default 15 minutes)
    cache_ttl_seconds: float = 900.0

    def __init__(self, **values):
        super().__init__(**values)
        if self.ai_provider_config_source not in ("env", "database"):
            raise ValueError("invalid provider configuration source")
        if self.ai_provider_config_source == "database":
            from .provider_registry import load_registry
            self.registry_pools, self.cloud_providers = load_registry(self.ai_provider_database_url, self.ai_provider_encryption_key)
            self.gemini_providers = [p for p in self.cloud_providers if p.platform == "gemini"]
            if "gemini" in self.registry_pools:
                policy = self.registry_pools["gemini"]
                self.gemini_model = policy["default_model"]
                self.gemini_global_rpm_limit, self.gemini_global_rpd_limit = policy["global_rpm_limit"], policy["global_rpd_limit"]
            if "deepseek" in self.registry_pools:
                self.deepseek_model = self.registry_pools["deepseek"]["default_model"]
            self.gemini_quota_enabled = True
        if self.gemini_quota_backend not in ("redis", "memory"):
            raise ValueError("invalid Gemini quota backend")
        quota_uri = urlsplit(self.gemini_quota_redis_url)
        if quota_uri.scheme not in ("redis", "rediss") or not quota_uri.hostname or quota_uri.fragment:
            raise ValueError("invalid Gemini quota Redis URL")
        ids, quotas = set(), set()
        for source in (self.cloud_providers if self.ai_provider_config_source == "database" else self.gemini_providers):
            if self.ai_provider_config_source == "env" and source.platform != "gemini":
                raise ValueError("non-Gemini accounts require database configuration")
            if source.id in ids:
                raise ValueError("duplicate Gemini provider ID")
            ids.add(source.id)
            key = (source.platform, source.project_id, source.model)
            if source.enabled and key in quotas:
                raise ValueError("duplicate Gemini project/model quota")
            if source.enabled:
                quotas.add(key)
        if self.gemini_quota_enabled and not self.enabled_cloud_sources:
            raise ValueError("Gemini quota requires an enabled provider")
        if self.production_mode and self.gemini_quota_enabled and self.gemini_quota_backend != "redis":
            raise ValueError("production Gemini quota requires Redis")
        if self.primary_provider not in ("auto", "ollama", *CLOUD_PROVIDERS):
            raise ValueError("unsupported primary_provider")
        if self.selected_provider in CLOUD_PROVIDERS and self.ai_provider_config_source == "database" and not self.sources_for(self.selected_provider):
            raise ValueError("selected platform has no enabled database account")
        if self.selected_provider != "gemini" and self.selected_provider in CLOUD_PROVIDERS and self.ai_provider_config_source != "database":
            raise ValueError("non-Gemini cloud providers require database configuration")
        if not 60 <= self.job_retention_seconds <= 86400:
            raise ValueError("job retention must be between 60 and 86400 seconds")
        if self.http_max_connections < 0:
            raise ValueError("invalid HTTP pool limit")
        if self.model_demo_enabled:
            uri = urlsplit(self.model_serving_url)
            if (uri.scheme != "http" or uri.hostname not in ("127.0.0.1", "localhost", "::1")
                    or uri.username or uri.password or uri.query or uri.fragment or uri.path not in ("", "/")
                    or not 32 <= len(self.service_token) <= 256
                    or not all(33 <= ord(c) <= 126 for c in self.service_token)
                    or not 32 <= len(self.model_serving_token) <= 256
                    or not all(33 <= ord(c) <= 126 for c in self.model_serving_token)):
                raise ValueError("CPU Model Serving requires loopback and a strong service token")
        for value, active in ((self.gemini_base_url, self.selected_provider == "gemini"),
                (self.ollama_url, self.selected_provider == "ollama"),
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
        if self.max_concurrency < 0:
            raise ValueError("max_concurrency must be nonnegative; zero means unlimited")
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
    def effective_max_concurrency(self):
        return self.max_concurrency if self.operational_limits_enabled else 0

    @property
    def effective_http_max_connections(self):
        return (self.http_max_connections or None) if self.operational_limits_enabled else None

    @property
    def enabled_gemini_sources(self):
        return sorted((p for p in self.gemini_providers if p.enabled), key=lambda p: (p.priority, p.id))

    @property
    def enabled_cloud_sources(self):
        return sorted((p for p in (self.cloud_providers if self.ai_provider_config_source == "database" else self.gemini_providers)
                       if p.enabled), key=lambda p: (p.priority, p.id))

    def sources_for(self, platform):
        return [p for p in self.enabled_cloud_sources if p.platform == platform]

    @property
    def selected_provider(self) -> str:
        if self.primary_provider != "auto":
            return self.primary_provider
        return "gemini" if self.production_mode else "ollama"

    @property
    def active_cloud_base_url(self) -> str:
        if self.selected_provider != "gemini" and self.sources_for(self.selected_provider):
            return self.sources_for(self.selected_provider)[0].url.removesuffix("/chat/completions")
        return self.gemini_base_url

    @property
    def active_cloud_api_key(self) -> str:
        if self.ai_provider_config_source == "database":
            return ""  # Account keys are selected only after admission, never a legacy fallback.
        return self.gemini_api_key

    @property
    def active_cloud_model(self) -> str:
        if self.selected_provider in self.registry_pools:
            return self.registry_pools[self.selected_provider]["default_model"]
        return self.gemini_model

    @property
    def active_cloud_models_url(self) -> str:
        return self.active_cloud_base_url.rstrip("/") + "/models"
