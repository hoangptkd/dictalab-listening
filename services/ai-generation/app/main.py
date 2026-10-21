import asyncio
import hashlib
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse

from .config import Settings
from . import telemetry
from .registry import RegistryError, load_artifact, resolve_version, verify_model
from .prompt_versions import provider_calls, selected_artifact
from .redaction import RedactingFilter, is_safe_log_value, redact
from .workflow import run_workflow
from .schemas import ConversationContext, ConversationTaskRequest, ConversationTaskResponse

settings = Settings()
app = FastAPI(title="DictaLab AI Generation", version="0.1.0")
logger = logging.getLogger("dictalab.ai_generation")
logger.addFilter(RedactingFilter())


@dataclass
class CachedResult:
    fingerprint: str
    response: ConversationTaskResponse
    expires_at: float


# In-memory idempotency cache (TTL from settings; lost on process restart by design)
cache: dict[str, CachedResult] = {}

# Per-key locks prevent two concurrent requests with the same idempotency key
# from both proceeding to generation. Only one holds the lock; the other waits
# then gets the cached result.
_key_locks: dict[str, asyncio.Lock] = {}
_key_locks_mu: asyncio.Lock = asyncio.Lock()

semaphore = asyncio.Semaphore(settings.max_concurrency)


@app.middleware("http")
async def observe_requests(request: Request, call_next):
    if request.url.path != "/v1/conversation/tasks":
        return await call_next(request)
    started = time.monotonic()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        mode = "shadow" if request.headers.get("X-AI-Mode") == "shadow" else "foreground"
        telemetry.observe(status, mode, time.monotonic() - started)


@app.get("/metrics", response_class=PlainTextResponse)
async def metrics(x_service_token: str | None = Header(default=None)):
    if not _authorized(x_service_token):
        raise HTTPException(status_code=401, detail="invalid service token")
    return PlainTextResponse(telemetry.render(), media_type="text/plain; version=0.0.4")


def _authorized(token: str | None) -> bool:
    return not settings.service_token or token == settings.service_token


def _fingerprint(payload: ConversationTaskRequest) -> str:
    raw = json.dumps(payload.model_dump(), sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()


async def _get_key_lock(key: str) -> asyncio.Lock:
    async with _key_locks_mu:
        if key not in _key_locks:
            _key_locks[key] = asyncio.Lock()
        return _key_locks[key]


def _evict_expired() -> None:
    """Remove expired cache entries and their associated locks."""
    now = time.monotonic()
    expired = [k for k, v in cache.items() if v.expires_at <= now]
    for k in expired:
        cache.pop(k, None)
        _key_locks.pop(k, None)


@app.get("/health/live")
async def live() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready")
async def ready() -> dict[str, str]:
    try:
        artifact = load_artifact(settings)
        timeout = httpx.Timeout(min(settings.timeout_seconds, 3.0))
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags")
            response.raise_for_status()
        verify_model(artifact, response.json())
        return {"status": "ready", "model": settings.model, "promptVersion": artifact["version"],
                "modelDigest": artifact["model"]["digest"]}
    except Exception as exc:
        raise HTTPException(status_code=503, detail="AI artifact or Ollama model is not ready") from exc


@app.post("/v1/conversation/tasks", response_model=ConversationTaskResponse)
async def create_task(payload: ConversationTaskRequest, request: Request,
                      x_service_token: str | None = Header(default=None),
                      idempotency_key: str | None = Header(default=None, alias="Idempotency-Key")) -> ConversationTaskResponse:
    request_id = request.headers.get("X-Request-Id")
    if not _authorized(x_service_token):
        raise HTTPException(status_code=401, detail="invalid service token")
    if not request_id or request_id != payload.requestId or idempotency_key != payload.idempotencyKey:
        raise HTTPException(status_code=400, detail="request id mismatch")
    mode = request.headers.get("X-AI-Mode", "foreground")
    if mode not in ("foreground", "shadow"):
        raise HTTPException(status_code=400, detail="invalid generation mode")

    try:
        artifact = load_artifact(settings)
    except RegistryError as exc:
        raise HTTPException(status_code=503, detail="AI artifact is not ready") from exc
    try:
        version = resolve_version(payload.promptVersion, artifact)
    except RegistryError as exc:
        raise HTTPException(status_code=400, detail="unsupported prompt version") from exc

    _evict_expired()
    now = time.monotonic()
    key = payload.idempotencyKey
    fingerprint = hashlib.sha256((_fingerprint(payload) + ":" + mode + ":" + artifact["sha256"]).encode()).hexdigest()

    # Fast path: already cached (no lock needed for read, fingerprint guards correctness)
    cached = cache.get(key)
    if cached and cached.expires_at > now:
        if cached.fingerprint != fingerprint:
            raise HTTPException(status_code=409, detail="idempotency key was reused")
        return cached.response

    # Acquire per-key lock to prevent duplicate concurrent generation
    key_lock = await _get_key_lock(key)
    async with key_lock:
        # Re-check after acquiring lock (another coroutine may have populated the cache)
        cached = cache.get(key)
        if cached and cached.expires_at > time.monotonic():
            if cached.fingerprint != fingerprint:
                raise HTTPException(status_code=409, detail="idempotency key was reused")
            return cached.response
        if cached:
            cache.pop(key, None)

        if semaphore.locked():
            raise HTTPException(status_code=429, detail="generation capacity exhausted")

        started = time.monotonic()
        async with semaphore:
            telemetry.active += 1
            calls = []
            call_token = provider_calls.set(calls)
            artifact_token = selected_artifact.set(artifact)
            try:
                timeout = httpx.Timeout(settings.timeout_seconds)
                async with httpx.AsyncClient(timeout=timeout) as client:
                    server_payload = payload.model_copy(update={"promptVersion": version,
                                                               "instruction": artifact["instruction"]})
                    runtime_settings = settings.model_copy(update={"fallback_enabled": False}) if mode == "shadow" else settings
                    task = await asyncio.wait_for(run_workflow(server_payload, runtime_settings, client),
                                                  timeout=settings.timeout_seconds)
            except TimeoutError as exc:
                raise HTTPException(status_code=504, detail="generation timed out") from exc
            except Exception as exc:
                raise HTTPException(status_code=503, detail="generation unavailable") from exc
            finally:
                telemetry.active -= 1
                provider_calls.reset(call_token)
                selected_artifact.reset(artifact_token)

        result = ConversationTaskResponse(
            task=task, model=calls[-1]["model"] if calls else settings.model,
            modelDigest=calls[-1]["modelDigest"] if calls else artifact["model"]["digest"],
            promptVersion=version, generationId=str(uuid.uuid4()),
            requestId=payload.requestId,
        )
        # Log only safe metadata — no raw prompt, response, or learner content
        logger.info("conversation_generation_completed", extra={
            "request_id": payload.requestId, "generation_id": result.generationId,
            "model": result.model, "model_digest": result.modelDigest, "mode": mode,
            "prompt_version": version, "provider_calls": calls,
            "duration_ms": round((time.monotonic() - started) * 1000),
            "turn_number": payload.turnNumber,
            "level": payload.level,
        })
        # Cache only the merged response (not raw content) within finite TTL
        cache[key] = CachedResult(fingerprint, result, time.monotonic() + settings.cache_ttl_seconds)
        return result


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, str) else str(exc.detail)
    logger.warning("conversation_request_rejected status=%s reason=%s", exc.status_code, redact(detail))
    return JSONResponse(status_code=exc.status_code, content={"error": redact(detail)})


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    logger.warning("conversation_request_invalid fields=%s", [".".join(map(str, error["loc"])) for error in exc.errors()])
    return JSONResponse(status_code=400, content={"error": "invalid conversation request"})
