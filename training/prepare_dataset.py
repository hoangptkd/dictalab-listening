"""Prepare an explicitly reviewed SFT dataset without changing any target output.

This tool uses the existing production validator and JSON Schema. It accepts exported JSONL records,
requires an explicit reviewer decision for every record, validates the message
contract, removes rejected/duplicate records, and splits by conversation group.
It does not repair, rewrite, or call a model.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "services/ai-generation/app/validation.py"
_spec = importlib.util.spec_from_file_location("sft_production_validation", VALIDATOR_PATH)
_validation = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_validation)


APPROVED = {"approved", "approve", "accepted", "accept", "pass"}
REJECTED = {"rejected", "reject", "failed", "fail"}
PENDING = {"", "pending", "needs_adjudication", "needs-adjudication", "uncertain", "review_required"}
PURPOSES = {"content", "translation"}
SECRET_RE = re.compile(
    r"(?:bearer\s+\S+|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|"
    r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})",
    re.IGNORECASE,
)


class DatasetError(ValueError):
    """Raised when a dataset cannot be safely prepared for training."""


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def validator_hash() -> str:
    # Git may check out CRLF on Windows and LF on the training host.
    return digest(VALIDATOR_PATH.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise DatasetError(f"{path}:{line_number}: invalid JSON") from exc
            if not isinstance(row, dict):
                raise DatasetError(f"{path}:{line_number}: record must be an object")
            row["_sourcePath"] = str(path)
            row["_sourceLine"] = line_number
            rows.append(row)
    return rows


def read_decisions(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        raise DatasetError(f"human review file is missing: {path}")
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"id", "decision", "reviewer", "inputHash", "outputHash"}
        if not required.issubset(set(reader.fieldnames or [])):
            raise DatasetError("human review CSV requires id, decision, reviewer, inputHash and outputHash columns")
        result: dict[str, dict[str, str]] = {}
        for line_number, raw in enumerate(reader, 2):
            if None in raw:
                raise DatasetError(f"human review CSV line {line_number}: extra columns")
            key = (raw.get("id") or "").strip()
            if not key:
                raise DatasetError(f"human review CSV line {line_number}: missing id")
            if key in result:
                raise DatasetError(f"human review CSV: duplicate id {key}")
            result[key] = {k: (v or "").strip() for k, v in raw.items()}
    return result


def _walk_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and key.casefold() in {
                    "password", "token", "secret", "api_key", "authorization", "cookie"}:
                yield "Bearer credential-field"
            yield from _walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_strings(child)


def _contains_secret(value: Any) -> bool:
    for text in _walk_strings(value):
        if SECRET_RE.search(text):
            return True
        # Messages contain serialized JSON; inspect keys inside it as well.
        try:
            nested = json.loads(text)
        except (ValueError, TypeError):
            continue
        if isinstance(nested, (dict, list)) and _contains_secret(nested):
            return True
    return False


def _normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold()
    return re.sub(r"[^\w]+", "", value, flags=re.UNICODE)


def _target(row: dict[str, Any]) -> tuple[str, int, dict[str, Any]]:
    messages = row.get("messages")
    if not isinstance(messages, list) or len(messages) != 3:
        raise DatasetError("messages must contain system, user and assistant messages")
    if any(not isinstance(message, dict) or set(message) != {"role", "content"}
           or message.get("role") != role or not isinstance(message.get("content"), str)
           or not message["content"].strip()
           for message, role in zip(messages, ("system", "user", "assistant"))):
        raise DatasetError("messages contain an invalid role or shape")
    target_index = row.get("targetMessageIndex", len(messages) - 1)
    if type(target_index) is not int or target_index != len(messages) - 1:
        raise DatasetError("targetMessageIndex must point to the final assistant message")
    target_message = messages[target_index]
    if target_message.get("role") != "assistant" or not isinstance(target_message.get("content"), str):
        raise DatasetError("the final target message must be an assistant string")
    try:
        target = json.loads(target_message["content"])
    except json.JSONDecodeError as exc:
        raise DatasetError("the final assistant target is not JSON") from exc
    if not isinstance(target, dict):
        raise DatasetError("the final assistant target must be an object")
    purpose = row.get("purpose")
    if not isinstance(purpose, str) or purpose not in PURPOSES:
        raise DatasetError("purpose must be content or translation")
    if purpose == "content":
        required = {"utteranceEn", "facts", "questionVi"}
    else:
        required = {"translationVi", "facts"}
    if not required.issubset(target):
        raise DatasetError(f"{purpose} target is missing required fields")
    if not isinstance(target["facts"], list) or not target["facts"]:
        raise DatasetError(f"{purpose} target facts must be a non-empty list")
    return purpose, target_index, target


def _review(row: dict[str, Any], decisions: dict[str, dict[str, str]]) -> str:
    key = row.get("id")
    if not isinstance(key, str) or not key.strip():
        raise DatasetError("record id is required")
    decision_row = decisions.get(key)
    if decision_row is not None:
        decision = decision_row.get("decision", "").casefold()
        reviewer = decision_row.get("reviewer", "")
    else:
        decision, reviewer = "", ""
    if decision in PENDING:
        raise DatasetError(f"{key}: human review is missing or unresolved")
    if decision in APPROVED and not reviewer:
        raise DatasetError(f"{key}: approved record needs a reviewer reference")
    if decision in APPROVED:
        for field in ("inputHash", "outputHash"):
            if not decision_row.get(field) or decision_row[field] != row.get(field):
                raise DatasetError(f"{key}: human review {field} does not match the record")
    if decision not in APPROVED and decision not in REJECTED:
        raise DatasetError(f"{key}: unsupported review decision {decision!r}")
    return decision


def _contract(row: dict[str, Any], artifact: dict[str, Any]) -> None:
    if row.get("developmentOnly") is True:
        raise DatasetError("development calibration/benchmark records cannot enter SFT")
    purpose, _, target = _target(row)
    level = row.get("level")
    if level not in ("A1", "A2", "B1") or type(row.get("turnNumber")) is not int or row["turnNumber"] not in (2, 3, 4):
        raise DatasetError("P0 requires A1/A2/B1 and turn 2/3/4")
    for field in ("scenario", "branchId", "source", "semanticReviewReference", "reviewVersion"):
        if not isinstance(row.get(field), str) or not row[field].strip():
            raise DatasetError(f"{field} is required")
    if row.get("repaired") is not False or type(row.get("attempt")) is not int or row["attempt"] != 1:
        raise DatasetError("only explicit unrepaired first-attempt records are allowed")
    if row.get("lossMaskPolicy") != "last-assistant-only":
        raise DatasetError("lossMaskPolicy must be last-assistant-only")
    schema = (artifact.get("contentSchemas", {}).get(level, artifact["contentSchema"])
              if purpose == "content" else artifact["translationSchema"])
    system = artifact["contentSystem" if purpose == "content" else "translationSystem"]
    if purpose == "content":
        examples = [e for e in artifact["fewShotExamples"] if e.get("input", {}).get("difficulty") == level
                    and e.get("input", {}).get("turnNumber") == (4 if row["turnNumber"] == 4 else 2)]
        system = artifact["instruction"] + "\n" + system + "\nExamples are format only: " + json.dumps(examples, ensure_ascii=False)
    messages = row["messages"]
    if row.get("artifactVersion") != artifact["version"] or messages[0]["content"] != system:
        raise DatasetError("artifact/system prompt mismatch")
    for field, value in (("promptHash", system), ("schemaHash", schema),
                         ("inputHash", messages[:-1]), ("outputHash", target)):
        if row.get(field) != digest(value):
            raise DatasetError(f"{field} mismatch")
    errors = list(Draft202012Validator(schema).iter_errors(target))
    if errors:
        raise DatasetError("target does not match artifact schema")
    merged = row.get("validationTask")
    if not isinstance(merged, dict):
        raise DatasetError("validationTask (reviewed content plus translation) is required")
    content = {k: merged.get(k) for k in ("slots", "utteranceEn", "facts", "questionVi", "taskVi", "skill")}
    facts = merged.get("facts")
    if not isinstance(facts, list) or any(not isinstance(f, dict) for f in facts):
        raise DatasetError("validationTask facts must be objects")
    content["facts"] = [{k: v for k, v in f.items() if k != "textVi"} for f in facts]
    translated = {"translationVi": merged.get("translationVi"), "facts": [{"textVi": f.get("textVi")} for f in facts]}
    for value, shape in ((content, artifact.get("contentSchemas", {}).get(level, artifact["contentSchema"])),
                         (translated, artifact["translationSchema"])):
        if list(Draft202012Validator(shape).iter_errors(value)):
            raise DatasetError("validationTask does not match artifact schemas")
    if target != (content if purpose == "content" else translated):
        raise DatasetError("target differs from validationTask")
    try:
        context = json.loads(messages[1]["content"])
    except ValueError as exc:
        raise DatasetError("user message must contain JSON context") from exc
    if not isinstance(context, dict):
        raise DatasetError("user context must be an object")
    if purpose == "content":
        if context.get("difficulty") != level or context.get("turnNumber") != row["turnNumber"]:
            raise DatasetError("context level/turn differs from metadata")
    elif context.get("utteranceEn") != content["utteranceEn"] or context.get("facts") != content["facts"]:
        raise DatasetError("translation source differs from validationTask")
    profile = row.get("levelProfile", artifact.get("levelProfiles", {}).get(level, {}).get("wordBounds"))
    if purpose == "content" and context.get("levelProfile") != profile:
        raise DatasetError("context levelProfile differs from validation profile")
    if profile is not None and not isinstance(profile, dict):
        raise DatasetError("levelProfile must be an object")
    errors = _validation.validate_task(merged, level=level, level_profile=profile)
    if errors:
        raise DatasetError("production validator: " + ", ".join(errors))


def _group(row: dict[str, Any]) -> str:
    group = row.get("groupId") or row.get("group")
    if not isinstance(group, str) or not group.strip():
        raise DatasetError(f"{row.get('id', '<unknown>')}: groupId is required for leakage-safe split")
    return group.strip()


def _split(group: str, seed: int, validation_percent: int, test_percent: int,
           forced_test: set[str]) -> str:
    if group in forced_test:
        return "test"
    if validation_percent < 0 or test_percent < 0 or validation_percent + test_percent >= 100:
        raise DatasetError("validation/test percentages must be non-negative and sum to less than 100")
    bucket = int.from_bytes(hashlib.sha256(f"{seed}:{group}".encode()).digest()[:4], "big") % 100
    if bucket < test_percent:
        return "test"
    if bucket < test_percent + validation_percent:
        return "validation"
    return "train"


def prepare(source_paths: list[Path], decisions_path: Path, output_dir: Path, *, artifact_path: Path, seed: int = 17,
            validation_percent: int = 15, test_percent: int = 15,
            forced_test_groups: set[str] | None = None, review_policy: str = "human") -> dict[str, Any]:
    if review_policy not in ("human", "delegated-ai"):
        raise DatasetError("unsupported review policy")
    if validation_percent <= 0 or test_percent < 0 or validation_percent + test_percent >= 100:
        raise DatasetError("invalid validation/test percentages")
    artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
    if not isinstance(artifact, dict) or artifact.get("sha256") != digest({k: v for k, v in artifact.items() if k != "sha256"}):
        raise DatasetError("artifact hash mismatch")
    if artifact.get("evaluationOnly") is True:
        raise DatasetError("shadow artifacts are not allowed in P0")
    decisions = read_decisions(decisions_path)
    source_rows = [row for path in source_paths for row in read_jsonl(path)]
    if not source_rows:
        raise DatasetError("no source records")
    errors: list[str] = []
    approved: list[dict[str, Any]] = []
    rejected_ids: list[str] = []
    rejected_groups: set[str] = set()
    seen_ids: set[str] = set()
    for row in source_rows:
        key = row.get("id", "<unknown>")
        try:
            if not isinstance(key, str) or key in seen_ids:
                raise DatasetError("duplicate or missing id")
            seen_ids.add(key)
            decision = _review(row, decisions)
            if decision in APPROVED and decisions[key]["reviewer"].startswith("AI:") and review_policy != "delegated-ai":
                raise DatasetError("AI approval requires explicit delegated-ai review policy")
            if decision in REJECTED:
                rejected_ids.append(key)
                rejected_groups.add(_group(row))
                continue
            _contract(row, artifact)
            _group(row)
            if _contains_secret(row):
                raise DatasetError("possible credential or personal secret in messages")
            approved.append(row)
        except DatasetError as exc:
            errors.append(f"{key}: {exc}")
    if errors:
        raise DatasetError("dataset is not training-ready:\n" + "\n".join(errors[:25]))
    if not approved:
        raise DatasetError("no approved records remain after human review")

    group_keys: dict[tuple[str, str, str], str] = {}
    for row in approved:
        group_key = (row["scenario"], row["branchId"], row["level"])
        previous = group_keys.setdefault(group_key, _group(row))
        if previous != _group(row):
            raise DatasetError("same scenario/branch/level has multiple groupId values")
    dependent_ids = [row["id"] for row in approved if _group(row) in rejected_groups]
    approved = [row for row in approved if _group(row) not in rejected_groups]
    unique: list[dict[str, Any]] = []
    duplicate_ids: list[str] = []
    seen_inputs: dict[str, tuple[str, str]] = {}
    seen_outputs: dict[tuple[str, str], str] = {}
    for row in sorted(approved, key=lambda r: r["id"]):
        purpose, target_index, target = _target(row)
        output_key = (purpose, _normalize_text(target.get("utteranceEn", "") or canonical(target)))
        input_key = row["inputHash"]
        group = _group(row)
        if input_key in seen_inputs and seen_inputs[input_key][1] != row["outputHash"]:
            raise DatasetError("conflicting targets for the same input")
        if (input_key in seen_inputs and seen_inputs[input_key][0] != group
                or output_key in seen_outputs and seen_outputs[output_key] != group):
            raise DatasetError("duplicate across groups; adjudicate groups before splitting")
        if input_key in seen_inputs or output_key in seen_outputs:
            duplicate_ids.append(row["id"])
            continue
        seen_inputs[input_key] = (group, row["outputHash"])
        seen_outputs[output_key] = group
        unique.append({
            "id": row["id"],
            "groupId": group,
            "scenario": row["scenario"],
            "branchId": row["branchId"],
            "purpose": purpose,
            "level": row.get("level"),
            "turnNumber": row.get("turnNumber"),
            "source": row.get("source", "unknown"),
            "inputHash": row["inputHash"],
            "outputHash": digest(target),
            "artifactVersion": row["artifactVersion"],
            "promptHash": row["promptHash"],
            "schemaHash": row["schemaHash"],
            "reviewStatus": "approved",
            "reviewerReference": decisions[row["id"]]["reviewer"],
            "reviewerType": "ai" if decisions[row["id"]]["reviewer"].startswith("AI:") else "human",
            "reviewVersion": row["reviewVersion"],
            "semanticReviewReference": row["semanticReviewReference"],
            "targetMessageIndex": target_index,
            "lossMaskPolicy": "last-assistant-only",
            "messages": row["messages"],
        })

    forced = forced_test_groups or set()
    splits: dict[str, list[dict[str, Any]]] = {"train": [], "validation": [], "test": []}
    for row in unique:
        splits[_split(row["groupId"], seed, validation_percent, test_percent, forced)].append(row)
    if not splits["train"] or not splits["validation"] or not splits["test"]:
        raise DatasetError("group split produced an empty split; add approved groups or adjust percentages")
    groups_by_split = {name: {row["groupId"] for row in rows} for name, rows in splits.items()}
    if groups_by_split["train"] & groups_by_split["validation"] or groups_by_split["train"] & groups_by_split["test"] \
            or groups_by_split["validation"] & groups_by_split["test"]:
        raise DatasetError("group leakage detected between splits")
    if output_dir.exists():
        raise DatasetError(f"refusing to overwrite existing output directory: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=False)
    for name, rows in splits.items():
        (output_dir / f"{name}.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
            encoding="utf-8", newline="\n")
    manifest = {
        "manifestVersion": 1,
        "datasetReady": True,
        "trainingReady": False,
        "pendingGates": ["P0 coverage and sample count approval", "near-duplicate and holdout audit",
                         "GPU dependency lock and checkpoint preflight", "last-assistant loss mask check"],
        "reviewPolicy": "every retained row approved with reviewer reference",
        "reviewAuthority": review_policy,
        "humanApproved": all(row["reviewerType"] == "human" for row in unique),
        "splitPolicy": {"seed": seed, "validationPercent": validation_percent,
                        "testPercent": test_percent, "by": "groupId", "forcedTestGroups": sorted(forced)},
        "contract": {"artifactVersion": artifact["version"], "artifactHash": artifact["sha256"],
                     "validatorHash": validator_hash(),
                     "promptHashes": sorted({r["promptHash"] for r in unique}),
                     "schemaHashes": sorted({r["schemaHash"] for r in unique})},
        "sources": [{"sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in source_paths],
        "reviewFileHash": hashlib.sha256(decisions_path.read_bytes()).hexdigest(),
        "counts": {name: len(rows) for name, rows in splits.items()},
        "coverage": {name: {field: {str(value): sum(r[field] == value for r in rows)
                                     for value in sorted({r[field] for r in rows}, key=str)}
                            for field in ("level", "turnNumber", "purpose", "scenario")}
                     for name, rows in splits.items()},
        "totalApprovedBeforeDedup": len(approved),
        "rejectedIds": rejected_ids,
        "dependentGroupsExcluded": dependent_ids,
        "duplicatesExcluded": duplicate_ids,
        "groups": {name: sorted(groups) for name, groups in groups_by_split.items()},
        "datasetHash": digest({name: splits[name] for name in ("train", "validation", "test")}),
    }
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8", newline="\n")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, action="append", required=True,
                        help="approved-export source JSONL; may be supplied more than once")
    parser.add_argument("--decisions", type=Path, required=True,
                        help="explicit review decisions CSV")
    parser.add_argument("--review-policy", choices=("human", "delegated-ai"), default="human")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True, help="frozen production prompt artifact")
    parser.add_argument("--seed", type=int, default=17)
    parser.add_argument("--validation-percent", type=int, default=15)
    parser.add_argument("--test-percent", type=int, default=15)
    parser.add_argument("--holdout-group-file", type=Path, required=True,
                        help="one groupId per line; these groups always go to test")
    args = parser.parse_args(argv)
    try:
        forced = set()
        if args.holdout_group_file:
            forced = {line.strip() for line in args.holdout_group_file.read_text(encoding="utf-8").splitlines()
                      if line.strip() and not line.lstrip().startswith("#")}
        if not forced:
            raise DatasetError("holdout group file must list the canonical benchmark and adversarial groups")
        manifest = prepare(args.source, args.decisions, args.output, artifact_path=args.artifact, seed=args.seed,
                           validation_percent=args.validation_percent, test_percent=args.test_percent,
                           forced_test_groups=forced, review_policy=args.review_policy)
    except (OSError, ValueError) as exc:
        print(f"prepare_dataset: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"output": str(args.output), "counts": manifest["counts"],
                      "datasetHash": manifest["datasetHash"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
