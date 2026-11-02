"""Internal Gemini management. Responses and audits never contain provider secrets."""
import asyncio
import json
import logging
import re
import secrets
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from .config import GeminiSource
from .provider_registry import connect, decrypt_key, save_source

router = APIRouter(prefix="/v1/admin/gemini")
log = logging.getLogger("dictalab.provider_admin")


class SourceEdit(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    revision: int = Field(ge=0)
    name: str = Field(min_length=1, max_length=120)
    projectId: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,100}$")
    apiKey: str | None = Field(default=None, repr=False, max_length=512)
    model: str = Field(pattern=r"^(models/)?[a-zA-Z0-9_.-]{1,100}$")
    rpmLimit: int = Field(gt=0, le=2147483647)
    operatingRpm: int = Field(ge=0, le=2147483647)
    tpmLimit: int = Field(ge=0, le=2147483647)
    rpdLimit: int = Field(ge=0, le=2147483647)
    priority: int = Field(ge=0, le=2147483647)
    enabled: bool


class PoolEdit(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)
    revision: int = Field(ge=0)
    defaultModel: str | None = Field(default=None, pattern=r"^[a-zA-Z0-9_.-]{1,100}$")
    globalRpmLimit: int = Field(ge=0, le=2147483647)
    globalRpdLimit: int = Field(ge=0, le=2147483647)
    enabled: bool


def authorize(token, actor):
    from .main import settings
    if (not re.fullmatch(r"[!-~]{32,256}", settings.provider_admin_token)
            or not secrets.compare_digest((token or "").encode(), settings.provider_admin_token.encode())):
        raise HTTPException(401, "invalid management token")
    if not actor or not re.fullmatch(r"[0-9]{1,19}", actor) or not 0 < int(actor) < 2**63:
        raise HTTPException(400, "invalid admin identity")
    if settings.ai_provider_config_source != "database" or not settings.provider_admin_database_url:
        raise HTTPException(503, "Gemini management is not configured")
    def target(dsn):
        uri = urlsplit(dsn)
        host = "loopback" if uri.hostname in ("localhost", "127.0.0.1", "::1") else uri.hostname
        return host, uri.port or 5432, uri.path
    try:
        same_database = target(settings.provider_admin_database_url) == target(settings.ai_provider_database_url)
    except ValueError:
        same_database = False
    if not same_database:
        raise HTTPException(503, "Management and runtime must use the same AI registry database")
    return settings, int(actor)


def initialize_management(dsn):
    """Operator-only additive migration, independently checksummed from registry v1."""
    from .registry_migration import restricted_role
    import hashlib
    sql = Path(__file__).with_name("sql").joinpath("002_provider_management.sql").read_text(encoding="utf-8")
    checksum = hashlib.sha256(sql.encode()).hexdigest()
    with connect(dsn, readonly=False) as db:
        restricted_role(db)
        db.execute("SELECT pg_advisory_xact_lock(6028102802)")
        existing = db.execute("SELECT to_regclass('ai_provider_management_schema') AS name").fetchone()
        if existing["name"]:
            if db.execute("SELECT checksum FROM ai_provider_management_schema WHERE version=1").fetchone() != {"checksum": checksum}:
                raise ValueError("management migration checksum mismatch")
            return
        db.execute(sql)
        db.execute("CREATE TABLE ai_provider_management_schema(version INTEGER PRIMARY KEY, checksum VARCHAR(64) NOT NULL)")
        db.execute("REVOKE ALL ON ai_provider_management_schema FROM PUBLIC")
        db.execute("INSERT INTO ai_provider_management_schema VALUES (1,%s)", (checksum,))


def audit(db, actor, source, action, changes):
    db.execute("INSERT INTO ai_provider_audit(actor_id,source_id,action,changes) VALUES (%s,%s,%s,%s::jsonb)",
        (actor, source, action, json.dumps(changes, ensure_ascii=False)))


def edit_source(settings, actor, source_id, edit, *, create=None):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", source_id):
        raise HTTPException(400, "invalid source ID")
    if not edit.name.strip():
        raise HTTPException(400, "source name must not be blank")
    with connect(settings.provider_admin_database_url, readonly=False) as db:
        current = db.execute("SELECT revision FROM ai_provider_management WHERE singleton=1 FOR UPDATE").fetchone()["revision"]
        if current != edit.revision:
            raise HTTPException(409, "Configuration changed; reload before saving")
        previous = db.execute("SELECT a.*,l.name FROM ai_provider_accounts a LEFT JOIN ai_provider_labels l ON l.source_id=a.id WHERE a.id=%s", (source_id,)).fetchone()
        if previous and create is True:
            raise HTTPException(409, "Source ID already exists")
        if not previous and create is False:
            raise HTTPException(404, "Source no longer exists")
        if previous and previous["platform"] != "gemini":
            raise HTTPException(400, "source belongs to another platform")
        key_changed = bool(edit.apiKey)
        key = edit.apiKey or (decrypt_key(settings.ai_provider_encryption_key, previous) if previous else "")
        source = GeminiSource(id=source_id, projectId=edit.projectId, apiKey=key, model=edit.model,
            rpmLimit=edit.rpmLimit, operatingRpm=edit.operatingRpm, tpmLimit=edit.tpmLimit,
            rpdLimit=edit.rpdLimit, priority=edit.priority, enabled=edit.enabled)
        # The fixed Google endpoint cannot be replaced by arbitrary URLs from a web request.
        pool = db.execute("SELECT platform FROM ai_provider_pools WHERE platform='gemini'").fetchone()
        if not pool:
            raise HTTPException(409, "Create the Gemini pool with the operator CLI first")
        duplicate = db.execute("SELECT id FROM ai_provider_accounts WHERE platform='gemini' AND account_id=%s AND model=%s AND id<>%s",
            (source.project_id, source.model, source.id)).fetchone()
        if duplicate:
            raise HTTPException(409, "Project and model already have a source")
        save_source(db, settings.ai_provider_encryption_key, source)
        db.execute("INSERT INTO ai_provider_labels(source_id,name) VALUES (%s,%s) ON CONFLICT(source_id) DO UPDATE SET name=EXCLUDED.name",
            (source_id, edit.name.strip()))
        mapping = {"name": "name", "projectId": "account_id", "model": "model", "rpmLimit": "rpm_limit",
            "operatingRpm": "operating_rpm", "tpmLimit": "tpm_limit", "rpdLimit": "rpd_limit", "priority": "priority", "enabled": "enabled"}
        values = edit.model_dump(exclude={"apiKey", "revision"}) | {"model": source.model, "name": edit.name.strip()}
        changes = {field: {"before": previous.get(column) if previous else None, "after": values[field]}
            for field, column in mapping.items() if not previous or previous.get(column) != values[field]}
        if key_changed:
            changes["apiKey"] = "replaced" if previous else "added"
        audit(db, actor, source_id, "updated" if previous else "created", changes)
        db.execute("UPDATE ai_provider_management SET revision=revision+1 WHERE singleton=1")
        return {"revision": current + 1, "id": source_id, "applied": "new_requests"}


def snapshot(settings, source_id=None, period="day", before=None):
    with connect(settings.provider_admin_database_url) as db:
        db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        revision = db.execute("SELECT revision FROM ai_provider_management WHERE singleton=1").fetchone()["revision"]
        rows = db.execute("""SELECT a.id,a.account_id,a.model,a.rpm_limit,a.operating_rpm,a.tpm_limit,a.rpd_limit,
            a.priority,a.enabled,a.updated_at,l.name,last_error.outcome AS last_error,last_error.created_at AS last_error_at
            FROM ai_provider_accounts a LEFT JOIN ai_provider_labels l ON l.source_id=a.id
            LEFT JOIN LATERAL(SELECT outcome,created_at FROM ai_provider_usage WHERE source_id=a.id
                AND outcome<>'success' AND created_at>=CURRENT_TIMESTAMP-INTERVAL '90 days' ORDER BY created_at DESC LIMIT 1) last_error ON TRUE
            WHERE a.platform='gemini' ORDER BY a.priority,a.id""").fetchall()
        pool = db.execute("SELECT default_model,global_rpm_limit,global_rpd_limit,enabled FROM ai_provider_pools WHERE platform='gemini'").fetchone()
        usage = db.execute("""SELECT date_trunc(%s, created_at AT TIME ZONE 'Asia/Bangkok') AS bucket,source_id,model,
            COUNT(*) AS attempts,COUNT(*) FILTER(WHERE request_done) AS requests,
            COUNT(*) FILTER(WHERE outcome='success') AS successes,
            COUNT(*) FILTER(WHERE outcome<>'success') AS errors,
            COUNT(*) FILTER(WHERE outcome='429') AS rate_limited,
            SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,
            COUNT(*) FILTER(WHERE input_tokens IS NOT NULL AND output_tokens IS NOT NULL) AS measured_attempts
            FROM ai_provider_usage WHERE created_at>=CURRENT_TIMESTAMP-INTERVAL '90 days'
            AND (%s::varchar IS NULL OR source_id=%s)
            GROUP BY bucket,source_id,model ORDER BY bucket,source_id""", (period, source_id, source_id)).fetchall()
        events = db.execute("""SELECT id,created_at,actor_id,source_id,action,changes FROM ai_provider_audit
            WHERE (%s::varchar IS NULL OR source_id=%s) AND (%s::bigint IS NULL OR id<%s)
            ORDER BY id DESC LIMIT 51""", (source_id, source_id, before, before)).fetchall()
    for row in rows:
        row["name"] = row["name"] or row["id"]
        row["key_mask"] = "••••••••"
    status_available = True
    try:
        from .gemini_pool import quota_store
        cfg = settings.current_registry()
        store = quota_store(cfg, "gemini")
        by_id = {p.id: p for p in cfg.gemini_providers}
        for row in rows:
            source = by_id.get(row["id"])
            row["quota"] = dict(zip(("rpm_used", "tpm_reserved", "rpd_used", "global_rpm_used", "global_rpd_used", "cooldown_ms"),
                store.execute(source, op="status"))) if source else None
    except Exception:
        status_available = False
    return {"revision": revision, "sources": rows, "pool": pool, "usage": usage, "audit": events[:50],
        "nextAudit": events[49]["id"] if len(events) > 50 else None, "quotaAvailable": status_available,
        "limitsEnabled": settings.operational_limits_enabled, "retentionDays": 90, "timezone": "Asia/Bangkok",
        "usageEnabled": bool(settings.provider_telemetry_database_url)}


def edit_pool(settings, actor, edit):
    with connect(settings.provider_admin_database_url, readonly=False) as db:
        revision = db.execute("SELECT revision FROM ai_provider_management WHERE singleton=1 FOR UPDATE").fetchone()["revision"]
        if revision != edit.revision:
            raise HTTPException(409, "Configuration changed; reload before saving")
        previous = db.execute("SELECT * FROM ai_provider_pools WHERE platform='gemini'").fetchone()
        model = edit.defaultModel or (previous["default_model"] if previous else None)
        if not model:
            raise HTTPException(400, "Default model is required for a new Gemini pool")
        from .provider_registry import save_pool
        save_pool(db, "gemini", model, edit.globalRpmLimit, edit.globalRpdLimit, edit.enabled)
        changes = {field: {"before": previous[column] if previous else None, "after": value} for field, column, value in
            (("defaultModel", "default_model", model), ("globalRpmLimit", "global_rpm_limit", edit.globalRpmLimit),
             ("globalRpdLimit", "global_rpd_limit", edit.globalRpdLimit), ("enabled", "enabled", edit.enabled))
            if not previous or previous[column] != value}
        audit(db, actor, None, "pool_updated" if previous else "pool_created", changes)
        db.execute("UPDATE ai_provider_management SET revision=revision+1 WHERE singleton=1")
        return {"revision": revision + 1, "applied": "new_requests"}


def delete_source(settings, actor, source_id, revision):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", source_id):
        raise HTTPException(400, "invalid source ID")
    with connect(settings.provider_admin_database_url, readonly=False) as db:
        current = db.execute("SELECT revision FROM ai_provider_management WHERE singleton=1 FOR UPDATE").fetchone()["revision"]
        if current != revision:
            raise HTTPException(409, "Configuration changed; reload before deleting")
        previous = db.execute("SELECT a.id,a.account_id,a.model,a.enabled,l.name FROM ai_provider_accounts a LEFT JOIN ai_provider_labels l ON l.source_id=a.id WHERE a.id=%s AND a.platform='gemini'", (source_id,)).fetchone()
        if not previous:
            raise HTTPException(404, "Source no longer exists")
        if previous["enabled"]:
            raise HTTPException(409, "Disable the source before deleting it")
        db.execute("DELETE FROM ai_provider_labels WHERE source_id=%s", (source_id,))
        db.execute("DELETE FROM ai_provider_accounts WHERE id=%s", (source_id,))
        audit(db, actor, source_id, "deleted", {"name": previous["name"] or source_id,
            "projectId": previous["account_id"], "model": previous["model"]})
        db.execute("UPDATE ai_provider_management SET revision=revision+1 WHERE singleton=1")
        return {"revision": current + 1, "applied": "new_requests"}


def record_attempt(settings, request_id, source, outcome, done, usage, duration):
    if not settings.provider_telemetry_database_url or source.platform != "gemini":
        return
    try:
        with connect(settings.provider_telemetry_database_url, readonly=False) as db:
            db.execute("""INSERT INTO ai_provider_usage(request_id,source_id,model,outcome,request_done,input_tokens,output_tokens,duration_ms)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""", (request_id, source.id, source.model, outcome, done,
                    usage.get("input_tokens"), usage.get("output_tokens"), max(0, round(duration * 1000))))
            db.execute("DELETE FROM ai_provider_usage WHERE created_at<CURRENT_TIMESTAMP-INTERVAL '90 days'")
    except Exception:
        # Telemetry failure after HTTP must never cause a duplicate provider call.
        log.warning("provider_usage_unavailable")


def finish_request(settings, request_id):
    if not settings.provider_telemetry_database_url:
        return
    try:
        with connect(settings.provider_telemetry_database_url, readonly=False) as db:
            db.execute("UPDATE ai_provider_usage SET request_done=TRUE WHERE id=(SELECT MAX(id) FROM ai_provider_usage WHERE request_id=%s)", (request_id,))
    except Exception:
        log.warning("provider_usage_unavailable")


def cleanup_usage(settings):
    if settings.provider_telemetry_database_url:
        try:
            with connect(settings.provider_telemetry_database_url, readonly=False) as db:
                db.execute("DELETE FROM ai_provider_usage WHERE created_at<CURRENT_TIMESTAMP-INTERVAL '90 days'")
        except Exception:
            log.warning("provider_usage_cleanup_unavailable")


@router.get("")
async def get_sources(x_provider_admin_token: str | None = Header(default=None), x_admin_actor: str | None = Header(default=None),
                      source: str | None = Query(default=None, pattern=r"^[a-zA-Z0-9_-]{1,64}$"),
                      period: str = Query(default="day", pattern=r"^(day|month)$"), before: int | None = Query(default=None, gt=0)):
    settings, _ = authorize(x_provider_admin_token, x_admin_actor)
    try:
        return await asyncio.to_thread(snapshot, settings, source, period, before)
    except Exception:
        raise HTTPException(503, "Gemini management database unavailable") from None


@router.post("/{source_id}")
async def create_source(source_id: str, edit: SourceEdit, x_provider_admin_token: str | None = Header(default=None),
                        x_admin_actor: str | None = Header(default=None)):
    return await write_source(source_id, edit, x_provider_admin_token, x_admin_actor, create=True)


@router.put("/{source_id}")
async def put_source(source_id: str, edit: SourceEdit, x_provider_admin_token: str | None = Header(default=None),
                     x_admin_actor: str | None = Header(default=None)):
    return await write_source(source_id, edit, x_provider_admin_token, x_admin_actor, create=False)


async def write_source(source_id, edit, token, actor_header, *, create):
    settings, actor = authorize(token, actor_header)
    try:
        return await asyncio.to_thread(edit_source, settings, actor, source_id, edit, create=create)
    except HTTPException:
        raise
    except ValueError:
        raise HTTPException(400, "Invalid Gemini source or key") from None
    except Exception:
        raise HTTPException(503, "Gemini configuration could not be saved") from None


@router.delete("/{source_id}")
async def remove_source(source_id: str, revision: int = Query(ge=0), x_provider_admin_token: str | None = Header(default=None),
                        x_admin_actor: str | None = Header(default=None)):
    settings, actor = authorize(x_provider_admin_token, x_admin_actor)
    try:
        return await asyncio.to_thread(delete_source, settings, actor, source_id, revision)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, "Gemini source could not be deleted") from None


@router.put("/pool/config")
async def put_pool(edit: PoolEdit, x_provider_admin_token: str | None = Header(default=None),
                   x_admin_actor: str | None = Header(default=None)):
    settings, actor = authorize(x_provider_admin_token, x_admin_actor)
    try:
        return await asyncio.to_thread(edit_pool, settings, actor, edit)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, "Gemini pool could not be saved") from None


@router.post("/{source_id}/probe")
async def probe_source(source_id: str, x_provider_admin_token: str | None = Header(default=None),
                       x_admin_actor: str | None = Header(default=None)):
    settings, actor = authorize(x_provider_admin_token, x_admin_actor)
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", source_id):
        raise HTTPException(400, "invalid source ID")
    def load():
        with connect(settings.provider_admin_database_url) as db:
            row = db.execute("SELECT * FROM ai_provider_accounts WHERE platform='gemini' AND id=%s", (source_id,)).fetchone()
            if not row:
                raise HTTPException(404, "source not found")
            return row, decrypt_key(settings.ai_provider_encryption_key, row)
    try:
        row, key = await asyncio.to_thread(load)
        from .provider_registry import validate_endpoint
        validate_endpoint("gemini", row["endpoint"])
        if not row["endpoint"].startswith("https://generativelanguage.googleapis.com/"):
            raise HTTPException(400, "probe requires the Google endpoint")
        async with httpx.AsyncClient(timeout=8, follow_redirects=False) as client:
            response = await client.get(row["endpoint"].removesuffix("/chat/completions") + "/models",
                headers={"Authorization": "Bearer " + key})
            available = response.status_code == 200 and any(item.get("id", "").removeprefix("models/") == row["model"]
                for item in response.json().get("data", []))
        result = {"httpStatus": response.status_code, "modelAvailable": available}
        def save():
            with connect(settings.provider_admin_database_url, readonly=False) as db:
                audit(db, actor, source_id, "probe", result)
        await asyncio.to_thread(save)
        return result
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, "Gemini connection check failed") from None
