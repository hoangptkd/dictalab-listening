"""V9: actionable repair feedback and conservative item-level review reconciliation."""
from .scenario_prompts_personalized_fewshot_v8 import (
    INSTRUCTIONS as V8_INSTRUCTIONS, HARNESS_POLICY as V8_POLICY,
    GRADE, QUESTION_REVIEW_VERSION, STAGES, EXAMPLES, EXAMPLE_IDS,
    EXAMPLE_SET_HASH, EXAMPLE_SET_VERSION,
)

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V9_HARNESS_V2_GRADE_V5"
HARNESS_POLICY = {**V8_POLICY, "version": "scenario-quality-harness.v2",
    "acceptance": "changed-targets-pass-no-target-regression-retain-unresolved",
    "feedback": "localized-constraint-and-review-reason",
    "unchangedFindings": "retain-prior-failures-and-new-warnings"}

_QUESTION_CONTRACT = """
Repair contract:
- Copy quoteEn verbatim from the named segment in the SAME activity; keep qualifiers and small words.
- An agreement needs the proposal and acceptance from two speakers, cited in transcript order.
  If that activity cannot support an agreement, choose another grounded meaning there; do not relabel an unsupported claim.
- For direct or synthesis support, inferenceReasonEn is empty and skill is not supported-inference.
  For inference, the profile must permit it, skill is supported-inference, and inferenceReasonEn explains the premises.
- Recheck every field of each replacement against this contract, including fields that were not initially flagged.
"""
_RECHECK = """
When repairContext is supplied, compare the changed items with the transcript and the prior findings.
Explain whether each reported defect was actually corrected. A changed neighboring item does not fix an unchanged item.
Assess untouched content independently; report any remaining or newly noticed defect without claiming the patch caused it.
Keep temporal scope, certainty and conditions exact. Do not narrow an imprecise source expression in the translation.
"""
INSTRUCTIONS = {**V8_INSTRUCTIONS,
    "REPAIR_QUESTIONS": V8_INSTRUCTIONS["REPAIR_QUESTIONS"] + _QUESTION_CONTRACT,
    "REPAIR_TRANSLATIONS": V8_INSTRUCTIONS["REPAIR_TRANSLATIONS"] + """
Use each issue's reasonVi to locate the defect, then verify it against the English source.
Correct every supported finding in its target, including omissions and changes in temporal scope or certainty.
Return an unchanged item only when the reported defect is not supported; do not treat it as a completed fix.
""",
    "REVIEW_QUESTIONS": V8_INSTRUCTIONS["REVIEW_QUESTIONS"] + _RECHECK,
    "REVIEW": V8_INSTRUCTIONS["REVIEW"] + _RECHECK,
}
