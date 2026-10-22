"""Run bounded JSONL Gemini jobs with durable checkpoints and shared project quotas.

Input: id, messages, optional schema. Model/output budget come from server settings.
No credentials belong in input, results or command-line arguments.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services" / "ai-generation"))

import httpx
from jsonschema import Draft202012Validator, SchemaError
from app.config import Settings
from app.gemini_pool import QuotaUnavailable
from app.model_gateway import ModelGateway
from app.providers.base import ProviderHttpError


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def load_jobs(path):
    rows, ids = [], set()
    with Path(path).open(encoding="utf-8-sig") as source:
        for line in source:
            if not line.strip():
                continue
            if len(line.encode("utf-8")) > 64000:
                raise ValueError("batch input row exceeds 64 KB")
            row = json.loads(line)
            if not isinstance(row, dict) or set(row) - {"id", "messages", "schema"}:
                raise ValueError("unsupported batch fields")
            ident = row.get("id")
            if not isinstance(ident, str) or not 1 <= len(ident) <= 160 or ident in ids:
                raise ValueError("batch IDs must be unique non-empty strings")
            messages = row.get("messages")
            if (not isinstance(messages, list) or not messages or any(
                    not isinstance(m, dict) or set(m) != {"role", "content"} or
                    m["role"] not in ("system", "user", "assistant") or not isinstance(m["content"], str)
                    for m in messages)):
                raise ValueError("invalid batch messages")
            if not isinstance(row.get("schema", {}), dict):
                raise ValueError("batch schema must be an object")
            schema = row.get("schema", {"type": "object"})
            # Offline jobs cannot resolve remote schema references or read local files.
            if '"$ref"' in canonical(schema) or '"$dynamicRef"' in canonical(schema):
                raise ValueError("batch schema references are unsupported; inline the schema")
            Draft202012Validator.check_schema(schema)
            ids.add(ident)
            rows.append(row)
    return rows


class BatchLedger:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=5)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA secure_delete=ON")
        self.db.execute("""CREATE TABLE IF NOT EXISTS batch_jobs (
            id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, status TEXT NOT NULL,
            owner TEXT, updated REAL NOT NULL, result TEXT, reason TEXT)""")

    def register(self, rows, settings):
        # Schema/model/context changes must never reuse a previous result.
        with self.db:
            for row in rows:
                fingerprint = hashlib.sha256(canonical({"row": row, "model": settings.active_cloud_model,
                    "maxOutput": settings.num_predict, "temperature": settings.temperature}).encode()).hexdigest()
                old = self.db.execute("SELECT fingerprint FROM batch_jobs WHERE id=?", (row["id"],)).fetchone()
                if old and old[0] != fingerprint:
                    raise ValueError("batch ID reused with different input/config")
                self.db.execute("INSERT OR IGNORE INTO batch_jobs(id,fingerprint,status,updated) VALUES (?,?,'PENDING',?)",
                    (row["id"], fingerprint, time.time()))

    def recover(self):
        # Previous owners may still be waiting on HTTP. Fence them rather than replaying.
        with self.db:
            self.db.execute("UPDATE batch_jobs SET status='UNKNOWN',reason='OUTCOME_UNKNOWN' WHERE status='RUNNING' AND updated<?",
                (time.time() - 605,))

    def claim(self, ident, owner, max_calls):
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            used = self.db.execute("SELECT COUNT(*) FROM batch_jobs WHERE owner=? AND status IN ('RUNNING','DONE','FAILED','UNKNOWN')", (owner,)).fetchone()[0]
            if used >= max_calls:
                return False
            return self.db.execute("UPDATE batch_jobs SET status='RUNNING',owner=?,updated=? WHERE id=? AND status='PENDING'",
                (owner, time.time(), ident)).rowcount == 1

    def finish(self, ident, owner, status, result=None, reason=None):
        with self.db:
            self.db.execute("UPDATE batch_jobs SET status=?,result=?,reason=?,updated=? WHERE id=? AND owner=? AND status='RUNNING'",
                (status, canonical(result) if result is not None else None, reason, time.time(), ident, owner))

    def summary(self):
        return dict(self.db.execute("SELECT status,COUNT(*) FROM batch_jobs GROUP BY status").fetchall())

    def close(self):
        self.db.close()


async def run_batch(rows, settings, ledger, *, concurrency=3, max_calls=100, max_seconds=3600, client=None):
    import uuid
    if not settings.gemini_quota_enabled or settings.selected_provider != "gemini":
        raise ValueError("batch runner requires an enabled Gemini pool")
    if not 1 <= concurrency <= 5 or max_calls < 1 or max_seconds < 1:
        raise ValueError("invalid batch execution bounds")
    ledger.register(rows, settings)
    ledger.recover()
    owner = str(uuid.uuid4())
    deadline = time.monotonic() + max_seconds
    semaphore = asyncio.Semaphore(concurrency)
    own_client = client is None
    client = client or httpx.AsyncClient(follow_redirects=False)
    async def one(row):
        async with semaphore:
            while time.monotonic() < deadline:
                if not ledger.claim(row["id"], owner, max_calls):
                    return
                gateway = ModelGateway(settings, client)
                try:
                    result, provider = await gateway.complete(row["messages"], row.get("schema", {"type": "object"}),
                        min(deadline, time.monotonic() + settings.timeout_seconds), "batch")
                    Draft202012Validator(row.get("schema", {"type": "object"})).validate(result)
                except QuotaUnavailable as unavailable:
                    ledger.finish(row["id"], owner, "PENDING")
                    remaining = deadline - time.monotonic()
                    if unavailable.retry_after >= remaining:
                        return
                    await asyncio.sleep(min(unavailable.retry_after, 30))
                    continue
                except ProviderHttpError as error:
                    # Rejected 429/5xx are checkpointed; rerun requires explicit operator action.
                    ledger.finish(row["id"], owner, "FAILED", reason=f"HTTP_{error.status}")
                except asyncio.CancelledError:
                    ledger.finish(row["id"], owner, "UNKNOWN", reason="OUTCOME_UNKNOWN")
                    raise
                except (TimeoutError, httpx.TransportError):
                    ledger.finish(row["id"], owner, "UNKNOWN", reason="OUTCOME_UNKNOWN")
                except Exception:
                    ledger.finish(row["id"], owner, "FAILED", reason="VALIDATION_OR_PROVIDER_ERROR")
                else:
                    ledger.finish(row["id"], owner, "DONE", result={"model": gateway.last_model,
                        "provider": provider, "sourceId": gateway.last_source_id, "task": result})
                return
    try:
        # Bound task creation too: each chunk has at most concurrency scheduled jobs.
        for offset in range(0, len(rows), concurrency):
            await asyncio.gather(*(one(row) for row in rows[offset:offset + concurrency]))
            if time.monotonic() >= deadline:
                break
        return ledger.summary()
    finally:
        if own_client:
            await client.aclose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--max-calls", type=int, default=100)
    parser.add_argument("--max-seconds", type=int, default=3600)
    args = parser.parse_args()
    ledger = None
    try:
        settings = Settings(primary_provider="gemini")
        rows = load_jobs(args.input)
        ledger = BatchLedger(args.ledger)
        print(canonical(asyncio.run(run_batch(rows, settings, ledger, concurrency=args.concurrency,
            max_calls=args.max_calls, max_seconds=args.max_seconds))))
        return 0
    except (ValueError, OSError, sqlite3.Error, SchemaError):
        # Config validation can contain secrets; report no input/error repr.
        print("Batch failed: verify runtime config, input and private ledger.", file=sys.stderr)
        return 2
    finally:
        if ledger:
            ledger.close()


if __name__ == "__main__":
    raise SystemExit(main())
