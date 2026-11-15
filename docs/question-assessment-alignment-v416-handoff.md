# Question assessment alignment — V4.16 candidate

Status: implemented, not activated. Real AI case reruns are reserved for the user's next testing agent.
Canonical checkout: `C:/Users/HP/Desktop/English_dictition`.

## Evidence and root causes

Existing private evidence, unchanged: `C:/Users/HP/.dictalab/local/harness-live-regression-20261115/`.
Read `report.md`, `manual-findings.json`, `lessons-for-review.json`, `grade-probes.json` and original per-request records before running anything.

- Saved outputs contain extra scoring requirements and a purpose question whose sole target describes the action, not its purpose. Real GRADE probes penalized correct responses.
- QUESTIONS already instructed “all and only what is asked”; adding the same instruction again would not close the contract gap. Targets could still be formed by decomposing the whole source sentence.
- Reviewer returned aggregate booleans but no inspectable decision for each requiredPart. Its answerUnitsVi decomposed the supplied reference, encouraging verification of the same mistaken key rather than deriving an independent answer.
- Harness repairs localized findings. All-true reviews did not provide a finding to repair. The old defects remained unchanged through repair.
- GRADE projection omitted transcript. Its output contract forced a status for every part, with no valid way to report a defective key. Marking an unasked part ABSENT penalizes the learner; marking it CAPTURED invents evidence. Aggregation itself correctly follows its inputs and is unchanged.
- The Studio judge also missed defects. Its verdict is not human gold; this change deliberately leaves that measuring rubric unchanged for paired comparison.

## Implementation

| Component | Change |
|---|---|
| QUESTIONS | Derive parts from the requested answer rather than full-source clauses; check both extra and missing requirements. Preserve referents, conditions and certainty without making given context an extra part. Shared meanings must fit every linked question. |
| REVIEW_QUESTIONS | Derive answerUnitsVi independently from transcript + question. Candidate-only `partChecks` covers every linked `(meaningId, partId)` with `requiredByQuestion` and a short reason. Existing checks still handle wrong answers, missing targets, source support and speech acts. |
| Backend review | Enforce exact part inventory, no duplicates/foreign IDs/blank reasons. Any part marked outside scope becomes a localized repair finding even if summary flags are all true. Incomplete coverage is UNKNOWN, never PASS. |
| REPAIR_QUESTIONS | Preserve a valid question's requested information; repair extra/missing targets with linked fields instead of broadening the question to justify a bad target. Existing allowedTargets closure and independent re-review remain. |
| Harness | Carry explicit part findings into repair. Re-review the linked dependency group when a target changes even if question text is unchanged; retain findings outside that group. This also fixes stale warnings and missed regressions in the existing V4.15 repair path. No new stages or calls. |
| GRADE | Candidate projection includes the original activity transcript. Candidate response includes `assessmentIssue`: empty with normal part results, or a concrete invalid-key explanation with no results. These outcomes are mutually exclusive. Invalid keys become definitive QUALITY_REJECTED, not a learner score or uncertain provider outcome. |
| Core | Recognize the candidate pin and existing flexible-bank policy. Scoring aggregation, successful response shape, learner evidence validation and active release are unchanged. |

The two small schema extensions are deliberate: they make per-part review coverage verifiable and provide a non-scoring outcome for an invalid key. They do not add persisted lesson fields, database migrations or new part statuses.

No new dependency, AI call, audit stage, keyword-based semantic validator, dataset mutation, TTS change or security relaxation. Existing advisory-review policy remains advisory. Candidate grading can still refuse a defective key that escaped review; it does not automatically repair an already-started lesson.

## Versioning and compatibility

- Active V4.15: `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.
- Candidate V4.16: `ce9b6398767cd1cdc927e0f0e442984373a05d058470e616a7d9a3a758669ab8`.
- One editable asset remains `services/ai-generation/app/scenario-prompts.json`; V4.15 is frozen in the existing checked historical archive. Historical prompt/schema hashes and saved-job projections remain intact.
- Core's ACTIVE_PROMPT_HASH and workflow-v3.sha256 remain V4.15. No runtime restart or production deployment in this task.
- Candidate is listed by Python evaluation after the next authorized canonical restart. Existing evaluation deliberately rejects changed source fingerprints until restart.
- Normal generation remains five calls after EXTRACT; GRADE remains one call. Existing repair allowance, model fallback and output-token settings are unchanged. More review output and grade context may increase tokens/latency; measure this in the next run.

## Checks and handoff

Offline checks only; provider calls are mocked. They verify contracts, not semantic improvement:

Completed locally: 71 focused Python checks (including scoped-release compatibility), 41 Java checks, Python compilation, UTF-8 and diff checks passed. No real AI requests were made.

```powershell
Set-Location C:/Users/HP/Desktop/English_dictition/services/ai-generation
python -m pytest tests/test_scenario_assessment_alignment.py tests/test_scenario_prompt_consolidation.py tests/test_scenario_assessment_prevention.py tests/test_scenario_assessment_scoped.py tests/test_scenario_evaluation.py -q
Set-Location C:/Users/HP/Desktop/English_dictition
mvn --batch-mode --no-transfer-progress -Pci-unit '-Dtest=ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioContentTest' test
```

Next agent, only after authorization to test AI:

1. Restart through `scripts/run-local.ps1`; verify evaluation catalog, candidate pin, backend fingerprint, provider/model/fallback configuration. Do not activate V4.16 just to evaluate it.
2. Reuse the saved failing transcript and question artifacts. Evaluate V4.15 and V4.16 on identical inputs; do not regenerate old source content. Use new isolated result IDs/directories; never overwrite the prior 17-case evidence.
3. Measure review detection and false rejection separately: extra purpose/context, missing purpose, multiple requested details, a correct concise answer, wrong reference, paraphrases, subject/condition/certainty changes, proposal versus acceptance, shared targets, and questions answerable without listening. Include clean controls outside the previous topics.
4. Inspect every part finding and every accepted repair. Verify unchanged transcript and out-of-scope artifacts, exact allowlists, requested scope preserved, no new defect. Do not count structural PASS as semantic success.
5. For GRADE, test both the original defective key (expect no score if recognized) and its correctly repaired key (correct answer should score normally). Include partial multi-detail answers, English/Vietnamese/mixed responses, blank answers and RECAP. Refusing a bad key is not the same as successfully grading it.
6. Keep the existing Studio judge rubric fixed for the paired run, and independently inspect the saved source/question/target alignment. Report disagreements; do not equate judge PASS with correctness.
7. Preserve full input/output, schema, prompt/hash, part checks, patch attempts, real returned models/fallbacks, errors, tokens and latency. Mark fallback-model mismatches rather than hiding them in a prompt-only comparison.
8. Before activation, verify live schema acceptance/output size at small and large valid bank sizes, learner UI recovery from definitive grading rejection, and compatibility of saved V4.15 lessons. Stop for approval before changing active pins or deployment.

## Known limits

The model can still misjudge every part, invent an invalid-key issue or miss missing targets. Code verifies coverage and propagates explicit findings; it does not prove natural-language equivalence. Existing saved V4.15 lessons are not silently migrated/repaired. No real-case rerun, E2E browser test or claim of improved AI accuracy is included in this delivery.
