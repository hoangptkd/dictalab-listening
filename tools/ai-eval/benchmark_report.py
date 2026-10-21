"""Assemble the CEFR benchmark report from one partial or complete run directory.

Inputs are the files the offline runner writes: manifest, capture, attempt
sidecar, deterministic table and AI review. Nothing is inferred: an arm that was
not captured is reported as missing, and AI review never becomes approval.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

FLAG_GROUPS = {
    "questionFactsAligned": "question_misalignment",
    "answerable": "unanswerable_question",
    "atomicFacts": "non_atomic_answer",
    "translationAccurate": "translation_defect",
    "rolesConsistent": "role_inconsistency",
    "continuity": "continuity_break",
    "transfer": "turn4_carryover",
    "cefrLevelAppropriate": "cefr_level_mismatch",
    "taskTypeAccurate": "tasktype_mismatch",
    "cuesSupportConclusion": "unsupported_conclusion",
    "noExternalKnowledge": "external_knowledge_required",
    "textOnly": "requires_audio",
}


def _load(path, default=None):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def _semantic_pass(record):
    verdict = record.get("verdict")
    return isinstance(verdict, dict) and all(verdict.get(flag) is True for flag in FLAG_GROUPS)


def _pct(value):
    return "n/a" if value is None else f"{value:.1%}"


def _level_summary(table, reviewed, *, complete):
    records = {f"{r['id']}:{r['seed']}": r for r in reviewed["records"]}
    out = {}
    for level, stats in table["levels"].items():
        rows = [row for row in table["rows"] if row["level"] == level]
        passed = [row for row in rows if _semantic_pass(records.get(row["key"], {}))]
        captured = [row for row in rows if row["key"] in records]
        entry = dict(stats)
        entry["semanticReviewPassed"] = len(passed)
        entry["semanticReviewRate"] = round(len(passed) / len(rows), 4) if rows else None
        entry["semanticReviewRateOnCaptured"] = round(len(passed) / len(captured), 4) if captured else None
        entry["finalValidOnCaptured"] = (round(sum(1 for row in captured if row["final"]["valid"]) / len(captured), 4)
                                         if captured else None)
        entry["complete"] = complete
        out[level] = entry
    return out


def _errors(table, reviewed):
    records = {f"{r['id']}:{r['seed']}": r for r in reviewed["records"]}
    errors = []
    for row in table["rows"]:
        record = records.get(row["key"], {})
        if not record:
            continue  # samples that were never run belong to coverage, not the error table
        verdict = record.get("verdict") or {}
        categories = sorted({label for flag, label in FLAG_GROUPS.items() if verdict.get(flag) is False})
        if not record.get("task"):
            categories = ["no_task_or_rejected"]
        elif not categories and row["final"]["validationErrors"]:
            categories = ["deterministic_rejection"]
        if not categories:
            continue
        errors.append({"key": row["key"], "level": row["level"], "transport": row["final"]["transport"],
                       "categories": categories,
                       "firstAttemptErrors": row["firstAttempt"]["validationErrors"],
                       "repairErrors": row["repair"]["validationErrors"],
                       "finalErrors": row["final"]["validationErrors"],
                       "note": (record.get("review") or {}).get("note", ""),
                       "reproduce": "python tools/ai-eval/generate.py --dataset <dataset> --output <new-capture>.json "
                                    f"--attempts <new>.jsonl  # key {row['key']}"})
    return errors


def build(run_dir):
    manifest = _load(run_dir / "manifest.json", {})
    supported_reviewed = _load(run_dir / "supported-reviewed.json")
    supported_table = _load(run_dir / "supported-table.json")
    supported_report = _load(run_dir / "supported-base-report.json")
    shadow_reviewed = _load(run_dir / "shadow-reviewed.json")
    shadow_table = _load(run_dir / "shadow-partial-table.json")
    shadow_report = _load(run_dir / "shadow-report.json")

    supported_levels = _level_summary(supported_table, supported_reviewed, complete=True)
    shadow_levels = _level_summary(shadow_table, shadow_reviewed, complete=False)
    supported_ok = supported_table["recordsCaptured"] == supported_table["recordsExpected"]
    shadow_ok = shadow_table["recordsCaptured"] == shadow_table["recordsExpected"]
    supported_schema_ok = (supported_report["metrics"]["schema"]["rate"] == 1
                           and supported_report["metrics"]["evidence"]["rate"] == 1)
    supported_length_ok = all(stats["finalLengthValidOnExpected"] >= .95 for stats in supported_levels.values())
    supported_semantic_ok = all((stats["semanticReviewRate"] or 0) >= .9 for stats in supported_levels.values())
    supported_decision = "PASS_AI_REVIEW_ONLY" if (supported_schema_ok and supported_length_ok
                                                   and supported_semantic_ok) else "FAIL"
    return {
        "reportVersion": 1,
        "kind": "cefr-real-model-benchmark",
        "runId": run_dir.name,
        "status": "partial" if not (supported_ok and shadow_ok) else "complete",
        "statusNote": ("Supported arm complete; shadow arm stopped on owner request after "
                       f"{shadow_table['recordsCaptured']}/{shadow_table['recordsExpected']} samples. "
                       "The remaining shadow samples were not run and no quality claim covers them."
                       if not shadow_ok else "Both arms captured in full."),
        "plan": manifest.get("plan"),
        "git": manifest.get("git"),
        "environment": {"runtime": manifest.get("runtime"), "hardware": manifest.get("hardware"),
                        "model": manifest.get("model"), "timeoutSeconds": manifest.get("timeoutSeconds"),
                        "generationConfig": manifest.get("generationConfig")},
        "observation": manifest.get("observation"),
        "arms": {
            "supported": {"dataset": "cefr-b1-v7-10x3.json", "bundle": "conversation-cefr-b1-v7-20261021",
                          "datasetHash": supported_table["datasetHash"],
                          "expected": supported_table["recordsExpected"],
                          "captured": supported_table["recordsCaptured"],
                          "metrics": supported_report["metrics"],
                          "latencySeconds": supported_report["latency_seconds"],
                          "levels": supported_levels},
            "shadow": {"dataset": "cefr-shadow-v1-10x3.json", "bundle": "conversation-cefr-shadow-v1-20261021",
                       "datasetHash": shadow_table["datasetHash"],
                       "expected": shadow_table["recordsExpected"],
                       "captured": shadow_table["recordsCaptured"],
                       "missing": len(shadow_table["missingKeys"]),
                       "levels": shadow_levels,
                       "shadowGate": shadow_report["gate"],
                       "candidateGatePassed": {level: stats["candidateGatePassed"]
                                               for level, stats in shadow_report["levels"].items()}},
        },
        "review": {"method": "AI semantic review of every captured sample (12 flags); not manual approval",
                   "supportedManualReview": None, "shadowManualReview": None,
                   "packet": "data/cefr-benchmark/<runId>/supported-review-packet.md (gitignored evidence)"},
        "gate": {
            "supported": {
                "schemaAndGrounding100Percent": supported_schema_ok,
                "length95PercentOnExpected": supported_length_ok,
                "semantic90PercentOnExpected": supported_semantic_ok,
                "decision": supported_decision,
                "reason": ("All planned thresholds met under AI review; manual review and promotion are separate decisions."
                           if supported_decision.startswith("PASS") else
                           "Structure/grounding, length or AI semantic review is below the planned thresholds; "
                           "manual review has not happened."),
            },
            "shadow": {"decision": "NOT EVALUATED", "releaseAllowed": False,
                       "reason": f"Coverage {shadow_table['recordsCaptured']}/{shadow_table['recordsExpected']}; "
                                 "shadow tool always returns releaseAllowed=false."},
            "releaseAllowed": False,
        },
        "errorTable": _errors(supported_table, supported_reviewed) + _errors(shadow_table, shadow_reviewed),
        "limits": [
            "AI review by the same agent that ran the benchmark; not independent human adjudication.",
            "B1 supported arm has 9 samples only; A1/A2 cannot compensate for B1.",
            "Shadow arm is partial (B2 3/30 captured, C1 0/30) and uses the compact 30-90 word format; "
            "it does not test long B2/C1 listening.",
            "600s timeout is an offline quality setting; it does not prove the 180s default or production SLA.",
            "CPU-only inference; cold start is included in latency.",
            "The observer records attempts but does not change prompts, seeds, schemas, options or repair count.",
        ],
        "nextSteps": [
            "Resume the remaining 57 shadow samples with the same bundle, dataset, seeds and environment, "
            "writing a new raw capture plus sidecar, then merge by key with duplicate/missing checks.",
            "Owner manual review of the 9 B1 samples and at least 6 B2 / 6 C1 once the shadow arm is complete; "
            "AI verdicts are not approval.",
            "Fix the repair loop: 5/5 supported failures repeated the same length or leak error, wasting a second call.",
            "Close the validator gap where fact textVi containing serialized JSON (shadow library seed 17) passes.",
            "Tighten role handling: the role metric is 19/30; 6 samples actually invert customer/staff or mix two speakers in one utterance, 5 more have no output.",
        ],
    }


def to_markdown(report):
    supported = report["arms"]["supported"]
    shadow = report["arms"]["shadow"]
    lines = [f"# Báo cáo benchmark CEFR bằng model thật — {report['runId']}", "",
             f"- Trạng thái: **{report['status']}** — {report['statusNote']}",
             f"- Model: `{report['environment']['model']['name']}` "
             f"(`{report['environment']['model']['digest']}`), timeout {report['environment']['timeoutSeconds']}s",
             f"- HEAD: `{report['git']['head']}` ({report['git']['branch']})",
             f"- Cấu hình: {json.dumps(report['environment']['generationConfig'], ensure_ascii=False)}", "",
             "## Đợt supported A1/A2/B1", "",
             f"Coverage {supported['captured']}/{supported['expected']}.", "",
             "| Mức | First length | First structure | Repair | Final valid | Final length (expected) | Semantic (AI) |",
             "|---|---|---|---|---|---|---|"]
    for level, stats in supported["levels"].items():
        lines.append(f"| {level} | {stats['firstAttemptLengthValid']:.1%} | {stats['firstAttemptStructureValid']:.1%} "
                     f"| {stats['repairAttempted']} (ok {stats['repairSucceeded']}) | {stats['finalValid']:.1%} "
                     f"| {stats['finalLengthValidOnExpected']:.1%} | {stats['semanticReviewRate']:.1%} |")
    metrics = supported["metrics"]
    lines += ["", "| Metric | Passed/evaluated |", "|---|---|"]
    lines += [f"| {name} | {value['passed']}/{value['evaluated']} |" for name, value in metrics.items()]
    lines += ["", f"Latency p50/p95/p99: {supported['latencySeconds']['p50']:.1f}s / "
                  f"{supported['latencySeconds']['p95']:.1f}s / {supported['latencySeconds']['p99']:.1f}s", "",
              "## Đợt shadow B2/C1 (một phần)", "",
              f"Coverage {shadow['captured']}/{shadow['expected']} — còn {shadow['missing']} mẫu chưa chạy. "
              "Tỷ lệ dưới đây tính trên mẫu **đã chạy**; tỷ lệ trên mẫu dự kiến nằm trong JSON.", "",
              "| Mức | Capture | Final valid (captured) | Semantic (AI, captured) | Trong khoảng từ (present) |",
              "|---|---|---|---|---|"]
    for level, stats in shadow["levels"].items():
        lines.append(f"| {level} | {stats['captured']}/{stats['expected']} | {_pct(stats['finalValidOnCaptured'])} "
                     f"| {_pct(stats['semanticReviewRateOnCaptured'])} | {_pct(stats['finalLengthValidOnPresent'])} |")
    lines += ["", f"Shadow gate: `{shadow['shadowGate']}`; candidateGatePassed: {shadow['candidateGatePassed']}", "",
              "## Bảng lỗi đã xác nhận", "", "| Key | Mức | Nhóm lỗi | Ghi chú |", "|---|---|---|---|"]
    for error in report["errorTable"]:
        lines.append(f"| {error['key']} | {error['level']} | {', '.join(error['categories'])} | {error['note']} |")
    levels = supported["levels"]
    first_length_fail = {level: round((1 - stats["firstAttemptLengthValid"]) * stats["expected"])
                         for level, stats in levels.items()}
    repair_attempted = sum(stats["repairAttempted"] for stats in levels.values())
    repair_ok = sum(stats["repairSucceeded"] for stats in levels.values())
    rejected = sum(stats["finalTransportFailures"] for stats in levels.values())
    lines += ["", "## Kết luận", "",
              f"Gate supported: **{report['gate']['supported']['decision']}** — {report['gate']['supported']['reason']}",
              f"Gate shadow: **{report['gate']['shadow']['decision']}** — {report['gate']['shadow']['reason']}",
              "Không có release, không đổi trạng thái SUPPORTED, không chạy C2.", "",
              f"- First-attempt sai độ dài: {first_length_fail} trên mẫu dự kiến từng mức.",
              f"- Repair: gọi {repair_attempted} lần, thành công {repair_ok}, còn {rejected} mẫu cuối bị reject.",
              f"- A1 final {levels['A1']['finalValid']:.1%}, semantic AI {levels['A1']['semanticReviewRate']:.1%}; "
              f"A2 final {levels['A2']['finalValid']:.1%}, semantic AI {levels['A2']['semanticReviewRate']:.1%}; "
              f"B1 final {levels['B1']['finalValid']:.1%}, semantic AI {levels['B1']['semanticReviewRate']:.1%}.",
              "- Chưa kết luận được độ phân biệt B1/B2/C1: shadow mới có 3/60 mẫu và B1 chỉ có 9 mẫu.", "",
              "## Giới hạn", "", *[f"- {item}" for item in report["limits"]], "",
              "## Việc tiếp theo", "", *[f"{i}. {item}" for i, item in enumerate(report["nextSteps"], 1)], ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    report = build(args.run)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(to_markdown(report) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
