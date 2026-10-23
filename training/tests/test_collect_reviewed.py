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


def test_diagnostic_experiment_cannot_export_sft(campaign):
    path, settings = campaign
    diagnostic = path.parent / "diagnostic"
    collect.prepare(diagnostic, settings, diagnostic=True)
    with pytest.raises(DatasetError, match="diagnostic"):
        collect.export(diagnostic)


def test_requested_diagnostic_rejection_evidence_is_preserved(campaign):
    path, settings = campaign
    with pytest.raises(DatasetError, match="diagnostic"):
        collect.prepare(path.parent / "invalid", settings, retain_diagnostic_evidence=True)
    diagnostic = path.parent / "diagnostic"
    collect.prepare(diagnostic, settings, diagnostic=True, retain_diagnostic_evidence=True)
    row = {"id": "candidate-0001", "groupId": "fresh/a/b", "level": "A1", "split": "train",
           "context": {}, "task": {"utteranceEn": "Original evidence"}}
    file = diagnostic / "candidates/candidate-0001.teacher-result.json"
    gen.write_json(file, row)
    collect.reject(diagnostic, row, "diagnostic-review")
    assert not file.exists()
    archived = diagnostic / "rejected-evidence/candidate-0001" / file.name
    assert gen.read_json(archived) == row
    assert collect.status(diagnostic)["pending"] == []
    with pytest.raises(DatasetError, match="diagnostic"):
        collect.export(diagnostic)


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


def test_compact_failure_retains_word_bounds_without_rejected_text(campaign):
    path, _ = campaign
    row = {"id": "candidate-0001", "groupId": "fresh/a/b", "level": "B2", "split": "train",
           "context": {}, "error": "production validator: utteranceEn:word_count=91,min=30,max=90 source body"}
    collect.reject(path, row, "hard-check")
    compact = gen.read_json(path / "decisions/candidate-0001.json")
    assert compact["validationCodes"] == ["utteranceEn:word_count=91,min=30,max=90"]
    assert "source body" not in json.dumps(compact)


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
           "task": {"utteranceEn": "The shop opens at ten.", "facts": [{"taskType": "direct_detail"}]}}
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
async def test_unrestricted_collection_passes_frozen_candidate_and_batch_caps(campaign, monkeypatch):
    path, settings = campaign
    settings.operational_limits_enabled = False
    state = gen.read_json(path / "state.json")
    state["nextIndex"] = 1500
    run._atomic_json(path / "state.json", state)
    monkeypatch.setattr(run, "quota_snapshot", lambda *a, **k: {"deepseek": {"availableCalls": None}})
    async def teacher(path, manifest, entry, group, artifact, folder, settings):
        row = {**entry, "hardPass": False, "sent": True}
        gen.write_json(folder / f"{entry['id']}.teacher-result.json", row)
        return row
    monkeypatch.setattr(run, "_teacher_one", teacher)
    result = await collect.collect(path, settings=settings, limit=21)
    assert result["attempted"] == 1521
    assert result["candidateCap"] == 0
    assert result["configuredCandidateCap"] == 1500
    assert result["status"] == "AWAITING_CODEX_REVIEW"
    assert len(collect.decisions(path)) == 21


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", [None, "unsupported", "quote", "index", "type", "scope",
                                  "novelty", "attributes", "history", "unknown-type"])
async def test_web_positive_fact_audit_is_grounded_and_consistent(campaign, monkeypatch, fault):
    path, _ = campaign
    manifest, _ = collect.load(path)
    row, _, _ = review_fixture(path)
    row["context"]["previouslyTestedDetails"] = [{"sourceUtteranceEn": "The shop opens at nine."}]
    audit = {"index": 0, "sourceQuote": "opens at ten", "claimSupported": True,
             "translationSupported": fault != "unsupported", "basis": "Direct source evidence.",
             "expectedTaskType": "direct_detail", "answerScopeSupported": True,
             "newMeaningSupported": True, "attributesSupported": True, "historyQuote": None}
    if fault == "quote":
        audit["sourceQuote"] = "invented quote"
    if fault == "index":
        audit["index"] = 1
    if fault == "type":
        audit["expectedTaskType"] = "condition"
    if fault == "unknown-type":
        audit["expectedTaskType"] = None
    if fault in ("scope", "novelty", "attributes"):
        audit[{"scope": "answerScopeSupported", "novelty": "newMeaningSupported",
               "attributes": "attributesSupported"}[fault]] = False
    if fault == "history":
        audit["historyQuote"] = "invented historical quote"
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


@pytest.mark.asyncio
async def test_web_recheck_without_hard_validation_never_dispatches(campaign, monkeypatch):
    path, _ = campaign
    manifest, _ = collect.load(path)
    row, _, _ = review_fixture(path)
    row.pop("hardPass")
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs: pytest.fail("Must not call Web"))
    with pytest.raises(DatasetError, match="before dispatch"):
        await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
    assert not (path / "sent-index.jsonl").exists()


@pytest.mark.asyncio
@pytest.mark.parametrize("has_history", [True, False])
@pytest.mark.parametrize("history_source", ["history", "previouslyTestedDetails"])
async def test_semantic_audit_accepts_grounded_repetition_or_missing_history(campaign, monkeypatch, has_history, history_source):
    path, _ = campaign
    manifest, _ = collect.load(path)
    row, _, _ = review_fixture(path)
    row["context"]["history"] = []
    row["context"][history_source] = ([{"utteranceEn" if history_source == "history" else
        "sourceUtteranceEn": "The shop opens at ten."}] if has_history else [])
    audit = {"index": 0, "sourceQuote": "opens at ten", "claimSupported": True,
             "translationSupported": True, "expectedTaskType": "direct_detail",
             "answerScopeSupported": True, "newMeaningSupported": False if has_history else None,
             "attributesSupported": True, "historyQuote": "opens at ten" if has_history else None,
             "basis": "Opening time was already asked." if has_history else "Earlier tested meanings are unavailable."}
    verdict = {"id": row["id"], **dict.fromkeys(collect.CHECKS, True), "newMeanings": audit["newMeaningSupported"],
        "issues": [{"check": "newMeanings", "sourceRef": "/task/utteranceEn",
                    "severity": "major" if has_history else "uncertain", "explanation": audit["basis"]}], "factAudit": [audit]}
    original = httpx.AsyncClient
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs: original(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps({"items": [verdict]})}}]})), **kwargs))
    result = await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
    assert result["checks"]["newMeanings"] is audit["newMeaningSupported"]


def test_collection_v2_preserves_frozen_contract_and_supplies_b2_demand(tmp_path):
    from training.freeze_candidate import freeze_collection_v2
    old_path = gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v1-20261023.json"
    old_bytes = old_path.read_bytes()
    old = gen.read_json(old_path)
    new = freeze_collection_v2(tmp_path / "v2.json")
    assert new["sha256"] == gen.digest({k: v for k, v in new.items() if k != "sha256"})
    assert old_path.read_bytes() == old_bytes
    for key in ("contentSchemas", "contentSchema", "translationSchema", "levelProfiles",
                "fewShotExamples", "semanticRubric", "collectionPolicy"):
        assert new[key] == old[key]
    assert not new["metricReport"]["gate"]["releaseAllowed"]
    group = {"groupId": "fresh/tickets/standard", "scenarioId": "tickets", "branchId": "standard",
             "level": "B2", "input": {"history": []}}
    context = gen.make_context(group, 4, [], new, 0)
    assert "levelDemand" in context
    payload = run.judge_payload({"context": context, "task": {}}, "item", {"rubricVersion": new["semanticRubric"]["version"]})
    assert payload["items"][0]["context"]["levelDemand"] == context["levelDemand"]
    with pytest.raises(FileExistsError):
        freeze_collection_v2(tmp_path / "v2.json")


def test_collection_v3_keeps_contract_and_adds_valid_b2_example(tmp_path):
    from jsonschema import Draft202012Validator
    from training.freeze_candidate import freeze_collection_v3
    old_path = gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v2-20261023.json"
    old_bytes = old_path.read_bytes()
    old = gen.read_json(old_path)
    new = freeze_collection_v3(tmp_path / "v3.json")
    assert old_path.read_bytes() == old_bytes
    assert new["sha256"] == gen.digest({k: v for k, v in new.items() if k != "sha256"})
    for key in ("contentSchemas", "contentSchema", "translationSchema", "levelProfiles",
                "semanticRubric", "collectionPolicy"):
        assert new[key] == old[key]
    assert new["fewShotExamples"][:-2] == old["fewShotExamples"]
    example = new["fewShotExamples"][-1]
    assert example["input"]["difficulty"] == "B2"
    Draft202012Validator(new["contentSchemas"]["B2"]).validate(example["output"])
    task = {**example["output"], "translationVi": "Bản dịch kiểm tra hợp đồng.",
            "facts": [{**fact, "textVi": "Đáp án kiểm tra."} for fact in example["output"]["facts"]]}
    assert not validate_training_task(task, "B2", new, example["input"]["levelProfile"])
    for turn in (2, 3, 4):
        context = {**example["input"], "turnNumber": turn}
        messages = gen.production_messages(new, context, "content")
        assert example["output"]["utteranceEn"] in messages[0]["content"]
    assert not new["metricReport"]["gate"]["releaseAllowed"]
    with pytest.raises(FileExistsError):
        freeze_collection_v3(tmp_path / "v3.json")


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", [None, "unknown-ref", "legacy-quote", "false-type", "false-novelty"])
async def test_web_issue_reference_preserves_original_and_rejects_false_audit(campaign, monkeypatch, fault):
    path, _ = campaign
    manifest, _ = collect.load(path)
    assert manifest["useIssueReferences"]
    row, _, _ = review_fixture(path)
    row["task"]["questionVi"] = "Khách đến khi nào, và khách hỏi điều kiện gì?"
    audit = {"index": 0, "sourceQuote": "opens at ten", "claimSupported": True,
        "translationSupported": True, "basis": "Inspect all requested meanings independently.",
        "expectedTaskType": "direct_detail", "answerScopeSupported": False,
        "newMeaningSupported": True, "attributesSupported": True, "historyQuote": None}
    check = {"false-type": "taskTypeAccurate", "false-novelty": "newMeanings"}.get(fault, "questionFactsAligned")
    if fault in ("false-type", "false-novelty"):
        audit["answerScopeSupported"] = True
    issue = {"check": check, "sourceRef": "/task/questionVi", "severity": "major",
             "explanation": "The additional asked prerequisite has no matching answer."}
    if fault == "unknown-ref":
        issue["sourceRef"] = "/rubric/example"
    if fault == "legacy-quote":
        issue.pop("sourceRef")
        issue["sourceQuote"] = "Khách hỏi điều kiện gì?"
    verdict = {"id": row["id"], **dict.fromkeys(collect.CHECKS, True), check: False,
               "issues": [issue], "factAudit": [audit]}
    original = httpx.AsyncClient
    calls = []
    def respond(request):
        calls.append(request)
        prompt = json.loads(request.content)["messages"][0]["content"]
        schema = json.loads(prompt.split("\nOUTPUT_SCHEMA:\n", 1)[1].split("\nAUDIT_INPUT:\n", 1)[0])
        properties = schema["properties"]["items"]["items"]["properties"]
        assert list(properties)[:2] == ["id", "factAudit"]
        assert "/task/questionVi" in properties["issues"]["items"]["properties"]["sourceRef"]["enum"]
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop",
            "message": {"content": json.dumps({"items": [verdict]}, ensure_ascii=False)}}]})
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs: original(
        transport=httpx.MockTransport(respond), **kwargs))
    if fault:
        with pytest.raises(DatasetError):
            await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
        assert not (path / "candidates" / f'{row["id"]}.result.json').exists()
    else:
        result = await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
        assert result["issues"][0]["sourceQuote"] == row["task"]["questionVi"]
        assert result["issueReferences"] == ["/task/questionVi"]
        assert run._review_integrity(row, result, manifest)
    envelope = gen.read_json(path / "candidates" / f'{row["id"]}.envelope.json')
    assert json.loads(envelope["choices"][0]["message"]["content"])["items"][0] == verdict
    assert len(calls) == 1


def test_judge_source_paths_only_address_original_values():
    case = {"task": {"utteranceEn": "Original text", "slots": [{"askVi": "Câu hỏi"}]},
            "context": {"a/b~c": "Giữ NGUYÊN", "short": "x", "number": 12}}
    original = copy.deepcopy(case)
    fields = run.judge_source_fields(case)
    assert fields == {"/context/a~1b~0c": "Giữ NGUYÊN", "/task/slots/0/askVi": "Câu hỏi",
                      "/task/utteranceEn": "Original text"}
    assert case == original


@pytest.mark.asyncio
async def test_web_can_report_wrong_role_in_task_instruction(campaign, monkeypatch):
    path, _ = campaign
    manifest, _ = collect.load(path)
    row, _, _ = review_fixture(path)
    row["context"]["partnerRole"] = "server"
    row["task"]["taskVi"] = "Nghe máy chủ giới thiệu món ăn."
    audit = {"index": 0, "sourceQuote": "opens at ten", "claimSupported": True,
        "translationSupported": True, "basis": "The fact is supported; the task instruction has a wrong role.",
        "expectedTaskType": "direct_detail", "answerScopeSupported": True,
        "newMeaningSupported": True, "attributesSupported": True, "historyQuote": None}
    verdict = {"id": row["id"], **dict.fromkeys(collect.CHECKS, True), "rolesCorrect": False,
        "factAudit": [audit], "issues": [{"check": "rolesCorrect", "severity": "critical",
            "sourceRef": "/task/taskVi", "explanation": "A restaurant server is not a computer server."}]}
    original = httpx.AsyncClient
    def respond(request):
        prompt = json.loads(request.content)["messages"][0]["content"]
        payload = json.loads(prompt.split("\nAUDIT_INPUT:\n", 1)[1])
        assert payload["items"][0]["task"]["taskVi"] == row["task"]["taskVi"]
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop",
            "message": {"content": json.dumps({"items": [verdict]}, ensure_ascii=False)}}]})
    monkeypatch.setattr(run.httpx, "AsyncClient", lambda **kwargs: original(
        transport=httpx.MockTransport(respond), **kwargs))
    result = await run._judge_one(path, manifest, row, row["id"], row["id"], None, folder=path / "candidates")
    assert result["checks"]["rolesCorrect"] is False and result["issues"][0]["severity"] == "critical"
    assert result["issues"][0]["sourceQuote"] == row["task"]["taskVi"]


def test_collection_v4_plans_questions_without_changing_contract(tmp_path):
    from training.freeze_candidate import freeze_collection_v4
    old = gen.read_json(gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v3-20261023.json")
    new = freeze_collection_v4(tmp_path / "v4.json")
    for key in ("contentSchemas", "translationSchema", "levelProfiles", "semanticRubric", "collectionPolicy"):
        assert new[key] == old[key]
    group = {"level": "B2", "scenarioId": "requirements", "branchId": "standard",
             "groupId": "new/requirements/standard", "input": {"history": [], "scenario": "Buổi thực hành"}}
    context = gen.make_context(group, 4, [], new, 0)
    plan = context["authoringPlan"]
    assert len(plan) == 3 and plan[0]["taskType"] == "argument_relation"
    assert len({item["index"] for item in plan}) == 3
    assert all(item["taskType"] in context["allowedTaskTypes"] for item in plan)
    assert all(item["questionForm"] for item in plan)
    assert context["diversityBrief"] in new["diversityBriefs"]
    assert context == gen.make_context(group, 4, [], new, 999)
    assert "Question/answer type cards" in gen.production_messages(new, context, "content")[0]["content"]
    group["level"] = "A1"
    context = gen.make_context(group, 4, [], new, 0)
    assert [item["taskType"] for item in context["authoringPlan"]] == ["direct_detail"] * 2
    assert not new["metricReport"]["gate"]["releaseAllowed"]
    example = new["fewShotExamples"][0]["output"]
    item = {"id": "planned-record", "content": example,
            "translation": {"translationVi": "Bản dịch thử nghiệm.",
                "facts": [{"textVi": "Đáp án thử nghiệm."} for _ in example["facts"]]}}
    task, records = gen.build_records(item, context,
        {**group, "scenario": "Buổi thực hành"}, new, "planned-review.json")
    assert len(records) == 2 and task["facts"]


def test_aligned_target_wire_projects_verbatim_before_full_contract_validation(tmp_path):
    from jsonschema import Draft202012Validator, ValidationError
    from training.freeze_candidate import freeze_collection_v5
    artifact = freeze_collection_v5(tmp_path / "v5.json")
    example = artifact["fewShotExamples"][0]["output"]
    content = {"utteranceEn": example["utteranceEn"], "taskVi": example["taskVi"], "skill": example["skill"],
        "targets": [{**slot, **fact} for slot, fact in zip(example["slots"], example["facts"])]}
    # The new protocol explicitly authors complete questions, not semantic headings.
    for target, question in zip(content["targets"], ("Khách nhận đồ uống ở đâu?", "Khách cần mang theo gì?")):
        target["askVi"] = question
    item = {"id": "aligned-example", "content": content,
        "translation": {"translationVi": "Bản dịch kiểm tra.",
            "facts": [{"textVi": "Đáp án kiểm tra."} for _ in content["targets"]]}}
    original = copy.deepcopy(item)
    Draft202012Validator(artifact["teacherContentSchemas"]["A1"]).validate(content)
    projected = gen.assemble_teacher_item(item, artifact)
    assert item == original
    assert projected["content"]["questionVi"] == " ".join(t["askVi"] for t in content["targets"])
    assert projected["content"]["facts"] == example["facts"]
    group = {"level": "A1", "scenarioId": "requirements", "branchId": "standard",
             "groupId": "aligned/requirements/standard", "input": {"history": [], "scenario": "Thực hành"}}
    context = gen.make_context(group, 4, [], artifact, 0)
    task, records = gen.build_records(projected, context, group, artifact, "review.json")
    assert len(records) == 2 and task["questionVi"] == projected["content"]["questionVi"]
    with pytest.raises(ValidationError):
        Draft202012Validator(artifact["teacherContentSchemas"]["A1"]).validate(
            {**content, "questionVi": "Unexpected second authored question"})
    assert gen.teacher_batch_system(artifact) != gen.TEACHER_BATCH
    assert gen.teacher_batch_system({}) == gen.TEACHER_BATCH


def test_v6_candidate_keeps_contract_and_requires_explicit_selection(tmp_path, monkeypatch, campaign):
    from training.freeze_candidate import freeze_collection_v6
    old_path = gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v5-20261023.json"
    original = old_path.read_bytes()
    old = gen.read_json(old_path)
    artifact_path = tmp_path / "v6.json"
    new = freeze_collection_v6(artifact_path)
    assert old_path.read_bytes() == original
    for key in ("contentSchemas", "teacherContentSchemas", "teacherWireFormat", "translationSchema",
                "levelProfiles", "semanticRubric", "collectionPolicy"):
        assert new[key] == old[key]
    assert new["sha256"] == gen.digest({k: v for k, v in new.items() if k != "sha256"})
    assert not new["metricReport"]["gate"]["releaseAllowed"]
    assert collect.ARTIFACT.name == "conversation-reviewed-collection-v8-20261023.json"
    monkeypatch.setattr(run, "PRIVATE_ROOT", tmp_path)
    monkeypatch.setattr(collect, "prior_corpus", lambda: [])
    path = tmp_path / "explicit-candidate"
    collect.prepare(path, campaign[1], diagnostic=True, artifact_path=artifact_path)
    manifest, loaded = collect.load(path)
    assert loaded == new and manifest["artifactPath"] == str(artifact_path.resolve())
    assert manifest["teacherWrapperHash"] == gen.digest(gen.teacher_batch_system(new))


def test_v7_changes_only_teacher_decoding_and_freezes_it(tmp_path, monkeypatch, campaign):
    from training.freeze_candidate import freeze_collection_v7
    old = gen.read_json(gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v6-20261023.json")
    artifact_path = tmp_path / "v7.json"
    new = freeze_collection_v7(artifact_path)
    for key in ("instruction", "teacherInstruction", "contentSchemas", "teacherContentSchemas",
                "translationSchema", "levelProfiles", "semanticRubric", "collectionPolicy"):
        assert new[key] == old[key]
    assert new["teacherThinkingMode"] == "enabled"
    assert new["sha256"] == gen.digest({k: v for k, v in new.items() if k != "sha256"})
    assert not new["metricReport"]["releaseAllowed"]
    monkeypatch.setattr(collect, "prior_corpus", lambda: [])
    path = tmp_path / "thinking-candidate"
    collect.prepare(path, campaign[1], diagnostic=True, artifact_path=artifact_path)
    manifest, _ = collect.load(path)
    assert manifest["teacher"]["thinkingMode"] == "enabled"


@pytest.mark.asyncio
async def test_teacher_thinking_must_match_manifest_before_sending(tmp_path):
    artifact = {"teacherThinkingMode": "enabled"}
    manifest = {"teacher": {"thinkingMode": "disabled"}}
    with pytest.raises(DatasetError, match="thinking mode"):
        await run._teacher_one(tmp_path, manifest, {"id": "new", "context": {}}, {}, artifact, tmp_path, None)
    assert not (tmp_path / "sent-index.jsonl").exists()


def test_previous_sources_remain_interpretable_without_carrying_encounter_forward(tmp_path):
    from training.freeze_candidate import freeze_collection_v8
    artifact = freeze_collection_v8(tmp_path / "v8.json")
    prior = gen.read_json(gen.ROOT / "artifacts/prompts/conversation-reviewed-collection-v7-20261023.json")
    for key in ("contentSchemas", "teacherContentSchemas", "translationSchema", "levelProfiles", "semanticRubric"):
        assert artifact[key] == prior[key]
    group = {"level": "A1", "scenarioId": "requirements", "branchId": "standard",
        "groupId": "old/requirements/standard", "input": {"scenario": "Buổi thực hành", "history": [{
            "utteranceEn": "Bring a notebook. Ask Ms Green for help.",
            "questionVi": "Cần mang gì và hỏi ai?",
            "facts": [{"textVi": "Sổ ghi chép", "evidenceEn": "Bring a notebook", "taskType": "direct_detail"}]}]}}
    original = copy.deepcopy(group)
    context = gen.make_context(group, 4, [], artifact, 0)
    assert not context["history"] and context["encounterState"]["encounterRule"] == "new"
    detail = context["previouslyTestedDetails"][0]
    assert detail["sourceUtteranceEn"] == group["input"]["history"][0]["utteranceEn"]
    assert detail["sourceQuestionVi"] == "Cần mang gì và hỏi ai?"
    assert detail["evidenceEn"] == "Bring a notebook" and detail["taskType"] == "direct_detail"
    assert group == original
    assert gen.make_context(group, 4, [], prior, 0)["previouslyTestedDetails"] == [{"textVi": "Sổ ghi chép"}]
    example = artifact["fewShotExamples"][0]["output"]
    task, records = gen.build_records({"id": "rich-context", "content": example,
        "translation": {"translationVi": "Bản dịch kiểm tra.",
            "facts": [{"textVi": "Đáp án kiểm tra."} for _ in example["facts"]]}},
        context, group, artifact, "review.json")
    assert len(records) == 2 and task["facts"]
