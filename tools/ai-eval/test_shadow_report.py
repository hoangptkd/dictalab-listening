"""Synthetic report checks; these labels are not model or CEFR quality evidence."""
import copy
from unittest.mock import patch

import pytest

from generate import capture as generate_capture
from app.config import Settings
from app.registry import load_artifact, artifact_hash
from app.shadow import ARTIFACT_VERSION
from metrics import FLAGS
from report import digest
from shadow_report import build_shadow_report


@pytest.fixture
def inputs():
    artifact = load_artifact(Settings(artifact_version=ARTIFACT_VERSION))
    examples = [artifact["fewShotExamples"][0], artifact["fewShotExamples"][2]]
    dataset = {"seeds": [17, 43, 71], "cases": [
        {"id": e["input"]["difficulty"], "context": e["input"], "expectedTaskTypes": [e["output"]["facts"][0]["taskType"]]}
        for e in examples]}
    artifact["datasetHash"] = digest(dataset)
    artifact["sha256"] = artifact_hash(artifact)
    records = []
    for example in examples:
        task = copy.deepcopy(example["output"])
        task["translationVi"] = "Bản dịch để kiểm tra báo cáo."
        for i, fact in enumerate(task["facts"]):
            del fact["textEn"]
            fact["textVi"] = "Kết luận" if i == 0 else "Tài liệu"
        verdict = {flag: True for fields in FLAGS.values() for flag in fields}
        verdict.update({flag: True for flag in artifact["reviewRubric"]})
        for seed in dataset["seeds"]:
            records.append({"id": example["input"]["difficulty"], "seed": seed, "task": copy.deepcopy(task),
                            "transport": "ok", "verdict": dict(verdict),
                            "manualReview": {"status": "approved", "reference": "synthetic-test-only"}})
    capture = {"datasetHash": digest(dataset), "metadata": {"model": artifact["model"]["name"],
        "modelDigest": artifact["model"]["digest"], "promptVersion": artifact["version"],
        "promptHash": artifact["sha256"], "config": artifact["generationConfig"], "evaluationOnly": True}, "records": records}
    return dataset, capture, artifact


def test_even_complete_synthetic_review_never_grants_release(inputs):
    report = build_shadow_report(*inputs)
    assert report["capturedRecords"] == report["expectedRecords"] == 6
    assert all(level["candidateGatePassed"] for level in report["levels"].values())
    assert report["gate"]["releaseAllowed"] is False


@pytest.mark.parametrize("defect", ["missing", "invalid_verdict", "false_cefr", "no_manual", "ungrounded", "wrong_type", "timeout", "length"])
def test_incomplete_or_invalid_evidence_fails_closed(inputs, defect):
    dataset, capture, artifact = inputs
    record = capture["records"][0]
    if defect == "missing":
        capture["records"].pop(0)
    elif defect == "invalid_verdict":
        record["verdict"]["cefrLevelAppropriate"] = "true"
    elif defect == "false_cefr":
        record["verdict"]["cefrLevelAppropriate"] = False
    elif defect == "no_manual":
        for row in capture["records"]:
            row.pop("manualReview")
    elif defect == "ungrounded":
        record["task"]["facts"][0]["reasoningEvidence"]["segmentsEn"].append("Fabricated evidence.")
    elif defect == "wrong_type":
        record["task"]["facts"][0]["taskType"] = "direct_detail"
    elif defect == "timeout":
        record["transport"] = "timeout"
    else:
        record["task"]["utteranceEn"] += " padding" * 100
    report = build_shadow_report(dataset, capture, artifact)
    assert report["levels"]["B2"]["candidateGatePassed"] is False
    assert report["gate"]["releaseAllowed"] is False


@pytest.mark.parametrize("defect", ["dataset", "model", "config", "duplicate", "unexpected"])
def test_mismatched_or_duplicate_captures_are_rejected(inputs, defect):
    dataset, capture, artifact = inputs
    if defect == "dataset":
        capture["datasetHash"] = "wrong"
    elif defect == "model":
        capture["metadata"]["modelDigest"] = "0" * 64
    elif defect == "config":
        capture["metadata"]["config"] = {"temperature": 2}
    elif defect == "duplicate":
        capture["records"].append(copy.deepcopy(capture["records"][0]))
    else:
        capture["records"][0]["seed"] = 999
    with pytest.raises(ValueError):
        build_shadow_report(dataset, capture, artifact)


@pytest.mark.asyncio
@pytest.mark.parametrize("defect", ["cloud", "production", "dataset", "config"])
async def test_shadow_capture_rejects_unsafe_or_unpaired_runs_before_network(inputs, tmp_path, defect):
    dataset, _, artifact = inputs
    settings = Settings(artifact_version=ARTIFACT_VERSION)
    if defect == "cloud":
        settings = Settings(primary_provider="gemini")
    elif defect == "production":
        settings = Settings(production_mode=True, primary_provider="ollama", service_token="synthetic-test")
    elif defect == "dataset":
        dataset["seeds"].append(999)
    else:
        settings = Settings(temperature=2)
    with patch("generate.load_artifact", return_value=artifact), \
         patch("generate.httpx.AsyncClient", side_effect=AssertionError("must not call a provider")):
        with pytest.raises(ValueError):
            await generate_capture(dataset, settings, tmp_path / "capture.json", shadow=True)
    assert not (tmp_path / "capture.json").exists()
