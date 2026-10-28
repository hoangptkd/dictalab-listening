# DictaLab microservices implementation

## Accepted scope

Three business services in this monorepo: Learning Core (Spring Boot), AI
(Python), and Progress (Spring Boot). A separate local Model Serving process
loads the existing Qwen3.5-4B base and full07 adapter. Content, Identity,
Billing, and Realtime remain in Core. No retraining or dataset changes.

Local demonstrates all components, asynchronous conversation generation, and a
base/adapter comparison screen with model provenance. VPS must eventually run
all three business services, using cloud inference. The read-only VPS inventory
on 2026-10-27 found 2 vCPU and 953,704,448 bytes of RAM (about 0.89 GiB).
Measure the complete deployment and propose any upgrade before spending.
Do not assume the fine-tuned model will run on that VPS.

Cloud fallback is opt-in, budgeted, bounded, and visible. Benchmark comparisons
never use fallback. Existing scoring, authentication, authorization, CSRF, quotas,
payment handling, learner history, and unrelated working-tree changes survive.

## Delivery and evidence ledger

An unchecked entry is incomplete, even if supporting code exists.

- [ ] Record baseline behavior and deployment/resource inventory.
- [x] Deliver the Progress service, private database, versioned event contract,
      Core transactional outbox, account deletion propagation, and replay.
- [ ] Backfill history and verify Core/Progress dashboard parity before cutover.
      Local backfill and remote cutover verified; VPS history/cutover remains.
- [x] Separate provider registry ownership from Core database access.
- [x] Make opt-in AI conversation demo jobs durable and restart-safe.
- [x] Serve the existing base and full07 adapter in a separate local process.
- [x] Integrate asynchronous conversation demo and the comparison UI.
- [x] Enforce budgeted demo fallback and report actual provider/model provenance.
- [ ] Run cross-service recovery, security, contract, and load checks.
- [ ] Package local startup, diagrams, decisions, benchmark, and demo evidence.
- [ ] Deploy all three business services to VPS and verify health and workflows.

## Decisions

1. Keep independent build artifacts rather than a shared runtime entity library.
   Contracts are JSON; Core's JPA entities do not cross the service boundary.
2. Progress owns a separate database and role. It never queries Core's database.
3. Core remains the source of truth for scores. Initial Progress projections
   cover solo dictation dashboard totals, trends, and error counts. Core retains
   recommendations and the existing listening assessment algorithm so migration
   cannot silently change eligibility, first-exposure, or scoring rules.
4. Use the existing RabbitMQ and transactional-outbox approach. Delivery is
   at least once; consumers must tolerate duplicates and reordering.
5. Default to legacy reads until parity is proven. Cutover is explicit; an
   unavailable Progress service must never turn a successful practice submission
   into a failed or repeated submission.
6. Account deletion uses a durable user tombstone so late deliveries and history
   replays cannot recreate a deleted learner's projection.

## Initial state inspected

- Core: Java 17 target, Spring Boot 4.1.1, PostgreSQL/Flyway, Redis/RabbitMQ.
- AI API/worker already exist under `services/ai-generation`; conversation
  idempotency currently includes a process-local cache and background jobs use
  a private SQLite ledger.
- `DashboardService` reads practice tables directly; shared-room sessions are
  excluded from dashboard totals and trends.
- `full07` training completion is documented, but generation quality is not
  production-approved. Training loss/token accuracy are not generation pass rates.
- Docker Engine was started through Windows Explorer and responded to
  `docker info` on 2026-10-27.
- Current branch at start: `codex/practice-drafts-audio-20261017`, HEAD `d3370e1`.
  Pre-existing modified and untracked files are not part of this delivery.

## Completion rule

Only mark the migration complete after each accepted requirement has direct
runtime/test/deployment evidence. A passing local test or push does not prove
VPS deployment, model quality, or full-system readiness.

## Existing VPS resource inventory (2026-10-27)

Read-only sample at `2026-10-27T15:48:14Z`, using the existing production
sampling script over the established SSH connection:

| Measurement | Observed value |
| --- | --- |
| CPU | 2 vCPU |
| Total RAM | 953,704,448 bytes, about 0.89 GiB |
| Available RAM | 234,610,688 bytes, about 224 MiB |
| Used swap | 540,471,296 bytes, about 515 MiB |
| Core resident memory | 363,708,416 bytes, about 347 MiB |
| Core swapped memory | 247,992,320 bytes, about 237 MiB |
| RabbitMQ cgroup memory | 21,823,488 bytes, about 21 MiB |
| Core service | active, restart counter 0 |

The learning evidence queue had one consumer and no waiting or unacknowledged
messages. The AI and listening queue flags were disabled. The sample did not
retrieve application metrics or PostgreSQL details; those parts of the baseline
remain incomplete. Low load and a zero restart counter do not establish capacity
for two additional business services. This observation does not authorize a
purchase or prove that the three-service VPS deployment will fit. No production
configuration, service state, or database was changed during sampling.

## Progress milestone evidence (2026-10-27)

- Standalone service: 8 passing tests with disposable PostgreSQL 18 and RabbitMQ;
  executable Java 17 JAR and non-root container image built successfully.
- Core client/outbox: 9 passing tests, including transaction rollback, historical
  export, metadata revisions, deletion scrubbing and explicit failed-event replay.
- Existing practice/account/backend regressions: 25 passing tests.
- Cross-process delivery: 9 passing tests against real PostgreSQL 18/RabbitMQ,
  including a separately packaged Progress JVM and independent databases.
  Runtime assertions cover dashboard parity, duplicate delivery/lost ACK,
  broker outage recovery, process restart, and deletion before late delivery.
  Repeatable command and cutover instructions are in `services/progress/README.md`.
- Compose configuration, browser JavaScript syntax and UTF-8 checks passed.
- Historical export/parity is proven on test fixtures. Export of the real learner
  history and production read cutover are still pending. Publishing and remote
  reads remain opt-in; this milestone does not change the running VPS.

## Model demo milestone evidence (2026-10-27)

- Separate loopback Model Serving process with pinned base/adapter/config hashes,
  runtime versions, tokenizer parity, bounded requests, token authentication and
  a free-memory guard. File/runtime verification passed against local files.
  Actual inference through this new server was subsequently verified on
  2026-10-28; see the separate runtime evidence below. No model quality claim follows.
- Core's additive V43 ledger persists ownership, intent idempotency, deadlines,
  short submit/poll leases and completed results. Existing conversation ownership,
  quota, semantic review, answer hiding and optimistic session leases remain.
- AI keeps jobs and two-call cloud reservations in its private SQLite ledger.
  Comparison uses sequential base/full07 with identical context, never cloud
  fallback. Unknown paid outcomes retain reservations rather than replay calls.
- Latest targeted Java run: 60 tests, 0 failures/errors, 1 pre-existing opt-in
  skip; includes 5 demo ledger/auth tests and 2 real conversation acceptance tests.
  A separate earlier budget run passed 7 tests.
- The 5 demo ledger/auth tests also passed against disposable PostgreSQL 18
  with Flyway V43, without skips; packaged Core JAR build passed.
- Python: 232 passed, 2 existing Redis-dependent skips in the local environment.
  New coverage includes 9 demo cases and 3 Model Serving API/contract cases.
- Real Java/AI API/RabbitMQ/worker smoke passed for both local and cloud routing
  using synthetic provider fixtures. Each comparison made exactly four local
  calls in base/base/full07/full07 order, reused the same inputs, and duplicate
  submission retained the result without repeating inference. Existing 1/5/10/20
  concurrency, provider-loss and worker-restart checks also passed. These are
  transport/recovery evidence, not real-model throughput measurements.
- Browser checks against the actual demo JavaScript with synthetic responses:
  queued/completed states, updated history labels, both comparison arms, escaped
  model output, disabled fallback for explicit cloud, hidden practice answers,
  quota notice and keyboard acceptance callback. No real learner data was used.
- Native local launcher and dotenv quoting checks added; JavaScript syntax,
  UTF-8 and diff checks passed. See `services/model-serving/README.md`.
- Progress workflow `40528616084` failed on GitHub artifact storage quota.
  AI workflow `40528615640` also exposed a budget-disabled test fixture; that
  fixture is corrected locally. CI must be rechecked after the next push.
- Remaining: broader real-model evaluation, resource baselines, independent AI
  registry ownership, real history backfill/cutover, full-system runtime evidence
  and the VPS rollout. All new demo flags default off; no VPS change is implied.

## Actual local inference (2026-10-28)

- Unrelated applications were closed with the owner's authorization. The
  harness observed 21,887,430,656 available bytes (20.38 GiB) before startup.
  Model Serving used a random loopback port and an ephemeral service token;
  no cloud requests, training or dataset changes were made.
- The pinned base and full07 adapter both returned HTTP 200 for the same
  one-word question, `What day follows Tuesday?`, with `Wednesday` (4 output
  tokens). Reported latency: base 62,953 ms including cold load/verification;
  full07 4,953 ms after load. This does not establish general throughput or
  an adapter speed advantage. Readiness returned 200 after loading.
- A real A1 cafe comparison used the frozen prompt/context with fallback off.
  Both content outputs were rejected with `INVALID_MODEL_SCHEMA`; translation
  was not dispatched for either arm. Both produced three slots/facts where A1
  permits two. Base also had missing slot/fact fields and an invalid skill;
  full07 used `text` instead of required `textEn`. Semantic review was not run.
- The whole harness took 633.8 seconds including startup and the two smoke
  calls. Minimum sampled system available RAM was 4,251,934,720 bytes (3.96 GiB).
  The automated RSS sampler measured the interpreter launcher rather than its
  model child, so its peak RSS field is invalid and is not a resource benchmark.
  The model process exited after the check and available RAM recovered.
- Result: actual serving/integration is demonstrated; this sample fails
  conversation acceptance. Keep the model in the evaluation lane. A broader
  evaluation and model quality improvements remain a separate phase.

## Generic text transport milestone (2026-10-28)

- Core now has an independent opt-in `legacy|python` text transport. The
  provider remains `ollama|gemini|deepseek|openai_compatible` for existing budget
  accounting. In Python mode, Core's generic text calls do not select accounts,
  query the Core provider registry or reserve provider quota a second time.
- AI executes the configured provider and an enabled exact model through the
  existing gateway/quota protocol. No endpoint returns provider credentials.
  JSON-object mode is limited to this generic path; structured workflows retain
  their JSON-schema behavior. Core retains semantic/domain validation.
- A metadata-only SQLite admission survives API restart and blocks duplicate
  request IDs until one hour after their original deadline. Failed/unknown and
  queue-expired requests retain admission. This is transport replay protection,
  not domain-level exactly-once delivery: a new Core invocation gets a new ID.
- Defaults remain off/legacy. Private AI registry storage, removal of credentials
  from Core runtime configuration and actual routing cutover are still pending;
  adding an API does not establish independent storage ownership.
- Configuration and repeatable verification: `services/ai-generation/README.md`.
- Local verification: 23 Java client/transport tests passed; 241 Python tests
  passed with 2 existing Redis-dependent skips. The real Java -> AI API -> fake
  Ollama smoke passed, including exactly one generic text call with a 2,200-token
  output cap, alongside the two existing structured conversation calls.

## Private ownership and actual local history (2026-10-28)

- AI registry now supports its own migration/checksum ledger and database,
  independent owner/read-only runtime roles, and a separate encryption master.
  Verified copy moved 2 pools/4 accounts in local configuration; encrypted backup
  parity, source preservation, disabled flags and role isolation passed. Core's
  V40 registry remains for operator-controlled rollback. Repeatable disposable
  PostgreSQL smoke covers copy/restore and rejects role membership escapes.
- New `scripts/run-service.py` requires explicit protected env per service and
  removes inherited application secrets/injection settings. Core uses a restricted
  role without provider registry permissions or provider/master keys. AI runtime
  receives no Core data permissions. Management credentials remain outside runtime.
  Existing root `.env` is preserved for legacy rollback/operator use.
- Core database was backed up with `pg_dump -Fc`, restored into a separate
  temporary database and table counts compared before migrating V41 to V43.
  Flyway checksum validation remains enabled. Progress owns another local database.
- Native Core, AI API, AI worker and Progress started through isolated files.
  Health returned 200; unauthenticated text/dashboard/Progress returned 401.
  Python text/TTS/coach/review routes are configured, Core caller budgets/rate
  limits enabled, paid demo budget zero, Progress publishing on and reads shadow.
  This verifies startup/authentication, not paid provider generation or production readiness.
- Actual local history: 7 users, 1 completed solo session, no shared session.
  Backfill scanned that session, matched all 7 dashboard projections across
  summaries/trends/levels/errors, left 0 pending/failed events, and a second
  backfill added no events. This small dataset supplements synthetic tests;
  it is not broad production parity. Remote Progress reads are not cut over yet.
- Native Windows startup RSS snapshot: Core 668,950,528 bytes, Progress
  220,696,576, AI API 98,512,896, worker 69,365,760. Infrastructure is excluded;
  these are neither peak usage nor Linux VPS capacity measurements. The harness
  stopped its own processes after checks.
- Operational instructions and recovery are in `docs/ai-provider-database.md`.
  VPS deployment, resource/recovery acceptance and portfolio evidence remain.

## Latest training conversation applied (2026-10-28)

The completed **train AI conversation** report at commit `129ce65` was checked
against its private frozen evidence. Source-guided R13 translation is integrated
only for delivery scenes, with immutable prompt/helper hashes, source/fact
alignment, targeted term/identifier checks and existing semantic gates retained.
Content now receives server-owned schema/slot ranges; the UI reports word bounds.
No weights or datasets changed. Full scope, evidence and coordinated cutover:
`docs/model-demo-guided-r13.md`.

Original GPU study scores do not constitute a fresh application/CPU quality test.
The updated wrapper has synthetic/unit/integration evidence; fresh CPU generation
still requires 20 GiB free RAM. B1 soft length targets and independent semantic
evaluation remain open. No production deployment or general adapter superiority
is claimed.

## Supervised local startup and remote Progress cutover (2026-10-28)

- `scripts/run-service.py stack --config-dir data/microservices` supervises
  Core, AI API/worker and Progress using their separate protected files. An
  optional `--with-model` starts the lazy CPU server. Preflight checks all
  configs/builds, matching internal tokens, loopback routes and free ports.
  Logs are access-restricted; Ctrl+C/startup failure/child exit cleans up owned
  processes without touching shared infrastructure or unrelated applications.
- The actual five-process `--with-model --check-only` run passed Core/AI/Progress
  health checks and model liveness, with the worker alive, then shut down.
  No model weights were loaded and no paid request was dispatched by this check.
  This is lifecycle/startup evidence, not an inference-quality or capacity test.
- A fresh shadow baseline captured actual Core dashboard JSON for all 7 local
  users. After a protected configuration backup, Core changed to `remote`.
  All 7 complete dashboard responses matched, including recommendation and
  listening assessment; metadata correctly identified source `progress`.
  One completed solo session was backfilled; repeated backfill was stable and
  the outbox had 0 pending/failed events. Source numeric types were normalized
  through JSON before comparing; no application values were changed to pass.
- `data/microservices/core.env` now retains `remote` locally. Core/AI/Progress
  health and unauthenticated route checks passed after cutover. The harness
  stopped only its own runtime processes. Production configuration is unchanged.
- Verification covers supervised cleanup/config rejection and the packaged
  Progress process losing connectivity while Core remains the source of truth.
  All 34 launcher/provider CLI checks and 4 Java remote/recovery checks passed;
  UTF-8 checks passed for all 6 edited Python/document files.
  VPS history backfill, full resource/load acceptance, fresh R13 CPU generation
  and production rollout remain open.

## Linux mixed-load rehearsal and demo guide (2026-10-28)

- `tools/ai-eval/stack_capacity.py` creates only disposable, labeled Linux
  containers and synthetic databases/configuration. It retains auth/CSRF/rate
  limits, uses the existing k6 practice/read journeys, and concurrently runs
  Coach jobs through API/RabbitMQ/worker against a private fake cloud registry.
  It measures simultaneous working sets and kernel cgroup events, verifies
  per-user practice/projection/outbox accounting and cross-service database
  access denial, and removes only owned resources.
- 5 users/30 seconds: 117 journeys, 396 HTTP requests, 5 completed practices,
  5 matching projections, 20 AI jobs, zero HTTP/business failures and no pending
  outbox. Core reached its 512 MiB limit; this does not prove memory headroom.
- 20 users/60 seconds, Core limit raised to 640 MiB: 964 journeys, 3,168 HTTP
  requests, 36 completed practices/matching projections, 36 AI jobs, zero
  HTTP/business failures, no pending outbox and no cgroup max/oom/oom-kill events.
  Working-set peak was 1,137.2 MiB for all containers and 1,006.8 MiB for the four
  business processes plus broker alone. The host OS is excluded. Docker had
  16 CPUs, so latency/throughput is not a two-vCPU VPS claim or a scaling study.
- A fresh bounded read-only VPS sample found the same 0.89 GiB physical RAM,
  only 181-192 MiB available and substantial swap use. Redis responded and Core
  remained active without restarts. Database statistics and Prometheus could not
  be retrieved in the bounded probe; the full production baseline remains open.
- The owner selected a 2 GB VPS upgrade, followed by actual-host measurement
  before full rollout. Hardware upgrade completion has not been verified.
- Sanitized report: `artifacts/reports/microservices-stack-20261028.json`.
  Architecture, startup, demo narrative, exact commands and evidence limits:
  `docs/microservices-demo.md`. This completes the resource rehearsal tooling,
  not the remaining real-model, production-baseline or VPS deployment gates.

### Follow-up production baseline

A later read-only probe returned HTTP 200 from Core health and Prometheus, but
the direct PostgreSQL connection was refused because the account/project quota
was exceeded. Health alone therefore does not establish database readiness.
The host still reported 931,352 KiB physical RAM; the planned 2 GB upgrade is
not yet observed. Resolve database availability and verify a backup/restore
before a production migration. No credentials, production configuration or
service state were changed. The owner subsequently confirmed that the production
database is being moved to AWS and will provide an update. Await that completed
cutover and verify the selected target before deploying this migration; no AWS
database cutover has been verified by this task.

## Coordinated release package (2026-10-28)

- `scripts/linux/service-bundle.py` exports one Git revision and builds Core
  with the production S3 profile and Progress from that snapshot. It packages
  both JARs, the AI source, its pinned requirements and prompt artifacts with a
  SHA-256/length manifest. Protected configs, state and model weights stay out.
- The verifier rejects checksum drift, missing files, duplicate names, links,
  traversal and unexpected file types. A pre-existing release is preserved.
  AI Platform CI now runs the integrity checks and builds the same package.
- Actual source `98a340f5ad8144dfd67963b6d3b5c6c1914debfa` produced a 55-file,
  128,679,086-byte package. Bundle SHA-256:
  `486b79fa1df9761a52eacd209996c35959a6ab35581f9d15c0c3305ce6c38857`.
  The Core S3/published-page check passed. Both JARs passed internal ZIP CRC
  checks. Extracted AI code compiled/imported in a network-disabled Linux
  container and loaded the existing conversation artifact, R13 demo artifact
  and private registry migration checksum without starting services.
- Nine integrity tests passed on Windows and Linux. This proves package
  preparation and integrity only. Production installation, coordinated rollback,
  AWS database availability, actual-host load/recovery and fresh CPU generation
  remain open. Existing main-only deployment gates continue to apply.

## Linux service supervision package (2026-10-28)

- Added systemd units for the AI API, AI worker and Progress. The AI processes
  share one dedicated Unix account/0700 persistent ledger directory; Progress
  uses its own account. Units bind HTTP to loopback, isolate configuration/state
  from Core and use read-only release files, restart limits and memory caps.
  No cross-service stop/restart dependency was introduced.
- Bundle format v2 includes and requires all three unit files with payload
  checksums. Existing format-v1 bundles remain verifiable. The package still
  excludes credentials/state and does not activate services.
- Eleven bundle-integrity tests passed locally, including old-bundle
  verification and missing-unit rejection. Linux systemd accepted the real
  units in a disposable root with stub executable paths; this is syntax and
  dependency evidence, not application runtime or permission acceptance.
- Provisioning paths, protected config ownership, Python venv installation,
  parent-directory traversal and activation boundaries are documented in
  `docs/microservices-demo.md`. Actual installation, state backup, coordinated
  rollback, AWS/VPS acceptance and new R13 CPU generation remain incomplete.
