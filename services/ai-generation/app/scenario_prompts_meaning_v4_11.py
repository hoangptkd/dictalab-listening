"""Unactivated candidate: question-scoped targets and evidenced, frozen audit findings.

V4.9/V4.10 are archived experiments. Their results do not certify this candidate.
"""
from .scenario_prompts_meaning_v4_7 import HARNESS_POLICY, OUTPUT_TOKENS as AUDIT_TOKENS
from .scenario_prompts_meaning_v4_6 import INSTRUCTIONS as PREVIOUS
from .scenario_prompts_meaning_v1 import _GROUNDING
from .scenario_prompts_v5 import SAFETY

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_11"
POLICY_VERSION = "scenario-meaning-assessment.v2"
MINIMUM_TARGETS = True
COLLECT_ITEM_ERRORS = True
INPUT_PROJECTION = "selected-targets-and-learner-responses.v1;scoped-question-parts.v2"
AUDIT_PROTOCOL = "independent-audit.v3-evidenced-coverage"
EVIDENCE_AUDIT = True
OUTPUT_TOKENS = {**AUDIT_TOKENS, "AUDIT_QUESTIONS": 8500}

_TARGETS = """
The scoring target is what the learner must supply, not everything mentioned in the question.
Build meaningEn and requiredParts from the source-grounded reference answer to the actual question.
Given context may identify a referent, but is not an additional recalled fact. A reason-only question
targets its reason; a question asking several details targets all those details. Never penalize a
complete answer for omitting information the question did not ask for.
requiredParts contains 1-4 independently recallable propositions, not fragments of grammar.
Keep shared context in each part when needed to understand it. Two independently omittable benefits
are two parts; repeating their common subject does not duplicate the scored information.
The union of parts must express the whole parent meaning, including any commitment or condition,
with no extra claim. Preserve possibility versus certainty and proposal versus agreement throughout.
Use standard Vietnamese with diacritics in promptVi, answerVi and reasonVi.
"""

INSTRUCTIONS = {**PREVIOUS,
    "EXTRACT": PREVIOUS["EXTRACT"] + """
Do not assign authority, responsibility or agreement to a participant merely from their presence.
Keep a supplied role unchanged. If an unspecified role must be chosen, identify it as an assumption
for confirmation; do not repeat an explicit user fact as a new assumption.
""",
    "GENERATE": PREVIOUS["GENERATE"] + """
Develop each activity enough to support at least three distinct, useful listening targets through
its natural exchange. This is a content requirement, not a request to plan questions or list facts.
If substance is thin, develop the existing topic and participants' motivations; do not add trivia.
""",
    "QUESTIONS": SAFETY + """
<role>Design fair listening checks from the completed dialogue.</role>
<task>
Return activities a1,a2,a3. Each contains assessment {policyVersion,meanings} and questions.
Use policyVersion scenario-meaning-assessment.v2. For EACH activity create 3-6 distinct important
meanings m1..mN and 3-6 questions q1..qN. These counts are independent of segments and requiredParts.
Read the transcript, choose useful understanding to check, and formulate a question and complete
reference answer. Then define exactly what that answer demonstrates as its meaning and parts.
Each meaning has id,meaningEn,skill,supportKind,inferenceReasonEn,evidence,requiredParts.
Each required part has id p1..pN,meaningEn,evidence, with quotes inside its parent's evidence.
Each question has id,factEn,speechAct,skill,supportKind,inferenceReasonEn,promptVi,answerVi,evidence,
meaningIds (1-3 IDs in this activity). Every meaning must be tested. Include every referenced
meaning's evidence in the question evidence. Several related details may share one question.
Avoid overlapping scored targets and repetitive questions; an alternative question may test a
target again without making that target a duplicate. Cover learningContext.focusSkill with at
least one meaning and question. Do not invent facts or pad the map to satisfy counts.
Core selects the checks later. Do not predict a comprehension percentage or proficiency score.
</task>
""" + _TARGETS + _GROUNDING,
    "AUDIT_MEANINGS": SAFETY + """
Independently read each supplied meaningEn. Return its activityId,meaningId and 1-6 claims c1..cN.
Each claim textEn states one independently recallable proposition with its necessary context.
Retain every assertion in the parent, including agreement, reasons, negation and uncertainty.
Separate two benefits that a person could recall independently. Keep actor, action and its value
together when they express one proposition. Shared actor/context is not a duplicate claim.
Do not use questions, requiredParts or transcript; none are supplied. Do not correct the parent.
""",
    "AUDIT_QUESTIONS": SAFETY + """
Audit actual questions and reference answers against their assigned scoring parts.
For each question return requestedEn (what the learner must supply), givenEn (context already
provided), and one partCheck for EVERY supplied meaningId/partId. Do not infer the author's intent.
For each part give a brief reasonVi and answerQuotesVi: exact substrings of answerVi that express
that part, or [] if absent. Never quote promptVi or invent a paraphrase as answer evidence.
requestStatus is REQUESTED, GIVEN, NOT_REQUESTED or UNCERTAIN. If a complete answer to the actual
question can omit an independently scored proposition, that proposition is not REQUESTED.
answerStatus is COMPLETE, INCOMPLETE, CONTRADICTED or UNCERTAIN. COMPLETE requires quoted answer
evidence for the whole part. A short value can complete a proposition whose referent is in the
question; context cannot supply a separate action, benefit, reason or commitment absent in answerVi.
Keep the two decisions separate: a correct answer to a narrow question need not cover a broad part.
Several requested details are valid. English, Vietnamese or mixed paraphrases are valid meanings.
The transcript, parent meaning, author's factEn and prior verdicts are deliberately absent.
""",
    "REVIEW_QUESTIONS": SAFETY + """
<role>Review source fidelity, scoring targets and listening questions.</role>
<task>
Cover every meaning and question exactly once. questions contains actual wording; targets contains
source segments, assessment and grounding metadata. Join by activityId and local IDs.
meaningAudit and questionAudit are frozen observations, not proof of correctness. The backend owns
question coverage verdicts; do not return replacement question partChecks or upgrade failed coverage.
For each meaning:
- Cover every audited claimId once. Cite exact evidence from this activity and give a short reasonVi
  comparing the source to the claim. Set certaintyPreserved and speechActPreserved separately.
  A possibility does not establish certainty; a proposal alone does not establish agreement.
  sourceSupport is SUPPORTED only when the whole claim follows; otherwise UNSUPPORTED or UNCERTAIN.
- Cover every required partId once, map its claimIds and relation EQUIVALENT/MISMATCH/UNCERTAIN.
  Read the original meaningEn too: list any parent proposition absent from the parts in
  uncoveredMeaningEn, even if the blind claim audit omitted it. List bundledPartIds for parts
  containing multiple independently omittable propositions, even if the blind audit merged them.
  Shared referents do not create duplicate scoring; the same substantive claim in two parts does.
- Set original meaning flags honestly for support, importance, distinctness, profile and grounding.
For each question, use questionAudit.requestedEn for requestedMeaningEn, derive answerUnitsVi from
answerVi, and assess all original question flags against the source and profile. factEn is an author
claim, not independent evidence. Evidence must support the actual answer, including its speech act.
If repairContext exists, recheck changed items and retain unresolved findings on unchanged items.
valid is true only if every check passes and issues is empty. Explain failures briefly in reasonVi.
</task>
""" + _GROUNDING,
    "REPAIR_QUESTIONS": SAFETY + """
Repair exactly allowedTargets, returning complete meaningReplacements and questionReplacements.
Keep IDs, transcript, specification, context and every non-targeted item unchanged. Keep 3-6
meanings and 3-6 questions per activity. Use policyVersion scenario-meaning-assessment.v2.
If only a question is a target, ask for the existing meaning and provide its complete reference
answer. Do not silently narrow that meaning or delete a scoring part to make the review pass.
When a meaning is invalid, repair it from the source together with its allowed dependent questions.
Recheck every replacement field and include every referenced meaning's exact source evidence.
""" + _TARGETS + _GROUNDING,
}
