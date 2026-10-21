# DictaLab AI generation service

This service is an optional boundary for conversation generation. Spring Boot remains the owner of
authentication, sessions, history, quota and persistence. The default provider is unchanged; set
`DICTALAB_CONVERSATION_GENERATION_PROVIDER=python` only after this service and Ollama are ready.

Run locally:

```text
python -m venv .venv
pip install -e ".[test]"
uvicorn app.main:app --port 8091
```

Configuration uses `DICTALAB_AI_` variables. Set `DICTALAB_AI_SERVICE_TOKEN` and the matching Java
`DICTALAB_CONVERSATION_PYTHON_SERVICE_TOKEN` when the endpoint is reachable beyond loopback.

From the repository root, build with `docker compose --profile ai build ai-generation`.
The image uses one Uvicorn process and 1-5 concurrent workflows (`DICTALAB_AI_MAX_CONCURRENCY`, default 1).
Do not add Uvicorn workers while idempotency is process-local.

`DICTALAB_AI_ARTIFACT_VERSION` explicitly selects a bundle from `artifacts/prompts/`.
Production additionally requires `DICTALAB_AI_ARTIFACT_SHA256`, `approvalStatus=approved`, and a passing
evaluation gate. The checked-in bundle is a development candidate and intentionally cannot pass production
readiness yet. Each result reports the selected prompt version and actual last-call model metadata; logs
retain the content/translation provider sequence. Unknown client versions return 400.

Readiness verifies both the artifact and Ollama's model digest. `/metrics` exposes status counters, latency
buckets, active workflows and a zero queue gauge (admission is fail-fast). Send `X-Service-Token` to scrape
metrics when configured. See `docs/ai-platform-runbook.md` for shadow, canary and rollback, and
`tools/ai-eval/README.md` for reproducible regression reports.
