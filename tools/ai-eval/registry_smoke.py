"""Disposable PostgreSQL: private registry copy, backup restore and runtime isolation. No paid calls."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote
from uuid import uuid4

import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))
from app.config import Settings
from app.provider_registry import connect, decrypt_key, load_registry, save_pool, save_source
from app.registry_migration import initialize, snapshot, verify_private

spec = importlib.util.spec_from_file_location("provider_accounts", Path(__file__).with_name("provider_accounts.py"))
operator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operator)


def denied(operation, exception=Exception):
    try:
        operation()
    except exception:
        return
    raise AssertionError("expected isolation/validation rejection")


def main():
    container = "dictalab-registry-check-" + uuid4().hex[:10]
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, POSTGRES_PASSWORD=password)
    started = False
    try:
        subprocess.run(["docker", "run", "--detach", "--rm", "--name", container,
            "-e", "POSTGRES_PASSWORD", "-p", "127.0.0.1::5432", "postgres:18-alpine"],
            env=env, check=True, stdout=subprocess.DEVNULL)
        started = True
        port = subprocess.check_output(["docker", "port", container, "5432"], text=True).strip().rsplit(":", 1)[1]
        def dsn(database, role, secret):
            return f"postgresql://{role}:{quote(secret, safe='')}@127.0.0.1:{port}/{database}"
        admin_dsn = dsn("postgres", "postgres", password)
        deadline = time.monotonic() + 45
        while True:
            try:
                admin = psycopg.connect(admin_dsn, connect_timeout=2, autocommit=True)
                break
            except psycopg.OperationalError:
                if time.monotonic() > deadline:
                    raise RuntimeError("disposable registry PostgreSQL did not become ready") from None
                time.sleep(0.2)
        secrets_by_role = {role: secrets.token_urlsafe(32) for role in ("core_owner", "ai_owner", "ai_runtime")}
        with admin:
            for role, secret in secrets_by_role.items():
                admin.execute(sql.SQL("CREATE ROLE {} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD {}")
                    .format(sql.Identifier(role), sql.Literal(secret)))
            for database, owner in (("core", "core_owner"), ("ai", "ai_owner"), ("ai_restore", "ai_owner")):
                admin.execute(sql.SQL("CREATE DATABASE {} OWNER {}").format(sql.Identifier(database), sql.Identifier(owner)))
            admin.execute("REVOKE CONNECT ON DATABASE ai,ai_restore FROM PUBLIC")
            admin.execute("GRANT CONNECT ON DATABASE ai TO ai_owner,ai_runtime")
        core_dsn = dsn("core", "core_owner", secrets_by_role["core_owner"])
        owner_dsn = dsn("ai", "ai_owner", secrets_by_role["ai_owner"])
        runtime_dsn = dsn("ai", "ai_runtime", secrets_by_role["ai_runtime"])
        restore_dsn = dsn("ai_restore", "ai_owner", secrets_by_role["ai_owner"])
        core_key, ai_key = (base64.b64encode(os.urandom(32)).decode() for _ in range(2))
        source = dict(AI_PROVIDER_DATABASE_URL=core_dsn, AI_PROVIDER_ENCRYPTION_KEY=core_key)
        target = dict(AI_PROVIDER_DATABASE_URL=owner_dsn, AI_PROVIDER_ENCRYPTION_KEY=ai_key)
        with connect(core_dsn, readonly=False) as db:
            db.execute((ROOT / "src/main/resources/db/migration/V40__ai_provider_accounts.sql").read_text(encoding="utf-8"))
            db.execute("CREATE TABLE core_private_marker (value TEXT NOT NULL)")
            db.execute("INSERT INTO core_private_marker VALUES ('private-fixture')")
            for platform, enabled in (("gemini", True), ("deepseek", False)):
                model = "gemini-3.5-flash-lite" if platform == "gemini" else "deepseek-flash"
                save_pool(db, platform, model, 13, 100, enabled)
                save_source(db, core_key, dict(id=platform, platform=platform, projectId="test-account", model=model,
                    apiKey="fake-test-secret-" + platform, enabled=True, url=("https://api.deepseek.com/chat/completions"
                        if platform == "deepseek" else "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")))
        denied(lambda: initialize(core_dsn), ValueError)
        initialize(owner_dsn)
        initialize(owner_dsn)  # Checksum-verified idempotent initialization.
        denied(lambda: initialize(admin_dsn), ValueError)
        with tempfile.TemporaryDirectory(prefix="dictalab-registry-check-") as temporary:
            operator.ROOT = Path(temporary)
            denied(lambda: operator.copy_registry(source, target | dict(AI_PROVIDER_ENCRYPTION_KEY=core_key)), ValueError)
            denied(lambda: operator.copy_registry(source | dict(AI_PROVIDER_ENCRYPTION_KEY=ai_key), target), ValueError)
            with connect(owner_dsn) as db:
                assert snapshot(db, ai_key) == {"pools": [], "accounts": []}
            result = operator.copy_registry(source, target)
            assert result == {"copied": True, "pools": 2, "accounts": 2}
            assert operator.copy_registry(source, target)["copied"] is False
            denied(lambda: operator.copy_registry(source, source), ValueError)
            with connect(core_dsn) as db:
                expected = snapshot(db, core_key)
                assert db.execute("SELECT value FROM core_private_marker").fetchone()["value"] == "private-fixture"
            with connect(owner_dsn, readonly=False) as db:
                assert snapshot(db, ai_key) == expected
                assert db.execute("SELECT enabled FROM ai_provider_accounts WHERE id='deepseek'").fetchone()["enabled"]
                db.execute("GRANT USAGE ON SCHEMA public TO ai_runtime")
                db.execute("GRANT SELECT ON ai_provider_pools,ai_provider_accounts,ai_registry_schema TO ai_runtime")
            backup = next(Path(temporary).rglob("*.encrypted"))
            envelope = backup.read_text(encoding="utf-8")
            assert "fake-test-secret" not in envelope and "private-fixture" not in envelope
            restored = json.loads(decrypt_key(ai_key, dict(id="registry-copy", platform="gemini", account_id="operator",
                model="backup", endpoint="local", api_key_ciphertext=envelope)))
            assert restored == expected
            initialize(restore_dsn)
            operator.apply_accounts(restore_dsn, ai_key, restored)
            with connect(restore_dsn) as db:
                assert snapshot(db, ai_key) == expected
            with psycopg.connect(admin_dsn, autocommit=True) as admin:
                admin.execute("GRANT ai_owner TO ai_runtime")
            denied(lambda: load_registry(runtime_dsn, ai_key, require_private=True), ValueError)
            with psycopg.connect(admin_dsn, autocommit=True) as admin:
                admin.execute("REVOKE ai_owner FROM ai_runtime")
            denied(lambda: load_registry(owner_dsn, ai_key, require_private=True), ValueError)
            pools, sources = load_registry(runtime_dsn, ai_key, require_private=True)
            assert len(pools) == 2 and len(sources) == 2 and not next(s for s in sources if s.platform == "deepseek").enabled
            cfg = Settings(primary_provider="gemini", ai_provider_config_source="database", registry_ownership="private",
                ai_provider_database_url=runtime_dsn, ai_provider_encryption_key=ai_key, gemini_quota_backend="memory")
            assert cfg.active_cloud_api_key == "" and len(cfg.sources_for("gemini")) == 1
            with connect(runtime_dsn, readonly=False) as db:
                denied(lambda: db.execute("UPDATE ai_provider_accounts SET enabled=FALSE"), psycopg.errors.InsufficientPrivilege)
            # AI can connect to Core under PostgreSQL defaults but cannot read its tables.
            ai_at_core = dsn("core", "ai_runtime", secrets_by_role["ai_runtime"])
            with connect(ai_at_core) as db:
                denied(lambda: db.execute("SELECT * FROM core_private_marker"), psycopg.errors.InsufficientPrivilege)
            core_at_ai = dsn("ai", "core_owner", secrets_by_role["core_owner"])
            denied(lambda: connect(core_at_ai), psycopg.OperationalError)
            # A different existing target is never overwritten, and the source remains unchanged.
            with connect(owner_dsn, readonly=False) as db:
                db.execute("UPDATE ai_provider_pools SET global_rpd_limit=99 WHERE platform='gemini'")
            denied(lambda: operator.copy_registry(source, target), ValueError)
            with connect(core_dsn) as db:
                assert snapshot(db, core_key) == expected
            print("Private registry smoke passed: copy/restore/parity, disabled flags, restricted runtime, Core isolation, safe rejection")
    finally:
        if started:
            subprocess.run(["docker", "rm", "--force", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Private registry smoke failed; secrets and DSNs suppressed. Inspect isolated assertions locally.") from None
