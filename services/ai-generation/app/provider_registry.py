"""PostgreSQL account registry. Never log rows, DSNs, encryption keys or plaintext keys."""
import base64
import os
import re
from urllib.parse import urlsplit

import psycopg
from psycopg.rows import dict_row
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PLATFORMS = ("gemini", "deepseek", "openai_compatible")


def connect(dsn, *, readonly=True):
    uri = urlsplit(dsn)
    if uri.scheme not in ("postgres", "postgresql") or not uri.hostname:
        raise ValueError("Provider registry requires a PostgreSQL URL")
    # 'postgres' is the private service DNS on this repository's local Compose network.
    if uri.hostname not in ("localhost", "127.0.0.1", "::1", "postgres") and "sslmode=verify-full" not in uri.query.split("&"):
        raise ValueError("Remote provider registry requires verified TLS")
    return psycopg.connect(dsn, connect_timeout=3, row_factory=dict_row,
        options="-c statement_timeout=5000 -c lock_timeout=3000" + (" -c default_transaction_read_only=on" if readonly else ""))


def associated_data(row):
    return "\n".join(("dictalab-ai-provider:v1", row["id"], row["platform"], row["account_id"], row["model"], row["endpoint"])).encode()


def validate_endpoint(platform, endpoint):
    uri = urlsplit(endpoint)
    if not uri.path.endswith("/chat/completions"):
        raise ValueError("Provider endpoint must be a full chat completions URL")
    expected = {"gemini": "generativelanguage.googleapis.com", "deepseek": "api.deepseek.com"}.get(platform)
    if expected and uri.hostname not in (expected, "localhost", "127.0.0.1", "::1"):
        raise ValueError("Provider endpoint does not match platform")


def cipher(master):
    try:
        key = base64.b64decode(master, validate=True)
        if len(key) != 32:
            raise ValueError()
        return AESGCM(key)
    except Exception:
        raise ValueError("Invalid provider encryption key") from None


def encrypt_key(master, row, secret):
    nonce = os.urandom(12)
    return "v1:" + base64.b64encode(nonce + cipher(master).encrypt(nonce, secret.encode(), associated_data(row))).decode()


def decrypt_key(master, row):
    try:
        envelope = row["api_key_ciphertext"]
        if not envelope.startswith("v1:"):
            raise ValueError()
        raw = base64.b64decode(envelope[3:], validate=True)
        return cipher(master).decrypt(raw[:12], raw[12:], associated_data(row)).decode()
    except Exception:
        raise ValueError("Provider secret cannot be decrypted") from None


def load_registry(dsn, master, *, require_private=False):
    try:
        cipher(master)
        with connect(dsn) as db:
            db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            if require_private:
                from .registry_migration import verify_private
                verify_private(db, runtime=True)
            with db.cursor() as cursor:
                cursor.execute("SELECT platform, default_model, global_rpm_limit, global_rpd_limit, enabled FROM ai_provider_pools ORDER BY platform")
                pools = {row["platform"]: row for row in cursor.fetchall()}
                cursor.execute("SELECT * FROM ai_provider_accounts ORDER BY priority, id")
                rows = cursor.fetchall()
        for row in rows:
            validate_endpoint(row["platform"], row["endpoint"])
        from .config import GeminiSource
        sources = [GeminiSource(id=row["id"], platform=row["platform"], project_id=row["account_id"],
            model=row["model"], url=row["endpoint"], api_key=decrypt_key(master, row),
            rpm_limit=row["rpm_limit"], operating_rpm=row["operating_rpm"], tpm_limit=row["tpm_limit"],
            rpd_limit=row["rpd_limit"], priority=row["priority"], enabled=row["enabled"] and pools[row["platform"]]["enabled"])
            for row in rows]
        if not pools:
            raise ValueError()
        return pools, sources
    except Exception:
        # Driver and validation exceptions can contain DSNs or full rows.
        raise ValueError("AI provider database unavailable or invalid; environment fallback is disabled") from None


def save_pool(db, platform, model, rpm, rpd, enabled):
    if platform not in PLATFORMS or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,100}", model) or min(rpm, rpd) < 0:
        raise ValueError("Invalid provider pool")
    db.execute("""INSERT INTO ai_provider_pools(platform,default_model,global_rpm_limit,global_rpd_limit,enabled)
        VALUES (%s,%s,%s,%s,%s) ON CONFLICT (platform) DO UPDATE SET
        default_model=EXCLUDED.default_model,global_rpm_limit=EXCLUDED.global_rpm_limit,
        global_rpd_limit=EXCLUDED.global_rpd_limit,enabled=EXCLUDED.enabled,updated_at=CURRENT_TIMESTAMP""",
        (platform, model, rpm, rpd, enabled))


def save_source(db, master, source):
    # Validate before encrypting; bind endpoint/model/account identity to the ciphertext.
    from .config import GeminiSource
    source = GeminiSource.model_validate(source)
    validate_endpoint(source.platform, source.url)
    row = dict(id=source.id, platform=source.platform, account_id=source.project_id, model=source.model, endpoint=source.url)
    encrypted = encrypt_key(master, row, source.api_key)
    db.execute("""INSERT INTO ai_provider_accounts(id,platform,account_id,model,endpoint,api_key_ciphertext,
        rpm_limit,operating_rpm,tpm_limit,rpd_limit,enabled,priority) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (id) DO UPDATE SET platform=EXCLUDED.platform,account_id=EXCLUDED.account_id,
        model=EXCLUDED.model,endpoint=EXCLUDED.endpoint,api_key_ciphertext=EXCLUDED.api_key_ciphertext,
        rpm_limit=EXCLUDED.rpm_limit,operating_rpm=EXCLUDED.operating_rpm,tpm_limit=EXCLUDED.tpm_limit,
        rpd_limit=EXCLUDED.rpd_limit,enabled=EXCLUDED.enabled,priority=EXCLUDED.priority,updated_at=CURRENT_TIMESTAMP""",
        (source.id, source.platform, source.project_id, source.model, source.url, encrypted, source.rpm_limit,
         source.operating_rpm, source.tpm_limit, source.rpd_limit, source.enabled, source.priority))
