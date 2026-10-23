"""Fresh, quota-bound teacher collection with explicit delegated Codex approval.

No benchmark score gate, repair, replay, automatic approval, or production release.
"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import copy
import csv
import difflib
import json
import os
from pathlib import Path
import re
import shutil

from training import generate_teacher_data as gen
from training import iterate_teacher as run
from training.prepare_dataset import COLLECTION_POLICY, DatasetError, _split, prepare as prepare_dataset
from training.semantic_rubric import CHECKS, VERSION_V3, rules_for

LEVELS = ("A1", "A2", "B1", "B2")
TARGETS = {"train": 125, "validation": 25, "test": 25}
ARTIFACT = gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v4-20261023.json"


def normalized(text: str) -> str:
    return " ".join(re.findall(r"[^\W_]+", text.casefold()))


def _texts(value):
    if isinstance(value, dict):
        if isinstance(value.get("utteranceEn"), str):
            yield normalized(value["utteranceEn"])
        for child in value.values():
            yield from _texts(child)
    elif isinstance(value, list):
        for child in value:
            yield from _texts(child)


def prior_corpus() -> list[str]:
    texts = set()
    for folder, directories, files in os.walk(run.PRIVATE_ROOT):
        directories[:] = [name for name in directories if name not in
                          {".venv-cpu", "hf-cache", "inspections", "runs", "chrome-profile"}]
        for name in files:
            path = Path(folder) / name
            if path.suffix not in {".json", ".jsonl"} or path.stat().st_size > 8_000_000:
                continue
            try:
                raw = path.read_text(encoding="utf-8")
                values = [json.loads(line) for line in raw.splitlines() if line.strip()] if path.suffix == ".jsonl" else [json.loads(raw)]
                for value in values:
                    texts.update(_texts(value))
            except (ValueError, UnicodeError):
                continue
    return sorted(texts - {""})


def prepare(path: Path, settings=None, *, diagnostic: bool = False,
            retain_diagnostic_evidence: bool = False, artifact_path: Path | None = None) -> dict:
    if retain_diagnostic_evidence and not diagnostic:
        raise DatasetError("full rejection evidence retention requires a diagnostic campaign")
    path = run._private_path(path)
    if path.exists():
        raise DatasetError("collection directory already exists")
    settings = settings or run.settings_from_registry()
    selected_artifact = (artifact_path or ARTIFACT).resolve()
    artifact = gen.read_json(selected_artifact)
    if artifact.get("collectionPolicy") != COLLECTION_POLICY:
        raise DatasetError("collection artifact policy mismatch")
    sources = settings.sources_for("deepseek")
    if not sources:
        raise DatasetError("DeepSeek registry has no enabled source")
    groups = []
    holdouts = []
    retired = run._retired_groups()
    for file in run.SOURCE_PLANS:
        if file.exists():
            plan = gen.read_json(file)
            retired.update(item["group"]["groupId"] for item in plan.get("entries", []))
            retired.update(item["groupId"] for item in plan.get("chains", []))
    for file in run.PRIVATE_ROOT.glob("teacher-gold*.json"):
        gold = gen.read_json(file)
        if isinstance(gold, list):
            retired.update(item["sourceId"] for item in gold if isinstance(item.get("sourceId"), str)
                           and len(item["sourceId"].split("/")) == 3)
    excluded_families = {ident.rsplit("/", 1)[0] for ident in retired}
    for row in gen.read_json(gen.CATALOGUE)["catalogue"]:
        group_id = f"fresh/{row['scenarioId']}/{row['branchId']}"
        original_id = f"{row['scenarioId']}/{row['branchId']}/{row['level']}"
        if (row["scenarioId"] in gen.CORE and row["branchId"] == "standard") or original_id.rsplit("/", 1)[0] in excluded_families:
            holdouts.append(f"{row['scenarioId']}/{row['branchId']}/{row['level']}")
            continue
        levels = ("B1", "B2") if row["level"] == "B1" else (row["level"],)
        for level in levels:
            group = copy.deepcopy(row)
            group.update(level=level, groupId=group_id,
                         split=_split(group_id, 17, 15, 15, set()))
            groups.append(group)
    for split in TARGETS:
        if any(not any(g["split"] == split and g["level"] == level for g in groups) for level in LEVELS):
            raise DatasetError("source allocation has an empty level/split cell")
    corpus = sorted(set(prior_corpus()) | set(_texts(artifact)))
    manifest = {"policy": COLLECTION_POLICY, "createdAt": run._now(), "seed": 17,
        "diagnosticOnly": diagnostic,
        "retainDiagnosticEvidence": retain_diagnostic_evidence,
        "artifactPath": str(selected_artifact), "artifactHash": artifact["sha256"],
        "validatorHash": run.validator_hash(), "judgeSystemHash": gen.digest(run.JUDGE_SYSTEM),
        "teacherWrapperHash": gen.digest(gen.teacher_batch_system(artifact)),
        "rubricVersion": VERSION_V3, "rubricHash": gen.digest(rules_for(VERSION_V3)),
        "teacher": {"provider": "deepseek", "model": settings.registry_pools["deepseek"]["default_model"],
                    "sourceIds": [source.id for source in sources]},
        "judge": {"provider": "chatgpt-web", "model": "chatgpt-web",
                  "url": "http://127.0.0.1:3000/v1/chat/completions"},
        "requireFactAudit": True,
        "requireSemanticAudit": True,
        "useIssueReferences": True,
        "candidateCap": 1500 if getattr(settings, "operational_limits_enabled", True) else 0,
        "targetsPerLevel": TARGETS,
        "groups": groups, "holdouts": sorted(set(holdouts)), "priorCorpusHash": gen.digest(corpus),
        "approvalAuthority": "delegated AI:Codex; independent per-item review, no automatic approval"}
    manifest["hash"] = gen.digest(manifest)
    path.mkdir(parents=True)
    for folder in ("candidates", "decisions"):
        (path / folder).mkdir()
    gen.write_json(path / "manifest.json", manifest)
    gen.write_json(path / "prior-corpus.json", corpus)
    run._atomic_json(path / "state.json", {"status": "READY", "nextIndex": 0})
    return status(path)


def load(path: Path) -> tuple[dict, dict]:
    path = run._private_path(path)
    manifest = gen.read_json(path / "manifest.json")
    if (manifest.get("policy") != COLLECTION_POLICY or
        manifest.get("hash") != gen.digest({k: v for k, v in manifest.items() if k != "hash"}) or
        manifest["validatorHash"] != run.validator_hash() or
        manifest["judgeSystemHash"] != gen.digest(run.JUDGE_SYSTEM)):
        raise DatasetError("frozen collection contract changed")
    artifact = gen.read_json(Path(manifest["artifactPath"]))
    if manifest["teacherWrapperHash"] != gen.digest(gen.teacher_batch_system(artifact)):
        raise DatasetError("frozen teacher wrapper changed")
    if artifact["sha256"] != manifest["artifactHash"] or artifact["sha256"] != gen.digest(
            {k: v for k, v in artifact.items() if k != "sha256"}):
        raise DatasetError("frozen artifact changed")
    return manifest, artifact


def decisions(path: Path) -> list[dict]:
    return [gen.read_json(file) for file in sorted((path / "decisions").glob("*.json"))]


def status(path: Path) -> dict:
    manifest, _ = load(path)
    done = decisions(path)
    counts = Counter((item["split"], item["level"]) for item in done if item["verdict"] == "approved")
    pending = [file.stem.removesuffix(".teacher-result") for file in sorted((path / "candidates").glob("*.teacher-result.json"))
               if not (path / "decisions" / (file.stem.removesuffix(".teacher-result") + ".json")).exists()]
    state = gen.read_json(path / "state.json")
    return {"status": state["status"], "attempted": state["nextIndex"],
        "configuredCandidateCap": manifest["candidateCap"],
        "candidateCap": manifest["candidateCap"] if state.get("operationalLimitsEnabled", True) else 0,
        "approvedTasks": sum(counts.values()),
        "counts": {split: {level: counts[split, level] for level in LEVELS} for split in TARGETS},
        "pending": pending, "complete": all(counts[split, level] == target
            for split, target in TARGETS.items() for level in LEVELS), "trainingReady": False}


def duplicate(text: str, corpus: list[str]) -> bool:
    value = normalized(text)
    words = set(value.split())
    for other in corpus:
        if value == other:
            return True
        if len(words) < 8 or abs(len(value) - len(other)) > max(len(value), len(other)) * 0.25:
            continue
        other_words = set(other.split())
        if len(words & other_words) / max(1, len(words | other_words)) >= 0.65 and difflib.SequenceMatcher(
                None, value, other, autojunk=False).ratio() >= 0.88:
            return True
    return False


def reject(path: Path, row: dict, reason: str, *, reviewer: str = "automatic-hard-check") -> None:
    folder = path / "candidates"
    ident = row["id"]
    compact = {key: row[key] for key in ("id", "groupId", "split", "level")}
    compact.update(verdict="rejected", reviewer=reviewer, reason=reason,
        taskHash=gen.digest(row.get("task")), contextHash=gen.digest(row["context"]),
        recordHashes=[record["outputHash"] for record in row.get("records", [])],
        usage=row.get("usage"), sent=bool(row.get("sent")), time=run._now())
    # Retain controlled provider codes, never exception dumps containing rejected text.
    compact["errorType"] = row.get("errorType")
    compact["errorCodes"] = re.findall(
        r"\bdeepseek:teacher_batch:(?:schema_mismatch|invalid_json|response_not_object|"
        r"output_truncated|http_or_parse_error|timeout|client_error:(?:400|401|403|422))\b",
        row.get("error", ""))
    compact["validationCodes"] = re.findall(
        r"(?:utteranceEn:word_count=\d+,min=\d+,max=\d+|"
        r"(?:fact\[\d+\]\.[A-Za-z]+|facts|skill|level|levelProfile|missing_or_empty_field):[A-Za-z_]+|"
        r"fact_slot_count_mismatch:slots=\d+,facts=\d+)", row.get("error", ""))
    for kind in ("teacher-request", "teacher-envelope", "request", "envelope", "result"):
        file = folder / f"{ident}.{kind}.json"
        if file.exists():
            value = gen.read_json(file)
            compact[kind + "Hash"] = gen.digest(value)
            if isinstance(value, dict) and value.get("usage"):
                compact.setdefault("allUsage", {})[kind] = value["usage"]
    run._atomic_json(path / "decisions" / f"{ident}.json", compact)
    if gen.read_json(path / "manifest.json").get("retainDiagnosticEvidence"):
        manifest, _ = load(path)
        if not manifest.get("diagnosticOnly"):
            raise DatasetError("full rejection evidence retention requires a diagnostic campaign")
        archive = path / "rejected-evidence" / ident
        archive.mkdir(parents=True, exist_ok=True)
        for file in folder.glob(f"{ident}.*.json"):
            shutil.copy2(file, archive / file.name)
    # Persist compact evidence first; only remove this campaign's candidate-specific files.
    for file in folder.glob(f"{ident}.*.json"):
        if file.resolve().parent != folder.resolve():
            raise DatasetError("candidate cleanup escaped its folder")
        file.unlink()


async def collect(path: Path, *, limit: int = 4, settings=None, shutdown_on_failure: bool = False) -> dict:
    path = run._private_path(path)
    manifest, artifact = load(path)
    settings = settings or run.settings_from_registry()
    if (settings.registry_pools["deepseek"]["default_model"] != manifest["teacher"]["model"] or
        [source.id for source in settings.sources_for("deepseek")] != manifest["teacher"]["sourceIds"]):
        raise DatasetError("teacher registry changed; create a new frozen collection")
    if limit < 1:
        raise DatasetError("collect limit must be positive")
    with run._campaign_lock(path):
        current = status(path)
        if current["pending"] or current["complete"]:
            return current
        state = gen.read_json(path / "state.json")
        if state["status"] in {"RUNNING", "BLOCKED"}:
            raise DatasetError("unresolved worker/request outcome; inspect checkpoint before continuing")
        state["operationalLimitsEnabled"] = getattr(settings, "operational_limits_enabled", True)
        corpus = gen.read_json(path / "prior-corpus.json")
        if gen.digest(corpus) != manifest["priorCorpusHash"]:
            raise DatasetError("prior corpus changed after collection began")
        accepted = [item for item in decisions(path) if item["verdict"] == "approved"]
        for item in accepted:
            corpus.append(normalized(gen.read_json(path / "candidates" / f"{item['id']}.teacher-result.json")["task"]["utteranceEn"]))
        for _ in range(limit):
            current = status(path)
            if (getattr(settings, "operational_limits_enabled", True) and
                    manifest["candidateCap"] and current["attempted"] >= manifest["candidateCap"]) or current["complete"]:
                state["status"] = "COMPLETE" if current["complete"] else "CANDIDATE_CAP"
                break
            quota = run.quota_snapshot(settings, platforms=("deepseek",))
            if quota["deepseek"]["availableCalls"] == 0:
                state.update(status="AWAITING_CODEX_REVIEW" if current["pending"] else "WAITING_QUOTA", quota=quota)
                break
            index = state["nextIndex"]
            # Ratio 5:1:1 matches the requested 125/25/25 task counts.
            preferred_level = LEVELS[index % len(LEVELS)]
            preferred_split = ("train",) * 5 + ("validation", "test")
            preferred_split = preferred_split[(index // len(LEVELS)) % 7]
            reserved = Counter()
            for pending in current["pending"]:
                pending_row = gen.read_json(path / "candidates" / f"{pending}.teacher-result.json")
                reserved[pending_row["split"], pending_row["level"]] += 1
            cells = [(s, level) for s, target in TARGETS.items() for level in LEVELS
                     if current["counts"][s][level] + reserved[s, level] < target]
            if not cells:
                state["status"] = "AWAITING_CODEX_REVIEW"
                break
            split, level = (preferred_split, preferred_level) if (preferred_split, preferred_level) in cells else cells[0]
            choices = [g for g in manifest["groups"] if g["split"] == split and g["level"] == level]
            group = min(choices, key=lambda g: gen.digest([index, g["groupId"], level]))
            prior = [item for item in accepted if item["groupId"] == group["groupId"] and item["level"] == level]
            previous = gen.read_json(path / "candidates" / f"{prior[-1]['id']}.teacher-result.json") if prior else None
            turn = 3 if previous and previous["context"]["turnNumber"] == 2 else (2 if index // 4 % 2 == 0 else 4)
            history = [{**previous, "eligible": True}] if turn == 3 else []
            varied_group = copy.deepcopy(group)
            varied_group["input"]["variationSeed"] = 17000 + index
            context = gen.make_context(varied_group, turn, history, artifact, index)
            ident = f"candidate-{index + 1:04d}"
            entry = {"id": ident, "groupId": group["groupId"], "split": split,
                     "level": level, "context": context}
            state.update(status="RUNNING", nextIndex=index + 1, currentId=ident)
            run._atomic_json(path / "state.json", state)
            if shutdown_on_failure and _ == 0:
                from training.shutdown_watchdog import arm
                arm(path)
            folder = path / "candidates"
            try:
                row = await run._teacher_one(path, manifest, entry, group, artifact, folder, settings)
                if not row["hardPass"]:
                    reject(path, row, "hard-validation-or-provider-error:" + row.get("errorType", "unknown"))
                elif duplicate(row["task"]["utteranceEn"], corpus):
                    reject(path, row, "exact-or-near-duplicate")
                else:
                    try:
                        await run._judge_one(path, manifest, row, ident, ident, settings, folder=folder)
                    except DatasetError:
                        if not (folder / f"{ident}.envelope.json").exists():
                            raise
                        reject(path, row, "judge-contract-error")
                    else:
                        corpus.append(normalized(row["task"]["utteranceEn"]))
                state["status"] = "RUNNING"
                run._atomic_json(path / "state.json", state)
                print(json.dumps({"candidate": ident, "level": level, "hardPass": row["hardPass"],
                                  "pendingIndependentReview": (folder / f"{ident}.teacher-result.json").exists()}), flush=True)
            except run.QuotaUnavailable:
                state.update(status="AWAITING_CODEX_REVIEW" if status(path)["pending"] else "WAITING_QUOTA")
                break
            except Exception as error:
                # A possibly sent request must never be dispatched again on resume.
                state.update(status="BLOCKED", errorType=type(error).__name__, currentId=ident)
                run._atomic_json(path / "state.json", state)
                raise
        if state["status"] == "RUNNING":
            state["status"] = "AWAITING_CODEX_REVIEW"
        run._atomic_json(path / "state.json", state)
    return status(path)


def adjudicate(path: Path, verdict: dict) -> dict:
    manifest, _ = load(path)
    ident = verdict["id"]
    if not re.fullmatch(r"candidate-\d{4}", ident):
        raise DatasetError("invalid candidate id")
    with run._campaign_lock(path):
        if (path / "decisions" / f"{ident}.json").exists():
            raise DatasetError("candidate already adjudicated")
        row = gen.read_json(path / "candidates" / f"{ident}.teacher-result.json")
        review = gen.read_json(path / "candidates" / f"{ident}.result.json")
        if (verdict.get("reviewer") != "AI:Codex" or
            verdict.get("taskHash") != gen.digest(row["task"]) or
            verdict.get("contextHash") != gen.digest(row["context"]) or
            verdict.get("webReviewHash") != gen.digest(review) or
            not isinstance(verdict.get("reason"), str) or not verdict["reason"].strip()):
            raise DatasetError("independent approval needs a reviewer, matching evidence hashes and reason")
        if verdict["verdict"] == "rejected":
            reject(path, row, verdict["reason"], reviewer=verdict["reviewer"])
        elif verdict["verdict"] == "approved":
            checks = verdict.get("checks", {})
            evidence = verdict.get("factEvidence", [])
            if (not row["hardPass"] or not run._review_integrity(row, review, manifest) or
                set(checks) != set(CHECKS) or any(value is not True for value in checks.values()) or
                len(evidence) != len(row["task"]["facts"]) or
                {item.get("index") for item in evidence} != set(range(len(evidence))) or
                any(not isinstance(item.get("sourceQuote"), str) or len(item["sourceQuote"]) < 4 or
                    item["sourceQuote"] not in row["task"]["utteranceEn"] or
                    not isinstance(item.get("finding"), str) or not item["finding"].strip() for item in evidence)):
                raise DatasetError("independent approval is missing complete grounded checks/fact evidence")
            differing = {key for key in CHECKS if review["checks"][key] is not True}
            overrides = verdict.get("webOverrides", [])
            if ({item.get("check") for item in overrides} != differing or
                any(not isinstance(item.get("basis"), str) or not item["basis"].strip() for item in overrides)):
                raise DatasetError("every Web disagreement requires an explicit independent override basis")
            current = status(path)
            if current["counts"][row["split"]][row["level"]] >= TARGETS[row["split"]]:
                raise DatasetError("level/split target already full")
            result = {**verdict, **{key: row[key] for key in ("groupId", "split", "level")},
                      "time": run._now()}
            run._atomic_json(path / "decisions" / f"{ident}.json", result)
        else:
            raise DatasetError("verdict must be approved or rejected; uncertain items cannot enter SFT")
        state = gen.read_json(path / "state.json")
        state["status"] = "AWAITING_CODEX_REVIEW" if status(path)["pending"] else "READY"
        run._atomic_json(path / "state.json", state)
    return status(path)


def export(path: Path) -> dict:
    manifest, _ = load(path)
    if manifest.get("diagnosticOnly"):
        raise DatasetError("diagnostic experiments cannot be exported for SFT")
    if not status(path)["complete"] or status(path)["pending"]:
        raise DatasetError("all 700 independently approved tasks are required before dataset export")
    output = path / "export"
    output.mkdir()
    rows = []
    for item in decisions(path):
        if item["verdict"] == "approved":
            row = gen.read_json(path / "candidates" / f"{item['id']}.teacher-result.json")
            for record in row["records"]:
                record["semanticReviewReference"] = str(path / "decisions" / f"{item['id']}.json")
                rows.append(record)
    source = output / "approved.jsonl"
    source.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8", newline="\n")
    review_file = output / "decisions.csv"
    with review_file.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["id", "decision", "reviewer", "inputHash", "outputHash"])
        writer.writeheader()
        for row in rows:
            writer.writerow({"id": row["id"], "decision": "approved", "reviewer": "AI:Codex",
                             "inputHash": row["inputHash"], "outputHash": row["outputHash"]})
    return prepare_dataset([source], review_file, output / "dataset", artifact_path=Path(manifest["artifactPath"]),
        forced_test_groups=set(manifest["holdouts"]), review_policy="delegated-ai")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "collect", "status", "adjudicate", "export"))
    parser.add_argument("--campaign", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=4)
    parser.add_argument("--decision-file", type=Path)
    parser.add_argument("--artifact", type=Path, help="freeze this candidate on prepare; default is the best measured baseline")
    parser.add_argument("--diagnostic", action="store_true", help="prepare an experiment excluded from SFT")
    parser.add_argument("--retain-diagnostic-evidence", action="store_true",
                        help="preserve rejected diagnostic requests and responses for owner review")
    parser.add_argument("--shutdown-on-worker-failure", action="store_true",
                        help="arm an external watchdog with the owner's forced shutdown authorization")
    args = parser.parse_args()
    path = run._private_path(args.campaign)
    if args.command == "prepare":
        result = prepare(path, diagnostic=args.diagnostic,
                         retain_diagnostic_evidence=args.retain_diagnostic_evidence, artifact_path=args.artifact)
    elif args.command == "collect":
        result = asyncio.run(collect(path, limit=args.limit, shutdown_on_failure=args.shutdown_on_worker_failure))
    elif args.command == "adjudicate":
        if not args.decision_file:
            parser.error("adjudicate requires --decision-file")
        result = adjudicate(path, gen.read_json(args.decision_file))
    elif args.command == "export":
        result = export(path)
    else:
        result = status(path)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
