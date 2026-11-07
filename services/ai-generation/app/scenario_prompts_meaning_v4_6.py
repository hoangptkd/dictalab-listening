"""V4 evaluation candidate with separated question and target audit inputs."""
from .scenario_prompts_meaning_v4_5 import (
    INSTRUCTIONS as V4_5_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS,
    MINIMUM_TARGETS, COLLECT_ITEM_ERRORS,
)

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_6"
INPUT_PROJECTION = "selected-targets-and-learner-responses.v1;separated-question-target-audit.v2"

INSTRUCTIONS = {**V4_5_INSTRUCTIONS,
    "GRADE": V4_5_INSTRUCTIONS["GRADE"].replace(
        "Source text and reference answers explain the target but never supply\nmissing learner evidence.",
        "Selected meaningEn and requiredParts define the target. Question wording can resolve a short\n"
        "answer's referent, but supplied context never demonstrates learner recall."
    ),
    "REVIEW_QUESTIONS": V4_5_INSTRUCTIONS["REVIEW_QUESTIONS"] + """
Input questions contains each actual prompt and reference answer once; targets contains source,
meanings and questionGrounding metadata. Join by activityId and question/meaning IDs. Derive
requestedMeaningEn from promptVi and answerUnitsVi from answerVi, then compare with the targets;
factEn is the author's proposed target, not evidence of what the question asks the learner to recall.
""",
}
