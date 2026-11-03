"""Correct personalized reviewer contracts without rewriting measured V1 prompts."""
from . import scenario_prompts_personalized_fewshot as previous

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V2_GRADE_V4"
EXAMPLES = previous.EXAMPLES
EXAMPLE_SET_VERSION = previous.EXAMPLE_SET_VERSION
EXAMPLE_SET_HASH = previous.EXAMPLE_SET_HASH
EXAMPLE_IDS = previous.EXAMPLE_IDS
STAGES = previous.STAGES
INSTRUCTIONS = dict(previous.INSTRUCTIONS)

# The V5 base contract is correct for legacy schemas; personalize only this release.
_CORRECTIONS = {
    "REVIEW_QUESTIONS": (
        ("all eight boolean checks",
         "all ten boolean checks (answerSupported,distinctMeaning,factSupported,questionMatchesFact,"
         "speechActSupported,atomicMeaning,noAnswerLeak,vietnameseNatural,skillAppropriate,groundingSupported)"),
    ),
    "REVIEW": (
        ("exactly SOURCE_ROLES,DIALOGUE,TRANSLATIONS in rubricChecks",
         "exactly five rubricChecks, one each for SOURCE_ROLES,DIALOGUE,TRANSLATIONS,PROFILE,LEARNING_GOAL"),
        ("include PROFILE and LEARNING_GOAL checks as well as the original three rubrics.",
         "apply the five rubricChecks listed in OUTPUT CONTRACT."),
    ),
}
for _stage, _corrections in _CORRECTIONS.items():
    for _old, _new in _corrections:
        if INSTRUCTIONS[_stage].count(_old) != 1:
            raise ValueError(f"Missing or ambiguous {_stage} output contract")
        INSTRUCTIONS[_stage] = INSTRUCTIONS[_stage].replace(_old, _new)
