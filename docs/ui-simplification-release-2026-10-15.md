# DictaLab: approved UI simplification

The owner accepted all 14 audit proposals on 2026-10-15 and requested GitHub delivery through the existing CI/CD workflow.

## Changes

1. Signed-in catalog: concise heading, remove repeated introduction and detail links.
2. Compact difficulty buttons; retain every level, content status and count. Descriptions and unchanged progress remain in a disclosure.
3. Keep search and learning status direct; group seven advanced criteria with a live count and conditional reset. Preserve URL values.
4. Listening history: clear heading and collapsible original instructions. Other listening states retain the original instructions.
5. Active conversation: one scenario/level context, instruction disclosure and desktop listening/reply columns; single column on mobile.
6. Group clarification, transcript and translation under assistance. Playback, slow playback, stop and the assessment warning remain direct. Opening the group does not request assistance.
7. Dictation: remove repeated slogan, generic description and duplicate keyboard guide. Retain protocol, controls and nearby shortcuts.
8. Account: email/password forms initially collapsed. Native validation and server errors reopen the relevant form; server errors receive focus and preserve entered values.
9. Admin users: usage overview initially collapsed.
10. Library management: dedicated heading and workspace. The regular lesson editor remains mounted and available in lesson management.
11. Admin lesson list: secondary filters grouped with active count; preserve defaults and URL restoration.
12. Support: remove duplicate introduction; preserve the response-time notice and data-deletion guidance.
13. Support: suppress pagination only on an empty first page without another page.
14. Result review: show three items first, with all remaining items and actions under a disclosure.

Rounded controls, existing tokens, business functions, validation, assessment disclosures and data protection are retained. No database, API, dependency or deployment configuration changes.

## Local verification

- JavaScript: 244 tests passed; no skips or failures.
- Java production profile: clean isolated build succeeded; 431 tests discovered, 66 conditional tests skipped, no failures/errors. PostgreSQL and mandatory Testcontainers gates remain required in CI.
- Production JAR verifier passed: S3 packaging, schema and published lesson pages retained.
- Release tooling: 29 Python tests, one POSIX-only test skipped on Windows.
- B1 content generator checks passed.
- Jev Ultrafast in owner Chrome: actual changed source with private, read-only local API snapshots; 13 routes at 1440×900 and 390×844, plus result and dictation at both sizes. Checked filter opening/reset/URL state, assistance, validation, account protection, support pagination, history instructions and isolated admin workspace; no horizontal overflow in these captures.
- Guest catalog navigates to the existing login route in this environment; guest introductory copy remains in source, but its rendered catalog state was not exercised.
- Vietnamese UTF-8 and whitespace checks passed.

Browser fixtures and screenshots are private ignored artifacts. These checks do not cover live two-person rooms, microphone permissions or sending real account emails. Production deployment is verified separately after the main CI/CD workflow completes.
