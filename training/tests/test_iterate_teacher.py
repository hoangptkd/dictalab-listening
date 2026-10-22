import copy
from pathlib import Path
from types import SimpleNamespace

import pytest

from training import iterate_teacher as run
from training.semantic_rubric import CHECKS, VERSION_V3


def _items(per_level=(10, 10, 10)):
    items = []
    for level, passing in zip(run.LEVELS, per_level):
        for index in range(10):
            items.append({"level": level, "hardPass": True, "decision": "PASS" if index < passing else "FAIL",
                          "severity": "none", "unresolvedCriticalRisk": False,
                          "attempt": 1, "developmentOnly": True})
    return items


def _chains(passing=5):
    return [[{"hardPass": True, "decision": "PASS" if group < passing else "FAIL",
              "severity": "none", "unresolvedCriticalRisk": False,
              "attempt": 1, "developmentOnly": True} for _ in range(3)] for group in range(6)]


def _gold():
    cases = []
    labels = [("PASS", "none")] * 12 + [("FAIL", "major")] * 12 + [
        ("FAIL", "critical")] * 8 + [("REVIEW_REQUIRED", "none")] * 4
    for index, (label, severity) in enumerate(labels):
        cases.append({"id": f"probe-{index}", "sourceId": f"source-{index // 3}",
                      "expected": label, "expectedSeverity": severity,
                      "rubricVersion": VERSION_V3, "hardPass": True,
                      "context": {"difficulty": "A1"}, "task": {"utteranceEn": "fixture"}})
    return cases


def test_acceptance_is_strict_about_total_levels_critical_and_chain():
    gate = lambda items, chains: run.acceptance_gate(items, chains, calibrated=True,
        coverage_valid=True, provenance_valid=True)["accepted"]
    assert not gate(_items((9, 9, 9)), _chains())
    assert not gate(_items((10, 10, 8)), _chains())
    assert gate(_items((10, 9, 9)), _chains())
    assert not gate(_items((10, 9, 9)), _chains(4))
    rows = _items()
    rows[0]["severity"] = "critical"
    assert not gate(rows, _chains())
    rows[0]["severity"] = "none"
    rows[0]["unresolvedCriticalRisk"] = None
    assert not gate(rows, _chains())
    assert not run.acceptance_gate(_items(), _chains(), calibrated=False,
        coverage_valid=True, provenance_valid=True)["accepted"]
    chains = _chains()
    chains[-1][0]["unresolvedCriticalRisk"] = True
    assert not gate(_items(), chains)


def test_gold_distribution_and_two_independent_calibration_runs(monkeypatch):
    monkeypatch.setattr(run._validation, "validate_task", lambda *a, **k: [])
    cases = _gold()
    assert run.audit_gold(cases)["sources"] == 12
    manifest = {"judge": {"model": "gemini-test"}, "rubricVersion": VERSION_V3,
                "rubricHash": "frozen"}
    verdicts = {}
    for row in cases:
        checks = dict.fromkeys(CHECKS, True)
        if row["expected"] == "FAIL":
            checks["rolesCorrect" if row["expectedSeverity"] == "critical" else "questionFactsAligned"] = False
        elif row["expected"] == "REVIEW_REQUIRED":
            checks["historyConsistent"] = None
        verdicts[row["id"]] = {"reviewer": "AI:gemini-test", "rubricVersion": VERSION_V3,
            "rubricHash": "frozen", "taskHash": run.gen.digest(row["task"]),
            "contextHash": run.gen.digest(row["context"]), "checks": checks,
            "requestHash": run.gen.digest(["first", row["id"]]),
            "responseHash": run.gen.digest(["response", row["id"]]),
            "issues": ["evidence and rule"] if row["expected"] != "PASS" else []}
    first = {"runId": "first", "order": list(verdicts), "reviews": verdicts}
    second_reviews = copy.deepcopy(verdicts)
    for row in cases:
        second_reviews[row["id"]]["requestHash"] = run.gen.digest(["second", row["id"]])
    second = {"runId": "second", "order": list(reversed(verdicts)), "reviews": second_reviews}
    assert run.calibration_gate(cases, [first, second], manifest)
    assert not run.calibration_gate(cases, [first, first], manifest)
    wrong = copy.deepcopy(second)
    wrong["reviews"][cases[24]["id"]]["checks"] = dict.fromkeys(CHECKS, True)
    wrong["reviews"][cases[24]["id"]]["issues"] = []
    assert not run.calibration_gate(cases, [first, wrong], manifest)
    wrong = copy.deepcopy(second)
    wrong["reviews"][cases[-1]["id"]]["checks"]["historyConsistent"] = False
    assert not run.calibration_gate(cases, [first, wrong], manifest)
    malformed = copy.deepcopy(second)
    malformed["reviews"][cases[-1]["id"]]["checks"].pop("historyConsistent")
    assert not run.calibration_gate(cases, [first, malformed], manifest)
    with pytest.raises(ValueError, match="36 unique"):
        run.audit_gold(cases[:-1])
    with pytest.raises(ValueError, match="independent source"):
        run.audit_gold([{**row, "sourceId": "one"} for row in cases])
    monkeypatch.setattr(run._validation, "validate_task", lambda *a, **k: ["bad"])
    with pytest.raises(ValueError, match="production-invalid"):
        run.audit_gold(cases)


def test_judge_identity_hashes_and_null_are_fail_closed():
    manifest = {"judge": {"model": "gemini-test"}, "rubricVersion": VERSION_V3,
                "rubricHash": "frozen"}
    row = {"hardPass": True, "context": {"difficulty": "A1"}, "task": {"utteranceEn": "hello"}}
    review = {"reviewer": "AI:gemini-test", "rubricVersion": VERSION_V3,
              "rubricHash": "frozen", "taskHash": run.gen.digest(row["task"]),
              "contextHash": run.gen.digest(row["context"]),
              "requestHash": run.gen.digest("request"), "responseHash": run.gen.digest("response"),
              "checks": dict.fromkeys(CHECKS, True), "issues": []}
    assert run.checked_review(row, review, manifest, calibrated=True) == "PASS"
    assert run.checked_review(row, review, manifest, calibrated=False) == "REVIEW_REQUIRED"
    for field, value in (("reviewer", "AI:Codex"), ("taskHash", "wrong"),
                         ("rubricHash", "wrong")):
        assert run.checked_review(row, {**review, field: value}, manifest,
                                  calibrated=True) == "REVIEW_REQUIRED"
    uncertain = copy.deepcopy(review)
    uncertain["checks"]["rolesCorrect"] = None
    assert run.checked_review(row, uncertain, manifest, calibrated=True) == "REVIEW_REQUIRED"
    malformed = copy.deepcopy(review)
    malformed["checks"]["rolesCorrect"] = 1
    assert run.checked_review(row, malformed, manifest, calibrated=True) == "REVIEW_REQUIRED"


def test_prepare_freezes_private_campaign_without_credentials(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run, "_plan_groups", lambda: {"development": [], "unseenCapacityByLevel": {}})
    source_plans = (tmp_path / "old-one.json", tmp_path / "old-two.json")
    for source in source_plans:
        run.gen.write_json(source, {"entries": [], "chains": []})
    monkeypatch.setattr(run, "SOURCE_PLANS", source_plans)
    artifact_path = tmp_path / "candidate.json"
    artifact = {"approvalStatus": "development", "version": "test"}
    artifact["sha256"] = run.gen.digest(artifact)
    run.gen.write_json(artifact_path, artifact)
    sources = {name: [SimpleNamespace(id=name + "-a", model=model, api_key="private-key")]
               for name, model in (("deepseek", "deepseek-test"), ("gemini", "gemini-test"))}
    settings = SimpleNamespace(sources_for=lambda name: sources[name], registry_pools={
        "deepseek": {"default_model": "deepseek-test"},
        "gemini": {"default_model": "gemini-test"}})
    campaign = tmp_path / "campaign"
    manifest = run.prepare(campaign, artifact_path=artifact_path, settings=settings)
    assert manifest["policy"] == run.POLICY
    assert run.load(campaign)[1]["status"] == "JUDGE_BLOCKED"
    assert "private-key" not in (campaign / "campaign.json").read_text(encoding="utf-8")
    with pytest.raises(ValueError, match="overwrite"):
        run.prepare(campaign, artifact_path=artifact_path, settings=settings)
    with pytest.raises(ValueError, match="private"):
        run._private_path(Path(tmp_path.parent / "elsewhere"))


def test_development_selection_excludes_old_sources_and_hides_final(tmp_path, monkeypatch):
    plans = (tmp_path / "v8.json", tmp_path / "v9.json")
    run.gen.write_json(plans[0], {"entries": [{"group": {"groupId": "A1-0"}}], "chains": []})
    run.gen.write_json(plans[1], {"entries": [], "chains": [{"groupId": "B1-0"}]})
    monkeypatch.setattr(run, "SOURCE_PLANS", plans)
    groups = [{"level": level, "groupId": f"{level}-{index}"}
              for level in run.LEVELS for index in range(17)]
    monkeypatch.setattr(run.gen, "plan_groups", lambda _: groups)
    selection = run._plan_groups()
    assert set(selection) == {"development", "unseenCapacityByLevel"}
    assert selection["unseenCapacityByLevel"] == {"A1": 12, "A2": 13, "B1": 12}
    assert len(selection["development"]) == 12
    assert not {"A1-0", "B1-0"}.intersection(row["groupId"] for row in selection["development"])
