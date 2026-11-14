# AI Evaluation Studio

## Access and runtime

Open `/admin/ai-evaluation` as an ADMIN. APIs exist only with Spring profile `local`.
The existing admin security, CSRF, account validation and AI emergency stop apply.
The version dialog selects the personalized Python pipeline **V4.13 / V4.14** or the
historical **Java V8** generator. These are separate experiment cohorts. Creating a version
does not activate a prompt for learners. V4.14 remains a candidate.
Both reuse the provider/account pools managed at `/admin/gemini`.
Existing provider quotas and user/application budgets still apply; Full can stop at a budget limit.

No new database tables or frontend packages are needed. Experiment documents are stored
atomically under ignored `data/ai-evaluation` (override with `dictalab.evaluation.directory`).
Back up that directory to preserve versions, runs and cached judgments. Do not serve it as static files.

### Personalized pipeline connection

Python requires `DICTALAB_AI_EVALUATION_ENABLED=true`, `DICTALAB_AI_SCENARIO_ENABLED=true`,
an existing strong service token and `production_mode=false`. The internal `/v1/evaluation/scenario`
API is disabled by default, authenticates before reading bodies and caps input at 64 KB.
Set `DICTALAB_AI_EVALUATION_DIRECTORY` to private local storage. Core still requires local profile,
ADMIN and CSRF. No learner/session records are created.

Execution reuses EXTRACT then GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW and
their existing bounded repairs. No replacement prompt or validator is introduced.
The runner reserves ten calls (extraction plus maximum generation envelope) before dispatch.
Unused reservations are conservative admission, not actual spend. GRADE and TTS are outside scope.

Personalized Smoke: three happy inputs at CEFR B1, TOEIC listening 255 and IELTS listening 5.5.
Regression: nine inputs across three programs. Full: twelve profiles repeated three times (36 samples).
Targets use the existing catalog; they are not recast as CEFR. These are synthetic inputs,
not human-approved gold lessons or calibrated exam scores.

Frozen versions include instructions, immutable prompt/few-shot hashes, Python source/assets/dependency
fingerprint, pool/model/fallback policy and Java fingerprint. Changed source/configuration rejects new
execution of an old snapshot. Python stores stage inputs, schemas, outputs and HTTP attempts; Core
stores stage inputs/outputs and the remote request ID, with instructions in the frozen version.
Credential-like content is redacted. Settings, service headers and API keys are never captured.

The initial complete lesson is assembled from the **first** QUESTIONS and TRANSLATE outputs and
checked by existing validators. Missing/invalid initial artifacts stay unresolved; a later repair cannot
retroactively pass them. Harness acceptance and independent nine-criterion semantic judgment are separate.

Exclusive durable admission and UUID idempotency prevent replay of interrupted/unknown work.
Stop lets the submitted lesson finish, then prevents the next lesson. Stored outputs remain readable
and exportable without regeneration. Replay rejudging is currently Java-only. Different actual returned
models across paired stages make a comparison inconclusive. None of these actions activates V4.14.
## Workflow

1. Create a baseline version from the current prompt. Optionally enable one Harness repair.
2. Run Smoke, inspect raw output, Harness steps, judgments and API observations.
3. Create a candidate with additional prompt instructions and run the same suite.
4. Compare two runs. Investigate critical failures and criterion regressions before aggregate rates.
5. Use Replay to validate stored first outputs without generating or repairing. Disable semantic
   judging for a provider-free replay. Replay is explicitly a narrower measurement than a live run.

For Java V8, Smoke has 3 inputs, Regression has 9, and Full has 27 inputs repeated 3 times (81 samples).
The initial synthetic dataset covers A1/A2/B1, turns 2/3/4, and cafe/directions/hotel contexts.
These are fixed context tests, not complete simulated learner journeys or a human-approved golden set.

## Reading the charts

- Blue: first output. Green: last output after the configured Harness policy.
- Semantic PASS requires structural validation and all nine independent checks.
- Disabling semantic judging leaves structurally valid outputs `REVIEW_REQUIRED`.
- Run rates use all planned samples; coverage is shown separately. Zero judged samples show no rate.
- Per-criterion bars use the samples actually judged on that criterion.
- Trend lines break at incomplete runs and use a cohort with the same dataset, runtime, mode and judge.
- Harness acceptance is separate from independent semantic quality. A rejected task can still have
  individually passing criteria; the dashboard does not treat Harness acceptance as proof of quality.
- Logical AI calls, provider HTTP attempts and measured tokens are different quantities.
  Missing usage is unknown, never a zero-cost claim. These are not provider invoices.

The judge uses the configured model in a separate context, with evidence checks. This reduces
some bias but does not make it independent human ground truth. Invalid or unsupported judgments
cannot pass. Matching judgments may be reused only for the same input, output, rubric and model.

## Comparison limits

Versions store prompts, repair policy, runtime settings and a Java backend fingerprint.
Running a frozen version against a different current Java backend/configuration is rejected;
create a new version. Historical runs remain comparable as evidence across backend updates.
Changes to prompt and backend/Harness together are reported as a combined experiment.

Gemini output remains nondeterministic at temperature 0; repeat indices are not provider seeds.
Actual source IDs/models are recorded when available. Source-pool edits, external provider
revisions are not pinned by the Java fingerprint alone; personalized runs additionally pin Python. Review traces
before attributing changes. No run or comparison automatically approves a production release.

One worker executes at a time. Start requests use UUID idempotency, calls are checkpointed,
and an interrupted run is never silently replayed. Stop prevents the next model admission;
an in-flight call may finish. Transport/quota failures stop further cases. Do not reset quotas
or switch accounts to conceal a failure.

## Checks

```text
mvn test -Dtest=AiEvaluationStudioTest,AiCallObservationTest,ConversationEvaluationTest,AiEvaluationSecurityTest,SpaRoutingTest,GeminiClientTest,AiPlatformClientTest,ConversationIntegrationTest
node --test src/test/js/ai-evaluation.test.cjs
python -m pytest services/ai-generation/tests/test_text_gateway.py
```

`src/test/fixtures/ai-evaluation-preview.cjs` contains UI-only synthetic results for visual QA.
It is never imported by production resources or written into the experiment ledger.
