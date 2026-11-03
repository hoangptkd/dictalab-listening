"""Compact stage-local references with balanced question-review verdicts."""
import hashlib
import json
from pathlib import Path

from . import scenario_prompts_personalized_fewshot_v5 as previous
from .scenario_prompts_personalized_fewshot import MARKER, compile_instructions

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V6_GRADE_V5"
STAGES = previous.STAGES
GRADE = previous.GRADE
ASSET = json.loads(Path(__file__).with_name('scenario_personalized_fewshot_examples_v2.json').read_text(encoding='utf-8'))
EXAMPLES = ASSET['examples']
EXAMPLE_SET_VERSION = ASSET['version']
EXAMPLE_SET_HASH = hashlib.sha256(json.dumps(
    ASSET, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
EXAMPLE_IDS = {stage: [e['id'] for e in EXAMPLES if e['stage'] == stage] for stage in STAGES}

# Keep the current rules while replacing only the frozen demonstration blocks.
_base = dict(previous.INSTRUCTIONS)
for _stage in STAGES:
    _prefix, _examples_marker, _tail = _base[_stage].partition('\n\nEXAMPLES\n')
    _examples, _input_marker, _suffix = _tail.partition('\n\nINPUT\n')
    if not _examples_marker or not _input_marker:
        raise ValueError(f'Missing {_stage} example boundary')
    _base[_stage] = _prefix + '\n\n' + MARKER + _input_marker + _suffix
INSTRUCTIONS = compile_instructions(EXAMPLES, _base)
for _stage in STAGES:
    INSTRUCTIONS[_stage] = INSTRUCTIONS[_stage].replace(
        'EXAMPLES\n',
        'EXAMPLES\nExample inputs omit runtime metadata and duplicate question copies, showing only stage-relevant context. '
        'Use the full learningContext from the actual input. Output examples remain complete.\n', 1)
