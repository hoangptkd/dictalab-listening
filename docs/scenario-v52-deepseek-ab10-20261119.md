# V5.2 DeepSeek cache comparison: 10 live admissions, 2026-11-19

DeepSeek returned complete cache accounting for all **69 observed model calls** in this experiment.
The existing optimized request layout had **45.57%** cached input, versus **41.25%** for the original
layout: **+4.32 percentage points**. Estimated token cost was **12.04% lower** for the optimized arm,
but most of that difference came from fewer output tokens and less work, rather than extra cache hits.
Both arms completed four of five lessons. This small run does not establish equivalent content quality
or authorize changing the learner default provider/layout.

## Scope and frozen protocol

The owner selected **five inputs times two layouts, ten paid DeepSeek lesson requests total**. Each
request uses the real EXTRACT/GENERATE/review/question/translation/Harness pipeline, so a lesson may
contain several provider calls. No extra paid judge, learner GRADE, TTS, warm-up, or failed-request replay
was performed. The ten historical Gemini results are reused without new Gemini calls.

| Input | Program and target | Reason for inclusion |
| --- | --- | --- |
| t04-sample-labels | TOEIC 180 | Short factual input and question redundancy |
| g16-rooftop-access | General communication B2 | Conditional safety clearance and unresolved status |
| i19-translation-corpus | IELTS 7.5 | Genre confound and grading scope |
| g19-quiet-festival | General communication B2 | Registration versus satisfaction/preference |
| t19-distributor-region | TOEIC 450 | Geographic scope and unsigned proposal |

These were selected before any new paid call from cases where both historical Gemini layouts completed.
This is a retrospective, success-selected regression subset, not a representative provider reliability
test. Gemini's 10/10 completion on this selected subset must not be generalized.

- Same pinned V5.2 prompt, examples, schemas, validators and Harness; temperature zero; concurrency one.
- Existing `original.v1` versus `implicit.v1`, sequential pairs with alternating first arm. Existing
  shared provider cache is neither cleared nor artificially warmed, so cross-arm cache reuse is possible.
- Provider `deepseek`, configured and returned model `deepseek-flash`, one existing protected source.
  Thinking is disabled by the current DeepSeek gateway. No model fallback is configured for this run.
- Existing DeepSeek adapter sends JSON-object output with the schema in a leading system message and
  validates it locally. The Gemini adapter uses native JSON-schema output. No extra DeepSeek-specific
  schema-prefix optimization was enabled; this tests the two existing layouts rather than a new adapter.
- Provider selection is overridden only inside the temporary evaluation API process. Canonical protected
  configuration, provider registry/default, worker and learner routing are not changed.
- Source commit: `042a2a30bd490cf5ac860956b67fa53ecdf917bd`.
- Backend fingerprint: `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74`.
- V5.2 prompt hash: `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`.
- Frozen plan hash: `8b7e9df00df4523e1e085bba79da942c30f65987065e2eb9be74578e7ead45b4`.
- Read-only usage audit helper hash: `837a5bdff9834d0c2e744fd91eccd5ff7144035c1a513f3af1069eb0d4aee90f`.

## DeepSeek measurements

| Measurement | Original | Optimized |
| --- | ---: | ---: |
| Paid lesson admissions | 5 | 5 |
| Completed lessons | 4 | 4 |
| EXTRACT adapter/output errors after HTTP 200 | 1 | 1 |
| Provider calls, including failed local processing | 35 | 34 |
| Complete usage/cache/cost coverage | 35/35 | 34/34 |
| Successful / failed local provider processing | 34 / 1 | 33 / 1 |
| Input tokens | 161,339 | 154,763 |
| Output tokens | 55,709 | 48,727 |
| Cached input tokens | 66,560 | 70,528 |
| Weighted cached-input ratio | 41.25% | 45.57% |
| Calls with a positive cache hit | 27/35 (77.14%) | 31/34 (91.18%) |
| Calls with explicitly zero cache hits | 8 | 3 |
| Estimated token cost USD, all admitted lessons | 0.047841930 | 0.042083034 |
| Same-usage counterfactual cost with all input uncached | 0.057626250 | 0.052450650 |
| Cache price discount on that same usage | 0.009784320 | 0.010367616 |
| Repair calls | 3 | 2 |
| Whole-case wall p50 / p95, including failed cases | 62.18s / 86.41s | 59.90s / 83.53s |
| Provider-call p50 / p95 | 4.25s / 25.14s | 4.87s / 15.27s |

Total estimated cost for all ten admissions is **USD 0.089924964**. This includes the two locally failed
EXTRACT responses because their HTTP-200 usage is known; failed processing is not treated as free.
All 69 responses returned valid usage. Each raw usage record was joined to an observed attempt by its
actual wire-request hash, with matching input/output/cache counters. Explicit cache hit plus miss equals
input, and reported total tokens equals input plus output. No responses or attempts remain unjoined.

A temporary read-only HTTP audit preserves the complete `usage` object, returned model, timestamps,
status and wire hash in the protected experiment evidence. It does not copy headers or messages, and
its mocked check verifies that request/response content is unchanged. The existing application cache
normalizer is also cross-checked against this independent raw metadata. This audit is experiment-only;
ordinary application telemetry and Studio's Gemini-only pricing table were not changed.

The two corpus EXTRACT calls return `ProviderError` after HTTP 200, with input/output/cache usage.
Their precise parse/schema/finish failure subtype is not retained by the existing evaluation error
ledger. They are not HTTP-503 failures or Harness quality rejections. The generation phase never starts
for those two cases. No paid diagnostic replay was used.

## What the cost difference means

The pricing snapshot is `deepseek-flash-2026-11-19.peak-offpeak.v1`. All calls started between 04:44
and 04:54 UTC, within the documented off-peak window. Exact Flash prices are USD 0.15 per million
uncached input tokens, 0.003 cached input, and 0.60 output. Calculations use actual returned model
identity and actual recorded tokens, with output counted once. This is a token estimate, not an invoice.
See [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/) and
[cache usage fields](https://api-docs.deepseek.com/guides/kv_cache/).

The optimized arm costs **USD 0.005758896 less** in this batch. Its arithmetic decomposition is:

| Contribution at the same price snapshot | USD |
| --- | ---: |
| 6,576 fewer total input tokens at uncached price | 0.000986400 |
| 6,982 fewer output tokens | 0.004189200 |
| Extra discount from 3,968 more cached tokens | 0.000583296 |
| Total observed cost difference | 0.005758896 |

Only about **10.13% of the observed cost difference** is the additional cached-token discount in this
arithmetic decomposition. It does not prove why lengths/repair paths changed. The weighted cache
ratio rose 4.32 percentage points (10.46% relative), while the raw cached-token count rose 5.96%; those
are different quantities. Five pairs, shared natural warm-up, different generated content and repair
paths do not establish a stable causal gain. No quality regression can be justified by the savings.

## Historical Gemini comparison on the exact five inputs

| Measurement | Gemini original | Gemini optimized | DeepSeek original | DeepSeek optimized |
| --- | ---: | ---: | ---: | ---: |
| Completed / selected cases | 5/5 | 5/5 | 4/5 | 4/5 |
| Input/output usage-known calls | 39/40 | 45/46 | 35/35 | 34/34 |
| Cache-measured calls | 0/40 | 1/46 | 35/35 | 34/34 |
| Whole-case wall p50 | 27.51s | 42.65s | 62.18s | 59.90s |
| Complete estimated token cost | unknown | unknown | $0.047841930 | $0.042083034 |

The historical Gemini original/optimized calls use 102,993/129,922 known input tokens and
35,584/49,833 known output tokens, respectively. Most Gemini cache fields are absent, so they stay
unknown rather than zero. No exact cross-provider cost-saving percentage can be inferred. DeepSeek
has complete cache accounting in this run and longer observed median case duration on the subset;
neither observation is a general provider benchmark. The provider dates, available models/fallback,
tokenizers, schema transport and generated question lengths differ. The historical selected Gemini
cases have two provisional manual review flags; this is unblinded regression evidence, not calibrated
relative quality scoring. The full earlier [Gemini batch](scenario-v52-cache-ab40-20261119.md) retains
its failures and interruption and must not be replaced by this selected subset.

## Quality and checks

All eight completed DeepSeek artifacts passed the existing compiled Core acceptance functions. All
eight were also manually inspected by Codex; every one has at least one issue requiring review under
the existing source/question/key/translation rubric. This does not mean every answer is wrong or
establish an independently calibrated failure rate. No expert-calibrated judge or live learner GRADE
was run, so actual learner scoring behavior is not measured.

| Case | Original finding | Optimized finding |
| --- | --- | --- |
| t04-sample-labels | Extra mandatory exclusion in a short what-is-checked key | Third-activity question repeats the earlier code mapping |
| g16-rooftop-access | Necessary report condition becomes guaranteed timing; extra later action in key | Uncertain official inspection becomes a completed-inspection assertion without confirmation |
| i19-translation-corpus | EXTRACT output processing fails | EXTRACT output processing fails |
| g19-quiet-festival | Quantity question requires registration speed; several keys include unasked parts | Quantity question requires registration speed; why-key repeats premise |
| t19-distributor-region | No nationwide exclusivity becomes a stronger regional non-exclusivity conclusion | Action key requires repeating agreement; uncertainty about commitments becomes categorical |

The conversations often sound more conversational than the earlier formulaic examples, but mandatory
grading parts frequently include every clause rather than only what the question requests. Structural
acceptance and quoted evidence do not remove that scope risk. Keep this as an evaluation result, with
no automatic provider/layout activation and no claim of content-quality equivalence.

Completed checks: mock-only read-only audit check; frozen manifest/source/instrumentation hashes;
ten unique paid admissions and ten unique terminal records with zero replays; 69/69 raw-usage joins
and arithmetic/price checks; eight-of-eight manual review coverage; existing
`ScenarioRecoveryTest#savedArtifactsUseTheActualCoreAcceptanceGate` over eight saved outputs via
`mvn -o surefire:test` (one parameter-file test, no failures). Only the export-redacted envelope
prompt-version label is restored from its pinned hash for that check; lesson content is unchanged.

Private inputs, transcripts, full usage audit, checks, per-call/per-request exports and review notes
remain under `C:/Users/HP/.dictalab/local/deepseek-cache-ab10-20261119/` and are not committed.
No provider key, runtime secret or QA password is in this report. No merge or production deployment
was attempted.
