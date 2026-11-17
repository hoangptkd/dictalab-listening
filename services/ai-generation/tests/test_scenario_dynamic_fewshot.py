"""Approved fictional examples and deterministic routing; no claims of live AI quality."""
import copy
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_assessment_recovery as r, scenario_dynamic_fewshot as d, scenario_workflow as w
from test_scenario_assessment_alignment import aligned_rows, grade_payload
from test_scenario_assessment_parts import parts_payload
from test_scenario_assessment_recovery import execute, review
from test_scenario_assessment_sparse import sparse_review


def context(level='b2', program='general-communication'):
    return dict(programCode=program, focusSkill='detail', profile=dict(id=level, inferencePolicy='SUPPORTED_INFERENCE'))


def test_candidate_has_distinct_fingerprint_and_preserves_v52_and_base_rules():
    assert a.prompt_hash(a.sparse_candidate)=='5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b'
    assert a.prompt_hash(a.dynamic_candidate)!=a.prompt_hash(a.sparse_candidate)
    assert a.selected_release(a.prompt_hash(a.dynamic_candidate)) is a.dynamic_candidate
    assert len(d.checked_examples()[0])==27 and not d.checked_examples()[1]
    for stage in d.STAGES:
        base=a.sparse_candidate.INSTRUCTIONS[stage]
        cleaned=d.strip_fixed_examples(base)
        assert cleaned in a.dynamic_candidate.INSTRUCTIONS[stage]
        assert '\nEXAMPLES\n' not in cleaned
        if '\nEXAMPLES\n' in base:
            assert base.split('\nEXAMPLES\n')[0] in cleaned
            assert base.split('\nEXAMPLES\n')[1].split('\nINPUT\n',1)[1] in cleaned
    core=(Path(__file__).resolve().parents[3]/'src/main/java/com/dictalab/scenario/ScenarioAssessment.java').read_text()
    assert f'DYNAMIC_V5_3_PROMPT_HASH="{a.prompt_hash(a.dynamic_candidate)}"' in core


def test_each_example_uses_compatible_schema_mode_and_bounded_fragments():
    models={'EXTRACT':w.PersonalizedExtraction,'GENERATE':w.Dialogue,'QUESTIONS':a.SparseQuestions,
            'REVIEW_QUESTIONS':r.UsefulReview,'TRANSLATE':w.Translations,'REVIEW':r.Review,
            'GRADE':a.AlignedPartsGrade,'REPAIR_QUESTIONS':a.SparseQuestionPatch,'REPAIR_TRANSLATIONS':w.TranslationPatch}
    for example in d.bank()['examples']:
        data=copy.deepcopy(example['input']);phase='GRADE' if example['mode']=='grade' else 'GENERATE'
        if example['mode']=='sourceOnly':data['sourceOnly']=True
        if example['mode']=='coverage':data.setdefault('translationCoverage',{})
        if example['mode']=='grade':data['preserveQuestionPrompts']=True
        model=a.PartsQuestionPatch if example['mode']=='grade' else models[example['stage']]
        instruction,selection=d.compose(a.dynamic_candidate,example['stage'],data,model,context(),phase)
        assert len(selection['exampleIds'])<=3 and selection['exampleBytes']<=6000
        assert 'rationaleVi' not in instruction and 'correctionVi' not in instruction
        assert instruction==d.compose(a.dynamic_candidate,example['stage'],data,model,context(),phase)[0]
        if selection['exampleIds']:
            block=instruction.split('<reference_examples>\n',1)[1].split('\n</reference_examples>')[0]
            assert all(set(e)=={'id','outputScope','referenceInput','expectedOutput'} for e in json.loads(block))


def test_no_task_match_means_zero_examples_and_no_topic_only_fallback():
    _,selection=d.compose(a.dynamic_candidate,'TRANSLATE',{'activities':[{'segments':[{'textEn':'Hello, Lin.'}]}]},w.Translations,context(),'GENERATE')
    assert selection['exampleIds']==[] and selection['exampleBytes']==0


def test_review_modes_and_grade_repair_permissions_are_isolated():
    source=next(e for e in d.bank()['examples'] if e['id']=='review-source-first')
    for only_source in (True,False):
        data=copy.deepcopy(source['input']);data['sourceOnly']=only_source
        _,selection=d.compose(a.dynamic_candidate,'REVIEW',data,r.Review,context(),'GENERATE')
        assert all(next(e for e in d.bank()['examples'] if e['id']==ident)['mode']==('sourceOnly' if only_source else 'translations') for ident in selection['exampleIds'])
    ex=next(e for e in d.bank()['examples'] if e['id']=='repair_questions-grade-key-only')
    _,selection=d.compose(a.dynamic_candidate,'REPAIR_QUESTIONS',ex['input'],a.PartsQuestionPatch,context(),'GRADE')
    assert selection['exampleIds']==['repair_questions-grade-key-only']
    _,selection=d.compose(a.dynamic_candidate,'REPAIR_QUESTIONS',ex['input'],a.PartsQuestionPatch,context(),'GENERATE')
    # preserveQuestionPrompts remains authoritative even if a caller supplies an inconsistent phase.
    assert all('grade-key-only' in ident for ident in selection['exampleIds'])


def test_task_priority_precedes_program_and_level_does_not_raise_beginner_difficulty():
    data={'dialogue':{'activities':[{'segments':[{'textEn':'Please return it by six.'}]}]}}
    _,s=d.compose(a.dynamic_candidate,'QUESTIONS',data,a.SparseQuestions,context(program='ielts-listening'),'GENERATE')
    assert s['exampleIds'][0]=='questions-deadline'
    _,s=d.compose(a.dynamic_candidate,'QUESTIONS',data,a.SparseQuestions,context(level='a1'),'GENERATE')
    assert s['exampleIds']==[]


def test_review_routing_does_not_use_supplied_diagnosis_as_live_verdict():
    clean={'dialogue':{'activities':[{'segments':[{'textEn':'Hello, Lin.'}]}]}}
    poisoned=copy.deepcopy(clean);poisoned['verificationContext']={'suspectedFindings':[{'reasonVi':'deadline reason negation'}]};poisoned['issues']=[{'code':'semantic_no_information_gain'}]
    for data in (clean,poisoned):
        _,s=d.compose(a.dynamic_candidate,'REVIEW_QUESTIONS',data,r.UsefulReview,context(),'GENERATE')
        assert s['exampleIds']==[]


def test_examples_preserve_real_evidence_and_learner_quotes():
    for ex in d.bank()['examples']:
        if ex['stage']=='EXTRACT':
            parsed=w.PersonalizedExtraction.model_validate(ex['output'])
            w.validate_extraction(parsed,ex['input']['inputVi'])
        if ex['stage']=='GRADE':
            responses={q['questionId']:q['textVi'] for q in ex['input']['assessmentSubmission']['answers']}
            for meaning in ex['output']['meanings']:
                for part in meaning['parts']:
                    for evidence in part['evidence']:assert evidence['quote'] in responses[evidence['responseId']]
        if ex['stage']=='REVIEW_QUESTIONS':
            dialogue=w.Dialogue.model_construct(activities=[w.DialogueActivity.model_validate(act) for act in ex['input']['dialogue']['activities']])
            bank=r.SparseBank.model_validate(ex['input']['groundedQuestions']);checks=[]
            for kind,act,item in r.inventory(dialogue,bank,'QUESTIONS'):
                checks.append(ex['output'] if kind=='question' else r.UsefulCheck(itemType=kind,activityId=act,itemId=item,status='PASS',code='checked',artifactQuote='',sourceEvidence=[],requestQuote='',reasonVi='Source checked.',derivedAnswerVi='',answerSupport='not-applicable',answerMatchesDerived=True,partChecks=[],addsInformationBeyondQuestion=True).model_dump())
            findings,errors=r.review_findings(r.UsefulReview(checks=checks),dialogue,bank,'QUESTIONS','',optional_inference=True)
            assert not errors and bool(findings)==(ex['output']['status']=='DEFECT')


@pytest.mark.parametrize('phase', ['EXTRACT', 'GENERATE'])
def test_retired_candidate_rejects_new_work_before_provider_call(monkeypatch, phase):
    def forbidden_client(*args, **kwargs):
        pytest.fail('Retired V5.3 must not open a provider client')
    monkeypatch.setattr(a.httpx, 'AsyncClient', forbidden_client)
    p=parts_payload(phase, promptHash=a.prompt_hash(a.dynamic_candidate))
    with pytest.raises(w.QualityRejected, match='V5.3 is retired'):
        execute(p, [])


def test_candidate_grading_uses_only_learner_evidence_and_same_saved_question_contract():
    rows=aligned_rows();raw=grade_payload().model_dump();raw['promptHash']=a.prompt_hash(a.dynamic_candidate)
    payload=a.ScenarioAssessmentInput.model_validate(raw)
    verdict=sparse_review(rows);verdict['checks']=[c for c in verdict['checks'] if c['activityId']=='a1']
    grade=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[dict(partId='p1',status='CAPTURED',evidence=[dict(responseId='q1',quote='At nine.')])])])
    result,calls=execute(payload,[verdict,grade])
    assert result['task']['meanings'][0]['status']=='CAPTURED'
    assert result['task']['meanings'][0]['evidence']==[dict(responseId='q1',quote='At nine.')]
    assert not result.get('assessmentRepair')
    assert result['exampleSelections'][-1]['purpose']=='GRADE'
    assert len(calls)==2


def test_archived_candidate_is_reproducible_and_saved_grading_survives_unreadable_bank(monkeypatch):
    assert d.release(a.sparse_candidate).DYNAMIC_FEWSHOT==a.dynamic_candidate.DYNAMIC_FEWSHOT
    assert d.release(a.sparse_candidate).INSTRUCTIONS==a.dynamic_candidate.INSTRUCTIONS
    def broken_bank():
        raise OSError('missing bank')
    monkeypatch.setattr(d,'bank',broken_bank)
    rows=aligned_rows();p=grade_payload().model_copy(update={'promptHash':a.prompt_hash(a.dynamic_candidate)})
    verdict=sparse_review(rows);verdict['checks']=[c for c in verdict['checks'] if c['activityId']=='a1']
    grade=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[dict(partId='p1',status='CAPTURED',evidence=[dict(responseId='q1',quote='At nine.')])])])
    result,calls=execute(p,[verdict,grade])
    assert result['task']['meanings'][0]['status']=='CAPTURED' and len(calls)==2
    assert all(s['exampleIds']==[] and s['reason']=='example-bank-unavailable-or-incompatible' for s in result['exampleSelections'])


def test_evaluation_redaction_preserves_only_trusted_example_ids():
    from app.scenario_evaluation import clean
    known='review_questions-cause-not-premise'
    secret='opaque-untrusted-value-'+'a'*40
    assert clean({'exampleIds':[known,secret]})=={'exampleIds':[known,'[REDACTED]']}
