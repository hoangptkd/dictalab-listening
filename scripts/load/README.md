# Bounded capacity checks

## Practice UX: 50 VU preparation (2026-10-14)

See [the practice UX plan](../../docs/practice-ux-test-plan-2026-10-14.md) for the 50-case matrix,
the input-race regressions, and the isolated `practice-ux.js` workload.
The local runner supports `-PracticeUX -Seconds 30` (1-VU smoke) and `-PracticeUX -Seconds 930`
(gradual ramp to 50 VUs, 10-minute plateau, recovery). It retains existing resource guards,
creates synthetic accounts in a new capacity database and requires dedicated empty Redis.
The smoke and 50-VU profiles have passed on the isolated local environment. Input-race fixes,
the latest rerun and remaining coverage limits are recorded in the
[fix report](../../artifacts/practice-fix-20261014/report.md). HTTP VUs do not test browser typing,
IME or VPS capacity.

## Evidence campaign (2026-10-04)

The new `evidence.js` suite is separate from the historical 80/20 benchmarks below.
Read [the evidence runbook](../../docs/reliability-evidence-runbook.md) before using it.
Current long-run targets are proposals, not achieved results. Do not run against production or bypass a resource guard.
`run-capacity-check.ps1` now requires a runtime manifest for this suite, retains compressed raw points,
checks observed window/peak VUs, reconciles started/finished/completed journeys and workload totals,
rejects malformed consumed metrics, and records repeated management snapshots.
Window/peak maxima alone do not prove sustained concurrency; accounting is necessary, not sufficient.
The 99.9% journey SLO remains unchanged: accounted failures within that limit are allowed.
Real AI is blocked until an enforced financial/call budget exists; the old `-AckProviderCalls` flag alone is insufficient.

Use only the isolated `dictalab_capacity` database and synthetic `capacity-N@loadtest.local` accounts.
Never share a real learner session or seed into production. Each VU has its own login, cookie and CSRF token.

## VPS lifecycle

Upload the verified application, original baseline and capacity scripts. Launch `run-capacity-vps.ps1` as an
interactive Administrator one-shot Windows task, not a recurring trigger. Windows OpenSSH terminates ordinary
child processes at disconnect; direct `Start-Process` over SSH is not a durable application launch.

The remote driver/watchdog stop only the recognized capacity Java PID after the declared runtime, or earlier
at less than 500 MiB free RAM, less than 10 GiB free disk, or CPU at least 85% for six samples. Existing services
stay running. The local runner removes its own SSH tunnel and load generator even on failure. After the batch,
stop the recognized app, wait for its driver/watchdog to exit, export logs/resources, and unregister only
`DictaLab-Capacity-OneShot`. No recurring task is required.

## Run

```powershell
# Every command has setup + work + cleanup budgets and retained JSON/stdout/stderr.
./scripts/run-capacity-check.ps1 -Module core -Mode journey -Users 1 -Seconds 20 -Label smoke
./scripts/run-capacity-check.ps1 -Module core -Mode reads -Users 50 -Seconds 60 -Label new
./scripts/run-capacity-check.ps1 -Module core -Mode arrival -Users 50 -Rate 30 -Seconds 60 -Label new-arrival
./scripts/run-capacity-check.ps1 -Module core -Mode reads -Profile spike -Users 100 -Seconds 90 -Label new-spike
./scripts/run-capacity-check.ps1 -Module core -Mode reads -Users 50 -Seconds 900 -Label new-soak
./scripts/run-capacity-check.ps1 -Module core -Mode mixed -Users 50 -Seconds 120 -Label mixed
./scripts/run-capacity-check.ps1 -Module listening -Users 1 -Seconds 180 -Label listening-smoke
# Historical real-AI command is intentionally blocked; an acknowledgement is not a budget.
```

Increase one bounded step at a time; stop at a failed threshold and investigate. Restore the same heap, pool,
dataset and shared-service topology for baseline/new comparisons. Record SHA-256, timestamps, k6 version,
raw metrics, network path and dataset counts. The SSH tunnel includes HP-to-VPS latency; it is not a proxy/CDN test.

Core validates catalog/history/dashboard schemas and actual check/hint/replay/complete transitions. Playback
and think time are included. `reads` is an authenticated screen-read workload, not idle browser tabs. `arrival`
reports dropped iterations; allocated VUs are not achieved requests per second or real users.
`mixed` assigns four of every five VUs to reads and one to a practice journey. This is an explicit synthetic
80/20 mix, not an observed production traffic distribution. Use a multiple of five for that exact allocation.
The arrival profile additionally requires less than 1% dropped iterations. Older captured runs lacking this
threshold retain their original result, but are not reclassified as fully meeting the new offered-load target.

`run-load-vps.ps1` supports an isolated co-located Windows one-shot run if the HP/VPS SSH transport is the
limiting factor. Verify the transferred native k6 binary with `ExpectedK6SHA256` before executing it; a failed
copy must never be treated as an installed tool. The process has setup/work budgets, per-five-second generator
RAM/CPU snapshots, and the same RAM abort floor. Co-located results include generator contention and cannot
be compared silently to the HP generator topology. The remote task must have no recurring trigger and an
execution-time cap. Native core/Listening/Room smoke has passed runtime validation; 500-VU reads have
also produced retained failed latency thresholds. See the capacity report for topology-specific results.

Listening respects playback duration, saves drafts, submits answers, waits at most 90 seconds for a terminal
grade, then checks report/history. Default empty recall exercises a valid deterministic zero-grade path;
**it does not measure AI grading throughput**. Explicit `RECALL_TEXT` requires a configured provider and a
separate cost budget. Failed grading is not counted as successful completion.

Conversation is deliberately capped at eight accounts and one four-turn journey each. The provider call
acknowledgement is mandatory. It checks audio bytes and completed evaluation, and repeats the same requestId
to check response replay. Translation assistance supplies the answer; this is not proficiency evidence.
The proposed AI p95 limit is 90 seconds, not the 1-second core target. Provider billing/network retries and
exactly-once external effects are not assumed. No fake-provider or WebRTC voice capacity is claimed.
Start the isolated app with `run-capacity-vps.ps1 -RealProviders` only for the acknowledged provider smoke;
it loads existing whitelisted service credentials without printing them. Never transfer provider secrets to k6.
Replay compares JSON values recursively, ignoring object member order but preserving array order/types.
Every declared per-VU journey must complete; a zero-completion script exception cannot pass on successful HTTP checks alone.

The 1-second core p95, less than 1% errors and over 99% business checks are proposed acceptance targets.
Smoke/stress snapshots alone do not establish a sustainable maximum. A capacity report must include a soak,
recovery, shared-VPS resources and provider/media limitations. Never map a seeded row count to concurrent users.

One bounded evening run resumed with user authorization and has finished: mixed 100/200/300 VUs passed
120 seconds each; 500 stopped at the whole-host CPU guard. WebLens/Bilingual remained running. No load
schedule or automatic retry is enabled. When explicitly resumed, `run-final-capacity-vps.ps1 -WarmMixedOnly
-RequireLowBackgroundLoad` performs one bounded preflight, warm-up and 100/200/300 mixed sequence.
Optional `-MixedUsers` selects finite steps, each divisible by five and within the script's 1,000-account
safety budget. This is not a website capacity ceiling; later steps must not run after a failure or guard abort.
Use a one-shot task without recurring triggers and with a 30-minute execution limit; the app watchdog
has a 1,500-second limit. The preflight requires at least ten samples, average CPU <=35%, peak <=60%,
free RAM >=1,800 MiB, disk queue <=2 and paging <=100 pages/s. Preserve its CSV if declined; do not reschedule.
Any failed warm-up/threshold or observed HTTP/business error stops escalation, even below the 1% threshold.
These are safety budgets, not a claimed maximum user count. Keep WebLens and unrelated services running.
