# V5.2 controlled three-model experiment, 2026-11-20

Status: complete. The owner selected **30 inputs × three models × one run = 90 lesson admissions**.
The owner later explicitly opted out of the 15-output review, so the final quality labels below are
Codex-only provisional judgments. No repeat generation, extra paid judge, learner GRADE or TTS is included.

## Frozen design

- Ten general-communication, ten TOEIC and ten IELTS briefs, newly authored before any call. They
  cover factual distinctions, deadlines, tentative outcomes, actor attribution, confounds and key scope.
  These are regression-style challenge cases, not a random population sample or expert-labeled gold.
- Each exact input/profile/learning context is identical for all three models. Canonical V5.2 prompt
  hash `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b` and backend hash
  `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74` are frozen.
- Models: `deepseek-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`. Every stage in a lesson is
  pinned to its assigned model; fallback is empty and actual request/response model identity is checked.
- One existing registered source per model. Gemini 3.5 and 3.1 use the same existing project and key;
  no registry/credential changes, account rotation, provider cooldown reset or quota reset.
- Layout `original.v1`, temperature zero, concurrency one, same examples/schema/Harness policy.
  Six permutations of model order occur five times each; programs are interleaved. Order is frozen
  rather than changed in response to outcomes. There is one replicate per input/model.
- Existing adapters remain: Gemini native schema mode on the OpenAI-compatible endpoint; DeepSeek
  JSON-object mode with schema text and local validation, thinking disabled. The experiment compares
  these actual deployed model/adaptor configurations, not transport-independent intrinsic ability.
- Natural provider caching is neither cleared nor warmed artificially. Cache, tokens, estimated cost
  and latency are secondary outcomes and cannot override content errors.
- Three temporary local evaluation APIs use process-only snapshots of the existing settings. Canonical
  learner routing/configuration remains unchanged. QA ID 14, disabled application limits, approved
  four-pair voices, authentication, ownership, validation, provider protection and emergency stop remain.
- Exclusive UUID admission is durable. Never replay an admitted request, including unknown outcomes.
  Stop on unknown admission or wrong model; pause the entire batch after six consecutive terminal
  provider failures. A resumption may admit only previously unsubmitted frozen UUIDs.

## Quality and owner review

Rubric `v52-three-provider-quality.v3` retains six dimensions: source fidelity, naturalness/level,
question value, answer/evidence, mandatory grading scope and translation. Ratings are OK, MINOR,
MATERIAL or UNASSESSED. Verdicts are NO_MATERIAL_ISSUE, NEEDS_REVISION or UNREVIEWABLE.
Compatible fictional elaboration is allowed; uncertainty, conditions and attribution must survive.
No minimum of two questions, inference label or inference coverage is imposed. Independent unasked
facts in mandatory keys are distinguished from redundant premises or entailed equivalent wording.

Every final outcome receives a randomly assigned metadata-hidden review packet. Codex records
annotations before unmasking. Since the reviewer also supervises execution and style can reveal a
provider, this is partial blinding rather than an independent or double-blind expert panel.
Missing final lessons remain unreviewable and are not replaced with successful reruns.

The planned owner audit covered five case triplets, selected before calls: two general communication,
two TOEIC and one IELTS. All three outputs of each case were included, even when an outcome was
missing. The owner explicitly opted out of this audit after execution. No human-agreement or expert
calibration result is therefore available; all final labels are Codex-only provisional judgments.

## Prespecified analysis and limits

Report completion, operational failure and content quality separately, retaining all 30 planned
inputs per model. Primary usable yield is a completed final lesson without a material review issue
divided by 30 planned. Content-only comparisons also show jointly completed pairs and missingness;
failed transport is not automatically a bad-content verdict.

Pair by input, with 95% case-bootstrap intervals (20,000 draws, seed 20261120) and three pairwise exact
McNemar tests with Holm correction. The frozen decision guard requires at least 20 jointly completed
cases, at least ten percentage points primary gain, adjusted p below 0.05, a positive paired interval
lower bound and no unresolved material owner disagreement. This guard concerns usable yield under
the tested pipeline; it does not by itself prove intrinsically superior model content. Non-significance
is not equivalence. Report human/AI agreement, confusion matrix and all disputes on the selected 15.

Thirty authored inputs with one replicate cannot guarantee a decisive result or a universal ranking.
If missingness, disagreement or close results prevent a conclusion, retain “inconclusive” rather than
declare a winner. Comparisons of repaired final lessons are also separated from initial-artifact
acceptance, and repeated review/regeneration cost is counted beyond calls named REPAIR.

The design follows the principle of checking automated judgments against human annotations described
in [Langfuse's judge documentation](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge).
Pricing is frozen from [Google Standard pricing](https://aistudio.google.com/docs/pricing) and
[DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/); these are token estimates, not
invoices. Unreported cache usage remains unknown rather than zero. No cache storage is requested.

Private plan/hash, newly authored inputs, hidden mapping, immutable admissions/outcomes, provider
usage/finish metadata, review packets and owner forms are under
`C:/Users/HP/.dictalab/local/provider-control90-20261120/`. Credentials, raw transcripts and private
runtime files remain outside Git. No merge, learner activation or production deployment is included.

## Final result

| Model | Planned | Completed | Errors | No material issue | Usable yield / planned | Clean / completed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Gemini 3.5 Flash Lite | 30 | 26 | 4 | 21 | **70.0%** | 80.77% |
| Gemini 3.1 Flash Lite | 30 | 26 | 4 | 19 | **63.33%** | 73.08% |
| DeepSeek Flash | 30 | 14 | 16 | 9 | **30.0%** | 64.29% |

`No material issue` means the locked Codex review found no material defect in source fidelity,
naturalness/level, question value, answer/evidence, grading scope or translation. It is not an expert
human score. `Usable yield / planned` keeps failed generations in the denominator, as required by the
frozen protocol. The higher clean rate among DeepSeek's completed lessons is outweighed by its many
missing final lessons when the planned denominator is used.

Operational failures:

- Gemini 3.5: four errors — two provider 503s and two Harness quality rejections.
- Gemini 3.1: four errors — one provider 503 and three Harness quality rejections.
- DeepSeek: sixteen errors — fourteen provider output errors, one timeout and one Harness rejection.
  Five provider errors were QUESTIONS failures, nine were EXTRACT failures and one was a review-stage
  timeout. No DeepSeek fallback occurred.

The 66 completed lessons received 66 content verdicts marked clean or needs revision; the 24 missing
lessons are `UNREVIEWABLE`. Material review flags by dimension were:

| Dimension | Gemini 3.5 | Gemini 3.1 | DeepSeek |
| --- | ---: | ---: | ---: |
| Source fidelity | 4 | 4 | 1 |
| Question value | 1 | 0 | 3 |
| Answer/evidence | 2 | 1 | 2 |
| Grading scope | 0 | 3 | 1 |
| Naturalness/level | 0 | 0 | 0 |
| Translation | 0 | 0 | 0 |

A lesson can appear in more than one dimension. Common defects were role/fact reversal, unsupported
causal certainty, repeated questions and keys requiring an action or premise the question did not ask
for. Structural acceptance was kept separate from these semantic findings.

## Paired comparison

There were 22 jointly completed Gemini 3.5/Gemini 3.1 cases, but only 13 jointly completed cases for
Gemini 3.5/DeepSeek and 13 for Gemini 3.1/DeepSeek. The prespecified superiority guard required at
least 20 jointly completed cases, a ten-point gain, a positive 95% paired bootstrap lower bound and
Holm-adjusted p<0.05, with no unresolved owner disagreement. The owner review was skipped, and the
DeepSeek pairs do not reach the 20-case requirement; no model is activated.

| Pair | Clean-only advantage | Exact McNemar p | Holm-adjusted p | 95% paired bootstrap interval | Jointly completed |
| --- | ---: | ---: | ---: | ---: | ---: |
| Gemini 3.5 − Gemini 3.1 | +6.67 pp | 0.7266 | 0.7266 | −13.33 to +26.67 pp | 22 |
| Gemini 3.5 − DeepSeek | +40.00 pp | 0.0118 | 0.0355 | +13.33 to +63.33 pp | 13 |
| Gemini 3.1 − DeepSeek | +33.33 pp | 0.0414 | 0.0828 | +6.67 to +60.00 pp | 13 |

The model-clean pattern across all 30 inputs was: 4 cases clean for all three; 12 clean for both
Gemini models only; 1 clean for Gemini 3.5 and DeepSeek; 4 clean only for Gemini 3.5; 3 clean only for
Gemini 3.1; 4 clean only for DeepSeek; and 2 clean for none. These categories include failed outputs as
not usable, so they describe end-to-end usable yield rather than an isolated model-content score.

For the matched Gemini-only subset, the result is inconclusive: the 6.67-point observed difference is
small, the interval crosses zero and the corrected test is far from significant. DeepSeek shows a
large operationally adjusted gap against both Gemini models, but its two comparisons have only 13
joint completions and no human audit. Treat that as a strong warning about this pipeline run, not a
universal model ranking.

## Token and cache observations

Gemini returned no recognized cache counters in this run, so cache quality/cost is unknown for both
Gemini models. DeepSeek returned complete usage for 168 provider responses: 695,659 input tokens,
224,864 output tokens and 372,224 cached input tokens, a weighted cached-input share of 53.51%.
These measurements are secondary and do not explain content quality. Current official documentation
lists `gemini-3.5-flash-lite` and `gemini-3.1-flash-lite` as separate models and lists their standard
text input/output and context-cache prices; DeepSeek documents `deepseek-flash` as its Flash model and
reports cache-hit/miss usage fields. See [Google Gemini pricing](https://aistudio.google.com/docs/pricing)
and [DeepSeek model/pricing documentation](https://api-docs.deepseek.com/quick_start/pricing/).

## Decision

Keep Gemini 3.5 Flash Lite as the current baseline. Do not switch the learner default to Gemini 3.1
or DeepSeek based on this one replicate. DeepSeek's completed outputs were often usable, but its
end-to-end failure rate is too high in this run. Gemini 3.5 versus 3.1 remains statistically
inconclusive. The owner skipped the human subset, so no human-agreement or calibrated quality claim
is available. A future decision test should first fix DeepSeek schema/extraction reliability, then run
at least 20 jointly completed cases per comparison with a human-audited hidden subset.

Final private verification: 90 unique admissions/finals, zero unknowns, zero fallback, zero wrong wire
models, 90 locked Codex reviews, API children stopped, canonical provider/configuration unchanged,
application limits still disabled, and no merge or production deployment.
