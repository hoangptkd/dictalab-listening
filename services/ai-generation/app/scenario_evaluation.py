"""Local-only experiment transport. Executes the real pipeline; never replays admitted work."""
import asyncio
from datetime import datetime, timedelta, timezone
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import os
import re
import sys
import time
from types import SimpleNamespace
from uuid import UUID

from fastapi import APIRouter, Header, HTTPException
from pydantic import Field

from . import scenario_assessment as a
from .coach import StrictModel
from .redaction import redact_string, _CREDENTIAL_KEYS
from .observability import observation

router = APIRouter()
_tasks = set()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def build_hash():
    sources = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(Path(__file__).parent.iterdir())
               if p.suffix in (".py", ".json", ".lua")}
    sources["dependencies"] = {name: version(name) for name in ("pydantic", "httpx", "fastapi")}
    sources["python"] = list(sys.version_info[:3])
    return digest(sources)


LOADED_BUILD = build_hash()


def authorize(token):
    from .main import settings, _authorized
    if not re.fullmatch(r"[!-~]{32,256}", settings.service_token) or not _authorized(token):
        raise HTTPException(401, "invalid service token")
    if not settings.evaluation_enabled or settings.production_mode or not settings.scenario_enabled:
        raise HTTPException(503, "local scenario evaluation is disabled")
    return settings


def catalog(settings):
    selected = settings.current_registry()
    # Hash executable Python sources, including validators, gateway, schemas and this runner.
    if build_hash() != LOADED_BUILD:
        raise HTTPException(503, "Python sources changed after startup; restart before evaluation")
    runtime = {"provider": selected.selected_provider, "model": selected.model if selected.selected_provider == "ollama" else selected.active_cloud_model,
               "fallbackModels": selected.gemini_fallback_models, "temperature": 0,
               "sourcePolicy": "existing-configured-pool", "transport": "scenario-python",
               "pool": [{"id": s.id, "model": s.model, "enabled": s.enabled} for s in selected.sources_for(selected.selected_provider)]}
    return {"backendHash": LOADED_BUILD, "runtime": runtime, "releases": [
        {"id": name, "promptVersion": release.PROMPT_VERSION, "promptHash": a.prompt_hash(release),
         "instructions": release.INSTRUCTIONS, "outputTokens": release.OUTPUT_TOKENS,
         "exampleSetHash": getattr(release, "EXAMPLE_SET_HASH", None)}
        for name, release in (("v4.13", a.scoped_release), ("v4.14", a.scoped_fewshot_candidate))]}


def directory(settings):
    root = Path(settings.evaluation_directory).resolve()
    root.mkdir(parents=True, exist_ok=True)
    if root.is_symlink():
        raise HTTPException(503, "evaluation storage unavailable")
    return root


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(clean(value), ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, path)


def clean(value, key=""):
    # Preserve trusted provenance hashes and nested evidence, rather than the log redactor's depth cutoff.
    if key.lower() in _CREDENTIAL_KEYS:
        return "[REDACTED]"
    if isinstance(value, dict):
        return {k: clean(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, str):
        if (key == "fingerprint" or key.endswith("Hash")) and re.fullmatch("[a-f0-9]{64}", value):
            return value
        return redact_string(value)
    return value


class EvaluationRequest(StrictModel):
    requestId: UUID
    backendHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    runtimeHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    payload: a.ScenarioAssessmentInput


@router.get("/v1/evaluation/scenario")
async def versions(x_service_token: str | None = Header(default=None)):
    settings = authorize(x_service_token)
    return await asyncio.to_thread(catalog, settings)


@router.post("/v1/evaluation/scenario", status_code=202)
async def submit(payload: EvaluationRequest, x_service_token: str | None = Header(default=None)):
    settings = authorize(x_service_token)
    if payload.payload.phase != "EXTRACT" or payload.payload.promptHash not in (
            a.prompt_hash(a.scoped_release), a.prompt_hash(a.scoped_fewshot_candidate)):
        raise HTTPException(400, "evaluation requires a supported extraction input")
    selected = await asyncio.to_thread(settings.current_registry)
    current = await asyncio.to_thread(catalog, selected)
    if payload.backendHash != current["backendHash"] or payload.runtimeHash != digest(current["runtime"]):
        raise HTTPException(409, "evaluation configuration changed")
    path = directory(settings) / (str(payload.requestId) + ".json")
    record = {"requestId": str(payload.requestId), "fingerprint": digest(payload.model_dump(mode="json")),
              "status": "RUNNING", "events": [], "startedAt": datetime.now(timezone.utc).isoformat()}
    # Exclusive creation is the durable admission. A lost/unknown outcome is never rerun.
    try:
        with path.open("x", encoding="utf-8") as stream:
            json.dump(record, stream)
    except FileExistsError:
        prior = json.loads(path.read_text(encoding="utf-8"))
        if prior["fingerprint"] != record["fingerprint"]:
            raise HTTPException(409, "evaluation request conflict")
        return prior
    task = asyncio.create_task(run(payload, selected, path, record))
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return {"requestId": str(payload.requestId), "status": "RUNNING"}


async def run(request, settings, path, record):
    from .main import semaphore
    acquired = False
    def observe(event):
        record["events"].append(event)
        save(path, record)
    try:
        async with asyncio.timeout(490):
            if semaphore is not None:
                await semaphore.acquire()
                acquired = True
            def event(payload):
                return SimpleNamespace(payload=payload, jobId=request.requestId, requestId=request.requestId,
                    deadline=datetime.now(timezone.utc) + timedelta(seconds=240))
            with observation("scenario-evaluation", input=request.payload.model_dump(),
                             metadata={"evaluationId": str(request.requestId), "promptHash": request.payload.promptHash}):
                extracted = await a.execute(event(request.payload), settings, observer=observe)
                record["extraction"] = extracted
                save(path, record)
                if not extracted["task"].get("allowed"):
                    record["status"] = "REJECTED"
                else:
                    fields = a.w.PersonalizedSpecification.model_fields
                    specification = a.w.PersonalizedSpecification.model_validate({k: v for k, v in extracted["task"].items() if k in fields})
                    generated = request.payload.model_copy(update={"phase": "GENERATE", "specification": specification})
                    record["result"] = await a.execute(event(generated), settings, observer=observe)
                    record["status"] = "COMPLETED"
    except (Exception, asyncio.CancelledError) as error:
        record.update(status="ERROR", error=type(error).__name__, httpStatus=getattr(error, "status", None))
        if isinstance(error, asyncio.CancelledError):
            record["status"] = "UNKNOWN"
    finally:
        if acquired:
            semaphore.release()
        record["finishedAt"] = datetime.now(timezone.utc).isoformat()
        record["before"] = initial_artifact(record)
        save(path, record)


def initial_artifact(record):
    first = {}
    for event in record["events"]:
        if event["status"] == "RETURNED":
            first.setdefault(event["stage"], event["output"])
    try:
        dialogue = a.w.Dialogue.model_validate(first["GENERATE"])
        context = a.AssessmentContext.model_validate({"currentValue": None, **next(e["input"]["learningContext"] for e in record["events"] if e["stage"] == "EXTRACT")})
        questions = a.PartsQuestions.model_validate(first["QUESTIONS"])
        content = a.attach_questions(dialogue, questions, context)
        translations = a.w.Translations.model_validate(first["TRANSLATE"])
        source = next(e["input"] for e in record["events"] if e["stage"] == "GENERATE")
        a.validate_content(content, a.w.PersonalizedSpecification.model_validate(source["specification"]),
                           a.w.PersonalizedProfile.model_validate(source["profile"]), context, translations)
        return {"hardPass": True, "raw": {**content.model_dump(), "translations": translations.model_dump()["segments"]}}
    except (ValueError, KeyError, StopIteration) as error:
        return {"hardPass": False, "reason": type(error).__name__, "raw": first}


@router.get("/v1/evaluation/scenario/{request_id}")
async def status(request_id: UUID, x_service_token: str | None = Header(default=None)):
    settings = authorize(x_service_token)
    path = directory(settings) / (str(request_id) + ".json")
    if not path.exists() or path.is_symlink():
        raise HTTPException(404, "evaluation not found")
    record = json.loads(path.read_text(encoding="utf-8"))
    if record["status"] == "RUNNING" and (datetime.now(timezone.utc) - datetime.fromisoformat(record["startedAt"])).total_seconds() > 510:
        record["status"] = "UNKNOWN"
    return record
