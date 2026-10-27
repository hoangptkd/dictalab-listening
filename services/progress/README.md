# Progress Service

Independent Spring Boot 4.1.1 / Java 17 service. Owns its PostgreSQL database;
receives JSON events from `dictalab.progress.v1` and serves read projections to
authenticated Learning Core calls. No Core database connection or shared JPA
entity library is present.

## Local startup

Add the Progress settings from the root `.env.example` to your ignored `.env`.
Set a strong, distinct `PROGRESS_DB_PASSWORD` and a random 32-256 character ASCII
`PROGRESS_SERVICE_TOKEN`. Core and Progress must receive the same service token.
Do not place real secrets in command arguments or source files.

From the repository root:

```powershell
docker compose --profile messaging --profile progress up -d --build progress
pwsh -File scripts/run-local.ps1 -WithMessaging
```

Set `DICTALAB_PROGRESS_ENABLED=true` for Core event publishing. Keep
`DICTALAB_PROGRESS_READ_MODE=legacy` until historical export and parity checks
finish. Progress defaults to a separate local database at port 15434; it never
connects to Core's database at 15433. API is loopback-only at port 8092.

Without Docker packaging, configure `PROGRESS_DB_URL`, `PROGRESS_DB_USERNAME`,
`PROGRESS_DB_PASSWORD`, `PROGRESS_SERVICE_TOKEN` and RabbitMQ settings in the
process environment, then run `mvn -f services/progress/pom.xml spring-boot:run`.
Use a separate PostgreSQL database/role; never give the role Core permissions.

## Contracts

- `GET /internal/v1/users/{positiveUserId}/dashboard`: requires `X-Service-Token`.
  Only Core is trusted to select the user after authenticating the browser.
- `GET /actuator/health/readiness`: database/broker readiness, no secret details.
- `GET /actuator/prometheus`: requires the service token.
- Event schema: `../../docs/progress-event-v1.schema.json`.
- HTTP contract: `../../docs/progress-openapi.yaml`.

Events contain no account email field, transcript, answer text, credentials, or
raw prompts. Lesson titles remain free text and should not contain personal data.
Only completed solo dictation sessions enter this dashboard. Core still owns
scoring, recommendation, assessment eligibility and the listening assessment
algorithm. Returned `updatedAt` is the projection's commit time; `lastEventAt`
is its newest source event timestamp, not proof the queue is fully caught up.

## Backfill and cutover

1. Enable publishing while keeping Core reads in `legacy` mode.
2. As an authenticated admin with the normal CSRF token, call
   `POST /api/admin/progress/backfill?limit=50`. Submit `nextCursor` as `after`
   until `done=true`. Batches are bounded, restartable and idempotent.
3. Wait for outbox and RabbitMQ queues to drain; inspect FAILED events and DLQ.
4. Set `DICTALAB_PROGRESS_READ_MODE=shadow`. Core returns legacy data and counts
   `dictalab.progress.parity{outcome=match|mismatch|unavailable}`. Do not cut over
   until parity is verified on historical, empty, recent, and shared-room cases.
5. Set read mode to `remote`. Dashboard summaries/trends/errors now come from
   Progress. Core adds its existing recommendation and listening assessment.

Rollback reads with `legacy`, keeping publishing enabled so the projection stays
current. A Progress outage returns a bounded dashboard error; practice writes
never wait for Progress or RabbitMQ. If publishing was disabled, backfill before
another cutover. Do not drop databases or rewind Flyway migrations to roll back.

## Recovery and deletion

Core waits for publisher confirms and rejects unrouted sends. A failed or lost
ACK can cause duplicate delivery; the consumer serializes per learner and
applies only newer session revisions. A conflicting payload at the same revision
is rejected. Metadata edits schedule bounded re-export with a new projection
revision. Retained source event bodies are scrubbed during account deletion.

User deletion creates a durable user tombstone in Progress. Late events cannot
recreate the projection. The tombstone retains only the opaque numeric ID and
timestamps; user IDs must never be reused.

After 20 failed publish attempts, inspect the cause and use the authenticated,
CSRF-protected `POST /api/admin/progress/events/{id}/retry` endpoint. Poison
messages go to `dictalab.progress.v1.dlq`. Repair the cause before redriving;
do not silently discard DLQ contents or publish unvalidated edits.

## Checks

Docker Engine must be available; selected infrastructure checks fail when it is
not. They use disposable PostgreSQL/RabbitMQ containers, no paid model calls:

```powershell
mvn -f services/progress/pom.xml --batch-mode --no-transfer-progress verify
mvn --batch-mode --no-transfer-progress '-Dtest=ProgressOutboxIntegrationTest,ProgressClientTest' test
mvn --batch-mode --no-transfer-progress '-Ddictalab.testcontainers=true' '-Ddictalab.testcontainers.postgres-image=postgres:18-alpine' '-Dspring.profiles.active=postgres-test' '-Dtest=ProgressDeliveryIntegrationTest' test
```

The Compose 384 MiB memory cap is an initial local limit, not a measured VPS
capacity guarantee. Measure the whole deployment before selecting VPS RAM.
