"""V4 candidate with one shared source/target/question consistency contract."""
from .scenario_prompts_meaning_v4_4 import (
    INSTRUCTIONS as V4_4_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS,
    MINIMUM_TARGETS, COLLECT_ITEM_ERRORS,
)
from .scenario_prompts_meaning_v4_3 import _ALIGNMENT, _EXAMPLE
from .scenario_prompts_meaning_v1 import INSTRUCTIONS as V1_INSTRUCTIONS

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_5"

_CONSISTENCY = """
Align these semantic claims: source-supported meaningEn = union of requiredParts = information
actually requested by the question = complete reference answer. Preserve actor, negation, modality,
conditions and speech act throughout. Split independently omittable claims into separate parts;
do not hide a parent claim outside its parts or add a new claim inside them. Question context
cannot supply a separately scored claim. Broaden the question/reference or narrow/split the
meaning to an important source-grounded target; multi-detail questions are optional. Cite every
referenced meaning's evidence in the question, including contextual quotes. Use standard accented
Vietnamese. Never change the transcript to make a target fit.
"""

INSTRUCTIONS = {**V4_4_INSTRUCTIONS,
    "QUESTIONS": V4_4_INSTRUCTIONS["QUESTIONS"].replace(_ALIGNMENT + _EXAMPLE, _CONSISTENCY),
    "REVIEW_QUESTIONS": V1_INSTRUCTIONS["REVIEW_QUESTIONS"] + _CONSISTENCY + """
Derive requestedMeaningEn from what the actual question asks the learner to supply and answerUnitsVi
from the actual reference answer before comparing them with meaningIds. Check parent-to-parts
equivalence as meaningSupported/groundingSupported, independently omittable bundled claims as
distinctMeaning, and complete question/reference coverage as questionMatchesFact. A source quote
or a detail given in the question does not repair missing learner recall. Set the relevant flags
false for any mismatch; use existing flags and explain the missing or changed claim in reasonVi.
""",
    "REPAIR_QUESTIONS": V1_INSTRUCTIONS["REPAIR_QUESTIONS"] + _CONSISTENCY + """
Use policyVersion scenario-meaning-assessment.v2; each meaning retains 1-4 requiredParts with
id,meaningEn,evidence. Each part cites exact source substrings inside its parent's evidence.
Preserve 3-6 meanings and 3-6 questions per activity and all non-targeted items.
""",
}
