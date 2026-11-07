"""Compact V4 candidate: aligned meaning parts, complete questions and accented Vietnamese."""
from .scenario_prompts_meaning_v4_1 import INSTRUCTIONS as V4_1_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS
from .scenario_prompts_meaning_v1 import _GROUNDING
from .scenario_prompts_v5 import SAFETY

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_3"

_ALIGNMENT = """
Questions test the complete meaning referenced by meaningIds. A reference answer must express
every required part. A detail already supplied in a question is context, not learner recall.
Do not reference a broader target than the question tests. Keep praise or filler out of a target
unless it matters to understanding. Preserve proposals, plans and uncertainty as stated.
Use standard Vietnamese with full diacritics in promptVi, answerVi and reasonVi.
"""

_EXAMPLE = """
<alignment_example>
Source s1: "Our train leaves at ten from platform four."
Meaning m1: "The train departs at ten from platform four."
Required parts: p1="The departure time is ten."; p2="The departure platform is four."
Each part cites its exact substring in s1.
Aligned question: "Chuyến tàu khởi hành lúc mấy giờ và từ sân ga nào?"
Reference: "Lúc mười giờ, từ sân ga số bốn." Both parts must be recalled.
Narrow question: "Tàu rời sân ga số bốn lúc mấy giờ?" Reference: "Mười giờ."
This is NOT aligned to m1: the platform is given away, not recalled. Broaden the question to ask
for both parts, or reference only a meaningful target it actually tests. Do not change the source.
</alignment_example>
"""

INSTRUCTIONS = {**V4_1_INSTRUCTIONS,
    "QUESTIONS": SAFETY + """
<role>Create listening questions from the completed transcript.</role>
<task>
Return a1,a2,a3. For EACH activity create 3-6 important nonduplicate meanings m1..mN and 3-6
questions q1..qN. The counts apply to meanings and questions, not the number of required parts.
Select distinct learning targets, not one target per speaker turn. Cover the main idea and useful
reasons, decisions, consequences or practical details already spoken. Do not pad with trivia.
Each assessment has policyVersion scenario-meaning-assessment.v2. Each meaning has meaningEn,
skill,supportKind,inferenceReasonEn,evidence and requiredParts (1-4 parts p1..pN). Parts together
express exactly that meaning, including essential reasons or conditions; split independently
omittable information, not individual words. Each part has id,meaningEn,evidence: cite exact source
substrings contained within the parent meaning's evidence. Do not add extra claims in the parts.
Each question has factEn,speechAct,skill,supportKind,inferenceReasonEn,promptVi,answerVi,evidence,
meaningIds (1-3 IDs). Ask for related details together when useful. Each meaning must be tested;
avoid nested or repeated targets and repeated questions. Include each referenced meaning's source
evidence in the question, possibly within a longer exact quote. At least one meaning and one
question address learningContext.focusSkill. If the transcript genuinely lacks enough targets,
do not invent content. Core later selects questions; do not predict a comprehension percentage.
</task>
""" + _ALIGNMENT + _EXAMPLE + _GROUNDING,
    "REVIEW_QUESTIONS": V4_1_INSTRUCTIONS["REVIEW_QUESTIONS"] + _ALIGNMENT + """
First derive requestedMeaningEn from the question as actually worded and answerUnitsVi from
the actual reference answer. Then compare those independently derived units to every required
part in meaningIds. If any part is not elicited or is absent from the reference, set
questionMatchesFact=false even when the quoted source supports it. Evaluate distinctMeaning
across all targets, including a whole claim repeated inside a broader target.
""" + _EXAMPLE,
    "REPAIR_QUESTIONS": V4_1_INSTRUCTIONS["REPAIR_QUESTIONS"] + _ALIGNMENT + _EXAMPLE,
}
