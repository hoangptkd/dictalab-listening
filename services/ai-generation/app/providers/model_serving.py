"""Existing pinned CPU inference connection, restricted to the evaluation lane."""
import json
import time
from uuid import uuid4
import httpx
from jsonschema import Draft202012Validator
from ..redaction import redact
from ..observability import record_response

BASE_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"
ADAPTER_SHA = "1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf"

class LocalUnavailable(Exception):
    def __init__(self, code, fallback_safe=False, raw=None):
        self.code, self.fallback_safe, self.raw = code, fallback_safe, raw


class ModelServingProvider:
    @staticmethod
    async def complete(client, settings, wire, schema, variant, deadline):
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
        if isinstance(envelope, dict):
            record_response(envelope.get("raw"), model=envelope.get("model"),
                            input_tokens=envelope.get("inputTokens"), output_tokens=envelope.get("outputTokens"))
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
