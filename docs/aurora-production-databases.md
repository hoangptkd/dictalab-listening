# Aurora databases for the EC2 microservices

## Completed on 2026-10-28

This is an on-demand demo using real restored data and separate production-style
identities. Database preparation is complete; application activation, public HTTP,
DNS and the coordinated service backup schedule are separate deployment gates.

The owner accepted Aurora's current data as the starting point. Its original Neon
snapshot is **2026-10-27 03:19 UTC+07**; later Neon writes are absent. The source
database `dictalab_demo` was left unchanged: all 75 table fingerprints and 16
sequence states, plus its schema-version entry, matched before and after the work.

| Database | Schema | Verified contents |
| --- | --- | --- |
| `dictalab_core` | Flyway V45 | 79 tables, 16 sequences; source restored before V44/V45 |
| `dictalab_ai` | Private registry v1 | 3 tables; 2 pools and 4 encrypted provider accounts |
| `dictalab_progress` | Flyway V1 | 3 tables; historical projection backfill remains an activation gate |

The source Aurora provider registry was empty. AI accounts were copied from the
existing local registry without changing it. Enabled flags and decrypted account
values matched after transfer, using a separate destination encryption key.

Infrastructure: Singapore `ap-southeast-1`, Aurora PostgreSQL 17.9 cluster
`dictalab-aurora-demo`, EC2 `dictalab-ec2-2gb`. The CloudFormation stack
`dictalab-ec2-database-access` completed its update. The template and SQL live in
`ops/aws/`; `databases.sql` deliberately refuses existing targets instead of
overwriting them. Do not rerun bootstrap against the completed databases.

## Identities and staged configuration

Each database has its own `dictalab_<service>_owner`, `_runtime` and `_backup` role.
Aurora IAM requires `INHERIT`; the only parent granted to these roles is
`rds_iam`, with `SET FALSE`. Runtime and backup roles cannot assume owner roles,
connect to the other service databases or connect to the source demo database.

| AWS identity | Database access |
| --- | --- |
| Existing `dictalab-audio-vps` | Core runtime; retains its existing audio/demo permissions |
| `dictalab-ec2-ai` | AI runtime, registry reads only |
| `dictalab-ec2-progress` | Progress runtime |
| `dictalab-ec2-migration` | Three owner logins for explicit migrations |
| `dictalab-ec2-backup` | Three read-only backup logins |

New IAM users have no Console login. Their policies grant only `rds-db:connect`
on exact database-user ARNs within this cluster. They cannot read Secrets Manager.
The operator-only secret `dictalab-ec2/database-credentials` contains the four new
key pairs. Keep its value out of reports, logs and process arguments.

EC2 configuration is staged under `/etc/dictalab/database/` (root `0700`), with
files root `0600`:

- `app.env`, `ai.env`, `progress.env`: **database snippets**, not complete service environments.
- `ai.aws-credentials`, `progress.aws-credentials`, `migration.aws-credentials`,
  `backup.aws-credentials`: separate profiles named after the file prefix.
- `backup.pg_service.conf`: explicit database and backup identity for each service.

Core references the existing `/etc/dictalab/aws-credentials`, profile
`dictalab-audio`. AI's staged environment contains its separate registry encryption
key. Service OS identities have not yet been granted access to these staged files.
Temporary operator tokens and plaintext transfer files were removed.

## Migrations and application activation

1. Build and verify the exact release. Activate the `aurora` Spring profile along
   with the intended production settings. This profile uses the AWS JDBC IAM
   plugin and hostname-verified TLS. Python's registry URI opts into freshly
   signed IAM tokens using `iam_region=ap-southeast-1` and `sslmode=verify-full`.
   Never store a generated 15-minute database token as a static password.
2. Merge the snippets into each service's complete protected environment;
   do not overwrite the full environment with a snippet. Preserve existing
   authentication, validation, mail/billing safeguards and service tokens.
   Remove legacy `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`,
   `SPRING_FLYWAY_PASSWORD` and conflicting `SPRING_DATASOURCE_*` overrides.
   Preserve explicit datasource identities used by the backup target checks.
3. Give each OS service identity access only to its own runtime key/configuration.
   Keep migration, backup and operator credentials inaccessible to runtime users.
4. Apply any future schema migrations separately using the appropriate owner
   login. Then reapply `ops/aws/grants.sql` as the Aurora operator and inspect its
   explicit table grants. Runtime has no automatic privileges on future tables;
   backups receive default SELECT grants for owner-created tables/sequences.
5. Runtime Flyway runs validation only on `aurora`; it must reject pending or
   changed migrations without attempting DDL or writing its history. Core retains
   Hibernate schema validation. Do not disable validation or repair history to
   conceal a mismatch. Core cannot read its legacy provider account/pool tables;
   configure the Python service to own the private registry.
6. Complete Progress historical backfill/replay, service messaging and job-ledger
   setup, then measure EC2 memory before starting the complete stack. Verify
   actual service health, authorization, queue delivery and backup recovery before
   switching public traffic. Do not run two writable copies of the application.

Actual Aurora acceptance passed 55 IAM/SQL privilege checks. Java JDBC IAM plus
read-only Flyway validation succeeded for Core V45 and Progress V1. Python loaded
the private registry through two physical connections. A reconnect after an actual
15-minute token expiry was not timed during this deployment.

## Backups and recovery evidence

Protected EC2 evidence is under
`/var/backups/dictalab-aws-production-20261028/` (root `0700`): source dump and
fingerprints, migration/permission receipts, encrypted registry snapshot, and
`post-migration-backup/` containing all three dumps and restore receipts.
`completion-receipt.json` records final configuration, cleanup and inactive-unit
checks. Evidence contains real user data; never commit it.

The three PostgreSQL 17 custom-format dumps were restored to disposable
PostgreSQL 17 with networking disabled. All **85 public tables and 16 sequence
states** matched their fingerprints after restore.

| Dump | SHA-256 |
| --- | --- |
| Core | `ecf830603779295ba1fa18db2cc3add249dee0a505eb60ad0cb86ebc4f594047` |
| AI | `bf3c8054a56d35c51e52f662e9cb92bff94aecfab1a2e6665b35a2c675b8c28f` |
| Progress | `b1894305f9b9d5f7c187281fac9abf70f00942fbb5458b101dc60bf975ff72e2` |

A second copy is on the operator's Windows machine under
`C:/Users/HP/.codex/tmp/aws-production-20261028/post-migration-backup/`.
The separately protected `keys/ai-registry.key` is required to recover encrypted
provider credentials. Preserve both database backups and the encryption key;
database dumps alone cannot recover those secrets. Windows ACLs restrict the
private parent folder/key to the operator and SYSTEM.

This is **not a coordinated application/Redis/RabbitMQ snapshot**. To recover,
restore into new isolated databases with owner identities, supply the matching
registry key, validate checksums and fingerprints, reapply explicit grants and
test runtime isolation before opening writes. Preserve the source and newer data;
never overwrite a live target with this historical copy.

The existing Lightsail backup timer still targets Neon. EC2's backup timer is
inactive; scheduled Aurora backups have not been activated. `cloud-backup.py`
supports protected IAM service profiles, but its `--services` mode still requires
four installed inactive service processes, a verified release and the private AI
job ledger. Its default client path is PostgreSQL 18; this preparation used
PostgreSQL 17 tools explicitly. Provision the chosen compatible client and boto3
runtime before activating that backup flow; do not claim a deployed schedule from
the existence of staged configuration.

## On-demand operation

At handoff, EC2 `dictalab.service`, `dictalab-aurora-demo.service` and
`cloud-backup.timer` are inactive. Aurora is configured for min 0 / max 1 ACU and
five-minute idle pause. After a demo, stop business processes and close SQL clients.
Confirm a pause event or zero `ServerlessDatabaseCapacity` in AWS; `Available`
alone is not pause evidence. Avoid repeated SQL/health polling after shutdown.
Storage, EC2 and other provisioned resources may still incur charges while
Aurora compute is paused. Deployment remains on demand, not a 24/7 service promise.

## Repeatable local checks

```sh
TEST_AWS_DATABASE_BOOTSTRAP=1 python -m unittest discover -s scripts/linux -p test_aws_database_access.py -v
python -m pytest -q services/ai-generation/tests/test_provider_registry.py
python tools/ai-eval/registry_smoke.py
mvn --batch-mode --no-transfer-progress -Pproduction-s3,ci-unit -Dtest=AuroraSchemaValidationTest package
mvn -f services/progress/pom.xml --batch-mode --no-transfer-progress verify
```

The bootstrap test uses Docker PostgreSQL 17 without network access and no AWS
credentials. Linux backup tests run as root in the existing disposable CI
container, including its opt-in real dump/restore rehearsal. The AI platform
workflow includes the new IAM/bootstrap and runtime-schema regression checks.
