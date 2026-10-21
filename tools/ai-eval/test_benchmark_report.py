"""Report assembly: partial coverage stays visible and gates fail closed."""
import json

from benchmark_report import _errors, build

FLAGS = ["questionFactsAligned", "answerable", "atomicFacts", "translationAccurate", "rolesConsistent",
         "continuity", "transfer", "cefrLevelAppropriate", "taskTypeAccurate", "cuesSupportConclusion",
         "noExternalKnowledge", "textOnly"]


def _stats(length, semantic, **extra):
    return {"expected": 1, "captured": 1, "finalLengthValidOnExpected": length,
            "semanticReviewRate": semantic, **extra}


def _write(tmp_path, *, length=1.0, semantic=1.0, task=None, verdict=None):
    record = {"id": "a1", "seed": 17, "task": task, "verdict": verdict or {flag: True for flag in FLAGS}}
    row = {"key": "a1:17", "level": "A1", "final": {"transport": "ok", "validationErrors": [], "taskPresent": task is not None, "valid": task is not None},
           "firstAttempt": {"validationErrors": [], "state": "measured"}, "repair": {"validationErrors": []}}
    files = {
        "manifest.json": {"plan": "p", "git": {"head": "h", "branch": "b"}, "runtime": {}, "hardware": {},
                          "model": {"name": "m", "digest": "d"}, "timeoutSeconds": 600, "generationConfig": {},
                          "observation": {}},
        "supported-reviewed.json": {"records": [record]},
        "supported-table.json": {"datasetHash": "x", "recordsExpected": 1, "recordsCaptured": 1,
                                 "levels": {"A1": _stats(length, semantic)}, "rows": [row]},
        "supported-base-report.json": {"metrics": {"schema": {"rate": 1.0}, "evidence": {"rate": 1.0}},
                                       "latency_seconds": {"p50": 1.0, "p95": 2.0, "p99": 3.0}},
        "shadow-reviewed.json": {"records": []},
        "shadow-partial-table.json": {"datasetHash": "y", "recordsExpected": 60, "recordsCaptured": 0,
                                      "missingKeys": ["b2:17"], "levels": {}, "rows": []},
        "shadow-report.json": {"gate": {"releaseAllowed": False}, "levels": {}},
    }
    for name, payload in files.items():
        (tmp_path / name).write_text(json.dumps(payload), encoding="utf-8")


def test_partial_shadow_and_failed_thresholds_are_explicit(tmp_path):
    _write(tmp_path, length=0.5, semantic=0.5, verdict={**{flag: True for flag in FLAGS}, "rolesConsistent": False})
    report = build(tmp_path)
    assert report["status"] == "partial"
    assert "0/60" in report["statusNote"]
    assert report["gate"]["supported"]["decision"] == "FAIL"
    assert report["gate"]["supported"]["length95PercentOnExpected"] is False
    assert report["gate"]["supported"]["semantic90PercentOnExpected"] is False
    assert report["gate"]["releaseAllowed"] is False


def test_all_thresholds_met_is_still_ai_review_only(tmp_path):
    _write(tmp_path, length=0.95, semantic=0.9)
    report = build(tmp_path)
    assert report["gate"]["supported"]["decision"] == "PASS_AI_REVIEW_ONLY"
    assert report["gate"]["releaseAllowed"] is False
    assert report["review"]["supportedManualReview"] is None


def test_missing_task_reports_one_category(tmp_path):
    _write(tmp_path, task=None, verdict={flag: False for flag in FLAGS})
    report = build(tmp_path)
    _write(tmp_path, task=None, verdict={flag: False for flag in FLAGS})
    table = json.loads((tmp_path / "supported-table.json").read_text(encoding="utf-8"))
    reviewed = json.loads((tmp_path / "supported-reviewed.json").read_text(encoding="utf-8"))
    errors = _errors(table, reviewed)
    assert errors[0]["categories"] == ["no_task_or_rejected"]
