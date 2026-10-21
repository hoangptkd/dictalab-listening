# AI Platform runbook

The Python sidecar remains optional. Global routing uses
`DICTALAB_CONVERSATION_GENERATION_PROVIDER=python`. Keep the existing provider during shadow/canary.
Production requires the service token in both Java/Python and a pinned, approved prompt/model bundle.
The included development bundle has not passed the release gate and intentionally fails production readiness.

The `ai` Compose profile binds port 8091 to loopback. Start the Engine using Explorer on this Windows PC,
then build/start only `ai-generation`. Readiness checks the configured model's presence/digest and the
artifact's hash/approval. A container healthcheck reports unhealthy until these pass; Compose does not
automatically prevent a host Java process from calling an unhealthy container, so verify readiness before
enabling a route. Python uses one Uvicorn process with 1-5 concurrent workflows (default 1), no worker queue,
and a 15-minute in-memory idempotency TTL. Restarting loses that cache; retain one process/replica until a
shared store is implemented. Java uses HTTP/1.1 because Uvicorn rejects an h2c upgrade on this boundary.

Shadow: set `DICTALAB_CONVERSATION_SHADOW_SAMPLE_RATE` between 0 and 1 (default 0),
`DICTALAB_CONVERSATION_SHADOW_MAX_REQUESTS_PER_HOUR` (default 10 per Java process), and
`DICTALAB_CONVERSATION_SHADOW_TIMEOUT_SECONDS` (default 30). One daemon worker uses a zero-length queue;
busy or exhausted samples are skipped immediately. Shadow runs only after a successful existing-provider
turn, sends the existing allowlisted context, discards the result and cannot update session state, history,
score or user quota. Python disables paid fallback for `X-AI-Mode: shadow`. The hourly budget resets on Java
restart, so do not scale pilot processes without coordinating that limit. Python's global concurrency limit
still applies; shadow may briefly consume local model capacity. Set sample rate 0 and restart to stop it.

Canary: set `DICTALAB_CONVERSATION_CANARY_ENABLED=true` plus comma-separated
`DICTALAB_CONVERSATION_CANARY_USERS` (internal numeric IDs) or `DICTALAB_CONVERSATION_CANARY_SESSIONS`.
An empty allowlist is rejected. Every turn in a listed session/user follows Python; other learners keep
the configured existing provider. All normal validation, quota and persistence stay in Java. There is no
automatic percentage expansion or alternate-provider retry after an ambiguous failure.

Rollback: set canary false, shadow sample rate 0, and generation provider to the prior `ollama` or `shopaikey`,
then restart Java. No migration or learner-data edit is required. For prompt/model rollback, select another
approved artifact/version/model/hash and restart Python. Mount the reviewed artifact directory read-only to
switch bundles without rebuilding Java. Unknown client versions return 400; old aliases resolve only to the
configured bundle.

Observe `/metrics` on port 8091 using `X-Service-Token` when configured. Configure the local metrics collector
to send this header; do not expose the endpoint publicly. Useful PromQL over the bounded mode/status labels:

```promql
histogram_quantile(0.95, sum by (le, mode) (rate(dictalab_ai_duration_seconds_bucket[10m])))
sum(rate(dictalab_ai_requests_total{status="504"}[10m])) / sum(rate(dictalab_ai_requests_total[10m]))
sum(rate(dictalab_ai_requests_total{status="429"}[10m]))
```

Use quantiles 0.50/0.95/0.99; retain sample count and inspect 400/503/504 separately. Python exports active
workflows and a zero queue depth; Java exports shadow active/queue counters and Python duration histograms.
Use `docker stats --no-stream dictalab-ai-generation` for CPU/RAM. Content/schema/role/translation quality
comes from the offline report, not an automatic production judge. Correlate request IDs with node/provider
logs and response model/digest/prompt metadata. Logs/reports contain no raw learner prompt/task bodies.

Run `python tools/ai-eval/smoke.py`, Python tests and Java contract/routing tests before changing flags.
CI validates the Compose config, image imports, readiness up/down/drift and synthetic 45-sample regression.
The branch workflow delivers code only. Production deployment still requires the main release workflow and
a verified application health check; this implementation does not enable production routing.

Do not put learner transcripts, credentials, API keys or raw provider output in reports or logs.
