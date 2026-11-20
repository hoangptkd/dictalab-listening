# V5.2 provider quality: what existing evidence can compare

Offline comparison on November 20, 2026. No new provider calls, learner GRADE, judge or TTS.
The stored evidence does not establish a quality ranking between DeepSeek Flash, Gemini 3.5 Flash
Lite and Gemini 3.1 Flash Lite. In particular, the canonical Gemini batch uses fallback, so its overall
result must not be attributed exclusively to 3.5 or treated as an independent 3.1 experiment.

## Evidence and model attribution

| Model or policy | Usable evidence in the canonical batches | Quality interpretation |
| --- | --- | --- |
| DeepSeek Flash | 50 admissions, 33 completed; all 354 calls return deepseek-flash | Locked provisional review: 16 completed lessons without material issue, 17 needing revision; 17 admissions without a final lesson |
| Gemini 3.5 Flash Lite only | 13 completed lessons use only 3.5 in all successful observed calls | Legacy review: one provisional pass, eight passes with notes, four requiring review; not rescored under the newer DeepSeek rubric |
| Gemini 3.1 Flash Lite | Appears in eight completed mixed-model lessons; three have GENERATE, QUESTIONS and TRANSLATE all on 3.1 | Valid content examples exist, but no completed lesson in this 40-admission batch uses 3.1 exclusively throughout the pipeline |
| Gemini primary 3.5 with fallback 3.1 | 40 admissions, 21 completed; 13 completed pure 3.5 and eight mixed | Measures the configured policy, not separate model quality |

The DeepSeek protocol is the [50-admission comparison](scenario-v52-deepseek-quality-ab50-20261120.md).
The Gemini protocol is the [40-admission comparison](scenario-v52-cache-ab40-20261119.md).
Both pin canonical V5.2 prompt hash
`5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`
and backend hash `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74`.
They run on different days. Nineteen input objects are shared and identical, including profile and
learning context. Thirteen same-input, same-layout pairs have completed artifacts from both runs.

The older Gemini review is unblinded and coarser; the DeepSeek review uses a refined rubric and
metadata-hidden packets. For example, repeating a question premise in a why-key was previously
flagged as a scope concern, whereas the newer rubric distinguishes redundancy from an independently
unasked mandatory fact. Do not compare legacy six-of-21 review flags directly with DeepSeek's
17-of-33 material flags, or turn them into a provider quality percentage ranking.

## What the outputs show

On the 13 matched completed pairs, median total English dialogue length is **154 whitespace-counted
words for the Gemini policy** and **287 for DeepSeek**. Median question count per lesson is **3 versus
7**. These are descriptive length/quantity measurements, not quality scores. The selected subset
requires both providers to succeed and cannot estimate general reliability. DeepSeek's longer output
offers more conversational development but also creates opportunities for duplicate targets and
overbroad keys; shorter Gemini output does not guarantee adequate coverage or correct grading.

| Area | DeepSeek observations | Gemini observations |
| --- | --- | --- |
| Dialogue and level | Completed examples generally read naturally and elaborate the situation; no material naturalness/level flag in the provisional 33-output review | Usually shorter exchanges in the matched sample; reviewers also note formulaic phrasing in some examples |
| Question value | Both dog-bowl and recycling layouts retest previously assessed facts; some final recaps add no information | Concise direct questions can work well, but low-information completion questions and overlapping targets also appear |
| Answers and mandatory keys | Some keys require unasked duration, location modifiers, consequences or explanations; rooftop key asserts an unconfirmed unsafe condition | Pure-3.5 rooftop why-question omits the cause from its sole mandatory part; Gemini sewing/corpus examples also include scope concerns |
| Vietnamese | Some minor awkward wording; original coat-check has a material temporal translation issue | Pure-3.5 forecast-range translation changes the actor accepting a task into instructing the other person |
| Output contract | Eleven terminal errors after HTTP 200: four invalid JSON and seven schema mismatches | The 40-admission batch's 17 final provider errors are HTTP errors, rather than evidence that its completed lesson content is inferior |

The schema comparison reflects both model output and transport: DeepSeek uses JSON-object mode with
schema text plus local checks, while Gemini uses the existing native schema mechanism. It does not
isolate intrinsic model instruction-following ability. All 33 completed DeepSeek and all 21 completed
Gemini artifacts passed the existing Core acceptance functions; those checks do not certify semantics.

Concrete model-attributed examples:

- **3.1:** original sample-labels has GENERATE/QUESTIONS/TRANSLATE on 3.1 and preserves code mapping
  and label-only scope. Original transcript-version also uses 3.1 for those stages, keeps hesitations
  for analysis and supplies concise supported questions. These show capability, not superiority over 3.5.
- **3.1:** optimized potluck uses 3.1 for those stages; its why/time keys repeat question premises.
  This is legacy review evidence. Under the newer distinction, repeated premises alone are not proof
  of an independently unasked mandatory fact or an actual learner scoring failure.
- **3.5:** original distributor-region uses only 3.5, asks for the two provinces and appreciative
  attitude with focused keys. This shows a well-scoped example, not a provider-wide win.
- **Both:** rooftop-access demonstrates a key problem on each provider: original Gemini's why-key
  repeats the lack of a decision/date without the cause; original DeepSeek's key converts unresolved
  railing safety into a categorical unsafe finding. Neither is cleared by structural acceptance.

## Decision and missing comparison

Keep Gemini as the current baseline while fixing the shared question/key-scope problems and the
DeepSeek output-contract failures. Existing evidence does not justify replacing Gemini with DeepSeek
on quality grounds, nor does it show that Gemini 3.5 is better than 3.1. Do not interpret HTTP 503 rates,
cache hit rates, verbosity, model names or the configured primary model as content quality rankings.

An isolated comparison would use the same frozen inputs, layout, prompt, example policy and Harness,
with each model pinned for every stage and fallback disabled. Review all final outputs with one rubric
and hidden provider labels; retain failed admissions in separate reliability denominators. No new
paid test was performed. Actual learner GRADE remains untested.

Counts and input identity were recomputed from the stored plans, attempts and terminal artifacts.
Private derived observations are retained at
`C:/Users/HP/.dictalab/local/deepseek-gemini-quality-comparison-20261120/observations.json`.
Private transcripts/configuration remain outside Git. No source, prompt, routing or production change.
