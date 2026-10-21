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


async def capture(dataset, settings, output):
    artifact = load_artifact(settings)
    if settings.fallback_enabled:
        raise ValueError("paired local evaluation requires fallback disabled")
    async with httpx.AsyncClient(timeout=3) as client:
        response = await client.get(settings.ollama_url.rstrip("/") + "/api/tags")
        response.raise_for_status()
        verify_model(artifact, response.json())
    result = {"capturedAt": datetime.now(timezone.utc).isoformat(), "datasetHash": digest(dataset),
              "metadata": {"model": settings.model, "modelDigest": artifact["model"]["digest"],
                           "promptVersion": artifact["version"], "promptHash": artifact["sha256"],
                           "config": {k: getattr(settings, k) for k in artifact["generationConfig"]},
                           "judgeVersion": "pending-offline-judge", "labels": "unjudged"}, "records": []}
    for case in dataset["cases"]:
        for seed in dataset["seeds"]:
            context = case["context"]
            key = f'{case["id"]}:{seed}'
            request = ConversationTaskRequest(requestId=key, idempotencyKey=key,
                level=context["difficulty"], turnNumber=context["turnNumber"], promptVersion=artifact["version"],
                instruction=artifact["instruction"], context=context)
            started = time.monotonic()
            task, transport = None, "ok"
            try:
                async with SeededClient(seed, timeout=settings.timeout_seconds) as client:
                    task = await asyncio.wait_for(run_workflow(request, settings, client), settings.timeout_seconds)
            except TimeoutError:
                transport = "timeout"
            except Exception:
                transport = "error"
            result["records"].append({"id": case["id"], "seed": seed, "task": redact(task), "transport": transport,
                                      "seconds": time.monotonic()-started, "verdict": "missing",
                                      "judgeInput": judge_input(context, task)})
            output.parent.mkdir(parents=True, exist_ok=True)
            temporary = output.with_suffix(output.suffix + ".tmp")
            temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(output)
            print(key, transport, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("capture already exists; select a new output to preserve evidence")
    asyncio.run(capture(json.loads(args.dataset.read_text(encoding="utf-8")), Settings(), args.output))
