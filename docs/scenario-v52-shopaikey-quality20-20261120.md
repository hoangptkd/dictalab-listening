# ShopAIKey versus direct Gemini 3.5 Flash Lite: 20 admissions, 2026-11-20

Twenty new ShopAIKey lesson requests finished execution, producing **eight completed lessons**,
11 `ProviderError` outcomes and one `QualityRejected` outcome. This is not a set of 20 final lessons.
No supplemental paid generation has been performed. The comparison below excludes provider failures
from content scoring and compares only the **eight jointly available final lesson pairs**.

## Content result

| Configuration | Final lessons reviewed | No material issue found | Needs revision | No material issue / reviewed |
| --- | ---: | ---: | ---: | ---: |
| ShopAIKey, requested/reported `gemini-3.5-flash-lite` | 8 | 5 | 3 | **62.5%** |
| Direct Google, `gemini-3.5-flash-lite`, saved matched baseline | 8 | 7 | 1 | **87.5%** |

Five pairs have no material issue in either output; one pair needs revision in both outputs;
two pairs have an issue only in ShopAIKey. None has an issue only in Google. The observed difference
is 25 percentage points in this subset; the exploratory two-sided exact McNemar test is **p = 0.5**
(two versus zero discordant pairs). This small selected sample cannot establish superiority,
equivalence or an intrinsic model-quality difference. “No material issue found” is a provisional
review finding, not a guarantee of error-free lessons.

Review uses the existing six dimensions in `v52-three-provider-quality.v3`: source fidelity,
naturalness/level, question value, answer/evidence, mandatory grading scope and translation. Direct
questions are valid; neither two questions per activity nor inference labels/coverage are required.
Final repaired lessons are assessed, rather than a live learner grading session.

The review is **retrospective, unblinded Codex review**, without expert calibration or an independent
human review. The private folder name `blind-packets` does not make this review blind. The owner had
previously opted out of the planned human audit. No extra paid judge, learner GRADE or TTS was run.

## Material findings

| Input | Output | Location | Finding |
| --- | --- | --- | --- |
| `n-g04-film-reservation` | Shop `S004` | `a3/q1` | The question asks what weather the app shows tonight. The key requires both clear skies and permission to pay for tickets. The payment decision is an independently unasked fact. |
| `n-g05-repair-cafe` | Shop `S005` | `a2/q1` | The question asks why the fan base should be inspected first. The source and Vietnamese answer mention possible loose vibration; the mandatory key repeats the inspection action and omits that reason. |
| `n-t04-dispatch-window` | Shop `S011` | `a3/q1` | The question asks why the courier costs more. The source merely states that it costs more. The answer/key repeat the price contrast without a supported cause. |
| `n-g04-film-reservation` | Google `P007` | `a2/q1` | The question asks why Nam proposes checking the forecast. The Vietnamese answer supplies the clear-weather reason, but the mandatory key only repeats checking before spending money. |

Other findings are minor style/naturalness/question-value issues rather than automatic failures.
The eight pairs comprise four general-communication, three TOEIC and one IELTS input. They do not
represent advanced IELTS quality adequately.

The final review explicitly corrects draft clean labels for Shop `S004` and `S005`, and the historical
Google `P007` label. Draft notes and historical control90 annotations are retained unchanged; the
new review supersedes those judgments **only for this matched-subset analysis**. Do not compare the
new 5/8 result directly with the historical Google 21/30 aggregate or treat the historical clean rate
as revalidated by this subset review.

## Design and comparability

- Twenty inputs were selected before Shop generation from **completed** Google 3.5 baseline cases in
  [the 90-admission control](scenario-v52-provider-control90-20261120.md): first eligible cases by
  existing ordering, stratified as seven general, seven TOEIC and six IELTS. Selection was not random;
  conditioning on Google completion and later on Shop completion introduces survivorship bias.
- Exact input/profile/learning context, canonical V5.2, layout `original.v1`, temperature zero and
  backend match the saved baseline. Each Shop UUID was admitted once; fallback is disabled. Twenty
  admissions can contain multiple pipeline calls, totaling **122 observed provider responses**.
- Prompt hash: `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`.
  Backend hash: `ddf21f186f75893594423bacfbd4fe518fc1d5608f2de304493b9fa0f35bfa74`.
- Shop uses the existing `openai_compatible` adapter at `https://api.shopaikey.com/v1`, with JSON-object
  mode plus a schema system message. Google uses native JSON-schema output through its
  OpenAI-compatible endpoint. These are **different wire formats**. The result compares these actual
  configurations and cannot isolate the proxy from output-mode effects.
- Every observed Shop response reports the requested model label. The underlying checkpoint behind
  that proxy label cannot be independently verified. Provider documentation describes both
  [OpenAI-compatible access](https://shopaikey.com/en/docs/quickstart) and
  [Google GenAI access](https://shopaikey.com/en/docs/google-genai); the latter was not tested here.
- Google artifacts were reused, not regenerated concurrently. Time and provider-version changes are
  additional confounds. This follow-up does not repeat the counterbalanced three-model control.

## Excluded execution outcomes

All 122 captured Shop responses are HTTP 200, report `gemini-3.5-flash-lite`, and end with `stop`.
The 11 `ProviderError` outcomes are **output-processing failures**, not observed HTTP outages:
eight invalid JSON outputs and three schema mismatches. They are excluded from the eight-lesson
content denominator as requested, while remaining visible as operational limitations. No absent
lesson is counted as clean. JSON/schema behavior may depend on the adapter/output mode.

One further request, `n-g02-scarf-seat` / `S002`, is `QualityRejected` after `REPAIR_QUESTIONS`.
It has no final lesson to score, is reported separately, and is not relabeled as a provider error.
Consequently, 20 minus 11 provider failures leaves nine non-provider outcomes: eight reviewed finals
and one Harness rejection. The content percentages above use eight, not nine or twenty.

## Evidence and offline checks

Private evidence stays outside Git under
`C:/Users/HP/.dictalab/local/shopaikey-quality20-20261120/`:

- Frozen plan/hash, unique admission/final ledger, 20 saved outcome wrappers, API runtime receipt,
  raw response/usage audit, and 122/122 attempts joined by wire-request hash with matching usage.
- Original frozen plan bodies retain `runtimeHash: pending`. The actual runtime was resolved before
  admission. A separate `effective-request-manifest.json` reconstructs it from the API receipt and
  verifies **all 20 normalized request fingerprints** against server records; the original plan is
  not rewritten or described as fully executable without runtime completion.
- `matched-eight-index.json`, preserved draft reviews, `review-corrections-and-final.json`,
  `final-reviews-locked.json`, and `content-comparison-summary.json`. Sixteen unique review rows are
  validated against exact saved packet activities, source records and content hashes.
- `core-gate-input.json` exports all 16 final artifacts. Only the redacted envelope `promptVersion`
  is restored from the pinned V5.2 release; lesson content hashes remain unchanged.

The existing compiled Core check passed: **one test, 16 saved final artifacts, zero failures/errors**:

```text
mvn -o surefire:test -Dtest=ScenarioRecoveryTest#savedArtifactsUseTheActualCoreAcceptanceGate -Ddictalab.scenario.saved-acceptance-file=C:/Users/HP/.dictalab/local/shopaikey-quality20-20261120/core-gate-input.json
```

It exercises `ScenarioEngine.validateHarnessCalls` and `ScenarioContent.content`. Passing structural
and Harness acceptance does **not** invalidate the four semantic review findings above. This run
uses previously compiled Core classes; no application code change or full rebuild is claimed.

Offline checks also confirm the current Python backend/prompt hashes match the experiment, protected
local settings retain disabled application limits and the approved four-pair voice pool, Docker
responds, and temporary Shop API port 8097 is closed. The two task process IDs are no longer present.
UTF-8 and Git diff checks pass. No application source, provider registry, canonical routing or runtime
configuration was changed; no merge, production deployment or automatic provider activation occurred.

The practical result is **more content defects observed in Shop's eight final lessons**, with insufficient
evidence to conclude that direct Google is generally better. Obtaining 20 completed Shop lessons
requires separately authorized supplemental paid admissions; none has been silently retried.
