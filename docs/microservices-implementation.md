# DictaLab microservices implementation

## Accepted scope

Three business services in this monorepo: Learning Core (Spring Boot), AI
(Python), and Progress (Spring Boot). A separate local Model Serving process
loads the existing Qwen3.5-4B base and full07 adapter. Content, Identity,
Billing, and Realtime remain in Core. No retraining or dataset changes.

Local demonstrates all components, asynchronous conversation generation, and a
base/adapter comparison screen with model provenance. VPS must eventually run
all three business services, using cloud inference. The existing VPS reportedly
has 1-2 GB RAM; measure actual capacity and propose any upgrade before spending.
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
- [ ] Separate provider registry ownership from Core database access.
- [x] Make opt-in AI conversation demo jobs durable and restart-safe.
- [ ] Serve the existing base and full07 adapter in a separate local process.
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
  Actual inference through this new server is pending: only about 4.3 GiB RAM
  was available, below the 20 GiB guard. No model quality claim follows.
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
- Remaining: real-model inference/evaluation, resource baselines, independent AI
  registry ownership, real history backfill/cutover, full-system runtime evidence
  and the VPS rollout. All new demo flags default off; no VPS change is implied.
