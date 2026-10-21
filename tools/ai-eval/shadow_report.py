"""Offline CEFR shadow report. Never grants release or changes learner levels."""
import argparse
import json
from pathlib import Path

import generate  # Initializes the existing AI service import path.
from app.config import Settings
from app.registry import load_artifact
from app.shadow import ARTIFACT_VERSION
from app.validation import validate_task
from metrics import FLAGS, score
from report import digest


def build_shadow_report(dataset, capture, artifact):
    if artifact.get("evaluationOnly") is not True:
        raise ValueError("not a shadow artifact")
    metadata = capture.get("metadata", {})
    if (capture.get("datasetHash") != digest(dataset) or artifact["datasetHash"] != digest(dataset)
            or metadata.get("promptHash") != artifact["sha256"]
            or metadata.get("promptVersion") != artifact["version"] or metadata.get("evaluationOnly") is not True
            or metadata.get("modelDigest") != artifact["model"]["digest"] or metadata.get("model") != artifact["model"]["name"]
            or metadata.get("config") != artifact["generationConfig"]):
        raise ValueError("capture does not match the frozen shadow dataset/artifact/config/model")
    cases = {case["id"]: case for case in dataset["cases"]}
    if len(cases) != len(dataset["cases"]) or len(set(dataset["seeds"])) != len(dataset["seeds"]):
        raise ValueError("duplicate case or seed")
    expected = {(case, seed) for case in cases for seed in dataset["seeds"]}
    records = {}
    for record in capture["records"]:
        key = (record["id"], record["seed"])
        if key not in expected or key in records:
            raise ValueError("unexpected or duplicate capture record")
        records[key] = record
    rows = []
    required_flags = {flag for fields in FLAGS.values() for flag in fields} | set(artifact["reviewRubric"])
    for case_id, seed in sorted(expected):
        case = cases[case_id]
        level = case["context"]["difficulty"]
        profile = artifact["levelProfiles"].get(level, {})
        if level not in ("B2", "C1") or profile.get("status") != "SHADOW":
            raise ValueError("dataset contains an unavailable shadow level")
        if not case["expectedTaskTypes"] or not set(case["expectedTaskTypes"]).issubset(profile["taskTypes"]):
            raise ValueError("dataset requests an unsupported task type")
        record = records.get((case_id, seed), {})
        task, verdict = record.get("task"), record.get("verdict")
        errors = validate_task(task, level=level, shadow_profile=profile) if isinstance(task, dict) else ["task:missing"]
        metrics = score(task, case["context"], verdict, record.get("transport", "missing"))
        types = {f.get("taskType") for f in task.get("facts", []) if isinstance(f, dict)
                 and isinstance(f.get("taskType"), str)} if isinstance(task, dict) and isinstance(task.get("facts"), list) else set()
        focus_matched = set(case["expectedTaskTypes"]).issubset(types)
        valid_review = isinstance(verdict, dict) and all(type(verdict.get(flag)) is bool for flag in required_flags)
        applicable = [value for name, value in metrics.items() if name in FLAGS and value is not None]
        semantic = bool(valid_review and all(applicable) and all(verdict[flag] for flag in artifact["reviewRubric"]))
        manual = record.get("manualReview", {})
        manual_ok = (isinstance(manual, dict) and manual.get("status") == "approved"
                     and isinstance(manual.get("reference"), str) and bool(manual["reference"].strip()))
        structure_errors = [error for error in errors if not error.startswith("utteranceEn:word_count=")]
        rows.append({"key": f"{case_id}:{seed}", "level": level, "expectedTaskTypes": case["expectedTaskTypes"],
                     "transport": metrics["transport"], "structure": metrics["schema"] and not structure_errors,
                     "length": isinstance(task, dict) and not any("word_count=" in error for error in errors),
                     "focusMatched": focus_matched, "semanticReview": semantic, "manualReview": manual_ok,
                     "validationErrors": errors, "reviewStatus": "valid" if valid_review else "missing_or_invalid"})
    levels = {}
    for level in ("B2", "C1"):
        samples = [row for row in rows if row["level"] == level]
        required_types = {t for row in samples for t in row["expectedTaskTypes"]}
        reviewed = {t for row in samples if row["manualReview"] and row["semanticReview"] and row["structure"]
                    and row["transport"] and row["focusMatched"] for t in row["expectedTaskTypes"]}
        rates = {name: sum(row[name] for row in samples) / len(samples) if samples else 0
                 for name in ("transport", "structure", "length", "focusMatched", "semanticReview")}
        levels[level] = {"expected": len(samples), "rates": rates, "manualCoverageMissing": sorted(required_types - reviewed),
                         "candidateGatePassed": bool(samples and rates["transport"] == rates["structure"] == rates["focusMatched"] == 1
                             and rates["length"] >= .95 and rates["semanticReview"] >= .9 and not required_types - reviewed)}
    return {"datasetHash": digest(dataset), "metadata": metadata, "capturedRecords": len(records),
            "expectedRecords": len(expected), "levels": levels, "rows": rows,
            "gate": {"releaseAllowed": False, "reason": "shadow_only_requires_separate_promotion_review"}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("report already exists; select a new output")
    load = lambda path: json.loads(path.read_text(encoding="utf-8"))
    artifact = load_artifact(Settings(artifact_version=ARTIFACT_VERSION))
    result = build_shadow_report(load(args.dataset), load(args.capture), artifact)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"expected": result["expectedRecords"], "captured": result["capturedRecords"], "releaseAllowed": False}))


if __name__ == "__main__":
    main()
