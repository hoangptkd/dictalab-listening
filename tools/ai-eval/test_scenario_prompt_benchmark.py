"""Protect V5 with V4 GRADE and preserve exact historical benchmark selection; no network needed."""
import copy
import asyncio
import json
from pathlib import Path

import pytest

import scenario_prompt_benchmark as benchmark


@pytest.mark.parametrize('candidate',['v4','v5','v6'])
def test_benchmark_variants_restore_active_mixed_release_even_after_failure(candidate):
    workflow=benchmark.workflow
    original=copy.deepcopy(workflow.INSTRUCTIONS)
    original_hash=workflow.prompt_hash()
    pin=(benchmark.ROOT/'src/main/resources/scenario/workflow-v1.sha256').read_text().strip()
    assert workflow.PROMPT_VERSION=='SCENARIO_LISTENING_V5_GRADE_V4_1' and original_hash==pin
    with pytest.raises(RuntimeError):
        with benchmark.variant(candidate):
            assert set(workflow.INSTRUCTIONS)==set(benchmark.STAGES)
            assert workflow.prompt_hash()!=original_hash
            raise RuntimeError('experiment stopped')
    assert workflow.INSTRUCTIONS==original and workflow.prompt_hash()==pin


def test_v4_and_v5_benchmark_fingerprints_survive_promotion():
    for name,expected in [('v4','e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b'),
                          ('v5','6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f')]:
        with benchmark.variant(name):
            assert benchmark.workflow.prompt_hash()==expected


def test_isolated_change_keeps_six_other_prompts_and_all_schemas():
    original=copy.deepcopy(benchmark.workflow.INSTRUCTIONS)
    schemas={k:benchmark.workflow.response_schema(v) for k,v in benchmark.STAGES.items()}
    tokens=copy.deepcopy(benchmark.workflow.OUTPUT_TOKENS)
    with benchmark.variant('v6','GRADE'):
        assert benchmark.workflow.INSTRUCTIONS['GRADE']!=original['GRADE']
        assert all(benchmark.workflow.INSTRUCTIONS[k]==v for k,v in original.items() if k!='GRADE')
        assert {k:benchmark.workflow.response_schema(v) for k,v in benchmark.STAGES.items()}==schemas
        assert benchmark.workflow.OUTPUT_TOKENS==tokens


def test_frozen_corpus_separates_holdout_and_covers_all_stages_and_languages():
    data=benchmark.quality.read(benchmark.DATASET)
    assert len(data['cases'])==27
    assert len({c['id'] for c in data['cases']})==27
    assert sum(c['split']=='holdout' for c in data['cases'])==6
    stages=benchmark.quality.read(benchmark.STAGE_DATASET)['cases']
    assert {c['phase'] for c in stages}==set(benchmark.STAGES)
    assert {c['expected'].get('valid') for c in stages if c['phase']=='REVIEW'}=={True,False}
    grade=[c for c in stages if c['phase']=='GRADE']
    assert {s for c in grade for s in c['expected']['statuses'].values()}=={'CAPTURED','PARTIAL','INCORRECT','MISSED','UNCERTAIN'}
    assert {'grade-english','grade-vietnamese','grade-mixed','grade-minor-typo'}<={c['id'] for c in grade}


def _capture(folder, *, model='fixed-model', output=None, text='Tôi muốn luyện nghe.', operational_limits=True):
    folder.mkdir()
    case={'id':'extract-case','phase':'EXTRACT','expected':{'allowed':True}}
    benchmark.quality.write(folder/'cases.json',{'cases':[case]})
    benchmark.quality.write(folder/'manifest.json',dict(datasetHash='same',provider='gemini',model=model,
        apiVersion='v1beta/openai',temperature=0,outputTokens={'EXTRACT':2600},providerRetries=0,
        operationalLimitsEnabled=operational_limits))
    stage=folder/'extract-case/STAGE';stage.mkdir(parents=True)
    benchmark.quality.write(stage/'outcome.json',{'status':'SUCCEEDED'})
    benchmark.quality.write(stage/'01-request.json',{'schema':{},'options':{'temperature':0},'maxOutputTokens':2600,
        'messages':[{'role':'system','content':'variant prompt'},{'role':'user','content':json.dumps({'inputVi':text})}]})
    if output is not None: benchmark.quality.write(stage/'01-response.json',{'raw':output})
    benchmark.seal(folder)


def test_missing_and_malformed_outputs_do_not_count_as_schema_success(tmp_path):
    _capture(tmp_path/'missing')
    assert not benchmark.stage_metrics(tmp_path/'missing')['EXTRACT'][0]['schemaValid']
    _capture(tmp_path/'malformed',output={'allowed':True})
    assert not benchmark.stage_metrics(tmp_path/'malformed')['EXTRACT'][0]['schemaValid']


@pytest.mark.parametrize('change',[{'model':'different-model'},{'text':'Different input'},
                                   {'operational_limits':False}])
def test_comparison_rejects_unfair_model_or_input(tmp_path,change):
    _capture(tmp_path/'v4')
    _capture(tmp_path/'v5',**change)
    with pytest.raises(ValueError): benchmark.compare(tmp_path/'v4',tmp_path/'v5')


def test_offline_report_refuses_tampered_evidence(tmp_path):
    folder=tmp_path/'v4';_capture(folder)
    (folder/'extract-case/STAGE/outcome.json').write_text('{}',encoding='utf-8')
    with pytest.raises(ValueError,match='integrity mismatch'): benchmark.stage_metrics(folder)


def test_interleaved_round_preserves_unknowns_and_never_retries(monkeypatch,tmp_path):
    dataset=benchmark.quality.read(benchmark.DATASET)
    dataset['cases']=dataset['cases'][:3]
    path=tmp_path/'cases.json';benchmark.quality.write(path,dataset)
    observed=[]
    async def fake_phase(folder,payload,settings,budget):
        folder.mkdir()
        name='v4' if benchmark.workflow.PROMPT_VERSION.endswith('_V4') else 'v5'
        observed.append((name,folder.parent.name,payload.phase))
        outcome={'status':'UNKNOWN','error':{'type':'TimeoutError'}}
        benchmark.quality.write(folder/'outcome.json',outcome)
        return outcome
    async def offline_replay(folder):
        benchmark.quality.verify(folder)
        return {'networkCalls':0,'phases':[]}
    monkeypatch.setattr(benchmark.quality,'phase',fake_phase)
    monkeypatch.setattr(benchmark.quality,'replay',offline_replay)
    settings=benchmark.quality.Settings(scenario_enabled=True,primary_provider='gemini',gemini_api_key='unit-test-key')
    asyncio.run(benchmark.paired_pipeline(tmp_path/'paired',settings,path))
    assert [row[0] for row in observed]==['v4','v5','v5','v4','v4','v5']
    assert len(observed)==6 and all(row[2]=='EXTRACT' for row in observed)
    assert benchmark.workflow.PROMPT_VERSION=='SCENARIO_LISTENING_V5_GRADE_V4_1'
    for name in ('v4','v5'):
        benchmark.quality.verify(tmp_path/'paired'/name)
    with pytest.raises(FileExistsError):
        asyncio.run(benchmark.paired_pipeline(tmp_path/'paired',settings,path))
    assert len(observed)==6


def test_three_way_round_balances_execution_positions_without_touching_active_prompt(monkeypatch,tmp_path):
    dataset=benchmark.quality.read(benchmark.DATASET)
    dataset['cases']=dataset['cases'][:6]
    path=tmp_path/'cases.json';benchmark.quality.write(path,dataset)
    observed=[]
    async def fake_phase(folder,payload,settings,budget):
        folder.mkdir()
        observed.append(benchmark.workflow.PROMPT_VERSION)
        outcome={'status':'UNKNOWN','error':{'type':'TimeoutError'}}
        benchmark.quality.write(folder/'outcome.json',outcome)
        return outcome
    async def offline_replay(folder):
        benchmark.quality.verify(folder)
        return {'networkCalls':0,'phases':[]}
    monkeypatch.setattr(benchmark.quality,'phase',fake_phase)
    monkeypatch.setattr(benchmark.quality,'replay',offline_replay)
    settings=benchmark.quality.Settings(scenario_enabled=True,primary_provider='gemini',gemini_api_key='unit-test-key')
    asyncio.run(benchmark.paired_pipeline(tmp_path/'three',settings,path,('v4','v5','v6')))
    orders=[tuple(observed[i:i+3]) for i in range(0,18,3)]
    assert len(set(orders))==6
    for position in range(3):
        assert all(sum(row[position]==value for row in orders)==2 for value in set(observed))
    assert benchmark.workflow.PROMPT_VERSION=='SCENARIO_LISTENING_V5_GRADE_V4_1'


def test_v6_examples_use_the_deployed_output_contract():
    import re
    from app.scenario_prompts import historical_release
    INSTRUCTIONS = historical_release('scenario_prompts_v6').INSTRUCTIONS
    for phase,prompt in INSTRUCTIONS.items():
        for raw in re.findall(r'<output>\s*(.*?)\s*</output>',prompt,re.S):
            item=json.loads(raw)
            # Reviewer/question excerpts deliberately demonstrate selected items; validate their item schema.
            if phase=='REVIEW':
                assert set(item)=={'translationChecks','issues'}
                for row in item['translationChecks']: benchmark.workflow.TranslationCheck.model_validate(row)
            elif phase=='REVIEW_QUESTIONS':
                for row in item['questionChecks']: benchmark.workflow.QuestionCheck.model_validate(row)
            elif phase=='QUESTIONS':
                for activity in item['activities']:
                    for row in activity['questions']: benchmark.workflow.QuestionDraft.model_validate(row)
            else:
                benchmark.STAGES[phase].model_validate(item)
    with pytest.raises(ValueError,match='unknown prompt variant'):
        with benchmark.variant('unknown'): pass
