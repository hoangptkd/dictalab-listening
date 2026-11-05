"""Flexible question and word-count policy for newly pinned personalized jobs."""
import json
from . import scenario_prompts_personalized_fewshot_v2 as previous

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V3_GRADE_V4"
EXAMPLES = previous.EXAMPLES
EXAMPLE_SET_VERSION = previous.EXAMPLE_SET_VERSION
EXAMPLE_SET_HASH = previous.EXAMPLE_SET_HASH
STAGES = previous.STAGES
EXAMPLE_IDS = {stage: [i for i in ids if i != "review-questions-separate-meanings-and-skills"]
               for stage, ids in previous.EXAMPLE_IDS.items()}
INSTRUCTIONS = dict(previous.INSTRUCTIONS)
_replacements = {
    "GENERATE": [("Aim near targetWordsPerActivity within all word limits. The backend counts actual words.",
                  "Use targetWordsPerActivity as a flexible guide, not a mandatory word limit.")],
    "QUESTIONS": [("Test one requested meaning per question; a compact main idea or a reason is valid, not just a name or number.",
                   "Questions may ask for multiple details. Ensure every requested detail is supported and answered.")],
    "REVIEW_QUESTIONS": [("- atomicMeaning: exactly one requested meaning. A time with day/period, a price with currency, or one compact main idea\n  is one unit; separate independently answerable details are multiple answerUnitsVi, even if bundled in a single phrase.",
                          "- atomicMeaning: compatibility field. Mark true if the question is clear and every requested detail can be answered; multiple details are allowed.")],
    "REVIEW": [("Evaluate the three required rubrics and every segment translation; explain specific defects without rewriting.",
                "Evaluate the five required rubrics and every segment translation; explain specific defects without rewriting.")],
}
for _stage, _changes in _replacements.items():
    for _old, _new in _changes:
        if INSTRUCTIONS[_stage].count(_old) != 1:
            raise ValueError(f"Missing or ambiguous {_stage} policy")
        INSTRUCTIONS[_stage] = INSTRUCTIONS[_stage].replace(_old, _new)

# The rejected example teaches the retired one-detail restriction; do not send it.
_example = next(e for e in EXAMPLES if e["id"] == "review-questions-separate-meanings-and-skills")
_pair = ("Example input JSON:\n" + json.dumps(_example["input"], ensure_ascii=False, separators=(",", ":"))
         + "\nExpected output JSON:\n" + json.dumps(_example["expectedOutput"], ensure_ascii=False, separators=(",", ":")))
if INSTRUCTIONS["REVIEW_QUESTIONS"].count(_pair) != 1:
    raise ValueError("Missing or ambiguous retired reviewer example")
INSTRUCTIONS["REVIEW_QUESTIONS"] = INSTRUCTIONS["REVIEW_QUESTIONS"].replace(_pair, "")
