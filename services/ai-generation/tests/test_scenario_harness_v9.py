"""Regressions motivated by the frozen 30-case capture; no model or network calls."""
import copy

import pytest

from app import scenario_workflow as w, scenario_harness as h
from test_scenario_harness import BASE, run, qpatch, tpatch
from test_scenario_workflow import personal_payload, personal_responses


def test_v9_pin_preserves_base_generation_and_v8_history():
    result, calls = run(list(zip(BASE, personal_responses())), payload=personal_payload())
    assert len(calls) == 5 and result['task']['qualityPassed']
    assert result['qualityHarness']['policyVersion'] == 'scenario-quality-harness.v2'
    assert w.personalized_prompt_hash() == 'df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303'
    assert w.personalized_prompt_hash(w.harness_release) == 'a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b'
    for stage in ('EXTRACT', 'GENERATE', 'QUESTIONS', 'TRANSLATE'):
        assert w.harness_v9.INSTRUCTIONS[stage] == w.harness_release.INSTRUCTIONS[stage]
    assert w.harness_v9.GRADE == w.harness_release.GRADE


def test_question_repair_receives_actual_constraint_and_unchanged_source():
    good = personal_responses()
    bad = copy.deepcopy(good[1]); bad['activities'][0]['questions'][0]['evidence'][0]['quoteEn'] = 'Not in this activity'
    result, calls = run([(BASE[0], good[0]), (BASE[1], bad), ('REPAIR_QUESTIONS', qpatch(good)),
        (BASE[2], good[2]), (BASE[3], good[3]), (BASE[4], good[4])], payload=personal_payload())
    assert calls[2][1]['issues'][0]['constraint'] == 'unanchored fact evidence'
    assert calls[2][1]['dialogue'] == good[0]
    assert calls[3][1]['repairContext']['changedTargets'] == [{'activityId':'a1','questionId':'q1'}]
    assert result['qualityHarness']['attempts'][0]['accepted']


def test_partially_unchanged_patch_cannot_erase_a_previous_failure():
    good = personal_responses()
    bad = copy.deepcopy(good[3])
    bad['segments'][0]['textVi'] = 'Omitted important content'
    bad['segments'][1]['textVi'] = 'Wrong temporal scope'
    review = copy.deepcopy(good[4])
    for row in review['translationChecks'][:2]:
        row['meaningPreserved'] = False
        row['reasonVi'] = 'Translation narrows the time meaning in the English source.'
    patch = {'replacements':[good[3]['segments'][0], bad['segments'][1]]}
    result, calls = run(list(zip(BASE[:3], good[:3])) + [(BASE[3], bad), (BASE[4], review),
        ('REPAIR_TRANSLATIONS', patch), (BASE[4], good[4])], payload=personal_payload())
    assert calls[5][1]['issues'][0]['reasonVi'] == review['translationChecks'][0]['reasonVi']
    assert result['task']['translations'][0] == good[3]['segments'][0]
    assert result['task']['translations'][1] == bad['segments'][1]
    assert result['task']['qualityPassed'] is False
    verdict = result['reviewAdvisory']['review']
    assert verdict['translationChecks'][1]['meaningPreserved'] is False
    assert verdict['translationChecks'][1]['reasonVi'] == review['translationChecks'][1]['reasonVi']
    attempt = result['qualityHarness']['attempts'][0]
    assert attempt['accepted'] and attempt['reason'] == 'accepted_with_warnings'
    assert attempt['unchangedTargets'] == [{'activityId':bad['segments'][1]['activityId'], 'segmentId':bad['segments'][1]['segmentId']}]


def test_new_warning_on_untouched_segment_keeps_improvement_and_warning():
    good = personal_responses(); bad = copy.deepcopy(good[3])
    bad['segments'][0]['textVi'] = 'Broken translation'
    before = copy.deepcopy(good[4]); before['translationChecks'][0]['meaningPreserved'] = False
    after = copy.deepcopy(good[4]); after['translationChecks'][1]['vietnameseNatural'] = False
    result, _ = run(list(zip(BASE[:3], good[:3])) + [(BASE[3], bad), (BASE[4], before),
        ('REPAIR_TRANSLATIONS', tpatch(good)), (BASE[4], after)], payload=personal_payload())
    assert result['task']['translations'][0] == good[3]['segments'][0]
    assert result['task']['translations'][1:] == bad['segments'][1:]
    assert result['qualityHarness']['attempts'][0]['accepted']
    assert not result['task']['qualityPassed']
    assert not result['reviewAdvisory']['review']['translationChecks'][1]['vietnameseNatural']


def test_regression_within_changed_target_still_rejects_candidate():
    good = personal_responses(); bad = copy.deepcopy(good[3])
    bad['segments'][0]['textVi'] = 'Broken translation'
    before = copy.deepcopy(good[4]); before['translationChecks'][0]['meaningPreserved'] = False
    after = copy.deepcopy(good[4]); after['translationChecks'][0]['terminologyCorrect'] = False
    result, _ = run(list(zip(BASE[:3], good[:3])) + [(BASE[3], bad), (BASE[4], before),
        ('REPAIR_TRANSLATIONS', tpatch(good)), (BASE[4], after)], payload=personal_payload())
    assert result['task']['translations'] == bad['segments']
    assert not result['qualityHarness']['attempts'][0]['accepted']
    assert not result['task']['qualityPassed']


def test_translation_patch_cannot_clear_a_frozen_dialogue_rubric_failure():
    good = personal_responses(); bad = copy.deepcopy(good[3])
    bad['segments'][0]['textVi'] = 'Broken translation'
    before = copy.deepcopy(good[4]); before['translationChecks'][0]['meaningPreserved'] = False
    before['rubricChecks'][0]['passed'] = False
    result, _ = run(list(zip(BASE[:3], good[:3])) + [(BASE[3], bad), (BASE[4], before),
        ('REPAIR_TRANSLATIONS', tpatch(good)), (BASE[4], good[4])], payload=personal_payload())
    assert result['task']['translations'][0] == good[3]['segments'][0]
    assert not result['reviewAdvisory']['review']['rubricChecks'][0]['passed']
    assert not result['task']['qualityPassed']


@pytest.mark.parametrize('stage', ['QUESTIONS', 'TRANSLATE'])
def test_unchanged_target_false_flags_stay_false_for_both_reviewers(stage):
    good = personal_responses()
    model = w.QuestionEvidenceReview if stage == 'QUESTIONS' else w.PersonalizedReview
    previous = model.model_validate(good[2] if stage == 'QUESTIONS' else good[4])
    current = previous.model_copy(deep=True)
    field = 'questionChecks' if stage == 'QUESTIONS' else 'translationChecks'
    flag = 'answerSupported' if stage == 'QUESTIONS' else 'meaningPreserved'
    setattr(getattr(previous, field)[1], flag, False)
    first = getattr(previous, field)[0]
    changed = {(first.activityId, first.questionId if stage == 'QUESTIONS' else first.segmentId)}
    result = h.retain_unrepaired_findings(previous, current, changed, stage)
    assert getattr(getattr(result, field)[1], flag) is False
    assert getattr(getattr(current, field)[1], flag) is True
