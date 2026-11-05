# DictaLab AI generation service

This service is an optional boundary for conversation generation. Spring Boot remains the owner of
authentication, sessions, history, quota and persistence. The default provider is unchanged; set
`DICTALAB_CONVERSATION_GENERATION_PROVIDER=python` only after this service and its evaluated provider are ready.
Production text AI uses Google Gemini; local text AI uses Ollama. Cartesia remains the audio provider.

## Langfuse: theo dõi prompt và model

SDK Python được khóa ở `langfuse==5.1.0`. Tracing mặc định **tắt**; bật bằng cấu hình
runtime riêng cho cả API và worker. Compose chuyển tiếp cùng cấu hình cho hai dịch vụ.
Không đưa khóa thật vào repository, frontend, notebook hoặc tham số dòng lệnh.

```dotenv
LANGFUSE_TRACING_ENABLED=true
LANGFUSE_BASE_URL=https://jp.cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=<project-public-key>
LANGFUSE_SECRET_KEY=<project-secret-key>
LANGFUSE_TRACING_ENVIRONMENT=local
LANGFUSE_RELEASE=<git-commit-deployed>
DICTALAB_AI_TRACE_CONTENT=true
```

`DICTALAB_AI_TRACE_CONTENT=true` cho phép gửi prompt, schema và kết quả đã che dữ liệu
nhạy cảm sang Langfuse. Bộ lọc hiện có che email, token và credential; không đảm bảo
nhận diện mọi tên riêng/địa chỉ trong văn bản tự do. Chỉ dùng corpus giả lập hoặc dữ liệu
được phép xuất; để `false` khi chỉ muốn metadata. Payload quá 64.000 ký tự được đánh dấu
`truncated`, kèm preview đã che và hash. Không xuất audio TTS, header, URL provider,
Settings hay nội dung exception. `userScope` đã băm được dùng làm user ID; không tự tạo
session ID khi contract chưa cung cấp phiên học.

### Dữ liệu được ghi

| Thành phần | Trace và bằng chứng |
| --- | --- |
| Scenario EXTRACT / GENERATE / GRADE | Một root cho mỗi job; mỗi lượt gọi model là một generation con |
| GENERATE | GENERATE → QUESTIONS → REVIEW_QUESTIONS → TRANSLATE → REVIEW; giữ đầu ra bị từ chối |
| Gate | `gate.validate_*`: 0/1 từ validator/reviewer hiện có; mỗi gate giữ giá trị cuối trong phase |
| Worker | `operation_success`: kết quả thực thi, **không phải điểm chất lượng nội dung** |
| Base/full07 demo | Tách variant, revision và adapter hash; token thật và `gate.demo_structure.<variant>` |
| Conversation Python cũ, coach/review, Core text qua Python | Dùng chung instrumentation của ModelGateway |
| Chi phí | Token do provider trả về, gồm cache nếu có; Langfuse tính giá khi nhận diện được model |

Đầu ra JSON lỗi vẫn được ghi trước parse/validation. Không ước lượng token khi provider
không trả usage; không coi giá thiếu là chi phí bằng 0. Không retry model khi Langfuse lỗi.
Tracing không thay prompt, prompt hash đã duyệt, kết quả API hoặc cơ chế fail closed.
API/worker flush khi dừng; script capture flush trước thoát. Log `ai_trace_completed`
chứa trace ID và request ID để đối chiếu với log ứng dụng.

**Phạm vi:** lời gọi Java đi thẳng tới provider và job train trong Colab chưa đi qua
Python AI boundary nên không tự có trace. So sánh inference của model đã train được
ghi khi chạy qua boundary/demo; không tự chạy lại train hay bật tuyến Python để lấy trace.

### So sánh sau mỗi lần cải thiện

1. Giữ cùng corpus `scenario-quality-v1.json`, `datasetHash`, `caseId`, profile và ngân
   sách. Mỗi lần capture dùng thư mục mới để giữ bằng chứng cũ.
2. Prompt được nhận diện bằng `promptHash` và `stagePromptHash`, không chỉ tên V4.
   Model local dùng digest thật. Với model đã train, cấu hình đúng các biến dưới đây
   cho **model đang phục vụ**; để trống thông tin chưa biết:

   ```dotenv
   DICTALAB_AI_TRACE_MODEL_REVISION=<base-revision>
   DICTALAB_AI_TRACE_ADAPTER_HASH=<adapter-sha256>
   DICTALAB_AI_TRACE_TRAINING_RUN=<training-run-id>
   DICTALAB_AI_TRACE_VARIANT=<base-or-adapter>
   ```

3. Chạy capture theo lệnh hiện có trong `tools/ai-eval/README.md`, bằng environment
   runtime đã nạp an toàn. Trace tự có `evaluationRun`, `datasetHash`, `caseId`,
   `profileId`, `programHash` và `gitCommit`. Việc capture gọi model và vẫn chịu budget.
   `report`/`replay` tiếp tục offline, không gọi provider hoặc gửi trace.
4. Root `version` là `configurationHash` của prompt/model/weights/program để các
   thay đổi prompt hoặc adapter cùng tên vẫn tách được. Generation `version` là hash
   prompt/schema riêng của bước. Metadata giữ fingerprint đầy đủ để kiểm tra lại.
5. So sánh cả tỷ lệ qua gate, số mẫu, lỗi provider, đầu ra bị loại, độ trễ và token.
   Gate/reviewer có thể bỏ sót lỗi; dùng annotation của người đọc trên cùng dữ liệu
   trước khi kết luận nội dung tiến bộ. Không dùng riêng điểm gate để duyệt release.

Dashboard đã tạo trong project:
[DictaLab — Prompt & Model Progress](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/dashboards/cmvx7kqtu000bad0kt3vvx20b).
Năm widget gồm tỷ lệ qua từng gate theo cấu hình, thực thi thành công theo thời gian,
p50/p95 từng bước, token/chi phí theo model và số mẫu theo cấu hình. Khi so sánh, lọc
cùng dataset/profile/environment; không gộp các corpus khác nhau thành một kết luận.
Trace fixture kiểm tra tích hợp được loại khỏi dashboard; dashboard trống khi chưa có
capture thật là trạng thái đúng, không phải bằng chứng AI đã đạt 100%.

Kiểm tra không cần credential: `python -m pytest services/ai-generation/tests/test_observability.py`
từ repository root. Trace đi qua SDK thật với exporter trong bộ nhớ, không gọi Langfuse
hoặc model trong unit test. Rollback tracing bằng `LANGFUSE_TRACING_ENABLED=false`.

Run locally:

```text
python -m venv .venv
pip install -e ".[test]"
uvicorn app.main:app --port 8091
```

Configuration uses `DICTALAB_AI_` variables. Set `DICTALAB_AI_SERVICE_TOKEN` and the matching Java
`DICTALAB_CONVERSATION_PYTHON_SERVICE_TOKEN` when the endpoint is reachable beyond loopback.

Conversation repair is temporarily disabled by default (`DICTALAB_AI_REPAIR_ENABLED=false`). Each workflow
generates English once, translates once, then validates. Invalid output is rejected without regeneration.
Java's outer repair is also off (`DICTALAB_CONVERSATION_GENERATION_REPAIR_ENABLED=false`). Keep both off
while improving first-pass prompt quality. Repair remains available for a separate production decision.

From the repository root, build with `docker compose --profile ai build ai-generation`.
The image uses one Uvicorn process and 1-5 concurrent workflows (`DICTALAB_AI_MAX_CONCURRENCY`, default 1).
Do not add Uvicorn workers while idempotency is process-local.

`DICTALAB_AI_ARTIFACT_VERSION` explicitly selects a bundle from `artifacts/prompts/`.
Production additionally requires `DICTALAB_AI_ARTIFACT_SHA256`, `approvalStatus=approved`, and a passing
evaluation gate. The checked-in bundle is a development candidate and intentionally cannot pass production
readiness yet. Each result reports the selected prompt version and actual last-call model metadata; logs
retain the content/translation provider sequence. Unknown client versions return 400.

With conversation enabled, readiness verifies the artifact and the selected provider. Local model digests
are checked against Ollama; cloud readiness checks Gemini's authenticated `/models` endpoint without a
billable completion. Set `DICTALAB_AI_CONVERSATION_ENABLED=false` to run background capabilities while the
cloud conversation artifact is awaiting evaluation. Existing Java conversation remains on Gemini in production.
`/metrics` exposes status counters, latency
buckets, active workflows and a zero queue gauge (admission is fail-fast). Send `X-Service-Token` to scrape
metrics when configured. See `docs/ai-platform-runbook.md` for shadow, canary and rollback, and
`tools/ai-eval/README.md` for reproducible regression reports.

Background jobs use the existing RabbitMQ and a private SQLite ledger on a shared API/worker volume.
Enable `DICTALAB_AI_JOBS_ENABLED=true`, then start `ai-generation` and `ai-worker` with profiles `ai,ai-worker`.
Internal submit endpoints are `/v1/tts/jobs`, `/v1/coach/reports`, `/v1/review/tasks` (202); poll
`/v1/jobs/{jobId}` with the matching service token and opaque user scope. OpenAPI describes the discriminated
payloads and v1 envelope. Java's existing durable outbox workers submit/poll; web requests remain asynchronous.
API/worker use SQLite for jobs and optionally a read-only PostgreSQL registry connection for provider configuration.
They have no Core practice-data connection. Python returns validated evidence classifications or
bounded PCM WAV bytes; Java revalidates and persists under its existing lease/ownership checks.

Each Java capability has an independent `legacy|shadow|canary|python` mode. Canary requires a user allowlist.
Rollback changes only that flag. Shadow admits extra work under the global budget, preserves learner allowances and discards
the Python output. Cloud shadow requires deliberately selecting this capability mode; conversation's older
automatic shadow mechanism rejects a cloud primary. These background capabilities do not automatically fall back to cloud.

The separately enabled conversation demo lane adds `/v1/conversation/jobs`, sequential base/full07
comparison and explicitly opted-in, budget-reserved cloud fallback. See `services/model-serving/README.md`.
It remains evaluation-only and cannot promote a model or bypass existing conversation review.

Run `python tools/ai-eval/jobs_smoke.py` for Java/API/RabbitMQ/worker tests in both local and production modes,
using fake Ollama/Gemini/Cartesia. It creates/removes a disposable Docker broker unless
`AI_TEST_RABBITMQ_URL` identifies a disposable test broker. No paid model calls occur.

## Shared Gemini project pool

Enable `GEMINI_QUOTA_ENABLED=true` and supply `GEMINI_PROVIDERS_JSON` to use 1-N project/model
sources. Java, Python API/worker and offline batch must point to the same Redis database and use the
same limits. `memory` is test/local single-process only; production Python refuses it. Pool admission
fails closed on Redis failure. No fallback to the legacy key occurs while the pool is enabled.

The common JSON overrides Java's older A/B/C-only variables. All fields, including quota limits, are
shown in `docs/gemini-providers.example.json`; entries are deliberately disabled and contain no real keys.
The requested model is preserved: another source may serve it, but no model downgrade/escalation runs.
Each source has rolling RPM/TPM, Pacific-day RPD, 429 cooldown and a five-failure/30-second circuit.
An HTTP request is never replayed inside the gateway. Reporting failure after HTTP does not discard a
successful result or label an already-sent call retry-safe.

`/metrics` adds shared `dictalab_gemini_*` quota/status/outcome metrics. They include Java calls when
the same project/model/Redis DB is used. Scrape one API instance to avoid double-counting shared counters.
Read `docs/ai-platform-runbook.md` (Gemini pool section) before enabling this on real accounts.

## Opt-in generic Core text transport

`POST /v1/text/completions` executes the synchronous JSON text calls already used
by Core for assessment, conversation review and clarification. It supplements the
durable capability jobs. It does not return provider keys, accounts or endpoints.

Configure both sides explicitly in protected runtime configuration:

| Core | AI API |
| --- | --- |
| `DICTALAB_AI_TEXT_TRANSPORT=python` (default `legacy`) | `DICTALAB_AI_TEXT_GATEWAY_ENABLED=true` (default `false`) |
| `DICTALAB_AI_PLATFORM_BASE_URL` | API binds loopback for native local use |
| `DICTALAB_AI_TEXT_PROVIDER` | Matching `DICTALAB_AI_PRIMARY_PROVIDER` |
| `DICTALAB_AI_TEXT_MODEL` | Matching enabled model in AI registry, or `DICTALAB_AI_MODEL` for Ollama |
| `DICTALAB_AI_SERVICE_TOKEN` | Same random ASCII token, 32-256 characters |

For local Ollama set both model settings to the installed model, for example
`qwen3.5:9b`. In Python transport mode Core's default text model is explicit; it
does not read the provider registry to discover a model. Existing conversation
model overrides must also name an enabled exact model. Model/provider mismatch
fails the call; no direct-provider or cloud fallback is implicit.

Core retains caller budgets, domain/semantic validation and its provider guard.
AI performs provider quota admission once through the existing shared Redis
protocol; the Core transport does not reserve that quota again. Generic text uses
JSON-object output and a 2,200-token cap; structured pipelines keep JSON schema.
Requests carry a UUID and an absolute deadline of at most 60 seconds. Input is
bounded to 256 KB; instruction/input have separate character limits and results
are bounded to a 12,000-character JSON object with verified provider/model/ID.

AI stores only request ID/deadline in its private SQLite ledger before provider
I/O. Replay of the same ID is rejected even after API restart, failure or queue
timeout; markers expire one hour after the original deadline. No prompt/result
is persisted by this endpoint. A new Core invocation gets a new ID, so callers
must keep their existing domain-level policy for explicit retries and unknown
outcomes. Neither HTTP client automatically retries this operation.

Rollback: restore the protected legacy Core configuration, set transport to `legacy`, restart Core, then disable the AI
gateway when in-flight requests have drained. Ensure the legacy provider registry
and credentials are still available before rollback. Private registry ownership
and a Core runtime without provider credentials are now available for local use;
the legacy shared registry remains supported. See `docs/ai-provider-database.md`.

Repeatable unpaid verification from repository root:

```powershell
python -X utf8 -m pytest -q services/ai-generation/tests/test_text_gateway.py services/ai-generation/tests/test_model_gateway.py
mvn --batch-mode --no-transfer-progress '-Dtest=GeminiClientTest,AiPlatformClientTest' test
python -X utf8 tools/ai-eval/smoke.py
```

## Isolated native runtime

From the repository root, use one protected explicit file per service:

```powershell
python scripts/run-service.py ai --env-file data/microservices/ai.env
python scripts/run-service.py worker --env-file data/microservices/ai.env
python scripts/run-service.py core --env-file data/microservices/core.env
python scripts/run-service.py progress --env-file data/microservices/progress.env
```

To supervise all three business services and the AI worker in one terminal:

```powershell
python scripts/run-service.py stack --config-dir data/microservices
```

Add `--with-model` to start the separate loopback CPU Model Serving process.
It loads weights only on first inference; its live check does not prove model
readiness or sufficient RAM. Add `--check-only` to verify startup and stop the
owned processes. The stack requires existing PostgreSQL, Redis and RabbitMQ;
it does not start, reset or stop shared infrastructure.

Before spawning, it checks every config/build, matching internal tokens, local
routes and available ports. Core/API/Progress must pass their health endpoints;
the worker must remain alive. Logs go to restricted files under the config
directory's `logs/`. Ctrl+C or a child failure stops the owned process trees.
Configuration values and service output are never copied to the console.

The launcher excludes inherited application credentials, proxy and JVM injection
settings; it does not auto-load root `.env`. AI requires `AI_PROVIDER_CONFIG_SOURCE=database`
and `DICTALAB_AI_REGISTRY_OWNERSHIP=private`, an independent AI database and a
read-only runtime role without memberships. Operator credentials stay in a separate
file. API/worker share one absolute SQLite path. Keep Redis/RabbitMQ and internal
tokens consistent across the explicit files. Build Core/Progress JARs first.

The local configuration uses Python text/TTS/coach/review routing with Core caller
budgets enabled and Progress reads in remote mode after local history parity. Health and cutover evidence are
recorded in `docs/microservices-implementation.md`; production cutover is pending.
The [R13 integration](../../docs/model-demo-guided-r13.md) is evaluation-only and
does not change the production conversation artifact approval gate.

### Scenario playback and character voices

Scenario sessions can freeze a server-owned two-voice recipe without changing the
generation or grading prompts. This is opt-in; existing sessions and all legacy
`kind=tts` jobs keep `TTS_WAV_V1` and their existing audio cache.

Set Core `DICTALAB_SCENARIO_ENGINE_TTS_RECIPE_JSON` to one recipe object, and set
`DICTALAB_AI_SCENARIO_TTS_RECIPES` to a JSON array containing the same object on
both AI API and worker. Recipe fields are `id`, `model`, `apiVersion`, `language`
(`en`), `outputFormat` (`wav-pcm16-mono-24000`), and `voices` (exactly two distinct,
provider-verified voice UUIDs). Voice IDs are configuration, not API credentials.
Keep the Cartesia credential only in protected AI runtime configuration.

Core maps characters in the confirmed specification's order to those two voices,
then stores the recipe and speaker mapping in session provenance. V2 TTS uses
`kind=scenario_tts` on the existing authenticated `/v1/tts/jobs` endpoint; callers
cannot supply a voice through the public playback API. Cache keys include the
immutable text, speaker, voice and recipe. Core verifies returned V2 metadata
before accepting audio. Content that would be redacted is rejected before TTS
instead of silently synthesizing a different transcript.

Deploy API/worker support before configuring Core to admit V2 sessions. Validate
both voices with short samples first. When changing recipes, keep old recipes in
the AI allowlist while their sessions need uncached audio. To stop new V2
admissions, clear the Core recipe setting; keep the V2-capable services and old
allowlist entries for existing sessions/jobs. Do not downgrade the worker while
V2 sessions or jobs still need service. No migration, cache deletion or rewrite
of old sessions is required.

A failed scenario grade can be requested again with
`POST /api/v2/scenarios/sessions/{session}/activities/{activity}/attempts/{attempt}/retry-grade`,
using the current `expectedVersion` and `Idempotency-Key`. Only the latest FAILED
attempt is eligible, with at most three GRADE operations including its first call.
The retry reuses the original answers, support snapshot and historical payload;
it does not spend another learner submission. UNKNOWN is never replayed. Retain
its operation ID for investigation. These are technical retries, not extra
pedagogical attempts or a change to completion/scoring policy.

After completion, the learner sees the activity review before continuing and can
replay completed activities. `plays` still records playback requests, not proof
of listening. The UI separates initial/latest grades and assistance; neither
summary is a certificate score. Provider WAV validation and browser decoding do
not replace human review of pronunciation or voice quality.
