"""Deterministic per-level benchmark table: first attempt, repair and final output.

Reads one raw capture plus its attempt sidecar (``attempt_trace`` JSONL) and
writes a JSON table whose denominators are explicit. Semantic review is never
inferred here: rows without an independent review stay ``PENDING_REVIEW``.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import generate  # Initializes the existing AI service import path.
from app.config import Settings
from app.registry import load_artifact
from app.validation import validate_task
from attempt_trace import word_count
from report import digest


def _bounds(case, artifact, level, shadow):
    if shadow:
        return dict(artifact["levelProfiles"][level]["wordBounds"])
    profile = case["context"].get("levelProfile") or {}
    base = artifact["levelProfiles"][level]["wordBounds"]
    return {"minWords": profile.get("minWords", base["minWords"]),
            "maxWords": profile.get("maxWords", base["maxWords"])}


def _validate(task, case, artifact, level, shadow):
    if not isinstance(task, dict):
        return ["task:missing"]
    if shadow:
        return validate_task(task, level=level, shadow_profile=artifact["levelProfiles"][level])
    return validate_task(task, level=level, level_profile=case["context"].get("levelProfile"))


def _length_ok(errors, words, bounds):
    if words is None:
        return None
    return bool(bounds["minWords"] <= words <= bounds["maxWords"]) and not any(
        error.startswith("utteranceEn:word_count=") for error in errors)


def _percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))]


def _rate(passes, total):
    return round(sum(1 for value in passes if value) / total, 4) if total else None


def build_table(dataset, capture, attempts, artifact, *, shadow):
    expected_keys = {(case["id"], seed) for case in dataset["cases"] for seed in dataset["seeds"]}
    records = {(record["id"], record["seed"]): record for record in capture["records"]}
    if len(records) != len(capture["records"]):
        raise ValueError("duplicate capture record")
    if set(records) - expected_keys:
        raise ValueError("unexpected capture record")
    rows_by_key = {}
    for row in attempts:
        rows_by_key.setdefault(row["requestId"], []).append(row)
    rows = []
    for case in dataset["cases"]:
        level = case["context"]["difficulty"]
        bounds = _bounds(case, artifact, level, shadow)
        for seed in dataset["seeds"]:
            key = f'{case["id"]}:{seed}'
            record = records.get((case["id"], seed), {})
            attempt_rows = rows_by_key.get(key, [])
            first = next((row for row in attempt_rows if row["attempt"] == 1 and row["phase"] == "validate"), None)
            second = next((row for row in attempt_rows if row["attempt"] == 2 and row["phase"] == "validate"), None)
            failures = [row for row in attempt_rows if row["phase"] != "validate"]
            task = record.get("task")
            errors = _validate(task, case, artifact, level, shadow)
            final_words = word_count(task.get("utteranceEn")) if isinstance(task, dict) else None
            structural = [error for error in errors if not error.startswith("utteranceEn:word_count=")]
            types = sorted({fact["taskType"] for fact in task.get("facts", []) if isinstance(fact, dict)
                            and isinstance(fact.get("taskType"), str)}) if isinstance(task, dict) else []
            type_matched = set(case.get("expectedTaskTypes") or []).issubset(types) if shadow else None
            rows.append({
                "key": key, "level": level, "turn": case["context"]["turnNumber"], "seed": seed,
                "expectedTaskTypes": case.get("expectedTaskTypes"),
                "bounds": bounds,
                "firstAttempt": {
                    "state": ("measured" if first else "pre_validation_failure" if failures else "missing"),
                    "wordCount": first["wordCount"] if first else None,
                    "lengthValid": _length_ok(first["validationErrors"], first["wordCount"], bounds) if first else None,
                    "structureValid": (not [e for e in first["validationErrors"]
                                            if not e.startswith("utteranceEn:word_count=")]) if first else None,
                    "validationErrors": first["validationErrors"] if first else [],
                    "failure": failures[-1]["status"] if failures else None,
                    "mergeSeconds": (first or {}).get("modelCalls", {}).get("generate_content"),
                    "translateSeconds": (first or {}).get("modelCalls", {}).get("translate"),
                },
                "repair": {
                    "attempted": second is not None,
                    "succeeded": bool(second and second["status"] == "valid"),
                    "wordCount": second["wordCount"] if second else None,
                    "lengthValid": _length_ok(second["validationErrors"], second["wordCount"], bounds) if second else None,
                    "validationErrors": second["validationErrors"] if second else [],
                },
                "final": {
                    "transport": record.get("transport", "missing"),
                    "seconds": record.get("seconds"),
                    "taskPresent": isinstance(task, dict),
                    "valid": isinstance(task, dict) and not errors,
                    "wordCount": final_words,
                    "lengthValid": (bool(isinstance(task, dict) and not errors and not any(
                        error.startswith("utteranceEn:word_count=") for error in errors))
                        if isinstance(task, dict) else False),
                    "structuralErrors": structural,
                    "validationErrors": errors,
                    "taskTypes": types,
                    "taskTypeMatched": type_matched,
                },
                "semanticReview": "PENDING_REVIEW",
            })
    rows.sort(key=lambda row: (["A1", "A2", "B1", "B2", "C1"].index(row["level"]), row["key"]))
    levels = {}
    for level in ("A1", "A2", "B1", "B2", "C1"):
        samples = [row for row in rows if row["level"] == level]
        if not samples:
            continue
        expected = len(samples)
        latencies = [row["final"]["seconds"] for row in samples if isinstance(row["final"]["seconds"], (int, float))]
        levels[level] = {
            "expected": expected,
            "captured": sum(1 for row in samples if row["final"]["transport"] != "missing"),
            "coverage": _rate([row["final"]["transport"] != "missing" for row in samples], expected),
            "firstAttemptMeasured": sum(1 for row in samples if row["firstAttempt"]["state"] == "measured"),
            "firstAttemptLengthValid": _rate([row["firstAttempt"]["lengthValid"] for row in samples], expected),
            "firstAttemptStructureValid": _rate([row["firstAttempt"]["structureValid"] for row in samples], expected),
            "firstAttemptLengthValidWhenMeasured": _rate(
                [row["firstAttempt"]["lengthValid"] for row in samples
                 if row["firstAttempt"]["state"] == "measured"],
                sum(1 for row in samples if row["firstAttempt"]["state"] == "measured")),
            "repairAttempted": sum(1 for row in samples if row["repair"]["attempted"]),
            "repairSucceeded": sum(1 for row in samples if row["repair"]["succeeded"]),
            "repairFailed": sum(1 for row in samples if row["repair"]["attempted"] and not row["repair"]["succeeded"]),
            "finalValid": _rate([row["final"]["valid"] for row in samples], expected),
            "finalLengthValidOnExpected": _rate([row["final"]["lengthValid"] for row in samples], expected),
            "finalPresent": sum(1 for row in samples if row["final"]["taskPresent"]),
            "finalLengthValidOnPresent": _rate(
                [row["final"]["lengthValid"] for row in samples if row["final"]["taskPresent"]],
                sum(1 for row in samples if row["final"]["taskPresent"])),
            "finalTransportFailures": sum(1 for row in samples if row["final"]["transport"] != "ok"),
            "latencySeconds": {
                "n": len(latencies),
                "p50": _percentile(latencies, .5), "p95": _percentile(latencies, .95),
                "p99": _percentile(latencies, .99), "max": max(latencies) if latencies else None,
                "note": "cold start included; timeout 600s offline quality run",
            },
            "semanticReview": "PENDING_REVIEW",
            "taskTypeCoverage": (
                {task_type: sum(1 for row in samples if row["final"]["taskTypeMatched"] is True
                                and task_type in (row["expectedTaskTypes"] or []))
                 for task_type in sorted({t for row in samples for t in (row["expectedTaskTypes"] or [])})}
                if shadow else None),
        }
    return {
        "arm": "shadow-b2-c1" if shadow else "supported-a1-a2-b1",
        "datasetHash": digest(dataset),
        "captureDatasetHash": capture.get("datasetHash"),
        "promptVersion": capture.get("metadata", {}).get("promptVersion"),
        "promptHash": capture.get("metadata", {}).get("promptHash"),
        "attemptsRecorded": len(attempts),
        "recordsExpected": len(expected_keys),
        "recordsCaptured": len(records),
        "missingKeys": sorted(f"{case}:{seed}" for case, seed in expected_keys - set(records)),
        "levels": levels,
        "rows": rows,
        "notes": [
            "First-attempt rates use all expected samples of the level; ungenerated samples are not passes.",
            "Final length is reported on expected samples and on samples that actually returned a task.",
            "Semantic/CEFR flags require independent review and stay PENDING_REVIEW until it exists.",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--attempts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--shadow", action="store_true")
    args = parser.parse_args()
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    capture = json.loads(args.capture.read_text(encoding="utf-8"))
    attempts = [json.loads(line) for line in args.attempts.read_text(encoding="utf-8").splitlines() if line.strip()]
    settings = Settings(artifact_version="conversation-cefr-shadow-v1-20261021") if args.shadow else Settings()
    artifact = load_artifact(settings)
    if capture.get("datasetHash") != digest(dataset) or artifact["datasetHash"] != digest(dataset):
        raise ValueError("capture/dataset does not match the frozen artifact")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(build_table(dataset, capture, attempts, artifact, shadow=args.shadow),
                                      ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
