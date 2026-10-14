# DictaLab capacity — evidence, not a user ceiling

Status: **preliminary; no sustainable maximum established; one evening mixed rerun completed, no ongoing load job**.
Latest result: [evening report](capacity-report-2026-10-03-evening.md), 100/200/300 mixed VUs each passed
120 seconds; 500 stopped by the CPU watchdog. This is separate from the older read measurements below.
The target is measured useful capacity on the current shared VPS. Neither 10,000 nor a k6 safety budget is
treated as a required/maximum user count.

## Environment

- Windows Server 2019, 8 vCPU, 7 GiB RAM; DictaLab, WebLens and other existing applications remained running.
- Java 17; PostgreSQL 17.11; test JVM `-Xms256m -Xmx512m`; Hikari pool 10; HTTP workers 100.
- Isolated database `dictalab_capacity`, 1,000 synthetic learners plus bootstrap administrator;
  24,000 completed practice fixtures, approximately 96,000 word fixtures. Every fixture has only one sentence
  and four words. These are database-read fixtures, not 24,000 concurrent learners or full learning journeys.
- Tested JAR SHA256 `F04299B21BBFAB705AA529FEAA81D087A977D75545AC4A9C635060B1C54A820F`.
  Subsequent workspace builds include changes from the concurrently active UI chat; they are not silently
  substituted into the performance comparison. There is no clean single-commit source baseline.
- k6 generator on HP; app exposed only through a private HP/VPS SSH tunnel. Measurements include the tunnel
  and HP scheduling. No Caddy/CDN, public browser rendering, WebRTC media or real AI capacity is claimed.
- Separate login/cookie/CSRF per VU. Login setup is paced and outside the `phase:work` latency measurement.
  The isolated app has abuse limits disabled; this does not test production login burst/NAT limits.

## Completed checks

Core journey smoke: 1 VU / 20-second scenario, 3 completed sentence chains, 30/30 business checks;
work p95 331.84 ms, p99 412.81 ms. Real hint/replay/complete transitions and playback waits were exercised.

Authenticated reads request catalog, history and dashboard, then wait 2 seconds:

| VUs / 60 s | Completed read cycles | Work p95 | Work p99 | HTTP/business errors |
|---:|---:|---:|---:|---:|
| 10 | 290 | 56.53 ms | 136.42 ms | 0 |
| 50 | 1,450 | 54.01 ms | 107.59 ms | 0 |
| 100 | 2,900 | 65.74 ms | 169.05 ms | 0 |
| 200 | 5,800 | 76.46 ms | 263.99 ms | 0 |

Each read cycle makes three work requests. The exported global `http_reqs/s` includes setup time and must
not be quoted as the steady-state rate. These short runs show feasibility, not soak-tested capacity.

Arrival profile, offered 200 cycles/s, 50 allocated VUs, 60-second work window:
11,862 cycles completed, 139 dropped, zero HTTP/business errors; work p95 39.19 ms / p99 108.19 ms.
Each cycle is three reads. This implies approximately 593 completed work requests/s over the declared
60-second window, not a tested 593 synchronous AI calls/s. Graceful completion and generator overhead apply.
The original captured thresholds passed but did not include drops. The script now requires **less than 1%
dropped iterations**; the old 139/12,000 offered-cycle result exceeds that proposed limit and is **not**
relabelled as fully meeting offered-load acceptance.

Failures retained, not hidden:

- Initial 10-VU cold run: p95 2.74 seconds failed the 1-second target. Subsequent warm runs passed. The server
  metrics observed afterward did not demonstrate a corresponding multi-second DB pool wait; root attribution
  to network/generator/cold-start cannot be proved from that snapshot alone.
- First 500-VU attempt lost transport during setup, before workload. Second attempt authenticated 500 accounts
  but the local SSH forwarding listener disappeared when work began. It did not produce a valid 500-VU app
  capacity measurement. Application and production loopback health remained UP, no VPS reboot was observed.
- Larger artifact transfers also reset/timed out over SSH. A partial native k6 upload is not executable
  evidence. Co-located generator testing requires a matching source SHA256 and separate topology attribution.

## Native co-located generator — separate topology

Native k6 v2.2.0 SHA256 `87DFA91BC3E47BC4BD77911D59D7FF79F25CD76FB8322C97072F08F08A0DA5ED`
was verified before execution. Production, WebLens and other services remained running. A restore-smoke JVM
also briefly ran during this batch; this shared-machine background activity is not a controlled benchmark.

| Read profile | Completed cycles | Work p95 / p99 | Outcome |
|---|---:|---:|---|
| Cold 500 VUs, declared 60 s | No full cycle recorded | 2,483.78 / 2,506.57 ms | Aborted latency threshold; partial route samples |
| Warm 500 VUs, declared 60 s | 742 | 1,080.34 / 1,588.40 ms | Aborted latency threshold; not a full 60 s run |
| 300 VUs, 60 s | 8,660 | 69.07 / 203.25 ms | Passed; about 433 work reads/s over declared window |
| 200 VUs, 300 s | 29,402 | 22.46 / 37.66 ms | Passed; about 294 work reads/s over declared window |

HTTP and business errors were zero in these captures. Aborted runs include partially completed iterations;
do not infer sustained throughput or a 500-user ceiling from them. Exact figures should be read from the
JSON records; rounded console output and setup-inclusive `http_reqs/s` are not steady-state measurements.

Listening smoke completed one journey and 15 checks, work p95 119.29 ms; grading elapsed 2.06 s with empty
recall, a deterministic non-AI path. Room smoke completed one room journey, 17 checks, two WS handshakes,
heartbeat/reconnect and monotonic draft synchronization, work p95 337.85 ms. Neither is a media/provider load
test. All per-VU journey modules now require their declared number of completed journeys.

## Resources and operational checks

The bounded VPS watchdog recorded 290 samples: whole-host CPU peak 91%, minimum free RAM 1,012 MiB,
maximum test JVM RSS 530.54 MiB. A peak CPU sample alone does not indicate sustained saturation or its owner.
The 85% CPU abort requires six consecutive samples; free-RAM abort is below 500 MiB. The one-shot lifetime
ended and the capacity JVM stopped. `DictaLab-Capacity-OneShot` was unregistered; production stayed UP.

Management scrape succeeded on the private management port; app-port metrics returned 401. No Hikari
timeouts were observed in the captured metric snapshot, and pool pending was zero at its sampled instant.
Neither instantaneous value proves that all shorter peaks were absent.

Read-only PostgreSQL test history plan used `idx_sessions_user_status_completed` with incremental sort:
10 rows, 12 shared buffer hits, execution 1.207 ms. It does not justify adding a duplicate index.

Raw evidence is under `target/capacity/`: per-run JSON, result, stdout/stderr, load-generator snapshots,
captured Prometheus exposition, `new-vps-resources.csv` and `new-vps-watchdog.log`. Do not run Maven clean
or discard these records before comparison. Synthetic credentials are not copied into reports.

## Current artifact, original comparison and longer run

Production/capacity artifact for this batch: SHA256
`0C5BF67446954F744BAFE091CAD1B166334ED6DE01C7E948A0084EDF6266D832`.
Original baseline: `780BD1837FC943D687F1B8A8E6CBBC49F60C87E35D1C859E420516140E0686C6`.
Both ran on the migrated isolated V24 database, heap 512 MiB, pool 10, 100 HTTP workers and 4,096 maximum
connections. The original artifact started successfully on the additive schema. This proves startup/read
compatibility for this drill, not a schema rollback or every old write contract. Shared-host background load
was not controlled, and aborted measurements do not support an exact improvement factor.

| Current/original profile | Completed cycles | Work p95 / p99 | Outcome |
|---|---:|---:|---|
| Original cold 300, declared 60 s | 0 | about 1,890 ms / see raw | Aborted latency |
| Original warm 300, declared 60 s | 102 | about 1,280 ms / see raw | Aborted latency |
| Current cold 300, declared 60 s | 0 | 2,322.05 / 2,682.13 ms | Aborted latency |
| Current warm 300, 60 s | 8,256 | 180.97 / 360.94 ms | Passed |
| Current warm 300, **600 s** | **85,962** | **74.02 / 315.37 ms** | Passed; 0 HTTP/business errors |
| Current ramp spike 200, 120 s | 7,942 | 22.34 / 30.22 ms | Passed; 0 HTTP/business errors |

The 600-second run completed about **430 work reads/s** over its declared work window (three reads/cycle).
It is a ten-minute bounded soak, not an hours-long endurance test, login-burst test or public-edge benchmark.
The 500-VU spike stopped in setup at **431 MiB free RAM**, below the 500-MiB generator guard; no valid full
500-VU workload was captured. Do not blame that solely on the app or classify it as a website user ceiling.

Current batch watchdog, 12:43:59–13:09:18: **241 samples**, whole-host CPU peak **100%**, minimum free RAM
**517 MiB**, app RSS peak **578.97 MiB**, sampled pool-pending peak **17**. Generator samples saw the lower
431-MiB RAM event between watchdog samples. Some requests reached about 3.32 s even though aggregate p95
passed. Resources have limited headroom; “p95 passed” does not mean every request was fast or no queuing existed.
Raw: `target/capacity/current-complete-resources.csv`, `current-*.json`, `current-*.generator.csv`.

## Real provider and mixed workload

One acknowledged real ShopAI/Cartesia assisted Conversation journey completed **all four turns**, audio
downloads and same-requestId replays: **28/28 checks**, zero HTTP/business errors, approximately **156 s**
journey time; work p95 **35.55 s**, p99 **36.85 s**, within the separate 90-second AI HTTP target. This is
one working journey, **not** concurrent AI capacity. Translation supplied reproducible answers, so it is
not learner proficiency evidence. The earlier failure was an order-sensitive JSON string comparison;
the replacement compares values/types/array order and has runnable regression tests. The failed raw run
remains retained with zero completed journeys, not retrospectively reclassified.

The first 100-VU synthetic 80/20 read/practice mix aborted at initial catalog p95 **2.09 s**; work p95
**2.06 s**, **zero completed cycles**, no HTTP/business errors. The warm AI paths did not warm core catalog
and practice query/JIT paths. This is not a sustained mixed-throughput measurement. A separate bounded
read/write warm-up and mixed rerun is required before recommending a mixed operating level. Startup health
alone does not establish first-burst latency readiness.

On artifact `031C55D38B183C4ABE68E7A96994B3F7ED41BE38969E2AF4012C74A98903462E`,
five-reader and five-practice warm-ups passed. The subsequent warm 100-VU mixed run recorded
3,420 completed cycles, p95 **791.06 ms**, p99 **7.84 s**, and **28 HTTP 500 responses out of 11,927 requests**
(0.2348%). k6's less-than-1% error threshold passed, but the resource watchdog stopped the isolated app
after six consecutive high-CPU samples. **This run is not accepted as a sustainable operating level.**
No valid 200/300 mixed result followed. k6 Rate `.value` is the failure fraction; `.passes` counts true
failure conditions, not successful requests. The controller now stops escalation after any observed errors.

The captured failures were database transaction/connection acquisition timeouts (approximately 4.7–7.2 s),
with whole-host CPU reaching 96–100%, paging up to 3,986 pages/s and disk queue up to 16. Sampled active
connections were low; this does **not** justify simply increasing the connection pool. Application handling
now maps these specific failures to sanitized 503/Retry-After, including pre-controller account validation.
That improves failure behavior; it does not remove shared-host contention or prove higher throughput.

A read-only 12-sample preflight around 13:51–13:53 on 03 October recorded average CPU **77.92%**, peak
**97%**, minimum free RAM **1,643 MiB**, maximum paging **720 pages/s**, and disk queue **10**. No new
load was started. The user then explicitly deferred load testing because another application is testing.
No recurring retry or future load schedule was installed. WebLens remains running.

## Verified operations and remaining acceptance

Latest consistent DB/media restore and previous-app smoke passed in **84.91 s** on the small production
dataset. Production DB-overload handling is live on artifact
`E39A42108C942B192F9736BBFCEB104CF57DDEEF5028B9205FAB55C0A86EB61C`, service Running/health UP.
This artifact passed 173 tests each on H2/PostgreSQL 18.4 (zero failures/errors, one skipped benchmark);
it has not been load-certified. Grafana 13.2.3 imported 12 panels with a healthy datasource and valid queries;
all seven alert rules passed firing/resolved tests. Temporary monitoring processes were stopped. Provider
OTLP export/collector-unavailable tests passed; full HTTP/JDBC/durable-job collector-storage tracing is
not certified. Synthetic browser login/catalog/history page 2/dashboard/practice/audio activation passed.

Remaining: longer mixed soak, repeatable breakpoint from an independent reliable generator, a larger
realistic evidence dataset and longer endurance, real two-device Wi-Fi/4G media, SMTP delivery, automated off-VPS
backup and collector/continuous-monitoring deployment. One consistent DB/media/JAR snapshot was copied to HP
with verified checksums; it is not an automatic off-host backup schedule. Alert delivery is deferred by the user.
The user resumed one bounded evening load run after approving closure of MoreLogin and the registration tool;
that run is finished. No automatic retry or future load schedule is enabled.
These are not hidden successes. Backup media RPO and disaster-recovery RTO remain separate from DB-only
daily backup and the measured small restore drill.

Largest completed read level in this dataset/topology is **300 warm active read VUs for ten minutes**.
The VPS maximum is **not established**. Priorities from evidence: reserve RAM from shared-host contention;
use an independent generator; handle cold recovery/warm-up; measure mixed writes and provider quotas before
raising production traffic. Production traffic, AI calls, voice bandwidth, idle sessions and dataset size
have different limits. See `operations-runbook.md` for recovery and multi-instance prerequisites.
