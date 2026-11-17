"""Forward migration in a disposable schema on the CI-only PostgreSQL fixture."""
import os
from pathlib import Path
from uuid import uuid4
import pytest


@pytest.mark.skipif(not os.getenv("TEST_PROVIDER_ADMIN_DSN"), reason="requires disposable PostgreSQL fixture")
def test_provider_id_upgrade_preserves_ciphertext_fk_and_quota_constraints():
    import psycopg
    from psycopg import sql
    folder = Path(__file__).parents[1] / "app" / "sql"
    with psycopg.connect(os.environ["TEST_PROVIDER_ADMIN_DSN"]) as db:
        schema = "provider_upgrade_" + uuid4().hex
        try:
            db.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
            db.execute(sql.SQL("SET search_path TO {}, public").format(sql.Identifier(schema)))
            db.execute((folder / "001_provider_registry.sql").read_text(encoding="utf-8"))
            db.execute("INSERT INTO ai_provider_pools(platform,default_model,global_rpm_limit,global_rpd_limit) VALUES ('gemini','fixture-model',0,0)")
            db.execute("INSERT INTO ai_provider_accounts(id,platform,account_id,model,endpoint,api_key_ciphertext,rpm_limit,operating_rpm) VALUES ('source','gemini','account','fixture-model','https://fixture.invalid/v1/chat/completions','unchanged-ciphertext',10,10)")
            db.execute((folder / "003_extensible_provider_ids.sql").read_text(encoding="utf-8"))
            db.execute("INSERT INTO ai_provider_pools(platform,default_model,global_rpm_limit,global_rpd_limit) VALUES ('fixture','fixture-model',0,0)")
            assert db.execute("SELECT api_key_ciphertext FROM ai_provider_accounts").fetchone()[0] == "unchanged-ciphertext"
            for index, query in enumerate(("UPDATE ai_provider_pools SET global_rpm_limit=-1", "UPDATE ai_provider_accounts SET rpm_limit=0",
                              "UPDATE ai_provider_accounts SET platform='missing'", "UPDATE ai_provider_pools SET platform='bad-id' WHERE platform='fixture'")):
                savepoint = f"provider_constraint_{index}"
                db.execute(sql.SQL("SAVEPOINT {}").format(sql.Identifier(savepoint)))
                try:
                    with pytest.raises(psycopg.IntegrityError):
                        db.execute(query)
                finally:
                    db.execute(sql.SQL("ROLLBACK TO SAVEPOINT {}").format(sql.Identifier(savepoint)))
                    db.execute(sql.SQL("RELEASE SAVEPOINT {}").format(sql.Identifier(savepoint)))
        finally:
            db.rollback()
            db.execute(sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema)))
            db.commit()
