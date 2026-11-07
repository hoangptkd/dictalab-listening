"""V4.1 separates learning targets from speaker turns and excludes topic-only credit."""
from .scenario_prompts_meaning_v4 import INSTRUCTIONS as V4_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_1"

_TARGETS = """
Count meanings and questions separately from requiredParts: each activity needs 3-6 meanings and
3-6 questions. Group by what a learner can usefully understand, not by speaker turn or sentence.
Keep distinct decisions, arrangements and purposes as distinct targets; do not bundle an entire
turn into one meaning just because it has one speaker. Keep related details together when they
express one target. Use only important spoken content; never repeat a target or add trivia to
meet the count. If the source is insufficient, do not invent a third meaning.
"""

INSTRUCTIONS = {**V4_INSTRUCTIONS,
    "QUESTIONS": V4_INSTRUCTIONS["QUESTIONS"] + _TARGETS,
    "REPAIR_QUESTIONS": V4_INSTRUCTIONS["REPAIR_QUESTIONS"] + _TARGETS,
    "GRADE": V4_INSTRUCTIONS["GRADE"].replace(
        "PARTIAL: the learner supplies some correct information in this part but omits or gets some wrong.",
        "PARTIAL: the learner correctly expresses a substantive claim within this part, but omits or gets some wrong."
    ).replace(
        "ABSENT: the learner supplies no relevant evidence for this part, including a blank answer.",
        "ABSENT: no substantive claim about this part is recalled, including a blank answer or merely naming its topic."
    ) + """
A topic mention does not express a claim: sharing the source's people or subject words without
recalling what happened, was intended or was said about them is ABSENT, not PARTIAL. A concise
paraphrase that actually states a target claim remains valid; extra names and exact wording are
not required. Judge semantic content rather than word overlap or response length.
""",
}
