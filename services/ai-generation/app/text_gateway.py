"""Internal text execution boundary; provider credentials never leave this service."""
import asyncio
from datetime import datetime
import json
import time
from uuid import UUID

import httpx
from fastapi import APIRouter, Header, HTTPException
from pydantic import Field, model_validator
from typing import Literal
from .providers import get_provider

from .coach import StrictModel
from .config import CLOUD_PROVIDERS
from .jobs import JobStore
from .model_gateway import ModelGateway

router = APIRouter()


class TextRequest(StrictModel):
    requestId: UUID
    provider: str = Field(pattern=r"^[a-z][a-z0-9_]{0,31}$")
    model: str = Field(pattern=r"^[a-zA-Z0-9_.:/-]{1,128}$")
    instruction: str = Field(min_length=1, max_length=48000)
    input: str = Field(min_length=1, max_length=120000)
    deadline: datetime


class TextMessage(StrictModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=168000)


class TextRequestV2(StrictModel):
    schemaVersion: Literal["2"]
    requestId: UUID
    provider: str = Field(pattern=r"^[a-z][a-z0-9_]{0,31}$")
    model: str = Field(pattern=r"^[a-zA-Z0-9_.:/-]{1,128}$")
    purpose: str = Field(pattern=r"^[a-z][a-z0-9_]{0,63}$")
    messages: list[TextMessage] = Field(min_length=1, max_length=32)
    output_schema: dict = Field(alias="schema")
    outputMode: Literal["json_object", "json_schema"]
    temperature: float | None = Field(default=None, ge=0, le=2, allow_inf_nan=False)
    maxOutputTokens: int = Field(ge=1, le=32768, strict=True)
    allowModelFallback: bool = Field(default=True, strict=True)
    deadline: datetime

    @model_validator(mode="after")
    def valid_schema(self):
        from jsonschema import Draft202012Validator
        try:
            Draft202012Validator.check_schema(self.output_schema)
        except Exception:
            raise ValueError("invalid JSON schema") from None
        def check(value):
            if isinstance(value, dict):
                for key in ("$ref", "$dynamicRef", "$recursiveRef", "$id"):
                    if key in value and not str(value[key]).startswith("#"):
                        raise ValueError("remote schema references are forbidden")
                for child in value.values():
                    check(child)
            elif isinstance(value, list):
                for child in value:
                    check(child)
        check(self.output_schema)
        if self.output_schema.get("type") != "object":
            raise ValueError("object schema required")
        return self


def select_model(settings, requested):
    provider = settings.selected_provider
    if requested.provider != provider:
        raise HTTPException(409, "provider configuration mismatch")
    model = get_provider(provider).normalize_model(requested.model)
    selected = settings.model_copy(deep=True)
    selected.num_predict = 2200
    if provider == "ollama":
        if model != settings.model:
            raise HTTPException(409, "model configuration mismatch")
        return selected
    allowed = {source.model for source in settings.sources_for(provider) if source.enabled}
    if not settings.gemini_quota_enabled:
        allowed = {settings.active_cloud_model}
    if model not in allowed:
        raise HTTPException(409, "model configuration mismatch")
    # This request may select an existing model, never a provider/account/endpoint/key.
    if provider in selected.registry_pools:
        selected.registry_pools[provider]["default_model"] = model
    elif provider == "gemini":
        selected.gemini_model = model
    return selected


@router.post("/v1/text/completions")
async def complete(payload: TextRequest, x_service_token: str | None = Header(default=None),
                   x_ai_observe: str | None = Header(default=None)):
    return await execute_text(payload, x_service_token, x_ai_observe)


@router.post("/v2/text/completions")
async def complete_v2(payload: TextRequestV2, x_service_token: str | None = Header(default=None),
                      x_ai_observe: str | None = Header(default=None)):
    return await execute_text(payload, x_service_token, x_ai_observe)


async def execute_text(payload, x_service_token, x_ai_observe):
    from .main import settings, _authorized, semaphore
    if not settings.service_token or not _authorized(x_service_token):
        raise HTTPException(401, "invalid service token")
    if not settings.text_gateway_enabled:
        raise HTTPException(503, "text gateway is disabled")
    if payload.deadline.tzinfo is None:
        raise HTTPException(400, "deadline must include timezone")
    try:
        from .providers import PROVIDERS
        if payload.provider not in PROVIDERS:
            raise HTTPException(409, {"code": "UNREGISTERED_PROVIDER", "outcome": "NOT_SENT"}
                if isinstance(payload, TextRequestV2) else "unregistered provider")
        selected = select_model(await asyncio.to_thread(settings.current_registry), payload)
        if isinstance(payload, TextRequestV2):
            selected.num_predict = payload.maxOutputTokens
    except ValueError:
        raise HTTPException(503, "provider configuration unavailable") from None
    remaining = payload.deadline.timestamp() - time.time()
    if not 0 < remaining <= 61:
        raise HTTPException(400, "invalid text deadline")
    # Admission precedes provider I/O and survives process loss. There is no automatic replay.
    try:
        store = await asyncio.to_thread(JobStore, settings.jobs_database, settings.job_retention_seconds)
        admitted = await asyncio.to_thread(store.admit_text, payload.requestId, payload.deadline.timestamp())
    except ValueError as error:
        raise HTTPException(400, "invalid text deadline") from error
    except Exception:
        raise HTTPException(503, "text admission unavailable") from None
    if not admitted:
        raise HTTPException(409, "text request already admitted; outcome may be unknown")
    acquired = False
    try:
        async with asyncio.timeout(max(0, payload.deadline.timestamp() - time.time())):
            if semaphore is not None:
                await semaphore.acquire()
                acquired = True
            async with httpx.AsyncClient(follow_redirects=False) as client:
                gateway = ModelGateway(selected, client)
                if x_ai_observe == "usage":
                    gateway.observed_attempts = []
                v2 = isinstance(payload, TextRequestV2)
                result, provider = await gateway.complete(
                    [m.model_dump() for m in payload.messages] if v2 else [
                        {"role": "system", "content": payload.instruction}, {"role": "user", "content": payload.input}],
                    payload.output_schema if v2 else {"type": "object"},
                    time.monotonic() + max(0, payload.deadline.timestamp() - time.time()), payload.purpose if v2 else "core_text",
                    temperature=payload.temperature if v2 else 0,
                    json_object=payload.outputMode == "json_object" if v2 else True,
                    allow_model_fallback=payload.allowModelFallback if v2 else True)
            if v2:
                from jsonschema import Draft202012Validator
                Draft202012Validator(payload.output_schema).validate(result)
            expected_model = get_provider(provider).normalize_model(payload.model)
            if (not isinstance(result, dict) or len(json.dumps(result, ensure_ascii=False)) > 12000
                    or provider != payload.provider or (gateway.last_model != expected_model and
                        (provider != "gemini" or gateway.last_model not in selected.gemini_fallback_models
                         or not gateway.model_fallbacks or gateway.model_fallbacks[-1]["toModel"] != gateway.last_model))):
                raise ValueError("invalid text output or provenance")
            envelope = {"schemaVersion": "1", "requestId": str(payload.requestId), "provider": provider,
                        "model": gateway.last_model, "task": result}
            if v2:
                envelope.update(schemaVersion="2", requestedModel=expected_model, fallbacks=[],
                    reportedModel=getattr(getattr(gateway, "last_result", None), "reported_model", None))
            if gateway.model_fallbacks:
                envelope.update(requestedModel=expected_model, fallbacks=gateway.model_fallbacks)
            if x_ai_observe == "usage":
                attempts = gateway.observed_attempts
                observation = {"attempts": attempts, "httpAttempts": len(attempts)}
                for target, field in (("prompt_tokens", "input_tokens"), ("completion_tokens", "output_tokens")):
                    if attempts and all(type(row.get(field)) is int for row in attempts):
                        observation[target] = sum(row[field] for row in attempts)
                envelope["observation"] = observation
            return envelope
    except Exception as error:
        from .gemini_pool import QuotaUnavailable, retry_seconds
        from .providers.base import ProviderHttpError
        if isinstance(payload, TextRequestV2) and str(error).startswith("UNSUPPORTED_CAPABILITY"):
            raise HTTPException(422, {"code": "UNSUPPORTED_CAPABILITY", "outcome": "NOT_SENT"}) from None
        if isinstance(error, QuotaUnavailable) or isinstance(error, ProviderHttpError) and error.status == 429:
            delay = error.retry_after if isinstance(error, QuotaUnavailable) else retry_seconds(error.retry_after)
            raise HTTPException(429, "AI sources temporarily unavailable", headers={"Retry-After": str(delay)}) from None
        if isinstance(payload, TextRequestV2):
            from .providers.base import ProviderError
            from jsonschema import ValidationError
            if isinstance(error, ValidationError) or isinstance(error, ProviderError) and any(
                    marker in str(error) for marker in ("invalid_json", "parse_error", "invalid_content", "schema_mismatch", "truncated", "response_not_object")):
                raise HTTPException(503, {"code": "INVALID_OUTPUT", "outcome": "RESPONSE_RECEIVED"}) from None
            if isinstance(error, ProviderHttpError) and error.server_failure or 400 <= getattr(error, "status", 0) < 500:
                raise HTTPException(503, {"code": "PROVIDER_REJECTED", "outcome": "REJECTED"}) from None
        # Provider exceptions can contain request text and credential-bearing URLs.
        raise HTTPException(503, "text outcome failed or unknown; automatic replay disabled") from None
    finally:
        if acquired:
            semaphore.release()
