"""Evaluation-only few-shot candidate; production keeps the approved V5/V4 mix."""
import json
from pathlib import Path

from .scenario_prompts_v5 import INSTRUCTIONS as V5_INSTRUCTIONS
from .scenario_workflow import V4_INSTRUCTIONS

PROMPT_VERSION = 'SCENARIO_LISTENING_V5_FEWSHOT_1_GRADE_V4'
EXAMPLES_PATH = Path(__file__).with_name('scenario_fewshot_examples_v1.json')
EXAMPLES = json.loads(EXAMPLES_PATH.read_text(encoding='utf-8'))['examples']
INSTRUCTIONS = {**V5_INSTRUCTIONS, 'GRADE': V4_INSTRUCTIONS['GRADE']}
for stage in V5_INSTRUCTIONS:
    demonstrations = [example for example in EXAMPLES if example['stage'] == stage]
    if not demonstrations:
        continue
    pairs = '\n\n'.join(
        'Example input JSON:\n' + json.dumps(example['input'], ensure_ascii=False, separators=(',', ':'))
        + '\nExpected output JSON:\n' + json.dumps(example['expectedOutput'], ensure_ascii=False, separators=(',', ':'))
        for example in demonstrations)
    INSTRUCTIONS[stage] = V5_INSTRUCTIONS[stage].replace(
        'EXAMPLES\nNone: this candidate is evaluated zero-shot.',
        'EXAMPLES\nThese fictional demonstrations illustrate the same task and output contract. '
        'Use their reasoning pattern; do not copy their scene, facts, names or identifiers into a different input.\n' + pairs)
