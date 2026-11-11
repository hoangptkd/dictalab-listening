# Conversation assessment staged fix plan — 2026-11-12

## Scope and acceptance policy

Keep product default V3 (`650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e`) active. V4.7 (`60b2c0ca9be856e3e5f7aa8f2f9626f305d7772e7908e0860cd126e7a3574868`) and historical prompt sources/hashes remain immutable. New candidates are experimental, explicitly pinned, and inactive until all gates pass. Semantic findings remain advisory (`qualityPassed=false` plus findings); malformed contracts fail closed.

The five frozen defects are: F1, a1/q1 requests only a reason but scores a preference supplied by the question; F2, a2/q3 requests benefits but also scores action/duration supplied by the question; F3, a1/m2 inflates “might fall behind” into certainty; F4, a3/m2 includes agreement in its parent but omits it from required parts; F5, a2/m2 hides two independently omittable benefits in one scoring part. Multi-detail questions remain allowed.

Use frozen original and corrected-positive `.input` values in ignored `data/conversation-meaning-assessment-20261106/consistency-projection-v3_2-v4_6/` and sanitized `services/ai-generation/tests/fixtures/scenario-independent-audit-regressions.json`. Keep expected labels separate from provider input. Historical evidence is in `docs/conversation-v4-independent-audit-implementation-plan-20261108.md`; do not rewrite historical results.

## Batch 0 — baseline and measurement preparation

1. Inspect task diff, repository instructions and relevant skills. Preserve unrelated edits. Baseline branch: `codex/fix-scenario-provider-503-20261106`; baseline commit: `9e040cf65a8afe44888a20f24c5ab7ff79a3dd35`.
2. Archive exact source/input/schema/model/config hashes under a new ignored evidence directory. Inspect actual V4.8 negative and V4.7 positive failures before changing rules.
3. Read relevant Langfuse prompt and judge calibration guidance; reuse installed SDK/tracing and measurement helpers. Do not migrate prompt management or add dependencies.
4. Before paid calls, verify provider registry, emergency stop and protected QA identity without printing credentials. Reuse Docker if responding; start Docker only through Explorer if needed. Do not start workers/backlog or Core merely to test a reviewer.
5. Set finite cap: batch 1 allows two full-map calls (original and positive), plus at most one independent repeat after successful outcomes. No automatic provider retry/fallback in comparisons. Record every actual POST, exact input/output/status, usage and hashes. A 503, unknown billed outcome or invalid output is not semantic success.

## Batch 1 — focused question coverage (F1/F2)

1. Review and complete partial `scenario_assessment_audit.py` and unregistered `scenario_prompts_meaning_v4_9.py`. Every supplied question/meaning/part needs exactly one request status, answer status and brief rationale. Reject omitted, duplicate, foreign and cross-activity IDs, blank rationales and malformed output through the bounded format retry.
2. Project only actual question/reference and enumerated required parts into the dedicated question audit. Exclude transcript, parent meaning, author `factEn`, prior verdicts and expected labels. Given referents can contextualize a short answer but cannot supply an independent scored fact. Preserve English, Vietnamese, mixed answers and valid multi-detail questions.
3. Freeze dedicated coverage verdicts into final normalization: final review may add failures, never upgrade coverage failures. Preserve all other quality flags.
4. Register only an explicit candidate pin across Python assessment dispatch, projections, model selection, validators and normalization; hash new schema/projection/derivation dependencies while keeping historical hashes stable. Update Java release policy/admission/envelope for only that pin. Retain seven normal calls, eleven maximum and existing deadline.
5. Runnable checks: candidate dispatch/schema/hash; projection whitelist; complete IDs/reasons; no-upgrade normalization; exact format-retry feedback; semantic repair rebuilds ledgers; deadline and eleven-call bound; no provider-error retry; historical pins; positive multi-detail cases.
6. Live gate: original F1 and F2 both detected with complete valid coverage; corrected positive accepted without false rejection. Invalid output counts as failure. If measured improvement fails, preserve receipts and remove unproven runtime registration/source. Record this checkpoint before batch 2.

## Batch 2 — source fidelity and certainty (F3)

Proceed only after batch 1 checkpoint. Inspect exact source/claim comparisons and reuse `ClaimCheck`, source support and exact activity evidence normalization. Focus source-vs-claim review on actor, negation, conditions, possibility, proposal and agreement, with brief grounded rationale before status. Quotes do not override unsupported/uncertain verdicts. Do not add keyword certainty heuristics. Version and explicitly pin changes; preserve prior evidence. Prefer existing stages and seven/eleven-call budget; update Python and Core protocol/admission before any new call. Check original F3 fails, corrected possibility passes and adjacent negation/conditional/quoted-speech examples. Recheck F1/F2 calibration. Mocked statuses alone do not prove semantic resolution.

## Batch 3 — parent/part completeness (F4/F5)

Inspect blind decomposition and positive false duplicate failures. Claims must retain shared actor/context; split independently recallable benefits/options rather than words in one proposition. Compare parent claims to parts through explicit coverage matrix: every parent claim covered, no invented part claim, duplicate scoring or hidden independently omittable claims. Agreement remains agreement and source grounded. Improve the failed decomposition/relation cause, reusing deterministic gap/duplicate/bundle normalization. Do not make every question atomic or add situation-specific rules. Check F4/F5 negatives and minimal positives plus multi-detail, paraphrase, timed single proposition, reason-only target, alternatives and shared-context benefits. Live gate: all five original defects detected, corrected positive fully accepted with complete contracts. Freeze reasons and costs. Stop unresolved after the finite experiment cap rather than accumulating speculative prompt versions.

## Batch 4 — scoped repair end to end

Validate finding → allowed targets → patch → fresh review in the existing harness. A question finding repairs only that question unless the target itself is invalid; meaning changes include dependent questions. Preserve transcript, spec, learning context and unrelated items byte/hash-identical. Retain failures on unchanged items and rebuild audits for changed artifacts. A patch cannot erase failure by dropping scoring parts, unjustifiably narrowing important source targets, changing evidence or reusing receipts for changed inputs. Preserve format retry chain, deadline, call accounting and unknown-provider semantics. Replay genuine frozen outputs where practical. Check fresh audit after patch, out-of-scope rejection, unresolved advisory result, corrupt-format fail closed and no replay after 503/timeout.

## Batch 5 — integrated verification and delivery

Run relevant Python contract/jobs/audit/assessment/parts/harness/v9/fallback/gateway suites and Java assessment/harness/engine/content/budget suites. Verify candidate pin persistence through reload and grade; keep grading/partial-credit aggregation unchanged. Verify English, Vietnamese, mixed, partial and uncertain cases; only run small live grade controls if grading inputs changed. No mass generation, SFT, UI or audio changes.

Any real local flow must reuse protected QA account 14, database `dictalab_personalized_local_20261102`, Core profile `local`, and `DICTALAB_LOCAL_QA_USER_ID=14`. Never print/copy credentials, weaken authentication/CSRF, reset quotas or revoke others’ sessions. Log out ephemeral test sessions.

Separate offline contract passes, live semantic passes, provider unavailable and not tested. Run UTF-8 checks, Python compilation and diff checks. Commit and push independently accepted task batches; verify exact remote SHA and follow Actions, fixing task-caused failures. Do not force push, merge main, deploy production, modify old sessions or activate a candidate without every semantic, positive, grading and integration gate.

## Stop conditions

Stop dependent work and report evidence if edits cannot be safely separated, QA identity changed, emergency stop is active, billed outcome is unknown, provider returns 503/429 without a safe approved retry plan, protocol/budget needs exceed the bounded design, evidence contradicts labels or finite calibration fails. Exhaust safe diagnostics and preserve receipts; never weaken a gate or claim semantics fixed from unit tests.

## Batch ledger

| Batch | State | Evidence/checks | Delivery |
| --- | --- | --- | --- |
| 0 | Complete | Source/input/schema/config hashes frozen; provider registry, emergency switch and QA 14 identity verified read only | Included in this checkpoint |
| 1 | Failed semantic gate; candidate removed | 61 experimental offline checks passed; 2 fresh POSTs, complete contracts; F1 detected, F2 missed; corrected positive passed | Evidence checkpoint only |
| 2 | Not executed | Dependent work stopped after batch 1 failed | No runtime change |
| 3 | Not executed | Dependent work stopped after batch 1 failed | No runtime change |
| 4 | Not executed | No accepted semantic candidate | No runtime change |
| 5 | Offline baseline checks complete | 257 Python and 127 Java tests passed; encoding, compilation and diff checks passed | This document is the only task delivery; no production deployment |

## Batch 1 checkpoint — failed acceptance gate

### What was tested

The partial scoped coverage design was completed as a temporary, explicitly pinned Python candidate, V4.9 hash `350eda1d6c5e14a19bd880188fe5dc84ddbfffd5226928f4eae9300432d88c24`. The stage received only actual question/reference text and the assigned required parts. Its contract required every question/meaning/part pair exactly once, a brief reason, request status and answer status. Final review normalization froze those statuses. Malformed final question IDs were validated before lookup so they followed bounded contract retry rather than raising an unhandled lookup error. The inherited prompt transformation was guarded against missing or repeated anchors.

The candidate audit suite passed 61 checks, including historical pins, seven-call dispatch, projection whitelist, missing/duplicate/foreign/cross-activity IDs, blank reasons, no-upgrade normalization, exact format feedback and provider-error non-replay. These were offline contract checks, not evidence of model comprehension. Java admission integration was deferred until the focused semantic gate; no Java candidate was registered.

The private read-only preflight confirmed the existing Gemini provider registry, enabled AI emergency control and protected QA account 14 identity. Docker infrastructure was already responding and was reused. No Core or queue worker was started, no account was created, and no browser login/session was used. The installed observability path recorded the calibration operations. No prompt-management migration or dependency update was made. The experimental protocol followed the distinction between structured contracts and semantic calibration described in [Langfuse's judge guidance](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge).

### Fresh live results

The finite cap was two full-map calls, with no retry, fallback or reused provider receipt. The optional independent repeat was not used because the required negative gate had already failed. Both actual POSTs used the configured `gemini-3.5-flash-lite` and returned HTTP 200.

| Input | Contract | Coverage | Semantic outcome | Duration | Reported tokens |
| --- | --- | --- | --- | --- | --- |
| Frozen original | Valid | 9 questions, 17 question/meaning/part checks | F1 detected; F2 missed | 7.270 s | 1,551 input + 1,553 output = 3,104 |
| Corrected positive | Valid | 9 questions, 16 question/meaning/part checks | Every part REQUESTED and COMPLETE; no false rejection | 7.143 s | 1,534 input + 1,261 output = 2,795 |
| Total | 2 actual POSTs | Complete maps | Batch 1 acceptance **failed** | 14.413 s | **5,899** |

F1 (`a1/q1/m1/p1`) was classified NOT_REQUESTED/INCOMPLETE, correctly excluding the preference from a reason-only answer. F2 (`a2/q3/m2/p1`) was incorrectly classified REQUESTED/COMPLETE. That question asks how the already-described first-fifteen-minutes reading solution benefits members and discussion. Its reference lists the two benefits. The scored part instead requires spending the first fifteen minutes reading quietly together in the room. The reviewer falsely attributed that action to the reference despite recording the reading/time context in `givenEn`. The isolated scope and complete output contract therefore did not fix this error class.

This is a semantic failure with a known successful provider response, not a quota problem, unavailable provider or contract-based detection. The positive result does not compensate for missing F2. No inference is made about F3/F4/F5 from this focused experiment.

### Saved evidence and resulting state

Private receipts remain under ignored `data/conversation-meaning-assessment-20261106/scoped-coverage-v4_9-20261112/`: `evaluation-started.json`, `frozen-inputs.json`, each `*.started.json` and `*.result.json`, `summary.json`, exact tested source copies, the experimental test file and `provenance-final.json`. They retain exact prompts, schemas, inputs, raw provider JSON, model, usage, statuses and source/config hashes. Historical evidence was not modified. Private receipts are excluded from the GitHub delivery.

Per the failed-gate policy, the unproven V4.9 prompt source, temporary Python registration, helper models/projection/normalization and experimental tests were removed from tracked runtime. The measured source remains in the ignored evidence archive. All tracked application/test files match the baseline commit. No Java protocol, migration, grade aggregation, session, UI or audio change remains. V3 stays the default and V4.7 stays inactive. F1–F5 remain unresolved in the shipped assessment reviewer; this checkpoint does not claim a delivered fix.

Batches 2–4 were not executed after the failed prerequisite. They require a newly justified calibration design and a fresh bounded experiment; additional paid calls or speculative prompt versions were not used in this run.

## Final verification and delivery scope

After removing the candidate, `git diff --quiet 9e040cf65a8afe44888a20f24c5ab7ff79a3dd35 -- services src` succeeded: application and test sources match the pre-task baseline.

| Check | Result |
| --- | --- |
| Python: `test_contract`, `test_jobs`, `test_scenario_assessment_audit`, `test_scenario_assessment`, `test_scenario_assessment_parts`, `test_scenario_harness`, `test_scenario_harness_v9`, `test_gemini_model_fallback`, `test_model_gateway` | 257 passed, 56.50 s; existing dependency deprecation warnings |
| Java: `ScenarioAssessmentTest`, `ScenarioHarnessTest`, `ScenarioEngineIntegrationTest`, `ScenarioContentTest`, `AiUsageBudgetIntegrationTest` | 127 passed, 0 failures/errors/skips; build success, 30.611 s |
| UTF-8 checker: this document and restored assessment/audit/harness/test files | Passed, five files; no encoding repair needed |
| `py_compile`: restored assessment/audit/harness modules | Passed |
| `git diff --check` | Passed |
| Default V3 / historical V4.7 pins | Existing frozen hash checks passed |

Java integration used the existing isolated H2 test profile. No new PostgreSQL migration, browser flow, live grading control or end-to-end candidate repair was tested. The 61 experimental checks and 257 restored-baseline checks are distinct runs; they must not be added together as semantic successes.

Delivery is a plan/evidence checkpoint on `codex/fix-scenario-provider-503-20261106`, with task-only commit/push and remote SHA verification reported with the final result. No completed assessment fix, candidate activation, main merge or production deployment is claimed. Private data and credentials are excluded from the commit. Both provider calls and both test processes finished before delivery; the pre-existing desktop helper was left running.
