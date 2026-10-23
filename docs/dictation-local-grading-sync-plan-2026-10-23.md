# Dictation local feedback and durable sync — 2026-10-23

## Delivered behavior

Personal practice checks supported answers locally after persisting the event in
IndexedDB. It no longer waits for an HTTP response to show the verdict. Shared
rooms keep their existing protocol. No dependency was added.

`practice-sync.js` owns one durable record per account/session: official snapshot,
revision, grading manifest, raw draft/timer, ordered events, immutable in-flight
batch and completion receipt. Web Locks permits one writing tab; a second tab
shows an explanation and must close the first tab before continuing.

The server remains authoritative. Local feedback does not grant server progress
or score. The manifest contains salted SHA-256 hashes, not the hidden transcript.
Hashes can still be dictionary searched; this is not encryption or anti-cheat.
The shared normalization contract is `ascii-nfkc-v1`: Java trim, NFKC, curly
apostrophe replacement and lowercase. Non-ASCII results or missing Web Crypto use
server grading rather than guessing.

## Protocol and recovery

- `POST /api/v1/practice-sessions/{id}/sync/open` locks the owned personal session,
  enables sync and returns the official state, revision and content manifest.
- `POST .../{id}/sync/batches` requires `Idempotency-Key`. A request carries the
  sentence, content version, base revision and 1–32 ordered events. Check batches
  reuse loaded attempts; replay/hint/classification/completion are single-event
  barriers. `X-Practice-Events` must match the body; rate limits charge per event.
- Events are saved before accepted feedback. The batch key and exact body are
  saved before sending. A retry reuses both, including timing fields. Server
  receipts are checked before current revision/sentence validation, so a lost
  response cannot double-count an accepted batch or completion.
- The first pending event starts a two-second timer; eight events flush sooner.
  Only one request is active. Failures use backoff, jitter and Retry-After.
- Snapshots overlay later pending events. First wrong answers and deliberate
  repeated wrong attempts remain ordered. Raw drafts never become attempts merely
  by restoring a page. An acknowledged completion survives disconnect/reload.
- A 401 pauses sending and clears the private UI while retaining IndexedDB.
  Logging in as another account never sends the previous account's events.
- Content/revision conflicts pause without silently rebasing. The old view and
  draft remain available, with an explicit discard control. Storage failures
  never report an unpersisted check as accepted. Keep the tab open if storage
  fails; unsaved typing can still be lost.
- Explicit logout offers keep locally, delete locally, or continue learning.
  Local data is browser-profile data and can be removed by browser cleanup.
- Uncertain legacy sessionStorage submissions are recovered with their original
  keys before adopting sync. Adopted sessions reject legacy mutation endpoints.

This is not a whole-app offline mode: no service worker or audio predownload was
added. A loaded sentence and available audio can continue while requests retry.

## Release and rollback

V43 (originally V41 before integration with payment/Coach) adds `sync_enabled` (false by default) and `sync_revision` (zero by default)
to `practice_sessions`. Integration checks applied it on PostgreSQL 17.11 and
18.4. Production table size, database version and lock timeout were not inspected;
ALTER TABLE still needs a lock. Use the established backup/release process.

`dictalab.practice.local-grading.enabled` defaults to true. Set it to false to
stop new adoption while retaining ingestion for sessions already using sync.
Do not roll back to a pre-sync backend after adoption: it cannot ingest the
durable outboxes and does not enforce the adopted protocol. Keep this ingestion
code available, drain pending events, then plan a compatible rollback. Browser
outboxes cannot be proven drained solely from server state.

Deploy matching backend and static resources together. Observe sync 4xx/5xx,
rate limits, database load, browser storage/conflict reports and actual typing
latency. A branch push is delivery to GitHub, not a production deployment.

## Verification

- PostgreSQL 17.11: 73 tests passed across sync, grading, practice, contracts,
  rooms, history and rate limiting.
- PostgreSQL 18.4: 47 tests passed across sync, grading, practice, learning and
  rate limiting.
- Production/unit Maven gate: 193 tests, 178 passed and 15 intentionally skipped;
  no failures/errors. Production JAR verifier passed.
- Final JavaScript suite: 330 passed. It includes the native Chrome input test,
  real IndexedDB/Web Locks, delayed persistence and offline page reload.
- Latest local synthetic input-to-next-frame p95: 21.8 ms for 12 input events.
  This is not production latency or a physical input-to-pixel measurement.
- Recovery tests cover lost responses after commit, quota/ack-write failures,
  stale async hashes, ownership, locks, pending overlays, changed content,
  completion disconnect, final classification choice and server security checks.

Runnable checks:

```powershell
node --test --test-reporter=dot src/test/js/*.cjs
mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=target/local-grading-postgres' '-Ddictalab.testcontainers=true' '-Dspring.profiles.active=postgres-test' '-Dtest=PracticeSyncIntegrationTest,PracticeWordCheckIntegrationTest,PracticeServiceIntegrationTest,DataContractIntegrationTest,RoomControllerIntegrationTest,HistoryIntegrationTest,RequestRateLimitFilterTest' test
mvn --batch-mode --no-transfer-progress '-Pproduction-s3,ci-unit' '-Ddictalab.build.directory=target/local-grading-package' verify
python scripts/verify-production-jar.py target/local-grading-package/english-dictation-0.1.0-SNAPSHOT.jar
```

Authenticated production benchmarks, mobile devices and throttled network tests
remain unverified. No production deployment was performed for this fix.
