import importlib.util
import json
from pathlib import Path
import time

import httpx
import pytest

spec = importlib.util.spec_from_file_location("gemini_batch", Path(__file__).with_name("gemini_batch.py"))
batch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(batch)


def settings():
    return batch.Settings(primary_provider="gemini", gemini_quota_enabled=True,
        gemini_quota_backend="memory", gemini_providers=[dict(id="batch-test", projectId="batch-test",
        apiKey="fake-key", model="gemini-3.5-flash-lite", rpmLimit=1000, operatingRpm=1000,
        enabled=True)], gemini_global_rpm_limit=1000)


def jobs(count=3):
    return [dict(id=str(i), messages=[{"role":"user", "content":"test"}],
        schema={"type":"object", "required":["ok"], "properties":{"ok":{"type":"boolean"}}}) for i in range(count)]


@pytest.mark.asyncio
async def test_checkpoint_duplicate_and_run_call_cap(tmp_path):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"choices":[{"message":{"content":'{"ok":true}'}}]})
    ledger = batch.BatchLedger(tmp_path / "batch.sqlite3")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        summary = await batch.run_batch(jobs(), settings(), ledger, max_calls=2, client=client)
        assert summary == {"DONE":2, "PENDING":1} and len(calls) == 2
        summary = await batch.run_batch(jobs(), settings(), ledger, client=client)
        assert summary == {"DONE":3} and len(calls) == 3
        await batch.run_batch(jobs(), settings(), ledger, client=client)
        assert len(calls) == 3
        result = json.loads(ledger.db.execute("SELECT result FROM batch_jobs WHERE id='0'").fetchone()[0])
        assert result["sourceId"] == "batch-test" and result["model"] == settings().gemini_model
    ledger.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("mode,status", [("timeout","UNKNOWN"), ("429","FAILED"), ("schema","FAILED")])
async def test_sent_failure_is_never_automatically_replayed(tmp_path, mode, status):
    calls = []
    def handler(request):
        calls.append(request)
        if mode == "timeout":
            raise httpx.ReadTimeout("synthetic timeout")
        if mode == "429":
            return httpx.Response(429)
        return httpx.Response(200, json={"choices":[{"message":{"content":'{"wrong":true}'}}]})
    ledger = batch.BatchLedger(tmp_path / "batch.sqlite3")
    # Fresh settings isolate 429 cooldown from other test invocations.
    from app.gemini_pool import _cached_store
    _cached_store.cache_clear()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        assert await batch.run_batch(jobs(1), settings(), ledger, client=client) == {status:1}
        await batch.run_batch(jobs(1), settings(), ledger, client=client)
    assert len(calls) == 1
    ledger.close()


def test_input_config_drift_and_crash_recovery(tmp_path):
    ledger = batch.BatchLedger(tmp_path / "batch.sqlite3")
    ledger.register(jobs(1), settings())
    assert ledger.claim("0", "old-owner", 1)
    with ledger.db:
        ledger.db.execute("UPDATE batch_jobs SET updated=?", (time.time()-606,))
    ledger.recover()
    assert ledger.summary() == {"UNKNOWN":1}
    assert not ledger.claim("0", "new-owner", 10)
    with pytest.raises(ValueError, match="different"):
        ledger.register(jobs(1), settings().model_copy(update={"temperature":0.7}))
    ledger.close()


def test_concurrent_ledger_owners_cannot_claim_same_job(tmp_path):
    path = tmp_path / "batch.sqlite3"
    first, second = batch.BatchLedger(path), batch.BatchLedger(path)
    first.register(jobs(1), settings())
    assert first.claim("0", "first", 1)
    assert not second.claim("0", "second", 1)
    first.close()
    second.close()


@pytest.mark.parametrize("row", [{"id":"a", "messages":[]}, {"id":"a", "messages":[{"role":"user","content":"ok"}],
    "schema":{"$ref":"https://example.test/schema"}}, {"id":"a", "apiKey":"forbidden"}])
def test_invalid_input_never_reaches_provider(tmp_path, row):
    path = tmp_path / "input.jsonl"
    path.write_text(json.dumps(row), encoding="utf-8")
    with pytest.raises(ValueError):
        batch.load_jobs(path)


@pytest.mark.asyncio
async def test_quota_outage_keeps_pending_without_http(tmp_path, monkeypatch):
    class Offline:
        def select(self, *args):
            raise batch.QuotaUnavailable(60)
    monkeypatch.setattr("app.model_gateway.quota_store", lambda _: Offline())
    ledger = batch.BatchLedger(tmp_path / "batch.sqlite3")
    def handler(request):
        raise AssertionError("HTTP must not be sent")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        assert await batch.run_batch(jobs(1), settings(), ledger, max_seconds=1, client=client) == {"PENDING":1}
    ledger.close()
