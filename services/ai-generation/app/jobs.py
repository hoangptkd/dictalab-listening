"""Versioned RabbitMQ jobs and a private SQLite outbox/result ledger, separate from domain DBs."""
import hashlib
import json
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from . import coach, review, tts, conversation_demo
from .coach import StrictModel
from .config import CLOUD_PROVIDERS

QUEUE = "dictalab.ai.platform.v1"
DLQ = QUEUE + ".dead"
Payload = Annotated[coach.CoachInput | review.ReviewInput | tts.TtsInput | conversation_demo.ConversationDemoInput, Field(discriminator="kind")]


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


class JobRequest(StrictModel):
    schemaVersion: Literal["1"] = "1"
    jobId: UUID
    requestId: UUID
    userScope: str = Field(pattern=r"^[a-f0-9]{64}$")
    idempotencyKey: str = Field(min_length=1, max_length=160, pattern=r"^[a-zA-Z0-9:./_-]+$")
    deadline: datetime
    payload: Payload

    @model_validator(mode="after")
    def timezone_required(self):
        if self.deadline.tzinfo is None:
            raise ValueError("deadline must include a timezone")
        return self


class JobEvent(JobRequest):
    attempt: int = Field(default=1, ge=1, le=3)
    payloadHash: str = Field(pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def check_hash(self):
        if digest(self.payload.model_dump()) != self.payloadHash:
            raise ValueError("payload hash mismatch")
        return self


def capability_metadata(payload, settings):
    if payload.kind == "conversation_demo":
        return conversation_demo.metadata(settings)
    module = {"coach": coach, "review": review, "tts": tts}[payload.kind]
    instruction = getattr(module, "INSTRUCTION", "Cartesia PCM 24000 mono 16-bit WAV")
    return {"provider": "cartesia" if payload.kind == "tts" else settings.selected_provider,
            "model": settings.cartesia_model if payload.kind == "tts" else
                settings.active_cloud_model if settings.selected_provider in CLOUD_PROVIDERS else settings.model,
            "modelDigest": settings.model_digest if settings.selected_provider == "ollama" and payload.kind != "tts" else "provider-managed",
            "promptVersion": module.PROMPT_VERSION,
            "promptHash": digest({"instruction": instruction, "input": type(payload).model_json_schema(),
                "output": getattr(module, "CoachOutput", getattr(module, "ReviewOutput", StrictModel)).model_json_schema()}),
            "schemaVersion": "1"}


def execution_fingerprint(payload, settings):
    return digest({"payload": payload.model_dump(), "metadata": capability_metadata(payload, settings),
        "pool": [{k: v for k, v in p.model_dump().items() if k != "api_key"}
                 for p in settings.sources_for(settings.selected_provider)] if settings.gemini_quota_enabled else [],
        "config": {name: getattr(settings, name) for name in ("num_ctx", "num_predict", "ollama_url", "gemini_base_url",
                    "gemini_model", "cartesia_url", "cartesia_voice", "cartesia_version", "model_serving_url",
                    "demo_daily_budget_microusd", "demo_call_reservation_microusd", "demo_daily_cloud_calls")}})


class Conflict(ValueError):
    pass


class JobStore:
    def __init__(self, path, retention=900):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path, self.retention = str(path), retention
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, scope TEXT NOT NULL, idem TEXT NOT NULL, fingerprint TEXT NOT NULL,
                event TEXT, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                deadline REAL NOT NULL, lease REAL, published REAL NOT NULL DEFAULT 0,
                result TEXT, error TEXT, updated REAL NOT NULL, UNIQUE(scope,idem))""")
            db.execute("CREATE TABLE IF NOT EXISTS worker_health (id INTEGER PRIMARY KEY, heartbeat REAL NOT NULL)")
            db.execute("""CREATE TABLE IF NOT EXISTS demo_budget (
                day TEXT NOT NULL, scope TEXT NOT NULL, reserved INTEGER NOT NULL, calls INTEGER NOT NULL,
                PRIMARY KEY(day,scope))""")
            db.execute("CREATE TABLE IF NOT EXISTS demo_reservations (job_id TEXT PRIMARY KEY)")
            db.execute("CREATE TABLE IF NOT EXISTS text_admissions (id TEXT PRIMARY KEY, deadline REAL NOT NULL)")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA secure_delete=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def register(self, request: JobRequest, settings):
        now = time.time()
        deadline = request.deadline.timestamp()
        maximum = 3600 if request.payload.kind == "conversation_demo" else 90
        if not now < deadline <= now + maximum + 1:
            raise ValueError("invalid job deadline")
        payload = request.payload.model_dump()
        fingerprint = execution_fingerprint(request.payload, settings)
        event = JobEvent(**request.model_dump(), payloadHash=digest(payload))
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM jobs WHERE scope=? AND idem=?", (request.userScope, request.idempotencyKey)).fetchone()
            if row:
                if row["fingerprint"] != fingerprint:
                    raise Conflict("idempotency key was reused")
                return row["id"]
            if settings.operational_limits_enabled and db.execute(
                    "SELECT COUNT(*) FROM jobs WHERE status IN ('PENDING','RUNNING')").fetchone()[0] >= 1000:
                raise OverflowError("job capacity exhausted")
            try:
                db.execute("INSERT INTO jobs(id,scope,idem,fingerprint,event,status,deadline,updated) VALUES (?,?,?,?,?,'PENDING',?,?)",
                    (str(event.jobId), event.userScope, event.idempotencyKey, fingerprint, event.model_dump_json(), deadline, now))
            except sqlite3.IntegrityError as exc:
                raise Conflict("job ID was reused") from exc
        return str(event.jobId)

    def admit_text(self, request_id, deadline):
        # Metadata only: no prompts, answers or credentials survive the synchronous call.
        # An uncertain paid outcome must not be replayed after an API restart.
        now = time.time()
        if not now < deadline <= now + 61:
            raise ValueError("invalid text deadline")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM text_admissions WHERE deadline<?", (now - 3600,))
            try:
                db.execute("INSERT INTO text_admissions(id,deadline) VALUES (?,?)", (str(request_id), deadline))
            except sqlite3.IntegrityError:
                return False
        return True

    def pending(self):
        self.cleanup()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT id,event FROM jobs WHERE status='PENDING' AND published<? AND deadline>? LIMIT 10",
                              (time.time() - 5, time.time())).fetchall()
            for row in rows:
                db.execute("UPDATE jobs SET published=? WHERE id=?", (time.time(), row["id"]))
        return [row["event"] for row in rows]

    def claim(self, event: JobEvent, settings=None):
        now = time.time()
        self.cleanup()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM jobs WHERE id=? AND scope=?", (str(event.jobId), event.userScope)).fetchone()
            if not row:
                return "REJECTED"
            # Only the outbox's exact event may claim the work, including deadline and attempt.
            if row["event"] and JobEvent.model_validate_json(row["event"]) != event:
                return "REJECTED"
            if row["status"] != "PENDING":
                return row["status"]
            if settings and row["fingerprint"] != execution_fingerprint(event.payload, settings):
                db.execute("UPDATE jobs SET status='FAILED',error='CONFIGURATION_CHANGED',event=NULL,updated=? WHERE id=?", (now, row["id"]))
                return "CONFIGURATION_CHANGED"
            if event.attempt != row["attempts"] + 1:
                return "REJECTED"
            db.execute("UPDATE jobs SET status='RUNNING',attempts=attempts+1,lease=?,updated=? WHERE id=?",
                       (min(row["deadline"], now + (3600 if event.payload.kind == "conversation_demo" else 90)) + 5, now, row["id"]))
        return "CLAIMED"

    def finish(self, event, result=None, *, error=None, retry=False):
        now = time.time()
        status = "DONE" if result is not None else "PENDING" if retry and event.attempt < 3 else "FAILED"
        next_event = event.model_copy(update={"attempt": event.attempt + 1}) if status == "PENDING" else None
        with self.connect() as db:
            updated = db.execute("""UPDATE jobs SET status=?,result=?,error=?,event=?,lease=NULL,published=0,updated=?
                WHERE id=? AND status='RUNNING' AND attempts=? AND lease>? AND deadline>?""",
                (status, canonical(result) if result is not None else None, error,
                 next_event.model_dump_json() if next_event else None, now, str(event.jobId), event.attempt, now, now)).rowcount
        return status if updated else "UNKNOWN"

    def get(self, job_id, scope):
        self.cleanup()
        with self.connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=? AND scope=?", (job_id, scope)).fetchone()
        if row is None:
            return None
        return {"jobId": row["id"], "schemaVersion": "1", "status": row["status"], "attempt": row["attempts"],
                "result": json.loads(row["result"]) if row["result"] else None, "error": row["error"]}

    def cleanup(self):
        now = time.time()
        with self.connect() as db:
            # A timed-out/crashed paid call is ambiguous. Never automatically invoke it again.
            db.execute("UPDATE jobs SET status='UNKNOWN',error='OUTCOME_UNKNOWN',event=NULL,updated=? WHERE status='RUNNING' AND lease<?", (now, now))
            db.execute("UPDATE jobs SET status='EXPIRED',error='DEADLINE',event=NULL,updated=? WHERE status='PENDING' AND deadline<?", (now, now))
            db.execute("UPDATE jobs SET result=NULL,event=NULL,status='EXPIRED' WHERE status='DONE' AND updated<?", (now - self.retention,))
            # Retain metadata-only tombstones for seven days; Java owns permanent domain idempotency.
            db.execute("DELETE FROM jobs WHERE status NOT IN ('RUNNING','PENDING') AND updated<?", (now - 604800,))

    def heartbeat(self):
        with self.connect() as db:
            db.execute("INSERT INTO worker_health VALUES (1,?) ON CONFLICT(id) DO UPDATE SET heartbeat=excluded.heartbeat", (time.time(),))

    def reserve_demo_cloud(self, job_id, scope, settings, calls):
        amount = settings.demo_call_reservation_microusd * calls
        if not amount or not settings.demo_daily_budget_microusd or not settings.demo_daily_cloud_calls:
            raise OverflowError("demo cloud budget is disabled")
        day = datetime.now(timezone.utc).date().isoformat()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            # Re-entering an uncertain paid execution must never issue the calls twice.
            if db.execute("SELECT 1 FROM demo_reservations WHERE job_id=?", (job_id,)).fetchone():
                raise OverflowError("demo reservation already consumed")
            for owner in ("GLOBAL", scope):
                db.execute("INSERT OR IGNORE INTO demo_budget VALUES (?,?,0,0)", (day, owner))
                changed = db.execute("""UPDATE demo_budget SET reserved=reserved+?,calls=calls+?
                    WHERE day=? AND scope=? AND reserved<=? AND calls<=?""",
                    (amount, calls, day, owner, settings.demo_daily_budget_microusd - amount,
                     settings.demo_daily_cloud_calls - calls)).rowcount
                if changed != 1:
                    raise OverflowError("demo budget exhausted")
            db.execute("INSERT INTO demo_reservations VALUES (?)", (job_id,))

    def metrics(self):
        self.cleanup()
        with self.connect() as db:
            counts = dict(db.execute("SELECT status,COUNT(*) FROM jobs GROUP BY status").fetchall())
            oldest = db.execute("SELECT MIN(updated) FROM jobs WHERE status='PENDING'").fetchone()[0]
            heartbeat = db.execute("SELECT heartbeat FROM worker_health WHERE id=1").fetchone()
        lines = [f'dictalab_ai_jobs{{status="{status}"}} {counts.get(status, 0)}' for status in
                 ("PENDING", "RUNNING", "DONE", "FAILED", "UNKNOWN", "EXPIRED")]
        lines += [f"dictalab_ai_job_queue_wait_seconds {max(0, time.time() - oldest) if oldest else 0}",
                  f"dictalab_ai_worker_up {int(bool(heartbeat) and time.time() - heartbeat[0] < 15)}"]
        return "\n".join(lines) + "\n"


def broker_connection(settings):
    import pika
    parameters = pika.URLParameters(settings.rabbitmq_url)
    parameters.heartbeat = 30
    parameters.socket_timeout = 5
    parameters.stack_timeout = 10
    parameters.blocked_connection_timeout = 5
    return pika.BlockingConnection(parameters)


def declare(channel):
    channel.queue_declare(queue=DLQ, durable=True, arguments={"x-message-ttl": 86400000, "x-max-length": 1000})
    channel.queue_declare(queue=QUEUE, durable=True, arguments={"x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": DLQ, "x-max-length": 1000, "x-overflow": "reject-publish", "x-message-ttl": 120000})


def publish_pending(settings, store):
    import pika
    events = store.pending()
    if not events:
        return
    with broker_connection(settings) as connection:
        channel = connection.channel()
        declare(channel)
        channel.confirm_delivery()
        for event in events:
            channel.basic_publish(exchange="", routing_key=QUEUE, body=event.encode(), mandatory=True,
                properties=pika.BasicProperties(content_type="application/json", type="dictalab.ai.job.v1", delivery_mode=2))
