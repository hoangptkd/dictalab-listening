"""Internal text execution boundary; provider credentials never leave this service."""
import asyncio
from datetime import datetime
import json
import time
from uuid import UUID

import httpx
from fastapi import APIRouter, Header, HTTPException
from pydantic import Field

from .coach import StrictModel
from .config import CLOUD_PROVIDERS
from .jobs import JobStore
from .model_gateway import ModelGateway

router = APIRouter()


class TextRequest(StrictModel):
    requestId: UUID
    provider: str = Field(pattern="^(ollama|gemini|deepseek|openai_compatible)$")
    model: str = Field(pattern=r"^[a-zA-Z0-9_.:/-]{1,128}$")
    instruction: str = Field(min_length=1, max_length=48000)
    input: str = Field(min_length=1, max_length=120000)
    deadline: datetime


def select_model(settings, requested):
    provider = settings.selected_provider
    if requested.provider != provider:
        raise HTTPException(409, "provider configuration mismatch")
    model = requested.model.removeprefix("models/") if provider in CLOUD_PROVIDERS else requested.model
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
async def complete(payload: TextRequest, x_service_token: str | None = Header(default=None)):
    from .main import settings, _authorized, semaphore
    if not settings.service_token or not _authorized(x_service_token):
        raise HTTPException(401, "invalid service token")
    if not settings.text_gateway_enabled:
        raise HTTPException(503, "text gateway is disabled")
    if payload.deadline.tzinfo is None:
        raise HTTPException(400, "deadline must include timezone")
    selected = select_model(settings, payload)
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
                result, provider = await gateway.complete([
                    {"role": "system", "content": payload.instruction},
                    {"role": "user", "content": payload.input}], {"type": "object"},
                    time.monotonic() + max(0, payload.deadline.timestamp() - time.time()), "core_text",
                    temperature=0, json_object=True)
            expected_model = payload.model.removeprefix("models/") if provider in CLOUD_PROVIDERS else payload.model
            if (not isinstance(result, dict) or len(json.dumps(result, ensure_ascii=False)) > 12000
                    or provider != payload.provider or gateway.last_model != expected_model):
                raise ValueError("invalid text output or provenance")
            return {"schemaVersion": "1", "requestId": str(payload.requestId), "provider": provider,
                    "model": gateway.last_model, "task": result}
    except Exception:
        # Provider exceptions can contain request text and credential-bearing URLs.
        raise HTTPException(503, "text outcome failed or unknown; automatic replay disabled") from None
    finally:
        if acquired:
            semaphore.release()
