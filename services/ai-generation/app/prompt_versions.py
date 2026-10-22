"""Run frozen prompts through the existing gateway; client instruction is never executed."""
import json
import time
from contextvars import ContextVar

from .config import CLOUD_PROVIDERS
from .model_gateway import ModelGateway
from .ollama import CONTENT_SCHEMA, TRANSLATION_SCHEMA, _context_dict
from .registry import load_artifact
from .redaction import redact
from .validation import LEVEL_BOUNDS
from .schemas import ShadowConversationTaskRequest
from .conversation_context import encounter_state

# Per-request metadata; no prompt/task bodies in logs or response metadata.
provider_calls: ContextVar[list | None] = ContextVar("provider_calls", default=None)
selected_artifact: ContextVar[dict | None] = ContextVar("selected_artifact", default=None)


async def _complete(request, settings, client, schema, purpose, data, temperature, deadline):
    artifact = selected_artifact.get() or load_artifact(settings)
    system = artifact["contentSystem" if purpose == "content" else "translationSystem"]
    if purpose == "content":
        system = artifact["instruction"] + "\n" + system
        examples = [e for e in artifact["fewShotExamples"] if e.get("input", {}).get("difficulty") == request.level
                    and e.get("input", {}).get("turnNumber") == (4 if request.turnNumber == 4 else 2)]
        system += "\nExamples are format only: " + json.dumps(examples, ensure_ascii=False)
    gateway = ModelGateway(settings, client)
    result, provider = await gateway.complete(
        [{"role": "system", "content": system},
         {"role": "user", "content": json.dumps(redact(data), ensure_ascii=False)}],
        (artifact.get("contentSchemas", {}).get(request.level, artifact["contentSchema"])
         if purpose == "content" else artifact["translationSchema"]),
        deadline if deadline is not None else time.monotonic() + settings.timeout_seconds,
        purpose, temperature=temperature)
    calls = provider_calls.get()
    if calls is not None:
        calls.append({"purpose": purpose, "provider": provider,
                      "model": gateway.last_model,
                      "modelDigest": artifact["model"]["digest"] if provider == "ollama" else "unpinned-cloud"})
    return result


async def generate_content(request, settings, client, *, deadline=None):
    data = _context_dict(request)
    data["difficulty"] = request.level
    data["turnNumber"] = request.turnNumber
    artifact = selected_artifact.get() or load_artifact(settings)
    profile = artifact.get("levelProfiles", {}).get(request.level, {})
    shadow = isinstance(request, ShadowConversationTaskRequest)
    if shadow != (artifact.get("evaluationOnly") is True):
        raise ValueError("shadow requests require an offline evaluation artifact")
    if shadow and profile.get("status") != "SHADOW":
        raise ValueError("level is not enabled for shadow generation")
    for key, value in (("levelProfile", profile.get("wordBounds")), ("listeningSkills", profile.get("skills")),
                       ("allowedTaskTypes", profile.get("taskTypes"))):
        if value is not None:
            data.setdefault(key, value)
    minimum, maximum = ((profile["wordBounds"]["minWords"], profile["wordBounds"]["maxWords"])
                        if shadow else LEVEL_BOUNDS[request.level])
    bounds = data.get("levelProfile") or {}
    minimum, maximum = bounds.get("minWords", minimum), bounds.get("maxWords", maximum)
    if type(minimum) is int and type(maximum) is int and 8 <= minimum <= maximum <= 90:
        # The client instruction is ignored, so reconstruct the concrete target on the server too.
        data["turn_brief"] = (f"Turn {request.turnNumber}. Speaker: {data.get('partnerRole', '')}; "
            f"listener: {data.get('learnerRole', '')}. Aim for about {(minimum + maximum) // 2} English words "
            f"within {minimum}-{maximum} words. " + ("Start a NEW independent encounter in this scenario category."
                if request.turnNumber == 4 else "Develop the SAME encounter and preserve settled arrangements."))
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
    data["encounterState"] = encounter_state(data)
    return await _complete(request, settings, client, CONTENT_SCHEMA, "content", data,
                           settings.temperature, deadline)


async def translate_task(content, request, settings, client, *, deadline=None):
    context = _context_dict(request)
    data = {"partnerRole": context.get("partnerRole"), "learnerRole": context.get("learnerRole"),
            "utteranceEn": content.get("utteranceEn"), "facts": content.get("facts", [])}
    return await _complete(request, settings, client, TRANSLATION_SCHEMA, "translation", data,
                           settings.translation_temperature, deadline)
