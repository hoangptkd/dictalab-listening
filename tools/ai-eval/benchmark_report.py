"""Assemble a CEFR benchmark report from one run directory.

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


def _first_existing(run_dir, *names):
    for name in names:
        data = _load(run_dir / name)
        if data is not None:
            return data
    return None


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


def _comparison(baseline_dir, level_summaries, metrics):
    """Compare attempt-1 metrics against another run of the same dataset/bundle."""
    baseline_table = _load(baseline_dir / "supported-table.json")
    baseline_reviewed = _load(baseline_dir / "supported-reviewed.json")
    baseline_report = _load(baseline_dir / "supported-base-report.json")
    if not (baseline_table and baseline_reviewed and baseline_report):
        return None
    baseline_levels = _level_summary(baseline_table, baseline_reviewed, complete=True)
    levels = {}
    for level, current in level_summaries.items():
        base = baseline_levels.get(level)
        if not base:
            continue
        levels[level] = {
            "baseline": {key: base.get(key) for key in (
                "expected", "firstAttemptLengthValid", "firstAttemptStructureValid",
                "finalValid", "finalLengthValidOnExpected", "semanticReviewRate")},
            "candidate": {key: current.get(key) for key in (
                "expected", "firstAttemptLengthValid", "firstAttemptStructureValid",
                "finalValid", "finalLengthValidOnExpected", "semanticReviewRate")},
        }
    metric_pairs = {name: {"baseline": baseline_report["metrics"].get(name), "candidate": value}
                    for name, value in metrics.items()}
    return {"baselineRunId": baseline_dir.name,
            "baselineModel": (baseline_report.get("metadata") or {}).get("model"),
            "baselineRepairPolicy": "repair enabled in the baseline; compare against its attempt-1 rates",
            "levels": levels, "metrics": metric_pairs}


def build(run_dir, baseline_dir=None):
    manifest = _load(run_dir / "manifest.json", {})
    supported_reviewed = _load(run_dir / "supported-reviewed.json")
    supported_table = _load(run_dir / "supported-table.json")
    supported_report = _load(run_dir / "supported-base-report.json")
    if not (supported_reviewed and supported_table and supported_report):
        raise ValueError("supported capture/table/report is incomplete")
    shadow_reviewed = _load(run_dir / "shadow-reviewed.json")
    shadow_table = _first_existing(run_dir, "shadow-table.json", "shadow-partial-table.json")
    shadow_report = _load(run_dir / "shadow-report.json")
    has_shadow = bool(shadow_reviewed and shadow_table)

    supported_levels = _level_summary(supported_table, supported_reviewed, complete=True)
    shadow_levels = _level_summary(shadow_table, shadow_reviewed, complete=False) if has_shadow else None
    supported_ok = supported_table["recordsCaptured"] == supported_table["recordsExpected"]
    shadow_ok = (shadow_table["recordsCaptured"] == shadow_table["recordsExpected"]) if has_shadow else True
    supported_schema_ok = (supported_report["metrics"]["schema"]["rate"] == 1
                           and supported_report["metrics"]["evidence"]["rate"] == 1)
    supported_length_ok = all(stats["finalLengthValidOnExpected"] >= .95 for stats in supported_levels.values())
    supported_semantic_ok = all((stats["semanticReviewRate"] or 0) >= .9 for stats in supported_levels.values())
    supported_decision = "PASS_AI_REVIEW_ONLY" if (supported_schema_ok and supported_length_ok
                                                   and supported_semantic_ok) else "FAIL"
    if not has_shadow:
        status_note = ("Supported arm captured in full; shadow arm is not in scope for this run. "
                       "No B2/C1 quality claim is made.")
    elif not shadow_ok:
        status_note = ("Supported arm complete; shadow arm stopped on owner request after "
                       f"{shadow_table['recordsCaptured']}/{shadow_table['recordsExpected']} samples. "
                       "The remaining shadow samples were not run and no quality claim covers them.")
    else:
        status_note = "Both arms captured in full."

    arms = {
        "supported": {"dataset": "cefr-b1-v7-10x3.json", "bundle": "conversation-cefr-b1-v7-20261021",
                      "datasetHash": supported_table["datasetHash"],
                      "expected": supported_table["recordsExpected"],
                      "captured": supported_table["recordsCaptured"],
                      "metrics": supported_report["metrics"],
                      "latencySeconds": supported_report["latency_seconds"],
                      "levels": supported_levels},
    }
    gate = {
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
        "releaseAllowed": False,
    }
    if has_shadow:
        arms["shadow"] = {"dataset": "cefr-shadow-v1-10x3.json",
                          "bundle": "conversation-cefr-shadow-v1-20261021",
                          "datasetHash": shadow_table["datasetHash"],
                          "expected": shadow_table["recordsExpected"],
                          "captured": shadow_table["recordsCaptured"],
                          "missing": len(shadow_table["missingKeys"]),
                          "levels": shadow_levels,
                          "shadowGate": shadow_report["gate"],
                          "candidateGatePassed": {level: stats["candidateGatePassed"]
                                                  for level, stats in shadow_report["levels"].items()}}
        gate["shadow"] = {"decision": "NOT EVALUATED", "releaseAllowed": False,
                          "reason": f"Coverage {shadow_table['recordsCaptured']}/{shadow_table['recordsExpected']}; "
                                    "shadow tool always returns releaseAllowed=false."}

    limits = [
        "AI review by the same agent that ran the benchmark; not independent human adjudication.",
        "B1 supported arm has 9 samples only; A1/A2 cannot compensate for B1.",
        "600s timeout is an offline quality setting; it does not prove the 180s default or production SLA.",
        "The observer records attempts but does not change prompts, seeds, schemas, options or repair count.",
    ]
    model = manifest.get("model") or {}
    if model.get("provider") == "shopaikey":
        limits += [
            "The frozen bundle is pinned to Ollama qwen3.5:9b; this cloud run changes provider and model, so "
            "differences cannot be attributed to the prompt alone.",
            "Shop AI requests do not carry the Ollama seed option; each seed is a repeated case label, not a "
            "reproducible sampling seed.",
        ]
    if has_shadow:
        limits.append("Shadow arm uses the compact 30-90 word format; it does not test long B2/C1 listening.")
    next_steps = [
        "Owner manual review of the nine B1 samples (and any failing sample); AI verdicts are not approval.",
        "Keep this run as provider evidence; do not promote or approve the bundle from a cloud result.",
    ]
    if has_shadow:
        next_steps.append("Finish the remaining shadow samples with the same bundle, dataset and environment, "
                          "writing a new raw capture plus sidecar and merging by key with duplicate/missing checks.")
    else:
        next_steps.append("Run the shadow arm separately if B2/C1 evidence is needed; it cannot run on Shop AI.")

    report = {
        "reportVersion": 1,
        "kind": "cefr-real-model-benchmark",
        "runId": run_dir.name,
        "status": "complete" if (supported_ok and shadow_ok) else "partial",
        "statusNote": status_note,
        "plan": manifest.get("plan"),
        "git": manifest.get("git"),
        "environment": {"runtime": manifest.get("runtime"), "hardware": manifest.get("hardware"),
                        "model": manifest.get("model"), "timeoutSeconds": manifest.get("timeoutSeconds"),
                        "generationConfig": manifest.get("generationConfig")},
        "observation": manifest.get("observation"),
        "arms": arms,
        "review": {"method": "AI semantic review of every captured sample (12 flags); not manual approval",
                   "supportedManualReview": None,
                   "shadowManualReview": None if has_shadow else "not in scope",
                   "packet": "data/cefr-benchmark/<runId>/supported-review-packet.md (gitignored evidence)"},
        "gate": gate,
        "errorTable": _errors(supported_table, supported_reviewed) + (
            _errors(shadow_table, shadow_reviewed) if has_shadow else []),
        "limits": limits,
        "nextSteps": next_steps,
    }
    if baseline_dir is not None:
        report["comparison"] = _comparison(baseline_dir, supported_levels, supported_report["metrics"])
    return report


def to_markdown(report):
    supported = report["arms"]["supported"]
    shadow = report["arms"].get("shadow")
    environment = report["environment"] or {}
    model = environment.get("model") or {}
    lines = [f"# Báo cáo benchmark CEFR bằng model thật — {report['runId']}", "",
             f"- Trạng thái: **{report['status']}** — {report['statusNote']}",
             f"- Provider/model: `{model.get('provider', 'ollama')}` / `{model.get('name')}` "
             f"(`{model.get('digest')}`)", ""]
    if environment.get("timeoutSeconds") is not None:
        lines.append(f"- Timeout: {environment['timeoutSeconds']}s")
    git = report.get("git") or {}
    if git:
        lines.append(f"- HEAD: `{git.get('head')}` ({git.get('branch')})")
    lines += [f"- Cấu hình: {json.dumps(environment.get('generationConfig'), ensure_ascii=False)}", "",
              "## Đợt supported A1/A2/B1", "",
              f"Coverage {supported['captured']}/{supported['expected']}.", "",
              "| Mức | First length | First structure | Repair | Final valid | Final length (expected) | Semantic (AI) |",
              "|---|---|---|---|---|---|---|"]
    for level, stats in supported["levels"].items():
        lines.append(f"| {level} | {_pct(stats['firstAttemptLengthValid'])} | {_pct(stats['firstAttemptStructureValid'])} "
                     f"| {stats['repairAttempted']} (ok {stats['repairSucceeded']}) | {_pct(stats['finalValid'])} "
                     f"| {_pct(stats['finalLengthValidOnExpected'])} | {_pct(stats['semanticReviewRate'])} |")
    metrics = supported["metrics"]
    lines += ["", "| Metric | Passed/evaluated |", "|---|---|"]
    lines += [f"| {name} | {value['passed']}/{value['evaluated']} |" for name, value in metrics.items()]
    latency = supported["latencySeconds"]
    max_latency = latency.get("max")
    if max_latency is None:
        known = [stats["latencySeconds"]["max"] for stats in supported["levels"].values()
                 if (stats.get("latencySeconds") or {}).get("max") is not None]
        max_latency = max(known) if known else None
    max_text = f"{max_latency:.1f}s" if max_latency is not None else "n/a"
    lines += ["", f"Latency p50/p95/p99/max: {latency['p50']:.1f}s / {latency['p95']:.1f}s / "
                  f"{latency['p99']:.1f}s / {max_text}", ""]

    comparison = report.get("comparison")
    if comparison:
        lines += [f"## So với baseline `{comparison['baselineRunId']}` (attempt 1)", "",
                  "| Mức | Length Ollama | Length cloud | Structure Ollama | Structure cloud | "
                  "Semantic Ollama | Semantic cloud |", "|---|---|---|---|---|---|---|"]
        for level, pair in comparison["levels"].items():
            base, cand = pair["baseline"], pair["candidate"]
            lines.append(f"| {level} | {_pct(base['firstAttemptLengthValid'])} | {_pct(cand['firstAttemptLengthValid'])} "
                         f"| {_pct(base['firstAttemptStructureValid'])} | {_pct(cand['firstAttemptStructureValid'])} "
                         f"| {_pct(base['semanticReviewRate'])} | {_pct(cand['semanticReviewRate'])} |")
        lines += ["", "Baseline có repair; cột cloud là attempt 1 (repair tắt), nên chỉ so attempt-1 rates.", ""]

    if shadow:
        lines += ["## Đợt shadow B2/C1 (một phần)", "",
                  f"Coverage {shadow['captured']}/{shadow['expected']} — còn {shadow['missing']} mẫu chưa chạy. "
                  "Tỷ lệ dưới đây tính trên mẫu **đã chạy**; tỷ lệ trên mẫu dự kiến nằm trong JSON.", "",
                  "| Mức | Capture | Final valid (captured) | Semantic (AI, captured) | Trong khoảng từ (present) |",
                  "|---|---|---|---|---|"]
        for level, stats in shadow["levels"].items():
            lines.append(f"| {level} | {stats['captured']}/{stats['expected']} | {_pct(stats['finalValidOnCaptured'])} "
                         f"| {_pct(stats['semanticReviewRateOnCaptured'])} | {_pct(stats['finalLengthValidOnPresent'])} |")
        lines += ["", f"Shadow gate: `{shadow['shadowGate']}`; candidateGatePassed: "
                      f"{shadow['candidateGatePassed']}", ""]

    lines += ["## Bảng lỗi đã xác nhận", "", "| Key | Mức | Nhóm lỗi | Ghi chú |", "|---|---|---|---|"]
    for error in report["errorTable"]:
        lines.append(f"| {error['key']} | {error['level']} | {', '.join(error['categories'])} | {error['note']} |")
    levels = supported["levels"]
    first_length_fail = {level: round((1 - stats["firstAttemptLengthValid"]) * stats["expected"])
                         for level, stats in levels.items()}
    total_expected = sum(stats["expected"] for stats in levels.values())
    final_length_overall = (sum(stats["finalLengthValidOnExpected"] * stats["expected"] for stats in levels.values())
                            / total_expected) if total_expected else None
    repair_attempted = sum(stats["repairAttempted"] for stats in levels.values())
    repair_ok = sum(stats["repairSucceeded"] for stats in levels.values())
    rejected = sum(stats["finalTransportFailures"] for stats in levels.values())
    lines += ["", "## Kết luận", "",
              f"Gate supported: **{report['gate']['supported']['decision']}** — {report['gate']['supported']['reason']}"]
    if report["gate"].get("shadow"):
        lines.append(f"Gate shadow: **{report['gate']['shadow']['decision']}** — {report['gate']['shadow']['reason']}")
    lines += ["Không có release, không đổi trạng thái SUPPORTED, không chạy C2.", "",
              f"- First-attempt sai độ dài: {first_length_fail} trên mẫu dự kiến từng mức.",
              f"- Gate độ dài dùng final hợp lệ/expected ({_pct(final_length_overall)}), nên mẫu bị reject "
              "làm gate fail dù first attempt có thể đúng độ dài.",
              f"- Repair: gọi {repair_attempted} lần, thành công {repair_ok}, còn {rejected} mẫu cuối bị reject.",
              "- " + "; ".join(f"{level} final {_pct(stats['finalValid'])}, semantic AI "
                               f"{_pct(stats['semanticReviewRate'])}"
                               for level, stats in levels.items()) + ".", ""]
    if not shadow:
        lines.append("- Chưa có bằng chứng B2/C1 trong run này; shadow arm không nằm trong phạm vi.")
    lines += ["", "## Giới hạn", "", *[f"- {item}" for item in report["limits"]], "",
              "## Việc tiếp theo", "", *[f"{i}. {item}" for i, item in enumerate(report["nextSteps"], 1)], ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--baseline-dir", type=Path, help="optional earlier run directory for attempt-1 comparison")
    args = parser.parse_args()
    report = build(args.run, baseline_dir=args.baseline_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(to_markdown(report) + "\n", encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
