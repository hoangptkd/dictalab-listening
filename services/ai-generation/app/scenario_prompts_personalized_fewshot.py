"""Versioned stage-local demonstrations; baseline prompts remain replayable."""
import hashlib
import json
from pathlib import Path

from . import scenario_prompts_personalized as baseline

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V1_GRADE_V4"
EXAMPLES_PATH = Path(__file__).with_name("scenario_personalized_fewshot_examples_v1.json")
ASSET = json.loads(EXAMPLES_PATH.read_text(encoding="utf-8"))
EXAMPLES = ASSET["examples"]
EXAMPLE_SET_VERSION = ASSET["version"]
EXAMPLE_SET_HASH = hashlib.sha256(json.dumps(
    ASSET, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
STAGES = ("EXTRACT", "GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW")
MARKER = "EXAMPLES\nNone: this candidate is evaluated zero-shot."


def compile_instructions(examples, instructions=None):
    if (not examples or len({e["id"] for e in examples}) != len(examples)
            or {e["stage"] for e in examples} != set(STAGES)):
        raise ValueError("Invalid personalized few-shot example IDs/stages")
    result = dict(baseline.INSTRUCTIONS if instructions is None else instructions)
    for stage in STAGES:
        if result[stage].count(MARKER) != 1:
            raise ValueError("Missing or ambiguous personalized examples marker")
        pairs = "\n\n".join(
            "Example input JSON:\n" + json.dumps(e["input"], ensure_ascii=False, separators=(",", ":"))
            + "\nExpected output JSON:\n" + json.dumps(e["expectedOutput"], ensure_ascii=False, separators=(",", ":"))
            for e in examples if e["stage"] == stage)
        result[stage] = result[stage].replace(MARKER,
            "EXAMPLES\nThese fictional reference pairs illustrate this stage's output contract. "
            "Follow the current input and learningContext, not the examples' names, facts, roles or difficulty. "
            "Reviewer pairs include both accepted and rejected content; never assume valid=true.\n" + pairs)
    return result


INSTRUCTIONS = compile_instructions(EXAMPLES)
EXAMPLE_IDS = {stage: [e["id"] for e in EXAMPLES if e["stage"] == stage] for stage in STAGES}
