"""Optional Langfuse tracing. Telemetry never retries or changes AI execution.

Only explicitly selected content and metadata leave the service. No Settings,
HTTP headers, endpoints, or exception messages are captured automatically.
"""
from __future__ import annotations

import functools
import hashlib
import json
import logging
import os
import threading
from contextlib import ExitStack, contextmanager
from contextvars import ContextVar

from .redaction import redact

logger = logging.getLogger("dictalab.ai.observability")
_client = None
_lock = threading.Lock()
_root = ContextVar("ai_trace_root", default=None)
_generation = ContextVar("ai_trace_generation", default=None)
_evaluation = ContextVar("ai_trace_evaluation", default={})
_suspended = ContextVar("ai_tracing_suspended", default=False)


def client():
    global _client
    if _suspended.get() or os.getenv("LANGFUSE_TRACING_ENABLED", "false").lower() != "true":
        return None
    with _lock:
        if _client is None:
            try:
                from langfuse import Langfuse
                from langfuse.span_filter import is_langfuse_span
                if not all(os.getenv(k) for k in ("LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY", "LANGFUSE_BASE_URL")):
                    raise ValueError("incomplete tracing configuration")
                _client = Langfuse(
                    base_url=os.environ["LANGFUSE_BASE_URL"],
                    environment=os.getenv("LANGFUSE_TRACING_ENVIRONMENT", "development"),
                    release=os.getenv("LANGFUSE_RELEASE", "unknown"),
                    should_export_span=is_langfuse_span,
                    timeout=5,
                )
            except Exception:
                logger.warning("ai_tracing_unavailable")
    return _client


def _safe(observation, method, **values):
    if observation is not None:
        try:
            return getattr(observation, method)(**values)
        except Exception:
            logger.warning("ai_tracing_write_failed")


def content(value):
    if os.getenv("DICTALAB_AI_TRACE_CONTENT", "false").lower() != "true":
        return {"capture": "disabled"}
    try:
        if hasattr(value, "model_dump"):
            value = value.model_dump(mode="json")
        # Convert nested Pydantic/UUID/datetime values before redaction, never stringify Settings.
        serialized = json.dumps(value, ensure_ascii=False, default=lambda _: "[unsupported]")
        cleaned = redact(json.loads(serialized))
        serialized = json.dumps(cleaned, ensure_ascii=False)
        if len(serialized) > 64000:
            return {"truncated": True, "redactedSha256": hashlib.sha256(serialized.encode()).hexdigest(),
                    "preview": serialized[:64000]}
        return cleaned
    except Exception:
        return {"capture": "unavailable"}


@contextmanager
def observation(name, *, as_type="span", input=None, metadata=None, version=None):
    """Disable automatic exception recording: errors can contain private URLs/text."""
    span = None
    stack = ExitStack()
    try:
        sdk = client()
        if sdk is not None:
            from opentelemetry.trace import use_span
            span = sdk.start_observation(name=name, as_type=as_type, input=content(input),
                                         metadata=metadata, version=version)
            stack.enter_context(use_span(span._otel_span, end_on_exit=False,
                                         record_exception=False, set_status_on_exception=False))
    except Exception:
        logger.warning("ai_tracing_start_failed")
    generation_token = _generation.set(span) if as_type == "generation" else None
    try:
        yield span
    except BaseException as error:
        _safe(span, "update", level="ERROR", status_message=type(error).__name__)
        raise
    finally:
        if generation_token is not None:
            _generation.reset(generation_token)
        try:
            stack.close()
        except Exception:
            logger.warning("ai_tracing_context_failed")
        _safe(span, "end")


def identity(settings):
    # Operator supplied fingerprints; absent means unknown, never infer trained weights from a model name.
    return {"modelDigest": settings.model_digest if settings.selected_provider == "ollama" else "provider-managed",
            **{key: os.environ[env] for key, env in (
                ("modelRevision", "DICTALAB_AI_TRACE_MODEL_REVISION"),
                ("adapterHash", "DICTALAB_AI_TRACE_ADAPTER_HASH"),
                ("trainingRun", "DICTALAB_AI_TRACE_TRAINING_RUN"),
                ("variant", "DICTALAB_AI_TRACE_VARIANT")) if os.getenv(env)}}


@contextmanager
def suspend_tracing():
    token = _suspended.set(True)
    try:
        yield
    finally:
        _suspended.reset(token)


@contextmanager
def evaluation_context(**metadata):
    """Trusted synthetic evaluation IDs/hashes, never arbitrary user metadata."""
    token = _evaluation.set(metadata)
    try:
        yield
    finally:
        _evaluation.reset(token)


@contextmanager
def operation(name, settings, input, metadata, user_id=None):
    if _root.get() is not None:
        yield _root.get()
        return
    metadata = {**metadata, **identity(settings), **_evaluation.get()}
    configuration = {k: metadata.get(k) for k in ("promptHash", "model", "modelDigest", "modelRevision",
                    "adapterHash", "trainingRun", "variant", "baseRevision", "adapterSha256", "programHash")}
    metadata["configurationHash"] = hashlib.sha256(json.dumps(configuration, sort_keys=True).encode()).hexdigest()
    stack = ExitStack()
    try:
        if client() is not None:
            from langfuse import propagate_attributes
            # Propagated attributes have small scalar values; full metadata stays on the root span.
            propagated = {key: str(value) for key, value in metadata.items()
                          if isinstance(value, (str, int, float, bool)) and len(str(value)) <= 200}
            stack.enter_context(propagate_attributes(user_id=user_id, metadata=propagated,
                tags=["dictalab", "evaluation" if _evaluation.get() else "application"] +
                     (["instrumentation-smoke"] if _evaluation.get().get("responseOrigin") == "synthetic-fixture-not-live-model" else []),
                trace_name=name))
    except Exception:
        logger.warning("ai_tracing_context_failed")
    with observation(name, input=input, metadata=metadata, version=metadata["configurationHash"]) as span:
        token = _root.set(span)
        try:
            yield span
        finally:
            _root.reset(token)
            if span is not None:
                logger.info("ai_trace_completed", extra={"trace_id": span.trace_id,
                            "request_id": metadata.get("requestId")})
            try:
                stack.close()
            except Exception:
                logger.warning("ai_tracing_context_failed")


def trace_job(function):
    @functools.wraps(function)
    async def wrapped(event, settings, *args, **kwargs):
        if client() is None or _root.get() is not None:
            return await function(event, settings, *args, **kwargs)
        # Lazy import avoids the jobs -> workflow module cycle.
        from .jobs import capability_metadata
        name = "ai." + event.payload.kind + "." + getattr(event.payload, "phase", "execute").lower()
        metadata = {**capability_metadata(event.payload, settings), "jobId": str(event.jobId),
                    "requestId": str(event.requestId), "attempt": getattr(event, "attempt", 1),
                    "programHash": getattr(event.payload, "programHash", None)}
        context = getattr(event.payload, "learningContext", None)
        if context is not None:
            from .scenario_workflow import example_metadata
            metadata.update(example_metadata(event.payload.promptHash, event.payload.phase))
            snapshot = context.model_dump(mode="json", exclude_none=True)
            metadata.update(programCode=context.programCode, programVersion=context.programVersion,
                            focusSkill=context.focusSkill, profileId=context.profile.id,
                            qualityPolicyVersion=context.qualityPolicyVersion,
                            calibrationStatus=context.calibrationStatus,
                            learningContextHash=hashlib.sha256(json.dumps(snapshot, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":")).encode()).hexdigest())
        trace_input = event.payload.model_dump(mode="json", exclude={"promptHash", "contractVersion", "programHash", "kind"})
        with operation(name, settings, trace_input, metadata, event.userScope) as span:
            try:
                result = await function(event, settings, *args, **kwargs)
            except BaseException as error:
                _safe(span, "score", name="operation_success", value=0,
                      comment="Execution outcome only; not a content quality judgment.")
                _safe(span, "update", metadata={"outcome": "QUALITY_REJECTED" if type(error).__name__ == "QualityRejected"
                                                else "FAILED_OR_UNKNOWN"})
                raise
            output = result.get("task", result)
            if event.payload.kind == "conversation_demo":
                output = {"mode": result["mode"], "arms": result["arms"]}
            elif event.payload.kind == "tts":
                output = {k: v for k, v in output.items() if k != "audioBase64"}
            _safe(span, "update", output=content(output))
            _safe(span, "score", name="operation_success", value=1,
                  comment="Execution outcome only; not a content quality judgment.")
            return result
    return wrapped


def trace_workflow(function):
    @functools.wraps(function)
    async def wrapped(request, settings, client):
        if _suspended.get() or os.getenv("LANGFUSE_TRACING_ENABLED", "false").lower() != "true":
            return await function(request, settings, client)
        from .prompt_versions import selected_artifact
        artifact = selected_artifact.get() or {}
        with operation("ai.conversation.generate", settings, request,
                       {"requestId": str(request.requestId), "promptVersion": request.promptVersion,
                        "promptHash": artifact.get("sha256", settings.artifact_sha256)}) as span:
            result = await function(request, settings, client)
            _safe(span, "update", output=content(result))
            return result
    return wrapped


def trace_generation(function):
    @functools.wraps(function)
    async def wrapped(self, messages, schema, deadline, purpose, **options):
        if client() is None:
            return await function(self, messages, schema, deadline, purpose, **options)
        settings = self._settings
        prompt = {"system": [m["content"] for m in messages if m.get("role") == "system"], "schema": schema}
        prompt_hash = hashlib.sha256(json.dumps(prompt, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with observation(purpose, as_type="generation", input={"messages": messages, "schema": schema},
                         metadata={"stagePromptHash": prompt_hash, "provider": settings.selected_provider,
                                   **identity(settings)}, version=prompt_hash) as span:
            _safe(span, "update", model=self.last_model, model_parameters={
                "temperature": options.get("temperature") if options.get("temperature") is not None else settings.temperature,
                "max_tokens": settings.num_predict,
                **{k: v for k, v in options.items() if k != "temperature"}})
            result, provider = await function(self, messages, schema, deadline, purpose, **options)
            _safe(span, "update", output=content(result),
                  metadata={"provider": provider, "sourceId": self.last_source_id, "requestedModel": self.last_model})
            return result, provider
    return wrapped


def record_response(raw, *, model, input_tokens=None, output_tokens=None, cached_tokens=None,
                    finish_reason=None, reasoning=None):
    """Called before parsing/validation, so malformed generations remain debuggable."""
    usage = {key: value for key, value in (("input", input_tokens), ("output", output_tokens))
             if type(value) is int and value >= 0}
    if type(cached_tokens) is int and 0 <= cached_tokens <= usage.get("input", -1):
        usage["input_cached_tokens"] = cached_tokens
        usage["input"] -= cached_tokens
    _safe(_generation.get(), "update", output=content(raw), model=model, usage_details=usage or None,
          metadata={"finishReason": finish_reason, "providerReasoning": content(reasoning)})


def trace_gate(function):
    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        try:
            result = function(*args, **kwargs)
        except ValueError:
            _safe(_root.get(), "score", name="gate." + function.__name__, value=0,
                  score_id=_gate_id(function.__name__),
                  comment="Existing validator/reviewer gate; not independently verified human quality.")
            _safe(_root.get(), "update", metadata={"rejectedGate": function.__name__})
            raise
        _safe(_root.get(), "score", name="gate." + function.__name__, value=1,
              score_id=_gate_id(function.__name__),
              comment="Existing validator/reviewer gate; not independently verified human quality.")
        return result
    return wrapped


def _gate_id(name):
    span = _root.get()
    # Validators are reused within a phase. Upsert one final value per gate/trace.
    return hashlib.sha256((span.trace_id + name).encode()).hexdigest()[:32] if span is not None else None


def flush(*, shutdown=False):
    if _client is not None:
        try:
            _client.shutdown() if shutdown else _client.flush()
        except Exception:
            logger.warning("ai_tracing_flush_failed")
