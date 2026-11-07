"""V4.3 instructions with provider-enforced minimum learning-target counts."""
from .scenario_prompts_meaning_v4_3 import INSTRUCTIONS as V4_3_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_4"
MINIMUM_TARGETS = True
COLLECT_ITEM_ERRORS = True

INSTRUCTIONS = {**V4_3_INSTRUCTIONS,
    "REPAIR_QUESTIONS": V4_3_INSTRUCTIONS["REPAIR_QUESTIONS"] + """
When a question omits referenced meaning evidence, include every evidence quote from each of its
referenced meanings in the question evidence, preserving segment IDs and exact substrings.
Contextual quotes are required too, even if another quote supports the reference answer.
""",
}
