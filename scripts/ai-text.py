"""Offline stdin/stdout bridge for the existing Node authoring tools. Secrets stay in env."""
import json
from pathlib import Path
import sys
import os
from datetime import datetime, timedelta, timezone
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "services" / "ai-generation"))
from app.offline_text import environment_settings, run
from app.text_gateway import TextRequestV2


def main():
    raw = sys.stdin.buffer.read(256001)
    if len(raw) > 256000:
        raise ValueError("offline text request exceeds limit")
    payload = json.loads(raw)
    if not isinstance(payload, dict) or set(payload) - {"model", "messages", "schema", "purpose", "temperature", "max_tokens"}:
        raise ValueError("unsupported offline text input")
    preferred = os.environ.get("DICTALAB_AI_PRIMARY_PROVIDER", "gemini")
    preferred = "gemini" if preferred == "auto" else preferred
    request = TextRequestV2.model_validate({"schemaVersion": "2", "requestId": str(uuid4()), "provider": preferred,
        "model": payload["model"], "purpose": payload.get("purpose", "offline_authoring"), "messages": payload["messages"],
        "schema": payload.get("schema", {"type": "object"}), "outputMode": "json_object",
        "temperature": payload.get("temperature", 0), "maxOutputTokens": payload.get("max_tokens", 2200),
        "deadline": datetime.now(timezone.utc) + timedelta(seconds=60)})
    result = run(environment_settings(provider=preferred), [m.model_dump() for m in request.messages], request.output_schema,
        model=request.model, purpose=request.purpose, temperature=request.temperature, max_tokens=request.maxOutputTokens, timeout=60)
    print(json.dumps(result.envelope, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # HTTP exceptions can contain credential-bearing URLs and private response text.
        print("offline text failed; outcome may be unknown; " + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
