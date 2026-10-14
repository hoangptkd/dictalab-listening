# DictaLab — continuation, 11/10/2026

SMTP follow-up, 18:28 UTC+7: Gmail SMTP is now configured and real verification/reset
mail acceptance passed. See [SMTP production evidence](smtp-production-2026-10-11.md).
Earlier SMTP-pending statements below describe the prior release checkpoint.

## Scope and outcome

Continue the public-launch checklist with evidence-led changes, not new services.
Production remains a modular monolith on the 1 GiB/2-vCPU Lightsail VPS, Neon and
Upstash. No paid upgrade, model switch, pool/thread change, deletion or Git push.
Earlier dirty workspace changes are retained.

## 1. False-negative replay assertion

- Compressed raw k6 points place the two historical business assertions immediately
  after successful `play` requests, not word/counter writes.
- A read-only synthetic state probe found sentence 115 with three words, all three




















































































































.--pz4rf9i
  555555exported dictation journeys use the same condition. Empty, unresolved and
  non-prefilled states still fail. No uncertain write retry was added.
- Regression fails before the harness edit and passes afterward. All 140 JavaScript
  checks pass. Existing failed load evidence is retained, not reclassified as PASS.
- `replay-fixed-50`: 50 VU/120 s, 1,185/1,185 journeys, no HTTP/business failures,
  complete logout. 33.258 RPS, p95 1,374.404 ms, p99 2,423.047 ms: FAIL latency.
  Generator host CPU peak 100% while local builds ran. This is functional evidence,
  not a clean performance comparison. Sampled service restarts remained zero.

Evidence: `target/public-ready-play-repro.log`, `public-load-state-probe.log`,
`public-load-contract-red.log`, `public-continuation-js.log`,
`production-load-20261011/replay-fixed-50.*`. No transcript or credential logged.

## 2. AI failure diagnosis, not a claimed grading fix

- Added only allowlisted validation codes to existing sanitized failure logging:
  JSON_SHAPE, TEXT, CONFIRMATION, FACT_COUNT, FACT, LEARNER_EVIDENCE,
  CONFIRMATION_MISSING, or NONE. Arbitrary provider messages never become log text.
- Eight new tests fail before the diagnostic edit and pass afterward, preserving
  pending answer, lease release, no grade publication and exactly one AI call.
- Diagnostic release SHA-256:
  `4442254a7a242f63a0ad4c7a548ef9c876da7dc7d21f7a108d847af469c02b44`.
  Rollback JAR/env: `/var/lib/dictalab/backups/release-akacuned`.
  Read-only DB fingerprints before/after deploy are identical; no schema/config change.
- Full build: 333 declared, 277 pass, 56 infrastructure skips. Separate reliability
  gate: 189/189 pass, zero skips with PostgreSQL 18.3, Redis and RabbitMQ containers.
  The image was 18.3 in this diagnostic gate, not the earlier 18.4 campaign.
- One real pending-grade attempt at 15:52–15:53 UTC+7 returned 503 after 25,577.278 ms.
  Allowlisted server diagnostic: TIMEOUT, validationRule NONE. Answer retained;
  no provider retry after failure. The old validation rejection did not reproduce.
- Production model and timeout remain unchanged. Do not relax evidence validation,
  award inferred credit, or mark AI grading as fixed based on this work.

Evidence: `target/public-validation-red.log`, `public-validation-green.log`,
`public-continuation-build.log`, `public-continuation-reliability.log`,
`public-validation-release.log`, `public-grade-validation-once.log`,
`public-grade-validation-codes.json`.

## 3. Sentence-reveal query amplification

- Flow: controller -> `PracticeService.useHint` -> owned aggregate lock ->
  `revealSentence` -> DTO state. OSIV stays disabled. No external I/O in this write.
- Deterministic SQL inspector counts only SELECTs from `word_attempts`; INSERT/
  UPDATE and other SQL are deliberately not included.
- Before edit, four scoreable words issue five lookup SELECTs; 32 words issue 33,
  including on repeated reveal. Regression fails its bounded-query requirement.
- Small change: reuse existing ordered list repository query, map by word index,
  create only missing WordAttempt rows. No bulk update/new dependency/migration.
- After edit, both sentence lengths and both first/repeated reveal issue two SELECTs
  (one input list and one DTO-state list). Writes still scale with sentence length.
- Checks preserve proper-name exclusion, saved first answer/correctness, first-letter
  hint, prior word-reveal type, one sentence reveal, counters and replay requirement.
  Injected exception after flush verifies persisted state and new rows roll back.
- Existing duplicate hint/complete tests pass. Full PostgreSQL concurrency gate and
  query/rollback regression pass: 191/191, zero skips on PostgreSQL 18.4, Redis
  and RabbitMQ Testcontainers. Full package: 335 declared, 279 pass and 56 skips;
  do not add overlapping suite counts. The 18.4 gate confirms both word lengths
  retain the two-SELECT budget with real committed service transactions.
  Separate PostgreSQL 17.11 query/concurrency check: 37/37 pass, zero skips,
  including the same rollback and two-SELECT budget. This is a targeted 17 check,
  not a new full PostgreSQL 17 gate; evidence `target/public-hint-postgres17.log`.
- Production hint release SHA-256:
  `eb3b0587efb9c696f08ed08f9834ee742e6dc979ccd5657f2f77eb8c53fb7d81`.
  Rollback JAR/env: `/var/lib/dictalab/backups/release-e5u9pgbl`.
  Quiescent backup ran before activation. Pre/post DB fingerprints identical;
  environment hash remains `ba3b9e31b27a4c1df47eee845e08827766c415bc8ec6e54b2ba39da3041318d1`.
  V28 unchanged, PID 90165, health UP, NRestarts 0 at pre-load check.
- No pool, heap, HTTP-thread, model, deadline or queue flag changed. Measured SQL
  reduction alone is not proof production end-to-end latency improved. Bounded
  load acceptance on the new artifact is recorded below.

Evidence: `target/public-hint-red.log`, `public-hint-green.log`,
`public-hint-build.log`, `public-hint-reliability.log`.

## 4. Post-hint production load

Same separate Windows generator, TLS verified, existing synthetic accounts, 80%
reader / 20% dictation VUs, two-second think and two simulated three-second plays.
No real AI/audio/Room/broker work. No concurrent Maven build during these stages.

| Stage | Duration | RPS | Work p95 / p99 | HTTP / business errors | Acceptance |
|---|---:|---:|---:|---:|---|
| 20 VU | 120 s | 17.417 | 697.131 / 1,135.329 ms | 0% / 0% | PASS |
| 50 VU | 120 s | 35.375 | 1,098.483 / 1,440.496 ms | 0% / 0% | FAIL p95 |

20 VU: all 120 VU samples equal 20; 616 started/finished/completed journeys, no
interruption; all 20 logout checks pass. Work CPU mean/peak 30.79/55.80%, minimum
available RAM 112.18 MiB, app RSS peak 511.09 MiB, heap 113.99/371.25 MiB;
Hikari active peak 5, pending peak 1, average acquisition 9.24 ms, timeout delta 0.
Sampled PG deadlock/rollback deltas and lock waiters zero. Hint p95 966.712 ms
with 36 work-window samples. CPU steal peak 33.36% is a source of tail-latency noise.
Generator CPU peak 18%, RSS 58.30 MiB, available RAM minimum 10,047 MiB.
This passes harness SLO, not a multi-hour capacity SLA or controlled A/B benchmark.

50 VU: all 120 VU samples equal 50; 1,252 started/finished/completed journeys, no
interruption; all 50 logout checks pass. Work CPU mean/peak 26.14/45.81%, minimum
available RAM 109.67 MiB, app RSS peak 497.65 MiB, heap 116.28/371.25 MiB;
Hikari active peak 5, pending peak 22, average acquisition 101.50 ms, usage 44.10 ms,
timeout delta 0. Sampled PG deadlock/rollback deltas and lock waiters zero. Hint p95
1,261.823 ms with 78 samples; start p95 1,524.777 ms. Generator CPU peak 31%, RSS
86.28 MiB, available RAM minimum 10,176 MiB. CPU steal peak 28.52% on VPS.
Aggregate and ten business-route p95 thresholds fail; replay-complete p95 passes.
Pool waiting persists, so the hint optimization does not demonstrate that the
overall capacity target has improved. No claim Neon CPU or RAM was saturated.

No 100 VU, >100 VU or multi-hour test was repeated on this latest artifact.
Highest tested here is 50 VU (latency FAIL), highest accepted here is 20 VU/120 s.
Do not reuse earlier 50/100 results as measurements of this artifact.
Raw percentile window excludes cleanup/drain;
the actual k6 threshold result retains its configured scope. Do not discard slow
samples to manufacture a PASS. The earlier 100 VU test remains failed on its own artifact.

Final read-only campaign probe: all six data invariants zero, idle-in-transaction
and lock waiters zero. Totals include earlier campaigns: 100 synthetic accounts,
203 sessions, 1,383 sentence attempts, 6,624 word attempts, 1,280 word events.
Nothing deleted or repaired. Final health UP, PID 90165/NRestarts 0; JAR/environment
hashes match the latest release. No k6 generator remains. Evidence files:
`target/public-hint-invariants-final.json`, `public-hint-final-check.json`,
`production-load-20261011/hint-fixed-20.*`, `hint-fixed-50.*`.
Changed-file diff whitespace checks and UTF-8 checks (eight files) pass. No commit,
push or computer shutdown occurred. No service registration or new purchase this turn.

## Verification boundaries

Code-quality review of these edits finds no new public API or transaction boundary;
the existing aggregate lock and unique word index remain the write serialization
mechanism. No parallel bulk write was introduced. The extra map lives only for the
sentence reveal and is bounded by the already-loaded transcript word list.
The SQL inspector is test-only and counts actual Hibernate SQL, not repository calls.

## Acceptance still open

Real SMTP, owned public/sender domain, automatic complete offsite backups, legal
and content-rights approval, full UI accessibility/error-state acceptance, multi-hour
soak and production RabbitMQ/AI-job acceptance remain open. Production queue flags
are false. Existing testcontainers broker evidence is not production-broker capacity.

Ponytail limits edits to reused flows. JPA Query Reviewer requires measured query
budgets; Logging Patterns keeps diagnostics free of raw content; Spring testing
skills keep service transaction boundaries and rollback evidence explicit. UTF-8
checks passed for edited files. Code-quality review retains API contracts,
aggregate locking, unique constraints, authorization and validation.

Next code priority: measure authentication/request SQL and transaction budgets
before any further pool/thread change; isolate per-request duplicate reads if
confirmed while preserving enabled/authVersion/role checks. AI priority: bounded
provider reliability evaluation plus fixed-rule validation diagnosis on explicit
failed attempts, without blind retries or changing the model globally from one sample.
