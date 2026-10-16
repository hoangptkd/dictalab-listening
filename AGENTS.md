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

After completing a feature or fix, run the relevant checks, then commit and push
the task's changes to GitHub without asking for confirmation again. This is the
owner's standing authorization for this project's normal commit and push flow.

- Review the diff and stage only changes belonging to the completed task. Preserve
  unrelated work and keep credentials, runtime data and generated private files out
  of Git. Use a clear commit message describing the feature or fix.
- Push to the current task branch on `origin`, setting its upstream if needed.
  Never force-push, discard work or bypass required checks and branch protections.
- Follow any GitHub Actions run triggered by the delivery. Fix failures caused by
  the task and repeat the relevant checks before pushing the correction.
- The existing deployment workflow deploys only from `main` after tests pass.
  A push to another branch is GitHub delivery, not a verified production deployment.
  Use the established release flow when production deployment is authorized.
- Verify the remote commit after pushing. For a production deployment, also verify
  the workflow result and the application's health before reporting success.
- Report the branch, commit, check results and actual delivery/deployment status.
  If checks, authentication, permissions or deployment fail, state the blocker;
  never claim the task was delivered or deployed successfully without verification.

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
