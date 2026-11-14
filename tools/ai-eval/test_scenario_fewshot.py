"""Validate all ten demonstrations and preserve the production baseline; no network."""
import json

import pytest

import scenario_prompt_benchmark as benchmark
from app.scenario_prompts import historical_release
candidate = historical_release('scenario_prompts_fewshot')


def test_ten_demonstrations_are_schema_valid_and_separate_from_evaluation():
    examples = candidate.EXAMPLES
    assert len(examples) == len({e['id'] for e in examples}) == 10
    assert {e['stage'] for e in examples} == set(benchmark.STAGES) - {'GRADE'}
    evaluation = benchmark.quality.read(benchmark.DATASET)['cases']
    evaluation += benchmark.quality.read(benchmark.STAGE_DATASET)['cases']
    briefs = {e.get('inputVi', e.get('input', {}).get('inputVi')) for e in evaluation}
    for example in examples:
        assert example['metadata']['labelSource'] == 'agent-authored-reference-not-human-gold'
        data, expected, stage = example['input'], example['expectedOutput'], example['stage']
        if 'inputVi' in data:
            assert data['inputVi'] not in briefs
        benchmark.STAGES[stage].model_validate(expected)
        if expected.get('valid') is False:
            with pytest.raises(ValueError):
                benchmark.validate_stage(stage, expected, data)
        else:
            benchmark.validate_stage(stage, expected, data)


def test_fewshot_only_adds_demonstrations_to_six_v5_stages_and_keeps_grade_v4():
    workflow = benchmark.workflow
    assert candidate.INSTRUCTIONS['GRADE'] == workflow.INSTRUCTIONS['GRADE'] == workflow.V4_INSTRUCTIONS['GRADE']
    for stage in benchmark.STAGES:
        if stage == 'GRADE':
            continue
        before, after = workflow.INSTRUCTIONS[stage], candidate.INSTRUCTIONS[stage]
        assert before.split('\nEXAMPLES\n')[0] == after.split('\nEXAMPLES\n')[0]
        assert before.rsplit('\nINPUT\n', 1)[1] == after.rsplit('\nINPUT\n', 1)[1]
        assert 'None: this candidate is evaluated zero-shot.' not in after
        for example in (e for e in candidate.EXAMPLES if e['stage'] == stage):
            assert json.dumps(example['expectedOutput'], ensure_ascii=False, separators=(',', ':')) in after


def test_selecting_fewshot_never_changes_the_active_pin_or_frozen_variants():
    pin = (benchmark.ROOT/'src/main/resources/scenario/workflow-v1.sha256').read_text().strip()
    with benchmark.variant('active'):
        assert benchmark.workflow.prompt_hash() == pin
    with pytest.raises(RuntimeError):
        with benchmark.variant('fewshot'):
            assert benchmark.workflow.PROMPT_VERSION == candidate.PROMPT_VERSION
            assert benchmark.workflow.prompt_hash() != pin
            raise RuntimeError('experiment interrupted')
    assert benchmark.workflow.prompt_hash() == pin
