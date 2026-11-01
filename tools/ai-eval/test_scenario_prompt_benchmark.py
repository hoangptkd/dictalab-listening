"""Protect V4 and prevent misleading benchmark comparisons; no network needed."""
import copy
import json
from pathlib import Path

import pytest

import scenario_prompt_benchmark as benchmark


def test_candidate_is_opt_in_and_restores_v4_even_after_failure():
    workflow=benchmark.workflow
    original=copy.deepcopy(workflow.INSTRUCTIONS)
    original_hash=workflow.prompt_hash()
    pin=(benchmark.ROOT/'src/main/resources/scenario/workflow-v1.sha256').read_text().strip()
    assert workflow.PROMPT_VERSION=='SCENARIO_LISTENING_V4' and original_hash==pin
    with pytest.raises(RuntimeError):
        with benchmark.variant('v5'):
            assert set(workflow.INSTRUCTIONS)==set(benchmark.STAGES)
            assert workflow.prompt_hash()!=original_hash
            raise RuntimeError('experiment stopped')
    assert workflow.INSTRUCTIONS==original and workflow.prompt_hash()==pin


def test_isolated_change_keeps_six_other_prompts_and_all_schemas():
    original=copy.deepcopy(benchmark.workflow.INSTRUCTIONS)
    schemas={k:benchmark.workflow.response_schema(v) for k,v in benchmark.STAGES.items()}
    tokens=copy.deepcopy(benchmark.workflow.OUTPUT_TOKENS)
    with benchmark.variant('v5','GRADE'):
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


def _capture(folder, *, model='fixed-model', output=None, text='Tôi muốn luyện nghe.'):
    folder.mkdir()
    case={'id':'extract-case','phase':'EXTRACT','expected':{'allowed':True}}
    benchmark.quality.write(folder/'cases.json',{'cases':[case]})
    benchmark.quality.write(folder/'manifest.json',dict(datasetHash='same',provider='gemini',model=model,
        apiVersion='v1beta/openai',temperature=0,outputTokens={'EXTRACT':2600},providerRetries=0))
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


@pytest.mark.parametrize('change',[{'model':'different-model'},{'text':'Different input'}])
def test_comparison_rejects_unfair_model_or_input(tmp_path,change):
    _capture(tmp_path/'v4')
    _capture(tmp_path/'v5',**change)
    with pytest.raises(ValueError): benchmark.compare(tmp_path/'v4',tmp_path/'v5')


def test_offline_report_refuses_tampered_evidence(tmp_path):
    folder=tmp_path/'v4';_capture(folder)
    (folder/'extract-case/STAGE/outcome.json').write_text('{}',encoding='utf-8')
    with pytest.raises(ValueError,match='integrity mismatch'): benchmark.stage_metrics(folder)
