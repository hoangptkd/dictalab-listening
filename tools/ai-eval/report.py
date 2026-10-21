"""Canonical hashes and a fail-closed offline release comparison."""
import hashlib
import json
import math
import re
from datetime import datetime, timezone

from metrics import aggregate


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def build_report(dataset, rows, metadata, timestamp=None):
    expected = {f'{case["id"]}:{seed}' for case in dataset["cases"] for seed in dataset["seeds"]}
    keys = [r["key"] for r in rows]
    if not expected or set(keys) != expected or len(keys) != len(expected):
        raise ValueError("dataset coverage mismatch or duplicate case/seed")
    for field in ("model", "modelDigest", "promptVersion", "promptHash", "config", "judgeVersion"):
        if not metadata.get(field):
            raise ValueError("missing evaluation metadata: " + field)
    for field in ("modelDigest", "promptHash"):
        if not isinstance(metadata[field], str) or not re.fullmatch(r"(?:sha256:)?[a-f0-9]{64}", metadata[field]):
            raise ValueError("unpinned evaluation metadata: " + field)
    rows = sorted(rows, key=lambda r: r["key"])
    sample = sorted(keys, key=lambda k: digest(k))[:max(1, round(len(keys)*0.2))]
    return {"reportVersion": 1, "datasetHash": digest(dataset), "metadata": metadata,
            "timestamp": timestamp or datetime.now(timezone.utc).isoformat(),
            "coverage": len(rows), **aggregate(rows), "rows": rows,
            "reviewSample": [{"key": k, "reviewer": "pending"} for k in sample],
            "approvalStatus": "unreviewed"}


def compare(baseline, candidate):
    reasons = []
    if baseline["datasetHash"] != candidate["datasetHash"]:
        reasons.append("dataset_hash_mismatch")
    for field in ("model", "modelDigest", "config", "judgeVersion"):
        if not baseline["metadata"].get(field) or baseline["metadata"].get(field) != candidate["metadata"].get(field):
            reasons.append("incomparable_" + field)
    if baseline.get("approvalStatus") != "approved":
        reasons.append("baseline_not_approved")
    for report in (baseline, candidate):
        if report["coverage"] <= 0:
            reasons.append("empty_report")
            continue
        if report["verdict_status"]["valid"] != report["coverage"]:
            reasons.append("incomplete_judging")
        sample = report.get("reviewSample", [])
        expected_sample = sorted((r["key"] for r in report["rows"]), key=lambda k: digest(k))[:max(1, round(report["coverage"]*0.2))]
        if sorted(r.get("key", "") for r in sample) != sorted(expected_sample) or not 0.1 <= len(sample) / report["coverage"] <= 0.2 or any(
                r.get("reviewer") not in ("human", "ai") or not r.get("reviewReference") for r in sample):
            reasons.append("review_sample_incomplete")
        if any(r.get("requiresAdjudication") for r in sample):
            reasons.append("review_disagreement")
    for name, old in baseline["metrics"].items():
        new = candidate["metrics"][name]
        if old["evaluated"] != new["evaluated"] or old["rate"] is None or new["rate"] is None or not all(
                math.isfinite(v) and 0 <= v <= 1 for v in (old["rate"], new["rate"])):
            reasons.append("incomplete_" + name)
        if old["rate"] is not None and new["rate"] is not None and new["rate"] < old["rate"]:
            reasons.append("regression_" + name)
    return {"releaseAllowed": not reasons, "reasons": sorted(set(reasons))}
