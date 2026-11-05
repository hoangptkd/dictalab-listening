# Scenario provider 503 diagnosis — 2026-11-06

## Confirmed cause

The local Core and AI API are healthy. The existing preview was still running the pre-merge Conversation checkout at `6682718`.
Historical EXTRACT jobs `6991bb4f-77fa-4072-b5bc-0b01b7fb7950`, `c002204f-03d9-4cb8-b16f-c2664601f7f8` and `a8e67c21-3d22-4803-acfd-ff2f876965a9` each terminated UNKNOWN after one attempt, with upstream HTTP 503.

Controlled checks against the configured Gemini model `gemini-3.5-flash-lite` found:

- A 14-token diagnostic completion returned 200 through source `a`.
- The actual approved EXTRACT prompt/schema returned 503. Google's JSON response was a one-element array containing error code 503, status `UNAVAILABLE`, and a high-demand message.
- There is no configured HTTP proxy. The upstream endpoint is Google's HTTPS OpenAI-compatible API.
- This is not evidence of quota exhaustion, invalid credentials, or a prompt/schema validation error. A small successful request does not prove the complete workflow is available.

[Google's API error reference](https://ai.google.dev/gemini-api/docs/generate-content/api-errors) identifies 503 UNAVAILABLE as temporary provider overload/unavailability.

## Changes

- Preserve provider 503 as `PROVIDER_UNAVAILABLE` in the worker ledger, surfaced as `AI_PROVIDER_UNAVAILABLE` in Core.
- Keep UNKNOWN and the existing no-automatic-replay policy. Do not reset/reclassify historical jobs or change provider/model/keys.
- Explain overload in Vietnamese in pending-operation and saved-grading feedback, retaining task IDs and saved work.
- Log phase, provider, model, source ID and completed-call count alongside the existing HTTP status. Do not log prompts, keys or provider response bodies.

## Verification

- Java ScenarioEngine integration: 68 tests passed.
- Scenario/conversation JavaScript: 69 tests passed.
- Python scenario/worker/provider tests: 171 passed, 25 opt-in database cases skipped. The added explicit HTTP 503/no-source-rotation parameter was then checked with the four-case rotation regression: all four passed.
- UTF-8, compilation, diff and JAR packaging checks passed.
- Restarted only the idle local preview's Core, API and worker using the integration checkout. Same local database, queue vhost, ledger and protected environment. Verified no pending/running jobs before shutdown; copied ledger backup outside Git.
- Live authenticated local creation: operation `37ec5fdb-fbec-4e1d-876e-998e977edf5b`, EXTRACT, UNKNOWN, `AI_PROVIDER_UNAVAILABLE`. Reusing its original idempotency key returned the same operation. Both served JS assets matched the updated checkout.
- Detailed evidence is outside Git under `data/provider-503-20261106/`; runtime history remains in the previous preview evidence directory.

## Remaining limitation

The live EXTRACT request still receives 503 from Google. These changes fix diagnosis and application feedback; they do not eliminate external overload. No automatic 503 retry, alternate account retry, platform switch or model downgrade was introduced. A new model/provider choice would require separate configuration and quality verification. Production was not redeployed for this follow-up.
