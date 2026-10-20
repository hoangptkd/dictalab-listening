"""T06 — Tests for security, redaction, idempotency hardening.

Covers:
- Redaction of email, JWT, Bearer, long opaque key, nested dict, deep recursion
- is_safe_log_value helper
- Production fail-fast when service_token is empty
- Service token with newline rejected at config
- Auth header enforcement (401 on bad token)
- Concurrent idempotency: two simultaneous requests with same key → only one
  generation, second gets cached result
- TTL expiry: after TTL, same key is re-generated
- Key reuse with different fingerprint → 409
- Log capture: no raw credential in log output
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.redaction import redact, redact_string, is_safe_log_value


# ---------------------------------------------------------------------------
# redact_string — pattern coverage
# ---------------------------------------------------------------------------

class TestRedactString:
    def test_email_is_redacted(self):
        result = redact_string("contact user@example.com for details")
        assert "user@example.com" not in result
        assert "[REDACTED]" in result

    def test_bearer_token_is_redacted(self):
        result = redact_string("Authorization: Bearer eyJabc123xyz789superlong")
        assert "eyJabc123xyz789superlong" not in result

    def test_jwt_is_redacted(self):
        jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        result = redact_string(f"token: {jwt}")
        assert jwt not in result
        assert "[REDACTED]" in result

    def test_long_opaque_key_is_redacted(self):
        key = "sk-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH"  # 46 chars
        result = redact_string(f"api_key={key}")
        assert key not in result

    def test_short_value_not_redacted(self):
        result = redact_string("code: A1B2")
        assert "A1B2" in result

    def test_normal_text_unchanged(self):
        text = "The meeting is at nine in the lobby."
        assert redact_string(text) == text

    def test_multiple_patterns_in_one_string(self):
        text = "user@test.com got Bearer mytoken123 access"
        result = redact_string(text)
        assert "user@test.com" not in result
        assert "Bearer mytoken123" not in result


# ---------------------------------------------------------------------------
# redact — recursive dict/list coverage
# ---------------------------------------------------------------------------

class TestRedact:
    def test_dict_with_credential_key_value_redacted(self):
        d = {"password": "secret123", "name": "hotel"}
        result = redact(d)
        assert result["password"] == "[REDACTED]"
        assert result["name"] == "hotel"

    def test_dict_authorization_header_redacted(self):
        d = {"authorization": "Bearer token123xyz", "content-type": "application/json"}
        result = redact(d)
        assert result["authorization"] == "[REDACTED]"
        assert result["content-type"] == "application/json"

    def test_nested_dict_recursive(self):
        d = {
            "context": {
                "history": [{"utteranceEn": "OK", "token": "secret-value"}],
                "email": "user@example.com",
            }
        }
        result = redact(d)
        assert result["context"]["history"][0].get("token") == "[REDACTED]"
        assert "user@example.com" not in str(result)

    def test_list_items_redacted(self):
        lst = ["normal", "user@example.com", "Bearer abc123xyz"]
        result = redact(lst)
        assert result[0] == "normal"
        assert "user@example.com" not in result[1]

    def test_depth_limit_prevents_infinite_recursion(self):
        """Very deep nesting must not cause a stack overflow."""
        deep: Any = {"v": "bottom"}
        for _ in range(25):
            deep = {"nested": deep}
        result = redact(deep)
        assert result is not None  # must complete without error

    def test_numbers_and_none_unchanged(self):
        d = {"count": 42, "flag": True, "nothing": None}
        result = redact(d)
        assert result == d

    def test_returns_new_object_not_mutating_original(self):
        original = {"password": "secret"}
        result = redact(original)
        assert original["password"] == "secret"  # original unchanged
        assert result["password"] == "[REDACTED]"


# ---------------------------------------------------------------------------
# is_safe_log_value
# ---------------------------------------------------------------------------

class TestIsSafeLogValue:
    def test_clean_text_is_safe(self):
        assert is_safe_log_value("The lobby is on the first floor.") is True

    def test_email_is_not_safe(self):
        assert is_safe_log_value("contact user@example.com") is False

    def test_bearer_is_not_safe(self):
        assert is_safe_log_value("Bearer xyz123abc") is False

    def test_jwt_is_not_safe(self):
        jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        assert is_safe_log_value(jwt) is False


# ---------------------------------------------------------------------------
# Config — production fail-fast and token validation
# ---------------------------------------------------------------------------

class TestConfigSecurity:
    def test_production_mode_without_token_raises(self):
        with pytest.raises(ValueError, match="SERVICE_TOKEN"):
            Settings(production_mode=True, service_token="")

    def test_production_mode_with_token_ok(self):
        s = Settings(production_mode=True, service_token="a-valid-token-here")
        assert s.production_mode is True

    def test_token_with_newline_rejected(self):
        with pytest.raises(ValueError, match="newline"):
            Settings(service_token="bad\ntoken")

    def test_token_with_carriage_return_rejected(self):
        with pytest.raises(ValueError, match="newline"):
            Settings(service_token="bad\rtoken")

    def test_empty_token_non_production_ok(self):
        s = Settings(production_mode=False, service_token="")
        assert s.service_token == ""


# ---------------------------------------------------------------------------
# HTTP endpoint — auth enforcement
# ---------------------------------------------------------------------------

def _good_task() -> dict:
    return {
        "slots": [{"kind": "time", "askVi": "Mấy giờ?"}],
        "utteranceEn": "at nine",
        "facts": [{"textVi": "Chín giờ", "evidenceEn": "at nine"}],
        "questionVi": "Cuộc họp lúc mấy giờ?",
        "taskVi": "Nghe và trả lời.",
        "skill": "Nắm thông tin cụ thể",
        "translationVi": "Lúc chín giờ.",
    }


def _payload(req_id: str = "req-001", key: str = "key-001") -> dict:
    return {
        "requestId": req_id,
        "idempotencyKey": key,
        "level": "A1",
        "turnNumber": 1,
        "promptVersion": "V1",
        "instruction": "test",
        "context": {"turnNumber": 1},
    }


def _headers(req_id: str = "req-001", key: str = "key-001", token: str | None = None) -> dict:
    h = {"X-Request-Id": req_id, "Idempotency-Key": key}
    if token is not None:
        h["X-Service-Token"] = token
    return h


class TestAuthEnforcement:
    def test_valid_token_accepted(self):
        from app.main import app, cache, settings
        orig_token = settings.service_token
        settings.service_token = "correct-token"
        cache.clear()
        try:
            with patch("app.main.run_workflow", new=AsyncMock(return_value=_good_task())):
                client = TestClient(app)
                resp = client.post("/v1/conversation/tasks",
                                   json=_payload(), headers=_headers(token="correct-token"))
            assert resp.status_code == 200
        finally:
            settings.service_token = orig_token
            cache.clear()

    def test_wrong_token_returns_401(self):
        from app.main import app, settings
        orig_token = settings.service_token
        settings.service_token = "correct-token"
        try:
            client = TestClient(app)
            resp = client.post("/v1/conversation/tasks",
                               json=_payload(), headers=_headers(token="wrong-token"))
            assert resp.status_code == 401
        finally:
            settings.service_token = orig_token

    def test_missing_token_when_required_returns_401(self):
        from app.main import app, settings
        orig_token = settings.service_token
        settings.service_token = "required-token"
        try:
            client = TestClient(app)
            resp = client.post("/v1/conversation/tasks",
                               json=_payload(), headers=_headers())
            assert resp.status_code == 401
        finally:
            settings.service_token = orig_token


# ---------------------------------------------------------------------------
# Idempotency — key reuse with different fingerprint → 409
# ---------------------------------------------------------------------------

class TestIdempotencyKeyReuse:
    def test_different_payload_same_key_returns_409(self):
        from app.main import app, cache
        cache.clear()
        task = _good_task()
        with patch("app.main.run_workflow", new=AsyncMock(return_value=task)):
            client = TestClient(app)
            first = client.post("/v1/conversation/tasks",
                                json=_payload(), headers=_headers())
            assert first.status_code == 200
            # Same key, different payload (different turn)
            second_payload = {**_payload(), "turnNumber": 2}
            second = client.post("/v1/conversation/tasks",
                                 json=second_payload, headers=_headers())
            assert second.status_code == 409
        cache.clear()


# ---------------------------------------------------------------------------
# TTL expiry
# ---------------------------------------------------------------------------

class TestCacheTTL:
    def test_expired_entry_triggers_re_generation(self):
        from app.main import app, cache, settings
        cache.clear()
        orig_ttl = settings.cache_ttl_seconds
        settings.cache_ttl_seconds = 0.01  # 10 ms TTL
        try:
            task = _good_task()
            call_count = {"n": 0}

            async def mock_workflow(*_a, **_kw):
                call_count["n"] += 1
                return task

            with patch("app.main.run_workflow", new=mock_workflow):
                client = TestClient(app)
                r1 = client.post("/v1/conversation/tasks",
                                 json=_payload("req-ttl", "key-ttl"),
                                 headers=_headers("req-ttl", "key-ttl"))
                assert r1.status_code == 200
                time.sleep(0.05)  # let TTL expire
                r2 = client.post("/v1/conversation/tasks",
                                 json=_payload("req-ttl", "key-ttl"),
                                 headers=_headers("req-ttl", "key-ttl"))
                assert r2.status_code == 200
                assert call_count["n"] == 2  # generated twice after TTL
        finally:
            settings.cache_ttl_seconds = orig_ttl
            cache.clear()


# ---------------------------------------------------------------------------
# Log capture — no raw credential in generation log
# ---------------------------------------------------------------------------

class TestLogRedaction:
    def test_generation_log_does_not_contain_credential(self):
        from app.main import app, cache
        cache.clear()
        log_records: list[logging.LogRecord] = []

        class Capture(logging.Handler):
            def emit(self, record):
                log_records.append(record)

        handler = Capture()
        logging.getLogger("dictalab.ai_generation").addHandler(handler)
        try:
            task = _good_task()
            with patch("app.main.run_workflow", new=AsyncMock(return_value=task)):
                client = TestClient(app)
                client.post("/v1/conversation/tasks",
                            json=_payload(), headers=_headers())
        finally:
            logging.getLogger("dictalab.ai_generation").removeHandler(handler)
            cache.clear()

        for record in log_records:
            msg = record.getMessage() + str(record.__dict__)
            assert "user@example.com" not in msg
            assert "Bearer " not in msg


# ---------------------------------------------------------------------------
# RedactingFilter unit test
# ---------------------------------------------------------------------------

class TestRedactingFilter:
    def test_filter_redacts_record_message_and_args(self):
        from app.redaction import RedactingFilter
        f = RedactingFilter()
        record = logging.LogRecord(
            name="test", level=logging.INFO, pathname=__file__, lineno=1,
            msg="User email: user@example.com with Bearer sec123",
            args=(), exc_info=None,
        )
        assert f.filter(record) is True
        assert "user@example.com" not in record.msg
        assert "Bearer" not in record.msg
        assert "[REDACTED]" in record.msg

    def test_filter_redacts_extra_attributes(self):
        from app.redaction import RedactingFilter
        f = RedactingFilter()
        record = logging.LogRecord(
            name="test", level=logging.INFO, pathname=__file__, lineno=1,
            msg="normal event",
            args=(), exc_info=None,
        )
        record.sensitive_info = "jwt: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        record.credential_dict = {"api_key": "secret-key-12345678901234567890"}
        assert f.filter(record) is True
        assert "eyJhbGci" not in record.sensitive_info
        assert record.credential_dict["api_key"] == "[REDACTED]"


# ---------------------------------------------------------------------------
# Concurrent idempotency test
# ---------------------------------------------------------------------------

class TestConcurrentIdempotency:
    @pytest.mark.asyncio
    async def test_concurrent_identical_requests_only_generate_once(self):
        from app.main import app, cache
        import httpx
        cache.clear()
        task = _good_task()
        generation_count = {"count": 0}

        async def slow_generation(*args, **kwargs):
            generation_count["count"] += 1
            await asyncio.sleep(0.05)  # simulate async LLM latency
            return task

        with patch("app.main.run_workflow", new=slow_generation):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                payload = _payload("req-concurrent", "key-concurrent")
                headers = _headers("req-concurrent", "key-concurrent")

                # Launch two concurrent requests with identical idempotencyKey
                res1, res2 = await asyncio.gather(
                    client.post("/v1/conversation/tasks", json=payload, headers=headers),
                    client.post("/v1/conversation/tasks", json=payload, headers=headers),
                )

                assert res1.status_code == 200
                assert res2.status_code == 200
                assert res1.json()["task"] == res2.json()["task"]
                # Crucial assertion: workflow was executed exactly once!
                assert generation_count["count"] == 1
        cache.clear()

