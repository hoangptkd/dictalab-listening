"""Capture the configured workflow on synthetic fixtures.

Local captures use fixed Ollama seeds. Cloud captures run the same workflow and
artifact against Gemini's OpenAI-compatible endpoint. Those requests have no
seed parameter, so the seed is only a case-repeat label and that difference is
recorded in metadata.
"""
import argparse
import asyncio
import contextlib
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))
import attempt_trace
import httpx
from app.config import CLOUD_PROVIDERS, Settings, cloud_model_id_matches
from app.registry import RegistryError, load_artifact, verify_model
from app.redaction import redact
from app.schemas import ConversationTaskRequest
from app.workflow import run_workflow
from report import digest


def judge_input(context, task):
    turn = context["turnNumber"]
    return {"turnNumber": turn, "context": redact(context), "task": redact(task),
            "rules": "Turn 4 is a NEW independent encounter in the same scenario. Do not impose old arrangements; continuity is not applicable. "
                     "Require concrete evidence of continuing the old encounter to fail transfer." if turn == 4 else
                     "Turns 1-3 continue the same encounter. Transfer is not applicable. Check settled values and newly requested meanings."}


class SeededClient(httpx.AsyncClient):
    def __init__(self, seed, **kwargs):
        super().__init__(**kwargs)
        self.seed = seed

    async def post(self, url, **kwargs):
        # This subclass exists only in the offline executable; production requests cannot set a seed.
        # Cloud bodies have no Ollama options object: Gemini does not accept a seed.
        body = kwargs.get("json")
        if isinstance(body, dict) and isinstance(body.get("options"), dict):
            body["options"]["seed"] = self.seed
        return await super().post(url, **kwargs)


def cloud_model_digest(settings):
    """Pin the cloud endpoint/model pair without pretending it is an Ollama digest."""
    identity = (f"{settings.selected_provider}|{settings.active_cloud_base_url.rstrip('/')}|"
                f"{settings.active_cloud_model}")
    return hashlib.sha256(identity.encode()).hexdigest()


async def verify_cloud_model(settings, client):
    response = await client.get(
        settings.active_cloud_models_url,
        headers={"Authorization": "Bearer " + settings.active_cloud_api_key},
        timeout=20,
    )
    response.raise_for_status()
    payload = response.json()
    ids = [item.get("id") or "" for item in payload.get("data", []) if isinstance(item, dict)]
    if not any(cloud_model_id_matches(settings.active_cloud_model, model_id) for model_id in ids):
        raise RegistryError("configured cloud model is missing")


async def capture(dataset, settings, output, *, shadow=False, attempts=None):
    if settings.repair_enabled:
        raise ValueError("first-pass evaluation requires repair disabled")
    artifact = load_artifact(settings)
    provider = settings.selected_provider
    if provider in CLOUD_PROVIDERS:
        if shadow:
            raise ValueError("shadow capture requires local Ollama")
        if settings.primary_provider != provider:
            raise ValueError("cloud capture requires an explicitly configured primary provider")
        if not settings.active_cloud_api_key:
            raise ValueError("cloud capture requires an API key")
    elif provider != "ollama":
        raise ValueError("unsupported evaluation provider")
    if attempts is not None:
        attempt_trace.install()
    if shadow:
        from app.shadow import shadow_request, run_shadow_workflow
        if settings.production_mode or artifact.get("evaluationOnly") is not True:
            raise ValueError("shadow capture requires a non-production evaluation artifact")
        if digest(dataset) != artifact["datasetHash"]:
            raise ValueError("shadow dataset differs from the frozen artifact")
        if any(getattr(settings, name) != value for name, value in artifact["generationConfig"].items()):
            raise ValueError("shadow generation config differs from the frozen artifact")
        # Reject invalid or planned levels before any provider calls or partial captures.
        for case in dataset["cases"]:
            shadow_request(case["context"], case["id"], artifact)
    elif artifact.get("evaluationOnly") is True:
        raise ValueError("evaluation artifact requires --shadow")
    if provider in CLOUD_PROVIDERS:
        async with httpx.AsyncClient(timeout=30) as client:
            await verify_cloud_model(settings, client)
        model_name = settings.active_cloud_model
        model_digest = cloud_model_digest(settings)
        model_digest_kind = "provider-descriptor-sha256"
        seed_mode = "not-transmitted"
    else:
        async with httpx.AsyncClient(timeout=3) as client:
            response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags")
            response.raise_for_status()
            verify_model(artifact, response.json())
        model_name = settings.model
        model_digest = artifact["model"]["digest"]
        model_digest_kind = "ollama-model-digest"
        seed_mode = "ollama-options"
    result = {"capturedAt": datetime.now(timezone.utc).isoformat(), "datasetHash": digest(dataset),
              "metadata": {"model": model_name, "modelDigest": model_digest,
                           "modelDigestKind": model_digest_kind, "provider": provider,
                           "seedMode": seed_mode,
                           "promptVersion": artifact["version"], "promptHash": artifact["sha256"],
                           "config": {k: getattr(settings, k) for k in artifact["generationConfig"]},
                           "judgeVersion": "pending-offline-judge", "labels": "unjudged",
                           "evaluationOnly": shadow}, "records": []}
    result["metadata"]["generationPolicy"] = {"repairEnabled": False, "maxGenerationAttempts": 1}
    for case in dataset["cases"]:
        for seed in dataset["seeds"]:
            context = case["context"]
            key = f'{case["id"]}:{seed}'
            request = shadow_request(context, key, artifact) if shadow else ConversationTaskRequest(requestId=key, idempotencyKey=key,
                level=context["difficulty"], turnNumber=context["turnNumber"], promptVersion=artifact["version"],
                instruction=artifact["instruction"], context=context)
            started = time.monotonic()
            task, transport = None, "ok"
            observation = (attempt_trace.observe(attempts, request_id=key, level=request.level,
                                                 turn=request.turnNumber, seed=seed)
                           if attempts is not None else contextlib.nullcontext())
            with observation:
                try:
                    async with SeededClient(seed, timeout=settings.timeout_seconds) as client:
                        job = run_shadow_workflow(request, settings, client, artifact) if shadow else run_workflow(request, settings, client)
                        task = await asyncio.wait_for(job, settings.timeout_seconds)
                except TimeoutError as error:
                    transport = "timeout"
                    if attempts is not None:
                        attempt_trace.record_failure("timeout", str(error))
                except Exception as error:
                    transport = "error"
                    if attempts is not None:
                        attempt_trace.record_failure(type(error).__name__, str(error))
            review = judge_input(context, task)
            if shadow:
                review.update(level=request.level, levelProfile=artifact["levelProfiles"][request.level],
                              reviewInstruction=artifact["reviewInstruction"], reviewRubric=artifact["reviewRubric"])
            result["records"].append({"id": case["id"], "seed": seed, "task": redact(task), "transport": transport,
                                      "seconds": time.monotonic()-started, "verdict": "missing",
                                      "judgeInput": review})
            output.parent.mkdir(parents=True, exist_ok=True)
            temporary = output.with_suffix(output.suffix + ".tmp")
            temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(output)
            print(key, transport, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--attempts", type=Path,
                        help="optional JSONL sidecar; keeps first-attempt output and errors per case/seed")
    parser.add_argument("--shadow", action="store_true", help="offline B2/C1 only; never enables learner levels")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("capture already exists; select a new output to preserve evidence")
    if args.attempts is not None and (args.attempts.exists() or args.attempts == args.output):
        parser.error("attempt sidecar already exists; select a new path to preserve evidence")
    from app.shadow import ARTIFACT_VERSION
    settings = Settings(artifact_version=ARTIFACT_VERSION) if args.shadow else Settings()
    asyncio.run(capture(json.loads(args.dataset.read_text(encoding="utf-8")), settings, args.output,
                        shadow=args.shadow, attempts=args.attempts))
