"""Operator-only migration/snapshot helpers for the private AI registry."""
import hashlib
from pathlib import Path

from .provider_registry import connect, decrypt_key, validate_endpoint

SCHEMA = Path(__file__).with_name("sql") / "001_provider_registry.sql"


def schema_checksum():
    # Git checkouts on Windows may use CRLF; the logical migration stays identical.
    return hashlib.sha256(SCHEMA.read_text(encoding="utf-8").encode()).hexdigest()


def identity(db):
    return db.execute("SELECT current_database() AS database, current_user AS role, "
        "inet_server_addr()::text AS server, inet_server_port() AS port").fetchone()


def restricted_role(db):
    row = db.execute("""SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,
        EXISTS(SELECT 1 FROM pg_roles parent WHERE parent.rolname<>current_user
            AND pg_has_role(current_user,parent.oid,'MEMBER')
            AND (parent.rolsuper OR parent.rolcreatedb OR parent.rolcreaterole
                 OR parent.rolreplication OR parent.rolbypassrls)) AS elevated_membership
        FROM pg_roles WHERE rolname=current_user""").fetchone()
    if not row or any(row.values()):
        raise ValueError("private registry requires a restricted database role")


def verify_private(db, *, runtime=False):
    restricted_role(db)
    versions = db.execute("SELECT version,checksum FROM ai_registry_schema ORDER BY version").fetchall()
    if versions != [{"version": 1, "checksum": schema_checksum()}]:
        raise ValueError("private registry migration checksum mismatch")
    if runtime:
        if db.execute("""SELECT EXISTS(SELECT 1 FROM pg_roles parent WHERE parent.rolname<>current_user
                AND pg_has_role(current_user,parent.oid,'MEMBER')) AS allowed""").fetchone()["allowed"]:
            # NOINHERIT still permits SET ROLE; runtime credentials must have no alternate role.
            raise ValueError("private registry runtime role must not have role memberships")
        writable = db.execute("""SELECT
            has_table_privilege(current_user,'ai_provider_pools','INSERT,UPDATE,DELETE,TRUNCATE') OR
            has_table_privilege(current_user,'ai_provider_accounts','INSERT,UPDATE,DELETE,TRUNCATE') OR
            has_table_privilege(current_user,'ai_registry_schema','INSERT,UPDATE,DELETE,TRUNCATE') AS allowed""").fetchone()
        if writable["allowed"]:
            raise ValueError("private registry runtime role must be read-only")


def initialize(dsn):
    with connect(dsn, readonly=False) as db:
        restricted_role(db)
        db.execute("SELECT pg_advisory_xact_lock(6028102801)")
        if db.execute("SELECT to_regclass('ai_registry_schema') AS name").fetchone()["name"]:
            verify_private(db)
            return
        if db.execute("SELECT 1 FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') LIMIT 1").fetchone():
            raise ValueError("private registry initialization requires an empty database")
        db.execute(SCHEMA.read_text(encoding="utf-8"))
        db.execute("CREATE TABLE ai_registry_schema (version INTEGER PRIMARY KEY, checksum VARCHAR(64) NOT NULL)")
        db.execute("REVOKE ALL ON ai_registry_schema FROM PUBLIC")
        db.execute("INSERT INTO ai_registry_schema VALUES (1,%s)", (schema_checksum(),))


def snapshot(db, master):
    """Preserve account enable flags even when their pool is disabled; no runtime masking."""
    from .config import GeminiSource
    pools = db.execute("SELECT platform,default_model,global_rpm_limit,global_rpd_limit,enabled "
        "FROM ai_provider_pools ORDER BY platform").fetchall()
    rows = db.execute("SELECT * FROM ai_provider_accounts ORDER BY id").fetchall()
    accounts = []
    for row in rows:
        validate_endpoint(row["platform"], row["endpoint"])
        accounts.append(GeminiSource(id=row["id"], platform=row["platform"], project_id=row["account_id"],
            model=row["model"], url=row["endpoint"], api_key=decrypt_key(master, row),
            rpm_limit=row["rpm_limit"], operating_rpm=row["operating_rpm"], tpm_limit=row["tpm_limit"],
            rpd_limit=row["rpd_limit"], priority=row["priority"], enabled=row["enabled"]).model_dump())
    return {"pools": pools, "accounts": accounts}
