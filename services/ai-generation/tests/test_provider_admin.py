import base64
import json
import os
import time
from types import SimpleNamespace
from uuid import uuid4
from unittest.mock import patch

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.config import Settings, GeminiSource
from app.model_gateway import ModelGateway
from app.providers.base import ProviderHttpError, ProviderError
from app.provider_admin import SourceEdit, PoolEdit, edit_source, edit_pool, snapshot, initialize_management, record_attempt, cleanup_usage
from app.provider_registry import connect, save_pool, save_source, decrypt_key

MASTER = base64.b64encode(bytes(range(32))).decode()


def config():
    return Settings(primary_provider="gemini", gemini_quota_backend="memory", gemini_quota_enabled=True,
        operational_limits_enabled=False,
        gemini_providers=[GeminiSource(id=f"admin-test-{i}", project_id=f"admin-project-{uuid4().hex}",
            api_key=f"fake-key-{i}", enabled=True) for i in range(4)])


@pytest.mark.asyncio
@pytest.mark.parametrize("http_status, expected", [(429, 4), (500, 1), (401, 1)])
async def test_only_429_visits_each_source_once_and_keeps_model(http_status, expected):
    cfg, calls = config(), []
    def handler(request):
        calls.append(request)
        return httpx.Response(http_status, headers={"Retry-After": "73"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderError):
            await ModelGateway(cfg, client).complete([], {}, time.monotonic() + 3, "test")
    assert len(calls) == expected
    assert len({r.headers["Authorization"] for r in calls}) == expected
    assert {json.loads(r.content)["model"] for r in calls} == {cfg.gemini_model}


@pytest.mark.asyncio
async def test_timeout_never_tries_another_source():
    calls = []
    def handler(request):
        calls.append(request)
        raise httpx.ReadTimeout("unknown outcome")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(TimeoutError):
            await ModelGateway(config(), client).complete([], {}, time.monotonic() + 3, "test")
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_token_usage_is_actual_and_logical_request_is_counted_once(monkeypatch):
    cfg, events = config(), []
    monkeypatch.setattr("app.provider_admin.record_attempt", lambda *args: events.append(args))
    def handler(request):
        if not events:
            return httpx.Response(429)
        return httpx.Response(200, json={"usage": {"prompt_tokens": 123, "completion_tokens": 45},
            "choices": [{"message": {"content": '{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result, _ = await ModelGateway(cfg, client).complete([], {}, time.monotonic() + 3, "test")
    assert result == {"ok": True}
    assert [event[3] for event in events] == ["429", "success"]
    assert [event[4] for event in events] == [False, True]
    assert events[0][1] == events[1][1]
    assert events[0][5] == {} and events[1][5] == {"input_tokens": 123, "output_tokens": 45}


def test_registry_refresh_changes_new_requests_but_preserves_existing_snapshot():
    cfg = config()
    cfg.ai_provider_config_source = "database"
    cfg.ai_provider_database_url = "postgresql://fixture-only"
    first, second = cfg.gemini_providers[:1], cfg.gemini_providers[1:2]
    pool = {"gemini": {"default_model": cfg.gemini_model, "global_rpm_limit": 0, "global_rpd_limit": 0}}
    with patch("app.provider_registry.load_registry", side_effect=[(pool, first), (pool, second)]):
        a, b = cfg.current_registry(), cfg.current_registry()
        assert a.sources_for("gemini") == first and b.sources_for("gemini") == second
        assert a.current_registry() is a
    with patch("app.provider_registry.load_registry", side_effect=ValueError("unavailable")):
        with pytest.raises(ValueError):
            cfg.current_registry()


def test_management_token_and_validation_do_not_echo_secret(monkeypatch):
    from app import main
    cfg = Settings(provider_admin_token="m" * 40, provider_admin_database_url="postgresql://unused",
        primary_provider="ollama", service_token="s" * 40)
    cfg.ai_provider_config_source = "database"
    cfg.ai_provider_database_url = cfg.provider_admin_database_url
    monkeypatch.setattr(main, "settings", cfg)
    with TestClient(main.app) as client:
        assert client.get("/v1/admin/gemini", headers={"X-Service-Token": "s" * 40}).status_code == 401
        headers = {"X-Provider-Admin-Token": "m" * 40, "X-Admin-Actor": "7"}
        result = client.put("/v1/admin/gemini/a", headers=headers, json={"apiKey": "private-test-key", "rpmLimit": "bad"})
        assert result.status_code == 400 and "private-test-key" not in result.text
        assert client.put("/v1/admin/gemini/a", headers=headers, content="x" * 9000).status_code == 413
        assert client.get("/v1/admin/gemini", headers=headers | {"X-Admin-Actor": "-1"}).status_code == 400


@pytest.fixture
def postgres_settings():
    """Opt-in container DSN only; never uses project/runtime credentials."""
    dsn = os.environ.get("TEST_PROVIDER_ADMIN_DSN")
    if not dsn:
        pytest.skip("requires disposable TEST_PROVIDER_ADMIN_DSN")
    from urllib.parse import urlsplit, urlunsplit, quote
    import psycopg
    from psycopg import sql
    uri = urlsplit(dsn)
    assert uri.hostname == "127.0.0.1" and uri.port == 15549
    suffix = uuid4().hex[:12]
    database, owner, reader, manager, writer = [f"test_{kind}_{suffix}" for kind in ("db", "owner", "reader", "manager", "writer")]
    password = uuid4().hex
    with psycopg.connect(dsn, autocommit=True) as admin:
        for role in (owner, reader, manager, writer):
            admin.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(sql.Identifier(role), sql.Literal(password)))
        admin.execute(sql.SQL("CREATE DATABASE {} OWNER {}").format(sql.Identifier(database), sql.Identifier(owner)))
    def url(role):
        return urlunsplit((uri.scheme, f"{role}:{quote(password)}@{uri.hostname}:{uri.port}", "/" + database, "", ""))
    owner_dsn = url(owner)
    from app.registry_migration import initialize, verify_private
    initialize(owner_dsn)
    initialize_management(owner_dsn)
    initialize_management(owner_dsn)  # Idempotent; v1 ownership checksum stays valid.
    with connect(owner_dsn, readonly=False) as db:
        verify_private(db)
        save_pool(db, "gemini", "gemini-3.5-flash-lite", 0, 0, True)
        db.execute(sql.SQL("GRANT USAGE ON SCHEMA public TO {},{},{}").format(*map(sql.Identifier, (reader, manager, writer))))
        db.execute(sql.SQL("GRANT SELECT ON ai_provider_pools,ai_provider_accounts,ai_registry_schema TO {}").format(sql.Identifier(reader)))
        db.execute(sql.SQL("GRANT SELECT ON ai_provider_pools,ai_provider_accounts,ai_provider_labels,ai_provider_management,ai_provider_audit,ai_provider_usage TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT INSERT,UPDATE ON ai_provider_accounts,ai_provider_labels,ai_provider_management TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT UPDATE ON ai_provider_pools TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT INSERT ON ai_provider_audit TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT USAGE ON SEQUENCE ai_provider_audit_id_seq TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT SELECT,INSERT,UPDATE,DELETE ON ai_provider_usage TO {}").format(sql.Identifier(writer)))
        db.execute(sql.SQL("GRANT USAGE ON SEQUENCE ai_provider_usage_id_seq TO {}").format(sql.Identifier(writer)))
    cfg = SimpleNamespace(provider_admin_database_url=url(manager), ai_provider_encryption_key=MASTER,
        provider_telemetry_database_url=url(writer), operational_limits_enabled=True,
        current_registry=lambda: config())
    cfg.reader_dsn, cfg.owner_dsn = url(reader), owner_dsn
    try:
        yield cfg
    finally:
        with psycopg.connect(dsn, autocommit=True) as admin:
            admin.execute(sql.SQL("DROP DATABASE {} WITH (FORCE)").format(sql.Identifier(database)))
            for role in (owner, reader, manager, writer):
                admin.execute(sql.SQL("DROP ROLE {}").format(sql.Identifier(role)))


def edit_payload(**values):
    return SourceEdit(revision=0, name="Nguồn Gemini thử nghiệm", projectId="project-a", model="gemini-3.5-flash-lite",
        apiKey="fake-secret-only", rpmLimit=15, operatingRpm=13, tpmLimit=0, rpdLimit=0, priority=0,
        enabled=True, **values)


def test_postgres_management_encrypts_audits_conflicts_and_keeps_runtime_readonly(postgres_settings):
    cfg = postgres_settings
    edit = edit_payload()
    assert edit_source(cfg, 7, "source-a", edit)["revision"] == 1
    with connect(cfg.reader_dsn) as db:
        from app.registry_migration import verify_private
        verify_private(db, runtime=True)
        row = db.execute("SELECT * FROM ai_provider_accounts WHERE id='source-a'").fetchone()
        assert decrypt_key(MASTER, row) == "fake-secret-only"
        assert "fake-secret-only" not in row["api_key_ciphertext"]
    with pytest.raises(HTTPException) as conflict:
        edit_source(cfg, 8, "source-a", edit)
    assert conflict.value.status_code == 409
    changed = edit.model_copy(update={"revision": 1, "apiKey": None, "projectId": "project-b", "enabled": False})
    edit_source(cfg, 8, "source-a", changed)
    with connect(cfg.reader_dsn) as db:
        row = db.execute("SELECT * FROM ai_provider_accounts WHERE id='source-a'").fetchone()
        assert row["account_id"] == "project-b" and decrypt_key(MASTER, row) == "fake-secret-only"
    result = snapshot(cfg)
    assert result["revision"] == 2 and result["sources"][0]["enabled"] is False
    assert "fake-secret-only" not in json.dumps(result, default=str)
    assert result["audit"][0]["actor_id"] == 8
    assert result["audit"][1]["changes"]["apiKey"] == "added"
    edit_pool(cfg, 7, PoolEdit(revision=2, globalRpmLimit=30, globalRpdLimit=100, enabled=False))
    result = snapshot(cfg)
    assert result["pool"]["enabled"] is False and result["revision"] == 3
    cold = Settings(ai_provider_config_source="database", ai_provider_database_url=cfg.reader_dsn,
        ai_provider_encryption_key=MASTER, registry_ownership="private", primary_provider="gemini", gemini_quota_backend="redis")
    assert cold.sources_for("gemini") == []


def test_postgres_statistics_unknown_tokens_retention_and_terminal_attempt(postgres_settings):
    cfg = postgres_settings
    source = config().gemini_providers[0]
    request = uuid4()
    record_attempt(cfg, request, source, "429", False, {}, .05)
    record_attempt(cfg, request, source, "success", True, {"input_tokens": 10, "output_tokens": 5}, .1)
    result = snapshot(cfg)
    row = result["usage"][0]
    assert row["attempts"] == 2 and row["requests"] == 1 and row["measured_attempts"] == 1
    assert row["input_tokens"] == 10 and row["output_tokens"] == 5
    assert row["rate_limited"] == 1
    assert len(snapshot(cfg, period="month")["usage"]) == 1
    with connect(cfg.owner_dsn, readonly=False) as db:
        db.execute("UPDATE ai_provider_usage SET created_at=CURRENT_TIMESTAMP-INTERVAL '91 days'")
    cleanup_usage(cfg)
    with connect(cfg.owner_dsn) as db:
        assert db.execute("SELECT COUNT(*) AS n FROM ai_provider_usage").fetchone()["n"] == 0


@pytest.mark.asyncio
async def test_postgres_key_rotation_reaches_new_workflows_only(postgres_settings):
    cfg = postgres_settings
    edit_source(cfg, 7, "source-a", edit_payload())
    runtime = Settings(ai_provider_config_source="database", ai_provider_database_url=cfg.reader_dsn,
        ai_provider_encryption_key=MASTER, registry_ownership="private", primary_provider="gemini",
        gemini_quota_backend="memory", operational_limits_enabled=False)
    keys = []
    def handler(request):
        keys.append(request.headers["Authorization"])
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        existing = ModelGateway(runtime, client)
        await existing.complete([], {}, time.monotonic() + 3, "before")
        edit_source(cfg, 8, "source-a", edit_payload().model_copy(update={"revision": 1, "apiKey": "fake-new-key"}))
        await ModelGateway(runtime, client).complete([], {}, time.monotonic() + 3, "after")
        await existing.complete([], {}, time.monotonic() + 3, "in_flight")
    assert keys == ["Bearer fake-secret-only", "Bearer fake-new-key", "Bearer fake-secret-only"]
