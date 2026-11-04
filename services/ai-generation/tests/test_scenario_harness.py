"""Agent-authored synthetic regressions; no network, models, or human-gold claims."""
import asyncio
import copy
import json
import time
from contextlib import contextmanager
from unittest.mock import patch

import pytest

from app import observability as obs, scenario_harness as h, scenario_workflow as w
from app.config import Settings
from app.providers.base import ProviderError
from test_scenario_workflow import personal_payload, personal_responses, request


BASE = ["GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW"]


def run(script, *, payload=None, hook=None):
    calls, deadlines = [], []
    value = payload or personal_payload(promptHash=w.personalized_prompt_hash(w.harness_release))

    class Gateway:
        def __init__(self, settings, client):
            self.last_model = "offline-model"

        async def complete(self, messages, schema, deadline, purpose, **options):
            stage = purpose.removeprefix("scenario_").upper()
            release = w.personalized_release(value.promptHash)
            assert messages[0]["content"] == release.INSTRUCTIONS[stage]
            data = json.loads(messages[1]["content"])
            calls.append((stage, data))
            deadlines.append(deadline)
            expected, response = script[len(calls) - 1]
            assert stage == expected
            if hook:
                hook(stage, data)
            if isinstance(response, Exception):
                raise response
            return copy.deepcopy(response), "ollama"

    with patch.object(w, "ModelGateway", Gateway):
        result = asyncio.run(w.execute(request(value), Settings(scenario_enabled=True)))
    assert len(calls) == len(script)
    assert len(set(deadlines)) == 1  # Every patch/recheck keeps the original monotonic deadline.
    return result, calls


def qpatch(responses, activity=0, question=0, **changes):
    q = copy.deepcopy(responses[1]["activities"][activity]["questions"][question])
    q.update(changes)
    return {"replacements": [{"activityId": responses[1]["activities"][activity]["id"], "question": q}]}


def tpatch(responses, index=0, **changes):
    return {"replacements": [{**responses[3]["segments"][index], **changes}]}


def test_good_lesson_has_five_calls_and_separate_historical_release():
    responses = personal_responses()
    result, calls = run(list(zip(BASE, responses)))
    assert [c[0] for c in calls] == BASE
    assert result["qualityHarness"]["extraCalls"] == 0
    assert result["task"]["qualityPassed"] is True
    assert result["promptHash"] == "a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b"
    assert w.personalized_prompt_hash(w.personalized_fewshot) == "defd36c45a53aa6ac5002d0acb50996a5790fd87554646e36f9830dd3a225504"
    assert all(w.harness_release.INSTRUCTIONS[k] == v for k, v in w.personalized_fewshot.INSTRUCTIONS.items())
    assert w.harness_release.GRADE == w.personalized_fewshot.GRADE
    assert "qualityHarness" not in result["task"]


@pytest.mark.parametrize("fault", ["wrong_activity", "agreement_order"])
def test_deterministic_question_repairs_only_target_and_never_regenerates(fault):
    good = personal_responses()
    bad = copy.deepcopy(good[1])
    index = 0 if fault == "wrong_activity" else 1
    q = bad["activities"][0]["questions"][index]
    if fault == "wrong_activity":
        q["evidence"] = copy.deepcopy(good[1]["activities"][1]["questions"][0]["evidence"])
    else:
        q["speechAct"] = "agreement"
        q["evidence"].reverse()
    result, calls = run([(BASE[0], good[0]), (BASE[1], bad),
        ("REPAIR_QUESTIONS", qpatch(good, question=index)),
        (BASE[2], good[2]), (BASE[3], good[3]), (BASE[4], good[4])])
    report = result["qualityHarness"]
    assert report["extraCalls"] == 2  # First semantic reviewer replaces a skipped base reviewer.
    assert report["attempts"][0]["accepted"]
    assert report["attempts"][0]["originalHash"] != report["attempts"][0]["candidateHash"]
    assert calls[2][1]["failedOutput"] == bad
    assert calls[2][1]["allowedTargets"] == [{"activityId": "a1", "questionId": f"q{index + 1}"}]
    assert [a["segments"] for a in result["task"]["activities"]] == [a["segments"] for a in good[0]["activities"]]
    assert result["task"]["qualityPassed"]


@pytest.mark.parametrize("flag,wrong", [
    ("groundingSupported", {"evidence": [{"segmentId": "s1", "quoteEn": "I need to know when you open."}]}),
    ("vietnameseNatural", {"answerVi": "Giờ chín cái mở"}),
])
def test_semantic_question_flags_trigger_typed_repair(flag, wrong):
    good = personal_responses()
    bad = copy.deepcopy(good[1]); bad["activities"][0]["questions"][0].update(wrong)
    review = copy.deepcopy(good[2])
    review["questionChecks"][0][flag] = False
    result, calls = run([(BASE[0], good[0]), (BASE[1], bad), (BASE[2], review),
        ("REPAIR_QUESTIONS", qpatch(good)), (BASE[2], good[2]),
        (BASE[3], good[3]), (BASE[4], good[4])])
    assert calls[3][1]["failedOutput"] == bad
    assert result["task"]["activities"][0]["questions"][0]["answerVi"] == good[1]["activities"][0]["questions"][0]["answerVi"]
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["qualityHarness"]["extraCalls"] == 2


@pytest.mark.parametrize("flag,wrong", [
    ("meaningPreserved", "Tôi đã nhận bánh hôm qua và cửa hàng đã đóng cửa."),
    ("terminologyCorrect", "Tôi muốn đặt một ổ thịt và nhận vào ngày mai."),
    ("vietnameseNatural", "Tôi muôn đặt báh và nhận vào ngài mai."),
])
def test_semantic_translation_faults_repair_without_changing_questions(flag, wrong):
    good = personal_responses()
    bad = copy.deepcopy(good[3]); bad["segments"][0]["textVi"] = wrong
    review = copy.deepcopy(good[4])
    review["translationChecks"][0][flag] = False
    result, calls = run(list(zip(BASE[:3], good[:3])) + [(BASE[3], bad), (BASE[4], review),
        ("REPAIR_TRANSLATIONS", tpatch(good)), (BASE[4], good[4])])
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert calls[5][1]["failedOutput"] == bad
    assert result["task"]["translations"][0]["textVi"] == good[3]["segments"][0]["textVi"]
    assert sum(stage == "QUESTIONS" for stage, _ in calls) == 1
    assert result["qualityHarness"]["artifacts"]["questions"] == h.digest(w.PersonalizedQuestions.model_validate(good[1]))


@pytest.mark.parametrize("fault", ["unknown", "duplicate", "missing", "untargeted"])
def test_patch_allowlist_preserves_original_on_scope_violation(fault):
    good = personal_responses()
    original = w.PersonalizedQuestions.model_validate(good[1])
    before = h.digest(original)
    patch_data = qpatch(good, answerVi="Sáng mai")
    if fault == "unknown": patch_data["replacements"][0]["activityId"] = "a9"
    elif fault == "duplicate": patch_data["replacements"] *= 2
    elif fault == "missing": patch_data["replacements"] = []
    else: patch_data["replacements"].append(qpatch(good, activity=1)["replacements"][0])
    with pytest.raises(ValueError):
        h.merge_patch(original, w.QuestionPatch.model_validate(patch_data), {("a1", "q1")}, "QUESTIONS")
    assert h.digest(original) == before


@pytest.mark.parametrize("fault", ["regression", "invalid_recheck", "unchanged"])
def test_rejected_candidate_keeps_original_and_does_not_repeat_repair(fault):
    good = personal_responses()
    review = copy.deepcopy(good[2])
    review["questionChecks"][0]["vietnameseNatural"] = False
    patch_data = qpatch(good, **({} if fault == "unchanged" else {"answerVi": "Vào sáng ngày mai"}))
    script = list(zip(BASE[:2], good[:2])) + [(BASE[2], review), ("REPAIR_QUESTIONS", patch_data)]
    if fault != "unchanged":
        recheck = copy.deepcopy(good[2])
        if fault == "regression": recheck["questionChecks"][1]["groundingSupported"] = False
        else: recheck = {}
        script.append((BASE[2], recheck))
    result, _ = run(script + list(zip(BASE[3:], good[3:])))
    assert result["task"]["activities"][0]["questions"][0]["answerVi"] == good[1]["activities"][0]["questions"][0]["answerVi"]
    assert result["task"]["qualityPassed"] is False
    assert not result["qualityHarness"]["attempts"][0]["accepted"]
    if fault == "unchanged": assert result["qualityHarness"]["attempts"][0]["reason"] == "unchanged_patch"


@pytest.mark.parametrize("first", ["schema", "coverage", "long_reason"])
def test_reviewer_format_retry_once_even_when_second_result_has_different_fault(first):
    good = personal_responses()
    invalid = copy.deepcopy(good[2])
    if first == "schema": invalid = {}
    elif first == "coverage": invalid["questionChecks"][0]["activityId"] = "a9"
    else: invalid["questionChecks"][0]["reasonVi"] = "x" * 2000
    bad_coverage = copy.deepcopy(good[2])
    bad_coverage["questionChecks"][0]["activityId"] = "a9"
    result, calls = run(list(zip(BASE[:2], good[:2])) + [(BASE[2], invalid), (BASE[2], bad_coverage)] + list(zip(BASE[3:], good[3:])))
    assert len(calls) == 6 and result["qualityHarness"]["extraCalls"] == 1
    assert "formatFeedback" in calls[3][1]
    assert result["reviewAdvisory"]["questionsStatus"] == "UNKNOWN"
    assert result["task"]["qualityPassed"] is False


def test_four_extra_calls_are_shared_and_insufficient_repair_budget_is_reported():
    good = personal_responses()
    qreview = copy.deepcopy(good[2]); qreview["questionChecks"][0]["vietnameseNatural"] = False
    treview = copy.deepcopy(good[4]); treview["translationChecks"][0]["meaningPreserved"] = False
    result, calls = run(list(zip(BASE[:2], good[:2])) + [(BASE[2], {}), (BASE[2], qreview),
        ("REPAIR_QUESTIONS", qpatch(good, answerVi="Vào sáng mai")), (BASE[2], good[2]),
        (BASE[3], good[3]), (BASE[4], {}), (BASE[4], treview)])
    report = result["qualityHarness"]
    assert len(calls) == 9 and report["extraCalls"] == 4
    assert report["attempts"][-1]["reason"] == "budget_or_deadline"
    assert report["attempts"][-1]["attempted"] is False
    assert result["task"]["qualityPassed"] is False


def test_expired_deadline_cannot_start_a_repair():
    state = h.State(time.monotonic() - 1)
    assert not state.available(2)
    with pytest.raises(w.QualityRejected, match="budget or deadline"):
        asyncio.run(state.extra(None, "REPAIR_QUESTIONS", {}, w.QuestionPatch))
    assert state.extra_calls == 0


def test_mutating_immutable_specification_is_rejected():
    value = personal_payload()
    def mutate(stage, data):
        if stage == "TRANSLATE": value.specification.settingVi = "Unexpected mutation"
    with pytest.raises(w.QualityRejected, match="immutable harness artifact"):
        run(list(zip(BASE, personal_responses())), payload=value, hook=mutate)


def test_translation_patch_preserves_untargeted_items_and_original():
    good = personal_responses()
    original = w.Translations.model_validate(good[3])
    before = h.digest(original)
    replacement = w.TranslationPatch.model_validate(tpatch(good, textVi="Bản dịch mới"))
    first = original.segments[0]
    candidate = h.merge_patch(original, replacement, {(first.activityId, first.segmentId)}, "TRANSLATE")
    assert candidate.segments[1:] == original.segments[1:]
    assert h.digest(original) == before and h.digest(candidate) != before


@pytest.mark.parametrize("failure", [ProviderError("provider parse/transport failure"), TimeoutError("deadline")])
def test_provider_unknown_propagates_without_retry_or_full_replay(failure):
    good = personal_responses()
    with pytest.raises(type(failure)):
        run(list(zip(BASE[:2], good[:2])) + [(BASE[2], failure)])


def test_v7_invalid_review_remains_advisory_without_v8_retry():
    good = personal_responses(); good[2] = {}
    result, calls = run(list(zip(BASE, good)), payload=personal_payload(promptHash=w.personalized_prompt_hash(w.personalized_fewshot)))
    assert len(calls) == 5 and "qualityHarness" not in result
    assert result["task"]["qualityPassed"] is False


def test_optional_trace_content_off_preserves_outputs_and_records_extra_spans(monkeypatch):
    good = personal_responses()
    script = list(zip(BASE[:2], good[:2])) + [(BASE[2], {}), (BASE[2], good[2])] + list(zip(BASE[3:], good[3:]))
    expected, _ = run(script)
    rows = []

    class Span:
        def update(self, **data): rows.append(data)
        def end(self): pass

    @contextmanager
    def observe(name, **data):
        rows.append({"name": name, "input": obs.content(data.get("input")), "metadata": data.get("metadata")})
        yield Span()

    monkeypatch.setenv("DICTALAB_AI_TRACE_CONTENT", "false")
    monkeypatch.setattr(h, "observation", observe)
    actual, _ = run(script)
    for result in (actual, expected):
        result.pop("generationId"); result.pop("requestId")
    assert actual == expected
    assert any(r.get("metadata", {}).get("callClass") == "extra" for r in rows if r.get("metadata"))
    assert all(r["output"] == {"capture": "disabled"} for r in rows if "output" in r)
    assert "textEn" not in json.dumps(rows)


def test_fatal_rejection_keeps_final_state_and_invalid_candidate_hash(monkeypatch):
    good = personal_responses()
    bad = copy.deepcopy(good[1]); bad["activities"][0]["questions"][0]["evidence"][0]["quoteEn"] = "Wrong activity source"
    rows = []

    class Span:
        def update(self, **data): rows.append(data)

    @contextmanager
    def observe(*args, **kwargs): yield Span()

    monkeypatch.setenv("DICTALAB_AI_TRACE_CONTENT", "true")
    monkeypatch.setattr(h, "observation", observe)
    with pytest.raises(w.QualityRejected):
        run(list(zip(BASE[:1], good[:1])) + [(BASE[1], bad), ("REPAIR_QUESTIONS", qpatch(good, evidence=[{"segmentId": "s1", "quoteEn": "Still wrong"}]))])
    final = next(r["output"] for r in reversed(rows) if "outcome" in r.get("output", {}))
    assert final["outcome"] == "FAILED_OR_UNKNOWN"
    assert final["attempts"][0]["reason"] == "deterministic_validation"
    assert rows[-1]["metadata"]["lastCandidateHash"] != rows[-1]["metadata"]["lastOriginalHash"]
