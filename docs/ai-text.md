# Shared AI text execution

Core business services use `AiTextService`; Python workflows use `ModelGateway`.
Gemini, DeepSeek, OpenAI-compatible Chat Completions and Ollama remain available.
Defaults, prompt versions, assessment rules and TTS configuration are unchanged.
This refactor does not activate a different provider or deploy a runtime.

## Boundaries

- `AiTextService.complete(Request)` returns a request-scoped `Result`, including
  selected provider/model, optional provider-reported model, source and usage.
  Convenience `completeJsonFor` overloads return only the JSON value. Coach and
  Listening grading consume the full result to persist the model actually selected.
- `AiTextProvider` owns endpoint/model validation, wire parameters and decoding.
  Spring registers implementations by their `id()`. Duplicate IDs fail startup.
- `ModelGateway.complete` retains its `(dict, provider)` return contract for
  existing workflows. Its request-scoped `last_result: ProviderResult` also carries
  selected model, reported model and normalized usage. Do not share a gateway
  instance between concurrent requests.
- `ModelProvider.complete` retains the parsed-dict adapter contract. Provider
  registration and `GenerationOptions` isolate thinking, effort and cache layout
  from conversation, Coach and assessment business logic.
- Quota/account selection and existing fallback rules stay in the gateways.
  Adapters never retry. Scenario continuation uses the gateway's
  `continuation_settings`, so later stages retain a successful configured fallback
  without replaying previous stages.
- Local Java conversation generation remains English-first followed by translation;
  both calls use the shared text transport. Translation uses the actual remaining
  workflow time. Local Coach retains its evidence, prompt and output validators.
- CPU model demo and Web judge are evaluation-only adapters. They are not selectable
  as application cloud providers. CPU provenance checks and Web account send
  journals/explicit-quota failover remain enforced.

Provider-specific legacy property/class names in the quota infrastructure remain
compatibility details. Redis key namespaces and usage history are not migrated.

## Provider and transport configuration

`DICTALAB_AI_TEXT_TRANSPORT=legacy` executes in Java; `python` executes through the
authenticated internal AI service. The execution location is independent of vendor.

Core's `DICTALAB_AI_TEXT_PROVIDER` must match Python's
`DICTALAB_AI_PRIMARY_PROVIDER`. Keep existing `GEMINI_*`, `dictalab.gemini.*`,
`DICTALAB_AI_TEXT_MODEL`, `DICTALAB_CONVERSATION_MODEL`, Conversation/Coach local
settings and rollout/capability settings. Existing defaults remain in place.

In an isolated installation, provider account keys and registry decryption keys
belong only to AI API/worker. Core receives the internal service URL/token and model
configuration. `scripts/run-service.py` continues to reject provider credentials
in the isolated Core configuration, including alternate property spellings.

The historical Conversation/Coach `provider=gemini` settings select their existing
default/cloud workflow. They do not force Google when the shared text configuration
selects another supported cloud connection. `provider=ollama` retains the separate
local workflow and its current model/prompt configuration.

Application limits remain disabled under the owner policy:
`DICTALAB_OPERATIONAL_LIMITS_ENABLED=false`, `AI_BUDGET_ENABLED=false`,
`RATE_LIMIT_ENABLED=false`, `AI_CONCURRENCY=0`, `TTS_CONCURRENCY=0` in Core, and
`DICTALAB_OPERATIONAL_LIMITS_ENABLED=false` in AI API/worker. Provider 429 responses,
cooldowns, circuits, authentication, ownership, validation and the AI emergency stop
remain effective. Do not reset usage counters when testing a provider.

## Model capabilities

Optional `DICTALAB_AI_MODEL_PROFILES` is JSON keyed by `provider/model`:

```json
{
  "openai_compatible/your-configured-model": {
    "tokenField": "max_completion_tokens",
    "temperaturePolicy": "omit",
    "jsonModes": ["json_object", "json_schema"],
    "schemaMode": "native"
  }
}
```

Use an actual model enabled in the encrypted registry; this example does not
recommend or activate a model. An empty object preserves existing wire behavior.

| Field | Allowed values | Behavior |
|---|---|---|
| `tokenField` | `max_tokens`, `max_completion_tokens` | Sends exactly one output-budget field. |
| `temperaturePolicy` | `send`, `omit` | Omission is explicit; no speculative HTTP retry with another payload. |
| `jsonModes` | Nonempty subset of `json_object`, `json_schema` | Rejects unsupported output modes before provider HTTP. |
| `schemaMode` | `native`, `prompt` | Uses native JSON Schema or JSON-object plus schema instructions. |

Without an override, Python Gemini retains native schema generation and generic
Core text retains JSON-object mode. Python OpenAI-compatible/DeepSeek retain their
existing schema-in-prompt and local JSON Schema validation. Java uses native schema
for explicit schema requests and existing JSON-object mode for generic calls.
Ollama retains its native `format` contract. Request-specific output budgets remain
with the workflow; adapters map them to the provider's field.

Model profiles are operator configuration, never request-supplied credentials or
arbitrary provider payloads. They do not implement Responses API, tool calls or
unsupported reasoning modes. Unknown capabilities are refused. A production
conversation artifact must explicitly include nonempty `model_profiles` in its
evaluated generation configuration. New profiles also participate in text job
fingerprints and evaluation identity; empty profiles preserve legacy job hashes.
TTS fingerprints do not depend on text profiles.

The selected model and provider-reported model are distinct metadata. A provider
response does not independently prove a mutable cloud model's internal revision.

## Internal text API

`POST /v2/text/completions` requires `X-Service-Token`. Fields:

- `schemaVersion="2"`, UUID `requestId`, configured `provider` and enabled `model`.
- `purpose`: `[a-z][a-z0-9_]{0,63}`; no user IDs or learner text.
- `messages`: 1–32 system/user/assistant messages with nonempty content.
- `schema`: object JSON Schema; remote references/IDs are prohibited.
- `outputMode`: `json_object` or `json_schema`; nullable `temperature` in 0–2;
  positive `maxOutputTokens` up to 32768.
- Absolute timezone-aware `deadline`, at most 60 seconds plus existing clock margin.
- Optional `allowModelFallback` defaults true for the existing Core behavior;
  false can disable configured model fallback, never enable an unconfigured one.

The response includes matching version/request ID, provider, `requestedModel`,
selected `model`, nullable `reportedModel`, JSON-object `task` and fallback chain.
`X-AI-Observe: usage` retains the existing optional observation envelope.
Core verifies provenance, bounds and the permitted fallback chain before accepting
the task. Python validates the supplied output schema; domain validators still
check facts, evidence, ownership and assessment rules.

Input remains bounded to 256 KB, response envelopes to 100 KB at Core and generic
task JSON to 12,000 characters. The v1 endpoint and Java v1 convenience transport
remain supported for an older Core. Both versions share durable UUID admission;
reusing an ID across versions is rejected even after failure/restart. Core does
not automatically downgrade v2 to v1 following a failed request.

V2 errors use an allowlisted code and outcome (`NOT_SENT`, `REJECTED`,
`RESPONSE_RECEIVED`, `UNKNOWN`). Authentication, invalid model/configuration,
unsupported capability, invalid output, duplicate admission and unknown provider
outcomes are distinguished without exposing raw provider responses. A received
invalid result is not permission for an adapter to repeat a paid call. Only the
existing bounded workflow repair rules may generate a separate repair request.

When deployment is separately authorized, upgrade AI API/worker before Core.
Keep production activation and all existing release/backup/capacity gates intact.

## Adding a provider

For Python execution:

1. Implement a class in `app/providers/` using the `ModelProvider` contract.
   Chat-compatible services can subclass `OpenAiCompatibleProvider`. The adapter
   owns its name, endpoint validation, probe/model IDs, capability validation,
   wire parameters, response parsing and safe errors.
2. Add one explicit registration in `app/providers/__init__.py`. Registration
   creates the adapter with endpoint/key/model and declared generation options.
   Do not add another provider whitelist in Settings, worker or workflows.
3. Configure its pool/accounts through the existing protected registry CLI. Keep
   endpoint TLS/host restrictions and encrypted account identity binding.
4. Select its ID/model in protected runtime configuration only after the required
   evaluation and release decisions. Do not edit conversation/Coach/grading logic.

If direct Java execution is required, additionally implement `AiTextProvider` as a
Spring component. The injected adapter list discovers it without editing business
services. A provider registered only in Python is valid for the Python transport;
direct Java execution refuses it explicitly.

`test_provider_extension.py` and `AiTextProviderTest` demonstrate the extension with
fake endpoints and no paid API calls.

## Registry schema compatibility

Core V55 replaces only the legacy enum CHECK on `ai_provider_pools.platform` with a
bounded ID syntax CHECK. It preserves accounts, ciphertext/AAD, FK, uniqueness,
quota constraints and history. It supports PostgreSQL/H2 and fails if it cannot
identify exactly one legacy constraint. Historical V42 is unchanged.

Private AI registry SQL 001 is unchanged. New operator command:

```powershell
python tools/ai-eval/provider_accounts.py upgrade-provider-ids --env-file <protected-owner-config>
```

This explicitly applies SQL 003 using the owner role, a transaction, advisory lock,
5-second lock timeout and 30-second statement timeout. It records version 3 and its
checksum in the registry ledger. Runtime roles remain read-only. Existing schema
v1 remains supported for the original providers; new IDs require the upgrade.
Management SQL/ledger 002 is independent and unchanged.

Do not run this against a runtime database merely to test the refactor. PostgreSQL
DDL still acquires locks; table size and traffic determine the release window.
After recording registry version 3, the old verifier will reject that database.
Rollback therefore needs the established protected restore/release procedure;
never delete a ledger row or rewrite a checksum to force an older binary to start.

## Offline authoring and evaluation

`app.offline_text` reuses `ModelGateway` for synchronous tools. Node authoring calls
`scripts/ai-text.py` using stdin and inherited protected configuration; credentials
never enter command arguments. Existing successful caches remain readable.
Teacher/benchmark journals remain with their owning tools. Unknown sends stop
without account/model fallback or automatic replay. Vocabulary and Node authoring
now retain an exclusive send marker before provider I/O. Inspect an uncertain
attempt manually; do not erase markers to hide a failure.

Frozen local benchmarks can supply an already frozen wire object. The shared
adapter must generate an equal request before its property order is reused.
A mismatch refuses execution and requires a new experiment; old evidence is never
silently rewritten. CPU/Web judge remain evaluation-only and retain their original
provenance and explicit-quota account policies.

## Unpaid verification from repository root

```powershell
python -X utf8 -m pytest -q services/ai-generation/tests services/model-serving/test_server.py tools/ai-eval training/tests
mvn --batch-mode --no-transfer-progress '-Pproduction-s3,ci-unit' clean verify
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationIntegrationTest,ConversationUxIntegrationTest,CoachServiceIntegrationTest,CoachLocalServiceIntegrationTest,CoachLocalQueueIntegrationTest,ListeningComprehensionIntegrationTest,AiBackgroundJobsIntegrationTest,AiUsageBudgetIntegrationTest,ScenarioIntegrationTest,ScenarioEngineIntegrationTest,ModelDemoIntegrationTest' test
node --test src/test/js/*.cjs
python tools/ai-eval/check_encoding.py
```

PostgreSQL/Redis/Rabbit tests remain conditional on disposable test infrastructure.
`test_registry_migration.py` uses only `TEST_PROVIDER_ADMIN_DSN` and rolls back its
private fixture schema. Never set migration test variables to the user's local or
production database. Live quality/evaluation flags remain opt-in and are not
needed for contract verification.
