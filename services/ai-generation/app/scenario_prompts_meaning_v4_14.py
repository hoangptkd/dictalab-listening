"""Unactivated V4.13 few-shot candidate; no workflow or grading-policy change."""
import hashlib
import json
from pathlib import Path

from . import scenario_prompts_meaning_v4_13 as previous
from .scenario_prompts_meaning_v4_13 import (
    POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS, MINIMUM_TARGETS,
    COLLECT_ITEM_ERRORS, INPUT_PROJECTION,
)
from .scenario_prompts_personalized_fewshot_v9 import EXAMPLES as HISTORICAL_EXAMPLES

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_14"
ASSET = json.loads(Path(__file__).with_name("scenario_assessment_fewshot_v1.json").read_text(encoding="utf-8"))
EXAMPLE_SET_VERSION = ASSET["version"]
# These four stages already contain compatible demonstrations; do not append them twice.
EXAMPLES = [e for e in HISTORICAL_EXAMPLES if e["stage"] in ("EXTRACT", "GENERATE", "TRANSLATE", "REVIEW")] + ASSET["examples"]
EXAMPLE_IDS = {stage: [e["id"] for e in EXAMPLES if e["stage"] == stage] for stage in previous.INSTRUCTIONS}
if (len({e["id"] for e in EXAMPLES}) != len(EXAMPLES)
        or {e["stage"] for e in EXAMPLES} != set(previous.INSTRUCTIONS)
        or any(not ids for ids in EXAMPLE_IDS.values())):
    raise ValueError("Invalid scoped few-shot IDs/stages")
EXAMPLE_SET_HASH = hashlib.sha256(json.dumps({"version": EXAMPLE_SET_VERSION,
    "labelSource": ASSET["labelSource"], "examples": EXAMPLES},
    ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

INSTRUCTIONS = dict(previous.INSTRUCTIONS)
for _stage in INSTRUCTIONS:
    _examples = [e for e in ASSET["examples"] if e["stage"] == _stage]
    if _examples:
        INSTRUCTIONS[_stage] += """
<few_shot_examples>
Fictional demonstrations for this stage only. Each scope says whether the output is complete or
an item-level excerpt. Excerpts illustrate judgments, not reduced runtime coverage: the actual
response must cover every supplied item and satisfy the full schema and required counts.
Example inputs omit unrelated context. Use the actual input/profile, not example names, facts,
difficulty or verdicts. A rejected draft is example input; expectedOutput is the desired response.
""" + "\n".join(json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in _examples) + "\n</few_shot_examples>\n"
