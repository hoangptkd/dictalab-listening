"""Build the manual review packet for a benchmark capture.

Selection is deterministic: every supported B1 sample, at least six samples per
shadow level covering that level's expected task types, and every sample whose
first attempt or final output failed. Candidate review text never becomes an
approval; the packet only links evidence for the owner.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def _fmt_task(task):
    if not isinstance(task, dict):
        return "**No task was produced for this sample.**"
    lines = [f"**Transcript ({len(task.get('utteranceEn', '').split())}-ish words):** {task.get('utteranceEn', '')}",
             f"**Question (vi):** {task.get('questionVi', '')}",
             f"**Task (vi):** {task.get('taskVi', '')}",
             f"**Skill:** {task.get('skill', '')}",
             f"**Translation (vi):** {task.get('translationVi', '')}", "", "| # | textVi | evidenceEn | taskType | reasoning |",
             "|---|---|---|---|---|"]
    for index, fact in enumerate(task.get("facts") or [], 1):
        reasoning = fact.get("reasoningEvidence") or {}
        segments = "; ".join(reasoning.get("segmentsEn") or [])
        rationale = reasoning.get("rationaleEn") or ""
        detail = (f"{segments} — {rationale}").strip(" —")
        lines.append(f"| {index} | {fact.get('textVi', '')} | {fact.get('evidenceEn', '')} | "
                     f"{fact.get('taskType', '(none)')} | {detail or '(none)'} |")
    return "\n".join(lines)


def select(rows, *, levels, shadow, per_level=6):
    chosen = []
    for level in levels:
        samples = sorted((row for row in rows if row["level"] == level), key=lambda row: row["key"])
        if level == "B1":
            chosen.extend(samples)
            continue
        types = {task_type for row in samples for task_type in (row.get("expectedTaskTypes") or [])}
        picked = [row for row in samples
                  if row["final"]["validationErrors"] or row["firstAttempt"]["validationErrors"]
                  or row["firstAttempt"]["state"] != "measured"]
        covered = {task_type for row in picked for task_type in (row.get("expectedTaskTypes") or [])}
        for row in samples:
            if len(picked) >= per_level and covered >= types:
                break
            if row in picked:
                continue
            picked.append(row)
            covered |= set(row.get("expectedTaskTypes") or [])
        chosen.extend(picked)
    return sorted(chosen, key=lambda row: row["key"])


def build_packet(table, capture, *, shadow):
    records = {f'{record["id"]}:{record["seed"]}': record for record in capture["records"]}
    order = ["A1", "A2", "B1", "B2", "C1"]
    levels = sorted({row["level"] for row in table["rows"]}, key=order.index)
    rows = select(table["rows"], levels=levels, shadow=shadow, per_level=6 if shadow else 0)
    lines = [f"# Manual review packet — {table['arm']}", "",
             f"- Dataset hash: `{table['datasetHash']}`",
             f"- Prompt: `{table['promptVersion']}` / `{table['promptHash']}`",
             f"- Samples in packet: {len(rows)} of {len(table['rows'])}",
             "- AI review is not approval. Approve only after reading each sample below.", ""]
    for row in rows:
        record = records.get(row["key"], {})
        lines += [f"## {row['key']} — {row['level']} turn {row['turn']}", "",
                  f"- transport: `{row['final']['transport']}` · seconds: {row['final']['seconds']}",
                  f"- words: {row['final']['wordCount']} (bounds {row['bounds']['minWords']}–{row['bounds']['maxWords']})",
                  f"- expectedTaskTypes: {row.get('expectedTaskTypes')} · actual: {row['final']['taskTypes']}",
                  f"- first attempt: {row['firstAttempt']['state']} · errors: {row['firstAttempt']['validationErrors']}",
                  f"- repair: attempted={row['repair']['attempted']} succeeded={row['repair']['succeeded']} "
                  f"errors: {row['repair']['validationErrors']}",
                  f"- final validation errors: {row['final']['validationErrors']}", "",
                  _fmt_task(record.get("task")), "",
                  "<details><summary>judgeInput</summary>", "",
                  "```json", json.dumps(record.get("judgeInput"), ensure_ascii=False, indent=2), "```", "",
                  "</details>", "",
                  "- [ ] questionFactsAligned  - [ ] answerable  - [ ] atomicFacts  - [ ] translationAccurate",
                  "- [ ] rolesConsistent  - [ ] continuity  - [ ] transfer",
                  "- [ ] cefrLevelAppropriate  - [ ] taskTypeAccurate  - [ ] cuesSupportConclusion",
                  "- [ ] noExternalKnowledge  - [ ] textOnly", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--table", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--shadow", action="store_true")
    args = parser.parse_args()
    table = json.loads(args.table.read_text(encoding="utf-8"))
    capture = json.loads(args.capture.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(build_packet(table, capture, shadow=args.shadow), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
