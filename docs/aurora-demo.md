# Aurora PostgreSQL demo

This is an on-demand CV demo on the existing VPS. It uses the `aurora-demo`
Spring profile and its own JAR, environment, Redis, JWT issuer/key and local files.
The normal Neon environment and application JAR remain available for recovery.
Never run both backends against their respective database copies at the same time.

## Start and stop

On the VPS:

```sh
sudo systemctl start dictalab-aurora-demo
curl --fail http://127.0.0.1:18183/actuator/health
sudo systemctl stop dictalab-aurora-demo
systemctl is-active dictalab-aurora-demo dictalab-aurora-redis
```

The existing HTTPS reverse proxy still uses port 18082. Starting the demo stops
`dictalab.service` through a systemd conflict. The demo is not enabled at boot.
Each start runs for at most one hour, plus graceful shutdown. It does not restart
automatically. Its private Redis stops when the demo stops. Redis sessions are
ephemeral; users sign in again after a Redis restart.

Aurora has min 0 / max 1 ACU and a five-minute idle timeout. Background database
jobs keep it awake while the backend runs. Stop the backend after a demo, close
SQL clients, then verify a pause event or `ServerlessDatabaseCapacity = 0` in AWS.
`Available` alone does not prove a database is paused. Avoid periodic health/SQL
probes after stopping it. A cold start can take tens of seconds.

## Authentication and isolation

`AWS_PROFILE` and `AWS_SHARED_CREDENTIALS_FILE` reference the existing protected
VPS credential file. The IAM identity has `rds-db:connect` for only the demo
database user on this cluster. The AWS JDBC IAM plugin renews connection tokens;
do not put a generated fifteen-minute token into `DB_PASSWORD`.

Aurora uses hostname-verified TLS with the Java default CA trust store. Private
Redis uses TLS on loopback port 16389, a password and a dedicated trusted
certificate. Credentials and private keys stay outside Git under `/etc/dictalab`.
The Redis certificate requires renewal before its expiry.

The demo environment disables outbound mail, billing, shared asynchronous queues,
progress publishing and S3 cleanup. Existing business validation remains enabled.
These settings prevent restored pending work from reaching production services.
Audio uses a separate prefix beneath the existing permitted S3 prefix. The 341
referenced S3 objects (622,686,923 bytes) were copied there and verified by size
and ETag, so demo edits do not change the original objects. This duplicate adds
S3 storage and request charges. Local media and personal files were copied too.

When deriving a demo environment from a Neon environment, remove legacy
`SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` and datasource
overrides. Flyway must use the IAM datasource. Keep migration validation and the
existing SQL timeouts enabled. Do not repair Flyway history to hide a mismatch.

## Data and recovery

The owner accepted the backup from **2026-10-27 03:19 UTC+07** because Neon rejected
new connections after exceeding its quota. Later Neon changes are absent.
The dump SHA-256 is
`bcd04cbd819a30683f9934ca0162448205dd7df5f0c580532e0df5f9e9fb881e`.

The PostgreSQL 18.6 dump was rehearsed on PostgreSQL 17 before Aurora 17.9.
Only the unsupported PG18 `SET transaction_timeout = 0` restore prologue was
omitted; restore uses `ON_ERROR_STOP` and one transaction. Keep the original dump.
Compare table fingerprints and sequence state before opening the demo to writes.
The initial Aurora restore matched all 75 table row counts/hashes and all 16
sequence states. The demo JAR was built from `origin/main`, whose migrations
match that backup; the other active development branch has different migration
numbers and must not be substituted without a separate migration review.

Aurora's automatic backups protect the demo database according to its retention
setting. The existing VPS `cloud-backup` timer still targets Neon, not this demo.
Do not treat its status or an old dump as a fresh Aurora backup. Before removing
Aurora or moving demo writes elsewhere, create and verify a current export.
Never restore the old Neon snapshot over newer Aurora changes.

## Cost and delivery

Singapore Aurora Standard prices checked on 2026-10-28: $0.20/ACU-hour,
$0.11/GB-month storage and $0.22/million I/O operations. Paused compute is free;
storage and other applicable charges remain. Thirty active hours at 0.5–1 ACU
cost $3–6 for compute, plus idle tails, storage, I/O and applicable transfer.
The $5–10 monthly target and budget email are not a hard spending cap.
Free Plan currently limits each cluster to 1 GB of data; verify usage before
loading more data. Credits depend on AWS eligibility and expiry.

Build and verify with:

```sh
mvn --batch-mode --no-transfer-progress -Pproduction-s3,ci-unit verify
python scripts/verify-production-jar.py target/english-dictation-0.1.0-SNAPSHOT.jar
```

The HTTPS auth smoke check and exact synthetic-account cleanup target Aurora with:

```powershell
./scripts/verify-cloud-auth.ps1 -AuroraDemo
```

This requires the demo to be running. Do not run the normal Neon cleanup mode
against a demo endpoint. The verified smoke covered registration, login, refresh,
logout, denied admin access, Secure/HttpOnly cookies and catalog access. A public
audio range probe also returned HTTP 206 with the requested 256 bytes.

Install a verified JAR separately at `/opt/dictalab/aurora-demo/dictalab.jar`.
The main deployment workflow targets `dictalab.service` and the normal JAR;
it does not deploy this separate demo or change its protected configuration.
Check that release flow before enabling a normal production deployment.

Sources: [Aurora pricing](https://aws.amazon.com/rds/aurora/pricing/),
[auto-pause](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html),
[Free Plan limits](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-free-tier.html),
[AWS JDBC IAM plugin](https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheIamAuthenticationPlugin.md).
