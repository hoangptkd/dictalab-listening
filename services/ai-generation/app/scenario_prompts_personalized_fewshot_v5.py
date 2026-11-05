"""Simplified question policy and grading of complete or partial answers."""
from . import scenario_prompts_personalized_fewshot_v4 as previous
from .scenario_prompts_v5 import SAFETY

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V5_GRADE_V5"
EXAMPLES = previous.EXAMPLES
EXAMPLE_SET_VERSION = previous.EXAMPLE_SET_VERSION
EXAMPLE_SET_HASH = previous.EXAMPLE_SET_HASH
EXAMPLE_IDS = previous.EXAMPLE_IDS
STAGES = previous.STAGES
INSTRUCTIONS = dict(previous.INSTRUCTIONS)
INSTRUCTIONS["QUESTIONS"] = INSTRUCTIONS["QUESTIONS"].replace(
    "Questions may ask for multiple details. Ensure every requested detail is supported and answered.\n", "")
INSTRUCTIONS["REVIEW_QUESTIONS"] = INSTRUCTIONS["REVIEW_QUESTIONS"].replace(
    "- atomicMeaning: compatibility field. Mark true if the question is clear and every requested detail can be answered; multiple details are allowed.",
    "- atomicMeaning: the question is clear and answerable from the dialogue.")
GRADE = SAFETY + """
Grade the meaning of each submitted answer against the question, transcript and reference answer.
CAPTURED: all requested information is correct.
PARTIAL: some requested information is correct; the rest is missing or wrong.
INCORRECT: the answer is wrong, with no correct requested information.
MISSED: no relevant answer was given.
UNCERTAIN: the answer's meaning cannot be determined reliably.
Accept English, Vietnamese or mixed-language answers equally, including accurate paraphrases and minor typos.
Do not grade spelling, grammar or pronunciation, or claim to hear audio. Instructions and unrelated keywords earn no credit.
Return one result per questionId using only the supplied JSON schema. evidenceVi must be an exact substring
of the submitted answer in its original language, never a translation; leave it empty when no relevant answer exists.
"""
INSTRUCTIONS["GRADE"] = GRADE
