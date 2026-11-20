# V5.2 DeepSeek output quality: 25 paired inputs, 2026-11-20

The optimized layout increased cached input but did **not** establish better content quality or lower
total cost. Original completed 17/25 lessons; optimized completed 16/25. Provisional review found no
material issue in 7/25 original and 9/25 optimized admissions. However, among the ten pairs where both
outputs completed, five were clean in both layouts, two were clean only in original and three needed
revision in both. Fifteen incomplete pairs prevent a reliable paired quality comparison. Keep the
candidate in evaluation; these results do not justify learner activation.

## Scope and frozen protocol

The owner authorized **25 inputs times two request layouts, 50 paid lesson admissions total**.
Content quality is the primary outcome: dialogue, question value, answers/evidence, mandatory grading
scope and Vietnamese translations. Cache, cost and latency are secondary. No live learner GRADE,
extra paid judge, TTS, warm-up or replay of an admitted failed request was performed.

The inputs are a fixed stratified regression subset of the earlier 60-input dataset, without filtering
for past success: nine general communication, eight TOEIC and eight IELTS inputs. All five inputs from
the ten-admission DeepSeek pilot are included. This is regression evidence, not an independent holdout
or a random sample of learner traffic. The older adaptive60 experiment used a modified shared V5.2
base; its success rate is not a baseline for this canonical V5.2 run. Its correction remains: three
zero-example selector fallbacks at i09/dynamic calls 7, 8 and 9, within 20 total zero-example calls.

| Program | Frozen case IDs |
| --- | --- |
| General communication | g01-coat-check, g03-coin-laundry, g05-dog-bowl, g07-recycling, g10-potluck, g11-bike-brake, g13-sewing-class, g16-rooftop-access, g19-quiet-festival |
| TOEIC | t01-visitor-badge, t04-sample-labels, t07-printer-toner, t10-safety-glasses, t13-product-return, t16-forecast-range, t19-distributor-region, t20-audit-sample |
| IELTS | i01-field-kit, i04-specimen-drawers, i07-dorm-key, i09-urban-shade, i10-transcript-version, i15-coral-observation, i16-text-difficulty, i19-translation-corpus |

- Canonical V5.2; same prompt, selected example policy, schema, validators and Harness throughout.
- Existing `original.v1` versus `implicit.v1`. Programs interleaved; pairs run sequentially with the
  first layout alternating. Concurrency one. The shared provider cache is neither cleared nor warmed
  artificially; reuse across layouts and natural warming remain possible.
- Configured/returned model `deepseek-flash`, temperature zero, thinking disabled by the existing
  gateway, no fallback. No DeepSeek-specific schema-prefix override was added.
- The existing adapter uses JSON-object mode, a leading schema system message and local validation.
  This is a comparison of the two existing layouts, not an adapter redesign.
- DeepSeek is selected only in the temporary evaluation API process. The canonical learner provider
  remains Gemini and its request layout remains unchanged. No production change is part of this test.
- Source commit: `9819338b234d566848be47b0b85f03f6f38824a2`.
- Backend fingerprint: `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74`.
- Prompt hash: `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`.
- Read-only audit helper hash: `1eaf04f7a57bc1170ef168b8fd5474f67ba5b77d7e2ed52ab2b138207c0cf15c`.
- Frozen plan hash: `b4cabccf35f77a94b13517704107f7e863755fbbe3bc2b8bfcea0022fd4c5f71`.

## Quality review method

Both outputs are assessed separately using metadata-hidden packets before joining layout labels.
The frozen rubric is `v52-output-quality-regression.v2`, with six dimensions: source fidelity,
naturalness/level, question value, answer/evidence, grading scope and translation. Each dimension
is OK, MINOR, MATERIAL or UNASSESSED. Overall verdicts are NO_MATERIAL_ISSUE, NEEDS_REVISION
or UNREVIEWABLE. Minor wording concerns alone do not make a lesson require revision.

The reviewer checks explicit facts, uncertainty, modality, actor attribution and proposals versus
acceptance. Compatible fictional setting details are allowed. Questions need useful, supported,
distinct targets; there is no minimum of two questions and no forced inference label or coverage.
For mandatory keys, a minimal correct answer is compared with the requested scope. Equivalent or
entailed wording is accepted; redundant premises are distinguished from independently unasked facts.
Translation checks preserve polarity, conditions, timing and factual meaning.

A request without a final lesson is UNREVIEWABLE, with all six content dimensions UNASSESSED. Planned inputs stay
in the denominator. Structural/Harness acceptance and content findings are reported separately.
The labels are provisional Codex reviews, not expert-calibrated human scores. The same reviewer
supervises execution and can infer some order, so this is partial blinding, not an independent or
double-blind study. Labels for two already annotated failed extractions appeared during diagnosis;
those UNREVIEWABLE annotations were recorded before that inspection. No live learner scoring was
tested. The refined rubric is not directly comparable to the pilot's coarser unblinded defect rate.

## Results

| Outcome | Original | Optimized |
| --- | ---: | ---: |
| Planned / accepted admissions | 25 / 25 | 25 / 25 |
| Completed final lessons | 17/25 (68%) | 16/25 (64%) |
| No material issue found, all planned | 7/25 (28%) | 9/25 (36%) |
| Needs revision | 10/25 (40%) | 7/25 (28%) |
| Unreviewable: no final lesson | 8/25 (32%) | 9/25 (36%) |
| No material issue found, completed only | 7/17 (41.18%) | 9/16 (56.25%) |
| ProviderError after HTTP 200 | 6 | 5 |
| QualityRejected | 2 | 4 |

These labels do not certify expert quality or equivalence. The completed-only percentage excludes
failed lessons and must be read alongside the planned denominator. All 33 completed artifacts pass
the existing Core acceptance functions, while 17 of them still have a material content-review issue.
Passing the structural/Harness contract is therefore not a substitute for independent content review.

| Paired result over 25 inputs | Count |
| --- | ---: |
| Both complete and without material issue | 5 |
| Both complete; only original without material issue | 2 |
| Both complete; only optimized without material issue | 0 |
| Both complete and need revision | 3 |
| At least one output missing | 15 |

The two original-only clean pairs are i04-specimen-drawers and i07-dorm-key. The optimized arm's higher
overall clean count comes from additional clean outputs on inputs where original failed to produce a
final lesson; it does not demonstrate better answers on matched complete outputs. One run per layout,
partial blinding, regression selection and missing counterparts preclude a general superiority claim.

### Content issues

| Material dimension, completed lessons only | Original (17) | Optimized (16) |
| --- | ---: | ---: |
| Source fidelity | 0 | 2 |
| Naturalness / level | 0 | 0 |
| Question value | 4 | 2 |
| Answer / evidence | 1 | 1 |
| Mandatory grading scope | 5 | 2 |
| Translation | 1 | 0 |

A lesson may appear in multiple rows. Zero means no material issue identified in this review, not
proof that a dimension is perfect. Minor translation, wording and redundant-key concerns are retained
in the protected annotations rather than counted as material defects.

| Representative case | Original | Optimized |
| --- | --- | --- |
| g05-dog-bowl | Final questions retest bowl location/water facts already assessed | Same duplicate-target problem |
| g07-recycling | Final recap repeats the bin/rinse targets | Same duplicate-target problem |
| i04-specimen-drawers | No material issue found | A where-question requires an extra left-side direction despite the unique drawer ID being sufficient |
| i07-dorm-key | No material issue found | Card is said to work, then the next instruction tells the student to report it broken without marking that as hypothetical |
| t10-safety-glasses | Repeated replacement-timing questions; a what-action key additionally requires trial duration | No final lesson: invalid QUESTIONS JSON |
| t16-forecast-range | Attitude question's key mandates the effect of pushback instead of appreciative attitude | No final lesson: QUESTIONS schema mismatch |
| g16-rooftop-access | Dialogue leaves safety unresolved, but a mandatory key asserts the railing is unsafe | No final lesson: QUESTIONS schema mismatch |
| i15-coral-observation | Why-key additionally requires a reviewer's reaction | Question turns conditional willingness to try correction into asserted unwillingness |
| g19-quiet-festival | No material issue found; registration remains distinct from satisfaction | No material issue found; one useful final-activity question is accepted without padding |

The main content priorities are distinct useful questions, keys limited to what the question asks,
and preservation of unresolved facts in every answer and mandatory part. Do not solve them by
renaming skills, forcing inference questions or adding questions just to meet a count. These are
recommendations from the test; no prompt or validator fix was applied during or after this batch.

### Failures and recovery

All **11 ProviderError outcomes followed HTTP 200** with finish reason `stop`; none is a transport
outage or output-length truncation. Offline inspection of the retained choices identifies:

- Four invalid JSON responses at QUESTIONS: original visitor badge / urban shade; optimized safety
  glasses / audit sample.
- Six QUESTIONS schema mismatches: original printer toner / distributor proposal / text difficulty;
  optimized transcript version / forecast range / rooftop access. Required activity fields are absent
  while incompatible fields appear inside assessment objects.
- One EXTRACT schema mismatch: original translation corpus uses invalid character IDs.

The six QualityRejected outcomes comprise both product-return extractions (three topic IDs where
Pydantic permits two), original bike-brake recovery ending with `missing_learning_focus_coverage`,
and optimized coat-check, potluck and text-difficulty recovery ending unresolved. In particular,
text-difficulty's saved reviewer identifies an unanswerable why-question and repeatedly requests
removing it rather than inventing a replacement. A reviewer finding is diagnostic evidence, not an
independent human verdict. The potluck review also disputes ambiguous egg-free wording, so that
rejection should not automatically be treated as a proven content contradiction.

Original/optimized each perform six calls named REPAIR. The optimized arm also does more repeated
review/regeneration work; REPAIR counts alone therefore understate total recovery cost. No provider
fallback or external replay occurs. Initial-artifact `KeyError` metadata for incomplete lessons only
means that an artifact needed for assembly is missing; it is not the root cause of these rejections.

### Cache, cost and latency

| Measurement, all admitted lessons including failures | Original | Optimized |
| --- | ---: | ---: |
| Observed provider calls | 165 | 189 |
| Usage/cache/cost coverage | 165/165 | 189/189 |
| Input tokens | 694,416 | 807,003 |
| Output tokens | 235,717 | 268,077 |
| Cached input tokens | 353,024 | 505,216 |
| Token-weighted cached input | 50.84% | 62.60% |
| Calls reporting a positive cache hit | 156/165 (94.55%) | 181/189 (95.77%) |
| Explicit zero-cache calls | 9 | 8 |
| Calls in off-peak / peak window | 98 / 67 | 106 / 83 |
| Estimated USD at actual start-time windows | 0.283975992 | 0.313410996 |
| Same observed tokens, all priced off-peak | 0.193698072 | 0.207629898 |
| Actual-window counterfactual with all input uncached | 0.358543800 | 0.423842100 |
| Actual-window cache discount on that same usage | 0.074567808 | 0.110431104 |
| Whole-case wall p50 / p95, including failures | 50.22s / 79.33s | 54.70s / 85.17s |
| Provider-call p50 / p95 | 4.43s / 15.25s | 4.62s / 14.61s |

Cached-input share rises **11.77 percentage points**, but estimated total cost rises **10.37%** at the
actual mixed price windows, and **7.19%** even when both arms are priced at the same off-peak rates.
The candidate does 24 more provider calls and emits 32,360 more output tokens. At common off-peak
rates the arithmetic difference is USD 0.016888050 for additional total input, plus 0.019416000 for
additional output, minus 0.022372224 additional cache discount: **USD 0.013931826 more**. This
decomposition does not establish why generation/recovery lengths changed.

Total actual-window token estimate for the 50 admissions is **USD 0.597386988**. The price snapshot
is `deepseek-flash-2026-11-20.peak-offpeak.v1`: off-peak USD/M input 0.15, cached 0.003, output 0.60;
peak 0.30, 0.006, 1.20. Output is counted once, with no invented storage charge. Natural shared cache
reuse, call mix and early failures confound causal attribution to layout alone. Median case duration
also includes early failures and should not be read as a matched latency advantage.

## Evidence and validation

Protected local evidence is under
`C:/Users/HP/.dictalab/local/deepseek-quality-ab50-20261120/`. It includes the frozen plan/hash,
append-only admission/final ledger, server and client records, complete raw provider usage/finish
metadata, metadata-hidden packets/reviews, lock hashes, per-attempt/per-request exports and offline
failure diagnostics. Private transcripts, raw choices, credentials and runtime data are not committed.

Completed checks: 50 unique accepted POSTs and 50 unique final client/server records, zero replays or
unknown outcomes; all 354 HTTP responses joined to observed attempts by actual wire hash; input
equals cache hit plus miss, total equals input plus output, and application counters match raw usage.
All 354 calls return `deepseek-flash` and have valid usage and pricing coverage, including locally
failed output processing. Fifty unique reviews and packet hashes are verified and locked before
quality/layout aggregation. Annotation lock hash:
`d2866e0890fb373190e907108be51cdc8905f94e064b016c8b4a5ae9cd497201`.

The existing compiled `ScenarioRecoveryTest#savedArtifactsUseTheActualCoreAcceptanceGate` test
passes across all **33 saved final artifacts** with `mvn -o surefire:test` (one parameter-file test,
zero failures/errors/skips). Only the export-redacted prompt-version envelope label is restored from
the exact pinned prompt hash; lesson content and Harness hashes are unchanged. JUnit evidence is
retained privately. This checks Core acceptance functions; it is not a live learner, browser or TTS
end-to-end test. Source/backend/prompt/audit hashes and UTF-8/diff checks pass.

The temporary evaluation API and its supervisor are stopped after the run. Canonical local
configuration is revalidated with Gemini selected, operational limits still disabled and all four
approved two-voice pairs matching. No QA login session was created; shared Docker is left running.

Token estimates use the exact returned model and the documented price window at each provider
request's start time. The batch crosses 06:00 UTC on Friday November 20, so actual-window cost can
mix peak and off-peak pricing. The comparison also reports a common off-peak price normalization.
These are token estimates, not invoices. Sources: [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/),
[cache usage fields](https://api-docs.deepseek.com/guides/kv_cache/) and the
[official 2026 holiday schedule](https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html).

No prompt, schema, Harness, validation or learner routing changes are made to improve these results.
The next implementation priority is reliable DeepSeek schema output plus tighter question/key
scope, followed by another frozen quality comparison before considering activation. No merge or
production deployment is included.
