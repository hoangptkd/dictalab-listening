# Conversation: scene audio and meaning-based assessment

Status: approved direction; implementation specification. Architect: GPT-6 Astra. Coding: GPT-6.1 Sol / High. Baseline audio commit: `75ee3dde90b62da6b6a22925b6fdce637b239942`; task checkout also includes `8f3af02` (QA-account-only local quota exemption), preserved unchanged.

## 1. Goal and boundaries

Improve natural listening and collect more useful evidence of understanding. Do not equate two correct answers, or an arbitrary larger number of answers, with understanding 80% of a dialogue. Keep `/conversation`, three activities, existing personalization, Vietnamese/English answers and historical lessons. No chatbot, new certificate scoring, training, production deployment, or bulk paid benchmark in this change.

Two independent delivery gates:

1. Meaning assessment can be implemented and tested now.
2. Contextual scene audio requires a provider capability/fidelity experiment first. A concatenation of independent clips is NOT acceptance of this requirement. Do not silently substitute it or replace working audio until the experiment passes.

## 2. Current flow and evidence

| Area | Actual code and consequence |
|---|---|
| Generation | `services/ai-generation/app/scenario_workflow.py::execute`: dialogue first, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW; V9 uses `scenario_harness.py`. Keep advisory semantic reviews and bounded scoped repairs. |
| Fixed assessment | `scenario_workflow.py::{Activity,QuestionDraft,Grade,validate_questions,validate_content}` and `ScenarioContent::{content,grade}` require two questions per activity. `ScenarioEngine.answer` requires two answers. |
| Learner state | `ScenarioEngine::{publicActivity,answer,completeActivity,recommendations}` stores attempts and support snapshots; completion needs a successful grade. Recommendations use ordinal 1, not independent evidence per meaning. |
| Historical pin risk | `ScenarioEngine.payload` currently reads today's workflow pin. `retryGrade` correctly uses saved payload. New work must pin all admitted drafts and use historical pins for later phases. |
| Playback | `scenario.js::{streamAudio,playMedia}` prepares two clips concurrently and sequences separate audio sources. It fixes network waiting but does not give separate TTS calls shared prosody. Preserve its cancellation/idempotency/ACK protections. |
| TTS | `tts.py::synthesize` sends one transcript/voice to Cartesia `/tts/bytes`; `ScenarioEngine::{ttsPayload,ttsFingerprint,accept}` validates recipe and speaker mapping. |
| Storage | V49 stores private activity JSON, attempt/support/result JSON, durable operations and per-segment media. V50 adds personalization. Latest migration is V50. |
| Range playback reuse | `app.js::{playRecordedAudio,audioSegment,loadAudioBuffer}` and `listening.js` already implement timestamp-based playback. Reuse appropriate primitives; do not extract a new player framework. |

## 3. Findings and priority

- P0: Never change historical schema fingerprints or route old lessons into a new grader. Never expose private meanings, reference answers or quotes during unanswered stages.
- P1: Two fixed checks provide narrow evidence; counts alone do not measure total comprehension. Recap omissions mean unassessed, not misunderstood.
- P1: First attempt ordinal is inadequate once recap/checks exist. Deduplicate by meaning and preserve independent versus assisted results.
- P1: Current audio unit cannot preserve cross-speaker context. A faster playlist is not evidence of conversational naturalness.
- P2: Repeated grading/generation can increase cost and cognitive effort. Precompute a finite question bank, select deterministically and never loop until the learner passes.

## 4. Provider decision gate

Official sources checked during discovery:

- [Cartesia contexts](https://docs.cartesia.ai/use-the-api/tts-websocket/contexts) and [WebSocket schema](https://docs.cartesia.ai/api-reference/tts/websocket): voice remains fixed within a context. Separate speaker contexts do not provide documented shared dialogue context.
- [Gemini speech generation](https://ai.google.dev/gemini-api/docs/speech-generation): one-request, two-speaker generation exists. Native REST can use installed httpx.
- [Gemini generate-content](https://ai.google.dev/api/generate-content): generic output audio transcription/word timestamp fields exist; support by a selected TTS model is not established by the TTS guide.

Implement a bounded, explicitly invoked capability probe, not a new default production provider. It must require an explicitly configured TTS model/source and applicable quota envelope; do not reuse text-model quota accounting silently. Credentials come from protected environment/configuration, never arguments or reports. Save sanitized request/response metadata, WAV, hashes and validation report in ignored evidence storage. One call per candidate; no automatic replay after uncertain outcome.

Probe a short two-speaker script and a normal-length dialogue. Check model availability, supported timestamp response, PCM format, voice separation, transcript omissions/additions/order, segment coverage and natural transitions. Preserve the exact baseline clips for side-by-side listening; do not regenerate benchmarks on each inspection. Timestamp metadata alone is not proof that the waveform says the same text: listen to the candidate and record observed fidelity separately.

If the account/model is not configured, timestamps unsupported, or fidelity fails, report the exact gate as BLOCKED. Do not install an ASR stack, buy a provider, invent timestamps, or deploy an assembled-audio substitute. Those require a new Architect decision. Existing playback remains usable.

### Accepted audio contract, only after capability gate

- Unit: one complete activity scene, all ordered speaker turns in one request.
- Freeze provider/model/recipe and speaker mapping in session provenance.
- Artifact: one immutable 24 kHz PCM16 mono WAV, transcript hash, recipe hash, media hash, and ordered `{segmentId,speakerId,startSample,endSample}` ranges.
- Validate exact IDs/order, positive non-overlapping in-bounds sample ranges and complete transcript alignment. No duration/text-length interpolation. Store alignment/fidelity method; do not label model transcription an independent audit.
- Play-all uses one source without source replacement at turn boundaries. Segment/evidence replay seeks/ranges the same master. Pause/resume/seek/stop retain actual source position. No resynthesis for replay.
- Separate the master cache namespace from old segments. Historical playback keeps its old path. READY acknowledgement cannot trigger synthesis; UNKNOWN is not auto-retried.
- Current 3 MB WAV cap is about 62.5 s; master bounds require coordinated changes to Python validation, Java `AiPlatformClient.audio`, result/HTTP limits and operation deadlines. Do not simply disable size limits.
- Completion gate: real cold/warm playback, mobile/desktop, switch/pause/seek/leave, exact replay ranges, duplicate request race, partial provider response, no transcript drift, and blind listening preference versus baseline. No performance-only claim of naturalness.

## 5. Meaning assessment contract (frozen for implementation)

### Versioning

Add `scenario-job.v3`, new `scenario-meaning-assessment.v1` policy and a separate workflow manifest/resource `scenario/workflow-v3.sha256`. Preserve v1/v2 schema classes, prompt modules, manifests and hashes byte-for-byte in behavior. Add new program definitions: general communication 4, TOEIC 3, IELTS 3; keep historical catalogs. New definitions remain DRAFT/local-preview until evaluated.

New learning context uses `scenario-learning-context.v2` and the same existing fields except its profile permits up to 18 questions/session. Existing context-v1/profile literal 6 remains unchanged. The change is explicitly guided practice, not official exam-format simulation or band calibration. Do not label existing free-response questions as official TOEIC/IELTS items.

New drafts store `{contractVersion,promptHash,assessmentPolicyVersion}` in nullable `workflow_snapshot_json`. Old rows recover the pin from their original successful EXTRACT or GENERATE operation; no silent fallback to today's pin when provenance is missing. Historical retries retain their exact saved payloads.

### Private content

Keep each activity's `id`, `segments`, `questions`; add `assessment`:

- `policyVersion`: `scenario-meaning-assessment.v1`.
- `meanings`: 3–6 `{id,meaningEn,skill,supportKind,inferenceReasonEn,evidence}` items; IDs m1…m6. Important, non-duplicate meanings derived AFTER dialogue, not a plan imposed on dialogue. Respect profile skills/inference policy and proposal/agreement distinctions.
- `questions`: 3–6 existing-style question objects, q1…q6, each adds `meaningIds` (1–3 distinct references). Multiple-detail questions remain legal. Every meaning must be covered by at least one question. No invented padding: insufficient grounded meanings is a structural generation shortfall.
- All meaning and question evidence uses exact quotes in this activity's transcript. A question's quote may include a meaning's quote within a longer exact quote from the same segment; do not reject valid proof merely because quote boundaries differ. Retain question-specific expected answer, source grounding and supported inference.
- 3 activities and 2–6 segments stay unchanged. Word length remains guidance, not a restored rejection gate.

Generate map and finite question bank together in QUESTIONS. No new generation call when a learner needs follow-up. Keep existing five-stage generation envelope, bounded repair allowance, provider fallback classification, deadlines, prompt hashes, redaction and trace linkage. New schemas/validators are separate; do not weaken old harness gates to accept new shapes.

### Learner submission and grade

Add `POST /sessions/{s}/activities/{a}/assessment`, leaving `/answers` unchanged for v1/v2.

Request: `{expectedVersion,mode,recapText,answers}`. `mode` is `RECAP`, `SKIP_RECAP` or `CHECKS`; recapText is empty except RECAP; answers is empty except CHECKS and otherwise uses `{questionId,textVi}`. Server validates exact currently admitted question IDs, text length, version, ownership and idempotency. SKIP_RECAP is a durable no-provider transition.

New GRADE payload adds `assessmentSubmission` with mode, recapText, answers and admitted `meaningIds`; retains private activity/context. Return `{meanings:[{meaningId,status,evidence:[{responseId,quote}]}]}`. `responseId` is `recap` or a submitted question ID. Status: CAPTURED/PARTIAL/INCORRECT/MISSED/UNCERTAIN/UNASSESSED. Require an exact result-ID set matching admitted meanings, unique IDs and verbatim learner quotes. CAPTURED/PARTIAL/INCORRECT need nonempty learner evidence. Omission in recap is UNASSESSED; empty answer to an explicitly asked check may be MISSED. Contradictory or ambiguous evidence is not CAPTURED. Do not grade writing/grammar in place of comprehension.

### Deterministic progression

Persist nullable activity `assessment_state_json` (recapSkipped, checksRound and pendingQuestionIds). Derive scored coverage from saved graded attempts; do not let a client submit or select the private map.

1. Initial stage RECAP, no question prompts exposed. Optional short recap vi/en; explicit skip. Explain optionality/scaffolding at lower levels.
2. Select initial checks after recap/skip: prefer questions covering as-yet-unassessed meanings, then partial/uncertain/incorrect meanings. Greedy maximum new coverage, stable question-order tie-break. Select up to four; target at least two when two non-redundant checks exist. Do not ask already demonstrated meanings solely to fill a quota. If all meanings are independently captured, permit completion without artificial repeat checks.
3. First check batch results stay hidden while extra independent checks are offered. One further batch, at most two previously unasked checks covering unresolved meanings; no on-demand question generation. Pending selection persists across reload.
4. No remaining useful checks, or final batch graded: stage DONE. Permit completion with unresolved/unassessed meanings explicitly retained. Also permit learner to finish after one graded batch without forcing exhaustive questioning; final review then ends independent assessment. No pass/fail threshold blocks completion.
5. Maximum three learner GRADE submissions (recap + initial + one follow-up) fits the existing ordinal constraint. Provider retry of a FAILED grade uses the same attempt, not a new learner submission. UNKNOWN never auto-retries. SKIP_RECAP uses no attempt.

Feedback secrecy: do not return per-meaning result, reference answers/quotes or corrective feedback before DONE/completion. Return neutral progress counts only while further independent checks are available. Public activity exposes `assessment:{policyVersion,stage,round,canComplete,recapAllowed,coverage}` and only currently admitted public questions. At DONE coverage may include meaning IDs, display labels and status; reference answers/quotes remain gated by completion or existing transcript disclosure policy. Evidence disclosed by hints/transcript makes later submissions assisted.

### Coverage projection

For each meaning keep first assessable independent result and latest learning result. UNASSESSED does not occupy first evidence; UNCERTAIN remains uncertain rather than becoming a weakness. Never overwrite first independent evidence with a later correction. Support snapshots retain transcriptOpened, hintOpened, plays and add feedbackExposed, mode and selected question IDs. A later batch without disclosed corrective feedback is not automatically assisted just because its ordinal exceeds one. Replays are recorded separately; play count is not proof of hearing/comprehension.

Summaries: observed understood / partial / incorrect / missed response / uncertain / unassessed, with independent and assisted evidence separated. Keep MISSED separate from INCORRECT: a missing explicit answer is not proof of a misconception. No percentage-understood claim, certificate score or fake mastery. Recommendations count each independently assessed meaning once; do not treat unassessed/uncertain as demonstrated weakness.

## 6. Ordered execution tasks and ownership

| Task | Owner / files | Required logic and checks |
|---|---|---|
| A: contracts and AI | Sol High worker Python; `app/scenario_assessment.py` and new prompt release, `scenario_workflow.py`, `scenario_harness.py`, `jobs.py`, Python tests | Isolated v3 dispatch/models, strict groundings, variable map/check sets, grade evidence, immutable old hashes. Use existing gateway/observability. Publish exact new hash to Core owner. Old fixture hashes must remain identical. |
| B: Core state | Sol High worker Core; `ScenarioEngine`, `ScenarioContent`, `ScenarioLearningController`, `ScenarioRelease`, personalization/program registry, Java tests, resources and V51 | Add nullable snapshot/state columns only; no destructive backfill. Pin historical payloads. Validate v3 independent of v2; server-select checks, suppress leaks, preserve receipts/CSRF/ownership/quotas. Add new DRAFT program catalogs/pin after A. Own OpenAPI update. |
| C: learner UX | Sol High worker UI; `scenario.js`, scoped `scenario.css`, existing JS tests | Optional recap → admitted checks → optional follow-up → honest coverage/review. Draft keys include stage/round; refresh restores server state. Reuse audio and retry protections unchanged pending audio capability gate. English/Vietnamese input, keyboard/focus/live status, mobile layout. Never show private answers early. |
| D: audio feasibility | Sol High worker after a slot frees; bounded probe script/tests and evidence README only | Validate explicitly configured native two-speaker TTS and timestamps. No default change or extra SDK. If blocked, preserve working playback and report, rather than inventing a substitute. Only after PASS does Architect authorize/assign the master-audio runtime work described above. |
| E: integration | Architect supervision; workers fix owned files | Full Python/JS/Java regression, migration/history tests, local QA14 actual flow and saved screenshots. No unrelated changes. Review diff; task-only commit/push current branch, remote verify and follow triggered CI. |

Workers are not alone. Do not revert another worker's changes. A/B/C coordinate through this frozen contract; conflicting requirements come to Architect before changing it. No independent commits while integration is incomplete.

## 7. Verification and rollout

- Python: old prompt hash snapshots, v1/v2 parsing/execution, v3 success and adversarial boundary fixtures, missing/duplicate/foreign meaning IDs, source quotes outside activity, invalid inference, ungrounded learner evidence, partial/multi-detail answers, omitted recap, full recap, contradictory answer, advisory reviewer failure, bounded repairs and UNKNOWN/no-replay.
- Java: `ScenarioContentTest`, `ScenarioEngineIntegrationTest`, migration/release/harness tests; selected subset enforcement, skip idempotency, stale versions, cross-user access, no answers in pending projection, feedback exposure, completion unresolved, missing historical pin rejection, old retries, no extra charge for replay/skip.
- JS: existing scenario tests plus recap/skip/refresh, dynamic 1–4 check batches, persisted follow-up, error recovery, learner drafts, no private leaks, stop/leave/account change and all existing audio races.
- Real local run: verify profile local, database `dictalab_personalized_local_20261102`, QA user14 and protected credential mapping. Keep auth/CSRF/ownership/provider quotas and emergency stop. Do not globally disable AI/TTS budgets. Log out only the test session when done.
- At least one new lesson through recap/checks/follow-up/completion and one old lesson through its unchanged path; also test recap skip and partial understanding. Save sanitized payloads/results, model/prompt hashes, latency, call counts, assistance history and screenshots. Distinguish mocked contract tests from paid/model-quality evidence.
- Migration: PostgreSQL additive nullable fields, no data rewrite/backfill requested. Table sizes, production load and exact server/Flyway versions must be checked before production; do not promise lock-free DDL. Local test upgrade first. Rollback disables new admission; already admitted jobs still settle and historical readers remain available.
- Do not activate new program/prompt globally based only on unit tests. Local preview can run after cross-layer validation. No 20/30/50-case benchmark unless separately requested.

## 8. Escalation

Stop the affected task and report to Architect for: unsupported TTS source/model or alignment; new paid provider/dependency requirement; historical fingerprint drift; ambiguous workflow provenance; unavoidable answer leak; schema/program incompatibility; requests to remove validation/security; unexpected private-data exposure; failed migrations; uncertain billable provider result. Continue independent safe tasks. Never silently relax a gate to produce a successful demo.

## 9. Delivery record

Implementation delegated to GPT-6.1 Sol / High. Assessment transport/state/UI implementation is complete for local preview; semantic grading quality and contextual audio are not approved for general release.

Runtime preflight (read-only, 2026-11-06): local Core is running with profile `local`, `DICTALAB_LOCAL_QA_USER_ID=14`, and no global AI budget disablement. AI API/worker are alive. Cartesia `sonic-3.6` is configured; **no enabled Gemini TTS source/model is configured**. Therefore task D is blocked before any billable request. No new provider/key/quota configuration was invented. The existing audio implementation is retained; the contextual master-audio requirement remains open. A probe script should only be added when there is an executable configured candidate, avoiding unused provider infrastructure.

### Delivered assessment behavior

- Isolated v3 contracts, 3–6 meanings and questions per activity, server-selected checks, optional recap/skip, bounded follow-up, learner evidence, independent/assisted projection and completion with unresolved meanings.
- Historical workflow pins recovered from saved operations. New drafts persist workflow snapshots. Old prompt contracts and admitted jobs remain readable; no current-pin fallback.
- Nullable V51 migration applied and validated on the isolated PostgreSQL 18.6 QA database after a native dump. No row backfill or production migration performed.
- General communication 4, TOEIC 3 and IELTS 3 remain DRAFT/local preview. No certificate calibration or percentage-understood claim.
- Existing tracing records prompt/schema versions, model, usage, artifacts and validation. Numeric provider HTTP status is retained without response-body logging.

### Checks and real local evidence

Automated checks completed in the coding/integration runs: 377 related Python tests; 418 JavaScript tests; 160 Java scenario tests with zero failures/errors and one skipped PostgreSQL container test. Later corrections passed 91 targeted Python tests, 62 assessment tests, 98 targeted Java tests and the three-release hash/version matrix. These are overlapping runs, not a summed unique-test total. The actual local PostgreSQL migration/validation supplies additional evidence beyond the skipped container check.

QA account 14 completed session `56634eb2-f6c1-4ecf-afb3-928a36697a19` through the real browser: EXTRACT, GENERATE, three activities and four grading submissions. The generated lesson had four meanings/questions per activity; the harness used six calls including one scoped question repair. Tested Vietnamese recap, English answers, blank/wrong answers, recap skip, transcript-assisted evidence, draft restoration after reload, unresolved completion and final review. Three activity-1 segments played sequentially. This tests integration; it is not evidence of improved human learning or natural scene prosody. The finite bank did not require a useful follow-up batch in this lesson; follow-up selection is covered by contract tests.

An old completed lesson (`4304b646-42e9-4abc-8228-5fd51cd436d0`) remained readable/reviewable after restart, and its cached audio played. Mobile 390 px had no horizontal overflow. The mobile review axe audit found zero violations and 39 passes. The desktop completion audit found duplicate landmark names across activity summaries; adding each activity number fixed them, with 65 scenario JavaScript tests passing. Final desktop audit: zero violations, 41 passes. One contrast rule remains incomplete because of gradient backgrounds; contrast was not fully certified.

Private evidence, outside Git: `data/conversation-meaning-assessment-20261106/`, including screenshots, native database dump, session artifacts, Langfuse observations, schema probe and saved grading comparisons. Credentials are not included in task source or reports.

### Integration failures and release lineage

1. Runtime approval-map keys with colons required bracketed Spring map syntax. Corrected private launch configuration; preserved authentication, CSRF, provider quotas and QA14-only budget exemption.
2. The first local v3 generation (`1f1c2152-1e46-4a06-be4a-0b7a1ccec872`) stopped at QUESTIONS and remains UNKNOWN. A distinct synthetic compatibility probe reproduced Gemini HTTP 400 `INVALID_ARGUMENT` with the nested bounded-array schema. Removing array bounds only from the provider wire schema allowed a different lesson to finish; strict Pydantic/Core count/evidence checks remain. The UNKNOWN job was never replayed or relabelled successful.
3. Real grading revealed false full credit for incomplete meanings. A versioned prompt correction added concise contrastive examples and full-meaning coverage guidance. Original outputs and admitted releases remain unchanged.

| Meaning prompt release | Workflow hash | Purpose |
|---|---|---|
| V1 | `ff50fd266eb277d973b8daed92dbf5b7ccb1beb17c28ad6c353fcaaf86c58630` | Original admitted schema; retained for historical jobs |
| V2 | `4693e3c73b820a6eaea53311eaa5d8beaa7269cb9a71089fca6af98c112d31eb` | Gemini wire compatibility; real completed QA lesson |
| V3 | `650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e` | Current local-preview admission; targeted completeness guidance |

These are meaning-assessment release names, distinct from earlier application-wide prompt V4/V5/V6 experiments. Harness policy/hash remain unchanged. Core accepts only each exact hash/version pair.

### Quality gate: not passed

The same three saved grading inputs were tested once per configured model, with no automatic replay:

| Focused case | Gemini 3.5 Flash Lite, meaning V3 | Gemini 3.1 Flash Lite, meaning V3 |
|---|---|---|
| Recap states preference but omits its reason | Correct PARTIAL | Rejected: omissions labelled MISSED in a recap; also over-credited preference |
| Check gives only one of two required details; another answer blank | Incorrect CAPTURED for partial answer; blank correctly MISSED | Correct PARTIAL / MISSED |
| Complete English recap | All required meanings CAPTURED | All required meanings CAPTURED |

Each model passed two of three focused cases. This is a small diagnostic, not an accuracy estimate or proof of calibration. Therefore no grading-model switch or general activation was made. Literal evidence validation rejects fabricated/foreign quotes and illegal omission statuses, but cannot prove semantic completeness of a paraphrase. The current preview still has a known false-full-credit risk for multi-detail meanings. Question review also accepted some mismatches between question scope and the full referenced meaning in the V2 lesson; V3 guidance addresses this but has not passed a new end-to-end content benchmark.

Next quality decision: evaluate a versioned required-part representation within each meaning and derive the aggregate grade on the server, preserving multi-detail questions. Compare it on these saved failures plus distinct held-out paraphrases before release. This requires a contract/design decision; do not use keyword matching, require atomic questions, suppress all CAPTURED results, weaken validation or keep adding unmeasured prompt rules.

### Delivery status

Delivery scope: task branch `codex/fix-scenario-provider-503-20261106`; exact commit and remote verification are reported with the final handoff. Production deployment is not authorized in this task. Contextual audio remains blocked on an explicitly configured provider/model with verified two-speaker fidelity and real alignment; grading quality remains a separate open release gate. Do not interpret a branch push or passing contract tests as production approval.

## 10. Required-part candidate implementation — 2026-11-07

User authorized continuing implementation after the delivery above. Implemented the next quality decision as an isolated **meaning V4 candidate**, policy `scenario-meaning-assessment.v2`, hash `b9be440e03b9b31163f02c75e685092277fb42fb1ecc0156d871d030efa94503`.

### Contract and behavior

- Keep the existing job v3/context v2 envelopes. Each new meaning adds 1–4 `requiredParts`, with contiguous IDs p1..pN, a meaning statement and exact source evidence contained within that meaning's evidence. Questions can still ask multiple details; no atomic-question restriction was introduced.
- QUESTIONS creates the parts with the meaning map, after dialogue completion. Existing scoped meaning repair replaces the complete meaning, including its parts, and cannot edit untargeted items or the dialogue. REVIEW_QUESTIONS also evaluates completeness, importance and grounding of the parts. Structural validators in both services check bounds, IDs, duplicate labels and exact evidence provenance; semantic equivalence still needs evaluation.
- GRADE returns only part IDs, statuses and learner evidence. A model-supplied whole-meaning status is a schema error. Part statuses are CAPTURED/PARTIAL/INCORRECT/UNCERTAIN/ABSENT. Every non-ABSENT part requires an exact quote from an eligible learner response. No second grading call was added.
- Worker derives the whole status: all CAPTURED → CAPTURED; otherwise any UNCERTAIN → UNCERTAIN; otherwise any CAPTURED/PARTIAL → PARTIAL; otherwise any INCORRECT → INCORRECT; otherwise recap → UNASSESSED, checks → MISSED. The result retains part evidence and its deduplicated aggregate. Core independently recomputes the status and verifies the full evidence union before persistence.
- Public results, finite question selection, assisted/independent evidence, answer withholding and unresolved completion remain unchanged. Core projects the saved activity's actual policy. New and old policies cannot be crossed under a different prompt pin.
- Existing meaning V1/V2/V3 schemas and hashes remain unchanged. No saved lesson, admitted operation, catalog definition or database migration was changed. Langfuse now obtains the assessment policy from each request's exact pinned release, including historical requests.

### Measured evidence and activation decision

One-shot Gemini 3.5 Flash Lite diagnostics reused the previous saved dialogue and learner submissions. Required parts for the grading diagnostic were human-authored from the frozen target meanings; they were **not** supplied by a successfully validated new generation. This isolates grading from map-generation quality.

| Diagnostic | Meaning V3 | Meaning V4 candidate |
|---|---|---|
| Same three saved grading cases | 2/3 | 3/3 |
| Five additional answer contrasts on the same transcript | Not measured | 4/5 |
| Total V4 focused grading cases | — | 7/8 |
| New QUESTIONS map from the saved three-activity dialogue | Not rerun | Rejected: activity 3 had only two meanings and two questions; contract requires at least three |

The remaining grading mismatch: recap “They mentioned some books.” was expected to leave all four meanings UNASSESSED; the model marked the book-selection meaning PARTIAL. The original false-full-credit case now correctly returns PARTIAL when only the reading-duration consequence is recalled. Correct Vietnamese paraphrase, a contradictory answer and an empty explicit answer also matched expected statuses. These are small diagnostics on one transcript, not an accuracy estimate or proof of learning improvement.

No failed provider call was replayed, no schema bound/reviewer gate was weakened and no grading model was switched. Preserve every raw result and expectation under ignored `data/conversation-meaning-assessment-20261106/required-parts-v4/` (`questions.json`, `grades.json`, one-shot markers and the isolated job ledger). Existing Langfuse tracing captures the candidate hash, provider/model, raw part evidence and derivation gate.

Final related checks: 300 Python tests passed (assessment, workflow, jobs, observability and contracts); 91 Java tests passed (9 assessment and 82 engine integration), with zero failures/errors/skips in those Java runs. Coverage includes mixed-policy rejection, the four-release hash/version matrix, part-boundary/evidence tampering, all status pairs, scoped repairs, persisted checks/completion and historical pin retention after admission changes. UTF-8 validation passed for all 13 changed files; diff whitespace checks passed. No frontend source changed, so no new browser/visual claim is made. The local Core/API/worker from the prior turn were no longer running after shutdown; diagnostics used the protected existing registry and isolated evaluation ledger, without restarting or changing product admission.

**Do not promote V4.** The product admission resource `workflow-v3.sha256` and default Python release remain meaning V3. Candidate V4 is registered for exact-pin worker evaluation and independent Core validation; it is not a default user-facing change. The next release gate needs a valid automatically generated map, end-to-end grading on that map and held-out semantic checks. The scarcity in activity 3 and the vague-topic over-credit must remain visible; do not pad with trivia or fix the test labels to obtain a pass. Contextual audio remains blocked as recorded above.

## 11. V4 continuation and measured release decision — 2026-11-07

### Changes delivered for evaluation

- V4.1 distinguishes a topic mention from a substantive claim. Merely saying that people discussed books is ABSENT for the required parts, producing UNASSESSED for a recap. It also separates the number of meanings/questions from the number of parts.
- V4.3 keeps V4.1 grading and replaces the question instructions with a shorter contract and one example aligning a train's departure time/platform with both the question and reference answer. Review independently derives the actual requested meaning and answer units. No additional review schema was introduced.
- V4.4 retains these instructions and restores **only minItems=3 for meanings and questions** in the Gemini wire schema. A real request compiled and returned three of each in all activities. Nested upper bounds remain absent on the wire; all local and Core bounds remain enforced. Earlier releases still receive their original wire schemas.
- V4.4 also collects one detected structural error per affected meaning/question before its single scoped repair. Previously, validation stopped at the first item: a repair fixed a missing quotation but then failed on a different item's unsupported skill. The new path exposes all detected item scopes together, preserves the dialogue and untargeted items, and still rejects a patch with any remaining structural error. Budget, deadline, call order and review policy are unchanged. Earlier releases retain their first-error behavior.
- Question repair explicitly requires every referenced meaning's source quotation, including contextual quotations. Part grading and deterministic aggregation are unchanged from V4.1. Historical hashes, saved jobs/lessons, program/context hashes and default V3 admission remain intact.

| Registered meaning release | Exact workflow hash |
|---|---|
| V4.1 | `ce0a72c6c7616ebfaab979bf3404379d5534ca9e21b4fbf8e4344a0bcea02714` |
| V4.3 | `139a7d7789923e10af8412d5f23f6fec7577116e4d1c97eb6ce907230a56f0aa` |
| V4.4 candidate | `09b222b0c140c0a3b07164639ff020cb272779319e6f79758eac55b058ce13bc` |

The V4.4 fingerprint includes both its wire schema and `all-localized-items.v1` structural-findings behavior. Core accepts only exact hash/version pairs. Intermediate experiment sources/results are retained privately; they were not admitted as product jobs. The abandoned V4.2 per-part reviewer schema was removed: it increased output size but still accepted visible defects.

### Real-model evidence

All diagnostics reused the same saved three-activity library-club transcript. No dialogue regeneration, training, dataset change or automatic replay of an uncertain request occurred. Expectations were saved before grading calls. These are small diagnostics on one transcript, not an accuracy estimate or a generalization benchmark.

| Measurement | Result |
|---|---|
| V4.1 grading on human-authored required parts | 8/8 expected outcomes; original V4 was 7/8 |
| V4.1 generated question map | 3/3/3 meanings/questions; reviewer passed, manual scope defects remained |
| V4.3 generated question map | 2/2/2; rejected before review |
| Minimal wire-bound experiment | 3/3/3; compiled without the previous HTTP 400, then rejected for missing source evidence |
| V4.4 initial scoped repair | Fixed the first item's evidence, then rejected on another item's unsupported skill |
| V4.4 collected-scope continuation | Reused the completed QUESTIONS response; one repair plus question review, translation and translation review completed with PASS |
| V4.4 grading on the **actual automatically generated map** | 8/8 expected outcomes: complete/partial/blank/bilingual answers, topic-only recap and two incomplete reference answers |
| Gemini 3.1 Flash Lite reviewer comparison | HTTP 503; no completed review or quality conclusion; not automatically replayed |

The collected-scope run is a frozen-artifact harness continuation, **not a fresh end-to-end browser lesson**. The original rejected runs remain rejected. Its successful structural/reviewer flags do not establish semantic quality.

### Remaining defects: activation is blocked

Manual inspection found at least two reviewer false acceptances:

1. **a1/q1** asks why Lan prefers short stories, supplying the preference in its wording. Its reference gives only the reason, while m1 requires both the preference and reason. Grading that reference correctly returns PARTIAL.
2. **a2/q3** supplies the first-fifteen-minutes reading activity and asks only its benefits. The reference omits the required action/duration. Grading it also correctly returns PARTIAL.

Additional map issues remain: a1/m2 changes the source's possible consequence into a definite causal statement; a3/m2 mentions agreement but its parts omit that component; a2/m2 still groups two independently omittable benefits inside one part. The reviewer reported PASS despite these defects. Therefore this continuation demonstrates better structural recovery and the intended grading behavior, **not a releasable question generator**. A student must not be penalized for fully answering a narrower question generated by the application.

Keep product admission on meaning V3; do not advertise V4 as active or deploy it. The next quality gate must resolve the saved question/target/reference scope defects and validate reviewer detection against these saved negatives before another activation decision. Do not weaken counts, evidence checks, scoring or expected labels to obtain a pass. No further prompt expansion is justified by the current evidence alone.

### Checks, evidence and delivery scope

- 304 related Python tests passed; 94 Java tests passed (9 assessment, 85 engine integration), zero Java failures/errors/skips. Tests cover release pairing, old pins, candidate-only wire bounds, multiple repair scopes in one bounded pass, evidence/part tampering, grading aggregation and persisted completion. Existing Python 3.14/dependency deprecation warnings remain.
- UTF-8 validation passed for all 10 changed files; diff-whitespace checks passed. No frontend source changed or new browser/audio-quality claim is made.
- Private evidence remains outside Git under `data/conversation-meaning-assessment-20261106/`: `required-parts-v4_1/`, `required-parts-v4_3/`, `required-parts-v4-wire-minimum/`, `required-parts-v4_4/`, `required-parts-v4_4-final/` and `required-parts-v4_4-collected/`. The last folder contains `harness.json`, `manual-quality-review.json`, `actual-map-grades.json`, isolated job ledger, pre-call expectations and the reviewer-comparison HTTP status. Existing Langfuse traces retain inputs, outputs and pinned metadata; the preliminary wire-only probe is explicitly experimental and its exact schema is retained with the continuation.
- Product Core/API/worker were not restarted and no production deployment was performed. The user's shutdown request was cancelled; do not shut down the computer.

## 12. V3 grader and V4 consistency candidates — 2026-11-07

Added two immutable candidates for controlled evaluation. Meaning V3.1 changes only the V3-schema
grader's substantive-claim/topic-only guidance. Meaning V4.5 shares a compact source/parent/parts/
question/reference consistency contract across question generation, review and repair. It retains
V4.4's wire minimums and collected structural errors, V4.1's grader, and deterministic aggregation.
Core registers only the exact version/hash pairs below. Default Python admission and the Core
`workflow-v3.sha256` resource remain meaning V3. No historical lesson, pin, grade or schema was changed.

| Candidate | Exact workflow hash |
|---|---|
| V3.1 | `6ee9928abf107c83121a0f80a6fb584c0cc5c8b49445c305292ba01be5800a76` |
| V4.5 | `2307e4d21648e6150016e5647ec3b5fbcbb60a5aaa3d6e787bc0cd44a602d5e3` |

The existing `meaningIds` schema requires the question and complete reference answer to elicit the
whole referenced meaning. Narrowing/splitting a target or broadening its question is valid when
grounded in the transcript. Crediting an unexpressed component is not. No required-parts retrofit,
per-part question mapping, history migration or regrading was introduced. Historical V3 lessons
with narrower questions retain that known limitation.

### Controlled model evidence and rejection

All calls used the configured Gemini 3.5 Flash Lite registry, unchanged authorization, provider
quotas and emergency stop. Expectations were written before each call. Saved inputs/dialogue were
reused; the corrected map is a private evaluation artifact, not a rewritten stored lesson or a
new automatically generated map. Its changes preserve proposal/options and uncertainty, split the
two benefits, ask for the actual target information, and use three distinct practical targets in
activity 3 instead of its former praise target.

| Diagnostic | Result |
|---|---|
| Identical 10-case V3 comparison, current | 6 expected outcomes; 4 semantic mismatches |
| Identical 10-case V3.1 comparison | 4 expected; 3 semantic mismatches; 1 validator rejection; 2 HTTP 503 gaps |
| V3.1 topic-only recap | Correctly UNASSESSED; current V3 gave PARTIAL to one meaning |
| V3.1 topic-only explicit check | Incorrectly PARTIAL, expected MISSED; current V3 also failed |
| V3.1 blank explicit check | False CAPTURED with copied reference text; exact learner-evidence validator rejected it |
| V4.5 replay of eight saved grading cases | 6 expected outcomes; 2 HTTP 503 gaps |
| Every corrected reference answer | 8 CAPTURED; 1 HTTP 503 gap; no completed semantic mismatch |
| Independently worded complete English answer | CAPTURED |
| Omit arrangement, confidence benefit, or discussion benefit | 3/3 PARTIAL |
| Corrected-map topic-only recaps | 2 UNASSESSED; 1 HTTP 503 gap |
| V4.5 reviewer, exact saved negative content/draft/context | Detected 0/5 required defect classes; falsely passed all five |
| V4.5 reviewer, corrected positive map | PASS |

The five reviewer failures are the saved narrow reason question, supplied arrangement/duration,
possible-to-definite drift, parent agreement omitted from parts, and independently omittable
benefits bundled in one part. A preliminary review with reconstructed draft fact labels also
missed all five; the exact saved draft fields were then evaluated separately and failed likewise.
Neither result is a fresh end-to-end generation benchmark.

**Both candidates fail activation gates.** V3.1 did not pass the topic-only/check or no-regression
requirements. V4.5 did not detect saved negatives, and its provider gaps leave some grading gates
incomplete. No further prompt iteration, activation override or additional two-genre experiment
was performed. Keep meaning V3 admission; the historical false-full-credit risk remains explicit.

There were 47 one-shot attempts, 41 raw responses and six explicit HTTP 503 gaps. One raw V3.1
response failed independent evidence validation. No failed/uncertain call was replayed and no model
fallback was used. Provider responses reported 116,445 input and 13,359 output tokens (129,804 total);
actual cost was unavailable. These small diagnostics do not establish general accuracy.

Private evidence is ignored under `data/conversation-meaning-assessment-20261106/consistency-v3_1-v4_5/`:
`acceptance-summary.json`, paired `v3-summary.json`, `v4-summary.json`, `corrected-map.json`, individual
pre-call expectation markers and full input/raw result files. The reviewer regression uses
`review-frozen-original-fields--SCENARIO_MEANING_ASSESSMENT_V4_5.json`; positive review uses
`review-corrected-positive--SCENARIO_MEANING_ASSESSMENT_V4_5.json`. Existing Langfuse observations retain
the pinned metadata and exact calls. No private evidence or runtime configuration belongs in Git.

### Verification and local QA outcome

341 related Python tests passed (assessment, required parts, workflow/harness, contracts, jobs and
observability). The 83 warnings are existing Python 3.14/Starlette/dependency deprecations.
Python compilation, UTF-8 checks for eight task files and diff-whitespace checks passed.
The final Java run passed 94 tests (9 assessment and 85 engine integration), with zero
failures, errors or skips. The isolated Core package also built successfully.

The parent integration run packaged an isolated Core JAR and started the local baseline on ports
18082/8094 with QA user 14. Extraction succeeded and the new draft retained the original V3 hash.
Generation reached GENERATE and QUESTIONS, then the configured Gemini 3.5 Flash Lite returned
HTTP 503 during REPAIR_QUESTIONS. The operation settled UNKNOWN/AI_PROVIDER_UNAVAILABLE, with
the worker ledger UNKNOWN/PROVIDER_UNAVAILABLE and no result. It was not replayed. Therefore
this API run does not verify grading or completion. The test's own authenticated session was
logged out; baseline Core/API health remained UP. No candidate was activated or TTS requested.
The private receipt is `data/conversation-meaning-assessment-20261106/local-api-v4-followup-20261107T070445Z/flow.json`.
An additional authenticated read confirmed the old completed lesson remains readable and all
six original operation payloads match the saved evidence. That test session was also logged out.
`historical-and-health.json` records the checks and successful Core/API health responses.

Independent GPT-6 Astra acceptance: retain both exact-pinned candidates as evaluation code and
reject both activations. No critical diff bug was found. The three blockers are V3.1's topic-only
check/incomplete-recall over-credit, its blank-answer CAPTURED with fabricated reference evidence
(correctly blocked by validation), and V4.5's false acceptance of all five saved reviewer defects.
Positive grading on a corrected map does not demonstrate automatic generator quality. Default V3,
historical pins/schemas, deterministic aggregation and release gates remain unchanged. No further
prompt revision or paid call was performed after this rejection.

### Bounded input-projection follow-up — 2026-11-07

The next authorized diagnostic changes candidate input framing once. Raw V3.1 inspection found
that the blank check's fabricated learner quote exactly equals its private `answerVi`. The inherited
V1 instruction explicitly tells the grader to use the transcript and reference answers; the wire
input also contains unrelated meanings and questions. The exact-quote validator correctly rejected
that response. It cannot reject a semantically insufficient but genuine learner quote: topic-only
`They mentioned novels.` received PARTIAL, and `Novels take too long to read.` received CAPTURED
despite omitting the target's possible falling-behind consequence. Preference-only recap evidence
likewise received CAPTURED without the reason. These are observed failures; context contamination
as the cause of every semantic mismatch remains a hypothesis.

The V4.5 reviewer receives the same map twice in `content` and `groundedQuestions`. Its narrow reason
question's `requestedMeaningEn` repeats the complete parent meaning, while `answerUnitsVi` contains
only the reason; every flag is true. Thus the audit did not independently establish requested
coverage. More instructions alone did not resolve the five saved defects.

| Evaluation candidate | Exact workflow hash |
|---|---|
| V3.2 | `5cb6422287d537676b1f0cd2cc636418dda1767612ce7560520f7cf74d0eed53` |
| V4.6 measured projection v1, rejected; never admitted | `be609308096fc41dc366f6ddb913aa1c38e9cd2b1a4f86ec6418d903d9e63b7c` |
| V4.6 corrected projection v2, offline verification only | `290f0b30728a30e1b37373d1de88c8e60eae4905fe38096114e76052d2f3a4d6` |

Their manifests fingerprint the projection version. Candidate grading sends only selected
meaning/part IDs and text, the unchanged learner submission, and submitted questions' IDs,
meaning references and wording. It excludes transcript, source evidence, reference answers,
unrelated targets and profile context. Question wording supports interpreting elliptical answers;
facts supplied by the question still do not demonstrate recall. The inherited instruction to use
the transcript/reference is replaced. Each grade still makes one real provider call and uses the
original payload for validation and deterministic aggregation; blank answers are not synthesized.

V4.6 review sends actual question wording/reference once, separately from the complete meaning/part
inventory, source segments, grounding metadata and learning profile, joined by IDs. Repair findings
remain available. This is a smaller independent audit request, not a blind extraction protocol.
Historical hashes, schemas, lessons, validation and default V3 admission remain unchanged.

Sanitized fictional excerpts of the four observed grader failures are checked in as
`services/ai-generation/tests/fixtures/scenario-projection-regressions.json`. Offline tests verify
projection isolation, original-payload preservation, a real call for blank input, rejection of
fabricated evidence, retained part aggregation and complete reviewer inventory. They do not claim
model-quality accuracy. The private finite experiment is prepared under ignored
`data/conversation-meaning-assessment-20261106/consistency-projection-v3_2-v4_6/`; it uses the same
frozen inputs and labels, saved current V3 outputs as the explicitly historical baseline, then
one candidate call per case. Provider errors or unknown outcomes cannot be counted as passes.

#### Projection diagnostic result

The configured Gemini 3.5 Flash Lite completed the bounded comparison with no model fallback.
Current V3's ten saved calls are the historical baseline (6 expected matches, 4 semantic
mismatches), not simultaneous reruns. `baseline-manifest.json` preserves their file, input and
expected-label hashes. Every candidate uses the same frozen logical input and expected labels;
the projected wire input, exact messages, schema, raw response, usage and error are saved separately.

| Candidate diagnostic | Expected matches | Semantic mismatches | Validator rejections | Explicit HTTP 503 gaps |
|---|---:|---:|---:|---:|
| V3.2 grading, ten cases | 9 | 0 | 0 | 1 |
| V4.6 negative reviewer, one map with five defect classes | 0 | 1 | 0 | 0 |
| V4.6 corrected positive reviewer | 0 | 0 | 0 | 1 |

V3.2 correctly graded the blank check MISSED, the topic-only check MISSED, the topic-only recap
UNASSESSED, both incomplete recall cases PARTIAL, the full English recap and short English/Vietnamese
answers CAPTURED, and the contradictory check INCORRECT. The complete mixed-language answer received
HTTP 503 and remains an unverified regression gate. No semantic mismatch was observed among its nine
completed cases; this small frozen diagnostic is not a general accuracy estimate.

V4.6 still passed all five saved reviewer defects. Its narrow reason question now has a correctly
narrow `requestedMeaningEn` (asking why), but the coverage flag remains true against a parent that
also requires the preference. Smaller input improved that extracted description without fixing the
comparison. Its corrected-positive review received HTTP 503 and was not replayed.

**Reject both activations.** V4.6 fails the five-class negative gate; V3.2 lacks the complete
mixed-language regression result. Keep default meaning V3. Stop this candidate iteration: no further
prompt changes, V4 grading expansion, new genres, local full-flow/TTS run, regeneration or production
activation follows these results. The parent restored Docker dependencies through Explorer after
the first preflight found the registry unavailable; that preflight made no provider request.

There were 12 one-shot provider attempts, 10 raw responses and two explicit HTTP 503 gaps. Completed
responses reported 14,897 input and 3,538 output tokens (18,435 total); actual cost was unavailable.
`acceptance-summary.json` counts result files only; pre-call `-started.json` markers are excluded.
The two failures and all raw outputs remain in the ignored projection evidence directory. The
earlier unknown local generation operation was not replayed.

Final offline review found that projection v1 dropped the harness's `formatFeedback` on an UNKNOWN
review retry. The corrected implementation preserves both repair context and format feedback;
an offline retry regression verifies the exact failed output and schema issues reach the next
request. Only the review projection version changed to v2, producing the distinct hash above.
The old hash was used solely by the direct diagnostic helper, never committed/admitted or queued
as a product job. Its raw evidence remains unchanged. The corrected hash has **no paid model-quality
measurement** and inherits no passing claim from the old result; no provider call followed this
feedback-preservation correction. Default V3 remains active.

Python verification passed 346 related tests before that feedback correction (83 existing
dependency/deprecation warnings), then 97 focused assessment/parts tests after it (one existing
Python 3.14 deprecation warning). The stricter exact-feedback regression also passed independently.
Python compilation, UTF-8 checks for seven task files and diff-whitespace checks passed. These
offline results verify the correction's call data and harness behavior, not its model quality.

Final Java verification with the corrected worker/Core pin passed 107 tests: 9 assessment, 87
engine integration and 11 content tests, with zero failures, errors or skips. UTF-8 verification
passed all ten task files. Independent Astra acceptance rejected activation and identified the
format-feedback retention bug; its approved correction uses a new projection hash and keeps the
measured raw evidence unchanged. No candidate was activated and no local full-flow, audio or
production check was performed in this follow-up.
