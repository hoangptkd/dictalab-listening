"""Fail-closed campaign bookkeeping for first-pass Conversation teacher experiments.

Private campaign data stays under data/fine-tuning; this module never exports SFT data.
The legacy benchmark remains unchanged. Run ``python -m training.iterate_teacher --help``.
"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path

from training import generate_teacher_data as gen
from training.benchmark_teacher import settings_from_registry
from training.prepare_dataset import _validation, validator_hash
from training.semantic_rubric import CHECKS, VERSION_V3, decision, rules_for
from app.gemini_pool import quota_store


POLICY = "teacher-first-pass-28of30-v1"
LEVELS = ("A1", "A2", "B1")
PRIVATE_ROOT = (gen.ROOT / "data/fine-tuning").resolve()
DEFAULT_ARTIFACT = gen.ROOT / "artifacts/prompts/conversation-cefr-v9-20261022.json"
SOURCE_PLANS = (gen.ROOT / "data/fine-tuning/semantic-v8-ab-20261022/plan.json",
                gen.ROOT / "data/fine-tuning/semantic-v9-pilot-20261022/plan.json")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _private_path(path: Path) -> Path:
    path = path.resolve()
    if path == PRIVATE_ROOT or PRIVATE_ROOT not in path.parents:
        raise gen.DatasetError("campaign must be a child of private data/fine-tuning")
    return path


def _atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


@contextmanager
def _campaign_lock(path: Path):
    lock = path / ".owner.lock"
    try:
        fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise gen.DatasetError("campaign is owned by another process; inspect .owner.lock") from None
    try:
        with os.fdopen(fd, "w", encoding="ascii") as handle:
            handle.write(str(os.getpid()))
            handle.flush()
            os.fsync(handle.fileno())
        yield
    finally:
        lock.unlink()


def _checked_artifact(path: Path) -> dict:
    artifact = gen.read_json(path)
    if artifact.get("sha256") != gen.digest({k: v for k, v in artifact.items() if k != "sha256"}):
        raise gen.DatasetError("artifact hash mismatch")
    if artifact.get("approvalStatus") != "development":
        raise gen.DatasetError("teacher campaign requires a development artifact")
    return artifact


def _plan_groups() -> dict:
    exposed = set()
    for source in SOURCE_PLANS:
        if not source.is_file():
            raise gen.DatasetError("DATA_COVERAGE_BLOCKED: prior source plan is missing")
        previous = gen.read_json(source)
        exposed.update(row["group"]["groupId"] for row in previous["entries"])
        exposed.update(row["groupId"] for row in previous["chains"])
    groups = gen.plan_groups(gen.read_json(gen.CATALOGUE)["catalogue"])
    result = {"development": [], "unseenCapacityByLevel": {}}
    for level in LEVELS:
        available = sorted((g for g in groups if g["level"] == level and g["groupId"] not in exposed),
                           key=lambda g: gen.digest(g["groupId"]))
        if len(available) < 16:
            raise gen.DatasetError(f"DATA_COVERAGE_BLOCKED: fewer than 16 independent {level} groups")
        result["development"].extend(available[:4])
        result["unseenCapacityByLevel"][level] = len(available) - 4
    return result


def prepare(path: Path, *, artifact_path: Path = DEFAULT_ARTIFACT, max_rounds: int = 10,
            settings=None) -> dict:
    path = _private_path(path)
    if path.exists():
        raise gen.DatasetError("refusing to overwrite an existing campaign")
    if not 1 <= max_rounds <= 10:
        raise gen.DatasetError("each session allows 1-10 candidate rounds")
    artifact = _checked_artifact(artifact_path)
    settings = settings or settings_from_registry()
    teacher_sources = settings.sources_for("deepseek")
    judge_sources = settings.sources_for("gemini")
    if not teacher_sources or not judge_sources:
        raise gen.DatasetError("both registry providers must have enabled accounts")
    teacher_model = settings.registry_pools["deepseek"]["default_model"]
    judge_model = settings.registry_pools["gemini"]["default_model"]
    if teacher_model == judge_model or any(s.model != teacher_model for s in teacher_sources) or any(
            s.model != judge_model for s in judge_sources):
        raise gen.DatasetError("campaign requires one fixed model per provider")
    groups = _plan_groups()
    manifest = {"policy": POLICY, "createdAt": _now(), "artifactPath": str(artifact_path.resolve()),
        "artifactHash": artifact["sha256"], "catalogueHash": gen.digest(gen.read_json(gen.CATALOGUE)),
        "validatorHash": validator_hash(), "rubricVersion": VERSION_V3,
        "sourcePlanHashes": [gen.digest(gen.read_json(source)) for source in SOURCE_PLANS],
        "rubricHash": gen.digest(rules_for(VERSION_V3)),
        "teacherWrapperHash": gen.digest(gen.TEACHER_BATCH),
        "teacher": {"provider": "deepseek", "model": teacher_model,
                    "sourceIds": [s.id for s in teacher_sources], "temperature": 0.2, "maxOutputTokens": 8192},
        "judge": {"provider": "gemini", "model": judge_model,
                  "sourceIds": [s.id for s in judge_sources], "temperature": 0},
        "groups": groups, "groupAllocation": "development-only-before-candidate-freeze",
        "goldPath": str((gen.ROOT / "data/fine-tuning/teacher-gold-v3.json").resolve()),
        "repair": False, "retry": False, "fallback": False, "trainingReady": False}
    manifest["manifestHash"] = gen.digest(manifest)
    path.mkdir(parents=True)
    with _campaign_lock(path):
        gen.write_json(path / "campaign.json", manifest)
        _atomic_json(path / "state.json", {"status": "JUDGE_BLOCKED", "reason": "gold set not calibrated",
            "sessionId": 1, "roundInSession": 0, "totalRounds": 0, "maxRounds": max_rounds,
            "phase": "calibration", "accepted": False, "updatedAt": _now()})
    return manifest


def load(path: Path) -> tuple[dict, dict]:
    path = _private_path(path)
    manifest = gen.read_json(path / "campaign.json")
    if manifest.get("manifestHash") != gen.digest({k: v for k, v in manifest.items() if k != "manifestHash"}):
        raise gen.DatasetError("campaign manifest changed")
    if manifest.get("groupAllocation") != "development-only-before-candidate-freeze" or \
            manifest["rubricHash"] != gen.digest(rules_for(manifest["rubricVersion"])) or \
            manifest["validatorHash"] != validator_hash() or \
            manifest["teacherWrapperHash"] != gen.digest(gen.TEACHER_BATCH) or \
            manifest["catalogueHash"] != gen.digest(gen.read_json(gen.CATALOGUE)) or \
            manifest.get("sourcePlanHashes") != [gen.digest(gen.read_json(source)) for source in SOURCE_PLANS] or \
            _checked_artifact(Path(manifest["artifactPath"]))["sha256"] != manifest["artifactHash"]:
        raise gen.DatasetError("campaign code/data contract changed; open a new campaign")
    return manifest, gen.read_json(path / "state.json")


def audit_gold(cases: list[dict]) -> dict:
    if len(cases) != 36 or len({row.get("id") for row in cases}) != 36:
        raise gen.DatasetError("JUDGE_BLOCKED: gold requires 36 unique cases")
    expected = Counter((row.get("expected"), row.get("expectedSeverity")) for row in cases)
    if (sum(n for (label, _), n in expected.items() if label == "PASS") != 12 or
        sum(n for (label, kind), n in expected.items() if label == "FAIL" and kind in ("major", "minor")) != 12 or
        expected[("FAIL", "critical")] != 8 or
        sum(n for (label, _), n in expected.items() if label == "REVIEW_REQUIRED") != 4):
        raise gen.DatasetError("JUDGE_BLOCKED: gold label/severity distribution is incomplete")
    if len({row.get("sourceId") for row in cases}) < 12:
        raise gen.DatasetError("JUDGE_BLOCKED: gold needs 12 independent source items")
    for row in cases:
        if (row.get("rubricVersion") != VERSION_V3 or row.get("hardPass") is not True or
            not isinstance(row.get("context"), dict) or not isinstance(row.get("task"), dict) or
            not isinstance(row.get("sourceId"), str) or not row["sourceId"] or
            row.get("semanticProbeOnly") is True):
            raise gen.DatasetError("JUDGE_BLOCKED: gold must contain reviewed full-schema v3 tasks")
        context = row["context"]
        if context.get("difficulty") not in LEVELS or _validation.validate_task(
                row["task"], level=context["difficulty"], level_profile=context.get("levelProfile")):
            raise gen.DatasetError("JUDGE_BLOCKED: gold contains a production-invalid task")
    return {"count": 36, "sources": len({row["sourceId"] for row in cases}),
            "hash": gen.digest(cases), "labels": dict(Counter(row["expected"] for row in cases))}


def _review_integrity(row: dict, review: dict, manifest: dict) -> bool:
    if not isinstance(review, dict) or not row.get("hardPass"):
        return False
    if (review.get("reviewer") != "AI:" + manifest["judge"]["model"] or
        review.get("rubricVersion") != manifest["rubricVersion"] or
        review.get("rubricHash") != manifest["rubricHash"] or
        review.get("taskHash") != gen.digest(row.get("task")) or
        review.get("contextHash") != gen.digest(row.get("context")) or
        any(not isinstance(review.get(key), str) or len(review[key]) != 64 or
            any(char not in "0123456789abcdef" for char in review[key])
            for key in ("requestHash", "responseHash")) or
        set(review.get("checks", {})) != set(CHECKS) or
        any(type(value) is not bool and value is not None for value in review["checks"].values()) or
        not isinstance(review.get("issues"), list) or
        any(not isinstance(issue, str) or not issue.strip() for issue in review["issues"])):
        return False
    uncertain = any(value is not True for value in review["checks"].values())
    return bool(review["issues"]) == uncertain


def checked_review(row: dict, review: dict, manifest: dict, *, calibrated: bool) -> str:
    if not calibrated or not _review_integrity(row, review, manifest):
        return "REVIEW_REQUIRED"
    return decision({**review["checks"], "issues": review["issues"]})


def calibration_gate(cases: list[dict], passes: list[dict], manifest: dict) -> bool:
    audit_gold(cases)
    if len(passes) != 2:
        return False
    case_ids = {row["id"] for row in cases}
    if (passes[0].get("runId") == passes[1].get("runId") or
        passes[0].get("order") == passes[1].get("order")):
        return False
    request_hashes = set()
    for run in passes:
        if (not isinstance(run.get("runId"), str) or not run["runId"] or
            set(run.get("reviews", {})) != case_ids or
            len(run.get("order", [])) != 36 or set(run["order"]) != case_ids):
            return False
        correct = 0
        for case in cases:
            review = run["reviews"][case["id"]]
            if not _review_integrity(case, review, manifest):
                return False
            if review["requestHash"] in request_hashes:
                return False
            request_hashes.add(review["requestHash"])
            result = checked_review(case, review, manifest, calibrated=True)
            if result == case["expected"]:
                correct += 1
            if case["expectedSeverity"] == "critical" and result == "PASS":
                return False
            if case["expected"] == "REVIEW_REQUIRED" and result != "REVIEW_REQUIRED":
                return False
        if correct < 35:
            return False
    return True


def acceptance_gate(items: list[dict], chains: list[list[dict]], *, calibrated: bool,
                    coverage_valid: bool, provenance_valid: bool) -> dict:
    counts = Counter(row.get("level") for row in items if row.get("decision") == "PASS")
    hard = sum(row.get("hardPass") is True for row in items)
    critical = sum(row.get("severity") == "critical" for row in items)
    unresolved = any(row.get("unresolvedCriticalRisk") is not False for row in items)
    unresolved = unresolved or any(row.get("unresolvedCriticalRisk") is not False
                                   for chain in chains for row in chain)
    complete = sum(len(chain) == 3 and all(row.get("decision") == "PASS" and
        row.get("hardPass") is True and row.get("severity") != "critical" and
        row.get("unresolvedCriticalRisk") is False and row.get("attempt") == 1
        for row in chain) for chain in chains)
    valid_shape = (len(items) == 30 and len(chains) == 6 and
        all(sum(row.get("level") == level for row in items) == 10 for level in LEVELS) and
        all(row.get("attempt") == 1 and row.get("developmentOnly") is True for row in items) and
        all(row.get("attempt") == 1 and row.get("developmentOnly") is True
            for chain in chains for row in chain))
    accepted = bool(calibrated and coverage_valid and provenance_valid and valid_shape and
        hard == 30 and sum(counts.values()) >= 28 and all(counts[level] >= 9 for level in LEVELS) and
        critical == 0 and not unresolved and complete >= 5 and
        all(row.get("severity") != "critical" for chain in chains for row in chain))
    return {"accepted": accepted, "hardPass": hard, "semanticPass": sum(counts.values()),
            "byLevel": {level: counts[level] for level in LEVELS},
            "critical": critical, "unresolvedCriticalRisk": unresolved, "completeChains": complete,
            "trainingReady": False}


def quota_snapshot(settings=None) -> dict:
    settings = settings or settings_from_registry()
    snapshot = {}
    for platform in ("deepseek", "gemini"):
        pool = quota_store(settings, platform=platform)
        sources = settings.sources_for(platform)
        account = []
        global_used = 0
        for source in sources:
            counters = pool.execute(source, op="status")
            global_used = int(counters[4])
            account.append({"sourceId": source.id, "usedRpd": int(counters[2]),
                "rpdLimit": source.rpd_limit,
                "remainingRpd": max(0, source.rpd_limit - int(counters[2])) if source.rpd_limit else None})
        global_limit = settings.registry_pools[platform]["global_rpd_limit"]
        global_remaining = max(0, global_limit - global_used) if global_limit else None
        account_remaining = sum(row["remainingRpd"] for row in account) if all(
            row["remainingRpd"] is not None for row in account) else None
        available = min(x for x in (global_remaining, account_remaining) if x is not None) \
            if global_remaining is not None or account_remaining is not None else None
        snapshot[platform] = {"accounts": account, "globalUsedRpd": global_used,
            "globalRpdLimit": global_limit, "availableCalls": available}
    return snapshot


def status(path: Path) -> dict:
    manifest, state = load(path)
    gold = Path(manifest["goldPath"])
    try:
        gold_status = audit_gold(gen.read_json(gold)) if gold.exists() else {"status": "missing"}
    except (ValueError, KeyError) as error:
        gold_status = {"status": "JUDGE_BLOCKED", "reason": str(error)}
    try:
        quota = quota_snapshot()
    except Exception as error:
        quota = {"status": "unavailable", "errorType": type(error).__name__}
    return {"campaign": str(_private_path(path)), "policy": manifest["policy"],
            "state": state, "gold": gold_status, "teacherModel": manifest["teacher"]["model"],
            "judgeModel": manifest["judge"]["model"], "quota": quota, "trainingReady": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "status"))
    parser.add_argument("--campaign", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, default=DEFAULT_ARTIFACT)
    parser.add_argument("--max-rounds", type=int, default=10)
    args = parser.parse_args()
    result = prepare(args.campaign, artifact_path=args.candidate, max_rounds=args.max_rounds) \
        if args.action == "prepare" else status(args.campaign)
    print(json.dumps(result if args.action == "status" else
        {"campaign": str(args.campaign), "manifestHash": result["manifestHash"], "status": "JUDGE_BLOCKED"},
        ensure_ascii=False))


if __name__ == "__main__":
    main()
