# AI Platform runbook

The Python sidecar remains optional. Global routing uses
`DICTALAB_CONVERSATION_GENERATION_PROVIDER=python`. Keep the existing provider during shadow/canary.
Production requires the service token in both Java/Python and a pinned, approved prompt/model bundle.
The included development bundle has not passed the release gate and intentionally fails production readiness.

The `ai` Compose profile binds port 8091 to loopback. Start the Engine using Explorer on this Windows PC,
then build/start only `ai-generation`. Readiness checks the configured model's presence/digest and the
artifact's hash/approval. A container healthcheck reports unhealthy until these pass; Compose does not
automatically prevent a host Java process from calling an unhealthy container, so verify readiness before
enabling a route. Python uses one Uvicorn process with 1-5 concurrent conversation workflows (default 1),
and a 15-minute in-memory idempotency TTL. Restarting loses that cache; retain one process/replica until a
shared conversation store is implemented. Background capabilities use their own durable ledger described below.
Java uses HTTP/1.1 because Uvicorn rejects an h2c upgrade on this boundary.

Shadow: set `DICTALAB_CONVERSATION_SHADOW_SAMPLE_RATE` between 0 and 1 (default 0),
`DICTALAB_CONVERSATION_SHADOW_MAX_REQUESTS_PER_HOUR` (default 10 per Java process), and
`DICTALAB_CONVERSATION_SHADOW_TIMEOUT_SECONDS` (default 30). One daemon worker uses a zero-length queue;
Java forwards its remaining budget in `X-AI-Timeout-Ms`, which also bounds the Python workflow and model calls.
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

## T10/T11: production cloud, local Ollama and background capabilities

Use `.env.ai-production.example` or `.env.ai-local.example` as routing overlays, keeping the actual keys,
service token and URL-encoded broker URL in protected runtime configuration. Compose forwards
`SHOPAIKEY_API_KEY` to Python's `DICTALAB_AI_CLOUD_API_KEY` and `CARTESIA_API_KEY` to its TTS client.
For native Python set those Python variables explicitly. The local launcher selects Spring's `local` profile
and forces Ollama for text AI, including legacy grading and review; it no longer needs a ShopAIKey key.
Audio still requires Cartesia. Default Java production routing remains ShopAIKey, including rollback clients.
Java `DICTALAB_AI_TEXT_PROVIDER` must match Python's selected primary; mismatches fail before paid job calls.

OpenCode Console is an explicit Python cloud primary (`DICTALAB_AI_PRIMARY_PROVIDER=opencode`). Compose
forwards `OPENCODE_API_KEY`, `OPENCODE_BASE_URL` and `OPENCODE_MODEL` to `DICTALAB_AI_OPENCODE_*`.
Only models using the OpenAI chat-completions protocol are supported; Claude Messages, GPT Responses and
Gemini endpoints need separate adapters. Use `https://opencode.ai/inference/go` with a Go workspace and
`https://opencode.ai/inference` for pay-as-you-go. Go requests need a stable `x-opencode-session` header;
the Python provider sends one automatically, plus a non-generic user agent. DeepSeek V4.1 Flash on Go also
requires the workspace Privacy setting to be **Global**. The free model IDs are restricted to requests made
from inside OpenCode: a direct API call returns `403 FreeTierError`, and pay-as-you-go needs account credit.
Do not treat the free model as a general API fallback or put learner data through it.

Production Python starts with conversation disabled until a cloud-evaluated conversation artifact passes its
release gate. This does not disable the existing Java ShopAIKey conversation. Do not approve the local R1
artifact just to make cloud readiness pass. Readiness in jobs-only cloud mode needs the service token,
configured cloud model in `/v1/models`, the ledger and RabbitMQ; it never contacts Ollama.

Start with one API and one worker, after checking Docker Engine health:

```powershell
docker compose --env-file .env --env-file .env.ai-production.example --profile ai --profile ai-worker config --quiet
docker compose --env-file .env --env-file .env.ai-production.example --profile ai --profile ai-worker up -d --build --wait ai-generation ai-worker
```

The overlay enables jobs but retains all three Java capability modes as `legacy`. Import the same routing
variables into the host Java service environment; Compose env files do not configure a host Java process.
Retain its existing RabbitMQ configuration and enable `DICTALAB_AI_QUEUE_ENABLED` for TTS/Coach and
`DICTALAB_LISTENING_QUEUE_ENABLED` for review. Use `DICTALAB_AI_CAPABILITY_CANARY_USERS` plus one capability's
mode `canary` after a shadow pilot. Change only one capability at a time: TTS, then Coach, then review.
An explicit `shadow` mode requires the same allowlist, admits an additional call through Java's global budget and
records valid/failed/skipped metrics. One daemon thread with no waiting queue discards the output and skips
busy samples, without extending the domain worker's lease. Keep the allowlist to a small test cohort.
Shadow consumes the global cost/resource budget and does not debit the learner's daily AI allowance.
Never enable it for a large population without accounting for the extra provider cost. Existing provider
classes remain available throughout transition. `python` routes the selected capability for all users.

Contracts: v1 jobs carry jobId/requestId/userScope/idempotencyKey/attempt/deadline/payloadHash and a typed
`tts|coach|review` payload. No arbitrary prompt or callback URL is accepted. Payloads are allowlisted and
redacted before transport/storage. Result metadata includes generationId, provider/model, prompt version/hash
and schema version. Coach carries no score/progress fields; review returns labels for exact backend fact IDs,
and Java computes recall scores with its existing rubric. WAV must be complete mono 24kHz 16-bit PCM, audio/wav,
44 bytes to 3 MB; Java validates it again before existing audio retention/storage applies.

The SQLite ledger is AI runtime state, not the domain database. Mount `dictalab_ai_jobs` into API and worker
on the SAME host. Do not use NFS or run jobs on separate hosts with this ledger. Its durable outbox republishes
unconfirmed pending work; publisher confirms and persistent RabbitMQ delivery protect broker handoff. Consumers
use manual ACK, prefetch 1 and preserve broker heartbeats during inference. Duplicate messages cannot claim
an active/completed job twice. Safe connection-establishment failures retry up to three attempts. Read timeout,
5xx, invalid output and expired/ambiguous calls do not automatically repeat a potentially paid call. Permanent
failures go to `dictalab.ai.platform.v1.dead`, bounded to 1,000 redacted messages/24 hours. Pending messages have
a 120-second broker TTL; the ledger records expiry independently. Job deadlines are at most 90 seconds.

Worker death after claim becomes `UNKNOWN` after its lease expires; late results are fenced. This deliberately
requires an explicit Java retry after inspecting provider outcome. Never replay a dead letter directly to
force another paid call. Java's authorized retry creates a new attempt ID; duplicate deliveries keep that ID.
There is no automatic switch to a second provider on an ambiguous outcome. Java checks parent ownership and
leases again on persistence, so deletion or lease replacement cannot write an orphaned/stale report or audio.
Changing model/prompt/config with jobs pending fails those jobs closed (`CONFIGURATION_CHANGED`); drain before
deploying changes and use explicit Java retries as needed.

Results (including WAV) expire after `DICTALAB_AI_JOB_RETENTION_SECONDS`, default 15 minutes. The ledger clears
the job payload after completion/failure and purges result content at expiry. Metadata-only idempotency
tombstones remain seven days; permanent duplicate prevention belongs to Java's domain outbox/report/audio
constraints. Bound backups of this private volume, restrict access to the app account, and retain no raw AI
payloads in logs. For rollback, switch one Java capability to `legacy`, stop new submissions and let current
work finish, then stop Python. Keep its volume/broker queues until all job outcomes are reconciled. Do not
delete the volume or reset RUNNING to PENDING. A model-host rollback never requires domain data deletion.

## T12: measured host separation, currently deferred

Production currently uses ShopAIKey and has no local model host. Keep this topology until measured evidence
justifies a change. Collect at least seven representative days, including peak traffic, with sample counts:
Java `dictalab_ai_platform_duration_seconds` p50/p95/p99 by capability/outcome, provider timeouts/429/schema
errors, `dictalab_ai_jobs{status=...}`, `dictalab_ai_job_queue_wait_seconds`, `dictalab_ai_worker_up`, RabbitMQ
ready/unacked/DLQ counts, and CPU/RAM from `docker stats --no-stream`. Job metrics are token-protected on
`/metrics`; Java shadow counters are `dictalab_ai_platform_shadow_total`. Unknown outcomes require operator
attention, not automatic replay. The queue-wait gauge measures the oldest currently pending job, not a percentile.

Initial pilot thresholds (operational guardrails, not measured production SLOs): pause expansion if failure or
timeout rate exceeds 1% over 15 minutes (at least 100 samples), 429 exceeds 1%, p95 end-to-end job time exceeds
30 seconds, p99 exceeds 60 seconds, oldest pending job exceeds 20 seconds for five minutes, or any new UNKNOWN
job appears. Alert when worker_up stays 0 for 30 seconds, pending exceeds 100, DLQ grows, disk exceeds 80%,
or process RAM exceeds 80% of its budget. Compare content/evidence error metrics separately; do not trade
quality for latency. Use the existing monitoring collector to scrape these metrics with the service token;
the alert rules and Grafana panels are in `ops/observability/alerts.yml` and its existing dashboard JSON.
Add `ops/observability/ai-platform-scrape.example.yml` under the collector's scrape_configs only after placing
the private service token in its credentials_file. Metrics accepts either X-Service-Token or Bearer auth.
Run the existing `alerts.test.yml` with promtool before reloading. The checked-in changes do not reload a live
collector or deploy monitoring agents to production.

If local inference becomes available in production, separate Ollama first. Keep API/worker/ledger on the app
host. Expose the model host only through a private TLS proxy/firewall; configure `DICTALAB_AI_OLLAMA_URL=https://...`,
`DICTALAB_AI_OLLAMA_SERVICE_TOKEN` and a pinned `DICTALAB_AI_MODEL_DIGEST`. The adapter sends X-Service-Token
to both `/api/tags` and `/api/chat`; configure the proxy to require it. Trust the private CA using SSL_CERT_FILE,
never disable verification. Bind native Ollama to loopback on that host. Verify the same digest before/after
moving traffic, warm the model and measure model load, CPU/RAM and network latency. A private address alone
does not satisfy the TLS/token requirement. Retain the old host until in-flight jobs drain.

Run `python tools/ai-eval/jobs_smoke.py` before a topology change. It tests real RabbitMQ with fake providers,
both routing modes, 1/5/10/20 concurrent submissions, host loss, duplicate suppression and worker restart;
`target/ai-platform-jobs-load.json` explicitly labels synthetic timings. They do not measure production model
capacity or real TLS certificates. Repeat the sweep against the candidate private host with synthetic data,
record CPU/RAM/model-load metrics, test certificate/token rejection and inject host loss before live canary.
Only add Python workers when inference capacity is available and Python is the measured bottleneck. Do not
replicate API conversation workers while their idempotency cache is process-local. Moving job workers across
hosts first requires a shared transactional AI-only ledger with equivalent fencing and recovery tests.
Do not multiply Ollama containers without measuring model memory. Kubernetes is not justified by current evidence.

T13 (DSPy/LangGraph/LangChain/fine-tuning) remains future work as specified by the plan. Current workflows
need neither framework orchestration nor an online optimizer. No production training or automatic prompt promotion runs.
