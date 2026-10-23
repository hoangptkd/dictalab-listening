from __future__ import annotations

import csv
import copy
import json
from pathlib import Path

import pytest

from training.prepare_dataset import DatasetError, ROOT, digest, prepare


ARTIFACT_PATH = ROOT / "artifacts/prompts/conversation-cefr-b1-v7-20261021.json"
ARTIFACT = json.loads(ARTIFACT_PATH.read_text(encoding="utf-8"))


def _row(index: int, *, group: str | None = None, purpose: str = "content") -> dict:
    content = {
        "slots": [{"kind": "room", "askVi": "Phòng nào?"}, {"kind": "time", "askVi": "Mấy giờ?"}],
        "utteranceEn": f"The meeting is in room {index}. It starts at ten.",
        "facts": [{"textEn": f"room {index}", "evidenceEn": f"room {index}", "taskType": "direct_detail"},
                  {"textEn": "ten", "evidenceEn": "ten", "taskType": "direct_detail"}],
        "questionVi": "Cuộc họp ở phòng nào và bắt đầu lúc mấy giờ?",
        "taskVi": "Nghe thông tin cuộc họp.", "skill": "Nắm thông tin cụ thể",
    }
    translated = {"translationVi": f"Cuộc họp ở phòng {index}. Bắt đầu lúc mười giờ.",
                  "facts": [{"textVi": f"phòng {index}"}, {"textVi": "mười giờ"}]}
    target = content if purpose == "content" else translated
    profile = ARTIFACT["levelProfiles"]["A1"]["wordBounds"]
    system = ARTIFACT["translationSystem"]
    context = {"utteranceEn": content["utteranceEn"], "facts": content["facts"],
               "partnerRole": "organizer", "learnerRole": "visitor"}
    if purpose == "content":
        examples = [e for e in ARTIFACT["fewShotExamples"] if e["input"]["difficulty"] == "A1"
                    and e["input"]["turnNumber"] == 2]
        system = ARTIFACT["instruction"] + "\n" + ARTIFACT["contentSystem"]
        system += "\nExamples are format only: " + json.dumps(examples, ensure_ascii=False)
        context = {"difficulty": "A1", "turnNumber": 2, "scenario": f"meeting-{index}",
                   "levelProfile": profile, "partnerRole": "organizer", "learnerRole": "visitor"}
    schema = (ARTIFACT.get("contentSchemas", {}).get("A1", ARTIFACT["contentSchema"])
              if purpose == "content" else ARTIFACT["translationSchema"])
    messages = [{"role": "system", "content": system},
                {"role": "user", "content": json.dumps(context, ensure_ascii=False)},
                {"role": "assistant", "content": json.dumps(target, ensure_ascii=False)}]
    return {
        "id": f"row-{index}-{purpose}", "groupId": group or f"group-{index}", "purpose": purpose,
        "scenario": f"meeting-{index}", "branchId": "standard", "level": "A1", "turnNumber": 2,
        "levelProfile": profile, "source": "synthetic-test", "semanticReviewReference": "fixture-review",
        "reviewVersion": "test-v1", "repaired": False, "attempt": 1,
        "inputHash": digest(messages[:-1]), "outputHash": digest(target),
        "artifactVersion": ARTIFACT["version"], "promptHash": digest(system), "schemaHash": digest(schema),
        "targetMessageIndex": 2, "lossMaskPolicy": "last-assistant-only", "messages": messages,
        "validationTask": {**content, "translationVi": translated["translationVi"],
                           "facts": [{**a, **b} for a, b in zip(content["facts"], translated["facts"])]},
    }


def _write(tmp_path: Path, rows: list[dict], decisions=None):
    source, review = tmp_path / "source.jsonl", tmp_path / "decisions.csv"
    source.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    with review.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["id", "decision", "reviewer", "inputHash", "outputHash"])
        writer.writeheader()
        for row in rows:
            writer.writerow({"id": row["id"], "decision": (decisions or {}).get(row["id"], "approved"),
                             "reviewer": "fixture-reviewer", "inputHash": row.get("inputHash", ""),
                             "outputHash": row.get("outputHash", "")})
    return source, review


def _prepare(source, review, output, **kwargs):
    return prepare([source], review, output, artifact_path=ARTIFACT_PATH, **kwargs)


@pytest.mark.parametrize("decision", ["", "needs_adjudication", "pending"])
def test_unresolved_review_fails_before_output(tmp_path, decision):
    row = _row(1)
    source, review = _write(tmp_path, [row], {row["id"]: decision})
    with pytest.raises(DatasetError, match="human review"):
        _prepare(source, review, tmp_path / "out")
    assert not (tmp_path / "out").exists()


def test_multitask_split_is_deterministic_and_keeps_group_together(tmp_path):
    rows = [_row(i, purpose=p) for i in range(30) for p in ("content", "translation")]
    source, review = _write(tmp_path, rows)
    first = _prepare(source, review, tmp_path / "out", forced_test_groups={"group-0"})
    source, review = _write(tmp_path, list(reversed(rows)))
    second = _prepare(source, review, tmp_path / "other", forced_test_groups={"group-0"})
    assert first["datasetHash"] == second["datasetHash"]
    assert first["datasetReady"] is True
    assert first["trainingReady"] is False
    assert sum(first["counts"].values()) == 60
    assert "group-0" in first["groups"]["test"]
    groups = [set(first["groups"][s]) for s in ("train", "validation", "test")]
    assert not groups[0] & groups[1] and not groups[0] & groups[2] and not groups[1] & groups[2]
    saved = json.loads((tmp_path / "out/train.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert saved["reviewerReference"] == "fixture-reviewer"
    with pytest.raises(DatasetError, match="refusing to overwrite"):
        _prepare(source, review, tmp_path / "out")


def test_mixed_frozen_artifacts_preserve_original_prompts_and_validate_each_row(tmp_path):
    from training.prepare_dataset import content_system
    new = copy.deepcopy(ARTIFACT)
    new.update(version="collection-continuation-fixture", instruction=new["instruction"] + "\nNew frozen instructions.")
    new["sha256"] = digest({k: v for k, v in new.items() if k != "sha256"})
    artifact_path = tmp_path / "new.json"
    artifact_path.write_text(json.dumps(new, ensure_ascii=False), encoding="utf-8")
    rows = [_row(i) for i in range(30)]
    for row in rows[::2]:
        system = content_system(new, row["level"], row["turnNumber"])
        row["artifactVersion"] = new["version"]
        row["messages"][0]["content"] = system
        row["promptHash"] = digest(system)
        row["inputHash"] = digest(row["messages"][:-1])
    original = copy.deepcopy(rows)
    source, review = _write(tmp_path, rows)
    with pytest.raises(DatasetError, match="artifact/system prompt mismatch"):
        prepare([source], review, tmp_path / "without-parent", artifact_path=artifact_path)
    result = prepare([source], review, tmp_path / "mixed", artifact_path=artifact_path,
                     extra_artifact_paths=(ARTIFACT_PATH,))
    assert result["contract"]["artifacts"] == {new["version"]: new["sha256"], ARTIFACT["version"]: ARTIFACT["sha256"]}
    saved = [json.loads(line) for split in ("train", "validation", "test")
             for line in (tmp_path / "mixed" / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
    expected = {row["id"]: row for row in original}
    assert len(saved) == 30
    for row in saved:
        assert row["messages"] == expected[row["id"]]["messages"]
        assert row["inputHash"] == expected[row["id"]]["inputHash"]
    rows[0]["messages"][0]["content"] += "Corrupted."
    source, review = _write(tmp_path, rows)
    with pytest.raises(DatasetError, match="artifact/system prompt mismatch"):
        prepare([source], review, tmp_path / "corrupt", artifact_path=artifact_path,
                extra_artifact_paths=(ARTIFACT_PATH,))


@pytest.mark.parametrize("fault", ["hash", "schema", "shadow"])
def test_inherited_artifact_never_relaxes_contract(tmp_path, fault):
    old = copy.deepcopy(ARTIFACT)
    old["version"] = "incompatible-parent"
    if fault == "schema":
        old["contentSchema"]["additionalProperties"] = True
    if fault == "shadow":
        old["evaluationOnly"] = True
    old["sha256"] = "invalid" if fault == "hash" else digest({k: v for k, v in old.items() if k != "sha256"})
    parent_path = tmp_path / "parent.json"
    parent_path.write_text(json.dumps(old, ensure_ascii=False), encoding="utf-8")
    source, review = _write(tmp_path, [_row(i) for i in range(30)])
    with pytest.raises(DatasetError, match="inherited artifact"):
        _prepare(source, review, tmp_path / "out", extra_artifact_paths=(parent_path,))


def test_delegated_ai_is_explicit_and_never_reported_as_human(tmp_path):
    rows = [_row(i, purpose=p) for i in range(30) for p in ("content", "translation")]
    source, review = _write(tmp_path, rows)
    review.write_text(review.read_text(encoding="utf-8").replace("fixture-reviewer", "AI:Codex"), encoding="utf-8")
    with pytest.raises(DatasetError, match="delegated-ai"):
        _prepare(source, review, tmp_path / "out")
    manifest = _prepare(source, review, tmp_path / "out", review_policy="delegated-ai")
    assert manifest["reviewAuthority"] == "delegated-ai" and manifest["humanApproved"] is False
    saved = json.loads((tmp_path / "out/train.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert saved["reviewerType"] == "ai"


@pytest.mark.parametrize("field,value,match", [
    ("developmentOnly", True, "cannot enter SFT"),
    ("schemaHash", "wrong", "schemaHash mismatch"),
    ("inputHash", "wrong", "inputHash mismatch"),
    ("artifactVersion", "old-v2", "artifact/system"),
    ("level", "B2", "P0 requires"),
    ("repaired", True, "first-attempt"),
    ("semanticReviewReference", "", "semanticReviewReference"),
    ("lossMaskPolicy", "all-assistants", "lossMaskPolicy"),
])
def test_contract_rejects_invalid_metadata(tmp_path, field, value, match):
    row = _row(1)
    row[field] = value
    source, review = _write(tmp_path, [row])
    with pytest.raises(DatasetError, match=match):
        _prepare(source, review, tmp_path / "out")


def test_review_is_bound_to_output_hash(tmp_path):
    row = _row(1)
    source, review = _write(tmp_path, [row])
    review.write_text(review.read_text(encoding="utf-8").replace(row["outputHash"], "old-hash"), encoding="utf-8")
    with pytest.raises(DatasetError, match="human review outputHash"):
        _prepare(source, review, tmp_path / "out")


@pytest.mark.parametrize("mutation,match", [
    (lambda c: c.update(utteranceEn="Short."), "production validator"),
    (lambda c: c["facts"][0].update(evidenceEn="not in transcript"), "production validator"),
    (lambda c: c.update(hiddenRationale="extra"), "artifact schema"),
    (lambda c: c.update(questionVi="Phòng 1 hay phòng 2?"), "production validator"),
])
def test_real_validator_and_schema_reject_invalid_targets(tmp_path, mutation, match):
    row = _row(1)
    target = json.loads(row["messages"][-1]["content"])
    mutation(target)
    row["messages"][-1]["content"] = json.dumps(target, ensure_ascii=False)
    row["outputHash"] = digest(target)
    row["validationTask"].update({k: v for k, v in target.items() if k != "facts"})
    row["validationTask"]["facts"] = [{**a, "textVi": b["textVi"]}
        for a, b in zip(target["facts"], row["validationTask"]["facts"])]
    if "Phòng" in target["questionVi"]:
        row["validationTask"]["facts"][0]["textVi"] = "Phòng 1"
    source, review = _write(tmp_path, [row])
    with pytest.raises(DatasetError, match=match):
        _prepare(source, review, tmp_path / "out")


def test_rejected_group_excludes_dependent_records(tmp_path):
    rows = [_row(i) for i in range(30)] + [_row(0, purpose="translation")]
    source, review = _write(tmp_path, rows, {rows[0]["id"]: "rejected"})
    result = _prepare(source, review, tmp_path / "out")
    assert result["dependentGroupsExcluded"] == ["row-0-translation"]
    assert sum(result["counts"].values()) == 29


def test_duplicate_across_groups_fails_closed(tmp_path):
    a, b = _row(1), _row(1, group="another-group")
    b.update(id="copy", scenario="other")
    source, review = _write(tmp_path, [a, b])
    with pytest.raises(DatasetError, match="duplicate across groups"):
        _prepare(source, review, tmp_path / "out")


def test_empty_split_fails_without_output(tmp_path):
    source, review = _write(tmp_path, [_row(1)])
    with pytest.raises(DatasetError, match="empty split"):
        _prepare(source, review, tmp_path / "out")
    assert not (tmp_path / "out").exists()


def test_credentials_in_json_context_are_rejected(tmp_path):
    row = _row(1)
    context = json.loads(row["messages"][1]["content"])
    context["password"] = "fixture-not-a-real-secret"
    row["messages"][1]["content"] = json.dumps(context, ensure_ascii=False)
    row["inputHash"] = digest(row["messages"][:-1])
    source, review = _write(tmp_path, [row])
    with pytest.raises(DatasetError, match="possible credential"):
        _prepare(source, review, tmp_path / "out")
