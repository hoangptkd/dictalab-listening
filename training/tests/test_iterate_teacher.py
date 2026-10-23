import asyncio
import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import httpx
from jsonschema import ValidationError

from training import iterate_teacher as run
from training.semantic_rubric import CHECKS, VERSION_V3, judge_generalization_cases, rules_for


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
    chains = _chains()
    chains[-1][0]["hardPass"] = False
    assert not gate(_items(), chains)


def test_pilot_gate_requires_full_first_pass_coverage():
    rows = [{**item, "id": f"dev-{i}"} for i, item in enumerate(_items()[:4] +
        _items()[10:14] + _items()[20:24])]
    assert run.pilot_summary(rows)["pilotPassed"]
    rows[-1]["decision"] = "FAIL"
    assert run.pilot_summary(rows)["pilotPassed"]
    rows[-2]["decision"] = "FAIL"
    assert not run.pilot_summary(rows)["pilotPassed"]
    rows[-2]["decision"] = "PASS"
    rows[0]["hardPass"] = False
    assert not run.pilot_summary(rows)["pilotPassed"]


def test_pilot_comparison_rejects_level_regression_and_ties():
    best = {"hardPass": 12, "semanticPass": 9, "critical": 0,
            "unresolvedCriticalRisk": False, "byLevel": {"A1": 3, "A2": 3, "B1": 3}}
    assert not run.compare_pilots(best, best)
    assert run.compare_pilots(best, {**best, "semanticPass": 10,
        "byLevel": {"A1": 4, "A2": 3, "B1": 3}})
    assert not run.compare_pilots(best, {**best, "semanticPass": 10,
        "byLevel": {"A1": 4, "A2": 4, "B1": 2}})
    assert not run.compare_pilots(best, {**best, "critical": 1, "semanticPass": 10})


def test_decide_promotes_best_and_removes_losing_attempt_data(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    campaign = tmp_path / "campaign"
    for name in ("best", "current"):
        (campaign / name).mkdir(parents=True)
    artifacts = {}
    for name in ("best", "current"):
        artifact = {"approvalStatus": "development", "version": name}
        artifact["sha256"] = run.gen.digest(artifact)
        artifacts[name] = artifact
        run.gen.write_json(campaign / name / "artifact.json", artifact)
    base = {"hardPass": 12, "critical": 0, "unresolvedCriticalRisk": False,
            "pilotPassed": False, "rubricHash": "frozen", "judgeModel": "gemini-test"}
    best = {**base, "artifactHash": artifacts["best"]["sha256"], "semanticPass": 9,
            "byLevel": {"A1": 3, "A2": 3, "B1": 3}}
    current = {**base, "artifactHash": artifacts["current"]["sha256"], "semanticPass": 10,
               "byLevel": {"A1": 4, "A2": 3, "B1": 3}}
    run.gen.write_json(campaign / "best" / "summary.json", best)
    run.gen.write_json(campaign / "current" / "summary.json", current)
    state = {"status": "PILOT_READY", "sessionId": 1, "totalRounds": 1,
             "bestSummaryHash": run.gen.digest(best), "currentSummaryHash": run.gen.digest(current)}
    run.gen.write_json(campaign / "state.json", state)
    manifest = {"rubricHash": "frozen", "judge": {"model": "gemini-test"}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    result = run.decide(campaign)
    assert result["kept"] is True and result["bestHash"] == artifacts["current"]["sha256"]
    assert not (campaign / "current").exists() and not (campaign / "best-old").exists()
    assert run.gen.read_json(campaign / "best" / "artifact.json") == artifacts["current"]
    assert len((campaign / "ledger.jsonl").read_text(encoding="utf-8").splitlines()) == 1


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
                        "severity": "uncertain" if value is None else
                            "critical" if key == "rolesCorrect" else "major"}
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


def test_generalization_probes_are_separate_and_labels_never_reach_judge():
    cases = judge_generalization_cases()
    assert len(cases) == len({r["id"] for r in cases}) == 17
    assert len({r["family"] for r in cases}) == 8
    assert all(r["semanticProbeOnly"] for r in cases)
    assert {r["expected"] for r in cases} == {"PASS", "FAIL", "REVIEW_REQUIRED"}
    for row in cases:
        assert not run._validation.validate_task(row["task"], level=row["context"]["difficulty"])
        payload = run.judge_payload(row, "item-001", {"rubricVersion": VERSION_V3})
        assert payload["items"] == [{"id": "item-001", "context": row["context"], "task": row["task"]}]
        assert "expected" not in json.dumps(payload)
        assert row["expected"] == "PASS" or row["expectedCheck"] in CHECKS
    with pytest.raises(ValueError, match="36 unique"):
        run.audit_gold(cases)


@pytest.mark.parametrize("text,quote,valid", [
    ('She said "wait here".\nUse C:\\Room.', '"wait here".\nUse C:\\Room', True),
    ("Bạn hãy đợi ở đây nhé.", "đợi ở đây", True),
    ("Only source values count.", "utteranceEn", False),
    ("Only source values count.", '"}, {"', False),
    ("Only source values count.", "an invented quotation", False),
])
def test_judge_citations_match_decoded_source_values(text, quote, valid):
    manifest = {"judge": {"model": "chatgpt-web"}, "rubricVersion": VERSION_V3,
                "rubricHash": "frozen"}
    row = {"hardPass": True, "context": {}, "task": {"utteranceEn": text}}
    review = {"reviewer": "AI:chatgpt-web", "rubricVersion": VERSION_V3,
        "rubricHash": "frozen", "taskHash": run.gen.digest(row["task"]),
        "contextHash": run.gen.digest(row["context"]),
        "requestHash": run.gen.digest("request"), "responseHash": run.gen.digest("response"),
        "checks": {**dict.fromkeys(CHECKS, True), "translationFaithful": None},
        "issues": [{"check": "translationFaithful", "sourceQuote": quote,
                    "explanation": "Two material readings remain unresolved.", "severity": "uncertain"}]}
    assert run._review_integrity(row, review, manifest) is valid


@pytest.mark.parametrize("value,severity,valid", [
    (None, "uncertain", True), (None, "minor", False), (None, "critical", False),
    (False, "uncertain", False), (False, "minor", True), (False, "major", True),
])
def test_judge_uncertainty_and_severity_cannot_contradict(value, severity, valid):
    manifest = {"judge": {"model": "chatgpt-web"}, "rubricVersion": VERSION_V3,
                "rubricHash": "frozen"}
    row = {"hardPass": True, "context": {}, "task": {"utteranceEn": "source text"}}
    review = {"reviewer": "AI:chatgpt-web", "rubricVersion": VERSION_V3,
        "rubricHash": "frozen", "taskHash": run.gen.digest(row["task"]),
        "contextHash": run.gen.digest(row["context"]),
        "requestHash": run.gen.digest("request"), "responseHash": run.gen.digest("response"),
        "checks": {**dict.fromkeys(CHECKS, True), "translationFaithful": value},
        "issues": [{"check": "translationFaithful", "sourceQuote": "source text",
                    "explanation": "Evidence and consequence.", "severity": severity}]}
    assert run._review_integrity(row, review, manifest) is valid


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", [None, "invalid-json", "schema", "quote", "timeout", "http-error", "unfinished", "bad-envelope"])
async def test_web_judge_uses_bridge_alias_and_rejects_bad_verdicts(tmp_path, monkeypatch, fault):
    folder = tmp_path / "calibration"
    folder.mkdir()
    manifest = {"judge": {"provider": "chatgpt-web", "model": "chatgpt-web",
                         "url": "http://127.0.0.1:3000/v1/chat/completions"},
                "rubricVersion": VERSION_V3, "rubricHash": "frozen"}
    case = {"hardPass": True, "context": {"difficulty": "A1"},
            "task": {"utteranceEn": "The shop opens at ten."}, "expected": "FAIL"}
    verdict = {"id": "item-001", **dict.fromkeys(CHECKS, True), "issues": []}
    if fault == "schema":
        verdict.pop("rolesCorrect")
    if fault == "quote":
        verdict["rolesCorrect"] = False
        verdict["issues"] = [{"check": "rolesCorrect", "severity": "critical",
                              "sourceQuote": "an invented quotation", "explanation": "wrong role"}]
    calls = []

    def respond(request):
        body = json.loads(request.content)
        calls.append(body)
        assert len(body["messages"]) == 1 and body["messages"][0]["role"] == "user"
        assert "OUTPUT_SCHEMA" in body["messages"][0]["content"]
        assert body["messages"][0]["content"].startswith(run.JUDGE_SYSTEM)
        audit = json.loads(body["messages"][0]["content"].split("\nAUDIT_INPUT:\n", 1)[1])
        assert audit["rubric"] == rules_for(VERSION_V3)
        assert rules_for(VERSION_V3).strip() not in run.JUDGE_SYSTEM
        assert '"expected"' not in body["messages"][0]["content"]
        assert body["model"] == "chatgpt-web" and body["stream"] is False
        assert "temperature" not in body and "max_tokens" not in body
        if fault == "timeout":
            raise httpx.ReadTimeout("response lost", request=request)
        if fault == "http-error":
            return httpx.Response(503, json={"error": "unavailable"})
        if fault == "bad-envelope":
            return httpx.Response(200, text="<html>Unavailable</html>")
        content = "```json\n{}\n```" if fault == "invalid-json" else json.dumps({"items": [verdict]})
        return httpx.Response(200, json={"model": "some-web-alias", "choices": [
            {"finish_reason": "length" if fault == "unfinished" else "stop", "message": {"content": content}}]})

    original = httpx.AsyncClient
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs:
        original(transport=httpx.MockTransport(respond), **kwargs))
    if fault:
        error = {"timeout": httpx.ReadTimeout, "http-error": httpx.HTTPStatusError,
                 "bad-envelope": json.JSONDecodeError}.get(fault, run.gen.DatasetError)
        with pytest.raises(error):
            await run._judge_one(tmp_path, manifest, case, "item-001", "first-001", None)
        assert not (folder / "first-001.result.json").exists()
    else:
        review = await run._judge_one(tmp_path, manifest, case, "item-001", "first-001", None)
        assert review["reviewer"] == "AI:chatgpt-web"
        assert review["modelIdentity"] == "unverified-web-alias"
        assert review["returnedModel"] == "some-web-alias"
        assert run.checked_review(case, review, manifest, calibrated=True) == "PASS"
    (folder / "first-001.request.json").unlink()
    with pytest.raises(ValueError, match="refusing replay"):
        await run._judge_one(tmp_path, manifest, case, "item-001", "first-001", None)
    assert len(calls) == 1


@pytest.mark.parametrize("url", ["https://example.com/v1/chat/completions",
    "http://127.0.0.1:3000/v1/chat/completions?key=secret",
    "http://user:secret@localhost/v1/chat/completions", "http://localhost/other"])
def test_web_judge_rejects_nonlocal_or_credential_urls(url):
    with pytest.raises(ValueError, match="loopback"):
        run._bridge_url(url)


def test_web_campaign_does_not_depend_on_gemini_accounts_or_quota(monkeypatch):
    manifest = {"teacher": {"model": "deepseek-test", "sourceIds": ["d"]},
                "judge": {"provider": "chatgpt-web", "model": "chatgpt-web",
                          "url": "http://127.0.0.1:3000/v1/chat/completions"}}
    settings = SimpleNamespace(registry_pools={"deepseek": {"default_model": "deepseek-test"}},
        sources_for=lambda provider: [SimpleNamespace(id="d")] if provider == "deepseek" else
            pytest.fail("Web must not require Gemini accounts"))
    run._check_sources(manifest, settings)

    def quota(settings, *, platforms):
        assert platforms == ("deepseek",)
        return {"deepseek": {"availableCalls": 50}}

    monkeypatch.setattr(run, "quota_snapshot", quota)
    assert run._campaign_quota(settings, manifest) == {"deepseek": {"availableCalls": 50},
        "chatgpt-web": {"availableCalls": None, "quotaVisibility": "web-account-managed"}}


@pytest.mark.asyncio
@pytest.mark.parametrize("passed", [False, True])
async def test_benchmark_only_runs_30_after_calibration(tmp_path, monkeypatch, passed):
    monkeypatch.setattr(run, "load", lambda path: ({}, {}))
    monkeypatch.setattr(run, "_calibration_valid", lambda *args: False)
    calls = []

    async def calibrate(*args):
        calls.append("calibrate")
        return {"status": "CALIBRATED" if passed else "JUDGE_BLOCKED"}

    async def final(*args, direct):
        assert direct is True
        calls.append("final")
        return {"status": "FINAL_FAILED", "semanticPass": 26}

    monkeypatch.setattr(run, "calibrate", calibrate)
    monkeypatch.setattr(run, "final", final)
    result = await run.benchmark(tmp_path)
    assert calls == (["calibrate", "final"] if passed else ["calibrate"])
    assert result["status"] == ("FINAL_FAILED" if passed else "JUDGE_BLOCKED")


def test_calibration_provenance_rejects_changed_gold(tmp_path):
    gold_path = tmp_path / "gold.json"
    run.gen.write_json(gold_path, [{"id": "fixture"}])
    summary = {"status": "CALIBRATED", "goldHash": run.gen.digest([{"id": "fixture"}]),
               "judgeModel": "gemini-test", "rubricHash": "frozen"}
    run.gen.write_json(tmp_path / "calibration-summary.json", summary)
    manifest = {"goldPath": str(gold_path), "judge": {"model": "gemini-test"},
                "rubricHash": "frozen"}
    state = {"goldHash": summary["goldHash"], "calibrationHash": run.gen.digest(summary)}
    assert run._calibration_valid(tmp_path, manifest, state)
    gold_path.write_text('[{"id":"changed"}]', encoding="utf-8")
    assert not run._calibration_valid(tmp_path, manifest, state)


@pytest.mark.parametrize("judge_provider", ["gemini", "chatgpt-web"])
@pytest.mark.parametrize("wire_format", [None, "aligned-targets-v1"])
def test_prepare_freezes_private_campaign_without_credentials(tmp_path, monkeypatch, judge_provider, wire_format):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run, "_plan_groups", lambda: {"development": [], "unseenCapacityByLevel": {}})
    monkeypatch.setattr(run, "development_cases", lambda groups, artifact: [])
    source_plans = (tmp_path / "old-one.json", tmp_path / "old-two.json")
    for source in source_plans:
        run.gen.write_json(source, {"entries": [], "chains": []})
    monkeypatch.setattr(run, "SOURCE_PLANS", source_plans)
    artifact_path = tmp_path / "candidate.json"
    artifact = {"approvalStatus": "development", "version": "test"}
    if wire_format:
        artifact["teacherWireFormat"] = wire_format
    artifact["sha256"] = run.gen.digest(artifact)
    run.gen.write_json(artifact_path, artifact)
    sources = {name: [SimpleNamespace(id=name + "-a", model=model, api_key="private-key")]
               for name, model in (("deepseek", "deepseek-test"), ("gemini", "gemini-test"))}
    settings = SimpleNamespace(sources_for=lambda name: sources[name] if
        name != "gemini" or judge_provider == "gemini" else pytest.fail("Web must not read Gemini accounts"), registry_pools={
        "deepseek": {"default_model": "deepseek-test"},
        "gemini": {"default_model": "gemini-test"}})
    campaign = tmp_path / "campaign"
    manifest = run.prepare(campaign, artifact_path=artifact_path, settings=settings, judge_provider=judge_provider)
    assert manifest["policy"] == run.POLICY
    assert manifest["judge"]["provider"] == judge_provider
    assert manifest["teacherWrapperHash"] == run.gen.digest(run.gen.teacher_batch_system(artifact))
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
    cases = run.development_cases(run._plan_groups()["development"], artifact)
    assert len(cases) == 12
    assert [row["turn"] for row in cases if row["level"] == "B1"] == [2, 2, 2, 4]
    focuses = {kind for group in groups for turn in (2, 4) for kind in (
        "gist", "opinion_attitude", "inference_prediction") if
        f"genuine {kind} target" in run.gen.make_context(group, turn, [], artifact, 0)["practiceFocus"]}
    assert focuses == {"gist", "opinion_attitude", "inference_prediction"}
    assert {kind for row in cases if row["level"] == "B1" for kind in focuses
            if f"genuine {kind} target" in row["context"]["practiceFocus"]} == focuses


def test_final_allocation_has_frozen_focus_coverage_and_no_group_reuse():
    artifact = run._checked_artifact(run.DEFAULT_ARTIFACT)
    manifest = {"groups": run._plan_groups()}
    allocation = run.final_allocation(manifest, artifact)
    assert len(allocation["entries"]) == 30 and len(allocation["chains"]) == 6
    assert len(set(allocation["groupIds"])) == 36
    assert not set(allocation["groupIds"]).intersection(
        group["groupId"] for group in manifest["groups"]["development"])
    for level in run.LEVELS:
        assert sorted(row["focus"] for row in allocation["entries"] if row["level"] == level) == \
            sorted(run.FINAL_FOCUSES[level])
    retired = [row["groupId"] for row in allocation["entries"] if row["level"] == "B1"]
    with pytest.raises(ValueError, match="DATA_COVERAGE_BLOCKED"):
        run.final_allocation(manifest, artifact, set(retired[:2]))


def test_retired_final_groups_apply_across_campaigns(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    old = tmp_path / "teacher-auto-old"
    old.mkdir()
    run.gen.append(old / "retired-final-index.jsonl", {"groupIds": ["one", "two"]})
    assert run._retired_groups() == {"one", "two"}


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


def test_unrestricted_quota_reports_usage_without_false_daily_exhaustion(monkeypatch):
    settings = SimpleNamespace(operational_limits_enabled=False,
        sources_for=lambda _: [SimpleNamespace(id="d", rpd_limit=100)],
        registry_pools={"deepseek": {"global_rpd_limit": 100}})
    monkeypatch.setattr(run, "quota_store", lambda *a, **k: SimpleNamespace(
        execute=lambda *a, **k: [0, 0, 101, 0, 101, 0]))
    quota = run.quota_snapshot(settings, platforms=("deepseek",))["deepseek"]
    assert quota["availableCalls"] is None
    assert quota["globalRpdLimit"] == 0
    assert quota["configuredGlobalRpdLimit"] == 100
    assert quota["globalUsedRpd"] == 101
    assert quota["accounts"][0]["remainingRpd"] is None


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
    request_hashes = set()
    async def fake_judge(path, contract, case, wire_id, stem, _settings):
        payload = run.judge_payload(case, wire_id, contract)
        request_hash = run.gen.digest(payload)
        assert request_hash not in request_hashes
        request_hashes.add(request_hash)
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
            "requestHash": request_hash,
            "responseHash": run.gen.digest([stem, "response"]), "checks": checks,
            "issues": [{"check": key, "sourceQuote": "fixture", "explanation": "violates rule",
                        "severity": "uncertain" if value is None else
                            "critical" if key == "rolesCorrect" else "major"}
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
@pytest.mark.parametrize("error", [json.JSONDecodeError("invalid JSON", "x", 0),
                                  ValidationError("invalid verdict schema")])
async def test_calibration_invalid_verdict_is_judge_failure_not_transport_failure(tmp_path, monkeypatch, error):
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

    async def invalid(path, contract, case, wire_id, stem, _settings):
        run.gen.write_json(path / "calibration" / f"{stem}.envelope.json", {"response": "received"})
        raise error

    monkeypatch.setattr(run, "_judge_one", invalid)
    result = await run.calibrate(campaign, settings)
    assert result["status"] == "JUDGE_BLOCKED"
    assert run.gen.read_json(campaign / "state.json")["calibrationFailed"] is True


@pytest.mark.asyncio
async def test_sent_index_blocks_replay_even_if_request_file_is_lost(tmp_path):
    folder = tmp_path / "calibration"
    folder.mkdir()
    run.gen.append(tmp_path / "sent-index.jsonl", {"kind": "judge-calibration",
        "phase": "calibration", "stem": "first-001", "requestHash": run.gen.digest("sent")})
    with pytest.raises(ValueError, match="refusing replay"):
        await run._judge_one(tmp_path, {}, {}, "wire", "first-001", None)
    run.gen.append(tmp_path / "sent-index.jsonl", {"kind": "teacher", "phase": "current",
        "id": "dev-A1-1", "requestHash": run.gen.digest("sent")})
    current = tmp_path / "current"
    current.mkdir()
    with pytest.raises(ValueError, match="refusing replay"):
        await run._teacher_one(tmp_path, {}, {"id": "dev-A1-1"}, {}, {}, current, None)


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


@pytest.mark.asyncio
async def test_development_provider_cache_and_review_integrity(tmp_path, monkeypatch):
    folder = tmp_path / "current"
    folder.mkdir()
    entries = [{"id": f"dev-{level}-{i}", "level": level, "groupId": f"{level}-{i}",
                "context": {"difficulty": level}} for level in run.LEVELS for i in range(4)]
    manifest = {"groups": {"development": [{"groupId": entry["groupId"]} for entry in entries]},
                "judge": {"model": "gemini-test"}, "rubricVersion": VERSION_V3,
                "rubricHash": "frozen"}
    calls = []

    async def teacher(path, contract, entry, group, artifact, target, settings):
        calls.append(("teacher", entry["id"]))
        row = {**entry, "task": {"utteranceEn": "fixture"}, "hardPass": True,
               "attempt": 1, "developmentOnly": True}
        run.gen.write_json(target / f"{entry['id']}.teacher-result.json", row)
        return row

    async def judge(path, contract, row, wire_id, stem, settings, *, folder):
        calls.append(("judge", stem))
        review = {"reviewer": "AI:gemini-test", "rubricVersion": VERSION_V3,
            "rubricHash": "frozen", "taskHash": run.gen.digest(row["task"]),
            "contextHash": run.gen.digest(row["context"]),
            "requestHash": run.gen.digest([stem, "request"]),
            "responseHash": run.gen.digest([stem, "response"]),
            "checks": dict.fromkeys(CHECKS, True), "issues": []}
        run.gen.write_json(folder / f"{stem}.result.json", review)
        return review

    monkeypatch.setattr(run, "_teacher_one", teacher)
    monkeypatch.setattr(run, "_judge_one", judge)
    rows = await run._run_entries(tmp_path, manifest, entries, {}, folder, None)
    assert run.pilot_summary(rows)["pilotPassed"]
    assert len(calls) == 24
    assert await run._run_entries(tmp_path, manifest, entries, {}, folder, None) == rows
    assert len(calls) == 24
    reviewed = folder / "dev-A1-0.reviewed.json"
    changed = run.gen.read_json(reviewed)
    changed["decision"] = "FAIL"
    reviewed.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="reviewed result hash mismatch"):
        await run._run_entries(tmp_path, manifest, entries, {}, folder, None)


@pytest.mark.asyncio
async def test_evaluate_freezes_baseline_and_candidate_once(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run, "_calibration_valid", lambda *args: True)
    campaign = tmp_path / "campaign"
    campaign.mkdir()
    artifacts = {}
    for name in ("baseline", "candidate"):
        artifact = {"approvalStatus": "development", "version": name,
                    "levelProfiles": {}, "contentSchemas": {}, "translationSchema": {}}
        artifact["sha256"] = run.gen.digest(artifact)
        artifacts[name] = artifact
        run.gen.write_json(tmp_path / f"{name}.json", artifact)
    entries = [{"id": f"dev-{level}-{index}", "level": level}
               for level in run.LEVELS for index in range(4)]
    manifest = {"artifactPath": str(tmp_path / "baseline.json"),
                "artifactHash": artifacts["baseline"]["sha256"],
                "development": entries, "groups": {"development": []},
                "teacher": {"model": "deepseek-test", "sourceIds": ["d"]},
                "judge": {"model": "gemini-test", "sourceIds": ["g"]},
                "rubricHash": "frozen"}
    state = {"status": "CALIBRATED", "sessionId": 1, "roundInSession": 0,
             "totalRounds": 0, "maxRounds": 10}
    run.gen.write_json(campaign / "state.json", state)
    run.gen.write_json(campaign / "calibration-summary.json", {"status": "CALIBRATED"})
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {
        "deepseek": {"availableCalls": 100}, "gemini": {"availableCalls": 100}})
    settings = SimpleNamespace(registry_pools={
        "deepseek": {"default_model": "deepseek-test"},
        "gemini": {"default_model": "gemini-test"}},
        sources_for=lambda provider: [SimpleNamespace(id={"deepseek": "d", "gemini": "g"}[provider])])
    called = []

    async def fake_entries(path, contract, entries, artifact, folder, settings):
        called.append(folder.name)
        return [{**entry, "hardPass": True, "decision": "PASS", "severity": "none",
                 "unresolvedCriticalRisk": False, "attempt": 1, "developmentOnly": True}
                for entry in entries]

    monkeypatch.setattr(run, "_run_entries", fake_entries)
    result = await run.evaluate(campaign, tmp_path / "candidate.json", settings)
    assert result["status"] == "PILOT_READY" and called == ["best", "current"]
    assert result["baseline"]["pilotPassed"] and result["candidate"]["pilotPassed"]
    assert run.gen.read_json(campaign / "state.json")["roundInSession"] == 1
    again = await run.evaluate(campaign, tmp_path / "candidate.json", settings)
    assert again["status"] == "PILOT_READY" and called == ["best", "current"]


@pytest.mark.asyncio
@pytest.mark.parametrize("direct", [False, True])
async def test_final_runs_new_30_and_six_dependent_chains_with_fake_providers(tmp_path, monkeypatch, direct):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run, "_calibration_valid", lambda *args: True)
    campaign = tmp_path / "campaign"
    (campaign / "best").mkdir(parents=True)
    artifact = run._checked_artifact(run.DEFAULT_ARTIFACT)
    run.gen.write_json(campaign / "best" / "artifact.json", artifact)
    summary = {"pilotPassed": True, "artifactHash": artifact["sha256"]}
    if not direct:
        run.gen.write_json(campaign / "best" / "summary.json", summary)
    state = {"status": "CALIBRATED", "phase": "development", "bestSummaryHash": run.gen.digest(summary)}
    run.gen.write_json(campaign / "state.json", state)
    run.gen.write_json(campaign / "calibration-summary.json", {"status": "CALIBRATED"})
    manifest = {"groups": run._plan_groups(), "rubricHash": "frozen",
                "artifactPath": str(run.DEFAULT_ARTIFACT), "artifactHash": artifact["sha256"],
                "teacher": {"model": "deepseek-test", "sourceIds": ["d"]},
                "judge": {"model": "gemini-test", "sourceIds": ["g"]}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {
        "deepseek": {"availableCalls": 100}, "gemini": {"availableCalls": 100}})
    settings = SimpleNamespace(registry_pools={
        "deepseek": {"default_model": "deepseek-test"},
        "gemini": {"default_model": "gemini-test"}},
        sources_for=lambda provider: [SimpleNamespace(id={"deepseek": "d", "gemini": "g"}[provider])])
    calls = []

    async def fake_entries(path, contract, entries, artifact, folder, settings):
        calls.extend(entry["id"] for entry in entries)
        for entry in entries:
            if entry["id"].startswith("chain-"):
                task = {"facts": [], "slots": [], "utteranceEn": "A new detail is available.",
                        "questionVi": "Chi tiết mới là gì?", "skill": "Nắm thông tin cụ thể"}
                run.gen.write_json(folder / f"{entry['id']}.teacher-result.json",
                    {**entry, "task": task, "context": entry["context"]})
        return [{"id": entry["id"], "level": entry["level"], "hardPass": True,
                 "decision": "PASS", "severity": "none", "unresolvedCriticalRisk": False,
                 "attempt": 1, "developmentOnly": True} for entry in entries]

    monkeypatch.setattr(run, "_run_entries", fake_entries)
    report = await run.final(campaign, settings, direct=direct)
    assert report["status"] == "ACCEPTED" and report["semanticPass"] == 30
    assert report["completeChains"] == 6 and len(calls) == 48
    assert run.gen.read_json(campaign / "state.json")["status"] == "ACCEPTED"
    assert (campaign / "final-accepted" / "report.json").exists()
    assert len((campaign / "retired-final-index.jsonl").read_text(encoding="utf-8").splitlines()) == 1
    if direct:
        assert "pilotPassed" not in run.gen.read_json(campaign / "best" / "summary.json")


@pytest.mark.asyncio
async def test_final_promotion_resume_never_starts_a_second_final(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    campaign = tmp_path / "campaign"
    (campaign / "final-accepted").mkdir(parents=True)
    (campaign / "best").mkdir()
    artifact = {"approvalStatus": "development", "version": "best"}
    artifact["sha256"] = run.gen.digest(artifact)
    run.gen.write_json(campaign / "best" / "artifact.json", artifact)
    report = {"status": "ACCEPTED", "accepted": True, "semanticPass": 28,
              "rubricHash": "frozen", "judgeModel": "gemini-test",
              "candidateHash": artifact["sha256"]}
    run.gen.write_json(campaign / "final-accepted" / "report.json", report)
    run.gen.write_json(campaign / "state.json", {"status": "FINAL_RUNNING"})
    monkeypatch.setattr(run, "load", lambda path: ({"rubricHash": "frozen",
        "judge": {"model": "gemini-test"}}, run.gen.read_json(path / "state.json")))
    assert await run.final(campaign) == report
    assert run.gen.read_json(campaign / "state.json")["status"] == "ACCEPTED"
    assert not (campaign / "final-running").exists()


@pytest.mark.asyncio
async def test_failed_final_retains_only_compact_ledger_and_retired_index(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path.resolve())
    monkeypatch.setattr(run, "_calibration_valid", lambda *args: True)
    monkeypatch.setattr(run, "final_allocation", lambda *args: {
        "entries": [], "chains": [], "groupIds": ["fresh-group"]})
    campaign = tmp_path / "campaign"
    (campaign / "best").mkdir(parents=True)
    artifact = run._checked_artifact(run.DEFAULT_ARTIFACT)
    run.gen.write_json(campaign / "best" / "artifact.json", artifact)
    summary = {"pilotPassed": True, "artifactHash": artifact["sha256"]}
    run.gen.write_json(campaign / "best" / "summary.json", summary)
    run.gen.write_json(campaign / "calibration-summary.json", {"status": "CALIBRATED"})
    run.gen.write_json(campaign / "state.json", {"status": "CALIBRATED",
        "phase": "development", "bestSummaryHash": run.gen.digest(summary)})
    manifest = {"groups": {"development": []}, "rubricHash": "frozen",
                "teacher": {"model": "deepseek-test", "sourceIds": ["d"]},
                "judge": {"model": "gemini-test", "sourceIds": ["g"]}}
    monkeypatch.setattr(run, "load", lambda path: (manifest, run.gen.read_json(path / "state.json")))
    monkeypatch.setattr(run, "quota_snapshot", lambda settings: {
        "deepseek": {"availableCalls": 100}, "gemini": {"availableCalls": 100}})
    monkeypatch.setattr(run, "_run_entries", lambda *args: asyncio.sleep(0, result=[]))
    settings = SimpleNamespace(registry_pools={
        "deepseek": {"default_model": "deepseek-test"},
        "gemini": {"default_model": "gemini-test"}},
        sources_for=lambda provider: [SimpleNamespace(id={"deepseek": "d", "gemini": "g"}[provider])])
    report = await run.final(campaign, settings)
    assert report["status"] == "FINAL_FAILED" and not report["accepted"]
    assert not (campaign / "final-running").exists()
    assert (campaign / "best" / "summary.json").exists()
    assert len((campaign / "ledger.jsonl").read_text(encoding="utf-8").splitlines()) == 1
    assert len((campaign / "retired-final-index.jsonl").read_text(encoding="utf-8").splitlines()) == 1
