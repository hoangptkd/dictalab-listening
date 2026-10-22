"""Materialize independently labeled v3 judge probes from private reviewed pilots.

Labels and mutations are curated here; the full generated tasks stay outside Git.
This file must not be used as a source of judge prompts or SFT examples.
"""
from __future__ import annotations

import copy
from pathlib import Path

from jsonschema import Draft202012Validator

from training import generate_teacher_data as gen
from training.iterate_teacher import DEFAULT_ARTIFACT, audit_gold
from training.semantic_rubric import VERSION_V3


SOURCES = {
    "v8": gen.ROOT / "data/fine-tuning/semantic-v8-ab-20261022",
    "v9": gen.ROOT / "data/fine-tuning/semantic-v9-pilot-20261022",
}
OUTPUT = gen.ROOT / "data/fine-tuning/teacher-gold-v3c.json"

# These judgments were reviewed against v3, including the new alignment and status rules.
PASS_V8 = ("blind-002", "blind-006", "blind-008", "blind-009", "blind-016", "blind-017",
           "blind-019", "blind-033", "blind-034", "blind-042", "blind-050", "chain-1-t2")
MAJOR_V8 = {
    "blind-004": "gist focus is absent; all requested answers are direct details",
    "blind-011": "sister is translated with unsupported relative age and speaker gender",
    "blind-015": "time answer adds an independently testable unasked location",
    "blind-026": "clock-time question is answered with an unasked day",
    "blind-027": "source gives three o'clock without morning/afternoon; translation adds afternoon",
    "blind-029": "requested action is mislabeled as a prerequisite condition",
    "blind-032": "sister becomes older sister without source evidence",
    "blind-037": "turn retests the same meaning with changed numbers",
    "blind-043": "opinion answer changes its object and adds unasked reasons",
    "blind-052": "food question has two ordered foods but the answer gives only one",
    "blind-054": "clock-time answer adds an unasked breakfast room",
    "blind-060": "required direct-detail focus is absent",
}
CRITICAL = {
    ("v8", "blind-001"): "ten to five is mistranslated as 9:55, changing a required time answer",
    ("v8", "blind-012"): "an unanswered fitting-room policy question becomes confirmed policy",
    ("v8", "blind-039"): "learner is the taxi dispatcher but UI addresses them as the passenger",
    ("v8", "blind-058"): "unknown stock status is presented as a confirmed answer",
    ("v8", "chain-5-t2"): "directly stated next action is mislabeled as inference",
    ("v9", "blind-005"): "seller's ability to sell a ticket becomes a completed purchase",
}
REVIEW = {
    ("v9", "blind-011"): "thanks for coming may be prospective or retrospective here",
    ("v8", "chain-2-t3"): "still at four may refer to rehearsal rather than the presentation",
}
CRITICAL_CHECK = {
    ("v8", "blind-001"): "translationFaithful",
    ("v8", "blind-012"): "informationStatusCorrect",
    ("v8", "blind-039"): "rolesCorrect",
    ("v8", "blind-058"): "informationStatusCorrect",
    ("v8", "chain-5-t2"): "reasoningGrounded",
    ("v9", "blind-005"): "informationStatusCorrect",
}


def _source(version: str, ident: str, expected: str, severity: str, note: str) -> dict:
    folder = SOURCES[version]
    candidate = gen.read_json(folder / f"{ident}.candidate.json")
    review = gen.read_json(folder / "reviews.json")[ident]
    if not candidate.get("hardPass") or review.get("reviewer") != "AI:Codex" or \
            review.get("taskHash") != gen.digest(candidate["task"]) or \
            review.get("contextHash") != gen.digest(candidate["context"]):
        raise gen.DatasetError(f"{version}/{ident}: source review hash or hard validation failed")
    expected_check = (next((key for key, value in review["checks"].items()
        if value is False), None) if expected == "FAIL" else
        next((key for key, value in review["checks"].items() if value is None), None)
        if expected == "REVIEW_REQUIRED" else None)
    return {"id": f"gold-{version}-{ident}", "sourceId": candidate["group"]["groupId"],
        "sourceReference": f"{version}/{ident}", "sourceReviewVersion": review["rubricVersion"],
        "sourceTaskHash": review["taskHash"], "sourceContextHash": review["contextHash"],
        "labelReviewer": "Codex independent v3 review 2026-10-23",
        "labelNote": note, "expected": expected, "expectedSeverity": severity,
        "expectedCheck": expected_check,
        "rubricVersion": VERSION_V3, "hardPass": True,
        "context": copy.deepcopy(candidate["context"]), "task": copy.deepcopy(candidate["task"])}


def _status_mutation() -> dict:
    row = _source("v8", "blind-008", "FAIL", "critical",
        "customer requests a window table; the restaurant has not confirmed one")
    row["id"] += "-confirmed-table"
    row["task"]["questionVi"] = "Nhà hàng đã xác nhận bàn ở vị trí nào, khách đến lúc mấy giờ và vì sao khách muốn đến sớm?"
    row["task"]["slots"][1]["askVi"] = "Vị trí bàn nhà hàng đã xác nhận"
    row["mutation"] = "request-to-confirmed-arrangement"
    row["expectedCheck"] = "informationStatusCorrect"
    return row


def _role_mutation() -> dict:
    row = _source("v8", "blind-006", "FAIL", "critical",
        "taxi dispatcher is addressed as the passenger seeking a pickup")
    row["id"] += "-listener-as-passenger"
    row["task"]["questionVi"] = "Người điều phối taxi muốn được đón ở đâu và có bao nhiêu hành lý?"
    row["task"]["slots"][0]["askVi"] = "Điểm người điều phối muốn được đón"
    row["mutation"] = "speaker-listener-inversion"
    row["expectedCheck"] = "rolesCorrect"
    return row


def _ambiguous_bank() -> dict:
    row = _source("v8", "blind-002", "REVIEW_REQUIRED", "none",
        "bank may mean a financial institution or a riverbank; context does not decide")
    row["id"] += "-bank-sense"
    task = row["task"]
    task["utteranceEn"] = task["utteranceEn"].replace(
        "at the bus stop near my house", "by the bank")
    task["translationVi"] = task["translationVi"].replace(
        "ở trạm xe buýt gần nhà mình", "gần ngân hàng")
    task["facts"][2].update(textEn="by the bank", textVi="gần ngân hàng",
                             evidenceEn="by the bank")
    row["mutation"] = "lexical-sense-underdetermined-bank"
    row["expectedCheck"] = "translationFaithful"
    return row


def _ambiguous_coach() -> dict:
    row = _source("v8", "blind-050", "REVIEW_REQUIRED", "none",
        "coach may mean a bus or a trainer; no event establishes which one")
    row["id"] += "-coach-sense"
    task = row["task"]
    task["utteranceEn"] = task["utteranceEn"].replace(
        "Before you reply,", "The coach arrives at noon. Before you reply,")
    task["translationVi"] = task["translationVi"].replace(
        "Trước khi trả lời,", "Xe khách đến lúc trưa. Trước khi trả lời,")
    row["mutation"] = "lexical-sense-underdetermined-coach"
    row["expectedCheck"] = "translationFaithful"
    return row


def build(output: Path = OUTPUT) -> dict:
    cases = []
    for ident in PASS_V8:
        cases.append(_source("v8", ident, "PASS", "none", "v3 role, alignment, status and translation re-reviewed"))
    for ident, note in MAJOR_V8.items():
        cases.append(_source("v8", ident, "FAIL", "major", note))
    for (version, ident), note in CRITICAL.items():
        row = _source(version, ident, "FAIL", "critical", note)
        row["expectedCheck"] = CRITICAL_CHECK[(version, ident)]
        cases.append(row)
    cases.extend((_status_mutation(), _role_mutation()))
    for (version, ident), note in REVIEW.items():
        cases.append(_source(version, ident, "REVIEW_REQUIRED", "none", note))
    cases.extend((_ambiguous_bank(), _ambiguous_coach()))
    artifact = gen.read_json(DEFAULT_ARTIFACT)
    for case in cases:
        task = case["task"]
        content = {key: task[key] for key in ("slots", "utteranceEn", "questionVi", "taskVi", "skill")}
        content["facts"] = [{key: value for key, value in fact.items() if key != "textVi"}
                            for fact in task["facts"]]
        translation = {"translationVi": task["translationVi"],
                       "facts": [{"textVi": fact["textVi"]} for fact in task["facts"]]}
        for value, schema in ((content, artifact["contentSchemas"][case["context"]["difficulty"]]),
                              (translation, artifact["translationSchema"])):
            if list(Draft202012Validator(schema).iter_errors(value)):
                raise gen.DatasetError(f"{case['id']}: gold target violates teacher schema")
    result = audit_gold(cases)
    gen.write_json(output, cases)
    return result


if __name__ == "__main__":
    import json
    print(json.dumps(build(), ensure_ascii=False))
