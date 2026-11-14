# Ponytail default mode: full

For coding and implementation tasks, prefer the smallest correct and safe change.
Inspect the relevant flow first and reuse existing code, standard libraries and
installed dependencies. Fix the root cause and related callers. Preserve unrelated
user changes. Never remove validation, security, accessibility, data protection,
error handling that prevents data loss, or explicit user requirements. Leave a
runnable check for non-trivial logic.

## Communication: caveman lite

Use professional, concise, complete sentences. Preserve technical meaning and use
normal prose for irreversible actions, security warnings and multi-step instructions.

## Automatic GitHub delivery after completed work

After completing an independently reviewable feature, fix or milestone, run the
relevant checks, then commit and push its changes without asking again. Complete
the implementation, related tests and documentation before delivery; intermediate
edits and each local test failure are not separate delivery milestones.

- Review the diff and stage only changes belonging to the completed task. Preserve
  unrelated work and keep credentials, runtime data and generated private files out
  of Git. Each commit has one clear purpose; include its tests and documentation.
- Use the task's `codex/*` branch. If the checkout is on `main` or detached, create
  a task branch before editing. Push to that branch on `origin`, setting its
  upstream if needed. Automatic push authorization does not authorize merging
  into `main` or deploying; those actions require task scope that includes them.
- Group related follow-up fixes before the first push. Once commits are shared,
  use normal follow-up commits; never force-push, discard work or bypass checks
  and branch protections. An authorized PR integration may squash related fixes.
- Follow any GitHub Actions run triggered by the delivery. Fix failures caused by
  the task and repeat the relevant checks before pushing the correction.
  For quota, runner availability or an external outage, report the blocker and
  completed checks. Do not create empty/unrelated commits, repeatedly rerun an
  unchanged workflow, or weaken required checks/uploads to manufacture success.
- Verify the remote SHA after pushing. Report push, CI and deployment separately:
  remote SHA verified or push failed; CI passed, failed, blocked or not triggered;
  deployment not attempted, failed or verified. Successful push is not green CI.
- Deploy only through the established `main` release flow after required checks
  pass and production activation is authorized. Keep `PRODUCTION_DEPLOY_ENABLED`
  paused until AWS backup/restore and actual VPS capacity are verified. A Core-only
  release is not a three-service deployment: verify Core, AI API/worker and Progress
  versions, readiness and business workflows before claiming microservice rollout.

## GitHub Actions scope and cost

- Keep the full required integration and release checks for PRs into `main` and
  main releases. Branch push checks should target affected components; include
  shared contracts, build settings and workflow files when they affect a service.
- Cancel superseded branch/PR checks using distinct concurrency groups. Never
  cancel an active production deployment or reuse its concurrency group for a
  standalone component check. Avoid overlapping push and PR triggers for the same
  optional checks; lightweight training checks run on PRs and direct main pushes.
- Keep optional iOS builds and heavy CPU training checks available manually.
  Preserve automated lightweight training validation and required backend tests.
- Keep artifact retention bounded. Do not delete rollback evidence, suppress an
  upload failure or bypass release gates merely to reduce storage or show green CI.

## Project authorization and credentials

The owner prefers autonomous execution of explicitly requested deployment work and
does not want repeated questions about security trade-offs already acknowledged.
When the owner explicitly asks to use a credential they supplied despite a prior
exposure warning, do not require rotation or a separate secret file merely to
repeat that warning. Use it only for the authorized target and task.

Keep credentials out of Git, source code, reports, output and process arguments;
use protected runtime configuration and stdin/environment-based transfer instead.
Do not disable TLS verification, authentication, authorization, validation, CSRF,
or data protection. Do not interpret this preference as permission for unrelated
targets, new purchases, deleting data, or ignoring higher-priority/tool confirmation
requirements. Report new material risks and uncertain outcomes once, concisely.
Keep backups and verify deployments; never claim an untested result.
