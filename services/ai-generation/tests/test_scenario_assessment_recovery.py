"""Executable recovery contracts; mocked verdicts are not semantic quality labels."""
import copy
import json
import time

import pytest

from app import scenario_assessment as a, scenario_assessment_recovery as r, scenario_workflow as w
from test_scenario_assessment import execute as native_execute
from test_scenario_assessment_alignment import aligned_rows, grade_payload
from test_scenario_assessment_parts import parts_payload


def execute(value,responses):
    if value.phase=='GRADE':
        return native_execute(value,responses)
    expanded=[]
    for response in responses:
        expanded.append(response)
        if isinstance(response,dict) and 'activities' in response and all('segments' in x and 'assessment' not in x for x in response['activities']):
            expanded.append(dict(checks=[dict(itemType='dialogue',activityId=act['id'],itemId=act['id'],status='PASS',code='checked',artifactQuote='',sourceEvidence=[],requestQuote='',reasonVi='Nguồn đúng yêu cầu.',derivedAnswerVi='',answerSupport='not-applicable',answerMatchesDerived=True,partChecks=[]) for act in response['activities']]))
    return native_execute(value,expanded)


def pin():
    return a.prompt_hash(a.recovery_candidate)


def review(rows, stage="QUESTIONS"):
    dialogue = w.Dialogue.model_validate(rows[0])
    artifact = a.FlexibleQuestions.model_validate(rows[1]) if stage == "QUESTIONS" else w.Translations.model_validate(rows[3])
    return dict(checks=[dict(itemType=k, activityId=act, itemId=item, status="PASS", code="checked",
        artifactQuote="", sourceEvidence=[dict(activityId=act,segmentId="s1",quoteEn="The shop opens at nine.")] if k=="question" else [], requestQuote="", answerSupport="direct" if k=="question" else "not-applicable", answerMatchesDerived=True, derivedAnswerVi="Đáp án từ nguồn." if k=="question" else "",
        partChecks=[dict(meaningId=mid,partId=p.id,requiredByQuestion=True,sourceSupportsPart=True,reasonVi="Phù hợp phạm vi.") for mid in artifact.activities[int(act[1])-1].questions[int(item[1])-1].meaningIds for p in next(m for m in artifact.activities[int(act[1])-1].assessment.meanings if m.id==mid).requiredParts] if k=="question" else [], reasonVi="Đã đối chiếu đúng nghĩa và phạm vi với nguồn.")
        for k, act, item in r.inventory(dialogue, artifact, stage)])


def defect(verdict, *, kind="question", activity="a1", item="q1", quote=None, code="scope"):
    value = copy.deepcopy(verdict)
    row = next(c for c in value["checks"] if (c["itemType"],c["activityId"],c["itemId"]) == (kind,activity,item))
    row.update(status="DEFECT", code=code, artifactQuote=quote or "Ý tham chiếu 1", reasonVi="Khóa yêu cầu thêm ý ngoài điều được hỏi.",
        sourceEvidence=[dict(activityId=activity,segmentId="s1",quoteEn="The shop opens at nine.")])
    return value


def patch(rows, *, suffix=" corrected"):
    act = rows[1]["activities"][0]
    meaning = copy.deepcopy(act["assessment"]["meanings"][0]);meaning["meaningEn"] += suffix
    return dict(meaningReplacements=[dict(activityId="a1",meaning=meaning)],
        questionReplacements=[dict(activityId="a1",question=copy.deepcopy(act["questions"][0]))])


def test_clean_path_has_five_calls_and_verified_core_bound_content():
    rows=aligned_rows();result,calls=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],review(rows),rows[3],review(rows,"TRANSLATE")])
    assert len(calls)==6 and result["task"]["qualityPassed"]
    assert result["qualityHarness"]["decision"]=="ACCEPTED"
    assert result["qualityHarness"]["contentHash"]==r.h.digest(result["task"])


def test_confirmed_defect_repaired_with_dependency_closure_and_neighbors_preserved():
    rows=aligned_rows();bad=defect(review(rows),code='extra_scoring_unit')
    result,calls=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,bad,patch(rows),review(rows),rows[3],review(rows,"TRANSLATE")])
    assert result["qualityHarness"]["attempts"][0]["accepted"]
    assert result["task"]["activities"][0]["assessment"]["meanings"][0]["meaningEn"].endswith(" corrected")
    assert result["task"]["activities"][1]["assessment"]==rows[1]["activities"][1]["assessment"]
    data=json.loads(next(c for c in calls if c[0]=="scenario_repair_questions")[1][1]["content"])
    assert {(t['itemType'],t['itemId']) for t in data['allowedTargets']}=={('question','q1'),('meaning','m1')}


@pytest.mark.parametrize('code,source,prompt,bad_answer,good_answer,bad_key,good_key',[
    ('extra_scoring_unit','The coach leaves at ten from the north gate.','Xe khởi hành lúc mấy giờ?','Mười giờ tại cổng bắc.','Mười giờ.','Ten, from the north gate.','Ten.'),
    ('wrong_speaker','I, the customer, will phone the baker.','Ai sẽ gọi điện?','Người làm bánh.','Khách hàng.','The baker.','The customer.'),
    ('speech_act','I might reserve a table if I finish early.','Người nói dự định làm gì nếu xong sớm?','Đã đặt bàn.','Có thể đặt bàn.','A table is already reserved.','Might reserve a table.'),
    ('deadline_not_instant','Please return the tray by six.','Hạn trả khay là khi nào?','Đúng sáu giờ.','Chậm nhất sáu giờ.','Exactly six.','No later than six.'),
    ('lost_negation','We do not deliver on holidays.','Cửa hàng có giao hàng ngày lễ không?','Có.','Không.','Deliveries operate on holidays.','No deliveries on holidays.'),
    ('quantity','The price falls by thirty percent.','Giá giảm bao nhiêu phần trăm?','Còn ba mươi phần trăm.','Giảm ba mươi phần trăm.','Falls to thirty percent.','Falls by thirty percent.'),
    ('certainty','I think the room might be available.','Người nói nhận định thế nào về phòng?','Chắc chắn còn phòng.','Có thể còn phòng.','The room is certainly available.','The speaker thinks the room might be available.'),
    ('wrong_terminology','The pottery studio supplies the clay.','Xưởng gốm cung cấp vật liệu gì?','Đất trồng cây.','Đất sét.','Potting soil.','Clay.'),
])
def test_source_grounded_fault_pair_is_restored_and_correct_control_stays_unchanged(code,source,prompt,bad_answer,good_answer,bad_key,good_key):
    # Realistic synthetic fault pairs. Verdicts are stubbed here to isolate recovery;
    # live probes separately measure whether the model actually detects these faults.
    rows=aligned_rows();rows[0]['activities'][0]['segments'][0]['textEn']+=' '+source
    act=rows[1]['activities'][0];q=act['questions'][0];m=act['assessment']['meanings'][0]
    evidence=[dict(segmentId='s1',quoteEn=source)]
    q.update(promptVi=prompt,answerVi=bad_answer,factEn=bad_key,evidence=evidence)
    m.update(meaningEn=bad_key,evidence=evidence,requiredParts=[dict(id='p1',meaningEn=bad_key,evidence=evidence)])
    fixed=patch(rows);fq=fixed['questionReplacements'][0]['question'];fm=fixed['meaningReplacements'][0]['meaning']
    fq.update(answerVi=good_answer,factEn=good_key);fm.update(meaningEn=good_key,requiredParts=[dict(id='p1',meaningEn=good_key,evidence=evidence)])
    clean=review(rows);bad=defect(clean,code=code,quote=bad_answer)
    result,_=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,bad,fixed,clean,rows[3],review(rows,'TRANSLATE')])
    assert result['qualityHarness']['attempts'][0]['accepted']
    assert result['task']['activities'][0]['questions'][0]['answerVi']==good_answer
    good_rows=copy.deepcopy(rows);good_rows[1]['activities'][0]['questions'][0]=fq;good_rows[1]['activities'][0]['assessment']['meanings'][0]=fm
    control,_=execute(parts_payload(promptHash=pin()),[good_rows[0],good_rows[1],review(good_rows),good_rows[3],review(good_rows,'TRANSLATE')])
    assert not control['qualityHarness']['attempts']
    assert result['task']==control['task']


def test_false_reviewer_warning_is_verified_and_does_not_force_patch_or_rejection():
    rows=aligned_rows();bad=defect(review(rows))
    result,calls=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,review(rows),rows[3],review(rows,"TRANSLATE")])
    assert result['task']['qualityPassed'] and len(calls)==7
    assert result['qualityHarness']['disputedFindings'] and not result['qualityHarness']['attempts']


def test_explanation_focus_can_find_and_repair_a_fault_missed_by_full_bank_review():
    rows=aligned_rows();rows[1]['activities'][0]['questions'][0]['skill']='reason'
    rows[1]['activities'][0]['assessment']['meanings'][0]['skill']='reason'
    full=review(rows)
    focused=dict(checks=[c for c in full['checks'] if c['itemType']=='dialogue' or c['activityId']=='a1' and c['itemId'] in ('m1','q1')])
    bad=defect(focused)
    result,calls=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],full,bad,bad,patch(rows),full,focused,rows[3],review(rows,'TRANSLATE')])
    assert result['qualityHarness']['attempts'][0]['accepted']
    projected=json.loads(calls[4][1][1]['content'])
    assert len(projected['groundedQuestions']['activities'][0]['questions'])==1
    assert 'skill' not in projected['groundedQuestions']['activities'][0]['questions'][0]
    assert result['task']['qualityPassed']


def test_translation_defect_repairs_only_the_allowed_turn_and_rechecks_source():
    rows=aligned_rows();rows[0]['activities'][0]['segments'][0]['textEn']+=' Invoices cannot be paid in cash.'
    rows[3]['segments'][0]['textVi']='Hóa đơn có thể thanh toán bằng tiền mặt.'
    bad=defect(review(rows,'TRANSLATE'),kind='translation',item='s1',quote=rows[3]['segments'][0]['textVi'],code='translation_negation')
    next(c for c in bad['checks'] if c['status']=='DEFECT')['sourceEvidence']=[dict(activityId='a1',segmentId='s1',quoteEn='Invoices cannot be paid in cash.')]
    value=copy.deepcopy(rows[3]['segments'][0]);value['textVi']='Hóa đơn không thể thanh toán bằng tiền mặt.'
    result,_=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],review(rows),rows[3],bad,bad,dict(replacements=[value]),review(rows,'TRANSLATE')])
    assert result['qualityHarness']['attempts'][0]['accepted']
    assert result['task']['translations'][0]==value
    assert result['task']['translations'][1:]==rows[3]['segments'][1:]


def test_fabricated_review_quote_gets_contract_recheck_not_semantic_authority():
    rows=aligned_rows();bad=defect(review(rows),quote="invented text")
    result,_=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,review(rows),rows[3],review(rows,"TRANSLATE")])
    assert result['task']['qualityPassed']
    assert any(v['status']=='UNKNOWN' for v in result['qualityHarness']['validations'])


def test_new_defect_after_patch_is_repaired_before_acceptance():
    rows=aligned_rows();bad=defect(review(rows));new=defect(review(rows),code="new_fault")
    responses=[rows[0],rows[1],bad,bad,patch(rows),new,new,new,new,patch(rows,suffix=" corrected again"),review(rows),rows[3],review(rows,"TRANSLATE")]
    result,_=execute(parts_payload(promptHash=pin()),responses)
    assert [t['accepted'] for t in result['qualityHarness']['attempts']]==[False,True]


def test_source_repair_rebuilds_all_dependents_and_keeps_prior_activity():
    rows=aligned_rows();rows[0]['activities'][1]['segments'][0]['textEn']+=' The access code is eight03.'
    bad=defect(review(rows),kind='dialogue',activity='a2',item='a2',quote='eight03',code='source_corrupt')
    changed=copy.deepcopy(rows);changed[0]['activities'][1]['segments'][0]['textEn']=changed[0]['activities'][1]['segments'][0]['textEn'].replace('eight03','eight zero three')
    result,calls=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,bad,changed[0],rows[1],review(changed),rows[3],review(changed,'TRANSLATE')])
    assert result['task']['qualityPassed'] and result['qualityHarness']['attempts'][0]['accepted']
    assert [c[0] for c in calls].count('scenario_generate')==2
    assert result['task']['activities'][0]['segments']==rows[0]['activities'][0]['segments']
    assert result['qualityHarness']['sourceHash']!=result['qualityHarness']['originalSourceHash']


def test_source_patch_cannot_change_an_unrelated_activity():
    rows=aligned_rows();bad=defect(review(rows),kind='dialogue',activity='a2',item='a2',quote='The shop opens at nine.',code='source_corrupt')
    changed=copy.deepcopy(rows[0]);changed['activities'][0]['segments'][0]['textEn']+=' Forbidden scope change.'
    with pytest.raises(w.QualityRejected,match='source recovery failed'):
        execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,bad,changed])


def test_out_of_scope_patches_cannot_be_accepted_and_recovery_can_try_a_valid_patch():
    rows=aligned_rows();bad=defect(review(rows));over=patch(rows);over['meaningReplacements'][0]['activityId']='a2'
    result,_=execute(parts_payload(promptHash=pin()),[rows[0],rows[1],bad,bad,over,bad,bad,bad,bad,patch(rows),review(rows),rows[3],review(rows,'TRANSLATE')])
    assert [t['accepted'] for t in result['qualityHarness']['attempts']]==[False,True]


def test_provider_unknown_is_not_replayed():
    with pytest.raises(TimeoutError):
        execute(parts_payload(promptHash=pin()),[TimeoutError('provider outcome unknown')])


def test_deadline_and_budget_stop_before_unfinishable_repair():
    recovery=r.Recovery(parts_payload(promptHash=pin()),None,time.monotonic()-1)
    with pytest.raises(w.QualityRejected,match='deadline'):
        recovery.reserve(1)
    recovery.deadline=time.monotonic()+60;recovery.plan=[{}]*15
    with pytest.raises(w.QualityRejected,match='budget'):
        recovery.reserve(3)


async def test_source_revision_does_not_reset_the_shared_patch_limit():
    rows=aligned_rows();recovery=r.Recovery(parts_payload(promptHash=pin()),None,time.monotonic()+60)
    recovery.attempts=[dict(stage='QUESTIONS'),dict(stage='QUESTIONS')]
    with pytest.raises(w.QualityRejected,match='attempts exhausted'):
        await recovery.patch(w.Dialogue.model_validate(rows[0]),a.FlexibleQuestions.model_validate(rows[1]),[],'QUESTIONS')


def grade_review(payload):
    rows=aligned_rows();value=review(rows)
    value['checks']=[c for c in value['checks'] if c['activityId']==payload.activity.id]
    return value


@pytest.mark.parametrize('text,status', [('At nine.','CAPTURED'),('Lúc chín giờ.','CAPTURED'),('Lúc nine.','CAPTURED'),('nine oclock','CAPTURED'),('At six.','INCORRECT'),('','ABSENT')])
def test_grade_prechecks_key_then_uses_only_original_learner_evidence(text,status):
    payload=grade_payload().model_copy(update={'promptHash':pin()});payload.assessmentSubmission.answers[0].textVi=text
    raw=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[dict(partId='p1',status=status,evidence=[dict(responseId='q1',quote=text)] if text else [])])])
    result,calls=execute(payload,[grade_review(payload),raw])
    assert [c[0] for c in calls]==['scenario_review_questions','scenario_grade']
    assert result['task']['meanings'][0]['status']==('MISSED' if status=='ABSENT' else status)


def test_invalid_grade_key_is_repaired_before_scoring_without_changing_learner_question():
    payload=grade_payload().model_copy(update={'promptHash':pin()});rows=aligned_rows();clean=grade_review(payload)
    bad=defect(clean);raw=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[dict(partId='p1',status='CAPTURED',evidence=[dict(responseId='q1',quote='At nine.')])])])
    result,_=execute(payload,[bad,bad,patch(rows),clean,raw])
    assert result['assessmentRepair']['originalHash']==r.h.digest(payload.activity)
    assert result['assessmentRepair']['activity']['questions'][0]['promptVi']==payload.activity.questions[0].promptVi
    assert result['task']['meanings'][0]['status']=='CAPTURED'


def test_grade_missing_one_requested_part_is_partial_not_captured():
    payload=grade_payload().model_copy(update={'promptHash':pin()})
    meaning=payload.activity.assessment.meanings[0]
    extra=meaning.requiredParts[0].model_copy(deep=True);extra.id='p2';extra.meaningEn='Another explicitly requested unit.'
    meaning.requiredParts.append(extra)
    clean=grade_review(payload)
    next(c for c in clean['checks'] if c['itemType']=='question' and c['itemId']=='q1')['partChecks'].append(dict(
        meaningId='m1',partId='p2',requiredByQuestion=True,sourceSupportsPart=True,reasonVi='Câu hỏi yêu cầu cả hai ý.'))
    raw=dict(assessmentIssue='',meanings=[dict(meaningId='m1',parts=[
        dict(partId='p1',status='CAPTURED',evidence=[dict(responseId='q1',quote='At nine.')]),dict(partId='p2',status='ABSENT',evidence=[])])])
    result,_=execute(payload,[clean,raw])
    assert result['task']['meanings'][0]['status']=='PARTIAL'


def test_grade_repair_cannot_reword_an_already_answered_question():
    payload=grade_payload().model_copy(update={'promptHash':pin()});rows=aligned_rows();bad=defect(grade_review(payload))
    changed=patch(rows);changed['questionReplacements'][0]['question']['promptVi']='Một câu hỏi khác?'
    with pytest.raises(w.QualityRejected):
        execute(payload,[bad,bad,changed,bad,bad])


def test_persistent_defect_is_unresolved_never_a_successful_repair():
    rows=aligned_rows();bad=defect(review(rows))
    with pytest.raises(w.QualityRejected) as caught:
        execute(parts_payload(promptHash=pin()),[rows[0],rows[1],*[bad,bad,patch(rows),bad,bad,bad,bad,patch(rows),bad,bad,bad,bad]])
    assert caught.value.quality_harness['decision']=='UNRESOLVED'
    assert not any(t['accepted'] for t in caught.value.quality_harness['attempts'])
