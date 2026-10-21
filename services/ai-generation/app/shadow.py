"""Offline B2/C1 runner; never imported by HTTP endpoints or queue consumers."""
from .prompt_versions import selected_artifact
from .schemas import ShadowConversationTaskRequest
from .workflow import run_workflow

ARTIFACT_VERSION = "conversation-cefr-shadow-v1-20261021"


def shadow_request(context, key, artifact):
    level = context.get("difficulty")
    profile = artifact.get("levelProfiles", {}).get(level, {})
    if artifact.get("evaluationOnly") is not True or profile.get("status") != "SHADOW":
        raise ValueError("only B2/C1 shadow profiles may generate offline tasks")
    if level not in ("B2", "C1"):
        raise ValueError("planned levels cannot generate tasks")
    # Caller focus/history is data; the frozen contract owns the level's limits and vocabulary.
    context = {**context, "levelProfile": profile["wordBounds"], "listeningSkills": profile["skills"],
               "allowedTaskTypes": profile["taskTypes"]}
    return ShadowConversationTaskRequest(requestId=key, idempotencyKey=key, level=level,
        turnNumber=context["turnNumber"], promptVersion=artifact["version"],
        instruction=artifact["instruction"], context=context)


async def run_shadow_workflow(request, settings, client, artifact):
    if settings.production_mode or settings.fallback_enabled or settings.selected_provider != "ollama":
        raise ValueError("shadow generation requires non-production local Ollama without fallback")
    if not isinstance(request, ShadowConversationTaskRequest):
        raise ValueError("shadow generation requires an offline request")
    request = shadow_request(request.context.model_dump(exclude_none=True), request.requestId, artifact)
    token = selected_artifact.set(artifact)
    try:
        return await run_workflow(request, settings, client)
    finally:
        selected_artifact.reset(token)
