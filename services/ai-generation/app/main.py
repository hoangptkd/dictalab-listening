import hashlib
import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .ollama import generate
from .schemas import ConversationTaskRequest, ConversationTaskResponse

settings = Settings()
app = FastAPI(title="DictaLab AI Generation", version="0.1.0")
logger = logging.getLogger("dictalab.ai_generation")


@dataclass
class CachedResult:
    fingerprint: str
    response: ConversationTaskResponse
    expires_at: float


cache: dict[str, CachedResult] = {}
semaphore = __import__("asyncio").Semaphore(settings.max_concurrency)


def _authorized(token: str | None) -> bool:
    return not settings.service_token or token == settings.service_token


def _fingerprint(payload: ConversationTaskRequest) -> str:
    raw = json.dumps(payload.model_dump(), sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


@app.get("/health/live")
async def live() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready")
async def ready() -> dict[str, str]:
    try:
        timeout = httpx.Timeout(min(settings.timeout_seconds, 3.0))
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags")
            response.raise_for_status()
        return {"status": "ready", "model": settings.model}
    except Exception as exc:
        raise HTTPException(status_code=503, detail="Ollama is not ready") from exc


@app.post("/v1/conversation/tasks", response_model=ConversationTaskResponse)
async def create_task(payload: ConversationTaskRequest, request: Request,
                      x_service_token: str | None = Header(default=None),
                      idempotency_key: str | None = Header(default=None, alias="Idempotency-Key")) -> ConversationTaskResponse:
    request_id = request.headers.get("X-Request-Id")
    if not _authorized(x_service_token):
        raise HTTPException(status_code=401, detail="invalid service token")
    if not request_id or request_id != payload.requestId or idempotency_key != payload.idempotencyKey:
        raise HTTPException(status_code=400, detail="request id mismatch")
    now = time.monotonic()
    key = payload.idempotencyKey
    fingerprint = _fingerprint(payload)
    cached = cache.get(key)
    if cached and cached.expires_at > now:
        if cached.fingerprint != fingerprint:
            raise HTTPException(status_code=409, detail="idempotency key was reused")
        return cached.response
    if cached:
        cache.pop(key, None)
    if semaphore.locked():
        raise HTTPException(status_code=429, detail="generation capacity exhausted")
    async with semaphore:
        started = time.monotonic()
        try:
            timeout = httpx.Timeout(settings.timeout_seconds)
            async with httpx.AsyncClient(timeout=timeout) as client:
                task = await generate(payload, settings, client)
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail="generation timed out") from exc
        except Exception as exc:
            raise HTTPException(status_code=503, detail="generation unavailable") from exc
    result = ConversationTaskResponse(task=task, model=settings.model, modelDigest=settings.model_digest,
                                      promptVersion=payload.promptVersion, generationId=str(uuid.uuid4()),
                                      requestId=payload.requestId)
    logger.info("conversation_generation_completed", extra={
        "request_id": payload.requestId, "generation_id": result.generationId,
        "model": settings.model, "model_digest": settings.model_digest,
        "prompt_version": payload.promptVersion, "duration_ms": round((time.monotonic() - started) * 1000),
    })
    cache[key] = CachedResult(fingerprint, result, now + 900)
    return result


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"error": "invalid conversation request"})
