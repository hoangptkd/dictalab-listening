# AI evaluation gate

## Scenario EXTRACT / GENERATE: capture once, inspect offline

`scenario_quality.py` runs the current `app.scenario_workflow.execute` directly with the
configured gateway. It is an evaluation observer, not a Core API/database/TTS end-to-end
test. Production does not import it. The default corpus contains eight synthetic cases
across six groups and three practice profiles, including one unsafe request.

From the repository root, load the existing protected AI settings into the process
environment using the normal local launcher. Do not pass keys on the command line or
copy an environment file into the evidence directory. Scenario execution must be explicitly
enabled in that protected configuration. Then run **capture only when fresh calls are intended**:

```powershell
python tools/ai-eval/scenario_quality.py capture --output data/scenario-evaluation/run-001 --max-calls 64 --max-seconds 1200
```

Each case runs EXTRACT once, including a separate source audit for allowed requests, and
GENERATE once only when EXTRACT allows it. V2 GENERATE uses six bounded calls: dialogue,
translation, one question call per activity, then review. The server assigns extraction character IDs
and requires exact server-owned translation keys. Question calls see only that activity's
speech, translation and role labels plus previous question meanings and answers; they select segment IDs and the server
anchors the exact source text. The real validation and semantic gates apply; failed samples are not repaired,
retried or replaced. The configured shared quota still applies. The budget counts gateway
calls, not billed tokens; wire usage is retained when supplied by the provider. Cloud output
has no guaranteed deterministic seed, even with temperature zero.

The output directory must be new and under ignored `data/`. Reusing it fails before any
provider calls. Artifacts include:

- `manifest.json`: corpus, program, prompt, source and harness hashes; model/provider/config
  identifiers, git revision and budget. Current captures also record library versions.
- `cases.json`, `program.json`, `instructions.json`, `workflow.source.py`, `harness.source.py`:
  the exact synthetic inputs and code used at capture time.
- `<case>/EXTRACT|GENERATE/input.json`, `started.json`, `outcome.json`: correlation/job ID,
  input, elapsed time, status and safe error classification.
- `*-request.json`: messages, schema, options and output budget for each actual call.
- `*-response.json`: parsed model reply **before domain validation**, with provider, model,
  source ID and timing. Rejected content remains available here.
- `*-wire.json`: HTTP status, response hash, model text and usage when present; retains
  malformed successful output too. It excludes headers, URLs and arbitrary provider error
  bodies. Credentials and full settings are not logged.
- `finished.json`, `integrity.json`: completion and SHA-256 inventory of original evidence.
  `derived/` holds versioned analysis separately and is excluded from the original seal.

Inspect/replay **the existing directory**, rather than rerunning capture:

```powershell
python tools/ai-eval/scenario_quality.py report --capture data/scenario-evaluation/run-001 --output data/scenario-evaluation/run-001/derived/report-v1.json
python tools/ai-eval/scenario_quality.py replay --capture data/scenario-evaluation/run-001 --output data/scenario-evaluation/run-001/derived/replay-v1.json
python -m pytest -q tools/ai-eval/test_scenario_quality.py services/ai-generation/tests/test_scenario_workflow.py
```

Both commands make **zero provider calls**. Reports include all recorded replies and failures.
`report --require-pass` writes the report then exits 2 unless every planned case has the correct
EXTRACT allow/deny outcome, every allowed case passes GENERATE, unsafe cases never generate,
and the evidence is sealed and finished. This is automatic acceptance, not human or release approval.
Use `capture --baseline <original-capture>` for a newly authorized candidate: it verifies identical
corpus hashes and records the original integrity hash without overwriting or resending that run.
Replay runs those replies through the workflow and compares results/errors. It rejects a
changed prompt pin or request/schema/options and never falls back to a live provider.
A provider failure with no parsed reply is `UNREPLAYABLE`, not a reason to resend it.
Changed validators may be evaluated offline, but preserve the original replay and label the
new analysis with its code revision. Comparing changed prompts requires a separately
authorized new capture with baseline lineage; never overwrite or replace a failed sample.

After interruption, leave raw files intact. `report --allow-incomplete` can inspect them using
a new output filename; it explicitly marks `UNSEALED_INCOMPLETE` and missing outcomes as
`UNKNOWN_INTERRUPTED`. `UNKNOWN` means an uncertain failed call, `NOT_STARTED` means no
call in that phase, and `BUDGET_STOPPED` means some replies exist but the phase could not
finish. None count as success. There is no automatic resume/resubmit command.

Keep private captures out of Git. Create a second restricted copy/archive outside a disposable
worktree, verify its SHA-256, ZIP CRC and raw integrity inventory, and retain it deliberately
until the quality investigation is closed. The tool does not auto-delete evidence. This logging
is for explicit synthetic evaluation; it does not enable logging real learner content in production.

The first real run and its manual findings are documented in
`docs/scenario-quality-evaluation-20261031.md`. After a prompt/schema change, use `report` to inspect
old evidence; exact replay deliberately refuses a different pin. Previously saved replay results and
archived workflow/harness sources remain available. V2 changes and candidate results are tracked in
`docs/scenario-quality-hardening-20261031.md`.

## Bounded Gemini JSONL batch

`gemini_batch.py` is a separate opt-in cloud generator, not the local benchmark runner below.
Install `services/ai-generation/requirements.lock` plus `training/requirements-prepare.lock`, then
configure the shared pool described in `docs/ai-platform-runbook.md`. The input has one object per line:

```json
{"id":"example-001","messages":[{"role":"user","content":"Return an object with ok=true."}],"schema":{"type":"object","required":["ok"],"properties":{"ok":{"type":"boolean"}},"additionalProperties":false}}
```

```powershell
python tools/ai-eval/gemini_batch.py --input data/batch/input.jsonl --ledger data/batch/checkpoints.sqlite3 --concurrency 3 --max-calls 100 --max-seconds 3600
```

This command makes real calls only when run by the operator. It uses `DICTALAB_AI_GEMINI_MODEL`
(default Flash-Lite), output budget and temperature from Python settings. `--max-calls` is a per-invocation
HTTP-attempt cap, separate from shared RPM/RPD; `--max-seconds` stops new admission/waiting and bounds
HTTP deadlines. Jobs are claimed transactionally in a private SQLite WAL ledger. At most five calls run
concurrently. Use the same ledger/input to resume: DONE, FAILED and UNKNOWN are never sent again.
Input/model/output-budget/temperature drift for an existing ID is rejected.

Quota rejection before HTTP leaves PENDING and waits within the run deadline. Sent 429/5xx or invalid
JSON/schema are FAILED, not retried; timeouts/transport ambiguity and abandoned RUNNING jobs older than
605 seconds become UNKNOWN. Inline JSON Schema (Draft 2020-12) validates results locally; schema references
are rejected. Domain/semantic/human review is still required before importing generated data.

Results remain in `batch_jobs.result` as JSON (`task`, `model`, `provider`, `sourceId`). The command prints
only status counts. Keep input/ledger under ignored `data/` with restricted access and a deliberate retention
period; the offline ledger does not auto-delete results. Reconcile UNKNOWN against provider evidence before
authorizing a new ID/ledger. Never mass-reset states or delete checkpoints to force a retry.

## Existing local evaluation

`run.py` replays redacted synthetic outputs against 15 inputs x seeds 17/43/97. It scores transport,
schema, exact evidence substring, question/fact alignment (including answerability/atomicity), translation,
role, continuity on turns 2-3 and turn-4 independence separately. Invalid/missing/timeout judge labels
never count as passes; coverage and each denominator are retained. The release comparison fails closed
on incomplete judging, dataset/config/model mismatch, unresolved review notes and metric regressions.

Example:

```powershell
python tools/ai-eval/run.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --capture services/ai-generation/tests/fixtures/evaluation/r1-split-translation-v2.json --output artifacts/reports/ai-platform-baseline.json --reviews artifacts/reports/ai-platform-review.json
python tools/ai-eval/run.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --capture services/ai-generation/tests/fixtures/evaluation/r2-slot-contract-v2.json --output artifacts/reports/ai-platform-candidate.json --baseline artifacts/reports/ai-platform-baseline.json --reviews artifacts/reports/ai-platform-review.json
python -m pytest -q services/ai-generation/tests tools/ai-eval
```

The second replay intentionally exits **2** because this candidate is not releasable. Both reports are
reproducible with frozen capture timestamps, dataset hash, model digest, prompt hash and generation config.
Use exit 2 as a release rejection, not a tool/inference failure. Nine keys per arm (20%) have AI review
notes; they are not human approval and never rewrite the original judge flags. Continuity/transfer labels
are only counted for applicable turns. These captures are earlier R1/R2 experiments at temperature 0.7;
the registered server workflow at 0.3 still needs its own paired evaluation.

To capture the current registered workflow on the same synthetic dataset, run from the repo root:

```powershell
python tools/ai-eval/generate.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --output data/ai-eval-new-capture.json
```

This explicitly runs local Ollama, verifies its digest, injects the fixed seed into each offline call and
persists redacted results after each sample. It never enables cloud fallback or calls a paid judge. Use a
new output path; existing evidence is preserved. Give the independent offline judge each record's
`judgeInput` (including turnNumber and the turn-4 rules), then attach a strict boolean verdict. Review only
the deterministic 10-20% sample. No judge/optimizer is imported by production.

Capture format: `{metadata, capturedAt, records}`. Required metadata is `model`, `modelDigest`,
`promptVersion`, `promptHash`, `config`, `judgeVersion`. Records have `id`, `seed`, `task`, `verdict`, and
optional `seconds`/`transport`. The baseline approval is a separate review decision; tools do not grant it.

### Attempt-level evidence for benchmark runs

Current captures measure first-pass quality only. Repair is disabled by default; `generate.py` refuses
`DICTALAB_AI_REPAIR_ENABLED=true` before provider calls. Capture metadata records
`generationPolicy: {repairEnabled: false, maxGenerationAttempts: 1}` separately from the frozen prompt config.
Keep fallback disabled. A workflow still uses one English generation call and one translation call.
Use `--attempts` to retain invalid first outputs even when the public capture has `task=null`.
Every rejected, timed-out or missing sample stays in the planned denominator. Historical captures with
repair remain unchanged; compare their first-attempt evidence, not repaired final success, against new runs.

`generate.py --attempts PATH` writes an offline-only JSONL sidecar: one row per validation attempt plus
failure rows, keyed by `requestId` (the `caseId:seed` capture key). Each row keeps the merged synthetic task,
word count, validation errors, observed model-call seconds and the failure phase. The wrapper in
`attempt_trace.py` calls the original workflow nodes exactly once and never changes prompts, seeds, schemas,
options, temperatures or the repair count; production code never imports it.

Downstream helpers:

```powershell
python tools/ai-eval/benchmark_table.py --dataset <dataset>.json --capture <raw>.json --attempts <raw>.jsonl --output <table>.json [--shadow]
python tools/ai-eval/review_packet.py --table <table>.json --capture <raw>.json --output <packet>.md [--shadow]
python tools/ai-eval/benchmark_report.py --run data/cefr-benchmark/<runId> --output artifacts/reports/<report>.json --markdown <summary>.md
```

`benchmark_table.py` reports first-attempt, repair and final denominators separately; a sample that never
generated is not a first-attempt pass. `review_packet.py` selects every B1 sample, at least six per shadow
level covering expected task types, and every failing sample. `benchmark_report.py` assembles one run
directory and always keeps `releaseAllowed` false; AI review is never recorded as manual approval.

`export_benchmark.py SOURCE DESTINATION` reconstructs the checked-in synthetic fixture captures from the
local historical benchmark, excluding wire logs, billing logs and runtime/account data.
`smoke.py` runs the real Java client against FastAPI and a fake Ollama server, then cleans up both servers.

## B2/C1 offline CEFR shadow

The shadow bundle and dataset are separate from the default B1 candidate. The public API, jobs, catalog,
database and learner selector still accept only A1/A2/B1. The existing `X-AI-Mode: shadow` provider comparison
header does not enable higher levels. C2 is PLANNED and cannot generate even in the offline runner.

```powershell
python tools/ai-eval/generate.py --shadow --dataset services/ai-generation/tests/fixtures/evaluation/cefr-shadow-v1-10x3.json --output data/cefr-shadow-capture.json
python tools/ai-eval/shadow_report.py --dataset services/ai-generation/tests/fixtures/evaluation/cefr-shadow-v1-10x3.json --capture data/cefr-shadow-reviewed.json --output data/cefr-shadow-report.json
```

Run later when ready to evaluate: 10 cases per level x 3 seeds = 60 local samples. Use non-production local
Ollama with fallback disabled. Existing artifact SHA pins must match the selected shadow bundle. Dataset,
model and config are pinned in capture/report metadata. No paid judge runs automatically. Existing outputs
are preserved; use new paths. The provisional 30-90 word bounds isolate discourse/question differences from
B1 within the existing compact format; they do not represent final B2/C1 lengths or extended listening.

For independent judging, use each record's `judgeInput`, including turn rules, level profile and review rubric.
Write all existing boolean `verdict` fields plus `cefrLevelAppropriate`, `taskTypeAccurate`,
`cuesSupportConclusion`, `noExternalKnowledge`, `textOnly`. Preserve raw captures; put judgments in a copy.
Missing, invalid or uncertain labels do not pass. `expectedTaskTypes` in the dataset checks planned coverage;
it does not prove the model's label matches the question. Review that meaning independently.

For manual coverage, attach `manualReview: {status: "approved", reference: "path-or-review-id"}` only after
an actual review. Every benchmark task type per level needs at least one representative reference.
The report includes missing captures as failures in the denominator, checks transport/schema/evidence/focus,
requires at least 95% within length bounds and 90% passing independent semantic review per level, and reports
manual coverage gaps. `candidateGatePassed` is preparation for a separate promotion decision;
`releaseAllowed` always remains false. No tool changes product support status.

The C2 draft rubric and outstanding format/audio decisions are in
`docs/conversation-cefr-classification-plan-2026-10-21.md`. No C2 dataset or generation is implied by that draft.
