"""One bounded model call per worker. Broker I/O continues while inference runs."""
import asyncio
import concurrent.futures
import logging
import time
import uuid
import signal
import threading

import httpx

from . import coach, review, tts
from .config import Settings
from .jobs import JobEvent, JobStore, QUEUE, broker_connection, capability_metadata, declare, publish_pending
from .model_gateway import ModelGateway
from .redaction import RedactingFilter

logger = logging.getLogger("dictalab.ai.worker")
logger.addFilter(RedactingFilter())


async def execute(event, settings):
    if event.payload.kind == "conversation_demo":
        from .conversation_demo import execute as execute_demo
        return await execute_demo(event, settings, JobStore(settings.jobs_database, settings.job_retention_seconds))
    remaining = min(90, event.deadline.timestamp() - time.time())
    if remaining <= 0:
        raise TimeoutError("job expired")
    deadline = time.monotonic() + remaining
    metadata = capability_metadata(event.payload, settings)
    async with httpx.AsyncClient(timeout=remaining, limits=httpx.Limits(max_connections=settings.effective_http_max_connections)) as client:
        if event.payload.kind == "tts":
            task = await asyncio.wait_for(tts.synthesize(event.payload, settings, client, deadline), remaining)
        else:
            module, output = (coach, coach.CoachOutput) if event.payload.kind == "coach" else (review, review.ReviewOutput)
            gateway = ModelGateway(settings, client)
            raw, provider = await asyncio.wait_for(gateway.complete(
                [{"role": "system", "content": module.INSTRUCTION},
                 {"role": "user", "content": event.payload.model_dump_json()}],
                output.model_json_schema(), deadline, event.payload.kind, temperature=0), remaining)
            task = module.validate_output(raw, event.payload)
            metadata["provider"] = provider
            metadata["model"] = gateway.last_model
    return {**metadata, "generationId": str(uuid.uuid4()), "requestId": str(event.requestId), "task": task}


def retry_safe(error):
    from .gemini_pool import QuotaUnavailable
    # Only connection establishment/pool admission failures prove that no request was sent.
    seen = set()
    while error and id(error) not in seen:
        seen.add(id(error))
        if isinstance(error, (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout, QuotaUnavailable)):
            return True
        error = error.__cause__
    return False


def process(body, settings, store, runner=None):
    if len(body) > 64000:
        return "DEAD"
    try:
        event = JobEvent.model_validate_json(body)
    except ValueError:
        return "DEAD"
    state = store.claim(event, settings)
    if state in ("DONE", "RUNNING"):
        return "ACK"
    if state != "CLAIMED":
        return "DEAD"
    try:
        result = runner(event) if runner else asyncio.run(execute(event, settings))
        state = store.finish(event, result)
    except Exception as error:
        state = store.finish(event, error="TRANSPORT" if retry_safe(error) else "GENERATION_FAILED", retry=retry_safe(error))
        logger.warning("ai_job_failed", extra={"job_id": str(event.jobId), "kind": event.payload.kind, "status": state})
    return "ACK" if state in ("DONE", "PENDING") else "DEAD"


def main():
    settings = Settings()
    if not settings.jobs_enabled:
        raise SystemExit("DICTALAB_AI_JOBS_ENABLED must be true")
    store = JobStore(settings.jobs_database, settings.job_retention_seconds)
    stopping = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopping.set())
    signal.signal(signal.SIGINT, lambda *_: stopping.set())
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
        while not stopping.is_set():
            inflight = None
            try:
                publish_pending(settings, store)
                with broker_connection(settings) as connection:
                    channel = connection.channel()
                    declare(channel)
                    channel.basic_qos(prefetch_count=1)
                    for method, properties, body in channel.consume(QUEUE, inactivity_timeout=2, auto_ack=False):
                        store.heartbeat()
                        if stopping.is_set():
                            break
                        if method is None:
                            publish_pending(settings, store)
                            continue
                        if properties.type != "dictalab.ai.job.v1" or properties.content_type != "application/json":
                            channel.basic_reject(method.delivery_tag, requeue=False)
                            continue
                        pending = executor.submit(process, body, settings, store)
                        inflight = pending
                        while not pending.done():
                            connection.process_data_events(time_limit=0.2)
                            store.heartbeat()
                        if pending.result() == "ACK":
                            channel.basic_ack(method.delivery_tag)
                        else:
                            channel.basic_reject(method.delivery_tag, requeue=False)
                        inflight = None
            except Exception:
                if inflight is not None:
                    # Drain the bounded provider call before reconnecting; do not accumulate executor work.
                    try:
                        inflight.result()
                    except Exception:
                        pass
                # Never log broker URLs, payloads, or raw exceptions containing credentials.
                logger.warning("ai_job_broker_unavailable")
                time.sleep(2)


if __name__ == "__main__":
    main()
