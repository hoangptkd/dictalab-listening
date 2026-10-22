"""Freeze the exported Java v8 contract without changing a previous artifact."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

from jsonschema import Draft202012Validator

from training.generate_teacher_data import ROOT, read_json, write_json
from training.prepare_dataset import digest
from training.semantic_rubric import VERSION, RULES, calibration_cases


def freeze(contract_path: Path, output: Path) -> dict:
    baseline = read_json(ROOT / "artifacts/prompts/conversation-cefr-b1-v7-20261021.json")
    contract = read_json(contract_path)
    for example in contract["fewShotExamples"]:
        level = example["input"]["difficulty"]
        Draft202012Validator(contract["contentSchemas"][level]).validate(example["output"])
        assert len(example["output"]["facts"]) == (2 if level == "A1" else 3)
        assert all("taskType" in fact for fact in example["output"]["facts"])
    dataset_hash = digest(calibration_cases())
    artifact = {**contract, "version": "conversation-cefr-v8-20261022",
        "approvalStatus": "development", "createdAt": datetime.now(timezone.utc).isoformat(),
        "baselineArtifactHash": baseline["sha256"], "datasetHash": dataset_hash,
        "datasetKind": "development-calibration-only; not a measured benchmark",
        "semanticRubric": {"version": VERSION, "hash": digest(RULES), "calibrationHash": dataset_hash},
        "model": baseline["model"], "modelNote": "Reference contract only; v8 has no approved model evaluation.",
        "trainingTarget": "Qwen/Qwen3.5-4B",
        "generationConfig": baseline["generationConfig"], "contentSystem": baseline["contentSystem"].replace(
            "For B1 include fact.taskType", "For every level include fact.taskType"),
        "clientAliases": ["CONVERSATION_GENERATION_V8_SEMANTIC_GROUNDING"],
        "metricReport": {"datasetHash": dataset_hash, "status": "not_evaluated",
            "gate": {"releaseAllowed": False, "reasons": ["candidate_requires_blind_benchmark_and_chain_review"]}}}
    artifact["sha256"] = digest(artifact)
    write_json(output, artifact)
    return artifact


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=ROOT / "target/conversation-cefr-java-contract.json")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/prompts/conversation-cefr-v8-20261022.json")
    args = parser.parse_args()
    print(json.dumps({"version": freeze(args.contract, args.output)["version"]}))
