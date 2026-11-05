# Conversation and Gemini integration — 2026-11-06

## Scope

- Base: production `origin/main` at `6d265391c4087cf2da09f4b707c1593b4fee92d8`.
- Merge Conversation branch through `6682718c0d2a691c7d52a69149f8ebc6ef52fa34`.
- Include Gemini changes from `66fcdfe`, `75f6eef`, `35b11bd`, `8418600`, `bb6ac73`.
- Preserve main's Premium enforcement, WAV validation, production release fixes and vocabulary migrations/UI.
- Exclude uncommitted work and runtime/private configuration from the primary checkout.

## Integration resolutions

- Keep both TTS recipe validation and separate management-token validation.
- Stop usage retention and flush tracing during API shutdown.
- Load one provider registry snapshot before each cloud scenario workflow; keep TTS independent of the text registry.
- Keep durable queue-admission regression coverage alongside HTTP 429 coverage.
- Keep scenario integration tests and Gemini admin/router tests in AI Platform CI.
- Include the three required few-shot JSON artifacts in immutable service bundles; continue excluding arbitrary JSON configuration.
- Import scenario workflow during isolated release staging so missing dependencies/artifacts fail before activation.

## Verification on the integrated tree

| Check | Result |
| --- | --- |
| Full JavaScript suite | 391 passed |
| Java production unit/build | 291 tests, 0 failures/errors, 17 skipped |
| Java scenario, conversation, Premium, Gemini admin and routing | 264 tests, 0 failures/errors, 1 skipped |
| Python AI/model/evaluation/teacher/data contracts | 794 passed, 25 skipped |
| Gemini management with disposable PostgreSQL + scenario workflow | 165 passed |
| Registry handoff regression | 2 passed; included in the preceding 165 |
| PostgreSQL migration upgrade/fresh-install preservation | Passed; versions 47/49/fresh to 50, historical session/draft preserved |
| Linux release tests in isolated container | 120 tests, OK, 20 skipped |
| Production JAR assets/schema | Passed |
| Workflow syntax, UTF-8 and diff checks | Passed |

Windows cannot run Linux `O_DIRECTORY` lifecycle operations. Those release tests were rerun successfully in Linux; validation was not removed.
Skipped infrastructure/opt-in tests are not counted as passed. Test logs remain outside Git under `data/merge-verification-20261106/`.

## Deployment boundary

Merge/push does not enable scenario release flags or provision Gemini management tokens/roles.
Existing configuration, private registry, source keys and local training pause are unchanged.
Scenario activation still requires approved program/prompt hashes. Gemini management still requires its documented owner migration, restricted roles and separate internal management token.
The main push triggers the existing CI/CD workflow. Its actual result and public health must be checked separately before reporting production deployment success.

### First CI delivery follow-up

Run `42081867478` passed build/required checks but staging rejected the new bundle with `ValueError; no deployment performed`. The workflow updated release/schema helpers but left the host's bundle verifier unchanged; its old payload allowlist rejects the newly required few-shot JSON. Public health remained `200 / UP`.
The delivery correction transfers, backs up and installs the bundle verifier from the same revision before staging. A workflow regression check requires that order. Existing validation and rollback gates remain enabled.
