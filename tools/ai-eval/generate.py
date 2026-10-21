"""Capture the configured workflow on synthetic fixtures, using fixed Ollama seeds."""
import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))
import httpx
from app.config import Settings
from app.registry import load_artifact, verify_model
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
        kwargs["json"]["options"]["seed"] = self.seed
        return await super().post(url, **kwargs)


async def capture(dataset, settings, output, *, shadow=False):
    artifact = load_artifact(settings)
    if settings.fallback_enabled or settings.selected_provider != "ollama":
        raise ValueError("paired local evaluation requires local Ollama with fallback disabled")
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
    async with httpx.AsyncClient(timeout=3) as client:
        response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags")
        response.raise_for_status()
        verify_model(artifact, response.json())
    result = {"capturedAt": datetime.now(timezone.utc).isoformat(), "datasetHash": digest(dataset),
              "metadata": {"model": settings.model, "modelDigest": artifact["model"]["digest"],
                           "promptVersion": artifact["version"], "promptHash": artifact["sha256"],
                           "config": {k: getattr(settings, k) for k in artifact["generationConfig"]},
                           "judgeVersion": "pending-offline-judge", "labels": "unjudged",
                           "evaluationOnly": shadow}, "records": []}
    for case in dataset["cases"]:
        for seed in dataset["seeds"]:
            context = case["context"]
            key = f'{case["id"]}:{seed}'
            request = shadow_request(context, key, artifact) if shadow else ConversationTaskRequest(requestId=key, idempotencyKey=key,
                level=context["difficulty"], turnNumber=context["turnNumber"], promptVersion=artifact["version"],
                instruction=artifact["instruction"], context=context)
            started = time.monotonic()
            task, transport = None, "ok"
            try:
                async with SeededClient(seed, timeout=settings.timeout_seconds) as client:
                    job = run_shadow_workflow(request, settings, client, artifact) if shadow else run_workflow(request, settings, client)
                    task = await asyncio.wait_for(job, settings.timeout_seconds)
            except TimeoutError:
                transport = "timeout"
            except Exception:
                transport = "error"
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
    parser.add_argument("--shadow", action="store_true", help="offline B2/C1 only; never enables learner levels")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("capture already exists; select a new output to preserve evidence")
    from app.shadow import ARTIFACT_VERSION
    settings = Settings(artifact_version=ARTIFACT_VERSION) if args.shadow else Settings()
    asyncio.run(capture(json.loads(args.dataset.read_text(encoding="utf-8")), settings, args.output, shadow=args.shadow))
