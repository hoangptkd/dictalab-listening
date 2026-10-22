import json
from types import SimpleNamespace

import pytest

from training import benchmark_teacher as bench
from training.tests.test_generate_teacher_data import _catalogue
from training.semantic_rubric import CHECKS, VERSION, VERSION_V3, rules_for, boundary_cases_v3


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
    assert bench.reviewed(row, {row["id"]: {**review, "rubricVersion": VERSION}}, VERSION_V3) == "REVIEW_REQUIRED"
    v3 = {**review, "rubricVersion": VERSION_V3, "rubricHash": bench.gen.digest(rules_for(VERSION_V3))}
    assert bench.reviewed(row, {row["id"]: v3}, VERSION_V3) == "PASS"
    assert bench.reviewed(row, {row["id"]: {**v3, "rubricHash": "changed"}}, VERSION_V3) == "REVIEW_REQUIRED"
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


def test_pilot_reserves_twelve_calls_and_never_approves_release(tmp_path, monkeypatch):
    output, settings = frozen(tmp_path, monkeypatch, used=84)
    plan = bench.prepare(output, settings, pilot=True, rubric_version=VERSION_V3)
    assert plan["httpCap"] == len(plan["entries"]) == 12 and not plan["chains"]
    assert plan["rubricHash"] == bench.gen.digest(rules_for(VERSION_V3))
    for level in ("A1", "A2", "B1"):
        for arm in (0, 1):
            assert sum(e["arm"] == arm and e["group"]["level"] == level for e in plan["entries"]) == 2
    reviews = {}
    for entry in plan["entries"]:
        row = {**entry, "hardPass": True, "task": {"utteranceEn": "test"}}
        bench.gen.write_json(output / (entry["id"] + ".candidate.json"), row)
        reviews[entry["id"]] = {"reviewer": "AI:Codex", "rubricVersion": VERSION_V3,
            "rubricHash": bench.gen.digest(rules_for(VERSION_V3)),
            "taskHash": bench.gen.digest(row["task"]), "contextHash": bench.gen.digest(row["context"]),
            "checks": dict.fromkeys(CHECKS, True), "issues": []}
    bench.gen.write_json(output / "reviews.json", reviews)
    summary = bench.report(output)
    assert all(arm["semanticPass"] == 6 and not arm["developmentGatePassed"] for arm in summary["arms"])
    assert all(arm["semanticDecisions"] == {"PASS": 6} for arm in summary["arms"])
    assert not summary["candidateGatePassed"] and not summary["trainingReady"]
    assert not list(output.glob("chain-export-*.jsonl"))
    bench.blind_packet(output)
    packet = bench.gen.read_json(output / "review-packet-01.json")
    assert packet["rubricVersion"] == VERSION_V3
    assert all("arm" not in item for item in packet["items"])


def test_pilot_still_respects_remaining_daily_cap(tmp_path, monkeypatch):
    output, settings = frozen(tmp_path, monkeypatch, used=89)
    with pytest.raises(ValueError, match="bounded 12-call"):
        bench.prepare(output, settings, pilot=True, rubric_version=VERSION_V3)
    assert not output.exists()


def test_v9_freeze_changes_only_instruction_and_development_metadata(tmp_path):
    from training.freeze_candidate import freeze_v9
    baseline = bench.gen.read_json(bench.gen.ARTIFACT)
    path = tmp_path / "v9.json"
    candidate = freeze_v9(path)
    assert candidate["sha256"] == bench.gen.digest({k: v for k, v in candidate.items() if k != "sha256"})
    for key in ("contentSystem", "fewShotExamples", "contentSchemas", "contentSchema", "translationSchema",
                "translationSystem", "levelProfiles", "generationConfig"):
        assert candidate[key] == baseline[key]
    assert len(candidate["instruction"]) < len(baseline["instruction"]) * 0.6
    assert candidate["semanticRubric"]["version"] == VERSION_V3
    assert candidate["approvalStatus"] == "development"
    assert not candidate["metricReport"]["gate"]["releaseAllowed"]
    with pytest.raises(FileExistsError):
        freeze_v9(path)


def test_v3_probes_and_v2_rules_are_separate_and_reproducible():
    from training.semantic_rubric import RULES
    assert bench.gen.digest(RULES) == "c35071ed9d548fb4fb0711aa6950ec1ce4ce5a23295ef79541742137e4820ba6"
    cases = boundary_cases_v3()
    assert len(cases) == len({r["id"] for r in cases}) == 12
    assert all(r["semanticProbeOnly"] for r in cases)
    assert sum(r["expected"] == "PASS" for r in cases) == 6
    assert all(r["expectedCheck"] in CHECKS for r in cases if r["expected"] == "FAIL")
    with pytest.raises(ValueError, match="unknown semantic rubric"):
        rules_for("invented")
