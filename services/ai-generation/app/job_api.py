"""Internal job API. Java authorizes domain access before submit and again before persistence."""
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException, Request

from .jobs import Conflict, JobRequest, JobStore

router = APIRouter()


def authorize(token):
    from .main import settings, _authorized
    if not _authorized(token):
        raise HTTPException(401, "invalid service token")
    if not settings.jobs_enabled:
        raise HTTPException(503, "background capabilities are disabled")
    return settings, JobStore(settings.jobs_database, settings.job_retention_seconds)


@router.post("/v1/tts/jobs", status_code=202)
@router.post("/v1/coach/reports", status_code=202)
@router.post("/v1/review/tasks", status_code=202)
async def submit(payload: JobRequest, request: Request,
                 x_service_token: str | None = Header(default=None),
                 x_user_scope: str | None = Header(default=None)):
    settings, store = authorize(x_service_token)
    expected = {"/v1/tts/jobs": "tts", "/v1/coach/reports": "coach", "/v1/review/tasks": "review"}[request.url.path]
    if expected != "tts" and request.headers.get("X-AI-Provider") != settings.selected_provider:
        raise HTTPException(409, "provider/quota configuration mismatch")
    if (payload.payload.kind != expected or x_user_scope != payload.userScope
            or request.headers.get("X-Request-Id") != str(payload.requestId)
            or request.headers.get("Idempotency-Key") != payload.idempotencyKey):
        raise HTTPException(400, "job/header contract mismatch")
    try:
        job_id = store.register(payload, settings)
    except Conflict as exc:
        raise HTTPException(409, "job idempotency conflict") from exc
    except ValueError as exc:
        raise HTTPException(400, "invalid job deadline") from exc
    except OverflowError as exc:
        raise HTTPException(429, "job capacity exhausted") from exc
    return store.get(job_id, payload.userScope)


@router.get("/v1/jobs/{job_id}")
async def status(job_id: UUID, x_service_token: str | None = Header(default=None),
                 x_user_scope: str | None = Header(default=None)):
    _, store = authorize(x_service_token)
    result = store.get(str(job_id), x_user_scope)
    if result is None:
        raise HTTPException(404, "job not found")
    return result
