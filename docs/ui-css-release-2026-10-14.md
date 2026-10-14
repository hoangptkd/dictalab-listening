# UI CSS release — 2026-10-14

## Scope

The owner approved all six UI proposals in the chat “Tìm repository sửa giao diện”.
This release applies those changes to the verified installed application:

1. Fix the support administration header at 64px and prevent sidebar height from stretching it.
2. Align the desktop conversation setup columns while preserving stacked mobile spacing.
3. Style disabled account pagination controls consistently without changing native disabled behavior.
4. Use the shared study navigation header on the desktop lesson catalog; preserve its mobile rules.
5. Expand the user table when no profile is selected; retain two columns for selected profiles.
6. Align lesson editor labels and use 44px input/select controls for new, draft and published lessons.

Rounded frames and buttons are retained. No Java, JavaScript, dependency, database, security or learning-history changes are included in the deployment.

## Exact deployment candidate

- Installed baseline SHA256: `d3bf6b4091e925b3943dfbccdf462f4ec5071f2c05d3f935aa661b7e8bbe174e`.
- Candidate SHA256: `20953feedd467813ef33e922404885df0907cf2b801925fec23b4545a21fe297`.
- Exactly two changed JAR entries: `BOOT-INF/classes/static/css/app.css` and `BOOT-INF/classes/static/css/fidelity.css`.
- The approved patch is [ui-css-release-2026-10-14.patch](ui-css-release-2026-10-14.patch).
- Local packaging and verification evidence: `artifacts/ui-css-release-20261014/` (excluded from Git because it contains build and QA output).

The GitHub branch also stores the current source, tests and operational scripts from earlier work, including Google login and the lesson difficulty library. It is a source snapshot, not a claim that a full build of the branch equals this CSS-only candidate. Existing local changes outside the approved patch are preserved and are not automatically deployed.

## Verification before deployment

- 231/231 JavaScript tests passed, including 36 UI regression tests.
- Current Java source compiled successfully with `mvn -DskipTests compile`; this does not constitute a full backend test run.
- 28/28 rendered saved-DOM checks passed using CSS extracted from the exact candidate JAR at desktop and mobile sizes.
- 11/11 browser checks passed against the exact candidate application on an isolated local PostgreSQL database.
- Library preserved: 15 difficulty tiers, 23 original public lessons and reviewed memberships.
- Staged source checked for runtime-secret matches and common credential patterns; no findings. This is a targeted check, not a comprehensive security audit.

Saved-DOM administration checks verify layout, not authenticated live administration or backend integration. No new 50-VU load test or Google OAuth sign-in test was performed for this CSS release.

## Deployment

The source snapshot was pushed and verified on GitHub before VPS deployment:

- Branch: `codex/dictalab-ui-release-20261014`.
- Source commit: `097fa4d44a459a818a71bc3f33df373be3ca0aa8`.
- Application activated successfully after quiescent backup with an installed-baseline checksum guard and automatic rollback on readiness failure.
- Rollback backup: `/var/lib/dictalab/backups/release-lina_c6l`.
- Installed JAR SHA256 matches the tested candidate; service health is `UP`.
- Public `app.css` SHA256: `f778b0561b13c3f05293a43fe75af02270f46d2ef886ac95a79d526802411fe2`.
- Public `fidelity.css` SHA256: `013b68af49b8590a984ee78f1304513b01917bf16db3f5269241921674f42688`.
- Both publicly served CSS resources match the candidate byte for byte.
- 11/11 public browser checks passed after deployment, including difficulty selection, filtering, pagination, retired links, guest login return, keyboard navigation and 320/390px mobile layouts; no uncaught browser errors.
- Public library retains 15 difficulty tiers, 23 public originals and 63 memberships.
- Google sign-in configuration remains enabled; an unauthenticated current-user request returns 401.
- Production preflight confirms unchanged lesson inventory, Flyway V33 and the sampled completed/replay progress records.
- Before and after counts are identical: 123 lessons, 1,237 practice sessions, 68,352 word attempts and 111 users.

No schema migration was included. The database backup is a recovery precaution; activation did not restore or replace learning data.
