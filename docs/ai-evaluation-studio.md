# AI Evaluation Studio

## Access and scope

Open <http://localhost:18082/admin/ai-evaluation> as an existing ADMIN. API requires Spring profile
`local`, authentication, ADMIN and CSRF. QA14 remains a learner; never elevate it to access this screen.
Provider accounts and fallback policies come from `/admin/gemini`.

New versions and runs use the personalized Python pipeline: current **V4.13** and the
frozen **V4.14 few-shot candidate**, available only for explicit local comparison.
The V4.14 prompt is loaded from the checked historical artifact; there is still one editable prompt source.
Java V8 and other historical prompt releases are read-only evidence. No new replay or generation uses them.
Creating an experiment snapshot does not activate or approve a learner/production prompt.

## Durable evidence

Canonical private state: `C:/Users/HP/.dictalab/local/studio/` and `python-records/`.
See [local startup](local-development.md). Keep these outside static resources and Git.
Back up snapshots, judgments and runs together; do not replace the ledger when restarting.

Python evaluation requires `DICTALAB_AI_EVALUATION_ENABLED=true`, `DICTALAB_AI_SCENARIO_ENABLED=true`,
the existing service token and `production_mode=false`. The internal `/v1/evaluation/scenario`
endpoint authenticates before reading bodies and caps input at 64 KiB.

The runner executes EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE and REVIEW,
including their existing bounded repairs. It reserves ten logical calls before dispatch and at most
two additional semantic judgments. Reservations are conservative admission, not measured cost.
GRADE and TTS are outside this experiment; they need their own functional checks.

| Suite | Samples |
| --- | ---: |
| Smoke | 3: CEFR B1, TOEIC listening 255, IELTS listening 5.5 |
| Regression | 9 across the three programs |
| Full | 12 profile inputs × 3 repeats = 36 |

Inputs are synthetic fixed contexts, not human-approved gold lessons or calibrated exam scores.

## Workflow

1. Create a snapshot from the current release.
2. Run Smoke. Inspect raw outputs, stage events, evidence, Harness results and independent judgments.
3. Change the single prompt source through the normal review/test flow, then restart the services.
4. Create a fresh snapshot. Old snapshots cannot dispatch against changed Python/Core code or runtime.
5. Compare compatible saved runs; inspect critical regressions before aggregate rates.

Snapshots pin prompt/few-shot hashes, Python source/assets/dependencies, model/pool/fallback policy,
and Core build. HTTP attempts, returned models and available usage are stored separately.
Changed configuration requires a new snapshot. Stored histories remain readable and exportable.
No automatic activation follows a comparison result.

UUID idempotency and an exclusive process lock prevent replay of admitted/UNKNOWN/interrupted work.
Stop lets the submitted lesson finish, then prevents the next lesson. Provider/transport failures stop
further paid cases. Do not reset counters, replay an unknown call or rotate accounts to disguise failure.
Definitive `QualityRejected` content failures remain failed samples and allow the next distinct
sample to run. They are not replayed or counted as passing; provider/transport/unknown failures still stop the run.

## Reading results

- Blue is the first output; green is the final output after Harness.
- Initial output is assembled from the first QUESTIONS and TRANSLATE artifacts; repairs cannot retroactively pass it.
- Semantic PASS requires structural validation and nine independent checks.
- Without semantic judgment, structurally valid output remains `REVIEW_REQUIRED`.
- Planned samples stay in the denominator; missing judgments show missing coverage, never a perfect score.
- Harness acceptance and independent semantic quality are separate measurements.
- HTTP attempts, logical calls, measured tokens and actual invoices are different quantities.
- Different actual returned models can make a paired comparison inconclusive.

The judge runs in a separate model context with source evidence checks. It can still be wrong.
Cached judgments require exact input, output, rubric and model identity. Code tests do not prove AI content quality.

## Checks

```text
mvn test -Dtest=AiEvaluationStudioTest,AiCallObservationTest,AiEvaluationSecurityTest,SpaRoutingTest,GeminiClientTest,AiPlatformClientTest,ConversationIntegrationTest
node --test src/test/js/ai-evaluation.test.cjs
python -m pytest services/ai-generation/tests/test_scenario_prompt_consolidation.py services/ai-generation/tests/test_scenario_evaluation.py
```

The UI preview fixture under `src/test/fixtures/` is synthetic. It never writes the experiment ledger.
