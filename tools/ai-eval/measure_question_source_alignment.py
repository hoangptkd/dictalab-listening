"""Measure raw QUESTIONS output before REVIEW/Harness on saved evaluation records.

This is an offline diagnostic. It never calls a provider, rewrites evidence, or
counts a later repair as a source-output pass.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
import sys
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))

from app import scenario_assessment as assessment  # noqa: E402
from app import scenario_workflow as workflow  # noqa: E402


def _event(record, stage):
    return next((event for event in record.get("events", [])
                 if event.get("stage") == stage and event.get("status") == "RETURNED"), None)


def measure_record(path: Path):
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"file": path.name, "status": "NOT_JSON"}
    if not isinstance(record, dict):
        return {"file": path.name, "status": "NOT_A_RECORD"}
    event = _event(record, "QUESTIONS")
    if not event:
        return {"file": path.name, "status": "NO_RAW_QUESTIONS"}
    try:
        dialogue = workflow.Dialogue.model_validate(event["input"]["dialogue"])
        selected = assessment.selected_release(record.get("promptHash") or record.get("extraction", {}).get("promptHash", ""))
        model = assessment.FlexibleQuestions if selected in (
            assessment.prevention_candidate, assessment.alignment_candidate, assessment.scope_candidate, assessment.fresh_candidate) \
            else assessment.PartsQuestions
        questions = model.model_validate(event["output"])
        learning_context = event["input"]["learningContext"]
        context = SimpleNamespace(
            focusSkill=learning_context["focusSkill"],
            profile=SimpleNamespace(**learning_context["profile"]),
        )
        assessment.validate_questions(questions, dialogue,
                                      context)
        findings = assessment.question_scope_findings(questions, dialogue)
        status = "PASS" if not findings else "SCOPE_FAIL"
        return {"file": path.name, "key": record.get("key"), "version": record.get("version"),
                "status": status, "findings": [str(item) for item in findings]}
    except Exception as error:  # preserve the raw record; this is only a measurement tool
        return {"file": path.name, "key": record.get("key"), "version": record.get("version"),
                "status": "STRUCTURAL_FAIL", "error": type(error).__name__ + ": " + str(error)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    skip = {"manifest.json", "summary.json", "cases-summary.json", "grade-results.json",
            "raw-question-source-alignment.json", "raw-question-source-alignment.out.json"}
    rows = [measure_record(path) for path in sorted(args.directory.glob("*.json")) if path.name not in skip]
    counts = Counter(row["status"] for row in rows)
    eligible = [row for row in rows if row["status"] not in {"NO_RAW_QUESTIONS", "NOT_A_RECORD", "NOT_JSON"}]
    by_version = {}
    dimension_counts = Counter()
    for row in rows:
        version = row.get("version") or "unknown"
        version_counts = by_version.setdefault(version, Counter())
        version_counts[row["status"]] += 1
        for finding in row.get("findings", []):
            if ": " in finding:
                dimension_counts.update(finding.rsplit(": ", 1)[-1].split(","))
    result = {
        "directory": str(args.directory),
        "rawQuestions": len(eligible),
        "scannedFiles": len(rows),
        "counts": dict(counts),
        "byVersion": {version: dict(values) for version, values in sorted(by_version.items())},
        "scopeDimensions": dict(sorted(dimension_counts.items())),
        "records": rows,
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8", newline="\n")
    print(text)


if __name__ == "__main__":
    main()
