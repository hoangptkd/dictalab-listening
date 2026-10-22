from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import os
import time
from urllib.parse import urlsplit

import httpx
import pytest
import redis

from app.config import Settings, GeminiSource
from app.gemini_pool import PACIFIC, QuotaStore, QuotaUnavailable, pacific_day, quota_store, retry_seconds
from app.model_gateway import ModelGateway
from app.providers.base import ProviderHttpError


def settings(n=3, **updates):
    sources = [dict(id=f"s{i}", projectId=f"test-project-{i}", apiKey=f"fake-{i}",
        model="gemini-3.5-flash-lite", rpmLimit=15, operatingRpm=13, rpdLimit=100,
        url="https://example.test/v1/chat/completions", enabled=True) for i in range(n)]
    return Settings(**(dict(primary_provider="gemini", gemini_quota_enabled=True,
        gemini_quota_backend="memory", gemini_providers=sources,
        gemini_global_rpm_limit=39) | updates))


@pytest.mark.parametrize("attempts,allowed", [(10,10), (20,20), (39,39), (45,39), (60,39)])
def test_concurrent_synthetic_capacity(attempts, allowed):
    store = QuotaStore(settings(), clock=lambda: 1792670400)
    def reserve(_):
        try:
            return bool(store.select("gemini-3.5-flash-lite", 1))
        except QuotaUnavailable:
            return False
    with ThreadPoolExecutor(max_workers=12) as executor:
        assert sum(executor.map(reserve, range(attempts))) == allowed
    assert all(store.execute(p, op="status")[0] <= 13 for p in store.settings.enabled_gemini_sources)


def test_fourth_provider_requires_config_only():
    store = QuotaStore(settings(n=4, gemini_global_rpm_limit=52))
    assert len({store.select("gemini-3.5-flash-lite", 1).id for _ in range(52)}) == 4
    with pytest.raises(QuotaUnavailable):
        store.select("gemini-3.5-flash-lite", 1)


def test_rpd_survives_minute_and_resets_at_pacific_midnight():
    now = [datetime(2026, 10, 22, 23, 58, tzinfo=PACIFIC).timestamp()]
    cfg = settings(n=1)
    cfg.gemini_providers[0].rpd_limit = 1
    store = QuotaStore(cfg, clock=lambda: now[0])
    store.select(cfg.gemini_model, 5)
    now[0] += 61
    with pytest.raises(QuotaUnavailable) as error:
        store.select(cfg.gemini_model, 5)
    assert error.value.retry_after == 59
    now[0] += 59
    assert store.select(cfg.gemini_model, 5)
    assert pacific_day(datetime(2026, 11, 1, 12, tzinfo=PACIFIC).timestamp())[2] - pacific_day(datetime(2026, 11, 1, 12, tzinfo=PACIFIC).timestamp())[1] == 25 * 3600000


def test_tpm_model_mismatch_and_circuit_do_not_consume_rejected_quota():
    cfg = settings(n=1)
    source = cfg.enabled_gemini_sources[0]
    source.tpm_limit = 10
    store = QuotaStore(cfg)
    with pytest.raises(QuotaUnavailable):
        store.select("another-model", 1)
    assert store.execute(source, 11)[0] == 0
    assert store.execute(source, op="status")[2] == 0
    for _ in range(5):
        store.report(source, "failure")
    assert store.execute(source, 1)[0] == 0


def test_success_and_failures_do_not_clear_long_429_cooldown():
    cfg = settings()
    store = QuotaStore(cfg)
    source = cfg.enabled_gemini_sources[0]
    store.report(source, "429", 600)
    store.report(source, "success")
    for _ in range(5):
        store.report(source, "failure")
    assert store.execute(source, op="status")[5] > 590000
    assert store.select(cfg.gemini_model, 1).id != source.id


def test_request_settings_share_one_store():
    cfg = settings()
    assert quota_store(cfg) is quota_store(cfg.model_copy(update={"timeout_seconds": 7, "temperature": 0.8}))


@pytest.mark.parametrize("missing_source,missing_requested,expected", [(None, False, 200), (1, False, 503), (None, True, 503)])
def test_pool_readiness_checks_every_source_and_requested_model(monkeypatch, missing_source, missing_requested, expected):
    from app import main
    from fastapi.testclient import TestClient
    from unittest.mock import AsyncMock, patch
    cfg = settings(conversation_enabled=False)
    if missing_requested:
        cfg.gemini_model = "unconfigured-model"
    monkeypatch.setattr(main, "settings", cfg)
    monkeypatch.setattr(main, "quota_store", lambda _: QuotaStore(cfg))
    calls = []

    async def models(url, headers):
        index = int(headers["Authorization"].rsplit("-", 1)[1])
        calls.append(index)
        return httpx.Response(200, json={"data": [] if index == missing_source else [{"id": cfg.gemini_providers[index].model}]},
                              request=httpx.Request("GET", url))

    with patch("httpx.AsyncClient.get", new=AsyncMock(side_effect=models)):
        assert TestClient(main.app).get("/health/ready").status_code == expected
    assert calls == ([0, 1] if missing_source == 1 else [0, 1, 2])


def test_invalid_configuration_and_secret_redaction():
    cfg = settings()
    assert "fake-0" not in repr(cfg) and "fake-0" not in repr(cfg.enabled_gemini_sources[0])
    for changes in ({"url": "http://remote.test/chat"}, {"api_key": "key\nsecret"}):
        with pytest.raises(ValueError):
            GeminiSource.model_validate(cfg.enabled_gemini_sources[0].model_dump() | changes)
    duplicate = cfg.gemini_providers[0].model_dump() | {"id": "other"}
    with pytest.raises(ValueError, match="duplicate"):
        settings(gemini_providers=[cfg.gemini_providers[0], duplicate])
    assert retry_seconds("invalid") == 60


def test_teacher_pool_keeps_approved_model_and_records_source_without_legacy_key(tmp_path):
    from io import BytesIO
    from types import SimpleNamespace
    from training.generate_teacher_data import Gemini, TEACHER
    client = object.__new__(Gemini)
    client.output, client.key = tmp_path, None
    calls, reports = [], []
    source = SimpleNamespace(id="source-b", api_key="test-pool-key", url="https://example.test/chat")
    def select(model, tokens):
        assert model == TEACHER and tokens > 0
        return source
    client.pool = SimpleNamespace(select=select, report=lambda *args: reports.append(args))
    def send(request, **kwargs):
        calls.append(request)
        assert request.get_header("Authorization") == "Bearer test-pool-key"
        return BytesIO(json.dumps({"choices":[{"finish_reason":"stop", "message":{"content":'{"items":[]}'}}]}).encode())
    client.opener = SimpleNamespace(open=send)
    client.call(TEACHER, "author", {}, "teacher-pool")
    assert len(calls) == 1 and reports == [(source, "success")]
    assert json.loads(calls[0].data)["model"] == TEACHER
    saved = json.loads((tmp_path / "teacher-pool.response.json").read_text())
    assert saved["sourceId"] == "source-b"
    assert "test-pool-key" not in "".join(p.read_text() for p in tmp_path.iterdir())


def test_teacher_pool_admission_failure_writes_no_request_checkpoint(tmp_path):
    from types import SimpleNamespace
    from training.generate_teacher_data import Gemini, TEACHER
    from training.prepare_dataset import DatasetError
    client = object.__new__(Gemini)
    client.output, client.key = tmp_path, None
    def reject(*args):
        raise QuotaUnavailable(60)
    client.pool = SimpleNamespace(select=reject)
    with pytest.raises(DatasetError, match="no request sent"):
        client.call(TEACHER, "author", {}, "teacher-pool")
    assert not list(tmp_path.iterdir())


@pytest.mark.asyncio
async def test_429_does_not_retry_sent_request_but_next_job_can_use_other_source(monkeypatch):
    cfg = settings()
    store = QuotaStore(cfg)
    monkeypatch.setattr("app.model_gateway.quota_store", lambda _: store)
    calls = []
    def handler(request):
        calls.append(request)
        if len(calls) == 1:
            return httpx.Response(429, headers={"Retry-After": "60"})
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"ok":true}'}}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        gateway = ModelGateway(cfg, client)
        with pytest.raises(ProviderHttpError):
            await gateway.complete([{"role":"user","content":"test"}], {}, time.monotonic()+5, "batch")
        assert len(calls) == 1
        result, _ = await gateway.complete([{"role":"user","content":"test"}], {}, time.monotonic()+5, "batch")
        assert result == {"ok": True} and len(calls) == 2
        assert calls[0].headers["Authorization"] != calls[1].headers["Authorization"]
        assert json.loads(calls[1].content)["model"] == cfg.gemini_model


@pytest.fixture
def redis_client():
    url = os.environ.get("TEST_GEMINI_REDIS_URL")
    if not url:
        pytest.skip("requires disposable Redis on TEST_GEMINI_REDIS_URL, DB 15")
    uri = urlsplit(url)
    assert uri.hostname in ("127.0.0.1", "localhost") and uri.path == "/15"
    client = redis.Redis.from_url(url, decode_responses=True)
    client.flushdb()  # Explicitly opted-in disposable test DB only.
    yield client
    client.flushdb()
    client.close()


def test_redis_atomic_shared_workers_and_rolling_expiry(redis_client):
    cfg = settings(gemini_quota_backend="redis")
    stores = [QuotaStore(cfg, redis_client), QuotaStore(cfg, redis_client)]
    def reserve(index):
        try:
            return bool(stores[index % 2].select(cfg.gemini_model, 1))
        except QuotaUnavailable:
            return False
    with ThreadPoolExecutor(max_workers=12) as executor:
        assert sum(executor.map(reserve, range(60))) == 39
    for source in cfg.enabled_gemini_sources:
        assert stores[1].execute(source, op="status")[2] == 13
        keys, _ = stores[0]._keys_args(source, 1, "status", 0)
        for key in (keys[0], keys[3]):
            for ident in redis_client.zrange(key, 0, -1):
                redis_client.zadd(key, {ident: time.time()*1000 - 60001})
    assert stores[0].select(cfg.gemini_model, 1)
    assert sum(stores[1].execute(p, op="status")[2] for p in cfg.enabled_gemini_sources) == 40


def test_redis_shared_cooldown_and_fail_closed(redis_client):
    cfg = settings(gemini_quota_backend="redis")
    first, second = QuotaStore(cfg, redis_client), QuotaStore(cfg, redis_client)
    source = cfg.enabled_gemini_sources[0]
    first.report(source, "429", 600)
    second.report(source, "success")
    for _ in range(5):
        second.report(source, "failure")
    assert first.execute(source, op="status")[5] > 590000
    assert second.select(cfg.gemini_model, 1).id != source.id
    class Offline:
        def time(self):
            raise redis.ConnectionError()
    with pytest.raises(QuotaUnavailable):
        QuotaStore(cfg, Offline()).select(cfg.gemini_model, 1)
