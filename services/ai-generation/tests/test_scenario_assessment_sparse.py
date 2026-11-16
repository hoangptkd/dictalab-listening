"""One useful question and optional inference; legacy banks keep their saved contract."""
import copy

import pytest

from app import scenario_assessment as a, scenario_assessment_recovery as r, scenario_workflow as w
from test_scenario_assessment_recovery import execute, review
from test_scenario_assessment_alignment import aligned_rows
from test_scenario_assessment_parts import parts_payload


def payload():
    return parts_payload(promptHash=a.prompt_hash(a.sparse_candidate))


def test_one_question_per_activity_is_valid_and_historical_minimum_is_unchanged():
    rows=aligned_rows()
    for act in rows[1]['activities']:
        act['questions']=act['questions'][:1];act['assessment']['meanings']=act['assessment']['meanings'][:1]
    questions=a.SparseQuestions.model_validate(rows[1]);dialogue=w.Dialogue.model_validate(rows[0])
    a.validate_content(a.attach_questions(dialogue,questions,payload().learningContext),payload().specification,payload().profile,payload().learningContext)
    with pytest.raises(ValueError):a.FlexibleQuestions.model_validate(rows[1])
    result,_=execute(payload(),[rows[0],rows[1],sparse_review(rows),rows[3],review(rows,'TRANSLATE')])
    assert [len(act['questions']) for act in result['task']['activities']]==[1,1,1]
    assert result['task']['qualityPassed']


def sparse_review(rows):
    artifact=a.SparseQuestions.model_validate(rows[1]);dialogue=w.Dialogue.model_validate(rows[0])
    checks=[]
    for (kind,act,item),value in r.inventory(dialogue,artifact,'QUESTIONS').items():
        question=kind=='question'
        bank=next(a for a in artifact.activities if a.id==act)
        checks.append(dict(itemType=kind,activityId=act,itemId=item,status='PASS',code='checked',artifactQuote='',requestQuote='',
            addsInformationBeyondQuestion=True,
            sourceEvidence=[dict(activityId=act,**value.evidence[0].model_dump())] if question else [],reasonVi='Đúng nghĩa và đúng phạm vi.',
            derivedAnswerVi=value.answerVi if question else '',answerSupport='direct' if question else 'not-applicable',answerMatchesDerived=True,
            partChecks=[dict(meaningId=mid,partId=p.id,requiredByQuestion=True,sourceSupportsPart=True,reasonVi='Ý được câu hỏi yêu cầu.')
                        for mid in value.meaningIds for p in next(m for m in bank.assessment.meanings if m.id==mid).requiredParts] if question else []))
    return dict(checks=checks)


def test_repeated_answer_cannot_hide_behind_a_passing_review_summary():
    rows=aligned_rows();verdict=sparse_review(rows)
    row=next(c for c in verdict['checks'] if c['itemType']=='question')
    row['addsInformationBeyondQuestion']=False
    findings,errors=r.review_findings(r.UsefulReview.model_validate(verdict),w.Dialogue.model_validate(rows[0]),a.SparseQuestions.model_validate(rows[1]),'QUESTIONS','',optional_inference=True)
    assert not errors and findings[0]['code']=='semantic_no_information_gain'


def test_tautological_question_can_be_removed_with_its_unused_key_without_replacement():
    rows=aligned_rows();bad=sparse_review(rows)
    row=next(c for c in bad['checks'] if (c['itemType'],c['activityId'],c['itemId'])==('question','a2','q2'))
    row.update(status='DEFECT',code='tautology',artifactQuote=rows[1]['activities'][1]['questions'][1]['answerVi'],answerMatchesDerived=False)
    fixed=copy.deepcopy(rows);fixed[1]['activities'][1]['questions'].pop();fixed[1]['activities'][1]['assessment']['meanings'].pop()
    patch=dict(questionReplacements=[],meaningReplacements=[],removals=[dict(activityId='a2',itemType=k,itemId=i) for k,i in [('question','q2'),('meaning','m2')]])
    result,calls=execute(payload(),[rows[0],rows[1],bad,bad,patch,sparse_review(fixed),rows[3],review(rows,'TRANSLATE')])
    assert result['qualityHarness']['attempts'][0]['accepted']
    assert len(result['task']['activities'][1]['questions'])==1
    assert len(result['task']['activities'][1]['assessment']['meanings'])==1
    assert result['task']['activities'][0]['assessment']==rows[1]['activities'][0]['assessment']
    assert len(calls)==9


@pytest.mark.parametrize('existing_label',[False,True])
def test_direct_questions_need_no_inference_coverage_or_relabeling(existing_label):
    rows=aligned_rows();p=payload();p.learningContext=p.learningContext.model_copy(update={'focusSkill':'supported-inference'})
    # Existing optional metadata can disagree with independently classified direct support.
    if existing_label:
        rows[1]['activities'][0]['questions'][0]['skill']='supported-inference'
        rows[1]['activities'][0]['assessment']['meanings'][0]['skill']='supported-inference'
    result,calls=execute(p,[rows[0],rows[1],sparse_review(rows),rows[3],review(rows,'TRANSLATE')])
    assert result['task']['qualityPassed'] and not result['qualityHarness']['attempts']
    assert len(calls)==6
    assert result['task']['activities'][0]['questions'][0]['answerVi']==rows[1]['activities'][0]['questions'][0]['answerVi']


def test_single_saved_question_can_be_graded_after_other_question_was_removed():
    from test_scenario_assessment_alignment import grade_payload
    rows=aligned_rows()
    for act in rows[1]['activities']:
        act['questions']=act['questions'][:1];act['assessment']['meanings']=act['assessment']['meanings'][:1]
    p=payload();content=a.attach_questions(w.Dialogue.model_validate(rows[0]),a.SparseQuestions.model_validate(rows[1]),p.learningContext)
    old=grade_payload().model_dump();old.update(promptHash=a.prompt_hash(a.sparse_candidate),activity=content.activities[0].model_dump())
    p=a.ScenarioAssessmentInput.model_validate(old)
    verdict=sparse_review(rows);verdict['checks']=[c for c in verdict['checks'] if c['activityId']=='a1']
    grade=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[dict(partId='p1',status='CAPTURED',evidence=[dict(responseId='q1',quote='At nine.')])])])
    result,_=execute(p,[verdict,grade])
    assert result['task']['meanings'][0]['status']=='CAPTURED'
    old['promptHash']=a.prompt_hash(a.recovery_candidate)
    with pytest.raises(ValueError,match='historical assessment'):
        a.ScenarioAssessmentInput.model_validate(old)


def test_removal_leaves_stable_ids_and_rejects_orphans_or_outside_scope():
    rows=aligned_rows();bank=a.SparseQuestions.model_validate(rows[1]);allowed={('question','a1','q1'),('meaning','a1','m1')}
    patch=a.SparseQuestionPatch(questionReplacements=[],meaningReplacements=[],removals=[a.RemovedTarget(activityId='a1',itemType=k,itemId=i) for k,i in [('question','q1'),('meaning','m1')]])
    candidate=r.merge_sparse_patch(bank,patch,allowed)
    a.validate_questions(candidate,w.Dialogue.model_validate(rows[0]),payload().learningContext)
    assert candidate.activities[0].questions[0].id=='q2' and candidate.activities[0].assessment.meanings[0].id=='m2'
    with pytest.raises(ValueError):r.merge_sparse_patch(bank,patch,{('question','a1','q1')})
    patch.removals=patch.removals[:1]
    candidate=r.merge_sparse_patch(bank,patch,{('question','a1','q1')})
    with pytest.raises(ValueError,match='meaning coverage'):a.validate_questions(candidate,w.Dialogue.model_validate(rows[0]),payload().learningContext)


def test_evidence_and_key_scope_checks_still_apply_to_one_question():
    rows=aligned_rows()
    for act in rows[1]['activities']:
        act['questions']=act['questions'][:1];act['assessment']['meanings']=act['assessment']['meanings'][:1]
    rows[1]['activities'][0]['questions'][0]['evidence'][0]['quoteEn']='Not in the transcript.'
    with pytest.raises(ValueError,match='unanchored'):
        a.validate_questions(a.SparseQuestions.model_validate(rows[1]),w.Dialogue.model_validate(rows[0]),payload().learningContext)
