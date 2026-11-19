# AI Evaluation Studio

## Access and scope

Open <http://localhost:18082/admin/ai-evaluation> as an existing ADMIN. API requires Spring profile
`local`, authentication, ADMIN and CSRF. QA14 remains a learner; never elevate it to access this screen.
Provider accounts and fallback policies come from `/admin/gemini`.

New experiment versions use releases advertised by the current Python catalog (V5.2, V5.1 and
V5.0). The learner default remains V5.2. V5.3 is retired for new generation and is not reopened
by the caching experiment. Existing snapshots, runs, judgments and output remain readable;
historical hashes are not rewritten. Unsupported historical releases are rejected before inference.
The current editable prompt source is `scenario-prompts.json`; frozen compatibility remains for
saved learner lessons. Remaining semantic and grading defects are documented in the
[18-case comparison](scenario-v415-v5-18-per-prompt-20261116.md).
See the [activation handoff](scenario-v5-activation.md) for current source/local/runtime status.
Creating an experiment snapshot does not activate or approve a learner/production prompt.

## Durable evidence

Canonical private state: `C:/Users/HP/.dictalab/local/studio/` and `python-records/`.
See [local startup](local-development.md). Keep these outside static resources and Git.
Back up snapshots, judgments and runs together; do not replace the ledger when restarting.

Python evaluation requires `DICTALAB_AI_EVALUATION_ENABLED=true`, `DICTALAB_AI_SCENARIO_ENABLED=true`,
the existing service token and `production_mode=false`. The internal `/v1/evaluation/scenario`
endpoint authenticates before reading bodies and caps input at 64 KiB.

The runner executes EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE and REVIEW,
including their existing bounded repairs. It reserves ten logical calls before dispatch and at most
two additional semantic judgments. Reservations are conservative admission, not measured cost.
GRADE and TTS are outside this experiment; they need their own functional checks.

| Suite | Samples |
| --- | ---: |
| Smoke | 3: CEFR B1, TOEIC listening 255, IELTS listening 5.5 |
| Regression | 9 across the three programs |
| Full | 12 profile inputs × 3 repeats = 36 |

Inputs are synthetic fixed contexts, not human-approved gold lessons or calibrated exam scores.

## Workflow

1. Create a snapshot from the current release.
2. Run Smoke. Inspect raw outputs, stage events, evidence, Harness results and independent judgments.
3. Change the single prompt source through the normal review/test flow, then restart the services.
4. Create a fresh snapshot. Old snapshots cannot dispatch against changed Python/Core code or runtime.
5. Compare compatible saved runs; inspect critical regressions before aggregate rates.

Snapshots pin prompt/few-shot hashes, Python source/assets/dependencies, model/pool/fallback policy,
and Core build. HTTP attempts, returned models and available usage are stored separately.
Canonical request/generation UUIDs remain readable across the sanitized Python transport;
credentials still redact. Usage accepts both provider token fields and scenario-observer token
fields, and only counts nonnegative integer measurements.
The initial artifact uses the complete admitted learning context, including nullable scale
bounds; projected prompt input is not a complete reconstruction source.
The independent text judge also uses the configured Gemini fallback policy for explicit server
rejections. Its returned model and fallback chain remain visible. A fallback verdict is stored
under the actual model identity and cannot satisfy a cache lookup for the requested primary.
Changed configuration requires a new snapshot. Stored histories remain readable and exportable.
No automatic activation follows a comparison result.

UUID idempotency and an exclusive process lock prevent replay of admitted/UNKNOWN/interrupted work.
Stop lets the submitted lesson finish, then prevents the next lesson. Provider/transport failures stop
further paid cases. Do not reset counters, replay an unknown call or rotate accounts to disguise failure.
Definitive `QualityRejected` content failures remain failed samples and allow the next distinct
sample to run. They are not replayed or counted as passing; provider/transport/unknown failures still stop the run.

### Continue an interrupted Full evaluation

An ADMIN may submit a new `/api/admin/ai-evaluation/runs` request with the usual fields and
`continueFrom: <stopped-run-id>`. Use a fresh snapshot of the same release if Core was rebuilt.
Prompt, Python backend, runtime/model policy, judge, fixtures, suite and repeat count must match.
The new run links to its parent and inherits recorded cases; the parent remains unchanged.
Only one continuation may consume a parent. Continue its latest stopped child if another interruption occurs.

Completed cases and definitive content failures are retained without regeneration. An admitted case
whose generation response was lost is recovered by status GET with its original request ID; it is
never submitted again. Unknown outcomes stop admission, and failed judge calls are not automatically replayed.
Transient status GET failures have three read attempts; generation POSTs remain single-attempt.
The combined run keeps all planned samples in its denominator and stores inherited/new evidence together.
`sourceBackendHash` records the parent's Core build; the new run records the current build.

## V4.15 bounded repair feedback (2026-11-15)

Harness implementation `assessment-repair-feedback.v1` fixes repair routing, not the frozen V4.15
prompt release. Source/build fingerprints distinguish the implementation; existing snapshots need
re-registration after restarting local services. Historical prompt hashes and saved evaluations stay intact.

- Missing focus-skill coverage is a bank-level repairable problem. The model receives all existing
  question/meaning IDs so it can choose a grounded pair, rewrite the actual content and echo unrelated
  items unchanged. Code never relabels skills to manufacture coverage.
- Validation reports absent focus coverage alongside local evidence faults, rather than revealing it
  only after another error is fixed.
- A returned patch with schema, scope or deterministic validation errors may receive one correction
  with its rejected draft and exact feedback. The original artifact and target allowlist stay fixed.
  Newly required targets outside that allowlist stop correction. Unchanged invalid drafts are handled too.
- The correction uses the existing one-format-feedback allowance and four-extra-call budget, reserving
  a call for independent review. There is still one adopted patch pass per stage, no loop until success.
  Provider failures/unknown outcomes are not patch feedback and are not replayed by this mechanism.
- The complete candidate must pass structural validation and changed-target review before adoption.
  Failed repairs preserve the original; unresolved advisory findings are not converted to PASS.
  Definitive quality failures now retain the Harness report in the Python evaluation record.

Offline replay of the eight recorded V4.15 rejected cases confirms all eight now have an eligible
repair scope; two expose missing focus together with another fault. This is routing evidence only,
not eight successful AI repairs. The old benchmark outputs and verdicts have not been rewritten.
Malformed initial QUESTIONS that cannot be parsed into a bank, immutable dialogue errors, and
semantically rejected patches still fail closed or retain warnings under the existing policy.

Verification: 327 assessment/Harness/evaluation/prompt-consolidation tests passed locally;
UTF-8 checks passed. These tests use mocked providers and do not measure live model quality.

## Reading results

- Blue is the first output; green is the final output after Harness.
- Initial output is assembled from the first QUESTIONS and TRANSLATE artifacts; repairs cannot retroactively pass it.
- Semantic PASS requires structural validation and nine independent checks.
- Without semantic judgment, structurally valid output remains `REVIEW_REQUIRED`.
- Planned samples stay in the denominator; missing judgments show missing coverage, never a perfect score.
- Harness acceptance and independent semantic quality are separate measurements.
- HTTP attempts, logical calls, measured tokens and actual invoices are different quantities.
- Different actual returned models can make a paired comparison inconclusive.

The judge runs in a separate model context with source evidence checks. It can still be wrong.
Cached judgments require exact input, output, rubric and model identity. Code tests do not prove AI content quality.

## Implicit caching evaluation (2026-11-19)

Creating a snapshot now offers **original.v1** (default control) or **implicit.v1** (evaluation only).
Both use the same pinned lesson prompt, schema, selected examples, model and Harness. The candidate
moves whole EXAMPLES blocks from the system instruction into separate reference user messages,
preserves their content/order, and keeps the actual task JSON in the final user message. Stable
learning context/profile/specification/dialogue precede variable input and repair feedback. Schema
property order, evidence and dialogue/answer arrays are not sorted or removed. This role/position
change needs live quality evaluation before activation; content preservation tests alone do not prove
equivalent model behavior. No padding, model/source routing change, cache API, warm-up call or new
dependency is introduced. Learner requests keep their original layout.

The snapshot/run and durable admission fingerprint include the layout. Continuations cannot change
layout or the recorded pricing version. Candidate results must attest their layout. Python build
fingerprints include nested provider sources; Core fingerprints include observation/metrics classes.
Changed sources require restart and new snapshots. Existing lesson prompt hashes are unchanged.

Every observed cloud attempt preserves total input/output, cached input and its uncached remainder
when explicitly reported, cache status (`hit`, `zero`, `not-reported`, `invalid`), optional reasoning
tokens, returned model, timing, and request/system/schema/prefix hashes. Hashes exclude credentials;
no new raw prompt capture is added to ordinary attempts. The existing protected evaluation log keeps
the candidate's effective messages for reproducibility. OpenAI-compatible reasoning tokens are already
part of completion tokens and are not charged twice. Missing/invalid cache usage is unknown, not zero.
The provider usage database remains unchanged; detailed cache measurements are stored in evaluation
records and existing Langfuse cached-token observations. No database migration is needed.

Studio detail/comparison/export now expose cache measurement coverage, token-weighted cached ratio,
request hit rate, measured cost subtotal, complete estimated cost, cost per judged PASS and provider
p50/p95 separately from whole-case duration. First-seen/repeated prefix counts and p95 are grouped
within a run by source/model/prefix; they do not assert Google's cache was cold/warm. Per-step attempt
records retain failures, fallback, returned model and hashes for diagnosis. Application idempotency and
reused judge verdicts are separate from provider caching, and do not manufacture provider cache hits.
Generation/review/repair cost and judge cost are separate. A caching improvement decision uses the
generation subtotal so that fewer paid judge calls cannot masquerade as a provider cache benefit.
Declared HTTP attempt counts must match recorded lists before any complete-cost claim.

The explicit pricing snapshot `google-standard-text-2026-11-19.v1` uses USD per million tokens:

| Exact model | Uncached text input | Cached text input | Output including thinking |
| --- | ---: | ---: | ---: |
| gemini-3.5-flash-lite | 0.30 | 0.03 | 2.50 |
| gemini-3.1-flash-lite | 0.25 | 0.025 | 1.50 |

Source: [Google Standard pricing](https://aistudio.google.com/docs/pricing). This is an estimate, not an
invoice, and does not cover other service tiers, unknown model/version identifiers, audio, tools or
explicit storage. The current path sends text only and does not request tools/explicit caching.
Unknown returned models never inherit a requested model's price. Total estimated cost is unavailable
if any recorded attempt lacks required usage/price, or a logical call has no attempt evidence. The
measured subtotal and its coverage remain visible. Historical records without cache fields stay
readable and unknown; source data and prior reports are not rewritten. Empty measurements never
produce a zero-cost claim.

Cost comparison requires matching price versions and compatible runs. Lower cost cannot override
missing judgments, semantic/critical regression or unknown cost. `releaseAllowed` remains false;
no experiment automatically activates a prompt. Retest with frozen inputs, bank/selector and execution
policy, paired order and separate repeat observations; review source/question/key/translation quality.
The adaptive60 batch is regression evidence, not an independent holdout. Its three guard fallbacks at
`i09-urban-shade/dynamic` calls 7/8/9 remain recorded within its 20 zero-example calls.

This delivery uses mocked providers and synthetic read-only UI fixtures only. No paid AI, live judge,
learner grading, TTS, runtime restart, merge or production deployment is part of offline verification.
Final offline checks: Python **1,033 passed, 27 skipped**; relevant Core usage/evaluation/client tests
**58 passed**; JavaScript **5 passed**. Browser preview verified missing-cost display and the layout
selector using synthetic data only. UTF-8 and diff checks passed. These results do not measure actual
cache hit rate, provider latency gains, savings or AI quality equivalence.

The later authorized [40-admission live comparison](scenario-v52-cache-ab40-20261119.md) records
real cache hits, incomplete usage coverage, provider failures, the interrupted execution and manual
content findings. It does not establish a caching gain or activate the optimized learner layout.

## Checks

```text
mvn test -Dtest=AiCacheMetricsTest,AiEvaluationStudioTest,AiCallObservationTest,AiEvaluationSecurityTest,SpaRoutingTest,GeminiClientTest,AiPlatformClientTest,ConversationIntegrationTest
node --test src/test/js/ai-evaluation.test.cjs
python -m pytest services/ai-generation/tests/test_cache_observation.py services/ai-generation/tests/test_scenario_prompt_consolidation.py services/ai-generation/tests/test_scenario_evaluation.py services/ai-generation/tests/test_text_gateway.py services/ai-generation/tests/test_provider_admin.py
```

The UI preview fixture under `src/test/fixtures/` is synthetic. It never writes the experiment ledger.
