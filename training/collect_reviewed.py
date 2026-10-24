"""Fresh, quota-bound teacher collection with frozen delegated AI review authority.

No benchmark score gate, repair, replay, or production release.
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
from training.semantic_rubric import CHECKS, VERSION_V3, decision, rules_for

LEVELS = ("A1", "A2", "B1", "B2")
TARGETS = {"train": 125, "validation": 25, "test": 25}
TARGETS_500 = {"train": 100, "validation": 12, "test": 13}
ARTIFACT = gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v9-20261024.json"
WEB_AUTHORITY = "delegated AI:chatgpt-web; hard checks and complete Web PASS, no Codex adjudication"


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
            retain_diagnostic_evidence: bool = False, artifact_path: Path | None = None,
            web_accounts: list[dict] | None = None, continue_from: Path | None = None,
            gemini_teacher: bool = False, total_articles: int = 700,
            web_approval: bool = False) -> dict:
    if retain_diagnostic_evidence and not diagnostic:
        raise DatasetError("full rejection evidence retention requires a diagnostic campaign")
    path = run._private_path(path)
    if path.exists():
        raise DatasetError("collection directory already exists")
    settings = settings or run.settings_from_registry()
    if total_articles not in (500, 700):
        raise DatasetError("supported collection totals are 500 or 700")
    targets = TARGETS_500 if total_articles == 500 else TARGETS
    selected_artifact = (artifact_path or ARTIFACT).resolve()
    artifact = gen.read_json(selected_artifact)
    if artifact.get("collectionPolicy") != COLLECTION_POLICY:
        raise DatasetError("collection artifact policy mismatch")
    sources = settings.sources_for("deepseek")
    if not sources:
        raise DatasetError("DeepSeek registry has no enabled source")
    if web_accounts:
        if (len({a["id"] for a in web_accounts}) != len(web_accounts) or
                len({a["url"] for a in web_accounts}) != len(web_accounts)):
            raise DatasetError("Web accounts must have distinct IDs and endpoints")
        for account in web_accounts:
            run._bridge_url(account["url"])
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
                    "thinkingMode": artifact.get("teacherThinkingMode", "disabled"),
                    "sourceIds": [source.id for source in sources]},
        "judge": {"provider": "chatgpt-web", "model": "chatgpt-web",
                  "url": "http://127.0.0.1:3000/v1/chat/completions"},
        "requireFactAudit": True,
        "requireSemanticAudit": True,
        "useIssueReferences": True,
        "candidateCap": 1500 if not diagnostic or getattr(settings, "operational_limits_enabled", True) else 0,
        "targetsPerLevel": targets,
        "groups": groups, "holdouts": sorted(set(holdouts)), "priorCorpusHash": gen.digest(corpus),
        "approvalAuthority": "delegated AI:Codex; independent per-item review, no automatic approval"}
    if web_approval:
        manifest["approvalAuthority"] = WEB_AUTHORITY
    if web_accounts:
        manifest["judge"]["accounts"] = web_accounts
        manifest["judge"]["url"] = web_accounts[0]["url"]
    if artifact.get("teacherGenerationConfig"):
        manifest["teacher"]["generationConfig"] = artifact["teacherGenerationConfig"]
    if gemini_teacher:
        gemini_sources = settings.sources_for("gemini")
        if (settings.registry_pools.get("gemini", {}).get("default_model") != "gemini-3.5-flash-lite" or
                not gemini_sources or any(s.model != "gemini-3.5-flash-lite" for s in gemini_sources)):
            raise DatasetError("Gemini teacher requires registered gemini-3.5-flash-lite accounts")
        manifest["additionalTeachers"] = [{**manifest["teacher"], "provider": "gemini",
            "model": "gemini-3.5-flash-lite", "thinkingMode": "provider-default",
            "sourceIds": [s.id for s in gemini_sources]}]
    if artifact.get("judgeAuditInstructions"):
        manifest["judge"]["auditInstructions"] = artifact["judgeAuditInstructions"]
    path.mkdir(parents=True)
    for folder in ("candidates", "decisions"):
        (path / folder).mkdir()
    run._atomic_json(path / "state.json", {"status": "PREPARING", "nextIndex": 0})
    next_index = 0
    if continue_from is not None:
        parent = run._private_path(continue_from)
        with run._campaign_lock(parent):
            old, _ = load(parent)
            previous = status(parent)
            if (diagnostic or old.get("diagnosticOnly") or previous["pending"] or
                    previous["status"] != "READY" or old["groups"] != manifest["groups"] or
                    old["holdouts"] != manifest["holdouts"] or
                    old["candidateCap"] != manifest["candidateCap"] or
                    old["teacher"]["model"] != manifest["teacher"]["model"]):
                raise DatasetError("continuation requires a fully decided READY non-diagnostic campaign with matching allocation")
            if any(previous["counts"][split][level] > target for split, target in targets.items() for level in LEVELS):
                raise DatasetError("approved inherited cell exceeds the requested target; never discard approvals")
            done = decisions(parent)
            next_index = previous["attempted"]
            if {item["id"] for item in done} != {f"candidate-{i:04d}" for i in range(1, next_index + 1)}:
                raise DatasetError("continuation must preserve every attempted candidate")
            files = list((parent / "decisions").glob("*.json"))
            for item in done:
                if item["verdict"] != "approved":
                    continue
                ident = item["id"]
                row = gen.read_json(parent / "candidates" / f"{ident}.teacher-result.json")
                web = gen.read_json(parent / "candidates" / f"{ident}.result.json")
                if (item["taskHash"] != gen.digest(row["task"]) or
                        item["contextHash"] != gen.digest(row["context"]) or
                        item["webReviewHash"] != gen.digest(web) or not run._review_integrity(row, web, old)):
                    raise DatasetError("inherited approval evidence changed")
                files.extend((parent / "candidates").glob(f"{ident}.*.json"))
            if (parent / "sent-index.jsonl").exists():
                files.append(parent / "sent-index.jsonl")
            files.extend((parent / "web-batches").glob("*.json"))
            inventory = {}
            for source in files:
                relative = source.relative_to(parent)
                (path / relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, path / relative)
                inventory[relative.as_posix()] = run.hashlib.sha256(source.read_bytes()).hexdigest()
            manifest["continuation"] = {"parentPath": str(parent), "parentManifest": old,
                "attempted": next_index, "approvedTasks": previous["approvedTasks"], "files": inventory}
            account_state = parent / "web-account-state.json"
            if account_state.exists():
                shutil.copy2(account_state, path / account_state.name)
                manifest["continuation"]["initialWebAccountStateHash"] = run.hashlib.sha256(account_state.read_bytes()).hexdigest()
    manifest["hash"] = gen.digest(manifest)
    gen.write_json(path / "manifest.json", manifest)
    gen.write_json(path / "prior-corpus.json", corpus)
    run._atomic_json(path / "state.json", {"status": "READY", "nextIndex": next_index})
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
    if (manifest["teacher"].get("generationConfig", {}) != artifact.get("teacherGenerationConfig", {}) or
            manifest["judge"].get("auditInstructions", "") != artifact.get("judgeAuditInstructions", "")):
        raise DatasetError("frozen generation/judge configuration differs from artifact")
    return manifest, artifact


def decisions(path: Path) -> list[dict]:
    return [gen.read_json(file) for file in sorted((path / "decisions").glob("*.json"))]


def verify_inherited(path: Path, manifest: dict) -> None:
    for relative, expected in manifest.get("continuation", {}).get("files", {}).items():
        data = (path / relative).read_bytes()
        if run.hashlib.sha256(data).hexdigest() == expected:
            continue
        if relative == "sent-index.jsonl":
            # The inherited log is an immutable prefix; new sends append to it.
            prefix = run.hashlib.sha256()
            for line in data.splitlines(keepends=True):
                prefix.update(line)
                if prefix.hexdigest() == expected:
                    break
            else:
                raise DatasetError("inherited sent-index prefix changed")
        else:
            raise DatasetError("inherited evidence changed after continuation")


def status(path: Path) -> dict:
    manifest, _ = load(path)
    targets = manifest["targetsPerLevel"]
    done = decisions(path)
    counts = Counter((item["split"], item["level"]) for item in done if item["verdict"] == "approved")
    pending = [file.stem.removesuffix(".teacher-result") for file in sorted((path / "candidates").glob("*.teacher-result.json"))
               if not (path / "decisions" / (file.stem.removesuffix(".teacher-result") + ".json")).exists()]
    state = gen.read_json(path / "state.json")
    return {"status": state["status"], "attempted": state["nextIndex"],
        "configuredCandidateCap": manifest["candidateCap"],
        "candidateCap": manifest["candidateCap"] if not manifest.get("diagnosticOnly") or state.get("operationalLimitsEnabled", True) else 0,
        "approvedTasks": sum(counts.values()),
        "counts": {split: {level: counts[split, level] for level in LEVELS} for split in TARGETS},
        "retryAt": state.get("retryAt"), "stopReason": state.get("stopReason"),
        "pending": pending, "complete": all(counts[split, level] == target
            for split, target in targets.items() for level in LEVELS), "trainingReady": False}


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
    for key in ("teacherProvider", "teacherModel", "teacherSourceId", "returnedModel"):
        if key in row:
            compact[key] = row[key]
    # Retain controlled provider codes, never exception dumps containing rejected text.
    compact["errorType"] = row.get("errorType")
    compact["providerHttpStatus"] = row.get("providerHttpStatus")
    if row.get("transportOutcomeUnknown"):
        compact["transportOutcomeUnknown"] = True
    compact["webAttempts"] = [{"hash": gen.digest(attempt), "accountId": attempt.get("accountId"),
        "outcome": attempt.get("outcome"), "httpStatus": attempt.get("httpStatus")}
        for attempt in run.web_accounts.attempts(folder, ident)]
    compact["errorCodes"] = re.findall(
        r"\b(?:deepseek|gemini):teacher_batch:(?:schema_mismatch|invalid_json|response_not_object|"
        r"output_truncated|http_or_parse_error|timeout|client_error:(?:400|401|402|403|422|429))\b",
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
    shared_raw = []
    link_file = folder / f"{ident}.web-batch.json"
    if link_file.exists():
        link = gen.read_json(link_file)
        stem = link["stem"]
        if stem != "batch-" + "-".join(link["ids"]) or any(not re.fullmatch(r"candidate-\d{4}", i) for i in link["ids"]):
            raise DatasetError("invalid Web batch cleanup reference")
        batch_folder = path / "web-batches"
        meta = batch_folder / f"{stem}.meta.json"
        if not meta.exists():
            raise DatasetError("Web batch has unresolved outcome; retain evidence before rejecting")
        compact["webBatch"] = gen.read_json(meta)
        # Shared raw bodies contain every member. Keep compact provenance, remove rejected text.
        shared_raw = [file for file in batch_folder.glob(f"{stem}.*.json") if file != meta]
    run._atomic_json(path / "decisions" / f"{ident}.json", compact)
    if not gen.read_json(path / "manifest.json").get("retainDiagnosticEvidence"):
        for file in shared_raw:
            file.unlink()
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


def _validate_web_review(row: dict, review: dict, manifest: dict) -> None:
    if not run._review_integrity(row, review, manifest):
        raise DatasetError("Web approval evidence is invalid or changed")
    if decision({**review["checks"], "issues": review["issues"]}) != "PASS":
        raise DatasetError("Web approval requires every check to pass")
    # Recheck persisted fact/semantic evidence even after shared rejected raw is purged.
    verdict = {"id": row["id"], **review["checks"], "issues": [],
               "factAudit": review.get("factAudit", [])}
    _, schema = run._web_judge_body(row, row["id"], manifest)
    strict = {"items": [verdict]}
    run.Draft202012Validator(schema).validate(strict)
    run._web_review(row, manifest, verdict, {}, strict, {}, review.get("webAccount"))


def _finalize_web(path: Path, manifest: dict, state: dict) -> None:
    if manifest.get("approvalAuthority") != WEB_AUTHORITY:
        return
    folder = path / "candidates"
    try:
        for ident in status(path)["pending"]:
            review_file = folder / f"{ident}.result.json"
            if not review_file.exists():
                continue
            row = gen.read_json(folder / f"{ident}.teacher-result.json")
            review = gen.read_json(review_file)
            if not run._review_integrity(row, review, manifest):
                raise DatasetError("Web result has invalid evidence; inspect without replay")
            if decision({**review["checks"], "issues": review["issues"]}) != "PASS":
                failed = ",".join(key for key in CHECKS if review["checks"][key] is not True)
                reject(path, row, "web-non-pass:" + failed, reviewer=review["reviewer"])
                continue
            _validate_web_review(row, review, manifest)
            if status(path)["counts"][row["split"]][row["level"]] >= manifest["targetsPerLevel"][row["split"]]:
                raise DatasetError("level/split target already full")
            result = {key: row[key] for key in ("id", "groupId", "split", "level")}
            result.update(verdict="approved", reviewer=review["reviewer"],
                reason="Hard checks and complete grounded Web PASS; no independent Codex review.",
                taskHash=gen.digest(row["task"]), contextHash=gen.digest(row["context"]),
                webReviewHash=gen.digest(review), checks=review["checks"],
                factEvidence=review["factAudit"], webAccount=review.get("webAccount"),
                modelIdentity=review.get("modelIdentity", "unverified-web-alias"),
                returnedModel=review.get("returnedModel"), time=run._now())
            run._atomic_json(path / "decisions" / f"{ident}.json", result)
        current = status(path)
        if state["status"] in {"READY", "RUNNING", "AWAITING_CODEX_REVIEW", "AWAITING_WEB_REVIEW"}:
            state["status"] = "AWAITING_WEB_REVIEW" if current["pending"] else "COMPLETE" if current["complete"] else "READY"
    except Exception as error:
        state.update(status="BLOCKED", errorType=type(error).__name__)
        raise
    finally:
        run._atomic_json(path / "state.json", state)


async def collect(path: Path, *, limit: int = 4, settings=None, shutdown_on_failure: bool = False,
                  overlap_web: bool = False, deepseek_only: bool = False, web_batch_size: int = 1,
                  teacher_concurrency: int | None = None) -> dict:
    path = run._private_path(path)
    manifest, artifact = load(path)
    web_approval = manifest.get("approvalAuthority") == WEB_AUTHORITY
    review_state = "AWAITING_WEB_REVIEW" if web_approval else "AWAITING_CODEX_REVIEW"
    settings = settings or run.settings_from_registry()
    teachers = [manifest["teacher"], *manifest.get("additionalTeachers", [])]
    if deepseek_only:
        teachers = [teacher for teacher in teachers if teacher["provider"] == "deepseek"]
    for teacher in teachers:
        provider = teacher["provider"]
        if (settings.registry_pools[provider]["default_model"] != teacher["model"] or
                [source.id for source in settings.sources_for(provider)] != teacher["sourceIds"]):
            raise DatasetError("teacher registry changed; create a new frozen collection")
    if limit < 1:
        raise DatasetError("collect limit must be positive")
    if web_batch_size not in (1, 2, 3):
        raise DatasetError("Web batch size must be one, two or three")
    if teacher_concurrency is not None and (type(teacher_concurrency) is not int or teacher_concurrency not in (1, 2)):
        raise DatasetError("teacher concurrency must be one or two")
    concurrency = teacher_concurrency if teacher_concurrency is not None else len(teachers)
    with run._campaign_lock(path):
        verify_inherited(path, manifest)
        current = status(path)
        state = gen.read_json(path / "state.json")
        folder = path / "candidates"
        if state["status"] in {"RUNNING", "PREPARING"}:
            raise DatasetError("unresolved worker/request outcome; inspect checkpoint before continuing")
        had_pending = bool(current["pending"])
        _finalize_web(path, manifest, state)
        current = status(path)
        watchdog = None
        def arm_watchdog():
            nonlocal watchdog
            if shutdown_on_failure and watchdog is None:
                from training.shutdown_watchdog import arm
                watchdog = arm(path)

        async def judge(row):
            ident = row["id"]
            try:
                await run._judge_one(path, manifest, row, ident, ident, settings, folder=folder)
            except DatasetError:
                if not (folder / f"{ident}.envelope.json").exists():
                    raise
                reject(path, row, "judge-contract-error")

        if state["status"] == "WAITING_WEB_QUOTA":
            waiting = [state["currentId"]]
        elif state["status"] in {"AWAITING_CODEX_REVIEW", "AWAITING_WEB_REVIEW"}:
            # An overlapped teacher may finish while the previous Web call hits quota.
            # Only an unsent saved row can be dispatched here; _judge_one guards replay.
            waiting = [ident for ident in current["pending"]
                       if not (folder / f"{ident}.result.json").exists()]
        else:
            waiting = []
        for ident in waiting:
            if (folder / f"{ident}.result.json").exists():
                continue
            row = gen.read_json(folder / f"{ident}.teacher-result.json")
            try:
                link = folder / f"{ident}.web-batch.json"
                if link.exists():
                    rows = [gen.read_json(folder / f"{i}.teacher-result.json") for i in gen.read_json(link)["ids"]]
                    await run._judge_web_batch(path, manifest, rows, folder=folder)
                else:
                    await judge(row)
            except run.web_accounts.WebQuotaWait as error:
                state.update(status="WAITING_WEB_QUOTA", currentId=ident, retryAt=error.retry_at)
                run._atomic_json(path / "state.json", state)
                return status(path)
            except Exception as error:
                state.update(status="BLOCKED", currentId=ident, errorType=type(error).__name__)
                run._atomic_json(path / "state.json", state)
                raise
            state.update(status=review_state)
            run._atomic_json(path / "state.json", state)
        if waiting:
            _finalize_web(path, manifest, state)
            return status(path)
        if had_pending and not current["pending"]:
            return current
        if current["pending"] or current["complete"] or state["status"] in {"WAITING_DEEPSEEK_QUOTA", "WAITING_GEMINI_QUOTA"}:
            return current
        if state["status"] in {"RUNNING", "BLOCKED", "PREPARING"}:
            raise DatasetError("unresolved worker/request outcome; inspect checkpoint before continuing")
        state["operationalLimitsEnabled"] = getattr(settings, "operational_limits_enabled", True)
        corpus = gen.read_json(path / "prior-corpus.json")
        if gen.digest(corpus) != manifest["priorCorpusHash"]:
            raise DatasetError("prior corpus changed after collection began")
        accepted = [item for item in decisions(path) if item["verdict"] == "approved"]
        for item in accepted:
            corpus.append(normalized(gen.read_json(path / "candidates" / f"{item['id']}.teacher-result.json")["task"]["utteranceEn"]))
        web_job = None
        web_row = None
        web_buffer = []
        teacher_jobs = []
        scheduled = 0
        teacher_offset = state["nextIndex"] // 8 % len(teachers)

        def teacher_stop(value, reason):
            priority = {"BLOCKED": 3, "WAITING_DEEPSEEK_QUOTA": 2, "WAITING_GEMINI_QUOTA": 1}
            previous = state.get("teacherStopStatus")
            if state["status"] == "BLOCKED":
                previous = "BLOCKED"
            if priority.get(value, 0) >= priority.get(previous, 0):
                state.update(teacherStopStatus=value, stopReason=reason)

        def start_teacher():
            nonlocal scheduled
            current = status(path)
            if ((not manifest.get("diagnosticOnly") or getattr(settings, "operational_limits_enabled", True)) and
                    manifest["candidateCap"] and current["attempted"] >= manifest["candidateCap"]) or current["complete"]:
                state["status"] = "COMPLETE" if current["complete"] else "CANDIDATE_CAP"
                return False
            # Alternate the starting provider across batches to cover every CEFR level.
            teacher = teachers[(scheduled + teacher_offset) % len(teachers)]
            platforms = tuple(dict.fromkeys(("deepseek", teacher["provider"])))
            quota = run.quota_snapshot(settings, platforms=platforms)
            for provider in platforms:
                if quota[provider]["availableCalls"] == 0:
                    state.update(status=("WAITING_" + provider.upper() + "_QUOTA") if len(teachers) > 1 or web_approval
                                 else review_state if current["pending"] else "WAITING_QUOTA",
                                 quota=quota, stopReason=provider + " quota pool exhausted")
                    teacher_stop(state["status"], state["stopReason"])
                    return False
            index = state["nextIndex"]
            preferred_level = LEVELS[index % len(LEVELS)]
            preferred_split = (("train",) * 5 + ("validation", "test"))[(index // len(LEVELS)) % 7]
            reserved = Counter()
            for ident in current["pending"]:
                pending_row = gen.read_json(folder / f"{ident}.teacher-result.json")
                reserved[pending_row["split"], pending_row["level"]] += 1
            for entry, _ in teacher_jobs:
                if entry["id"] not in current["pending"]:
                    reserved[entry["split"], entry["level"]] += 1
            cells = [(s, level) for s, target in manifest["targetsPerLevel"].items() for level in LEVELS
                     if current["counts"][s][level] + reserved[s, level] < target]
            if not cells:
                state["status"] = review_state
                return False
            split, level = (preferred_split, preferred_level) if (preferred_split, preferred_level) in cells else cells[0]
            choices = [g for g in manifest["groups"] if g["split"] == split and g["level"] == level]
            group = min(choices, key=lambda g: gen.digest([index, g["groupId"], level]))
            prior = [item for item in accepted if item["groupId"] == group["groupId"] and item["level"] == level]
            previous = gen.read_json(folder / f"{prior[-1]['id']}.teacher-result.json") if prior else None
            turn = 3 if previous and previous["context"]["turnNumber"] == 2 else (2 if index // 4 % 2 == 0 else 4)
            history = [{**previous, "eligible": True}] if turn == 3 else []
            varied_group = copy.deepcopy(group)
            varied_group["input"]["variationSeed"] = 17000 + index
            context = gen.make_context(varied_group, turn, history, artifact, index)
            ident = f"candidate-{index + 1:04d}"
            entry = {"id": ident, "groupId": group["groupId"], "split": split, "level": level, "context": context,
                     "teacherProvider": teacher["provider"]}
            state.update(status="RUNNING", nextIndex=index + 1, currentId=ident)
            run._atomic_json(path / "state.json", state)
            arm_watchdog()
            contract = {**manifest, "teacher": teacher}
            teacher_jobs.append((entry, asyncio.create_task(
                run._teacher_one(path, contract, entry, group, artifact, folder, settings))))
            scheduled += 1
            return True

        def retain_teacher(row):
            if not row["hardPass"]:
                reject(path, row, "hard-validation-or-provider-error:" + row.get("errorType", "unknown"))
                if row.get("providerHttpStatus") in {402, 429} or row.get("errorType") == "QuotaUnavailable":
                    provider = row.get("teacherProvider", "deepseek")
                    teacher_stop("WAITING_" + provider.upper() + "_QUOTA", provider + " quota or balance exhausted")
                elif row.get("transportOutcomeUnknown"):
                    teacher_stop("BLOCKED", "Teacher transport outcome unknown; inspect before continuing")
                return False
            if duplicate(row["task"]["utteranceEn"], corpus):
                reject(path, row, "exact-or-near-duplicate")
                return False
            corpus.append(normalized(row["task"]["utteranceEn"]))
            return True

        async def finish_web():
            nonlocal web_job
            if web_job is None:
                return
            state["currentId"] = web_row["id"]
            run._atomic_json(path / "state.json", state)
            job, web_job = web_job, None
            await job

        try:
            for processed in range(limit):
                # Observe a completed failure before starting another paid request.
                if web_job is not None and web_job.done():
                    try:
                        await finish_web()
                    except run.web_accounts.WebQuotaWait as error:
                        state.update(status="WAITING_WEB_QUOTA", retryAt=error.retry_at)
                        break
                    except Exception as error:
                        state.update(status="BLOCKED", errorType=type(error).__name__)
                        run._atomic_json(path / "state.json", state)
                        raise
                # An out-of-order sibling failure must stop new paid calls immediately.
                for _, job in teacher_jobs:
                    if job.done():
                        try:
                            result = job.result()
                        except Exception:
                            teacher_stop("BLOCKED", "Teacher worker failed; inspect checkpoint")
                            break
                        if result.get("transportOutcomeUnknown"):
                            teacher_stop("BLOCKED", "Teacher transport outcome unknown; inspect before continuing")
                        elif result.get("providerHttpStatus") in {402, 429} or result.get("errorType") == "QuotaUnavailable":
                            provider = result.get("teacherProvider", "deepseek")
                            teacher_stop("WAITING_" + provider.upper() + "_QUOTA", provider + " quota or balance exhausted")
                while scheduled < limit and len(teacher_jobs) < concurrency and not state.get("teacherStopStatus"):
                    if not start_teacher():
                        break
                if not teacher_jobs:
                    break
                entry, teacher_job = teacher_jobs.pop(0)
                ident, level = entry["id"], entry["level"]
                try:
                    row = await teacher_job
                    needs_review = retain_teacher(row)
                    # Never cancel an in-flight Web request, including on teacher failure.
                    await finish_web()
                    state["currentId"] = ident
                    if state.get("teacherStopStatus"):
                        state["status"] = state["teacherStopStatus"]
                        break
                    if needs_review:
                        web_buffer.append(row)
                        if len(web_buffer) >= web_batch_size:
                            rows, web_buffer = web_buffer, []
                            web_row = rows[0]
                            work = (run._judge_web_batch(path, manifest, rows, folder=folder)
                                    if len(rows) > 1 else judge(rows[0]))
                            web_job = asyncio.create_task(work)
                            # Start Web now, before the next teacher's synchronous setup.
                            await asyncio.sleep(0)
                            if not overlap_web:
                                await finish_web()
                    state["status"] = "RUNNING"
                    run._atomic_json(path / "state.json", state)
                    print(json.dumps({"candidate": ident, "level": level, "hardPass": row["hardPass"],
                                      "pendingReview": (folder / f"{ident}.teacher-result.json").exists()}), flush=True)
                except run.web_accounts.WebQuotaWait as error:
                    state.update(status=state.get("teacherStopStatus", "BLOCKED" if state["status"] == "BLOCKED"
                                                  else "WAITING_WEB_QUOTA"), retryAt=error.retry_at)
                    break
                except run.QuotaUnavailable:
                    provider = entry["teacherProvider"]
                    teacher_stop("WAITING_" + provider.upper() + "_QUOTA" if len(teachers) > 1 or web_approval else
                                 review_state if status(path)["pending"] else "WAITING_QUOTA",
                                 provider + " quota unavailable")
                    state["status"] = state["teacherStopStatus"]
                    break
                except Exception as error:
                    # A possibly sent request must never be dispatched again on resume.
                    state.update(status="BLOCKED", errorType=type(error).__name__)
                    run._atomic_json(path / "state.json", state)
                    raise
        except Exception as error:
            state.update(status="BLOCKED", errorType=type(error).__name__)
            run._atomic_json(path / "state.json", state)
            raise
        finally:
            # Preserve every paid response on stop; never cancel or resend sibling calls.
            for entry, job in teacher_jobs:
                try:
                    retain_teacher(await job)
                except run.QuotaUnavailable:
                    teacher_stop("WAITING_" + entry["teacherProvider"].upper() + "_QUOTA" if len(teachers) > 1 else "WAITING_QUOTA",
                                 "Queued teacher quota unavailable")
                except Exception as error:
                    teacher_stop("BLOCKED", "Queued teacher failed; inspect checkpoint")
                    state["errorType"] = type(error).__name__
            if state.get("teacherStopStatus"):
                state["status"] = state["teacherStopStatus"]
            if web_job is not None:
                try:
                    await finish_web()
                except run.web_accounts.WebQuotaWait as error:
                    state.update(status=state.get("teacherStopStatus", "BLOCKED" if state["status"] == "BLOCKED"
                                                  else "WAITING_WEB_QUOTA"), retryAt=error.retry_at)
                except Exception as error:
                    state.update(status="BLOCKED", errorType=type(error).__name__)
                    run._atomic_json(path / "state.json", state)
                    raise
            if web_buffer and state["status"] in {"RUNNING", "CANDIDATE_CAP", review_state} and not state.get("teacherStopStatus"):
                state["currentId"] = web_buffer[0]["id"]
                try:
                    if len(web_buffer) > 1:
                        await run._judge_web_batch(path, manifest, web_buffer, folder=folder)
                    else:
                        await judge(web_buffer[0])
                except run.web_accounts.WebQuotaWait as error:
                    state.update(status="WAITING_WEB_QUOTA", retryAt=error.retry_at)
                except Exception as error:
                    state.update(status="BLOCKED", errorType=type(error).__name__)
                    run._atomic_json(path / "state.json", state)
                    raise
            _finalize_web(path, manifest, state)
            run._atomic_json(path / "state.json", state)
        if state["status"] == "RUNNING":
            state["status"] = review_state
        run._atomic_json(path / "state.json", state)
        if shutdown_on_failure and watchdog is None:
            from training.shutdown_watchdog import request_shutdown, shutdown_reason
            reason = shutdown_reason(state, worker_alive=True)
            if reason and not (path / "shutdown-request.json").exists():
                request_shutdown(reason, path / "shutdown-request.json")
    return status(path)


def adjudicate(path: Path, verdict: dict) -> dict:
    manifest, _ = load(path)
    if manifest.get("approvalAuthority") == WEB_AUTHORITY:
        raise DatasetError("Web-authority collection does not accept Codex adjudication")
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
            if current["counts"][row["split"]][row["level"]] >= manifest["targetsPerLevel"][row["split"]]:
                raise DatasetError("level/split target already full")
            result = {**verdict, **{key: row[key] for key in ("groupId", "split", "level")},
                      "time": run._now()}
            run._atomic_json(path / "decisions" / f"{ident}.json", result)
        else:
            raise DatasetError("verdict must be approved or rejected; uncertain items cannot enter SFT")
        state = gen.read_json(path / "state.json")
        if state["status"] not in {"WAITING_DEEPSEEK_QUOTA", "WAITING_GEMINI_QUOTA", "WAITING_WEB_QUOTA", "BLOCKED"}:
            state["status"] = "AWAITING_CODEX_REVIEW" if status(path)["pending"] else "READY"
        run._atomic_json(path / "state.json", state)
    return status(path)


def export(path: Path) -> dict:
    manifest, _ = load(path)
    if manifest.get("diagnosticOnly"):
        raise DatasetError("diagnostic experiments cannot be exported for SFT")
    if not status(path)["complete"] or status(path)["pending"]:
        total = sum(manifest["targetsPerLevel"].values()) * len(LEVELS)
        raise DatasetError(f"all {total} approved tasks are required before dataset export")
    verify_inherited(path, manifest)
    extra_artifacts = []
    inherited = manifest
    while inherited.get("continuation"):
        inherited = inherited["continuation"]["parentManifest"]
        original_path = Path(inherited["artifactPath"])
        if gen.read_json(original_path)["sha256"] != inherited["artifactHash"]:
            raise DatasetError("inherited artifact changed after continuation")
        extra_artifacts.append(original_path)
    output = path / "export"
    output.mkdir()
    rows = []
    reviewers = {}
    for item in decisions(path):
        if item["verdict"] == "approved":
            row = gen.read_json(path / "candidates" / f"{item['id']}.teacher-result.json")
            if item["reviewer"] == "AI:chatgpt-web":
                review = gen.read_json(path / "candidates" / f"{item['id']}.result.json")
                if (item["taskHash"] != gen.digest(row["task"]) or
                        item["contextHash"] != gen.digest(row["context"]) or
                        item["webReviewHash"] != gen.digest(review)):
                    raise DatasetError("Web decision evidence changed before export")
                _validate_web_review(row, review, manifest)
            for record in row["records"]:
                record["semanticReviewReference"] = str(path / "decisions" / f"{item['id']}.json")
                rows.append(record)
                reviewers[record["id"]] = item["reviewer"]
    source = output / "approved.jsonl"
    source.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8", newline="\n")
    review_file = output / "decisions.csv"
    with review_file.open("x", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["id", "decision", "reviewer", "inputHash", "outputHash"])
        writer.writeheader()
        for row in rows:
            writer.writerow({"id": row["id"], "decision": "approved", "reviewer": reviewers[row["id"]],
                             "inputHash": row["inputHash"], "outputHash": row["outputHash"]})
    return prepare_dataset([source], review_file, output / "dataset", artifact_path=Path(manifest["artifactPath"]),
        forced_test_groups=set(manifest["holdouts"]), review_policy="delegated-ai",
        extra_artifact_paths=tuple(extra_artifacts))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "collect", "status", "adjudicate", "export"))
    parser.add_argument("--campaign", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=4)
    parser.add_argument("--total-articles", type=int, choices=(500, 700), default=700,
                        help="freeze total on prepare: 500 uses 400 train / 48 validation / 52 test")
    parser.add_argument("--decision-file", type=Path)
    parser.add_argument("--artifact", type=Path, help="freeze this candidate on prepare; default is the best measured baseline")
    parser.add_argument("--web-account", action="append", default=[], metavar="ID=URL",
                        help="repeat for independent local bridge accounts; switch only on explicit Web quota")
    parser.add_argument("--diagnostic", action="store_true", help="prepare an experiment excluded from SFT")
    parser.add_argument("--web-approval", action="store_true",
                        help="freeze Web PASS as final approval authority on prepare; no Codex adjudication")
    parser.add_argument("--continue-from", type=Path, help="snapshot a fully decided main campaign on prepare")
    parser.add_argument("--gemini-teacher", action="store_true",
                        help="freeze Gemini 3.5 Flash-Lite accounts as parallel teachers on prepare")
    parser.add_argument("--overlap-web", action="store_true", help="overlap one teacher call with one Web review")
    parser.add_argument("--deepseek-only", action="store_true", help="disable additional teachers without changing frozen evidence")
    parser.add_argument("--teacher-concurrency", type=int, choices=(1, 2),
                        help="maximum in-flight teacher calls; default one per frozen provider")
    parser.add_argument("--web-batch-size", type=int, choices=(1, 2, 3), default=1,
                        help="independent articles per fresh Web conversation; default one")
    parser.add_argument("--retain-diagnostic-evidence", action="store_true",
                        help="preserve rejected diagnostic requests and responses for owner review")
    parser.add_argument("--shutdown-on-worker-failure", action="store_true",
                        help="arm an external watchdog with the owner's forced shutdown authorization")
    args = parser.parse_args()
    path = run._private_path(args.campaign)
    if args.command == "prepare":
        result = prepare(path, diagnostic=args.diagnostic,
                         retain_diagnostic_evidence=args.retain_diagnostic_evidence, artifact_path=args.artifact,
                         web_accounts=[dict(zip(("id", "url"), item.split("=", 1))) for item in args.web_account] or None,
                         continue_from=args.continue_from, gemini_teacher=args.gemini_teacher,
                         total_articles=args.total_articles, web_approval=args.web_approval)
    elif args.command == "collect":
        result = asyncio.run(collect(path, limit=args.limit, shutdown_on_failure=args.shutdown_on_worker_failure,
                                     overlap_web=args.overlap_web, deepseek_only=args.deepseek_only,
                                     web_batch_size=args.web_batch_size, teacher_concurrency=args.teacher_concurrency))
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
