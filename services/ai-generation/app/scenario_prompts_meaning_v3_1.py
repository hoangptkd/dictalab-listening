"""V3-schema candidate: topic mentions do not demonstrate a substantive claim."""
from .scenario_prompts_meaning_v3 import INSTRUCTIONS as V3_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V3_1"

INSTRUCTIONS = {**V3_INSTRUCTIONS,
    "GRADE": V3_INSTRUCTIONS["GRADE"].replace(
        "PARTIAL: some of this meaning is correct but part is missing or wrong.",
        "PARTIAL: the learner correctly expresses a substantive claim within this meaning, but part is missing or wrong."
    ) + """
A topic mention alone recalls no substantive claim: shared people or subject words without what
happened, was intended or was said are UNASSESSED in RECAP, MISSED in CHECKS, with empty evidence.
Concise paraphrases expressing the complete meaning remain CAPTURED; extra names, exact wording
and response length are not requirements. Credit semantic claims, not overlapping vocabulary.
""",
}
