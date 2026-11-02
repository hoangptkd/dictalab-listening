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
from app.provider_admin import SourceEdit, PoolEdit, authorize, edit_source, edit_pool, snapshot, initialize_management, record_attempt, finish_request, cleanup_usage
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
        db.execute(sql.SQL("GRANT INSERT,UPDATE ON ai_provider_pools TO {}").format(sql.Identifier(manager)))
        db.execute(sql.SQL("GRANT DELETE ON ai_provider_accounts,ai_provider_labels TO {}").format(sql.Identifier(manager)))
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


@pytest.fixture
def admin_client(postgres_settings, monkeypatch):
    from app import main
    cfg = postgres_settings
    runtime = Settings(ai_provider_config_source="database", ai_provider_database_url=cfg.reader_dsn,
        ai_provider_encryption_key=MASTER, registry_ownership="private", primary_provider="gemini",
        gemini_quota_backend="memory", provider_admin_token="m" * 40, service_token="s" * 40,
        provider_admin_database_url=cfg.provider_admin_database_url,
        provider_telemetry_database_url=cfg.provider_telemetry_database_url)
    monkeypatch.setattr(main, "settings", runtime)
    with TestClient(main.app) as client:
        client.headers.update({"X-Provider-Admin-Token": "m" * 40, "X-Admin-Actor": "7"})
        yield client


def test_api_create_cannot_overwrite_and_update_cannot_recreate_missing_source(admin_client):
    client = admin_client
    payload = edit_payload().model_dump()
    assert client.post("/v1/admin/gemini/source-a", json=payload).status_code == 200
    payload.update(revision=1, name="Accidental overwrite", apiKey="fake-wrong-key")
    assert client.post("/v1/admin/gemini/source-a", json=payload).status_code == 409
    assert client.put("/v1/admin/gemini/missing", json=payload).status_code == 404
    data = client.get("/v1/admin/gemini").json()
    assert data["revision"] == 1 and data["sources"][0]["name"] != "Accidental overwrite"


def test_api_creates_pool_changes_default_and_deletes_disabled_source_preserving_history(admin_client, postgres_settings):
    client, cfg = admin_client, postgres_settings
    with connect(cfg.owner_dsn, readonly=False) as db:
        db.execute("DELETE FROM ai_provider_pools WHERE platform='gemini'")
    pool = dict(revision=0, defaultModel="gemini-test", globalRpmLimit=0, globalRpdLimit=0, enabled=True)
    assert client.put("/v1/admin/gemini/pool/config", json=pool).status_code == 200
    payload = edit_payload().model_dump() | {"revision": 1, "model": "gemini-test"}
    assert client.post("/v1/admin/gemini/source-a", json=payload).status_code == 200
    assert client.delete("/v1/admin/gemini/source-a?revision=2").status_code == 409
    source = config().gemini_providers[0].model_copy(update={"id": "source-a"})
    record_attempt(cfg, uuid4(), source, "success", True, {}, .01)
    payload.update(revision=2, enabled=False, apiKey=None)
    assert client.put("/v1/admin/gemini/source-a", json=payload).status_code == 200
    assert client.delete("/v1/admin/gemini/source-a?revision=2").status_code == 409
    assert client.delete("/v1/admin/gemini/source-a?revision=3").status_code == 200
    data = client.get("/v1/admin/gemini").json()
    assert data["sources"] == [] and len(data["usage"]) == 1
    assert data["revision"] == 4 and data["audit"][0]["action"] == "deleted"
    assert "fake-secret-only" not in json.dumps(data)
    assert client.delete("/v1/admin/gemini/source-a?revision=4").status_code == 404
    assert client.delete("/v1/admin/gemini/source-a?revision=-1").status_code == 400


def test_api_rejects_oversized_actor_without_server_error(admin_client):
    response = admin_client.get("/v1/admin/gemini", headers={"X-Admin-Actor": "9" * 5000})
    assert response.status_code == 400


@pytest.mark.parametrize("settings,token,actor,expected", [
    ({}, "bad", "7", 401), ({}, "m" * 40, "0", 400), ({}, "m" * 40, "9223372036854775808", 400),
    ({}, "m" * 40, "٧", 400), ({"ai_provider_config_source": "env"}, "m" * 40, "7", 503),
    ({"provider_admin_database_url": "postgresql://127.0.0.1:bad/db"}, "m" * 40, "7", 503),
    ({"provider_admin_database_url": "postgresql://127.0.0.1/other"}, "m" * 40, "7", 503)])
def test_authorization_rejects_bad_identity_and_database_target(monkeypatch, settings, token, actor, expected):
    from app import main
    cfg = SimpleNamespace(provider_admin_token="m" * 40, ai_provider_config_source="database",
        provider_admin_database_url="postgresql://localhost/db", ai_provider_database_url="postgresql://127.0.0.1/db")
    for name, value in settings.items():
        setattr(cfg, name, value)
    monkeypatch.setattr(main, "settings", cfg)
    with pytest.raises(HTTPException) as error:
        authorize(token, actor)
    assert error.value.status_code == expected


@pytest.mark.parametrize("path,payload", [
    ("source-a", {"name": "   "}), ("source-a", {"rpmLimit": 1, "operatingRpm": 2}),
    ("source-a", {"apiKey": "bad\nkey"}), ("source-a", {"apiKey": None}),
    ("bad.id", {}), ("source-a", {"unknown": "fake-secret-only"})])
def test_api_validation_is_atomic_and_redacts_inputs(admin_client, path, payload):
    response = admin_client.post("/v1/admin/gemini/" + path, json=edit_payload().model_dump() | payload)
    assert response.status_code == 400
    assert "fake-secret-only" not in response.text
    result = admin_client.get("/v1/admin/gemini").json()
    assert result["revision"] == 0 and result["sources"] == [] and result["audit"] == []


def test_api_filters_paginates_and_retains_disabled_quota(admin_client, postgres_settings, monkeypatch):
    cfg = postgres_settings
    first = edit_payload()
    edit_source(cfg, 7, "source-a", first)
    edit_source(cfg, 8, "source-b", first.model_copy(update={"revision": 1, "projectId": "project-b"}))
    for index in range(2, 54):
        edit_source(cfg, 7, "source-a", first.model_copy(update={"revision": index, "enabled": False}))
    result = admin_client.get("/v1/admin/gemini?source=source-a&period=month").json()
    assert len(result["sources"]) == 2 and len(result["audit"]) == 50 and result["nextAudit"]
    older = admin_client.get(f"/v1/admin/gemini?source=source-a&before={result['nextAudit']}").json()
    assert len(older["audit"]) == 3
    assert not {item["id"] for item in result["audit"]} & {item["id"] for item in older["audit"]}
    class Store:
        def execute(self, source, **kwargs):
            return [2, 20, 5, 10, 50, 5000]
    monkeypatch.setattr("app.gemini_pool.quota_store", lambda *args: Store())
    state = snapshot(__import__("app.main", fromlist=["settings"]).settings)
    assert all(row["quota"]["rpd_used"] == 5 for row in state["sources"])
    assert state["quotaAvailable"]
    assert admin_client.get("/v1/admin/gemini?period=year").status_code == 400
    assert admin_client.get("/v1/admin/gemini?before=0").status_code == 400


@pytest.mark.parametrize("status,listed,expected", [(200, True, True), (200, False, False), (401, False, False), (429, False, False)])
def test_api_probe_is_bounded_redacted_and_audited(admin_client, monkeypatch, status, listed, expected):
    admin_client.post("/v1/admin/gemini/source-a", json=edit_payload().model_dump())
    requests = []
    original_client = httpx.AsyncClient
    def handler(request):
        requests.append(request)
        return httpx.Response(status, json={"data": [{"id": "models/gemini-3.5-flash-lite"}] if listed else []})
    monkeypatch.setattr("app.provider_admin.httpx.AsyncClient", lambda **kwargs:
        original_client(transport=httpx.MockTransport(handler), **kwargs))
    response = admin_client.post("/v1/admin/gemini/source-a/probe")
    assert response.status_code == 200 and response.json() == {"httpStatus": status, "modelAvailable": expected}
    assert len(requests) == 1 and str(requests[0].url) == "https://generativelanguage.googleapis.com/v1beta/openai/models"
    assert requests[0].headers["Authorization"] == "Bearer fake-secret-only"
    data = admin_client.get("/v1/admin/gemini").json()
    assert data["revision"] == 1 and data["audit"][0]["action"] == "probe"
    assert "fake-secret-only" not in response.text + json.dumps(data)
    assert admin_client.post("/v1/admin/gemini/missing/probe").status_code == 404


def test_database_and_probe_failures_return_safe_errors(admin_client, postgres_settings, monkeypatch):
    import app.provider_admin as admin
    admin_client.post("/v1/admin/gemini/source-a", json=edit_payload().model_dump())
    original_client = httpx.AsyncClient
    def fail(_request):
        raise httpx.ReadTimeout("private-provider-input")
    monkeypatch.setattr(admin.httpx, "AsyncClient", lambda **kwargs: original_client(transport=httpx.MockTransport(fail), **kwargs))
    assert admin_client.post("/v1/admin/gemini/source-a/probe").status_code == 503
    def fail_db(*args, **kwargs):
        raise RuntimeError("private-database-credential")
    monkeypatch.setattr(admin, "connect", fail_db)
    responses = [admin_client.get("/v1/admin/gemini"),
        admin_client.put("/v1/admin/gemini/source-a", json=edit_payload().model_dump()),
        admin_client.delete("/v1/admin/gemini/source-a?revision=1"),
        admin_client.put("/v1/admin/gemini/pool/config", json=dict(revision=1, globalRpmLimit=0, globalRpdLimit=0, enabled=False))]
    assert all(response.status_code == 503 and "private-" not in response.text for response in responses)


def test_postgres_concurrent_edits_have_one_winner(postgres_settings):
    from concurrent.futures import ThreadPoolExecutor
    cfg = postgres_settings
    def edit(index):
        try:
            edit_source(cfg, 7, f"source-{index}", edit_payload())
            return 200
        except HTTPException as error:
            return error.status_code
    with ThreadPoolExecutor(max_workers=2) as executor:
        assert sorted(executor.map(edit, range(2))) == [200, 409]
    data = snapshot(cfg)
    assert data["revision"] == 1 and len(data["sources"]) == len(data["audit"]) == 1


def test_postgres_audit_failure_rolls_back_configuration(postgres_settings):
    from urllib.parse import urlsplit
    from psycopg import sql
    from psycopg.errors import InsufficientPrivilege
    cfg = postgres_settings
    with connect(cfg.owner_dsn, readonly=False) as db:
        db.execute(sql.SQL("REVOKE INSERT ON ai_provider_audit FROM {}").format(sql.Identifier(urlsplit(cfg.provider_admin_database_url).username)))
    with pytest.raises(InsufficientPrivilege):
        edit_source(cfg, 7, "source-a", edit_payload())
    data = snapshot(cfg)
    assert data["revision"] == 0 and data["sources"] == []


def test_api_duplicate_project_and_model_cannot_create_another_quota(admin_client):
    payload = edit_payload().model_dump()
    assert admin_client.post("/v1/admin/gemini/source-a", json=payload).status_code == 200
    payload["revision"] = 1
    assert admin_client.post("/v1/admin/gemini/source-b", json=payload).status_code == 409
    result = admin_client.get("/v1/admin/gemini").json()
    assert len(result["sources"]) == len(result["audit"]) == 1 and result["revision"] == 1


def test_api_quota_failure_preserves_masked_configuration(admin_client, monkeypatch):
    from app.gemini_pool import QuotaUnavailable
    admin_client.post("/v1/admin/gemini/source-a", json=edit_payload().model_dump())
    def fail(*args):
        raise QuotaUnavailable()
    monkeypatch.setattr("app.gemini_pool.quota_store", fail)
    result = admin_client.get("/v1/admin/gemini").json()
    assert result["quotaAvailable"] is False and result["sources"][0]["key_mask"]
    assert "fake-secret-only" not in json.dumps(result)


def test_postgres_finish_request_cleanup_and_migration_guards(postgres_settings):
    cfg = postgres_settings
    request, source = uuid4(), config().gemini_providers[0]
    record_attempt(cfg, request, source, "429", False, {}, .01)
    finish_request(cfg, request)
    assert snapshot(cfg)["usage"][0]["requests"] == 1
    with connect(cfg.owner_dsn, readonly=False) as db:
        db.execute("UPDATE ai_provider_management_schema SET checksum='changed'")
    with pytest.raises(ValueError, match="checksum"):
        initialize_management(cfg.owner_dsn)
    cfg.provider_telemetry_database_url = cfg.reader_dsn
    # Restricted roles cannot write; telemetry loss must never fail a sent request.
    record_attempt(cfg, request, source, "success", True, {}, .01)
    finish_request(cfg, request)
    cleanup_usage(cfg)
    assert snapshot(cfg)["usage"][0]["attempts"] == 1


@pytest.mark.asyncio
async def test_token_usage_is_reset_when_reusing_provider_after_failure():
    from app.providers.openai_compatible import OpenAiCompatibleProvider
    responses = [httpx.Response(200, json={"usage": {"prompt_tokens": 10, "completion_tokens": 5},
        "choices": [{"message": {"content": "{}"}}]}), httpx.Response(500)]
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: responses.pop(0))) as client:
        provider = OpenAiCompatibleProvider(base_url="https://example.com/v1", api_key="fake", model="test", client=client)
        await provider.complete([], {}, time.monotonic() + 2, "test")
        assert provider.last_usage == {"input_tokens": 10, "output_tokens": 5}
        with pytest.raises(ProviderHttpError):
            await provider.complete([], {}, time.monotonic() + 2, "test")
        assert provider.last_usage == {}


@pytest.mark.asyncio
async def test_429_with_other_sources_cooling_finishes_logical_request_once(monkeypatch):
    from app.gemini_pool import quota_store
    cfg, events, finished, calls = config(), [], [], []
    cfg.gemini_providers = cfg.gemini_providers[:2]
    quota_store(cfg).report(cfg.gemini_providers[1], "429", 100)
    monkeypatch.setattr("app.provider_admin.record_attempt", lambda *args: events.append(args))
    monkeypatch.setattr("app.provider_admin.finish_request", lambda _, request: finished.append(request))
    def handler(request):
        calls.append(request)
        return httpx.Response(429, headers={"Retry-After": "31"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ProviderHttpError):
            await ModelGateway(cfg, client).complete([], {}, time.monotonic() + 3, "test")
    assert len(calls) == len(events) == 1 and events[0][4] is False
    assert finished == [events[0][1]]


def test_postgres_operator_updates_bump_revision_and_never_audit_keys(postgres_settings):
    import importlib.util
    from pathlib import Path
    path = Path(__file__).resolve().parents[3] / "tools/ai-eval/provider_accounts.py"
    spec = importlib.util.spec_from_file_location("audit_operator_accounts", path)
    accounts = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(accounts)
    cfg = postgres_settings
    source = config().gemini_providers[0]
    accounts.apply_accounts(cfg.owner_dsn, MASTER, {"accounts": [source.model_dump()]})
    data = snapshot(cfg)
    assert data["revision"] == 1 and data["audit"][0]["action"] == "operator_updated"
    assert source.api_key not in json.dumps(data, default=str)


def test_postgres_pool_model_and_limits_refresh_only_for_new_snapshots(postgres_settings):
    cfg = postgres_settings
    runtime = Settings(ai_provider_config_source="database", ai_provider_database_url=cfg.reader_dsn,
        ai_provider_encryption_key=MASTER, registry_ownership="private", primary_provider="gemini", gemini_quota_backend="memory")
    existing = runtime.current_registry()
    edit_pool(cfg, 7, PoolEdit(revision=0, defaultModel="gemini-next", globalRpmLimit=30, globalRpdLimit=100, enabled=True))
    fresh = runtime.current_registry()
    assert existing.active_cloud_model == "gemini-3.5-flash-lite"
    assert fresh.active_cloud_model == "gemini-next" and fresh.registry_pools["gemini"]["global_rpm_limit"] == 30


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
