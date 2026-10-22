from __future__ import annotations

import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import httpx

from training import collect_reviewed as collect
from training import generate_teacher_data as gen
from training import iterate_teacher as run
from training.prepare_dataset import DatasetError, _contract, _split, validate_training_task
from training.shutdown_watchdog import request_shutdown, shutdown_reason
from training.tests.test_prepare_dataset import _row
from training.train_sft_lora import check_cpu_memory


@pytest.fixture
def campaign(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path)
    monkeypatch.setattr(run, "SOURCE_PLANS", ())
    monkeypatch.setattr(collect, "prior_corpus", lambda: ["old reviewed source text"])
    settings = SimpleNamespace(sources_for=lambda _: [SimpleNamespace(id="d1")],
        registry_pools={"deepseek": {"default_model": "deepseek-test"}})
    path = tmp_path / "collection"
    collect.prepare(path, settings)
    return path, settings


def test_plan_has_all_four_levels_and_no_cross_level_family_leak(campaign):
    path, _ = campaign
    manifest, _ = collect.load(path)
    by_family = {}
    for group in manifest["groups"]:
        assert by_family.setdefault(group["groupId"], group["split"]) == group["split"]
        assert group["split"] == _split(group["groupId"], 17, 15, 15, set())
        assert not (group["scenarioId"] in gen.CORE and group["branchId"] == "standard")
    assert {group["level"] for group in manifest["groups"]} == set(collect.LEVELS)
    assert collect.status(path)["approvedTasks"] == 0
    assert not collect.status(path)["trainingReady"]
    with pytest.raises(DatasetError, match="700"):
        collect.export(path)


def test_existing_gold_source_families_cannot_enter_collection(tmp_path, monkeypatch):
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path)
    monkeypatch.setattr(run, "SOURCE_PLANS", ())
    monkeypatch.setattr(collect, "prior_corpus", lambda: [])
    gen.write_json(tmp_path / "teacher-gold-fixture.json", [{"sourceId": "invitation/standard/A2"}])
    settings = SimpleNamespace(sources_for=lambda _: [SimpleNamespace(id="d1")],
        registry_pools={"deepseek": {"default_model": "test"}})
    collect.prepare(tmp_path / "collection", settings)
    manifest, _ = collect.load(tmp_path / "collection")
    assert not any(g["scenarioId"] == "invitation" and g["branchId"] == "standard" for g in manifest["groups"])


@pytest.mark.asyncio
async def test_quota_stop_sends_nothing_and_resume_does_not_reset_it(campaign, monkeypatch):
    path, settings = campaign
    monkeypatch.setattr(run, "quota_snapshot", lambda *a, **k: {"deepseek": {"availableCalls": 0}})
    result = await collect.collect(path, settings=settings)
    assert result["status"] == "WAITING_QUOTA" and result["attempted"] == 0
    assert not (path / "sent-index.jsonl").exists()
    assert (await collect.collect(path, settings=settings))["attempted"] == 0


@pytest.mark.asyncio
async def test_unresolved_worker_state_is_not_replayed(campaign):
    path, settings = campaign
    run._atomic_json(path / "state.json", {"status": "RUNNING", "nextIndex": 1})
    with pytest.raises(DatasetError, match="unresolved"):
        await collect.collect(path, settings=settings)


def test_rejection_compacts_evidence_and_preserves_other_candidates(campaign):
    path, _ = campaign
    row = {"id": "candidate-0001", "groupId": "fresh/a/b", "level": "A1", "split": "train",
           "context": {"private": "context"}, "task": {"utteranceEn": "source body"}, "sent": True,
           "error": "deepseek:teacher_batch:schema_mismatch source body", "errorType": "ProviderError"}
    gen.write_json(path / "candidates/candidate-0001.teacher-result.json", row)
    gen.write_json(path / "candidates/candidate-0001.teacher-request.json", {"body": "details"})
    gen.write_json(path / "candidates/candidate-0002.teacher-request.json", {"body": "keep"})
    collect.reject(path, row, "ambiguous-required-answer")
    compact = gen.read_json(path / "decisions/candidate-0001.json")
    assert compact["taskHash"] == gen.digest(row["task"])
    assert compact["teacher-requestHash"]
    assert compact["errorCodes"] == ["deepseek:teacher_batch:schema_mismatch"]
    assert "source body" not in json.dumps(compact) and "context" not in compact
    assert not list((path / "candidates").glob("candidate-0001.*"))
    assert (path / "candidates/candidate-0002.teacher-request.json").exists()


def test_duplicate_audit_detects_small_rewrites_without_rejecting_different_topics():
    old = "Please meet me outside the library at seven in the evening and bring your red notebook."
    assert collect.duplicate(old, [collect.normalized(old)])
    assert collect.duplicate(old.replace("red", "blue"), [collect.normalized(old)])
    assert not collect.duplicate("The gallery can retain the pieces overnight because the coating needs more time to dry.", [collect.normalized(old)])


def test_b2_is_allowed_only_by_explicit_offline_artifact_and_still_checks_grounding():
    artifact = gen.read_json(collect.ARTIFACT)
    row = _row(10)
    item = row["validationTask"]
    item["utteranceEn"] += " Please check the notice near the entrance before you arrive, because the side gate is closed and everyone needs to use the main door today."
    assert not validate_training_task(item, "B2", artifact, artifact["levelProfiles"]["B2"]["wordBounds"])
    old = gen.read_json(gen.ROOT / "artifacts/prompts/conversation-cefr-v9-20261022.json")
    with pytest.raises(DatasetError, match="outside"):
        validate_training_task(item, "B2", old, {"minWords": 30, "maxWords": 90})
    invalid = copy.deepcopy(item)
    invalid["facts"][0]["evidenceEn"] = "invented evidence"
    assert any("not_substring" in error for error in validate_training_task(invalid, "B2", artifact, {"minWords": 30, "maxWords": 90}))
    assert "level:unsupported" in run._validation.validate_task(item, level="B2")


def test_authorized_training_attempt_skips_ram_admission_only():
    inspection = {"parameterCountFromConfig": 4_205_751_296}
    with pytest.raises(DatasetError, match="RAM preflight"):
        check_cpu_memory(inspection, 1024)
    report = check_cpu_memory(inspection, 1024, authorized_attempt=True)
    assert report["authorizedAttemptBelowEstimate"] and report["availableBytesBeforeLoad"] == 1024
    with pytest.raises(DatasetError, match="parameter count"):
        check_cpu_memory({}, 1024, authorized_attempt=True)


def test_shutdown_policy_and_command_are_mocked(tmp_path):
    assert shutdown_reason({"status": "RUNNING"}, False) == "WORKER_EXITED_UNEXPECTEDLY"
    assert shutdown_reason({"status": "RUNNING"}, True) is None
    assert shutdown_reason({"status": "AWAITING_CODEX_REVIEW"}, False) is None
    assert shutdown_reason({"status": "READY"}, True) is None
    assert shutdown_reason({"status": "WAITING_QUOTA"}, True) == "WAITING_QUOTA"
    calls = []
    def fake_execute(command, **kwargs):
        calls.append(command)
        return SimpleNamespace(returncode=0, stderr="")
    request_shutdown("TEST", tmp_path / "evidence.json", execute=fake_execute)
    assert calls == [["shutdown.exe", "/s", "/f", "/t", "60"]]
    assert gen.read_json(tmp_path / "evidence.json")["returnCode"] == 0


def review_fixture(path):
    manifest, _ = collect.load(path)
    row = {"id": "candidate-0001", "hardPass": True, "split": "train", "level": "A1",
           "groupId": "fresh/fixture/standard", "context": {"difficulty": "A1"},
           "task": {"utteranceEn": "The shop opens at ten.", "facts": [{}]}}
    review = {"reviewer": "AI:chatgpt-web", "rubricVersion": manifest["rubricVersion"],
        "rubricHash": manifest["rubricHash"], "taskHash": gen.digest(row["task"]),
        "contextHash": gen.digest(row["context"]), "requestHash": "a" * 64,
        "responseHash": "b" * 64, "checks": dict.fromkeys(collect.CHECKS, True), "issues": []}
    verdict = {"id": row["id"], "reviewer": "AI:Codex", "verdict": "approved",
        "taskHash": gen.digest(row["task"]), "contextHash": gen.digest(row["context"]),
        "webReviewHash": gen.digest(review), "reason": "Independent fixture review.",
        "checks": dict.fromkeys(collect.CHECKS, True), "factEvidence": [
            {"index": 0, "sourceQuote": "opens at ten", "finding": "Direct opening-time evidence."}]}
    return row, review, verdict


@pytest.mark.parametrize("fault", [None, "stale-hash", "missing-evidence", "unsupported-override"])
def test_adjudication_requires_hash_bound_independent_evidence(campaign, fault):
    path, _ = campaign
    row, review, verdict = review_fixture(path)
    if fault == "stale-hash":
        verdict["taskHash"] = "c" * 64
    elif fault == "missing-evidence":
        verdict["factEvidence"] = []
    elif fault == "unsupported-override":
        review["checks"]["translationFaithful"] = None
        review["issues"] = [{"check": "translationFaithful", "severity": "uncertain",
            "sourceQuote": "opens at ten", "explanation": "Fixture ambiguity."}]
        verdict["webReviewHash"] = gen.digest(review)
    gen.write_json(path / "candidates/candidate-0001.teacher-result.json", row)
    gen.write_json(path / "candidates/candidate-0001.result.json", review)
    if fault:
        with pytest.raises(DatasetError):
            collect.adjudicate(path, verdict)
        assert collect.status(path)["approvedTasks"] == 0
    else:
        assert collect.adjudicate(path, verdict)["approvedTasks"] == 1
        with pytest.raises(DatasetError, match="already adjudicated"):
            collect.adjudicate(path, verdict)


@pytest.mark.asyncio
async def test_collection_stays_running_until_entire_batch_finishes(campaign, monkeypatch):
    path, settings = campaign
    monkeypatch.setattr(run, "quota_snapshot", lambda *a, **k: {"deepseek": {"availableCalls": 4}})
    states = []
    async def teacher(path, manifest, entry, group, artifact, folder, settings):
        states.append(gen.read_json(path / "state.json")["status"])
        row = {**entry, "hardPass": False, "sent": True}
        gen.write_json(folder / f"{entry['id']}.teacher-result.json", row)
        return row
    monkeypatch.setattr(run, "_teacher_one", teacher)
    result = await collect.collect(path, settings=settings, limit=4)
    assert states == ["RUNNING"] * 4
    assert result["status"] == "AWAITING_CODEX_REVIEW" and result["attempted"] == 4
    assert len(collect.decisions(path)) == 4


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", [None, "unsupported", "quote", "index"])
async def test_web_positive_fact_audit_is_grounded_and_consistent(campaign, monkeypatch, fault):
    path, _ = campaign
    manifest, _ = collect.load(path)
    row, _, _ = review_fixture(path)
    audit = {"index": 0, "sourceQuote": "opens at ten", "claimSupported": True,
             "translationSupported": fault != "unsupported", "basis": "Direct source evidence."}
    if fault == "quote":
        audit["sourceQuote"] = "invented quote"
    if fault == "index":
        audit["index"] = 1
    verdict = {"id": row["id"], **dict.fromkeys(collect.CHECKS, True), "issues": [], "factAudit": [audit]}
    original = httpx.AsyncClient
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs: original(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps({"items": [verdict]})}}]})), **kwargs))
    if fault:
        with pytest.raises(DatasetError):
            await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
    else:
        result = await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
        assert result["factAudit"] == [audit]
