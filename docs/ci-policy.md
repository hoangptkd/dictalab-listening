# CI/CD execution policy

## Entry points

- `deploy.yml` coordinates PRs into `main`, pushes to `main`, manual validation,
  and weekly PostgreSQL compatibility checks.
- AI and Progress are reusable workflows. They do not also run on feature-branch
  pushes. Use a PR or manual dispatch to validate work before merging.
- PR checks cancel obsolete runs. Production activation is never interrupted by
  this cancellation policy. Compatibility runs have a separate concurrency group.
- `required-checks` fails for failed, cancelled, or unexpectedly skipped jobs.
  It accepts only the skips selected by the scope job. This is the stable aggregate
  check to require when configuring branch protection; existing settings are not
  changed by this commit.

## Scope selection

`scripts/ci-changes.cjs` compares the exact base and checked-out commit using Git,
including both sides of renames and deleted files. A failed comparison fails CI.

| Change in a PR | Heavy checks |
| --- | --- |
| Static HTML/CSS/JS | No backend services; browser and script checks still run |
| Documentation Markdown, iOS, JS tests | No backend services |
| Progress source or contract | Progress |
| AI services, prompts, training, AI evaluation | AI |
| Java, migrations, shared config, deployment scripts, unknown paths | Core unit, PostgreSQL, reliability, AI and Progress |

All application releases run every mandatory backend/service gate, even for a
UI-only release. Documentation-only and iOS-only pushes to `main` do not deploy the
backend. Weekly/manual validation runs all gates. Manual dispatch from a feature
branch never activates production. `full_compatibility: true` never deploys.

New source/config paths default to all checks. Extend the classifier only with a
test showing that cross-service changes and releases remain covered.

## Build and database gates

- Routine Core integration/reliability and Progress tests use PostgreSQL 17, as
  production does. The weekly/explicit full Core matrix also checks PostgreSQL 18.
- The reliability suite still requires real containers, concurrency CSV, and all
  required suites without missing/skipped cases. Migration permissions, backup,
  restore, and rollback checks remain in place.
- AI pure Java unit contracts are not repeated when Core CI runs them. Redis and
  AI integration contracts keep their separate environment coverage.
- AI packaging builds and stages one immutable three-service bundle from the Git
  revision. If deployment is enabled, this same file is uploaded as `application`.
  The deploy job checks its contents, checksums and source revision before use.
- Core unit packaging and the Progress Docker build remain distinct checks;
  this change does not claim every component compiles only once.

## Reports and storage

- Successful PRs use normal check/log output without uploading full test reports.
- Failed PR reports are retained for 3 days. Upload failures are diagnostic only;
  test commands and evidence validation still fail normally.
- Manual feature-branch report upload failures are also diagnostic only.
- Main/weekly evidence uploads remain required: routine reports 3 days,
  reliability evidence 7 days. Release bundles are retained for 7 days and are
  uploaded only when the established production deployment variables enable them.
- These rules do not delete existing artifacts, change billing limits, or recover
  compute already used. A full storage quota can still block an actual release.

## iOS and training

- iOS builds on affected PRs, affected `main` pushes, `ios-build-*` tags, or manual
  dispatch. Packages are published only for those tags or manual dispatch.
- Training has one PR/main trigger path; Linux checks run on PRs, and the heavier
  Windows CPU runtime runs on `main` or manual dispatch. Python downloads use the
  Actions pip cache.
- Workflow enable/disable settings on GitHub are unchanged. In particular, editing
  `training-offline.yml` does not enable a disabled training workflow.

## Local checks

```powershell
node --test src/test/js/ci-scope.test.cjs src/test/js/reliability-gate.test.cjs src/test/js/restore-isolation.test.cjs
mvn -f services/progress/pom.xml --batch-mode --no-transfer-progress '-Ddictalab.testcontainers.postgres-image=postgres:17.11-alpine' verify
$workflowPaths = Get-ChildItem .github/workflows/*.yml | ForEach-Object { $_.FullName }
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 -shellcheck= -pyflakes= @workflowPaths
```

These checks validate routing, final-gate failure behavior, workflow syntax and the
changed Progress database configuration. Only a successful workflow on `main`
followed by application verification confirms a production deployment.
