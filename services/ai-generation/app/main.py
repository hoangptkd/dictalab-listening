import asyncio
import hashlib
import json
import logging
import time
import uuid
import secrets
from contextlib import asynccontextmanager, nullcontext, suppress
from dataclasses import dataclass, field
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse

from .config import CLOUD_PROVIDERS, Settings, cloud_model_id_matches
from . import telemetry
from .registry import RegistryError, load_artifact, resolve_version, verify_model
from .prompt_versions import provider_calls, selected_artifact
from .redaction import RedactingFilter, is_safe_log_value, redact
from .workflow import run_workflow
from .schemas import ConversationContext, ConversationTaskRequest, ConversationTaskResponse
from .gemini_pool import quota_store, quota_metrics

settings = Settings()


@asynccontextmanager
async def lifespan(_app):
    async def dispatch_jobs():
        from .jobs import JobStore, publish_pending
        store = JobStore(settings.jobs_database, settings.job_retention_seconds)
        while True:
            try:
                await asyncio.to_thread(publish_pending, settings, store)
            except Exception:
                logger.warning("ai_job_publish_unavailable")
            await asyncio.sleep(2)
    dispatcher = asyncio.create_task(dispatch_jobs()) if settings.jobs_enabled else None
    try:
        yield
    finally:
        if dispatcher:
            dispatcher.cancel()
            with suppress(asyncio.CancelledError):
                await dispatcher


app = FastAPI(title="DictaLab AI Generation", version="0.2.0", lifespan=lifespan)
from .job_api import router as job_router
app.include_router(job_router)
from .text_gateway import router as text_router
app.include_router(text_router)
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

semaphore = asyncio.Semaphore(settings.effective_max_concurrency) if settings.effective_max_concurrency else None


@app.middleware("http")
async def bound_job_requests(request: Request, call_next):
    if request.method == "POST" and request.url.path == "/v1/text/completions":
        if not settings.service_token or not _authorized(request.headers.get("X-Service-Token")):
            return JSONResponse(status_code=401, content={"error": "invalid service token"})
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 256000:
                return JSONResponse(status_code=413, content={"error": "text input exceeds limit"})
        request._body = bytes(body)
    if request.method == "POST" and request.url.path in ("/v1/tts/jobs", "/v1/coach/reports", "/v1/review/tasks", "/v1/conversation/jobs"):
        if not _authorized(request.headers.get("X-Service-Token")):
            return JSONResponse(status_code=401, content={"error": "invalid service token"})
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 64000:
                return JSONResponse(status_code=413, content={"error": "job input exceeds limit"})
        # Starlette's cached request forwards this bounded body to the endpoint parser.
        request._body = bytes(body)
    return await call_next(request)


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
async def metrics(x_service_token: str | None = Header(default=None), authorization: str | None = Header(default=None)):
    bearer = authorization[7:] if authorization and authorization.startswith("Bearer ") else None
    if not _authorized(x_service_token or bearer):
        raise HTTPException(status_code=401, detail="invalid service token")
    job_metrics = ""
    if settings.jobs_enabled:
        from .jobs import JobStore
        job_metrics = JobStore(settings.jobs_database, settings.job_retention_seconds).metrics()
    quota = await asyncio.to_thread(quota_metrics, settings)
    return PlainTextResponse(telemetry.render() + job_metrics + quota, media_type="text/plain; version=0.0.4")


def _authorized(token: str | None) -> bool:
    return not settings.service_token or secrets.compare_digest((token or "").encode(), settings.service_token.encode())


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
        artifact = load_artifact(settings) if settings.conversation_enabled else None
        timeout = httpx.Timeout(min(settings.timeout_seconds, 3.0))
        provider = settings.selected_provider
        async with httpx.AsyncClient(timeout=timeout) as client:
            if provider in CLOUD_PROVIDERS:
                if settings.gemini_quota_enabled:
                    store = quota_store(settings)
                    for source in settings.sources_for(provider):
                        await asyncio.to_thread(store.execute, source, 1, "status")
                        url = source.url.removesuffix("/chat/completions") + "/models"
                        check = await client.get(url, headers={"Authorization": "Bearer " + source.api_key})
                        check.raise_for_status()
                        if not any(cloud_model_id_matches(source.model, m.get("id") or "") for m in check.json().get("data", [])):
                            raise ValueError("pool model missing")
                    if not any(cloud_model_id_matches(settings.active_cloud_model, p.model) for p in settings.sources_for(provider)):
                        raise ValueError("requested cloud model missing from pool")
                    response = check
                else:
                    if not settings.active_cloud_api_key:
                        raise ValueError("cloud key is missing")
                    response = await client.get(settings.active_cloud_models_url,
                        headers={"Authorization": "Bearer " + settings.active_cloud_api_key})
            else:
                response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags",
                    headers={"X-Service-Token": settings.ollama_service_token} if settings.ollama_service_token else {})
            response.raise_for_status()
        if provider == "ollama":
            if artifact:
                verify_model(artifact, response.json())
            elif not any(m.get("name") == settings.model and (settings.model_digest == "unknown" or
                    m.get("digest", "").removeprefix("sha256:") == settings.model_digest.removeprefix("sha256:"))
                    for m in response.json().get("models", [])):
                raise ValueError("local model missing or digest drift")
        elif not settings.gemini_quota_enabled and not any(cloud_model_id_matches(settings.active_cloud_model, m.get("id") or "")
                     for m in response.json().get("data", [])):
            raise ValueError("cloud model missing")
        if settings.jobs_enabled:
            from .jobs import JobStore, broker_connection, QUEUE
            JobStore(settings.jobs_database, settings.job_retention_seconds).cleanup()
            def check_broker():
                with broker_connection(settings) as connection:
                    connection.channel().queue_declare(queue=QUEUE, passive=True)
            await asyncio.to_thread(check_broker)
        return {"status": "ready", "model": settings.active_cloud_model if provider in CLOUD_PROVIDERS else settings.model,
                "promptVersion": artifact["version"] if artifact else "conversation-disabled",
                "modelDigest": artifact["model"]["digest"] if artifact else "provider-managed"}
    except Exception as exc:
        raise HTTPException(status_code=503, detail="AI artifact or provider model is not ready") from exc


@app.post("/v1/conversation/tasks", response_model=ConversationTaskResponse)
async def create_task(payload: ConversationTaskRequest, request: Request,
                      x_service_token: str | None = Header(default=None),
                      x_ai_timeout_ms: int | None = Header(default=None, ge=1, le=600000),
                      idempotency_key: str | None = Header(default=None, alias="Idempotency-Key")) -> ConversationTaskResponse:
    request_id = request.headers.get("X-Request-Id")
    if not _authorized(x_service_token):
        raise HTTPException(status_code=401, detail="invalid service token")
    if not request_id or request_id != payload.requestId or idempotency_key != payload.idempotencyKey:
        raise HTTPException(status_code=400, detail="request id mismatch")
    mode = request.headers.get("X-AI-Mode", "foreground")
    if mode not in ("foreground", "shadow"):
        raise HTTPException(status_code=400, detail="invalid generation mode")
    if not settings.conversation_enabled:
        raise HTTPException(status_code=503, detail="conversation capability is disabled")
    if mode == "shadow" and settings.selected_provider in CLOUD_PROVIDERS:
        raise HTTPException(status_code=400, detail="cloud shadow is disabled")

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

        if semaphore is not None and semaphore.locked():
            raise HTTPException(status_code=429, detail="generation capacity exhausted")

        started = time.monotonic()
        runtime_seconds = min(settings.timeout_seconds, x_ai_timeout_ms / 1000) if x_ai_timeout_ms is not None else settings.timeout_seconds
        async with semaphore if semaphore is not None else nullcontext():
            telemetry.active += 1
            calls = []
            call_token = provider_calls.set(calls)
            artifact_token = selected_artifact.set(artifact)
            try:
                timeout = httpx.Timeout(runtime_seconds)
                async with httpx.AsyncClient(timeout=timeout, limits=httpx.Limits(max_connections=settings.effective_http_max_connections)) as client:
                    server_payload = payload.model_copy(update={"promptVersion": version,
                                                               "instruction": artifact["instruction"]})
                    runtime_settings = settings.model_copy(update={"timeout_seconds": runtime_seconds})
                    task = await asyncio.wait_for(run_workflow(server_payload, runtime_settings, client),
                                                  timeout=runtime_seconds)
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
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    logger.warning("ai_request_invalid count=%s", len(exc.errors()))
    message = "invalid conversation request" if request.url.path == "/v1/conversation/tasks" else "invalid background job request"
    return JSONResponse(status_code=400, content={"error": message})
