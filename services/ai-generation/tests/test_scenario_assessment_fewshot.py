"""Candidate/reference contracts, not measurements of live model quality."""
import copy
import json
import tomllib
from pathlib import Path
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app import scenario_assessment as a, scenario_workflow as w
from test_scenario_assessment import execute
from test_scenario_assessment_parts import parts_fixtures, parts_payload

RELEASE = a.scoped_fewshot_candidate
PIN = "66f97b88e087e528f71b6e1f7bcec1224361b2edea94623ce93e4b5c1cf1189b"
BASELINE_PIN = "b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087"


def test_candidate_is_pinned_stage_local_and_does_not_replace_baseline():
    assert a.prompt_hash(RELEASE) == PIN and a.selected_release(PIN) is RELEASE
    assert a.prompt_hash(a.scoped_release) == BASELINE_PIN
    assert a.prompt_hash() == "650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e"
    assert len(RELEASE.EXAMPLES) == len({e["id"] for e in RELEASE.EXAMPLES}) == 25
    assert RELEASE.ASSET["labelSource"] == "agent-authored-reference-not-human-gold"
    assert RELEASE.OUTPUT_TOKENS == a.scoped_release.OUTPUT_TOKENS
    assert RELEASE.HARNESS_POLICY == a.scoped_release.HARNESS_POLICY
    assert not getattr(RELEASE, "AUDIT_PROTOCOL", None)
    for stage, ids in RELEASE.EXAMPLE_IDS.items():
        assert ids and RELEASE.INSTRUCTIONS[stage].startswith(a.scoped_release.INSTRUCTIONS[stage])
        assert len(RELEASE.INSTRUCTIONS[stage].encode()) < 20000
    for example in RELEASE.ASSET["examples"]:
        serialized = json.dumps(example, ensure_ascii=False, separators=(",", ":"))
        assert [stage for stage, text in RELEASE.INSTRUCTIONS.items() if serialized in text] == [example["stage"]]
    root = Path(__file__).resolve().parents[3]
    packaging = tomllib.loads((root / "services/ai-generation/pyproject.toml").read_text(encoding="utf-8"))
    assert "scenario_assessment_fewshot_v1.json" in packaging["tool"]["setuptools"]["package-data"]["app"]
    assert f'PARTS_V4_14_PROMPT_HASH="{PIN}"' in (root / "src/main/java/com/dictalab/scenario/ScenarioAssessment.java").read_text(encoding="utf-8")
    assert (root / "src/main/resources/scenario/workflow-v3.sha256").read_text().strip() == a.prompt_hash()


@pytest.mark.parametrize("example", RELEASE.EXAMPLES, ids=lambda e: e["id"])
def test_example_output_matches_its_declared_scope_and_source(example):
    stage, data, expected = example["stage"], example["input"], example["expectedOutput"]
    if stage == "EXTRACT":
        output = w.PersonalizedExtraction.model_validate(expected)
        w.validate_extraction(output, data["inputVi"])
    elif stage == "GENERATE":
        output = w.Dialogue.model_validate(expected)
        w.validate_dialogue(output, w.PersonalizedSpecification.model_validate(data["specification"]),
                            w.PersonalizedProfile.model_validate(data["profile"]), flexible=True)
    elif stage == "QUESTIONS":
        # Item-level examples are not a valid complete runtime question bank.
        with pytest.raises(ValidationError):
            a.PartsQuestions.model_validate(expected)
        context = SimpleNamespace(profile=SimpleNamespace(**data["learningContext"]["profile"]))
        sources = {act["id"]: act for act in data["dialogue"]["activities"]}
        for activity in expected["activities"]:
            segments = {s["id"]: w.Segment.model_validate(s) for s in sources[activity["id"]]["segments"]}
            meanings = {m["id"]: a.PartsMeaning.model_validate(m) for m in activity["assessment"]["meanings"]}
            for meaning in meanings.values():
                a._support(meaning, context, activity["id"], "meaning")
                a._evidence(meaning, segments, activity["id"], "meaning")
                for part in meaning.requiredParts:
                    a._evidence(part, segments, activity["id"], "meaning")
                    assert all(any(p.segmentId == m.segmentId and p.quoteEn in m.quoteEn for m in meaning.evidence) for p in part.evidence)
            for raw in activity["questions"]:
                question = a.QuestionDraft.model_validate(raw)
                a._support(question, context, activity["id"], "question")
                a._evidence(question, segments, activity["id"], "question")
                assert set(question.meaningIds) <= meanings.keys()
                assert all(any(me.segmentId == qe.segmentId and me.quoteEn in qe.quoteEn for qe in question.evidence)
                           for mid in question.meaningIds for me in meanings[mid].evidence)
                if question.speechAct == "agreement":
                    assert len({segments[e.segmentId].speakerId for e in question.evidence}) == 2
    elif stage == "REVIEW_QUESTIONS":
        rows = [a.MeaningCheck.model_validate(row) for row in expected.get("meaningChecks", [])]
        rows += [w.QuestionEvidenceCheck.model_validate(row) for row in expected["questionChecks"]]
        rejected = any(value is False for row in rows for value in row.model_dump().values())
        assert expected["valid"] is (not rejected)
        assert bool(expected["issues"]) is rejected
        with pytest.raises(ValidationError):
            a.QuestionReview.model_validate(expected)
    elif stage == "GRADE":
        output = a.PartsGrade.model_validate(expected)
        submission = a.Submission.model_validate(data["assessmentSubmission"])
        targets = {m["id"]: m for m in data["activity"]["assessment"]["meanings"]}
        answers = {r.questionId: r.textVi for r in submission.answers} if submission.mode == "CHECKS" else {"recap": submission.recapText}
        assert {m.meaningId for m in output.meanings} == set(submission.meaningIds)
        for meaning in output.meanings:
            assert {p.partId for p in meaning.parts} == {p["id"] for p in targets[meaning.meaningId]["requiredParts"]}
            for part in meaning.parts:
                assert (part.status == "ABSENT") is (not part.evidence)
                assert all(e.quote in answers[e.responseId] for e in part.evidence)
    elif stage == "TRANSLATE":
        translations = [w.Translation.model_validate(t) for t in expected["segments"]]
        assert {(t.activityId, t.segmentId) for t in translations} == {(act["id"], s["id"]) for act in data["activities"] for s in act["segments"]}
    elif stage == "REVIEW":
        w.PersonalizedReview.model_validate(expected)
        assert len(expected["rubricChecks"]) == 5
    elif stage == "REPAIR_QUESTIONS":
        patch = a.PartsQuestionPatch.model_validate(expected)
        assert {(r.activityId, r.question.id) for r in patch.questionReplacements} == {(t["activityId"], t["itemId"]) for t in data["allowedTargets"]}
        assert not patch.meaningReplacements
    else:
        patch = w.TranslationPatch.model_validate(expected)
        assert {(r.activityId, r.segmentId) for r in patch.replacements} == {(t["activityId"], t["itemId"]) for t in data["allowedTargets"]}


def test_review_examples_include_acceptance_and_precise_failure_labels():
    examples = {e["id"]: e for e in RELEASE.ASSET["examples"]}
    checks = {key: e["expectedOutput"] for key, e in examples.items() if e["stage"] == "REVIEW_QUESTIONS"}
    assert sum(r["valid"] for r in checks.values()) == 2
    assert checks["review-questions-wrong-reference"]["questionChecks"][0]["answerSupported"] is False
    assert checks["review-questions-wrong-reference"]["questionChecks"][0]["answerUnitsVi"] == ["Phòng số năm"]
    given = checks["review-questions-given-context-not-target"]["questionChecks"][0]
    assert given["answerSupported"] and not given["questionMatchesFact"]
    assert checks["review-questions-proposal-is-not-agreement"]["questionChecks"][0]["speechActSupported"] is False
    assert checks["review-questions-duplicate-meaning"]["questionChecks"][1]["distinctMeaning"] is False
    assert checks["review-questions-parent-part-mismatch"]["questionChecks"][0]["questionMatchesFact"] is False
    assert examples["questions-given-reason-place-only"]["expectedOutput"]["activities"][0]["assessment"]["meanings"][0]["requiredParts"] == [
        {"id": "p1", "meaningEn": "Mira chose the riverside cafe.", "evidence": [{"segmentId": "s1", "quoteEn": "I chose the riverside cafe because it is quiet."}]}]


def grade_payload(example):
    """Build a complete stored activity around the exact projected demonstration."""
    data = parts_payload("GRADE", promptHash=PIN).model_dump()
    activity = data["activity"]
    incoming = example["input"]
    activity["id"] = incoming["activity"]["id"]
    template = copy.deepcopy(activity["assessment"]["meanings"][0])
    for target in incoming["activity"]["assessment"]["meanings"]:
        meaning = {**copy.deepcopy(template), **copy.deepcopy(target)}
        for part in meaning["requiredParts"]:
            part["evidence"] = copy.deepcopy(template["evidence"])
        activity["assessment"]["meanings"][int(target["id"][1:]) - 1] = meaning
    for question in incoming["activity"]["questions"]:
        activity["questions"][int(question["id"][1:]) - 1].update(question)
    data["assessmentSubmission"] = incoming["assessmentSubmission"]
    return a.ScenarioAssessmentInput.model_validate(data)


@pytest.mark.parametrize("example", [e for e in RELEASE.EXAMPLES if e["stage"] == "GRADE"], ids=lambda e: e["id"])
def test_grade_demonstration_uses_real_projection_and_unchanged_aggregation(example):
    value = grade_payload(example)
    result, calls = execute(value, [example["expectedOutput"]])
    assert len(calls) == 1 and json.loads(calls[0][1][1]["content"]) == example["input"]
    assert calls[0][1][0]["content"] == RELEASE.INSTRUCTIONS["GRADE"]
    statuses = [m["status"] for m in result["task"]["meanings"]]
    assert statuses == {
        "grade-correct-place-wrong-time": ["PARTIAL"],
        "grade-place-only-given-reason": ["CAPTURED"],
        "grade-unresolved-alternatives": ["UNCERTAIN"],
        "grade-recap-omission": ["PARTIAL", "UNASSESSED", "UNASSESSED"],
        "grade-partial-condition-and-blank": ["PARTIAL", "MISSED"],
    }[example["id"]]
    assert result["calls"][0]["exampleIds"] == RELEASE.EXAMPLE_IDS["GRADE"]


def test_actual_generation_and_extraction_messages_use_only_their_stage_examples():
    result, calls = execute(parts_payload(promptHash=PIN), parts_fixtures())
    assert len(calls) == 5 and result["task"]["qualityPassed"]
    for recorded, (purpose, messages, _, _) in zip(result["calls"], calls):
        stage = purpose.removeprefix("scenario_").upper()
        assert messages[0]["content"] == RELEASE.INSTRUCTIONS[stage]
        assert recorded["exampleIds"] == RELEASE.EXAMPLE_IDS[stage]
        assert recorded["exampleSetHash"] == RELEASE.EXAMPLE_SET_HASH
    review = json.loads(calls[2][1][1]["content"])
    assert set(review) == {"content", "learningContext"}
    assert review["content"]["activities"][0]["questions"][0]["speechAct"] == "statement"
    example = next(e for e in RELEASE.EXAMPLES if e["stage"] == "EXTRACT")
    _, calls = execute(parts_payload("EXTRACT", promptHash=PIN, inputVi=example["input"]["inputVi"]), [example["expectedOutput"]])
    assert calls[0][1][0]["content"] == RELEASE.INSTRUCTIONS["EXTRACT"]


@pytest.mark.parametrize("stage", ["QUESTIONS", "TRANSLATE"])
def test_repair_messages_use_candidate_examples_without_reintroducing_audits(stage):
    base = parts_fixtures()
    if stage == "QUESTIONS":
        failed = copy.deepcopy(base[2])
        failed["valid"] = False
        failed["questionChecks"][0].update(answerSupported=False, reasonVi="Đáp án chưa đủ.")
        fixed = copy.deepcopy(base[1]["activities"][0]["questions"][0])
        fixed["answerVi"] = "Chín giờ."
        patch = {"meaningReplacements": [], "questionReplacements": [{"activityId": "a1", "question": fixed}]}
        responses = [*base[:2], failed, patch, base[2], *base[3:]]
    else:
        failed = copy.deepcopy(base[4])
        failed["valid"] = False
        failed["translationChecks"][0].update(meaningPreserved=False, reasonVi="Dịch thiếu nghĩa.")
        fixed = {**base[3]["segments"][0], "textVi": "Cửa hàng mở lúc chín giờ. Bánh mì giá sáu bảng. Hãy nhận bánh vào ngày mai."}
        responses = [*base[:4], failed, {"replacements": [fixed]}, base[4]]
    result, calls = execute(parts_payload(promptHash=PIN), responses)
    assert len(calls) == 7 and result["qualityHarness"]["extraCalls"] == 2
    purpose = "REPAIR_QUESTIONS" if stage == "QUESTIONS" else "REPAIR_TRANSLATIONS"
    assert next(messages[0]["content"] for name, messages, _, _ in calls if name == "scenario_" + purpose.lower()) == RELEASE.INSTRUCTIONS[purpose]
    assert next(row for row in result["calls"] if row["purpose"] == purpose)["exampleIds"] == RELEASE.EXAMPLE_IDS[purpose]


def test_new_demonstrations_are_not_copied_from_committed_evaluation_briefs():
    fixture_dir = Path(__file__).parent / "fixtures/evaluation"
    briefs = set()
    for path in fixture_dir.glob("scenario-*.json"):
        for case in json.loads(path.read_text(encoding="utf-8")).get("cases", []):
            briefs.add(case.get("inputVi", case.get("input", {}).get("inputVi")))
    assert all(e["input"]["inputVi"] not in briefs for e in RELEASE.ASSET["examples"] if "inputVi" in e["input"])
