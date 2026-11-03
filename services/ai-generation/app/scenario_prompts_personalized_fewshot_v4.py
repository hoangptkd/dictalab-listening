"""V3 prompts with advisory rather than blocking reviewer verdicts."""
from . import scenario_prompts_personalized_fewshot_v3 as previous

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V4_GRADE_V4"
INSTRUCTIONS = previous.INSTRUCTIONS
EXAMPLES = previous.EXAMPLES
EXAMPLE_SET_VERSION = previous.EXAMPLE_SET_VERSION
EXAMPLE_SET_HASH = previous.EXAMPLE_SET_HASH
EXAMPLE_IDS = previous.EXAMPLE_IDS
STAGES = previous.STAGES
