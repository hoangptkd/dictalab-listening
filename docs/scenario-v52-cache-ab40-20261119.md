# V5.2 original vs implicit request layout: live local benchmark, 2026-11-19

This run does **not establish a caching or cost improvement**. Cache usage coverage is reported below;
missing usage remains unknown. Provider failures and model fallback confound the comparison. The
candidate remains evaluation-only; no learner default, production configuration or release gate changed.

## Frozen protocol

- Requested: 20 existing regression inputs, each once with `original.v1` and once with `implicit.v1`;
  40 lesson pipeline requests, rather than 40 individual model calls.
- Distribution: seven general communication, seven TOEIC, six IELTS inputs, spanning simple factual
  recall and constrained higher-level discussions. This is not an independent or random holdout.
- Same V5.2 prompt/schema/examples/Harness and configured Gemini pool. Concurrency one, programs
  interleaved, paired runs with alternating first arm. No model/source routing changes, paid warm-ups,
  explicit cache objects, extra AI judges, learner GRADE, TTS or replays of failed requests.
- Requests use the authenticated local evaluation API and the real Gemini HTTPS endpoint. Core and
  worker were not started for this run. Existing compiled Core acceptance functions check saved outputs.
- Source commit: `2d3bd929c794ae5c580112e5533f67ad8b084d46`.
- Backend fingerprint: `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74`.
- V5.2 prompt hash: `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`.
- Frozen plan SHA-256: `2a1ee62de707049fb2581f7206f0ef50a33aa10a4ee6a2db21693192bb66b011`.
- Primary model: `gemini-3.5-flash-lite`; configured fallback: `gemini-3.1-flash-lite`.
- Admitted requests with an outcome record: **40/40**. Stop reason: `None`.

## Observations

| Measurement | Original V5.2 | Optimized layout |
| --- | ---: | ---: |
| Admitted requests / planned | 20/20 | 20/20 |
| Completed / planned | 12/20 | 9/20 |
| Final provider errors | 8 | 9 |
| QualityRejected | 0 | 1 |
| Provider HTTP attempts | 159 | 149 |
| Success / failure attempts | 133 / 26 | 125 / 24 |
| Attempts with input + output usage | 133 | 125 |
| Observed input tokens (usage-known attempts) | 344797 | 334855 |
| Observed output tokens (usage-known attempts) | 114384 | 117403 |
| Cache-measured attempts | 2 | 3 |
| Weighted ratio on cache-measured subset only | 44.41% | 43.69% |
| Complete estimated cost USD | unknown | unknown |
| All duration-measured cases wall p50 / p95 | 33.50s / 99.74s | 39.66s / 61.08s |
| Completed cases wall p50 / p95 | 33.57s / 99.74s | 42.65s / 61.08s |
| Provider attempts p50 / p95 | 4.38s / 8.62s | 4.54s / 8.87s |
| Observed model fallback transitions | 18 | 15 |
| Repair logical calls | 7 | 10 |
| Harness repair attempts accepted / recorded | 4 / 4 | 6 / 8 |
| Completed initial structural hard-pass | 8 | 4 |
| Unknown execution outcome | 0 | 1 |
| Never admitted | 0 | 0 |
| Cases with measured whole-case duration | 20 | 19 |
| Reported cached input tokens | 3156 | 4765 |
| Cache measurement coverage among successful attempts | 2/133 | 3/125 |
| Whole-run cached input ratio | unknown | unknown |

The local runtime stopped during request 27, after a confirmed durable admission and before the
translation outcome was recorded. Its original API snapshot is preserved; it remains UNKNOWN, with
one unobserved in-flight call and no complete wall latency. It was never replayed. After restarting
the existing Docker dependencies and the AI API, identical source/runtime fingerprints were verified
and only the thirteen never-submitted IDs were admitted. This interruption is an additional timing/cache
confound; the forty admissions do not mean forty completed lessons or forty known execution outcomes.

Original errors: `{"ProviderHttpError": 8}`. Candidate errors: `{"ExecutionInterruptedAfterKnownAdmission": 1, "ProviderHttpError": 9, "QualityRejected": 1}`.

Latencies use the nearest-rank percentile. All-case times include failures and polling overhead;
provider times cover individual observed attempts. Faster failure is not faster lesson completion.
Model transitions are counted from consecutive attempts, including a fallback whose final attempt fails;
successful-event fallback lists alone undercount those transitions. Missing usage on failed attempts is
not treated as zero tokens or zero cost.

There are **7** pairs completed on both layouts; **1** pair has identical returned
stage/model signatures. Those counts do not establish comparable source conditions or statistical power.
The result envelope's primary model name cannot replace the actual per-step/per-attempt model evidence.

## Cached input and cost limits

Original successful-attempt cache statuses: `{"hit": 2, "not-reported": 131}`.
Candidate successful-attempt cache statuses: `{"hit": 3, "not-reported": 122}`.

Five of the 258 successful attempts report valid positive cache usage. Four are REVIEW_QUESTIONS calls
on the fallback 3.1 Flash Lite model; one is a candidate REVIEW call on 3.5 Flash Lite. Thus cache
accounting does observe real hits, but measurement coverage is only 2/133 and 3/125 successful attempts.
The candidate's larger raw cached-token sum is not a measured percentage improvement: the observed
calls, generated content, models and coverage differ. The 44.41% / 43.69% ratios in the table apply only
to the two/three cache-measured attempts, not their arms' entire input. All five measured calls are hits;
that conditional 5/5 must not be presented as the overall cache hit rate.

`not-reported` means the currently recognized compatible cache fields were not measured. The raw complete
provider usage envelope is not retained here, so this run cannot distinguish an omitted field from an
unrecognized alternate representation. It does **not** prove a zero cache hit rate. No cached-input gain,
cache savings percentage or complete invoice estimate can be calculated from unknown cache coverage.
System/schema/model prefix hashes are descriptive local fingerprints; repeated hashes are not measured
Google cache hits and do not attest every reference message or provider-internal token prefix.

Pricing stays pinned to `google-standard-text-2026-11-19.v1` (USD per million tokens): 3.5 Flash Lite
0.30 uncached / 0.03 cached / 2.50 output; 3.1 Flash Lite 0.25 / 0.025 / 1.50. For attempts with known
input/output and an exact priced model only, the mathematical cost interval obtained by assigning all
input either cached or uncached is $0.251552 to $0.338830 for original and $0.265245 to $0.350716 for candidate. These
intervals exclude usage-unknown attempts and are **neither full-run bounds nor observed savings**.
Price snapshot source: [Google Standard pricing](https://aistudio.google.com/docs/pricing).

[Google caching documentation](https://aistudio.google.com/docs/caching?generate-content=true) describes
implicit caching as automatic without guaranteed savings and directs usage inspection to response
metadata. The current path is the [OpenAI-compatible API](https://aistudio.google.com/docs/openai).
Do not substitute native metadata assumptions for measurements absent from this transport.

## Output quality and verification

All **21/21** completed artifacts were manually inspected by Codex for source fidelity,
naturalness/level, question answerability, answer/evidence, grading scope and translation. This review is
unblinded, provisional and not expert-calibrated. Independent AI judgments: **0**, by protocol. Harness
acceptance and manual inspection are separate; neither is an independently measured production quality rate.

Manual review counts: `{"PROVISIONAL_PASS": 5, "PROVISIONAL_PASS_WITH_NOTES": 10, "REVIEW_REQUIRED_GRADING_SCOPE": 3, "REVIEW_REQUIRED_KEY_ALIGNMENT": 1, "REVIEW_REQUIRED_SOURCE_AND_SCOPE": 1, "REVIEW_REQUIRED_TRANSLATION": 1}`.

Program completion, including planned denominators:

| Program | Original completed | Optimized completed |
| --- | ---: | ---: |
| General communication | 3/7 | 4/7 |
| TOEIC | 6/7 | 3/7 |
| IELTS | 3/6 | 2/6 |

All planned cases were admitted. The one unknown execution belongs to optimized TOEIC.

The paired sample-label case preserves blue K8, beige K6 and label-only scope on both arms. The candidate
adds an unnecessary production-order detail, an awkward Vietnamese rendering of “Let us check”, and a
less informative closing yes/no question. Those are quality notes, not an equivalence finding.

At `g10-potluck/implicit`, the accepted artifact has a grading-scope concern: a reason question also
requires repeating its premise, and a timing question also requires repeating the already-given caller
and recipient. The requested reason/time are supported, but additional required parts can penalize
short correct answers. This is a verified key-scope risk; actual learner scoring is unmeasured because
the protocol excludes live GRADE. Structural Core/Harness acceptance does not clear that semantic issue.

Six of the 21 completed artifacts require further review under the provisional manual rubric:

| Layout | Case | Finding |
| --- | --- | --- |
| Original | t13-product-return | No malfunction report becomes a positive working-machine claim; extra mandatory premise in a why-key. |
| Original | g16-rooftop-access | A why-question's sole mandatory part repeats the lack of a decision/date, omitting the cause. |
| Original | t16-forecast-range | Translation changes the speaker accepting a task into instructing the other person. |
| Optimized | g10-potluck | Mandatory parts repeat the premise of reason/time questions. |
| Optimized | g13-sewing-class | Main-idea key additionally requires a reason/general policy. |
| Optimized | i19-translation-corpus | Why-key additionally requires repeating that the proposed method is better. |

These are content/key concerns, not measured learner scoring failures. No live GRADE was run. Other
notes include overlapping comprehension targets, formulaic wording and scientific elaboration that
needs domain review. The sample does not support a causal claim that layout optimization created or
fixed those issues.

At `i09-urban-shade/implicit`, two repairs retain an agreement quote attached to the wrong segment and
mistakenly associate agreement with a later time proposal. Harness correctly leaves the run unresolved.
This is a source-evidence/utterance-scope defect, rather than rejection solely for absent inference labels.
Rejected or provider-failed runs are retained and never replaced by successful reruns.

The historical adaptive60 batch used fixed/dynamic examples on a modified shared V5.2 base. Its three
selector-guard zero-example calls at i09/dynamic (7 REVIEW_QUESTIONS, 8 TRANSLATE, 9 REVIEW) remain part
of its 20 total zero-example calls. Its rates are not used as this benchmark's original-layout baseline.

## Evidence and decision

Private plan, immutable terminal records, admission/final ledger, per-attempt/per-call data, per-request
CSV, provisional quality/error reviews and Core gate input are retained under the local
`cache-ab40-20261119` evidence directory. Transcripts, private configuration and credentials are excluded
from Git. No accounts, authentication limits, provider quota state or existing lessons were reset.

Keep the current learner layout. Before using cached-input gain as a release criterion, resolve the
measurement gap without changing prompt content, and obtain a stable paired comparison with adequate
quality review. No merge or production deployment was attempted.

## Completed checks

- Frozen manifest, exact per-request fingerprints and source fingerprint verified. Forty unique POST
  admissions and forty unique outcome records; 39 known terminal outcomes and one quarantined known
  admission. Zero paid replays. No artificial cache warm-ups or extra model calls for judging.
- All 21 completed artifacts have provisional manual review. Private export/aggregate consistency checks
  passed, including token/cache totals and review coverage.
- Existing compiled `ScenarioRecoveryTest#savedArtifactsUseTheActualCoreAcceptanceGate` passed over all
  21 saved outputs (one parameter-file test, 21 artifacts), using `mvn -o surefire:test`. Only the redacted
  envelope prompt-version label was restored from the exact pinned hash; lesson text, questions, keys,
  translations and content hashes were not rewritten. This is a Core function check, not an end-to-end
  learner, live grading, audio, or TTS test.
