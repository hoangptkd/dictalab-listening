"""Redaction utilities for log sanitisation.

Scans strings and nested JSON structures for patterns that resemble credentials
(email addresses, JWT tokens, Bearer tokens, API keys, cookies, secret-like
strings) and replaces them with a fixed placeholder before any logging occurs.

Rules
-----
- Operates on strings and recursively on dict/list structures.
- Never raises; returns original value on unexpected types.
- Does NOT modify the original object — returns a new sanitised copy.
- Short alphanumeric values (< 8 chars) are not treated as secrets to avoid
  over-redacting legitimate short content fields.

Patterns redacted
-----------------
- Email addresses: ``user@example.com``
- Bearer tokens: ``Bearer <token>``
- JWT strings: three base64url segments separated by dots
- Hex or base64 strings ≥ 32 chars (API keys, hashes passed as values)
- ``Authorization``, ``X-Service-Token``, ``X-Api-Key``, ``Cookie``,
  ``Set-Cookie`` header values in dicts
- Dict keys that look like credential keys (password, secret, token, key,
  api_key, access_token, refresh_token, cookie, authorization, credential)
"""
from __future__ import annotations

import logging
import re
from typing import Any

_REDACTED = "[REDACTED]"

# Patterns applied to string values
_EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}", re.ASCII)
_BEARER_RE = re.compile(r"Bearer\s+\S+", re.IGNORECASE)
# JWT: header.payload.signature (base64url segments)
_JWT_RE = re.compile(
    r"eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+", re.ASCII
)
# Long opaque hex or base64url strings (≥ 32 chars) — typical API key shape
_OPAQUE_KEY_RE = re.compile(r"[A-Za-z0-9\-_+/]{32,}={0,2}", re.ASCII)

# Dict keys whose values should always be redacted (case-insensitive)
_CREDENTIAL_KEYS: frozenset[str] = frozenset({
    "password", "passwd", "secret", "token", "api_key", "apikey",
    "access_token", "refresh_token", "id_token", "cookie", "set-cookie",
    "authorization", "x-service-token", "x-api-key", "credential",
    "private_key", "client_secret",
})

# HTTP header names whose values should be redacted
_CREDENTIAL_HEADERS: frozenset[str] = frozenset({
    "authorization", "x-service-token", "x-api-key", "cookie", "set-cookie",
    "x-auth-token", "x-access-token",
})


def redact_string(value: str) -> str:
    """Replace credential-like patterns in *value* with ``[REDACTED]``."""
    value = _BEARER_RE.sub(_REDACTED, value)
    value = _JWT_RE.sub(_REDACTED, value)
    value = _EMAIL_RE.sub(_REDACTED, value)
    # Only redact long opaque strings to avoid matching short words/numbers
    value = _OPAQUE_KEY_RE.sub(
        lambda m: _REDACTED if len(m.group()) >= 32 else m.group(), value
    )
    return value


def redact(value: Any, *, _depth: int = 0) -> Any:
    """Recursively sanitise *value* and return a new cleaned copy.

    Stops recursing after depth 20 to prevent stack overflow on pathological
    inputs; deeper levels are replaced with ``[REDACTED:depth]``.
    """
    if _depth > 20:
        return "[REDACTED:depth]"
    if isinstance(value, str):
        return redact_string(value)
    if isinstance(value, dict):
        return _redact_dict(value, _depth)
    if isinstance(value, (list, tuple)):
        cleaned = [redact(item, _depth=_depth + 1) for item in value]
        return type(value)(cleaned)
    # Numbers, booleans, None — safe as-is
    return value


def _redact_dict(d: dict[str, Any], depth: int) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for k, v in d.items():
        key_lower = k.lower() if isinstance(k, str) else ""
        if key_lower in _CREDENTIAL_KEYS or key_lower in _CREDENTIAL_HEADERS:
            result[k] = _REDACTED
        else:
            result[k] = redact(v, _depth=depth + 1)
    return result


def is_safe_log_value(value: str) -> bool:
    """Return True if *value* contains no detectable credential pattern.

    Convenience helper for assertions in tests.
    """
    if _EMAIL_RE.search(value):
        return False
    if _BEARER_RE.search(value):
        return False
    if _JWT_RE.search(value):
        return False
    return True


class RedactingFilter(logging.Filter):
    """Logging filter that ensures records never contain credential-like patterns."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = redact_string(record.msg)
        if isinstance(record.args, dict):
            record.args = redact(record.args)
        elif isinstance(record.args, (list, tuple)):
            record.args = tuple(redact(arg) for arg in record.args)
        for key in list(record.__dict__.keys()):
            if key not in (
                "name", "msg", "args", "levelname", "levelno", "pathname", "filename",
                "module", "exc_info", "exc_text", "stack_info", "lineno", "funcName",
                "created", "msecs", "relativeCreated", "thread", "threadName",
                "processName", "process", "taskName"
            ):
                record.__dict__[key] = redact(record.__dict__[key])
        return True

