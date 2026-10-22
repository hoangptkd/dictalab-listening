import json
from types import SimpleNamespace

import pytest

from training import benchmark_teacher as bench
from training.tests.test_generate_teacher_data import _catalogue
from training.semantic_rubric import CHECKS


def frozen(tmp_path, monkeypatch, used=16):
    catalogue = tmp_path / "catalogue.json"
    groups = _catalogue()
    for group in groups:
        group["input"] = {"history": [], "scenario": group["scenarioId"]}
    catalogue.write_text(json.dumps({"catalogue": groups}), encoding="utf-8")
    monkeypatch.setattr(bench.gen, "CATALOGUE", catalogue)
    source = SimpleNamespace(id="test-account", effective_rpm=13, rpd_limit=100, api_key="test-only")
    settings = SimpleNamespace(sources_for=lambda _: [source], active_cloud_model="test-model",
        enabled_cloud_sources=[source],
        registry_pools={"deepseek": {"global_rpm_limit": 13, "global_rpd_limit": 100}})
    monkeypatch.setattr(bench, "quota_store", lambda _: SimpleNamespace(execute=lambda *a, **k: [0, 0, used, 0, used, 0]))
    output = tmp_path / "benchmark"
    return output, settings


def test_plan_freezes_identical_contexts_and_refuses_quota_overrun(tmp_path, monkeypatch):
    output, settings = frozen(tmp_path, monkeypatch)
    plan = bench.prepare(output, settings)
    assert len(plan["entries"]) == 60 and len(plan["chains"]) == 6 and plan["httpCap"] == 78
    for level in ("A1", "A2", "B1"):
        for arm in (0, 1):
            assert sum(e["arm"] == arm and e["group"]["level"] == level for e in plan["entries"]) == 10
    paired = {}
    for entry in plan["entries"]:
        key = entry["group"]["groupId"], entry["context"]["turnNumber"]
        paired.setdefault(key, []).append(entry["context"])
    assert all(a == b for a, b in paired.values())
    assert bench.load_plan(output) == plan
    with pytest.raises(ValueError, match="overwrite/replan"):
        bench.prepare(output, settings)
    other = tmp_path / "other"; other.mkdir()
    output, settings = frozen(other, monkeypatch, used=23)
    with pytest.raises(ValueError, match="insufficient remaining quota"):
        bench.prepare(output, settings)
    assert not output.exists()


def test_review_is_bound_to_task_context_and_identity():
    row = {"id": "blind-1", "context": {"turnNumber": 2}, "task": {"utteranceEn": "test"}, "hardPass": True}
    review = {"reviewer": "AI:Codex", "taskHash": bench.gen.digest(row["task"]),
              "contextHash": bench.gen.digest(row["context"]), "checks": dict.fromkeys(CHECKS, True), "issues": []}
    assert bench.reviewed(row, {row["id"]: review}) == "PASS"
    for key, value in (("reviewer", "human"), ("taskHash", "wrong"), ("contextHash", "wrong")):
        assert bench.reviewed(row, {row["id"]: {**review, key: value}}) == "REVIEW_REQUIRED"
    review["checks"]["taskTypeAccurate"] = None
    assert bench.reviewed(row, {row["id"]: review}) == "REVIEW_REQUIRED"


@pytest.mark.asyncio
async def test_interrupted_attempt_never_replays_and_unreviewed_chains_stop(tmp_path, monkeypatch):
    output, settings = frozen(tmp_path, monkeypatch)
    plan = bench.prepare(output, settings)
    first = plan["entries"][0]
    (output / (first["id"] + ".request.json")).write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="refusing replay"):
        await bench.generate(output, "ab", settings)
    for index in range(6):
        row = {"id": f"chain-{index+1}-t2", "hardPass": True, "context": {}, "task": {}}
        bench.gen.write_json(output / (row["id"] + ".candidate.json"), row)
    await bench.generate(output, "chain", settings)
    assert not list(output.glob("*t3.request.json"))
    summary = bench.report(output)
    assert not summary["candidateGatePassed"] and not summary["trainingReady"]
    assert summary["arms"][1]["planned"] == 30
