# DictaLab — public launch implementation

Baseline: 2026-10-11. Modular monolith; Linux VPS 1 GiB, Neon PostgreSQL, Upstash Redis.
The source workspace contains earlier uncommitted changes; those are retained.
Later same-day diagnostic/hint releases and corrected load assertions are recorded
in [the continuation report](public-launch-continuation-2026-10-11.md). Release
hashes and load tables below belong to this earlier campaign, not the latest artifact.

| Work package | Source | Local verification | Production acceptance |
|---|---|---|---|
| AI failure diagnostics and recovery | Deadline 25 s, typed provider failures, no hidden retry; compact grading payload | Timeout/truncation/malformed JSON tests pass | Clarify 200; pending-answer grading still rejected by validation, answer retained |
| AI admission budget and emergency stop | V28 additive tables, atomic UTC user/global reservations, audited admin switch | Four checks pass on H2 and PostgreSQL 17/18 | V28 applied; enabled and three durable global admissions observed |
| Email/password policy | Existing outbox/token flow; shared 8-character/72-UTF8-byte BCrypt boundary policy | DTO/security/outbox checks pass | Password policy deployed; SMTP delivery and verification enforcement pending |
| Domain, public policy and content rights | Privacy/Terms/help pages and configurable public contact | Public pages and security headers tested | Pages/email contact deployed; domain and rights evidence pending |
| Loading/error/mobile | Existing states retained | 139 JavaScript checks pass | Six mobile views inspected; no horizontal overflow at 375 px; full state/accessibility acceptance pending |
| Authorization, headers, dependency scan | Admin budget endpoints protected; CSP/referrer/permissions headers; OSV CI scanner | AuthFlow 10/10; 215 patched runtime packages, zero known OSV matches | USER denied both admin budget actions; headers deployed; not a complete security certification |
| Monitoring and notification | Existing metrics/rules | Not applicable | UptimeRobot free monitor UP; 5-minute interval; DOWN and UP test emails received in owner Gmail at 13:53 on 2026-10-11 |
| Offsite backup and full restore | Daily DB-only local timer retained | Production snapshot restored in disposable PostgreSQL/Redis; old and new apps UP | One off-VPS snapshot, 43 checksums verified; automatic full offsite schedule pending |
| Linux release and rollback | Linux CI uses quiescent backup and checksum-verified atomic JAR swap | Python 23 checks: 22 pass/1 POSIX skip on Windows; release 6/6 pass on VPS | New JAR activated with backup; actual rollback not forced on production |
| Browser performance and long load | Existing harness reused from separate Windows generator | HTTP probe and raw k6 accounting retained | 10/20 VU for 300 s pass; 50/100 VU for 120 s fail latency thresholds; multi-hour soak pending |
| Onboarding/daily goal and spaced review | Pending | Pending | Pending |
| AI profile/recommendation | Rule-based recommendations exist | Deferred P2 | Deferred P2 |

Completion requires evidence for each applicable column. A disabled feature, a mock,
or a successful HTTP response does not prove successful provider output or delivery.

## Evidence gathered in this implementation

- Result transcript lookup reproduced 21 SQL statements for 20 sentences in
  `target/public-query-repro/surefire-reports`. Targeted to-one fetch join reduced
  the same test to one statement, preserving sentence ordering and transcript values.
- `target/public-launch-next.log`: 21 tests passed, zero failures/errors/skips.
  Includes query-count regression, UTF-8 BCrypt byte boundary, AI admission
  concurrency/rollback/emergency-stop, and provider timeout without hidden retry.
- UptimeRobot monitor ID `804235833`, target production `/actuator/health`.
  The first external health sample was 160 ms. This is not a business API latency
  or a long-term availability measurement. Test notification emails prove email
  delivery, not an actual production failure/recovery incident.
- Synthetic provider samples: Sonnet 14,723.71 ms; Flash 1,922.50 ms. One sample
  per model; neither is a semantic grading acceptance test. Production model remains unchanged.

## Verified release

- Production JAR SHA-256: `ec813630f5e1d0df06d6bb5aa00bf8f83f3814132aeb47d598112515c3065203`.
- Prior JAR: `d06b9fb3ad547d91083126230026ea219781f2169b2131fd9109cd9301c995a5`.
- Rollback JAR/env directory: `/var/lib/dictalab/backups/release-nwwj1le5`.
- Pre-release quiescent DB/media/JAR: `/var/lib/dictalab/backups/cloud-h2ybyoxq`.
- Off-VPS copy: `C:/Users/HP/.codex/backups/DictaLab/public-launch-20261011/cloud-h2ybyoxq`.
  It contains application data and is ACL-protected; do not publish the dump/media.
- Before/after deploy fingerprints matched every existing business table; only
  Flyway history changed. V28 succeeded and zero unvalidated constraints were observed.
- `target/public-patched.log`: 325 declared tests, 269 pass, 56 infrastructure skips.
- `target/public-patched-reliability.log` and `target/public-patched-reliability17.log`:
  181/181 pass per PostgreSQL major, zero skips. Both strict evidence verifiers pass.
  These suites overlap; do not add their counts as unique test cases.
- `target/public-js-final.log`: 139/139 pass. Python release/config/scanner checks
  and artifact-transfer self-test pass. CI workflow is changed locally, not pushed/run on GitHub.
- Windows JDK 21 used for these builds, targeting Java 17 bytecode. GitHub Java 17
  jobs are configured but this does not prove this revised workflow has run remotely.
- OSV baseline matched four affected packages. Targeted family patches:
  RabbitMQ Java client 5.37.0, Tomcat 11.0.25, Jackson BOM 3.1.7.
  `target/dependency-scan-patched.json` reports zero known matches among 215 resolved
  runtime packages. This is a timestamped advisory scan, not proof all code is secure.
- Slow upload was handled by reusing identical compressed ZIP payloads from the old
  JAR. Reconstruction happens only in staging, rejects an existing destination and
  requires the complete candidate SHA-256. Original interrupted upload was retained.

## Production acceptance and limits

- `target/public-acceptance-20261011/requests.jsonl`: 34 requests, 33 expected outcomes.
  The failure is real conversation grading: HTTP 503 after 6,177.499 ms, reason
  `VALIDATION_OR_INTERNAL`, not the old 60-second timeout. Pending answer remains.
- Clarification succeeded in 7,370.286 ms. A successful clarify does not prove
  every generated task or grading response is semantically valid.
- Result detail, same 36-sentence synthetic session, five samples: mean 899.681 ms,
  min 816.006/max 1,143.629 ms, versus prior mean 1,568 ms. Different run times and
  warm-up/network conditions apply; this is not a controlled causal benchmark.
- Eight synthetic semantic provider probes (four per model) passed schema/status
  checks: paraphrase, partial, contradiction, prompt injection. Sonnet 4,840.15–16,460.29 ms;
  Flash 2,761.15–3,448.98 ms. Model unchanged; these are not comprehensive AI evaluation.
- One bounded diagnostic call using only the existing synthetic fixture passed
  all grading schema rules in 10,940.35 ms. The production validation failure was
  not reproduced in that call; do not infer its exact broken rule or hide the failure.
- AI money reservations are conservative estimates, not invoices. Default admission
  limits are 100 global/40 per-user calls per UTC day and $5/$2 reserved estimates.
  They do not prove an absolute provider billing cap. Unknown outcomes are not refunded.
- `target/public-restore-drill.log` contains the initial failed isolated restore attempt;
  disposable resources were removed. `target/public-restore-drill-retry.log` passes:
  all 27 existing table fingerprints match the pre-release copy; 43 snapshot files
  match; S3 recovery gate confirms no referenced external S3 objects; old and new
  JARs boot from restored DB. Redis is dedicated/empty and provider/scheduling work disabled.
  The passing drill took 34.51 s, not a committed production RTO. No production DB restore occurred.
- Login desktop sample: LCP/FCP 760 ms, TTFB 275.3 ms, CLS 0. Dashboard desktop sample:
  LCP/FCP 592 ms, TTFB 267.9 ms, CLS 0, INP unavailable. These are one-sample lab observations,
  not mobile field Core Web Vitals certification. Axe returned zero executed rules,
  so accessibility is **not** marked passed.
- Mobile screenshots in `target/public-ui/screenshots/`: dashboard/history/listening/
  conversation/account/help all reported viewport/scroll width 375 px. Desktop history
  A2 empty state has actionable links. Full loading/error/focus/contrast audit remains open.

## Remaining work, ordered

1. P0 real SMTP delivery, sender/domain ownership, verification/reset mail acceptance.
   Existing Mailtrap account was located, but a new OAuth permission screen was not
   completed. No paid subscription or domain purchase was made; no further question sent.
2. P0 public domain/DNS and sender authentication. Keep current HTTPS/IP while pending.
3. P0 content rights inventory and evidence per audio collection, policy/retention owner review.
   Educational use or source attribution alone is not accepted as redistribution permission.
   `src/main/resources/static/audio/elllo/manifest.json` records ELLLO source pages
   and links to [the official FAQ](https://www.elllo.org/about/faq.htm). That FAQ permits
   available audio downloads for classrooms/class LMS; it does not establish an
   unrestricted public rehosting license for every DictaLab collection. No license
   was invented. Generated B1 content also needs separate provider/use-right evidence.
4. P0 automatic complete offsite backup and monitoring of backup failure. Current daily
   timer is DB-only/local; copied snapshot is a one-time backup. S3 profile lacks verified
   permission for a separate backup prefix, so IAM was not expanded silently.
5. P0/P1 AI grading schema diagnosis and fuller semantic evaluation; preserve validation,
   pending answers, leases, cost bounds and explicit retry semantics.
6. P1 multi-hour soak and actual production RabbitMQ acceptance after deliberately enabling
   infrastructure. RabbitMQ feature flags remain false; Testcontainers evidence is not
   production-broker evidence. Do not advertise exactly-once processing.
7. P1 onboarding/daily goal and durable spaced review from Error Bank: not implemented here.
   Existing Coach drill/Error Bank remain functional; they are not spaced repetition.
8. P2 learner profile/recommendation extensions stay deferred. Existing rule-based
   recommendation retained. No microservice rewrite or new runtime technology added.

## k6 post-release evidence

Workload remains 80% reader VUs / 20% dictation VUs, 2-second think time and
3-second synthetic playback. No paid AI/media capacity claim is made. Generator
runs on HP Windows, not the VPS; TLS validation and production config are unchanged.

`target/production-load-20261011/launch-soak-10.analysis.json`:

- 10 VU, 300 seconds, 2,767 work requests / 9.223 RPS; p95 493.466 ms,
  p99 646.396 ms; HTTP error rate 0%; all configured thresholds pass.
- 807 started/finished/completed journeys, zero interrupted, 100% business success,
  300 VU samples all equal 10. Auth cleanup threshold passed.
- 58 resource samples in the work window: CPU mean/peak 13.82/35.56%; minimum
  available RAM 101.20 MiB; JVM RSS peak 509.24 MiB, heap peak 118.70/371.25 MiB;
  Hikari active peak 5, pending 0, timeout delta 0, mean acquire 1.08 ms.
- PostgreSQL 20 samples: deadlock delta 0, sampled lock waiters 0, idle-in-transaction
  peak 3 during work, 0 at the post-run invariant probe. Resource sampling may miss spikes.
- Production queues remain disabled; no RabbitMQ throughput/latency measurement exists.
- Campaign-scoped counter and duplicate-active-session checks are all zero.
  The campaign already had 100 synthetic accounts from earlier tests; none were deleted.

20 VU/300-second evidence: 5,506 work requests / 18.353 RPS, p95 511.582 ms,
p99 694.845 ms, HTTP error rate 0%, 1,610/1,610 journeys, all thresholds pass.
All 300 VU samples equal 20. CPU mean/peak 11.93/34.89%; available RAM minimum
85.58 MiB; JVM RSS peak 511.48 MiB, heap peak 119.93 MiB, Hikari pending peak 3,
mean acquire 3.41 ms, timeout delta 0; PostgreSQL deadlock/lock-wait delta 0.
50 VU/120-second evidence (`launch-load-50.analysis.json`): 4,490 work requests /
37.417 RPS, p95 1,010.529 ms, p99 1,322.195 ms, HTTP error rate 0%; all 120 VU
samples equal 50. k6 exit 99: six latency thresholds failed (aggregate, start,
resume, history, hint, play). 1,326 journeys started/finished, 1,325 completed;
one business-state assertion failed despite a successful HTTP status. The exact
assertion cause is not identified; it is not erased by the low HTTP error rate.
CPU mean/peak 17.75/36.15%; available RAM minimum 88.20 MiB; JVM RSS peak
517.08 MiB, heap peak 106.98/371.25 MiB; Hikari active peak 5, pending peak 28,
mean acquire 78.85 ms and timeout delta 0. PostgreSQL sampled deadlock delta and
lock waiters were zero; post-run idle-in-transaction and lock waiters were zero.
Swap usage peak was 233.45 MiB; this is occupied swap, not measured swap-in rate.

100 VU/120-second evidence (`launch-load-100.analysis.json`): 4,510 work requests /
37.583 RPS, p95 2,602.565 ms, p99 2,887.140 ms, HTTP error rate 0%; all 120 VU
samples equal 100. k6 exit 99: both aggregate latency thresholds and all eleven
business-route p95 thresholds failed. 1,349 journeys started/finished, 1,348
completed, zero interrupted. One business-state assertion failed despite HTTP
success; its exact cause remains unidentified. All 100 logout/cleanup checks passed.
Twenty refresh calls in the work window returned 204.
CPU mean/peak 17.44/34.14%; available RAM minimum 109.00 MiB; JVM RSS peak
477.73 MiB, heap peak 113.64/371.25 MiB, GC pause delta 0.339 s; Hikari active
peak 5, pending peak 37, mean acquire 306.30 ms, usage 42.42 ms, timeout delta 0.
PostgreSQL sampled deadlock/rollback deltas and lock waiters were zero. Occupied
swap peaked at 284.65 MiB. Generator CPU peak was 34%, RSS 124.55 MiB, available
RAM minimum 10,216 MiB; no generator CPU/RAM saturation was observed.

| Tested VUs | Work duration | Work RPS | p95 ms | p99 ms | HTTP errors | Acceptance |
|---:|---:|---:|---:|---:|---:|---|
| 10 | 300 s | 9.223 | 493.466 | 646.396 | 0% | PASS |
| 20 | 300 s | 18.353 | 511.582 | 694.845 | 0% | PASS |
| 50 | 120 s | 37.417 | 1,010.529 | 1,322.195 | 0% | FAIL latency; one business assertion |
| 100 | 120 s | 37.583 | 2,602.565 | 2,887.140 | 0% | FAIL latency; one business assertion |

SLO: aggregate p95 <1 s, p99 <2 s; each declared business route p95 <1 s;
HTTP/business errors <1%; journey success/checks >99%; cleanup errors zero and
logout count equal VUs. Raw-window figures above exclude setup/cleanup/drain;
k6 thresholds retain their original scopes, including drain where declared.
Business error rates remain below their thresholds but the two assertions are
still unresolved defects, not a declaration of perfect correctness.

Highest tested on this release: **100 VU**, not accepted. Highest fully accepted
among these four stages: **20 VU for five minutes**. Target 100 VU misses latency
SLO. No intermediate 30/40 VU, >100 VU, open-loop arrival-rate, multi-hour soak,
concurrent burst login, real audio/AI/Room/WebRTC or RabbitMQ load was tested in
this release campaign. Constant VUs with think time do not mean that many RPS or
simultaneous in-flight HTTP requests. The VPS remains 1 GiB/2 vCPU, not 2 GiB.

Measured bottleneck evidence: 50 -> 100 VU increases RPS only 0.45%, while p95
increases about 2.58x; connection-pool waiters and acquire latency rise sharply.
This supports pool/request queue contention, not a claim Neon exhausted CPU or
that adding RAM/pool connections alone would solve it. Prioritize query/transaction
budgets for authentication, history/dashboard and reveal hint before controlled
thread/pool tuning. CPU steal peaked at 19.45% in the 100 VU samples; cloud/network
noise and growing synthetic data make these separate runs an uncontrolled comparison.
Neon compute CPU/RAM, Upstash quota/billing and production RabbitMQ metrics were
not available; no values were invented.

Final read-only evidence: `target/public-load-invariants-final.json` and
`target/public-final-check-post-load.json`. All six campaign-scoped invariants are
zero; idle-in-transaction and lock waiters return to zero. Campaign totals: 100
accounts, 202 sessions, 1,191 sentence attempts, 5,513 word attempts, 1,089 word
events, including earlier campaign data. No records were deleted or Redis flushed.
Application remains UP, PID 86308, NRestarts 0, with the same release/environment
hashes. All four stage logout thresholds pass. A five-minute run is not a
multi-hour soak or proof of the website's maximum capacity.

## Boundaries

No commits, pushes, domain purchases, paid upgrades, IAM expansion, production
data deletion or computer shutdown occurred. Existing dirty changes were retained.
Fresh UptimeRobot dashboard reload stalled at its loading screen; only the earlier
monitor creation/UP sample and delivered DOWN/UP test emails are accepted evidence.
No later continuous-monitoring samples or real incident were invented.
Browser recordings did not preserve authenticated state reliably; failed interactions
are retained as tool evidence, not reported as DictaLab UI defects or successful tests.
UTF-8 checks passed for the edited report, scripts and localized public pages.
