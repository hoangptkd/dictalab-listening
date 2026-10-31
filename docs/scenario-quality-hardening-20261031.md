# Scenario EXTRACT / GENERATE quality hardening — 2026-10-31

## Decision and scope

The V2 candidate passes the current automatic suite and the eight-case live corpus.
This is **not production release approval or proof that all semantic defects are fixed**.
A subsequent agent reading found a missed requirement: the transport dialogue says the
next train will get the passenger there “on time”, but gives no concrete arrival time.
The generation and review instructions require an explicit arrival time. The same-model
reviewer incorrectly accepted this output. Retain this finding as a release concern;
do not reinterpret the automatic pass as independent semantic validation.

No training, new dependency, schema migration or production deployment is part of this work.
The running local processes were not restarted into V2 during this verification.

## Why repeated prompt edits were insufficient

The original workflow asked a model to generate dialogue, questions and evidence together.
Valid JSON and an exact quote did not prove that an answer followed from the quote, that a
question was atomic, or that a requested outcome was resolved. One broad reviewer could
agree with the generator's mistake. Prompt changes could also fix one case while breaking
another. The complete candidate history below preserves that regression evidence.

V2 moves mechanical invariants into server code and keeps model judgment for meaning.
These two kinds of checks are reported separately. A positive model flag is still fallible.

## Implemented flow

### EXTRACT: at most two model calls

1. Extract a specification from the Vietnamese input. Preserve roles, constraints and goals;
   show added assumptions separately. Character IDs are assigned by the server.
2. Allowed requests receive an extraction audit against exact quotes from the input.
   Every required audit flag must be true and issues empty. Unsafe requests stop after
   the first call with `allowed=false`; they never enter GENERATE.
3. The existing explicit learner confirmation remains. Draft preview separates constraints,
   assumptions and character roles so the learner can inspect the model's interpretation.

### GENERATE: six model calls for a successful session

1. Plan six learning points and generate only the three dialogue activities.
2. Translate speech using an object keyed by the exact server-owned activity/segment IDs.
   Reject extra/missing translations and altered literal digits or character names.
3. Generate two questions for each activity in three separate calls. Each call receives
   only that activity's bilingual speech and roles, plus earlier questions/answers to
   avoid retesting. It does not receive metadata names or another activity's transcript.
4. Models select segment IDs. The server copies their complete original English text as
   evidence. The answer must occur in a selected translation, or exactly match a speaker
   role. A semantic audit must still verify that the answer follows from the speech.
5. Review all six questions and the full session. Require separate flags for roles,
   assumptions, constraints, goal resolution, dialogue, safety and translations. Require
   question support, grounding, distinctness, atomicity and translation agreement.
   Every specification constraint needs an exact anchored witness; so does the goal.

Existing gates remain: activity/session word limits, known speakers, both speakers per
activity, unique IDs, complete translation coverage, anchored evidence and no substantive
answer disclosure. Duplicate normalized answer phrases fail. Legitimate yes/no polarity
is allowed without permitting substantive answers to leak into questions.

False/missing/uncertain audit flags, schema errors and invalid evidence reject the job.
There is no automatic repair, silent retry, paid replay or replacement of failed samples.
However, a false-positive model judgment can still pass; the arrival-time finding is an example.

## Compatibility and cost

- Public specification/content shapes, saved sessions and database schema remain unchanged.
- Core verifies the V2 call order and reserves two EXTRACT calls and six GENERATE calls.
  Unsafe EXTRACT actually uses one call; reservation is conservatively two.
- A complete allowed case costs eight model calls, versus four in the original flow.
  This increases latency and cost; no monetary estimate is inferred from call count.
- Existing quotas and deadlines remain enforced. GRADE behavior is unchanged.
- Core recognizes the exact original V1 pin when settling its original call sequence.
  New submissions require the approved current pin. Validation is not bypassed.
- Production logs contain correlated request ID, phase, gate and safe reason. Raw synthetic
  evaluation requests, responses and wire metadata remain opt-in and outside Git.

## Live evidence and complete candidate history

All runs use the same eight synthetic cases, six groups and three practice profiles.
Seven cases allow generation; the eighth is a request to solicit bank credentials and OTPs.
Each candidate follows source changes. No failed run or raw response was overwritten.
The table uses the current offline report's formal acceptance denominator, including failures.

| Run | Correct EXTRACT / 8 | Accepted GENERATE / 7 | Model calls | Automatic acceptance |
| --- | ---: | ---: | ---: | --- |
| Original baseline | 8 | 4 | 25 | No; accepted samples also had content defects |
| V2-01 | 8 | 2 | 35 | No |
| V2-02 | 8 | 6 | 43 | No |
| V2-03 | 8 | 4 | 37 | No |
| V2-04 | 8 | 5 | 55 | No |
| V2-05 | 7 | 3 | 43 | No |
| V2-06 | 8 | 7 | 57 | Yes, current automatic gates only |

V2-01 exposed length overflow and metadata-only answers. Later runs exposed ambiguous
rescheduling questions, copied evidence errors, unsupported answer wording, repeated facts,
translation coverage errors and invalid model-owned IDs. Those failures drove the server-owned
ID, evidence and translation changes. The original report remains historical evidence:
`docs/scenario-quality-evaluation-20261031.md`.

Final capture: `data/scenario-local-20261031/quality-candidate-v2-06`.
The capture finished at `2026-10-31T07:13:56.662095+00:00`.
Its manifest names the pre-change Git HEAD and separately hashes the exact dirty source
snapshots used for execution; it does not claim that HEAD already contained V2.

| Fingerprint | SHA-256 |
| --- | --- |
| Current prompt/schema pin | `c2e372c30375d843a0c0b6e6798cc5f66dffecbf3daf2c73d32416f9ff70665b` |
| Captured workflow source | `eb24c3e54c4b2dfa5926a0b198eabff8ba8928a9b4db407d943c95cb16f5cd23` |
| Captured harness source | `41b1b63c35ee7ba3b187d54dc8fe486c7b6f077e3d3546fafe7511e4caba60c0` |
| Normalized corpus | `d641b1b129a031cb7cbe3ec6123c0236dd25a7e9b72e82f9c5c5d7dc785dc377` |
| Program | `f5d8881001f17767cc87639adb92c6b389b7e462974f8c67a2bdb05fbcd7aab0` |
| Baseline integrity inventory | `053b5047d7c25ddf4bcbdef3c912fa649df43607bcb61f7bc8268c3f1fb5cff8` |

Model/provider: `gemini-3.5-flash-lite` / Gemini. Cloud outputs have no guaranteed deterministic
seed. Final success on this small corpus does not estimate a production success rate.

## Verification and repeatable commands

- Python service and evaluation suites: **499 passed, 2 skipped**.
- Scenario JavaScript suite: **8 passed**.
- Core content and engine integration tests: **25 passed, zero skipped**. This includes an
  opt-in test validating all real captured final outputs with the independent Java validators.
- Final capture automatic acceptance: **EXTRACT 8/8, GENERATE 7/7**, unsafe generation absent.
- Final exact replay: **15/15 phases with the same outcome, zero network/provider calls**.
- Surefire XML, report and replay results are saved in the final capture's `derived/` directory.

```powershell
python tools/ai-eval/scenario_quality.py report --capture data/scenario-local-20261031/quality-candidate-v2-06 --output data/scenario-local-20261031/quality-candidate-v2-06/derived/report-v2.json --require-pass
python tools/ai-eval/scenario_quality.py replay --capture data/scenario-local-20261031/quality-candidate-v2-06 --output data/scenario-local-20261031/quality-candidate-v2-06/derived/replay-v2.json
& "C:/Program Files/apache-maven-3.9.12/bin/mvn.cmd" --batch-mode --no-transfer-progress "-Dtest=ScenarioContentTest,ScenarioEngineIntegrationTest" "-Dscenario.quality.capture=data/scenario-local-20261031/quality-candidate-v2-06" test
```

Use a new derived filename each time. Reports and replay never regenerate data. Replay rejects
a changed prompt/schema/options fingerprint rather than falling back to a live model.

## Retention and next release checks

The restricted backup directory outside this disposable worktree is
`C:/Users/HP/Desktop/English_dictition/data/scenario-evaluation-20261031/`.
It retains the original baseline ZIP and a separate candidate archive with a receipt containing
ZIP SHA-256, CRC verification, every-file hashes and raw integrity verification for each run.
Credentials, protected environment files and real learner data are excluded.

Candidate archive: `quality-candidates-v2-01-through-06-20261031.zip`, **1,136 files**,
SHA-256 `a94503994e17262794724153085bf5960c5e1aa670178df92f2427ebdd425be1`.
Receipt: `quality-candidates-v2-01-through-06-receipt.json`. All six raw inventories,
ZIP CRC and every archived file hash were verified without provider calls.

Before production promotion, resolve the arrival-time finding and evaluate semantic false positives
with independent judgments on broader held-out scenarios. Also inspect conversational naturalness:
some Vietnamese translations remain literal, and the badminton sample uses “today” while discussing
Sunday. These observations are agent review notes, not human approval or rewritten model verdicts.
This work does not verify TTS/audio delivery, live browser learning flow, TOEIC/IELTS calibration,
multi-sample stability or production deployment.
