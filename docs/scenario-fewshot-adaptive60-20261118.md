# Adaptive versus fixed few-shot: 60 new cases, 120 live attempts

The experiment provides no clear evidence that the adaptive process improves
content quality over the matched fixed-example branch. Application defaults and
source code were not changed. This report does not recommend activating a new
prompt version.

## Scope

- Run on 18 November 2026, 13:24-14:18 Asia/Bangkok (about 53m45s including
  orchestration pauses).
- 60 new inputs: 20 general communication, 20 TOEIC, 20 IELTS. Each input received
  one fixed and one dynamic attempt: exactly 120 original requests, no replays.
- Generation, review and repair used real AI; no live learner grading, paid
  judging, TTS or Core API lesson creation was performed.
- The preceding experiment was 30 inputs times two branches (60 attempts), not
  60 distinct inputs. Its artifacts were preserved.

Both branches used the same frozen engine at `6cb5773`, V5.2 base instructions
with the original EXAMPLES section removed, shared reference-example guidance,
schemas, Harness, temperature 0, configured provider pool, Gemini 3.5 Flash Lite
and Gemini 3.1 Flash Lite fallback. The fixed branch was a matched experimental
counterpart, not an untouched production V5.2 prompt.

Both had a cap of two examples and 6,000 UTF-8 bytes per stage call. Fixed IDs
were held constant within stage/mode/schema; dynamic selection considered the
case, level and actual repair diagnostics. Actual example counts differed.

The owner explicitly allowed adaptation throughout all 60 cases, with no held-out
confirmation set. The dynamic bank grew from 53 to 65 examples. Added references
used unrelated fictional facts rather than test names, numbers or answers.

## Results

| Metric | Fixed | Dynamic |
|---|---:|---:|
| Original attempts | 60 | 60 |
| Completed lessons | 39 (65.0%) | 38 (63.3%) |
| Provisional content PASS / all attempts | 18 (30.0%) | 15 (25.0%) |
| Provisional content PASS / completed | 18/39 (46.2%) | 15/38 (39.5%) |
| Completed content FAIL / UNCERTAIN | 20 / 1 | 21 / 2 |
| Provider HTTP errors | 15 | 16 |
| QuotaUnavailable | 0 | 1 |
| Harness QualityRejected | 4 | 5 |
| Runner AssertionError | 2 | 0 |
| Fallback events / requests with fallback | 25 / 24 | 24 / 24 |
| Repair calls | 20 | 31 |
| Reported patch attempts / accepted | 18 / 9 | 28 / 17 |
| Median attempt time, including errors | 27.21s | 29.70s |
| Observed p95 attempt time, including errors | 54.25s | 61.07s |
| Recorded input tokens | 1,075,925 | 1,304,560 |
| Recorded output tokens | 353,520 | 390,987 |
| Examples inserted across stage calls | 546 | 791 |

Of 29 inputs with both lessons completed, 10 were both PASS, 10 both FAIL,
4 dynamic PASS/fixed FAIL, 3 fixed PASS/dynamic FAIL, and 2 involved UNCERTAIN.
The 4-to-3 difference is inconclusive and is conditional on both runs completing.
The overall yields also do not establish that dynamic selection is worse.

Codex read all 77 completed lessons: source fidelity, questions, public answers,
scoring parts and Vietnamese translations. These unblinded provisional labels
are not expert-calibrated gold labels or measured learner-grading accuracy.
Uncompleted runs retain their own error categories rather than content FAIL.

Dynamic recorded input tokens were 21.2% higher, but step counts, errors and
example counts differed. This is neither a complete invoice nor a causal cost
estimate. Error timing also affects the latency figures. Accepted patches do not
establish whole-lesson semantic correctness.

## Failures and validation

Two fixed runs, `i09-urban-shade` and `i10-transcript-version`, failed in the
experimental selector: a `SparseQuestionPatch` exemplar was incompatible with
the requested `PartsQuestionPatch` schema. Selection was corrected to require
the exact schema and omit examples if no compatible one existed. An offline
regression covers both branches. Both original failures remain counted, without
reruns. These are runner failures, not provider failures.

Concurrency briefly increased from one pair to two pairs, then returned to one
pair after 429/503 errors. Admitted calls drained before resuming; provider
cooldowns and quotas were not reset, and accounts were not rotated.

Offline checks covered input/bank schemas, deterministic selection, provenance,
caps, mode/schema isolation, fragments and simulated pipelines. The actual
finding aggregator was exercised against added review examples.

The provenance audit passed for 810 recorded stage-call attempts: shared base
instructions, exact reconstructed example bytes and stable fixed IDs. Twenty
calls used no examples. A subsequent per-case export found that three of these
calls in `i09-urban-shade/dynamic` (`REVIEW_QUESTIONS`, `TRANSLATE`, `REVIEW`)
have the exact metadata structure of the selector's exception fallback, rather
than ordinary empty selection. Their recorded reason was `[REDACTED]`; the exact
exception was not retained. The previous audit compared only the unredacted
reason literal and incorrectly reported zero such fallbacks. The other 17 calls
have ordinary empty-selection metadata. Completion, content-review and example
insertion totals are unchanged. Stage attempts are not necessarily provider
HTTP request counts.

All 77 completed lessons passed compiled Core recovery/content functions. This
was direct function testing, not end-to-end Core API acceptance or registration
of an experimental release. Core acceptance missed semantic errors such as
turning an unconfirmed order into an unplaced order, attributing a reason to the
wrong speaker, or scoring a premise already supplied in a why-question.

No application source/defaults, saved lessons, limits, approved voice pairs or
authentication settings changed. No QA login, merge or production deployment
was performed.

## Evidence and decision

Private local evidence includes `plan.json`, all 120 original JSON/log pairs,
content reviews, paired lesson details, CSV, bank/policy/selector revisions,
adaptation notes, provenance audits and 270 file checksums. Raw runtime data and
credentials are deliberately excluded from Git. The local report is
`fewshot-adaptive60-20261118/report-final.md` under the private evaluation store.

- Engine backend SHA-256:
  `d8abdd35490b328e419216a9422b82cdd6e38aa666eeb4410f316124312df234`
- Base V5.2 SHA-256:
  `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`
- Plan SHA-256:
  `01e379dc0a45c88a8bef455ceb2195a924add7a6130953a3566f0ab115fd3a83`

Keep the existing default. The adaptive process showed isolated improvements
and more accepted patches, but no clear gain in reviewed lesson yield. Without
a frozen holdout, equal example exposure and calibrated review, these results
do not support a general superiority claim for dynamic few-shot selection.
