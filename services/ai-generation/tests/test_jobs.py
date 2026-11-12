import base64
import io
import json
import time
import wave
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from unittest.mock import patch, AsyncMock
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.jobs import JobRequest, JobEvent, JobStore, Conflict, digest, capability_metadata
from app.worker import process, execute
from app import coach, review, tts


def request(kind="review", **updates):
    payload = {"kind": "review", "facts": [{"id": "F1", "text": "nine"}], "recall": "nine"}
    if kind == "coach":
        payload = {"kind": "coach", "level": "A1", "candidates": [{"id": "1:0", "pattern": "KNOWN_MISSED",
                   "heard": "", "expected": "nine", "sentence": "At nine.", "priority": 90, "occurrences": 1}]}
    elif kind == "tts":
        payload = {"kind": "tts", "text": "At nine."}
    return JobRequest.model_validate({"jobId": str(uuid4()), "requestId": str(uuid4()), "userScope": "a" * 64,
        "idempotencyKey": str(uuid4()), "deadline": datetime.now(timezone.utc) + timedelta(seconds=60),
        "payload": payload, **updates})


def setup_job(tmp_path, kind="review"):
    settings = Settings(jobs_database=str(tmp_path / "jobs.sqlite3"))
    store = JobStore(settings.jobs_database)
    req = request(kind)
    job_id = store.register(req, settings)
    body = store.pending()[0]
    return settings, store, req, job_id, body


def test_duplicate_delivery_and_restart_return_same_generation(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    calls = []
    def runner(event):
        calls.append(event)
        return {"generationId": "fixed", "task": {"facts": []}}
    assert process(body, settings, store, runner) == "ACK"
    restarted = JobStore(settings.jobs_database)
    assert process(body, settings, restarted, runner) == "ACK"
    assert len(calls) == 1
    assert restarted.get(job_id, req.userScope)["result"]["generationId"] == "fixed"
    assert restarted.get(job_id, "b" * 64) is None
    assert restarted.register(req.model_copy(update={"jobId": uuid4()}), settings) == job_id
    with pytest.raises(Conflict):
        restarted.register(req.model_copy(update={"payload": review.ReviewInput(facts=[review.Fact(id="F1", text="other")], recall="x")}), settings)


def test_two_workers_claim_only_once(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    calls = []
    def runner(event):
        calls.append(event)
        time.sleep(0.05)
        return {"ok": True}
    with ThreadPoolExecutor(max_workers=5) as workers:
        outcomes = list(workers.map(lambda _: process(body, settings, JobStore(settings.jobs_database), runner), range(5)))
    assert outcomes == ["ACK"] * 5
    assert len(calls) == 1


def test_unrestricted_job_admission_passes_1000_without_dropping_existing_jobs(tmp_path):
    settings, store, req, job_id, _ = setup_job(tmp_path)
    settings.operational_limits_enabled = True
    with store.connect() as db:
        db.executemany("INSERT INTO jobs(id,scope,idem,fingerprint,status,deadline,updated) VALUES (?,?,?,?,?,?,?)",
            [(f"pending-{i}", req.userScope, f"pending-{i}", "fixture", "PENDING", time.time()+60, time.time())
             for i in range(999)])
    new_request = request()
    with pytest.raises(OverflowError):
        store.register(new_request, settings)
    settings.operational_limits_enabled = False
    assert store.register(new_request, settings) == str(new_request.jobId)
    assert store.get(job_id, req.userScope)["status"] == "PENDING"
    with store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1001


def test_bounded_safe_transport_retries_then_dead_letter(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    def unavailable(_):
        raise httpx.ConnectError("no connection")
    for attempt in range(1, 4):
        assert process(body, settings, store, unavailable) == ("ACK" if attempt < 3 else "DEAD")
        if attempt < 3:
            body = store.pending()[0]
            assert json.loads(body)["attempt"] == attempt + 1
    assert store.get(job_id, req.userScope)["status"] == "FAILED"
    assert store.get(job_id, req.userScope)["attempt"] == 3


@pytest.mark.parametrize("error", [httpx.ReadTimeout("uncertain"), ValueError("invalid evidence")])
def test_ambiguous_or_invalid_result_never_retries(tmp_path, error):
    settings, store, req, job_id, body = setup_job(tmp_path)
    def failed(_):
        raise error
    assert process(body, settings, store, failed) == "DEAD"
    assert store.pending() == []


def test_visibility_timeout_and_late_result_are_fenced(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    event = JobEvent.model_validate_json(body)
    assert store.claim(event) == "CLAIMED"
    with store.connect() as db:
        db.execute("UPDATE jobs SET lease=? WHERE id=?", (time.time() - 1, job_id))
    restarted = JobStore(settings.jobs_database)
    assert process(body, settings, restarted, lambda _: pytest.fail("must not repeat an uncertain call")) == "DEAD"
    assert restarted.get(job_id, req.userScope)["status"] == "UNKNOWN"
    assert restarted.finish(event, {"late": True}) == "UNKNOWN"


def test_expiry_retention_and_tampered_event(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    tampered = json.loads(body)
    tampered["payload"]["recall"] = "other"
    assert process(json.dumps(tampered), settings, store) == "DEAD"
    with store.connect() as db:
        db.execute("UPDATE jobs SET deadline=? WHERE id=?", (time.time() - 1, job_id))
    assert process(body, settings, store, lambda _: pytest.fail("expired")) == "DEAD"
    assert store.get(job_id, req.userScope)["status"] == "EXPIRED"
    settings, store, req, job_id, body = setup_job(tmp_path / "second")
    process(body, settings, store, lambda _: {"audioBase64": "private"})
    with store.connect() as db:
        db.execute("UPDATE jobs SET updated=? WHERE id=?", (time.time() - 901, job_id))
    assert store.get(job_id, req.userScope)["result"] is None
    assert store.get(job_id, req.userScope)["status"] == "EXPIRED"


def test_event_rejects_unknown_fields_versions_and_unredacted_text(tmp_path):
    req = request()
    payload = req.model_dump(mode="json")
    payload["schemaVersion"] = "2"
    with pytest.raises(ValueError):
        JobRequest.model_validate(payload)
    payload = req.model_dump(mode="json")
    payload["payload"]["password"] = "private"
    with pytest.raises(ValueError):
        JobRequest.model_validate(payload)
    payload["payload"].pop("password")
    payload["payload"]["recall"] = "user@example.com Bearer secret"
    sanitized = JobRequest.model_validate(payload)
    assert "user@example.com" not in sanitized.model_dump_json()
    assert "Bearer secret" not in sanitized.model_dump_json()


def test_coach_and_review_reject_changed_evidence_and_scores():
    p = request("coach").payload
    assert coach.validate_output({"focus": {"pattern": "KNOWN_MISSED", "evidenceIds": ["1:0"],
        "insight": "Bạn đã bỏ lỡ từ quen trong câu."}, "reviewIds": []}, p)["focus"]
    with pytest.raises(ValueError):
        coach.validate_output({"focus": {"pattern": "KNOWN_MISSED", "evidenceIds": ["invented"], "insight": "Bạn cần nghe lại."}, "reviewIds": []}, p)
    for raw in ({"facts": [{"factId": "invented", "status": "CAPTURED", "explanationVi": "Đúng."}]},
                {"facts": [{"factId": "F1", "status": "CAPTURED", "explanationVi": "Đúng."}], "score": 100}):
        with pytest.raises(ValueError):
            review.validate_output(raw, request().payload)


def wav_bytes():
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(24000); wav.writeframes(b"\x00\x00" * 240)
    return output.getvalue()


def voice_recipe():
    return {"id": "qa-pair-v1", "model": "sonic-3.6", "apiVersion": "2026-08-14", "language": "en",
            "outputFormat": "wav-pcm16-mono-24000", "voices": ["00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"]}


def voice_request(index=0):
    return request(payload={"kind": "scenario_tts", "text": "At nine.", "speakerId": f"c{index+1}",
                            "voiceId": voice_recipe()["voices"][index], "recipe": voice_recipe()})


@pytest.mark.asyncio
async def test_scenario_tts_executes_distinct_frozen_voices_and_returns_verifiable_metadata():
    settings = Settings(cartesia_api_key="test-only", cartesia_voice="changed-legacy-default", scenario_tts_recipes=[voice_recipe()])
    calls = []
    def respond(req):
        calls.append((json.loads(req.content), req.headers["Cartesia-Version"]))
        return httpx.Response(200, content=wav_bytes(), headers={"Content-Type": "audio/wav"})
    for i in range(2):
        req = voice_request(i)
        event = JobEvent(**req.model_dump(), payloadHash=digest(req.payload.model_dump()))
        client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
        with patch("app.worker.httpx.AsyncClient", return_value=client):
            result = await execute(event, settings)
        assert result["promptVersion"] == "TTS_WAV_V2"
        assert result["recipeHash"] == digest(voice_recipe())
        assert result["voiceId"] == voice_recipe()["voices"][i]
        assert result["speakerId"] == f"c{i+1}"
        tts.validate_wav(base64.b64decode(result["task"]["audioBase64"]))
    assert [body["voice"]["id"] for body, _ in calls] == voice_recipe()["voices"]
    assert all(body["model_id"] == "sonic-3.6" and version == "2026-08-14" for body, version in calls)


def test_scenario_recipe_validation_and_legacy_job_replay_are_compatible(tmp_path):
    from app.jobs import execution_fingerprint
    settings = Settings(jobs_database=str(tmp_path / "voices.sqlite"), scenario_tts_recipes=[voice_recipe()])
    store = JobStore(settings.jobs_database)
    legacy = request("tts")
    assert legacy.payload.model_dump() == {"kind": "tts", "text": "At nine."}
    store.register(legacy, settings)
    old_body = store.pending()[0]
    assert JobEvent.model_validate_json(old_body).payloadHash == digest({"kind": "tts", "text": "At nine."})
    assert process(old_body, settings, store, lambda _: {"task": {"audioBase64": "retained"}}) == "ACK"
    assert JobStore(settings.jobs_database).register(legacy, settings) == str(legacy.jobId)
    req = voice_request()
    fingerprint = execution_fingerprint(req.payload, settings)
    assert execution_fingerprint(req.payload, settings.model_copy(update={"cartesia_voice": "another-default"})) == fingerprint
    with pytest.raises(ValueError):
        store.register(req, Settings())
    store.register(req, settings)
    assert store.register(req, settings) == str(req.jobId)
    for changes in ({"voiceId": "not-approved"}, {"text": "Contact private@example.com"}):
        with pytest.raises(ValueError):
            voice_request().payload.model_validate({**req.payload.model_dump(), **changes})
    for invalid in ({**voice_recipe(), "voices": [voice_recipe()["voices"][0]] * 2},
                    {**voice_recipe(), "voices": ["not-a-uuid", "also-invalid"]}):
        with pytest.raises(ValueError):
            Settings(scenario_tts_recipes=[invalid])


def test_scenario_tts_uses_existing_internal_endpoint_and_rejects_other_routes(monkeypatch, tmp_path):
    from app import main
    settings = Settings(jobs_enabled=True, service_token="test-only", jobs_database=str(tmp_path / "endpoint.sqlite"), scenario_tts_recipes=[voice_recipe()])
    monkeypatch.setattr(main, "settings", settings)
    req = voice_request()
    headers = {"X-Service-Token": "test-only", "X-User-Scope": req.userScope, "X-Request-Id": str(req.requestId), "Idempotency-Key": req.idempotencyKey}
    client = TestClient(main.app)
    assert client.post("/v1/tts/jobs", json=req.model_dump(mode="json"), headers=headers).status_code == 202
    headers["X-AI-Provider"] = settings.selected_provider
    assert client.post("/v1/coach/reports", json=req.model_dump(mode="json"), headers=headers).status_code == 400


def test_wav_validation():
    audio = wav_bytes()
    tts.validate_wav(audio)
    for invalid in (b"not audio", audio[:-1], audio + b"junk", b"RIFF" + b"x" * 50, b"x" * 3_000_001):
        with pytest.raises(ValueError):
            tts.validate_wav(invalid)


def streamed_wav():
    audio = wav_bytes()
    # Real provider layout: fmt, LIST metadata, then an unknown-length data chunk.
    metadata = b"LIST" + (26).to_bytes(4, "little") + b"INFO" + b"\x00" * 22
    return b"RIFF" + b"\xff" * 4 + audio[8:36] + metadata + b"data" + b"\xff" * 4 + audio[44:]


@pytest.mark.asyncio
@pytest.mark.parametrize("audio", [wav_bytes(), streamed_wav()], ids=["finite-wav", "streaming-wav"])
async def test_tts_finalizes_complete_wav_without_changing_pcm(audio):
    settings = Settings(cartesia_api_key="test-only")
    calls = []
    def respond(req):
        calls.append(req)
        return httpx.Response(200, content=audio, headers={"Content-Type": "audio/wav"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result = await tts.synthesize(request("tts").payload, settings, client, time.monotonic() + 10)
    finalized = base64.b64decode(result["audioBase64"])
    tts.validate_wav(finalized)
    assert len(calls) == 1
    assert result["size"] == len(audio) == len(finalized)
    with wave.open(io.BytesIO(finalized), "rb") as wav:
        assert wav.getnframes() == 240
        assert wav.readframes(240) == wav_bytes()[44:]
    if audio == wav_bytes():
        assert finalized == audio


def test_streamed_wav_keeps_strict_validation():
    audio = streamed_wav()
    invalid_format = bytearray(audio)
    invalid_format[24:28] = (16000).to_bytes(4, "little")
    invalid_metadata = bytearray(audio)
    invalid_metadata[40:44] = (len(audio) * 2).to_bytes(4, "little")
    for invalid in (audio[:-1], audio[:78], bytes(invalid_format), bytes(invalid_metadata),
                    audio[:74] + (999).to_bytes(4, "little") + audio[78:],
                    wav_bytes()[:-2], wav_bytes() + b"junk", b"x" * 3_000_001):
        with pytest.raises(ValueError):
            tts.finalize_wav(invalid)
    with pytest.raises(ValueError):
        tts.validate_wav(audio)


@pytest.mark.asyncio
async def test_tts_never_finalizes_interrupted_stream():
    class InterruptedStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield streamed_wav()
            raise httpx.ReadError("connection interrupted")
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(
            200, stream=InterruptedStream(), headers={"Content-Type": "audio/wav"}))) as client:
        with pytest.raises(httpx.ReadError):
            await tts.synthesize(request("tts").payload, Settings(cartesia_api_key="test-only"),
                                 client, time.monotonic() + 10)


@pytest.mark.parametrize("kind", ["coach", "review", "tts"])
def test_job_api_contract_auth_scope_and_worker_result(tmp_path, monkeypatch, kind):
    from app import main
    settings = Settings(jobs_enabled=True, jobs_database=str(tmp_path / "jobs.sqlite3"), service_token="test-only")
    monkeypatch.setattr(main, "settings", settings)
    req = request(kind)
    headers = {"X-Service-Token": "test-only", "X-User-Scope": req.userScope,
               "X-Request-Id": str(req.requestId), "Idempotency-Key": req.idempotencyKey, "X-AI-Provider": "ollama"}
    path = {"coach": "/v1/coach/reports", "review": "/v1/review/tasks", "tts": "/v1/tts/jobs"}[kind]
    client = TestClient(main.app)
    assert client.post(path, json=req.model_dump(mode="json")).status_code == 401
    assert client.post(path, headers=headers, content=b"x" * 64001).status_code == 413
    response = client.post(path, headers=headers, json=req.model_dump(mode="json"))
    assert response.status_code == 202, response.text
    store = JobStore(settings.jobs_database)
    body = store.pending()[0]
    assert JobEvent.model_validate_json(body).payloadHash == digest(req.payload.model_dump())
    assert process(body, settings, store, lambda _: {**capability_metadata(req.payload, settings), "task": {"ok": True}}) == "ACK"
    assert client.get("/v1/jobs/" + str(req.jobId), headers=headers).json()["status"] == "DONE"
    assert client.get("/v1/jobs/" + str(req.jobId), headers={**headers, "X-User-Scope": "b" * 64}).status_code == 404
    assert client.post(path, headers={**headers, "X-AI-Provider": "gemini"}, json=req.model_dump(mode="json")).status_code == (202 if kind == "tts" else 409)


@pytest.mark.asyncio
async def test_production_uses_gemini_without_contacting_ollama():
    settings = Settings(production_mode=True, service_token="test-only", gemini_api_key="test-cloud", conversation_enabled=False)
    assert settings.selected_provider == "gemini"
    assert Settings().selected_provider == "ollama"
    from app.model_gateway import ModelGateway
    from unittest.mock import AsyncMock
    async with httpx.AsyncClient() as client:
        gateway = ModelGateway(settings, client)
        gateway._local.complete = AsyncMock(side_effect=AssertionError("must not contact Ollama"))
        gateway._primary_cloud.complete = AsyncMock(return_value={"ok": True})
        assert await gateway.complete([], {}, time.monotonic() + 10, "review") == ({"ok": True}, "gemini")


def test_cloud_readiness_does_not_require_local_model_or_unapproved_conversation(monkeypatch):
    from app import main
    monkeypatch.setattr(main, "settings", Settings(production_mode=True, service_token="test-only", gemini_api_key="test-cloud", conversation_enabled=False))
    response = httpx.Response(200, json={"data": [{"id": "gemini-3.5-flash-lite"}]}, request=httpx.Request("GET", "https://cloud/v1/models"))
    with patch("httpx.AsyncClient.get", new=AsyncMock(return_value=response)) as get:
        assert TestClient(main.app).get("/health/ready").status_code == 200
        assert get.call_args.args[0] == "https://generativelanguage.googleapis.com/v1beta/openai/models"


def test_gemini_provider_uses_models_prefix(monkeypatch):
    settings = Settings(primary_provider="gemini", gemini_api_key="gemini-test")
    assert settings.selected_provider == "gemini"
    assert settings.active_cloud_base_url == "https://generativelanguage.googleapis.com/v1beta/openai"
    assert settings.active_cloud_api_key == "gemini-test"
    assert settings.active_cloud_model == "gemini-3.5-flash-lite"
    assert settings.active_cloud_models_url == (
        "https://generativelanguage.googleapis.com/v1beta/openai/models"
    )

    from app import main
    monkeypatch.setattr(main, "settings", settings.model_copy(update={"conversation_enabled": False}))
    response = httpx.Response(200, json={"data": [{"id": "models/gemini-3.5-flash-lite"}]},
                              request=httpx.Request("GET", settings.active_cloud_models_url))
    with patch("httpx.AsyncClient.get", new=AsyncMock(return_value=response)) as get:
        assert TestClient(main.app).get("/health/ready").status_code == 200
        assert get.call_args.args[0] == settings.active_cloud_models_url


def test_remote_plaintext_provider_is_rejected():
    with pytest.raises(ValueError):
        Settings(production_mode=True, primary_provider="ollama", service_token="test-only", ollama_url="http://10.0.0.2:11434")


def test_model_change_while_job_waits_fails_closed_and_exports_queue_metrics(tmp_path):
    settings, store, req, job_id, body = setup_job(tmp_path)
    changed = settings.model_copy(update={"model": "different-model"})
    assert process(body, changed, store, lambda _: pytest.fail("must not run a different model")) == "DEAD"
    assert store.get(job_id, req.userScope)["error"] == "CONFIGURATION_CHANGED"
    store.heartbeat()
    assert 'dictalab_ai_jobs{status="FAILED"} 1' in store.metrics()
    assert "dictalab_ai_worker_up 1" in store.metrics()


@pytest.mark.asyncio
async def test_remote_ollama_sends_private_service_token_and_does_not_disable_tls():
    from app.providers.ollama import OllamaProvider
    def respond(req):
        assert req.url.scheme == "https"
        assert req.headers["X-Service-Token"] == "private-test-token"
        return httpx.Response(200, json={"done": True, "done_reason": "stop", "message": {"content": '{"ok":true}'}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        provider = OllamaProvider(ollama_url="https://ollama.internal", model="test", client=client, service_token="private-test-token")
        assert await provider.complete([], {}, time.monotonic() + 10, "test") == {"ok": True}


@pytest.mark.asyncio
@pytest.mark.parametrize("content_type,audio", [("text/html", wav_bytes()), ("audio/wav", b"bad"), ("audio/wav", b"x" * 3_000_001)],
                         ids=["wrong-content-type", "invalid-wav", "oversized-wav"])
async def test_tts_transport_rejects_invalid_or_oversized_audio(content_type, audio):
    settings = Settings(cartesia_api_key="test-only")
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=audio,
            headers={"Content-Type": content_type}))) as client:
        with pytest.raises(ValueError):
            await tts.synthesize(request("tts").payload, settings, client, time.monotonic() + 10)
