"""Small process-local Prometheus snapshot with bounded labels and no learner data."""
from collections import Counter

counts = Counter()
buckets = Counter()
duration_sum = Counter()
LIMITS = (1, 5, 15, 30, 60, 120, 180, 300, 600)
active = 0


def observe(status, mode, seconds):
    counts[(str(status), mode)] += 1
    duration_sum[mode] += seconds
    for limit in LIMITS:
        if seconds <= limit:
            buckets[(mode, limit)] += 1


def render():
    lines = [f"dictalab_ai_generation_active {active}", "dictalab_ai_generation_queue_depth 0"]
    for (status, mode), count in sorted(counts.items()):
        lines.append(f'dictalab_ai_requests_total{{status="{status}",mode="{mode}"}} {count}')
    for mode in ("foreground", "shadow"):
        total = sum(v for (status, m), v in counts.items() if m == mode)
        for limit in LIMITS:
            lines.append(f'dictalab_ai_duration_seconds_bucket{{mode="{mode}",le="{limit}"}} {buckets[(mode, limit)]}')
        lines.append(f'dictalab_ai_duration_seconds_bucket{{mode="{mode}",le="+Inf"}} {total}')
        lines.append(f'dictalab_ai_duration_seconds_count{{mode="{mode}"}} {total}')
        lines.append(f'dictalab_ai_duration_seconds_sum{{mode="{mode}"}} {duration_sum[mode]}')
    return "\n".join(lines) + "\n"
