import copy
import json
from pathlib import Path
import time
from unittest.mock import patch

import httpx
import pytest

from app import guided_translation as guidance
from app.config import Settings
from app.conversation_demo import artifact, arm, messages, PROMPT_SHA
from app.registry import artifact_hash
from app.schemas import ConversationContext

FIXTURE = Path(__file__).with_name('fixtures') / 'guided-r13-contexts.json'
CASES = json.loads(FIXTURE.read_text(encoding='utf-8'))['cases']


@pytest.mark.parametrize('case', CASES, ids=lambda case: case['id'])
def test_builder_matches_verified_frozen_round13_inputs_without_changing_sources(case):
    expected = case['context']
    source = {k:v for k,v in expected.items() if k not in ('translationGlossary','sentenceRequiredTerms','sourceTermAlignment')}
    before = copy.deepcopy(source)
    assert guidance.enrich(source) == expected
    assert source == before
    assert guidance.enrich(expected) == expected


def test_longest_source_expressions_and_distinct_clause_times_are_preserved():
    c = guidance.enrich({'utteranceEn': 'Collect the locker key after Tuesday evening, but do not return it before Saturday morning.',
        'facts':[{'textEn':'Do not return it before Saturday morning'}]})
    clauses = c['sourceTermAlignment']['utteranceClauses']
    assert clauses[0]['localGlossary'] == [{'source':'locker key','vietnamese':'chìa khóa tủ'},
        {'source':'after Tuesday evening','vietnamese':'sau tối thứ Ba'}]
    assert clauses[1]['localGlossary'] == [{'source':'do not','vietnamese':'đừng'},
        {'source':'before Saturday morning','vietnamese':'trước sáng thứ Bảy'}]
    assert not guidance.scoped_terms('Sundayish', [{'source':'Sunday','vietnamese':'Chủ nhật'}])


def test_physical_key_and_folder_rules_do_not_apply_to_computer_terms_or_other_scenes():
    assert guidance.in_scope({'scenario':'Nhận hàng'})
    assert not guidance.in_scope({'scenario':'cafe'})
    assert not guidance.in_scope({'scenario':'Nhận nhiệm vụ'})
    c = guidance.enrich({'utteranceEn':'Press the keyboard key to open the computer folder.', 'facts':[]})
    assert not {'key','folder'} & {t['source'] for t in c['translationGlossary']}


def test_messages_use_server_schema_and_guidance_only_in_the_evaluated_scene():
    frozen = artifact(Settings())
    context = {'scenario':'Nhận hàng','difficulty':'A1','partnerRole':'employee','learnerRole':'visitor'}
    content = {'utteranceEn':'Show the original receipt kD9.', 'facts':[{'textEn':'The original receipt kD9'}]}
    wire = messages(frozen, context, 'translation', content)
    data = json.loads(wire[1]['content'])
    assert wire[0]['content'] == frozen['guidedTranslationSystem']
    assert data['utteranceEn'] == content['utteranceEn'] and data['facts'] == content['facts']
    assert data['sourceTermAlignment']['facts'][0]['sourceEn'] == 'The original receipt kD9'
    legacy = messages(frozen, context | {'scenario':'cafe'}, 'translation', content)
    assert legacy[0]['content'] == frozen['translationSystem']
    assert 'translationGlossary' not in json.loads(legacy[1]['content'])
    for level, maximum in (('A1',2),('B1',3)):
        content_wire = messages(frozen, context | {'difficulty':level}, 'content')
        data = json.loads(content_wire[1]['content'])
        assert data['slotCountRange'] == {'min':2,'max':maximum}
        assert data['outputSchema'] == frozen['contentSchemas'][level]
    assert 'translationGlossary' not in ConversationContext.model_validate(context | {'translationGlossary':'injected'}).model_dump()


def test_guidance_hash_is_bound_to_the_core_ai_prompt_pin(tmp_path):
    frozen = artifact(Settings())
    assert frozen['sha256'] == artifact_hash(frozen) == PROMPT_SHA
    core = (Path(__file__).resolve().parents[3] / 'src/main/java/com/dictalab/service/ModelDemoService.java').read_text(encoding='utf-8')
    assert f'PROMPT_SHA = "{PROMPT_SHA}"' in core
    frozen['guidanceImplementationSha256'] = '0' * 64
    frozen['sha256'] = artifact_hash(frozen)
    (tmp_path / (frozen['version'] + '.json')).write_text(json.dumps(frozen), encoding='utf-8')
    with patch('app.conversation_demo.PROMPT_SHA', frozen['sha256']):
        with pytest.raises(ValueError, match='DEMO_GUIDANCE_DRIFT'):
            artifact(Settings(registry_dir=str(tmp_path)))


def fixture_task():
    utterance = 'Do not open the purple folder before Wednesday morning. My younger brother cannot collect it unless he can show the original receipt pL4.'
    content = {'utteranceEn':utterance, 'slots':[{'kind':'instruction','askVi':'Cần xử lý đồ vật thế nào?'},
        {'kind':'document','askVi':'Cần loại giấy tờ nào?'}], 'facts':[
        {'textEn':'Do not open the purple folder before Wednesday morning','evidenceEn':'Do not open the purple folder before Wednesday morning','taskType':'direct_detail'},
        {'textEn':'The original receipt pL4','evidenceEn':'the original receipt pL4','taskType':'direct_detail'}],
        'questionVi':'Cần xử lý đồ vật thế nào và cần giấy tờ gì?', 'taskVi':'Nghe yêu cầu giao nhận.', 'skill':'Nắm thông tin cụ thể'}
    translated = {'translationVi':'Đừng mở bìa hồ sơ màu tím trước sáng thứ Tư. Em trai tôi không thể nhận nó trừ khi có thể xuất trình biên lai gốc pL4.',
        'facts':[{'textVi':'Đừng mở bìa hồ sơ màu tím trước sáng thứ Tư'},{'textVi':'Biên lai gốc pL4'}]}
    return content, translated


@pytest.mark.parametrize('failure', [None, 'negation', 'identifier', 'cjk'])
def test_targeted_audit_rejects_missing_facts_and_never_repairs_answers(failure):
    content, answer = fixture_task()
    if failure == 'negation':
        answer['facts'][0]['textVi'] = 'Mở bìa hồ sơ màu tím trước sáng thứ Tư'
    elif failure == 'identifier':
        answer['facts'][1]['textVi'] = 'Biên lai gốc PL4'
    elif failure == 'cjk':
        answer['translationVi'] += ' 出示'
    before = copy.deepcopy(answer)
    errors = guidance.validate(guidance.enrich(content), answer)
    assert bool(errors) == bool(failure)
    assert answer == before


async def test_real_demo_arm_applies_guidance_and_keeps_length_and_semantic_gates():
    from app.conversation_demo import ConversationDemoInput
    content, answer = fixture_task()
    settings = Settings()
    frozen = artifact(settings)
    payload = ConversationDemoInput(level='A1',context={'scenario':'Nhận hàng','turnNumber':1})
    wires = []
    async def generate(client, config, wire, schema, variant, deadline):
        wires.append(wire)
        return (content if len(wires) % 2 else answer), {'provider':'local_cpu','model':'Qwen/Qwen3.5-4B'}
    with patch('app.conversation_demo.local_call', side_effect=generate):
        async with httpx.AsyncClient() as client:
            result = await arm(payload, settings, frozen, 'full07', client, time.monotonic()+30, None, 'scope', 'job')
            assert result['valid'] and result['translationGuidance'] == 'source-guided-r13'
            assert result['wordCount'] == 23 and result['wordBounds'] == {'minWords':8,'maxWords':35}
            assert result['semanticReview'] == 'not_evaluated'
            assert json.loads(wires[1][1]['content'])['sourceTermAlignment']
            content['utteranceEn'] += ' another' * 15
            result = await arm(payload, settings, frozen, 'base', client, time.monotonic()+30, None, 'scope', 'job')
            assert not result['valid'] and result['task'] is None
            assert any('word_count=' in error for error in result['validationErrors'])
