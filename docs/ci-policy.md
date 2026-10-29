# Demo CI/CD execution policy

## Automatic pipeline

The owner selected a smaller pipeline for the demo on 2026-10-29. `deploy.yml`
runs on PRs into `main`, pushes to `main`, or manual dispatch. It has four jobs:

1. `changes`: compare exact Git revisions and select affected source scopes.
2. `test-build`: browser tests, Linux helper tests, Core Java unit tests/package,
   production JAR verification, and runtime dependency scanning.
3. `required-checks`: reject failed, cancelled, missing or unexpectedly skipped
   scope/build jobs. A release must select a complete source build.
4. `deploy`: deploy only a release on `main`, with both production variables set.

AI Platform, Progress, PostgreSQL integration and reliability jobs are not part of
this automatic pipeline. The PostgreSQL owner-migration rehearsal and weekly
schedule are also removed. Demo releases therefore do not claim fresh coverage
of database integration, concurrency, broker recovery or AI service contracts.

## Scope and manual runs

`scripts/ci-changes.cjs` retains source classification for Core, AI, Progress and
frontend files. These flags describe source dependencies, not separate demo jobs.
Every application release builds all components, including a UI-only release.
PRs containing only static files or documentation avoid Java/backend builds.
Documentation-only and iOS-only main pushes do not deploy the backend.

The existing `full_compatibility` manual input is retained for compatibility with
saved dispatch commands. When true it validates the demo build without deploying;
it no longer starts PostgreSQL 17/18 matrices. A manual run from a feature branch
never deploys. Obsolete PR runs cancel; production activation is not interrupted.

AI and Progress workflow files remain available for explicit manual checks, with
no push or PR triggers. The Maven integration/reliability profiles and their
evidence validators remain available for local or future opt-in validation.

## Release packaging and activation

`test-build` builds the immutable three-service bundle from the exact Git commit
using `service-bundle.py`. This compiles Core and Progress and includes the pinned
AI source/runtime requirements; it does not run the removed service suites. Core
unit tests and the isolated bundle build remain separate Maven invocations.

The bundle is built, verified and uploaded only when production deployment is
enabled for `ec2-services`. `deploy` downloads that same artifact and rejects an
unexpected source revision or checksum. EC2 installs/checks the isolated AI runtime
before activation. Staging alone does not count as deployment success.

Activation retains protected-file checks, installed systemd unit verification,
service quiescence, a private backup, owner-only Flyway migration, reviewed
application rollback, and readiness checks. Live databases are never restored
automatically. The workflow verifies public health and always revokes its
temporary runner SSH rule. OIDC and ingress remain scoped to the existing target.

## Reports, costs and other workflows

- The application bundle and routine reports are retained for 3 days.
- Successful PRs do not upload complete reports; failed PR evidence is diagnostic.
- iOS and training triggers/enablement are unchanged by this demo policy.
- This change does not alter billing limits or delete existing artifacts.
- A branch push is GitHub delivery, not production deployment. Only a successful
  main workflow plus application verification confirms a deployment.

## Local verification

```powershell
node --test src/test/js/*.cjs
mvn --batch-mode --no-transfer-progress -Pproduction-s3,ci-unit clean verify
$workflowPaths = Get-ChildItem .github/workflows/*.yml | ForEach-Object { $_.FullName }
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -shellcheck= -pyflakes= @workflowPaths
```

Run the Linux helper suite on Linux/WSL or in a disposable Linux container:

```bash
python3 -m unittest discover -s scripts/linux -p 'test_*.py'
```

CI policy tests exercise the actual final-gate program and check that packaging,
revision verification, migration, rollback and scoped SSH cleanup remain wired.
