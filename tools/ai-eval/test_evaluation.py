import copy
import json
from pathlib import Path

import pytest

from metrics import FLAGS, score
from report import build_report, compare
from run import replay
from generate import judge_input

FIXTURES = Path(__file__).resolve().parents[2] / "services/ai-generation/tests/fixtures/evaluation"
GOOD = {"utteranceEn": "Meet at nine in the lobby.", "questionVi": "When and where?", "taskVi": "Listen.",
        "skill": "details", "translationVi": "At nine in the lobby.",
        "facts": [{"textVi": "nine", "evidenceEn": "at nine"}, {"textVi": "lobby", "evidenceEn": "in the lobby"}]}
VERDICT = {flag: True for fields in FLAGS.values() for flag in fields}


@pytest.mark.parametrize("group", FLAGS)
def test_each_semantic_metric_can_fail_independently(group):
    verdict = {**VERDICT, FLAGS[group][0]: False}
    turn = 4 if group == "turn4_independence" else 2
    metrics = score(GOOD, {"turnNumber": turn}, verdict)
    assert metrics[group] is False
    assert score(GOOD, {"turnNumber": turn}, VERDICT)[group] is True


@pytest.mark.parametrize("bad,status", [("oops", "invalid_verdict"), ("timeout", "timeout"), (None, "missing"), ({"transfer": True}, "invalid_verdict")])
def test_invalid_judge_never_passes(bad, status):
    result = score(GOOD, {"turnNumber": 4}, bad)
    assert result["verdict_status"] == status
    assert result["translation"] is None


@pytest.mark.parametrize("bad", [None, "not json", {**GOOD, "facts": "wrong"}, {**GOOD, "utteranceEn": 123}, {**GOOD, "utteranceEn": "x" * 1201}])
def test_bad_schema(bad):
    assert score(bad, {"turnNumber": 2}, VERDICT)["schema"] is False


def test_evidence_and_transport_failures_are_separate():
    bad = {**GOOD, "utteranceEn": "Other utterance."}
    assert score(bad, {"turnNumber": 2}, VERDICT)["schema"] is True
    assert score(bad, {"turnNumber": 2}, VERDICT)["evidence"] is False
    assert score(GOOD, {"turnNumber": 2}, VERDICT, "timeout")["transport"] is False


def test_replay_45_cases_and_reproducible_reports():
    load = lambda name: json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    dataset = load("dataset.json")
    baseline = replay(dataset, load("r1-split-translation-v2.json"))
    assert baseline == replay(dataset, load("r1-split-translation-v2.json"))
    assert baseline["coverage"] == 45
    golden = json.loads((FIXTURES.parents[4] / "artifacts/reports/ai-platform-baseline.json").read_text(encoding="utf-8"))
    assert baseline["rows"] == golden["rows"]
    assert baseline["metrics"] == golden["metrics"]
    candidate = replay(dataset, load("r2-slot-contract-v2.json"))
    gate = compare(baseline, candidate)
    assert gate["releaseAllowed"] is False
    changed = copy.deepcopy(candidate)
    changed["datasetHash"] = "different"
    assert "dataset_hash_mismatch" in compare(baseline, changed)["reasons"]
    regressed = copy.deepcopy(baseline)
    regressed["metrics"]["translation"]["rate"] = 0
    assert "regression_translation" in compare(baseline, regressed)["reasons"]


def test_duplicate_or_missing_case_rejected():
    with pytest.raises(ValueError):
        build_report({"seeds": [1], "cases": [{"id": "case"}]}, [], {})


def test_judge_receives_turn_and_transfer_rules():
    assert "NEW independent encounter" in judge_input({"turnNumber": 4}, GOOD)["rules"]
    assert judge_input({"turnNumber": 2}, GOOD)["turnNumber"] == 2
    assert "same encounter" in judge_input({"turnNumber": 2}, GOOD)["rules"]


def test_complete_reviewed_comparison_can_pass_and_regression_blocks():
    dataset = {"seeds": [1], "cases": [{"id": str(i)} for i in range(10)]}
    rows = [{"key": f"{i}:1", "metrics": score(GOOD, {"turnNumber": 2 if i % 2 else 4}, VERDICT)} for i in range(10)]
    meta = {"model": "test", "modelDigest": "a"*64, "promptVersion": "v1", "promptHash": "b"*64,
            "config": {"temperature": 0.3}, "judgeVersion": "calibrated-test"}
    baseline = build_report(dataset, rows, meta)
    baseline["approvalStatus"] = "approved"
    for sample in baseline["reviewSample"]:
        sample.update(reviewer="ai", reviewReference="synthetic-only")
    candidate = copy.deepcopy(baseline)
    assert compare(baseline, candidate)["releaseAllowed"] is True
    candidate["metrics"]["translation"]["rate"] = 0.9
    assert compare(baseline, candidate)["reasons"] == ["regression_translation"]
