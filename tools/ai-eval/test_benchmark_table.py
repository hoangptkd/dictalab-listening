"""Deterministic table: explicit denominators, sidecar word counts, no inferred review."""
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.registry import load_artifact
from benchmark_table import build_table


def _task(words, *, valid=True):
    utterance = " ".join(f"word{i}" for i in range(words))
    facts = [{"evidenceEn": "word0", "textVi": "một"}, {"evidenceEn": "word1", "textVi": "hai"}]
    return {"utteranceEn": utterance, "facts": facts,
            "slots": [{"kind": "detail", "askVi": "Gì?"}, {"kind": "detail", "askVi": "Ở đâu?"}],
            "questionVi": "Nghe và chọn.", "taskVi": "Chọn đáp án.", "skill": "details",
            "translationVi": "Bản dịch."}


def _row(key, attempt, phase="validate", *, status="valid", words=None, errors=()):
    return {"requestId": key, "attempt": attempt, "phase": phase, "status": status,
            "wordCount": words, "validationErrors": list(errors), "modelCalls": {"generate_content": 1.0}}


@pytest.fixture
def inputs():
    artifact = load_artifact(Settings())
    dataset = {"seeds": [17, 43], "cases": [
        {"id": "a1", "context": {"difficulty": "A1", "turnNumber": 2}},
        {"id": "b1", "context": {"difficulty": "B1", "turnNumber": 2}}]}
    capture = {"datasetHash": "x", "metadata": {},
               "records": [{"id": "a1", "seed": 17, "task": _task(10), "transport": "ok", "seconds": 10},
                           {"id": "a1", "seed": 43, "task": None, "transport": "timeout", "seconds": 600},
                           {"id": "b1", "seed": 17, "task": _task(40), "transport": "ok", "seconds": 30}]}
    attempts = [_row("a1:17", 1, words=10),
                _row("a1:43", 1, phase="generate_content", status="TimeoutError"),
                _row("b1:17", 1, status="invalid", words=26,
                     errors=["utteranceEn:word_count=26,min=30,max=90"]),
                _row("b1:17", 2, words=40)]
    return dataset, capture, attempts, artifact


def test_denominators_and_sidecar_evidence_are_explicit(inputs):
    table = build_table(inputs[0], inputs[1], inputs[2], inputs[3], shadow=False)
    assert table["recordsExpected"] == 4 and table["recordsCaptured"] == 3
    assert table["missingKeys"] == ["b1:43"]
    a1 = table["levels"]["A1"]
    assert a1["expected"] == 2 and a1["captured"] == 2
    assert a1["firstAttemptMeasured"] == 1
    assert a1["firstAttemptLengthValid"] == 0.5          # timeout is not a pass
    assert a1["firstAttemptLengthValidWhenMeasured"] == 1.0
    assert a1["finalValid"] == 0.5
    assert a1["finalPresent"] == 1
    assert a1["finalLengthValidOnExpected"] == 0.5
    assert a1["finalLengthValidOnPresent"] == 1.0
    b1 = table["levels"]["B1"]
    assert b1["firstAttemptLengthValid"] == 0.0
    assert b1["repairAttempted"] == 1 and b1["repairSucceeded"] == 1 and b1["repairFailed"] == 0
    assert b1["finalValid"] == 0.5   # b1:43 was never generated
    assert b1["finalLengthValidOnExpected"] == 0.5
    assert table["levels"]["A1"]["semanticReview"] == "PENDING_REVIEW"


def test_duplicate_or_unexpected_capture_records_are_rejected(inputs):
    dataset, capture, attempts, artifact = inputs
    capture["records"].append(dict(capture["records"][0]))
    with pytest.raises(ValueError, match="duplicate"):
        build_table(dataset, capture, attempts, artifact, shadow=False)
    capture["records"] = capture["records"][:3] + [{"id": "ghost", "seed": 17, "transport": "ok"}]
    with pytest.raises(ValueError, match="unexpected"):
        build_table(dataset, capture, attempts, artifact, shadow=False)


def test_shadow_mode_checks_expected_task_types(inputs):
    dataset, capture, attempts, artifact = inputs
    dataset["cases"][0]["expectedTaskTypes"] = ["gist"]
    task = _task(40)
    task["facts"][0]["taskType"] = "direct_detail"
    capture["records"][0]["task"] = task
    table = build_table(dataset, capture, attempts, artifact, shadow=True)
    assert table["levels"]["A1"]["taskTypeCoverage"] == {"gist": 0}
    assert table["rows"][0]["final"]["taskTypeMatched"] is False
