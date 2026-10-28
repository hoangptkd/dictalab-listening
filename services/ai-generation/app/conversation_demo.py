"""Explicit evaluation lane: frozen prompts, durable jobs, no production release promotion."""
import asyncio
import hashlib
import json
import re
from pathlib import Path
import time
from typing import Literal
from uuid import uuid4

import httpx
from jsonschema import Draft202012Validator
from pydantic import Field, model_validator

from .coach import StrictModel
from .config import CLOUD_PROVIDERS
from .model_gateway import ModelGateway
from .ollama import merge_content_and_translation
from .redaction import redact
from .registry import artifact_hash
from .schemas import ConversationContext
from .validation import validate_task
from . import guided_translation

PROMPT_VERSION = "conversation-demo-guided-r13-20261028"
PROMPT_SHA = "62d4e1c7e03c26ebd9a5dca7d186bb27c22fd62be6078f09a72c2643402cf5cb"
BASE_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"
ADAPTER_SHA = "1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf"


class ConversationDemoInput(StrictModel):
    kind: Literal["conversation_demo"] = "conversation_demo"
    level: Literal["A1", "A2", "B1"]
    mode: Literal["practice", "compare"] = "practice"
    variant: Literal["base", "full07", "cloud"] = "full07"
    allowFallback: bool = False
    context: ConversationContext

    @model_validator(mode="after")
    def comparison_is_local(self):
        if self.mode == "compare" and (self.allowFallback or self.variant != "full07"):
            raise ValueError("comparison runs base and full07 without fallback")
        return self


def artifact(settings):
    value = json.loads((Path(settings.registry_dir) / (PROMPT_VERSION + ".json")).read_text(encoding="utf-8"))
    if value.get("sha256") != PROMPT_SHA or artifact_hash(value) != PROMPT_SHA:
        raise ValueError("DEMO_PROMPT_DRIFT")
    implementation = Path(guided_translation.__file__).read_text(encoding="utf-8").encode()
    if hashlib.sha256(implementation).hexdigest() != value.get("guidanceImplementationSha256"):
        raise ValueError("DEMO_GUIDANCE_DRIFT")
    return value


def metadata(settings):
    artifact(settings)
    return {"provider": "evaluation", "model": "Qwen/Qwen3.5-4B", "baseRevision": BASE_REVISION,
        "adapterSha256": ADAPTER_SHA, "promptVersion": PROMPT_VERSION, "promptHash": PROMPT_SHA,
        "schemaVersion": "1", "evaluationOnly": True}


class LocalUnavailable(Exception):
    def __init__(self, code, fallback_safe=False, raw=None):
        self.code, self.fallback_safe, self.raw = code, fallback_safe, raw


def messages(frozen, context, purpose, content=None):
    if purpose == "content":
        system = frozen["instruction"] + "\n" + frozen["contentSystem"] + "\n" + frozen["demoContentContract"]
        schema = frozen["contentSchemas"][context["difficulty"]]
        slots = schema["properties"]["slots"]
        data = {**context, "slotCountRange": {"min": slots["minItems"], "max": slots["maxItems"]}, "outputSchema": schema}
    else:
        system = frozen["translationSystem"]
        data = {"partnerRole": context.get("partnerRole"), "learnerRole": context.get("learnerRole"),
            "utteranceEn": content["utteranceEn"], "facts": content["facts"]}
        if guided_translation.in_scope(context):
            data = guided_translation.enrich(data)
            system = frozen["guidedTranslationSystem"]
    return [{"role": "system", "content": system},
        {"role": "user", "content": json.dumps(redact(data), ensure_ascii=False)}]


async def local_call(client, settings, wire, schema, variant, deadline):
    remaining = int(min(1800, deadline - time.monotonic()))
    if remaining < 1:
        raise LocalUnavailable("DEADLINE")
    request_id = str(uuid4())
    try:
        async with client.stream("POST", settings.model_serving_url.rstrip("/") + "/v1/infer",
                json={"requestId": request_id, "variant": variant, "messages": wire,
                      "maxNewTokens": 1024, "timeoutSeconds": remaining},
                headers={"X-Service-Token": settings.model_serving_token}, timeout=remaining + 5) as response:
            body = bytearray()
            async for block in response.aiter_bytes():
                body.extend(block)
                if len(body) > 64000:
                    raise LocalUnavailable("INVALID_MODEL_RESPONSE")
            if response.status_code != 200:
                raise LocalUnavailable("MODEL_UNAVAILABLE" if response.status_code == 503 else "MODEL_REJECTED",
                    response.status_code in (422, 503))
            try:
                envelope = json.loads(body)
            except (ValueError, TypeError) as error:
                raise LocalUnavailable("INVALID_MODEL_RESPONSE") from error
    except (httpx.ConnectError, httpx.ConnectTimeout) as error:
        raise LocalUnavailable("MODEL_OFFLINE", True) from error
    except httpx.TransportError as error:
        raise LocalUnavailable("LOCAL_OUTCOME_UNKNOWN") from error
    if (not isinstance(envelope, dict) or envelope.get("requestId") != request_id or envelope.get("provider") != "local_cpu"
            or envelope.get("model") != "Qwen/Qwen3.5-4B" or envelope.get("baseRevision") != BASE_REVISION
            or envelope.get("variant") != variant
            or envelope.get("adapterSha256") != (ADAPTER_SHA if variant == "full07" else None)):
        raise LocalUnavailable("MODEL_PROVENANCE_MISMATCH")
    if envelope.get("deadlineExceeded") or envelope.get("truncated"):
        raise LocalUnavailable("INCOMPLETE_OUTPUT", True, redact(envelope.get("raw", "")[:16000]))
    try:
        raw = json.loads(envelope["raw"])
        Draft202012Validator(schema).validate(raw)
    except (ValueError, KeyError, TypeError) as error:
        raise LocalUnavailable("INVALID_MODEL_JSON", True, redact(str(envelope.get("raw", ""))[:16000])) from error
    except Exception as error:
        from jsonschema.exceptions import ValidationError
        if isinstance(error, ValidationError):
            raise LocalUnavailable("INVALID_MODEL_SCHEMA", True, redact(str(envelope.get("raw", ""))[:16000])) from error
        raise
    return raw, {key: envelope.get(key) for key in ("provider", "model", "baseRevision", "variant", "adapterSha256",
        "baseMode", "dtype", "device", "greedy", "thinking", "versions", "inputTokens", "outputTokens", "latencyMs")}


async def arm(payload, settings, frozen, variant, client, deadline, store, scope, job_id):
    context = redact(payload.context.model_dump(exclude_none=True))
    # Level and its bounds are server-owned. Client instructions cannot promote a model.
    profile = frozen["levelProfiles"][payload.level]
    context.update(difficulty=payload.level, levelProfile=profile["wordBounds"],
        listeningSkills=profile["skills"], allowedTaskTypes=profile["taskTypes"])
    context.setdefault("turnNumber", 1)
    calls, values = [], {}
    cloud = variant == "cloud"
    if cloud:
        if settings.selected_provider not in CLOUD_PROVIDERS:
            raise LocalUnavailable("CLOUD_NOT_CONFIGURED")
        # Reserve both pipeline calls atomically before any cloud request. Unknown outcomes retain it.
        await asyncio.to_thread(store.reserve_demo_cloud, job_id, scope, settings, 2)
    for purpose in ("content", "translation"):
        schema = frozen["contentSchemas"][payload.level] if purpose == "content" else frozen["translationSchema"]
        wire = messages(frozen, context, purpose, values.get("content"))
        if cloud:
            gateway = ModelGateway(settings, client)
            value, provider = await gateway.complete(wire, schema, deadline, "demo_" + purpose, temperature=0)
            Draft202012Validator(schema).validate(value)
            source = {"provider": provider, "model": gateway.last_model, "variant": "cloud", "modelDigest": "provider-managed"}
        else:
            value, source = await local_call(client, settings, wire, schema, variant, deadline)
        calls.append({"purpose": purpose, **source})
        values[purpose] = value
    task = merge_content_and_translation(values["content"], values["translation"])
    errors = validate_task(task, level=payload.level, level_profile=profile["wordBounds"])
    guided = guided_translation.in_scope(context)
    if guided:
        source = {"utteranceEn": values["content"]["utteranceEn"], "facts": values["content"]["facts"]}
        errors += guided_translation.validate(guided_translation.enrich(source), values["translation"])
    stripped = task["utteranceEn"].strip(" \t\n\r\f\v")
    word_count = len(re.split(r"[ \t\n\r\f\v]+", stripped)) if stripped else 0
    return {"variant": variant, "valid": not errors, "task": task if not errors else None,
        "candidate": task, "validationErrors": errors, "semanticReview": "not_evaluated", "calls": calls,
        "translationGuidance": "source-guided-r13" if guided else "legacy",
        "wordCount": word_count, "wordBounds": profile["wordBounds"]}


async def execute(event, settings, store):
    payload, frozen = event.payload, artifact(settings)
    deadline = time.monotonic() + min(3600, event.deadline.timestamp() - time.time())
    arms = []
    async with httpx.AsyncClient() as client:
        # Sequential arms share exactly the same frozen prompt/context and save RAM.
        for variant in (("base", "full07") if payload.mode == "compare" else (payload.variant,)):
            fallback_safe = False
            try:
                result = await arm(payload, settings, frozen, variant, client, deadline, store, event.userScope, str(event.jobId))
                fallback_safe = not result["valid"]
            except LocalUnavailable as error:
                result = {"variant": variant, "valid": False, "task": None, "error": error.code, "semanticReview": "not_evaluated"}
                if error.raw is not None:
                    result["raw"] = error.raw
                fallback_safe = error.fallback_safe
            arms.append(result)
            if (payload.mode == "practice" and variant != "cloud" and payload.allowFallback and fallback_safe):
                try:
                    replacement = await arm(payload, settings, frozen, "cloud", client, deadline, store, event.userScope, str(event.jobId))
                    replacement["fallbackFrom"] = variant
                    arms.append(replacement)
                except (OverflowError, LocalUnavailable) as error:
                    result["fallbackError"] = "BUDGET_EXHAUSTED" if isinstance(error, OverflowError) else error.code
                except Exception:
                    # Preserve the local evidence and consumed reservation after an uncertain cloud outcome.
                    result["fallbackError"] = "CLOUD_OUTCOME_FAILED_OR_UNKNOWN"
    return {**metadata(settings), "mode": payload.mode, "arms": arms, "generationId": str(uuid4()),
        "requestId": str(event.requestId), "task": {}, "semanticReview": "not_evaluated"}
