"""Shared Gemini project quotas and admission-only routing; never retries a sent request."""
from __future__ import annotations

from collections import deque
from datetime import datetime, time as daytime, timedelta
from functools import lru_cache
from pathlib import Path
from threading import RLock
from zoneinfo import ZoneInfo
import logging
import math
import time
import uuid
from email.utils import parsedate_to_datetime
from types import SimpleNamespace
import json

import redis

from .providers.base import ProviderError

log = logging.getLogger("dictalab.gemini_quota")
PACIFIC = ZoneInfo("America/Los_Angeles")
SCRIPT = Path(__file__).with_name("gemini_quota.lua").read_text(encoding="utf-8")


class QuotaUnavailable(ProviderError):
    """Admission failed before any HTTP request was sent."""
    def __init__(self, retry_after=5):
        super().__init__("gemini:quota_unavailable")
        self.retry_after = max(1, retry_after)


def pacific_day(epoch):
    day = datetime.fromtimestamp(epoch, PACIFIC).date()
    start = datetime.combine(day, daytime.min, PACIFIC).timestamp()
    end = datetime.combine(day + timedelta(days=1), daytime.min, PACIFIC).timestamp()
    return str(day), int(start * 1000), int(end * 1000)


class QuotaStore:
    def __init__(self, settings, client=None, clock=time.time):
        self.settings, self.clock = settings, clock
        self.lock, self.cursor = RLock(), 0
        self.windows, self.daily, self.states, self.stats = {}, {}, {}, {}
        self.client = client
        if settings.gemini_quota_backend == "redis" and self.client is None:
            self.client = redis.Redis.from_url(settings.gemini_quota_redis_url,
                socket_timeout=1, socket_connect_timeout=1, retry_on_timeout=False, decode_responses=True)

    def _keys_args(self, source, tokens, op, delay):
        try:
            seconds, microseconds = self.client.time()
        except redis.RedisError:
            raise QuotaUnavailable() from None
        day, start, end = pacific_day(seconds + microseconds / 1_000_000)
        platform = getattr(self.settings, "quota_platform", "gemini")
        root = "{dictalab-gemini-quota}:v2:" if platform == "gemini" else "{dictalab-ai-quota}:v2:" + platform + ":"
        prefix = root + source.project_id + ":" + source.model
        keys = [prefix + ":requests", prefix + ":tokens", prefix + ":day:" + day,
            root + "global:requests", root + "global:day:" + day, prefix + ":state", prefix + ":metrics"]
        args = [op, tokens, source.effective_rpm, source.tpm_limit, source.rpd_limit,
            self.settings.gemini_global_rpm_limit, self.settings.gemini_global_rpd_limit,
            str(uuid.uuid4()), start, end, 5, 30000, delay * 1000]
        return keys, args

    def execute(self, source, tokens=1, op="reserve", delay=0):
        if tokens < 1:
            raise ValueError("Gemini token reservation must be positive")
        if self.settings.gemini_quota_backend == "memory":
            return self._memory(source, tokens, op, delay)
        keys, args = self._keys_args(source, tokens, op, delay)
        try:
            result = self.client.eval(SCRIPT, len(keys), *keys, *args)
            if op == "status" and len(result) != 6:
                raise QuotaUnavailable(1)
            return result
        except redis.RedisError:
            raise QuotaUnavailable() from None

    def _memory(self, source, tokens, op, delay):
        with self.lock:
            now = self.clock()
            day, _, end = pacific_day(now)
            key = source.project_id + ":" + source.model
            state = self.states.setdefault(key, {"until": 0, "failures": 0})
            stats = self.stats.setdefault(key, {})
            if op not in ("reserve", "status"):
                stats[op] = stats.get(op, 0) + 1
                if op == "success":
                    state["failures"] = 0
                elif op == "429":
                    state["until"] = max(state["until"], now + delay)
                elif op == "failure":
                    state["failures"] += 1
                    if state["failures"] >= 5:
                        state["until"] = max(state["until"], now + 30)
                return [1, 0]
            for bucket in (key, "__global__"):
                queue = self.windows.setdefault(bucket, deque())
                while queue and queue[0][0] <= now - 60:
                    queue.popleft()
                if bucket not in self.daily or self.daily[bucket][0] != day:
                    self.daily[bucket] = [day, 0]
            queue, global_queue = self.windows[key], self.windows["__global__"]
            rpm, tpm = len(queue), sum(row[1] for row in queue)
            rpd, global_rpd = self.daily[key][1], self.daily["__global__"][1]
            if op == "status":
                return [rpm, tpm, rpd, len(global_queue), global_rpd, max(0, state["until"] - now) * 1000]
            wait = max(0, state["until"] - now)
            if rpm >= source.effective_rpm:
                wait = max(wait, queue[0][0] + 60 - now)
            if source.tpm_limit and tpm + tokens > source.tpm_limit:
                wait = max(wait, 60)
            if self.settings.gemini_global_rpm_limit and len(global_queue) >= self.settings.gemini_global_rpm_limit:
                wait = max(wait, global_queue[0][0] + 60 - now)
            if ((source.rpd_limit and rpd >= source.rpd_limit) or
                    (self.settings.gemini_global_rpd_limit and global_rpd >= self.settings.gemini_global_rpd_limit)):
                wait = max(wait, end / 1000 - now)
            stats["rejected" if wait else "allowed"] = stats.get("rejected" if wait else "allowed", 0) + 1
            if wait:
                return [0, math.ceil(wait * 1000)]
            queue.append((now, tokens))
            global_queue.append((now, tokens))
            self.daily[key][1] += 1
            self.daily["__global__"][1] += 1
            return [1, 0]

    def select(self, model, tokens):
        model = model.removeprefix("models/")
        sources = [p for p in self.settings.enabled_gemini_sources if p.model == model]
        if not sources:
            raise QuotaUnavailable(60)
        with self.lock:
            start = self.cursor % len(sources)
            self.cursor += 1
        retry = 86400
        for offset in range(len(sources)):
            source = sources[(start + offset) % len(sources)]
            allowed, wait = self.execute(source, tokens)
            if allowed:
                return source
            retry = min(retry, max(1, math.ceil(wait / 1000)))
        raise QuotaUnavailable(retry)

    def report(self, source, outcome, retry_after=0):
        try:
            self.execute(source, op=outcome, delay=retry_after)
        except QuotaUnavailable:
            # Reporting follows HTTP: never turn it into a retry-safe admission failure.
            log.warning("gemini_quota_outcome_unavailable")

    def metrics(self):
        lines = []
        for source in self.settings.enabled_gemini_sources:
            values = self.execute(source, op="status")
            label = f'provider="{source.id}",model="{source.model}"'
            for name, value in zip(("rpm_used", "tpm_reserved", "rpd_used", "global_rpm_used", "global_rpd_used", "cooldown_ms"), values):
                lines.append(f"dictalab_gemini_{name}{{{label}}} {value}")
            if source.rpd_limit:
                lines.append(f"dictalab_gemini_rpd_remaining_ratio{{{label}}} {max(0, 1 - values[2]/source.rpd_limit)}")
            if self.settings.gemini_quota_backend == "redis":
                keys, _ = self._keys_args(source, 1, "status", 0)
                stats = self.client.hgetall(keys[6])
            else:
                stats = self.stats.get(source.project_id + ":" + source.model, {})
            for outcome in ("allowed", "rejected", "success", "429", "failure"):
                lines.append(f'dictalab_gemini_events_total{{{label},outcome="{outcome}"}} {stats.get(outcome, 0)}')
        return "\n".join(lines) + "\n"


@lru_cache(maxsize=32)
def _cached_store(config):
    from .config import GeminiSource
    values = json.loads(config)
    values["enabled_gemini_sources"] = [GeminiSource.model_validate(p) for p in values["enabled_gemini_sources"]]
    return QuotaStore(SimpleNamespace(**values))


def quota_store(settings, platform=None):
    # Request deadlines/temperatures must not create independent admission budgets.
    values = {name: getattr(settings, name) for name in ("gemini_quota_backend", "gemini_quota_redis_url",
        "gemini_global_rpm_limit", "gemini_global_rpd_limit")}
    platform = platform or (settings.selected_provider if settings.selected_provider != "ollama" else "gemini")
    values["quota_platform"] = platform
    if platform in settings.registry_pools:
        policy = settings.registry_pools[platform]
        values["gemini_global_rpm_limit"], values["gemini_global_rpd_limit"] = policy["global_rpm_limit"], policy["global_rpd_limit"]
    values["enabled_gemini_sources"] = [p.model_dump() for p in settings.sources_for(platform)]
    return _cached_store(json.dumps(values, sort_keys=True))


def quota_metrics(settings):
    if not settings.gemini_quota_enabled:
        return ""
    try:
        return "dictalab_gemini_quota_available 1\n" + "".join(quota_store(settings, platform).metrics()
            for platform in sorted({p.platform for p in settings.enabled_cloud_sources}))
    except (QuotaUnavailable, redis.RedisError):
        return "dictalab_gemini_quota_available 0\n"


def estimate_tokens(messages, max_output, schema=None):
    import json
    # Conservative byte bound also covers Vietnamese and structured-output schema overhead.
    return len(json.dumps(messages, ensure_ascii=False).encode("utf-8")) + (
        len(json.dumps(schema).encode("utf-8")) if schema else 0) + max_output + 100


def retry_seconds(value):
    try:
        seconds = int(value) if str(value).isdigit() else math.ceil(parsedate_to_datetime(value).timestamp() - time.time())
        return min(86400, max(1, seconds))
    except (TypeError, ValueError, OverflowError):
        return 60
