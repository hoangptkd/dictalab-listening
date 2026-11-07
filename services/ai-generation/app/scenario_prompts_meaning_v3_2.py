"""V3-schema evaluation candidate with a learner-only grading projection."""
from .scenario_prompts_meaning_v3_1 import INSTRUCTIONS as V3_1_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V3_2"
INPUT_PROJECTION = "selected-targets-and-learner-responses.v1"

INSTRUCTIONS = {**V3_1_INSTRUCTIONS,
    "GRADE": V3_1_INSTRUCTIONS["GRADE"].replace(
        "Use the activity's private meaning map, transcript and reference answers.",
        "Compare the selected private meaningEn targets with learner responses only. Question wording\n"
        "can resolve a short answer's referent, but supplied context never demonstrates learner recall."
    ),
}
