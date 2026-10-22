# AI Platform runbook

Cloud accounts now support the shared encrypted PostgreSQL registry. See
[provider database operations](ai-provider-database.md) for local import, DeepSeek,
explicit platform selection, protected master keys and fail-closed behavior. Legacy
environment examples below remain for compatibility, not the migrated local account source.

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
score or user quota. Python rejects cloud providers for `X-AI-Mode: shadow`; shadow requires local Ollama. The hourly budget resets on Java
restart, so do not scale pilot processes without coordinating that limit. Python's global concurrency limit
still applies; shadow may briefly consume local model capacity. Set sample rate 0 and restart to stop it.

Canary: set `DICTALAB_CONVERSATION_CANARY_ENABLED=true` plus comma-separated
`DICTALAB_CONVERSATION_CANARY_USERS` (internal numeric IDs) or `DICTALAB_CONVERSATION_CANARY_SESSIONS`.
An empty allowlist is rejected. Every turn in a listed session/user follows Python; other learners keep
the configured existing provider. All normal validation, quota and persistence stay in Java. There is no
automatic percentage expansion or alternate-provider retry after an ambiguous failure.

Rollback: set canary false, shadow sample rate 0, and generation provider to the prior `ollama` or `gemini`,
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
`GEMINI_API_KEY` to Python's `DICTALAB_AI_GEMINI_API_KEY` and `CARTESIA_API_KEY` to its TTS client.
For native Python set those Python variables explicitly. The local launcher selects Spring's `local` profile
and forces Ollama for text AI, including legacy grading and review; it needs no cloud provider key.
Audio still requires Cartesia. Default Java production routing uses Gemini, including rollback clients.
Java `DICTALAB_AI_TEXT_PROVIDER` must match Python's selected primary; mismatches fail before paid job calls.

Gemini is the only cloud text provider (`DICTALAB_AI_PRIMARY_PROVIDER=gemini`). Compose forwards
`GEMINI_API_KEY`, `GEMINI_BASE_URL` and `GEMINI_MODEL` to `DICTALAB_AI_GEMINI_*`. The Python provider uses
Google's OpenAI-compatible endpoint (`https://generativelanguage.googleapis.com/v1beta/openai`) with the
`gemini-3.5-flash-lite` model. Google lists this model as `models/gemini-3.5-flash-lite`; readiness accepts
both forms. The key stays in protected runtime configuration. Do not use a free-tier key for learner data
without an explicit data-handling decision, and do not treat the benchmark result as production approval.

Production Python starts with conversation disabled until a cloud-evaluated conversation artifact passes its
release gate. This does not disable the existing Java Gemini conversation. Do not approve the local R1
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

Production currently uses Gemini and has no local model host. Keep this topology until measured evidence
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

## Shared Gemini project quotas and offline batch

### Configuration and capacity

The opt-in pool serves Java `GeminiClient`, Python API/worker, `tools/ai-eval/gemini_batch.py` and the
teacher collector. Keep `GEMINI_QUOTA_ENABLED=false` for the legacy single-key path; enabling the pool
never falls back to that key. Supply one common `GEMINI_PROVIDERS_JSON` (camelCase array) to every process.
Start from `docs/gemini-providers.example.json`, which contains disabled placeholders, not usable keys.
Include all fields explicitly for identical Java/Python behavior. A/B/C variables remain Java-only legacy
configuration; do not mix them with a different Python pool. JSON takes precedence in Java.

Each enabled entry has a unique ID and a unique project/model pair. Use the same canonical project
identifier everywhere (a project number is accepted); a renamed source/key must not create a new quota.
Record the real model's RPM/TPM/RPD in AI Studio. `operatingRpm=13` for a 15-RPM project starts three
sources at 39 RPM; four at 52 require raising `GEMINI_GLOBAL_RPM_LIMIT` explicitly. `operatingRpm=0`
means the configured RPM, not disabled. `tpmLimit=0`, `rpdLimit=0` and global limit 0 mean no local cap
for that dimension; do not leave unknown free-tier TPM/RPD at zero when activating real accounts.

With three independent 500-RPD projects, theoretical daily capacity is 1,500 requests, before other
traffic, failures and safety margins. It is not 45 requests/minute for an entire day. AI Studio's
28-day **Peak requests per day** is observed historical usage, not a quota setting or today's remainder.
Limits/capacity can change: verify [official rate-limit guidance](https://ai.google.dev/gemini-api/docs/rate-limits)
and the selected project/model, not the consumer Gemini Pro subscription. This runner uses normal
chat completions, not Google's separate Batch API with its own quotas and billing.

### Runtime secrets and Redis

Keep actual keys/JSON in protected runtime configuration, never tracked examples, CLI arguments, logs,
reports or metric labels. The existing ignored `.env` is suitable on this workstation with owner-only
permissions. Compose reads it; host Python does not automatically import it. In PowerShell, import its
values into the current process without displaying them:

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
  if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
    $runtimeName = $matches[1]
    $runtimeValue = $matches[2].Trim().Trim([char]34).Trim([char]39)
    [Environment]::SetEnvironmentVariable($runtimeName, $runtimeValue, 'Process')
  }
}
$env:GEMINI_QUOTA_REDIS_URL = 'redis://127.0.0.1:16379/0'
```

Store the JSON on one line in `.env` (single quotes around the complete JSON are supported by Compose).
The import above is for simple single-line KEY=VALUE files only, not a general shell/dotenv parser.
Do not print `docker compose config`, environment dumps or settings representations containing secrets;
use `docker compose --profile ai --profile ai-worker config --quiet` to validate.

Java uses the established `REDIS_URL` or `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`; Python uses
`GEMINI_QUOTA_REDIS_URL`. They must reach **the same Redis database**. Compose defaults Python to
`redis://redis:6379/0`; a host-side tool normally uses `redis://127.0.0.1:16379/0`. Do not place the host
loopback URL in container settings. Protect remote Redis with private networking, ACLs and TLS (`rediss`)
as appropriate. Redis stays loopback-bound in Compose, uses persistent AOF and `noeviction`.

Quota v2 keys start with `{dictalab-gemini-quota}:v2:`. Java packages the exact Lua file shipped by Python;
do not deploy mixed protocol versions. A single atomic reservation checks rolling 60-second RPM/TPM,
Pacific-day RPD and global RPM/RPD. Token reservation is deliberately conservative (UTF-8 input/schema
bytes plus output budget/overhead), not an invoice or exact tokenizer result; no token refund is applied.
Traffic outside this coordinator is invisible. On first activation, reconcile prior usage or wait until
the next Pacific reset. Use remaining-day budgets conservatively; never treat a fresh Redis DB as proof
of unused provider quota. Back up/preserve counters and do not flush them on deployment/restart.

Redis failure blocks admission; it never silently switches to memory. Memory mode is for single-process
local tests only, and production Python rejects it. A 429 sets shared project/model cooldown (Retry-After
or 60 seconds); five transport/5xx failures open a 30-second circuit. Other projects remain usable.
An older success cannot clear a concurrent 429 cooldown. Requests are routed round-robin among matching,
available sources. No adaptive weighting, automatic tier escalation or cross-model substitution runs.

### Running and recovery

Install the AI runtime and offline preparation dependencies, then use the bounded JSONL runner:

```powershell
python -m pip install -r services/ai-generation/requirements.lock -r training/requirements-prepare.lock
python tools/ai-eval/gemini_batch.py --input data/batch/input.jsonl --ledger data/batch/checkpoints.sqlite3 --concurrency 3 --max-calls 100 --max-seconds 3600
```

See `tools/ai-eval/README.md` for the input schema, ledger and result format. Each job is one provider
call, not one generated data item. A 24-item response still consumes one request; a conversation workflow
generating then translating consumes two. Concurrent runs must reuse the same SQLite ledger on the same
host to deduplicate IDs. SQLite on a network share is not supported; distributed batches need a shared
transactional job store before scaling workers across hosts. Existing RabbitMQ application jobs retain
their separate outbox, deadlines and bounded safe-retry policy.

The offline runner retries admission only, within its deadline. DONE/FAILED/UNKNOWN are never replayed.
After an ambiguous timeout/cancellation, inspect the provider and ledger; do not reset UNKNOWN to PENDING.
Only an explicit, reconciled new attempt gets a new ID. Validate schema locally and review domain/semantic
quality before importing results. The ledger/input may hold private content: restrict access, back up
while preserving WAL consistency and define retention; this offline ledger has no automatic purge.

The teacher collector preserves its frozen approved model, 19-call cap, no retry/repair and human-review
gates. A Flash-Lite-only pool cannot serve a frozen Flash Preview teacher call. Changing source code/model
requires a fresh reviewed collection plan; do not overwrite old evidence.

### Monitoring, rollout and rollback

Scrape one Python API `/metrics` with the existing service token. Its `dictalab_gemini_*` gauges/counters
read shared Redis, including Java reservations/outcomes. `quota_available=0` means quota telemetry/admission
is unavailable, not that Google has banned a key. RPD remaining is relative to configured caps, not live
Google usage. Prometheus rules cover quota outage, less than 20% RPD and sustained 429 over 1% (at least
20 admissions). Grafana adds RPM/TPM, RPD, cooldown and outcome panels. Existing application-job UNKNOWN
alerts remain; standalone batch status is in its ledger/CLI summary, not the API job metrics.

Deploy code with the flag off, then verify Redis and non-generating model readiness. Enable one source,
then two at 26 RPM, then three at 39 RPM; observe real 429, latency and content quality for 24-72 hours.
Only try 42/45 after evidence, never above the verified project quotas; use bounded synthetic input and
an explicit run call cap. The included 10/20/39/45/60 load tests simulate admission, not live Google capacity.
To add source N, append its complete JSON/secret entry, adjust global budgets, drain existing jobs and
restart all consumers consistently. Keep approved model IDs and artifact gates unchanged.

Rollback by lowering global/source limits or disabling only the new source; drain active work before
changing job fingerprints. Stop admission if Redis state is uncertain. Preserve all counters and ledgers.
Setting the pool flag off restores the legacy path, which bypasses the pool; stop batch writers first
and do not leave pooled/legacy calls concurrently unaccounted. These instructions do not themselves
deploy production or authorize live data generation.
