from __future__ import annotations

import copy
import csv
import json
import re
from io import BytesIO
from types import SimpleNamespace

import pytest

from training.generate_teacher_data import (CHECKS, TEACHER, JUDGE, REQUEST_CAP, Gemini, build_records, export_review,
    authoring_rules, batch_schema, provider_schema, strict_json, judge_pass, make_context, plan_groups, roles, unpack_batch, run)
from jsonschema import Draft202012Validator
from training.prepare_dataset import DatasetError, ROOT, digest
from training.tests.test_prepare_dataset import ARTIFACT, _row


def _group():
    return {"level": "A1", "branchId": "standard", "groupId": "meeting/standard/A1"}


def _item():
    content = _row(37)
    translation = _row(37, purpose="translation")
    item = {"id": "meeting-A1-t2", "content": json.loads(content["messages"][-1]["content"]),
            "translation": json.loads(translation["messages"][-1]["content"])}
    context = json.loads(content["messages"][1]["content"])
    return item, context


def test_plan_covers_all_scenarios_levels_and_reserves_benchmark_groups():
    source = (ROOT / "src/main/java/com/dictalab/service/ConversationContent.java").read_text(encoding="utf-8")
    scenes = re.findall(r'new Scene\("([^"]+)"', source)
    catalogue = [{"scenarioId": scene, "level": level, "branchId": branch, "input": {}}
                 for scene in scenes for level in ("A1", "A2", "B1") for branch in ("standard", "alternative")]
    groups = plan_groups(catalogue)
    assert len(groups) == len({g["groupId"] for g in groups}) == 72
    assert all(len({g["scenarioId"] for g in groups if g["level"] == level}) == 24 for level in ("A1", "A2", "B1"))
    assert not any(g["scenarioId"] in {"cafe", "hotel", "directions", "plans", "delivery"}
                   and g["branchId"] == "standard" for g in groups)


def test_roles_match_authored_branch_overrides():
    assert roles("hotel", "quiet") == {"partnerRole": "guest", "learnerRole": "receptionist"}
    assert roles("cafe", "payment")["partnerRole"] == "server"
    assert roles("tickets", "collection")["partnerRole"] == "ticket clerk"


def test_exclusions_choose_unattempted_branches_and_never_reuse_exhausted_groups():
    catalogue = json.loads((ROOT / "data/conversation-distillation/production-contract.json").read_text(encoding="utf-8"))["catalogue"]
    original = plan_groups(catalogue)
    excluded = {group["groupId"] for group in original}
    remaining = plan_groups(catalogue, excluded)
    assert len(remaining) == 66  # plans/delivery have only one non-holdout branch per level.
    assert not ({group["groupId"] for group in remaining} & excluded)
    assert not any(group["scenarioId"] in {"cafe", "hotel", "directions", "plans", "delivery"}
                   and group["branchId"] == "standard" for group in remaining)
    exhausted = {f"{row['scenarioId']}/{row['branchId']}/{row['level']}" for row in catalogue}
    assert plan_groups(catalogue, exhausted) == []
    with pytest.raises(DatasetError, match="outside the frozen catalogue"):
        plan_groups(catalogue, {"unknown/branch/B1"})


@pytest.mark.parametrize("level,expected", [("A1", (16, 26, 2)), ("A2", (29, 43, 3)), ("B1", (48, 72, 3))])
def test_teacher_drafting_targets_stay_inside_authoritative_bounds(level, expected):
    context = {"difficulty": level, "levelProfile": ARTIFACT["levelProfiles"][level]["wordBounds"]}
    original = copy.deepcopy(context)
    assert tuple(authoring_rules(context).values()) == expected
    assert context == original
    context["levelProfile"] = {"minWords": 30, "maxWords": 30}
    assert authoring_rules(context)["draftMinWords"] == authoring_rules(context)["draftMaxWords"] == 30


def test_targeted_plan_filters_levels_before_any_api_call(tmp_path):
    summary = run(tmp_path / "targeted", prepare_only=True, levels=("B1", "A2"))
    assert summary["plannedTasks"] == 144 and not summary["trainingReady"]
    plan = json.loads((tmp_path / "targeted/generation-plan.json").read_text(encoding="utf-8"))
    assert plan["levels"] == ["B1", "A2"]
    assert {group["level"] for group in plan["groups"]} == {"A2", "B1"}
    assert not (tmp_path / "targeted/api-reservations.jsonl").exists()
    for levels in ((), ("B1", "B1"), ("C1",)):
        with pytest.raises(DatasetError, match="levels must be distinct"):
            run(tmp_path / "invalid", prepare_only=True, levels=levels)


def test_turn_four_removes_old_story_and_preserves_only_answer_avoidance():
    group = {**_group(), "scenarioId": "meeting", "input": {"history": [{"utteranceEn": "OLD STORY",
        "facts": [{"textVi": "phòng cũ"}]}], "scenario": "meeting", "scenarioJourney": "OLD JOURNEY",
        "sessionBlueprint": {"journey": "OLD BLUEPRINT"}, "recentPractice": ["OLD PRACTICE"]}}
    context = make_context(group, 4, [], ARTIFACT, 0)
    assert context["history"] == []
    assert context["previouslyTestedDetails"] == [{"textVi": "phòng cũ"}]
    assert all(key not in context for key in ("scenarioJourney", "sessionBlueprint", "recentPractice"))
    assert "OLD" not in json.dumps(context)


def test_exported_pairs_pass_current_contract_without_altering_targets():
    item, context = _item()
    original = copy.deepcopy(item)
    task, records = build_records(item, context, _group(), ARTIFACT, "judge-A1.response.json")
    assert item == original
    assert len(records) == 2 and task["facts"][0]["textEn"] and task["facts"][0]["textVi"]
    assert records[0]["messages"] == _row(37)["messages"]
    expected = _row(37, purpose="translation")["messages"]
    assert records[1]["messages"][0] == expected[0]
    assert json.loads(records[1]["messages"][1]["content"]) == json.loads(expected[1]["content"])
    assert records[1]["messages"][2] == expected[2]
    assert records[0]["teacherProvenance"]["productionWireRequest"] is False


def test_mismatched_translation_or_overlength_is_rejected_without_repair():
    item, context = _item()
    item["translation"]["facts"].pop()
    with pytest.raises(DatasetError, match="number of facts"):
        build_records(item, context, _group(), ARTIFACT, "review")
    item, context = _item()
    item["content"]["utteranceEn"] += " extra" * 50
    with pytest.raises(DatasetError, match="word_count"):
        build_records(item, context, _group(), ARTIFACT, "review")


def test_batch_and_judge_contracts_fail_closed():
    with pytest.raises(DatasetError, match="ids/count/order"):
        unpack_batch({"items": [{"id": "b"}, {"id": "a"}]}, ["a", "b"])
    verdict = {"id": "a", "issues": [], **dict.fromkeys(CHECKS, True)}
    assert judge_pass(verdict)
    verdict["rolesCorrect"] = "true"
    with pytest.raises(DatasetError, match="invalid verdict"):
        judge_pass(verdict)
    verdict["rolesCorrect"] = True
    verdict["issues"] = ["wrong role"]
    assert not judge_pass(verdict)


def test_interrupted_requests_are_not_retried_or_switched_to_other_models(tmp_path):
    client = object.__new__(Gemini)
    client.output = tmp_path
    (tmp_path / "batch.request.json").write_text("{}", encoding="utf-8")
    with pytest.raises(DatasetError, match="refusing automatic retry"):
        client.call(TEACHER, "system", {}, "batch")
    with pytest.raises(DatasetError, match="request cap"):
        client.call("gemini-4-argon", "system", {}, "another-batch")


def test_cached_provider_response_requires_matching_input_and_output_hashes(tmp_path):
    client = object.__new__(Gemini)
    client.output = tmp_path
    wire = {"model": TEACHER, "messages": [{"role": "system", "content": "system"},
        {"role": "user", "content": "{}"}],
        "response_format": {"type": "json_schema", "json_schema": {"schema": {"type": "object"}}}}
    result = {"items": []}
    (tmp_path / "batch.request.json").write_text(json.dumps(wire), encoding="utf-8")
    response = {"result": result, "requestHash": digest(wire), "responseHash": digest(result)}
    (tmp_path / "batch.response.json").write_text(json.dumps(response), encoding="utf-8")
    assert client.call(TEACHER, "system", {}, "batch") == result
    with pytest.raises(DatasetError, match="integrity mismatch"):
        client.call(TEACHER, "changed-system", {}, "batch")
    response["result"] = {"items": [{"id": "tampered"}]}
    (tmp_path / "batch.response.json").write_text(json.dumps(response), encoding="utf-8")
    with pytest.raises(DatasetError, match="integrity mismatch"):
        client.call(TEACHER, "system", {}, "batch")


def test_teacher_and_judge_share_quota_but_use_separate_output_budgets(tmp_path, monkeypatch):
    client = object.__new__(Gemini)
    client.output, client.key = tmp_path, "test-only"
    envelope = {"choices": [{"finish_reason": "stop", "message": {"content": '{"items":[]}'}}]}
    client.opener = SimpleNamespace(open=lambda *a, **kw: BytesIO(json.dumps(envelope).encode()))
    monkeypatch.setattr("training.generate_teacher_data.time.sleep", lambda _: None)
    assert TEACHER == JUDGE
    client.call(TEACHER, "author", {}, "teacher-first")
    client.call(JUDGE, "audit", {}, "judge-first")
    wire = json.loads((tmp_path / "teacher-first.request.json").read_text())
    assert wire["max_tokens"] == 8192 and wire["response_format"]["type"] == "json_schema"
    assert wire["response_format"]["json_schema"]["strict"] is True
    assert json.loads((tmp_path / "judge-first.request.json").read_text())["max_tokens"] == 4096
    ledger = tmp_path / "api-reservations.jsonl"
    used = ledger.read_text().splitlines()
    ledger.write_text("\n".join(used + [used[0]] * (REQUEST_CAP - len(used))) + "\n", encoding="utf-8")
    for model, name in ((TEACHER, "teacher-next"), (JUDGE, "judge-next")):
        with pytest.raises(DatasetError, match="request cap"):
            client.call(model, "system", {}, name)
        assert not (tmp_path / f"{name}.request.json").exists()


def test_malformed_unicode_target_is_preserved_and_never_repaired_or_retried(tmp_path):
    client = object.__new__(Gemini)
    client.output, client.key = tmp_path, "test-only"
    malformed = r'{"items": [{"textVi": "bad \u00dt"}]}'
    envelope = {"choices": [{"finish_reason": "stop", "message": {"content": malformed}}]}
    client.opener = SimpleNamespace(open=lambda *a, **kw: BytesIO(json.dumps(envelope).encode()))
    with pytest.raises(DatasetError, match="invalid target JSON"):
        client.call(TEACHER, "author", {}, "teacher-bad")
    saved = json.loads((tmp_path / "teacher-bad.envelope.json").read_text())
    assert saved["choices"][0]["message"]["content"] == malformed
    failure = json.loads((tmp_path / "teacher-bad.invalid-json.json").read_text())
    assert failure["acceptedTargetRecords"] == 0 and failure["repaired"] is False
    assert not (tmp_path / "teacher-bad.response.json").exists()
    with pytest.raises(DatasetError, match="refusing automatic retry"):
        client.call(TEACHER, "author", {}, "teacher-bad")


@pytest.mark.parametrize("target", [r'{"x":"\ud800"}', r'{"x":"\ufffd"}',
    '{"x":1,"x":2}', '{"x":NaN}', '{"x":Infinity}'])
def test_ambiguous_or_corrupt_json_is_rejected(target):
    with pytest.raises(DatasetError):
        strict_json(target)


def test_valid_unicode_is_preserved_and_provider_schema_enforces_shape():
    target = {"textVi": "giờ, phòng, điều kiện"}
    assert strict_json(json.dumps(target, ensure_ascii=False)) == target
    assert strict_json(json.dumps(target, ensure_ascii=True)) == target
    verdict = {"id": "a", "issues": [], **dict.fromkeys(CHECKS, True)}
    schema = batch_schema(["a"])
    validator = Draft202012Validator(schema)
    validator.validate({"items": [verdict]})
    assert list(validator.iter_errors({"items": []}))
    assert list(validator.iter_errors({"items": [{**verdict, "id": "unknown"}]}))
    assert list(validator.iter_errors({"items": [{**verdict, "rolesCorrect": "true"}]}))
    item, _ = _item()
    item["id"] = "a"
    Draft202012Validator(batch_schema(["a"], ARTIFACT["contentSchema"], ARTIFACT["translationSchema"])).validate({"items": [item]})


def test_gemini_schema_subset_does_not_remove_local_production_constraints(tmp_path):
    schema = batch_schema(["a"], ARTIFACT["contentSchema"], ARTIFACT["translationSchema"])
    wire = provider_schema(schema)
    assert wire["properties"]["items"]["maxItems"] == 1
    assert "maxLength" not in json.dumps(wire) and "uniqueItems" not in json.dumps(wire)
    item, _ = _item()
    item["id"] = "a"
    item["translation"]["translationVi"] = "x" * 1201
    result = {"items": [item]}
    Draft202012Validator(wire).validate(result)
    assert list(Draft202012Validator(schema).iter_errors(result))
    client = object.__new__(Gemini)
    client.output, client.key = tmp_path, "test-only"
    envelope = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]}
    client.opener = SimpleNamespace(open=lambda *a, **kw: BytesIO(json.dumps(envelope).encode()))
    with pytest.raises(DatasetError, match="response schema failed"):
        client.call(TEACHER, "author", {}, "teacher-overlength", schema=schema)
    assert not (tmp_path / "teacher-overlength.response.json").exists()


def test_failed_batch_does_not_replay_or_discard_other_unattempted_groups(tmp_path, monkeypatch):
    import training.generate_teacher_data as module
    _, context = _item()
    context["history"] = []
    groups = [{**_group(), "scenarioId": "meeting", "level": level,
        "groupId": f"meeting/standard/{level}", "input": context} for level in ("A1", "A2")]
    catalogue = tmp_path / "catalogue.json"
    catalogue.write_text('{"catalogue":[]}', encoding="utf-8")
    monkeypatch.setattr(module, "CATALOGUE", catalogue)
    monkeypatch.setattr(module, "plan_groups", lambda *args: groups)
    calls = []
    class FailingCloud:
        def __init__(self, _):
            pass
        def call(self, model, system, data, name, **kwargs):
            calls.append(name)
            if name == "judge-calibration":
                return {"items": [{"id": item["id"], "issues": [] if index == 0 else ["seeded"],
                    **{key: index == 0 for key in CHECKS}} for index, item in enumerate(data["items"])]}
            raise DatasetError("HTTP 503; no retry")
    monkeypatch.setattr(module, "Gemini", FailingCloud)
    summary = run(tmp_path / "result")
    assert calls == ["judge-calibration", "teacher-A1-t2-b1", "teacher-A2-t2-b1"]
    assert summary["pendingTargetRecords"] == 0 and len(summary["excludedGroups"]) == 2
    assert not summary["trainingReady"]


def test_review_export_does_not_fabricate_human_approval_and_excludes_failed_groups(tmp_path):
    item, context = _item()
    task, records = build_records(item, context, _group(), ARTIFACT, "review")
    candidate = {"id": item["id"], "eligible": True, "group": _group(), "context": context,
                 "task": task, "records": records, "verdict": {}}
    summary = export_review(tmp_path, [candidate], ARTIFACT, ["cafe/standard/A1"])
    assert summary["pendingTargetRecords"] == 2
    assert not summary["datasetReady"] and not summary["trainingReady"]
    assert summary["independentJudgeModel"] is False
    with (tmp_path / "human-review-decisions.csv").open(encoding="utf-8", newline="") as handle:
        decisions = list(csv.DictReader(handle))
    assert all(row["decision"] == "pending" and row["reviewer"] == "" for row in decisions)
    failed_dir = tmp_path / "failed"
    failed_dir.mkdir()
    summary = export_review(failed_dir, [candidate, {"eligible": False, "group": _group()}], ARTIFACT, [])
    assert summary["pendingTargetRecords"] == 0
