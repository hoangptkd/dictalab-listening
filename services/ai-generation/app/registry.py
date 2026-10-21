"""Load one explicitly configured, immutable prompt/model bundle. No client paths."""
import hashlib
import json
import re
from pathlib import Path


class RegistryError(ValueError):
    pass


def artifact_hash(artifact):
    payload = {k: v for k, v in artifact.items() if k != "sha256"}
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def load_artifact(settings):
    version = settings.artifact_version
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", version):
        raise RegistryError("invalid configured artifact version")
    try:
        artifact = json.loads((Path(settings.registry_dir) / (version + ".json")).read_text(encoding="utf-8"))
        if not isinstance(artifact, dict):
            raise RegistryError("artifact is not an object")
        if artifact["version"] != version or artifact_hash(artifact) != artifact["sha256"]:
            raise RegistryError("artifact hash mismatch")
        if settings.artifact_sha256 and artifact["sha256"] != settings.artifact_sha256:
            raise RegistryError("configured artifact hash mismatch")
        if artifact["model"]["name"] != settings.model:
            raise RegistryError("configured model differs from artifact")
        if settings.model_digest not in ("unknown", artifact["model"]["digest"]):
            raise RegistryError("configured model digest differs from artifact")
        for field in ("contentSystem", "translationSystem", "instruction", "createdAt", "datasetHash"):
            if not isinstance(artifact[field], str) or not artifact[field].strip():
                raise RegistryError("artifact missing required metadata")
        if not isinstance(artifact["fewShotExamples"], list) or not isinstance(artifact["metricReport"], dict):
            raise RegistryError("artifact missing evaluation metadata")
        if not re.fullmatch(r"(?:sha256:)?[a-f0-9]{64}", artifact["model"]["digest"]):
            raise RegistryError("artifact model digest must be pinned")
        for field in ("contentSchema", "translationSchema"):
            if not isinstance(artifact[field], dict) or artifact[field].get("type") != "object":
                raise RegistryError("artifact schema missing")
        if artifact["metricReport"].get("datasetHash") != artifact["datasetHash"]:
            raise RegistryError("artifact report dataset differs")
        if settings.production_mode and (not settings.artifact_sha256 or artifact["approvalStatus"] != "approved"
                or artifact["metricReport"].get("gate", {}).get("releaseAllowed") is not True):
            raise RegistryError("production requires a pinned, approved artifact with a passing gate")
        if settings.production_mode:
            for name, expected in artifact["generationConfig"].items():
                if getattr(settings, name) != expected:
                    raise RegistryError("production config differs from evaluated artifact")
        return artifact
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        raise RegistryError("configured prompt/model artifact is unavailable or invalid") from exc


def resolve_version(client_version, artifact):
    # Aliases are frozen inside the server's allowlist; never load a client-specified file.
    if client_version not in [artifact["version"], *artifact.get("clientAliases", [])]:
        raise RegistryError("unsupported client prompt version")
    return artifact["version"]


def verify_model(artifact, tags):
    expected = artifact["model"]
    models = tags.get("models", [])
    match = next((m for m in models if m.get("name") == expected["name"]), None)
    if not match:
        raise RegistryError("configured Ollama model is missing")
    if match.get("digest", "").removeprefix("sha256:") != expected["digest"].removeprefix("sha256:"):
        raise RegistryError("model digest drift detected")
