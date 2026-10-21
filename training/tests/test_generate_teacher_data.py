from __future__ import annotations

import copy
import csv
import json
import re

import pytest

from training.generate_teacher_data import (CHECKS, Gemini, build_records, export_review,
    judge_pass, make_context, plan_groups, roles, unpack_batch)
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
        client.call("gemini-3.8-flash", "system", {}, "batch")
    with pytest.raises(DatasetError, match="request cap"):
        client.call("gemini-4-argon", "system", {}, "another-batch")


def test_cached_provider_response_requires_matching_input_and_output_hashes(tmp_path):
    client = object.__new__(Gemini)
    client.output = tmp_path
    wire = {"model": "gemini-3.8-flash", "messages": [{"role": "system", "content": "system"},
                                                       {"role": "user", "content": "{}"}]}
    result = {"items": []}
    (tmp_path / "batch.request.json").write_text(json.dumps(wire), encoding="utf-8")
    response = {"result": result, "requestHash": digest(wire), "responseHash": digest(result)}
    (tmp_path / "batch.response.json").write_text(json.dumps(response), encoding="utf-8")
    assert client.call("gemini-3.8-flash", "system", {}, "batch") == result
    with pytest.raises(DatasetError, match="integrity mismatch"):
        client.call("gemini-3.8-flash", "changed-system", {}, "batch")
    response["result"] = {"items": [{"id": "tampered"}]}
    (tmp_path / "batch.response.json").write_text(json.dumps(response), encoding="utf-8")
    with pytest.raises(DatasetError, match="integrity mismatch"):
        client.call("gemini-3.8-flash", "system", {}, "batch")


def test_review_export_does_not_fabricate_human_approval_and_excludes_failed_groups(tmp_path):
    item, context = _item()
    task, records = build_records(item, context, _group(), ARTIFACT, "review")
    candidate = {"id": item["id"], "eligible": True, "group": _group(), "context": context,
                 "task": task, "records": records, "verdict": {}}
    summary = export_review(tmp_path, [candidate], ARTIFACT, ["cafe/standard/A1"])
    assert summary["pendingTargetRecords"] == 2
    assert not summary["datasetReady"] and not summary["trainingReady"]
    with (tmp_path / "human-review-decisions.csv").open(encoding="utf-8", newline="") as handle:
        decisions = list(csv.DictReader(handle))
    assert all(row["decision"] == "pending" and row["reviewer"] == "" for row in decisions)
    failed_dir = tmp_path / "failed"
    failed_dir.mkdir()
    summary = export_review(failed_dir, [candidate, {"eligible": False, "group": _group()}], ARTIFACT, [])
    assert summary["pendingTargetRecords"] == 0
