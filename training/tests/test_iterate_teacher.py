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
                      "expectedCheck": "rolesCorrect" if severity == "critical" else
                          "questionFactsAligned" if label == "FAIL" else
                          "historyConsistent" if label == "REVIEW_REQUIRED" else None,
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
            "issues": [{"check": key, "sourceQuote": "fixture", "explanation": "violates rule",
                        "severity": "critical" if key == "rolesCorrect" else "major"}
                       for key, value in checks.items() if value is not True]}
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
    assert run._fatal_calibration_error(cases[24], wrong["reviews"][cases[24]["id"]], "PASS")
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
    uncertain["issues"] = [{"check": "rolesCorrect", "sourceQuote": "hello",
                            "explanation": "ambiguous", "severity": "uncertain"}]
    assert run.checked_review(row, uncertain, manifest, calibrated=True) == "REVIEW_REQUIRED"
    malformed = copy.deepcopy(review)
    malformed["checks"]["rolesCorrect"] = 1
    assert run.checked_review(row, malformed, manifest, calibrated=True) == "REVIEW_REQUIRED"


def test_judge_payload_never_contains_gold_labels_or_candidate_identity():
    manifest = {"rubricVersion": VERSION_V3}
    case = {"id": "secret-case", "expected": "FAIL", "expectedSeverity": "critical",
            "sourceId": "source", "labelNote": "private label", "context": {"difficulty": "B1"},
            "task": {"utteranceEn": "hello"}}
    payload = run.judge_payload(case, "item-001", manifest)
    assert set(payload) == {"rubricVersion", "rubric", "items"}
    assert set(payload["items"][0]) == {"id", "context", "task"}
    assert "private label" not in str(payload["items"])
    assert "secret-case" not in str(payload["items"])


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
    groups = [{"level": level, "groupId": f"{level}-{index}", "input": {}}
              for level in run.LEVELS for index in range(17)]
    for ident in run.B1_DEVELOPMENT:
        groups.append({"level": "B1", "groupId": ident, "input": {}})
    monkeypatch.setattr(run.gen, "plan_groups", lambda _: groups)
    selection = run._plan_groups()
    assert set(selection) == {"development", "unseenCapacityByLevel"}
    assert selection["unseenCapacityByLevel"] == {"A1": 12, "A2": 13, "B1": 16}
    assert len(selection["development"]) == 12
    assert not {"A1-0", "B1-0"}.intersection(row["groupId"] for row in selection["development"])
    assert [row["groupId"] for row in selection["development"] if row["level"] == "B1"] == list(
        run.B1_DEVELOPMENT)
    assert selection["development"][10]["input"]["variationSeed"] == 25


def test_real_development_b1_covers_gist_opinion_and_inference():
    artifact = run._checked_artifact(run.DEFAULT_ARTIFACT)
    groups = [row for row in run._plan_groups()["development"] if row["level"] == "B1"]
    focuses = {kind for group in groups for turn in (2, 4) for kind in (
        "gist", "opinion_attitude", "inference_prediction") if
        f"genuine {kind} target" in run.gen.make_context(group, turn, [], artifact, 0)["practiceFocus"]}
    assert focuses == {"gist", "opinion_attitude", "inference_prediction"}


def test_quota_reads_each_provider_store_without_mixing_counters(monkeypatch):
    sources = {"deepseek": [SimpleNamespace(id="d", rpd_limit=100)],
               "gemini": [SimpleNamespace(id="g1", rpd_limit=500),
                          SimpleNamespace(id="g2", rpd_limit=500)]}
    settings = SimpleNamespace(sources_for=lambda name: sources[name], registry_pools={
        "deepseek": {"global_rpd_limit": 100}, "gemini": {"global_rpd_limit": 1350}})
    requested = []
    def store(_, platform):
        requested.append(platform)
        used = {"d": (96, 96), "g1": (23, 106), "g2": (23, 106)}
        return SimpleNamespace(execute=lambda source, op: [0, 0, *used[source.id][:1], 0,
                                                           used[source.id][1], 0])
    monkeypatch.setattr(run, "quota_store", store)
    snapshot = run.quota_snapshot(settings)
    assert requested == ["deepseek", "gemini"]
    assert snapshot["deepseek"]["availableCalls"] == 4
    assert snapshot["gemini"]["availableCalls"] == 954


@pytest.mark.asyncio
async def test_calibration_uses_72_distinct_first_pass_calls_and_caches(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run._validation, "validate_task", lambda *a, **k: [])
    cases = _gold()
    gold_path = tmp_path / "gold.json"
    run.gen.write_json(gold_path, cases)
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    run.gen.write_json(campaign / "state.json", {"status": "JUDGE_BLOCKED", "phase": "calibration"})
    manifest = {"goldPath": str(gold_path), "rubricVersion": VERSION_V3,
                "rubricHash": "frozen", "judge": {"model": "gemini-test"}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {"gemini": {"availableCalls": 72}})
    settings = SimpleNamespace(registry_pools={"gemini": {"default_model": "gemini-test"}})
    called = []
    async def fake_judge(path, contract, case, wire_id, stem, _settings):
        payload = run.judge_payload(case, wire_id, contract)
        assert set(payload["items"][0]) == {"id", "context", "task"}
        called.append(stem)
        checks = dict.fromkeys(CHECKS, True)
        if case["expected"] == "FAIL":
            checks["rolesCorrect" if case["expectedSeverity"] == "critical" else "questionFactsAligned"] = False
        if case["expected"] == "REVIEW_REQUIRED":
            checks["historyConsistent"] = None
        review = {"reviewer": "AI:gemini-test", "rubricVersion": VERSION_V3,
            "rubricHash": "frozen", "taskHash": run.gen.digest(case["task"]),
            "contextHash": run.gen.digest(case["context"]),
            "requestHash": run.gen.digest([stem, "request"]),
            "responseHash": run.gen.digest([stem, "response"]), "checks": checks,
            "issues": [{"check": key, "sourceQuote": "fixture", "explanation": "violates rule",
                        "severity": "critical" if key == "rolesCorrect" else "major"}
                       for key, value in checks.items() if value is not True]}
        run.gen.write_json(path / "calibration" / f"{stem}.result.json", review)
        return review
    monkeypatch.setattr(run, "_judge_one", fake_judge)
    summary = await run.calibrate(campaign, settings)
    assert summary["status"] == "CALIBRATED" and summary["scores"] == [
        {"runId": "first", "correct": 36}, {"runId": "second", "correct": 36}]
    assert len(called) == len(set(called)) == 72
    assert (await run.calibrate(campaign, settings)) == summary
    assert len(called) == 72


@pytest.mark.asyncio
async def test_calibration_sent_request_without_result_never_replays(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run._validation, "validate_task", lambda *a, **k: [])
    gold_path = tmp_path / "gold.json"
    run.gen.write_json(gold_path, _gold())
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    run.gen.write_json(campaign / "state.json", {"status": "JUDGE_BLOCKED"})
    manifest = {"goldPath": str(gold_path), "rubricVersion": VERSION_V3,
                "rubricHash": "frozen", "judge": {"model": "gemini-test"}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {"gemini": {"availableCalls": 72}})
    settings = SimpleNamespace(registry_pools={"gemini": {"default_model": "gemini-test"}})
    async def interrupted(path, contract, case, wire_id, stem, _settings):
        run.gen.write_json(path / "calibration" / f"{stem}.request.json", {"sent": True})
        raise RuntimeError("lost response")
    monkeypatch.setattr(run, "_judge_one", interrupted)
    result = await run.calibrate(campaign, settings)
    assert result["status"] == "INFRA_BLOCKED" and "no replay" in result["reason"]
    assert len(list((campaign / "calibration").glob("*.request.json"))) == 1


@pytest.mark.asyncio
async def test_calibration_stops_after_first_fatal_miss(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run._validation, "validate_task", lambda *a, **k: [])
    gold_path = tmp_path / "gold.json"
    run.gen.write_json(gold_path, _gold())
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    run.gen.write_json(campaign / "state.json", {"status": "JUDGE_BLOCKED"})
    manifest = {"goldPath": str(gold_path), "rubricVersion": VERSION_V3,
                "rubricHash": "frozen", "judge": {"model": "gemini-test"}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {"gemini": {"availableCalls": 72}})
    settings = SimpleNamespace(registry_pools={"gemini": {"default_model": "gemini-test"}})
    calls = []

    async def judge(path, contract, case, wire_id, stem, _settings):
        calls.append(stem)
        checks = dict.fromkeys(CHECKS, True)
        review = {"reviewer": "AI:gemini-test", "rubricVersion": VERSION_V3,
            "rubricHash": "frozen", "taskHash": run.gen.digest(case["task"]),
            "contextHash": run.gen.digest(case["context"]),
            "requestHash": run.gen.digest([stem, "request"]),
            "responseHash": run.gen.digest([stem, "response"]),
            "checks": checks, "issues": []}
        run.gen.write_json(path / "calibration" / f"{stem}.result.json", review)
        return review

    monkeypatch.setattr(run, "_judge_one", judge)
    result = await run.calibrate(campaign, settings)
    assert result["status"] == "JUDGE_BLOCKED"
    assert result["evaluated"] < 36
    assert run.gen.read_json(campaign / "state.json")["calibrationFailed"] is True
    assert len(calls) == result["evaluated"]
