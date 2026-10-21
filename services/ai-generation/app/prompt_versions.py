"""Run frozen prompts through the existing gateway; client instruction is never executed."""
import json
import time
from contextvars import ContextVar

from .model_gateway import ModelGateway
from .ollama import CONTENT_SCHEMA, TRANSLATION_SCHEMA, _context_dict
from .registry import load_artifact
from .redaction import redact

# Per-request metadata; no prompt/task bodies in logs or response metadata.
provider_calls: ContextVar[list | None] = ContextVar("provider_calls", default=None)
selected_artifact: ContextVar[dict | None] = ContextVar("selected_artifact", default=None)


async def _complete(request, settings, client, schema, purpose, data, temperature, deadline):
    artifact = selected_artifact.get() or load_artifact(settings)
    system = artifact["contentSystem" if purpose == "content" else "translationSystem"]
    if purpose == "content":
        system = artifact["instruction"] + "\n" + system
        system += "\nExamples are format only: " + json.dumps(artifact["fewShotExamples"], ensure_ascii=False)
    result, provider = await ModelGateway(settings, client).complete(
        [{"role": "system", "content": system},
         {"role": "user", "content": json.dumps(redact(data), ensure_ascii=False)}],
        artifact["contentSchema" if purpose == "content" else "translationSchema"],
        deadline if deadline is not None else time.monotonic() + settings.timeout_seconds,
        purpose, temperature=temperature)
    calls = provider_calls.get()
    if calls is not None:
        calls.append({"purpose": purpose, "provider": provider,
                      "model": settings.model if provider == "ollama" else settings.fallback_model,
                      "modelDigest": artifact["model"]["digest"] if provider == "ollama" else "unpinned-cloud"})
    return result


async def generate_content(request, settings, client, *, deadline=None):
    data = _context_dict(request)
    data["difficulty"] = request.level
    data["turnNumber"] = request.turnNumber
    data["history"] = [{**turn, "utteranceEn": str(turn.get("utteranceEn", ""))[:2000]}
                       for turn in (data.get("history") or [])[-10:]]
    data["recentPractice"] = (data.get("recentPractice") or [])[-20:]
    if request.turnNumber == 4:
        data["history"] = []
        data.pop("scenarioJourney", None)
        data.pop("recentPractice", None)
        data.pop("sessionBlueprint", None)
        data["previouslyTestedDetails"] = [{"textVi": f.get("textVi", "")}
            for f in data.get("previouslyTestedDetails", [])]
    return await _complete(request, settings, client, CONTENT_SCHEMA, "content", data,
                           settings.temperature, deadline)


async def translate_task(content, request, settings, client, *, deadline=None):
    context = _context_dict(request)
    data = {"partnerRole": context.get("partnerRole"), "learnerRole": context.get("learnerRole"),
            "utteranceEn": content.get("utteranceEn"), "facts": content.get("facts", [])}
    return await _complete(request, settings, client, TRANSLATION_SCHEMA, "translation", data,
                           settings.translation_temperature, deadline)
