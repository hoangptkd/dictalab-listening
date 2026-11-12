"""Unactivated candidate: evidence-first coverage and direct part alignment."""
from .scenario_prompts_meaning_v4_11 import (
    COLLECT_ITEM_ERRORS, EVIDENCE_AUDIT, HARNESS_POLICY, INPUT_PROJECTION,
    MINIMUM_TARGETS, OUTPUT_TOKENS, POLICY_VERSION, INSTRUCTIONS as PREVIOUS,
)
from .scenario_prompts_meaning_v1 import _GROUNDING
from .scenario_prompts_v5 import SAFETY

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V4_12"
AUDIT_PROTOCOL = "independent-audit.v4-direct-parts"
DIRECT_PART_AUDIT = True

INSTRUCTIONS = {**PREVIOUS,
    "AUDIT_QUESTIONS": SAFETY + """
<role>Check what each question asks and what its reference answer actually says.</role>
<task>
Return every question and every supplied meaningId/partId exactly once.
requestedEn describes the information requested, without supplying its answer. givenEn contains
context already stated in promptVi. Neither field is evidence of what answerVi says.
For each part, first copy exact answerQuotesVi from answerVi, or [] when the proposition is absent.
Then give a short reasonVi comparing the whole part to those quotes and make two separate decisions:
- requestStatus: REQUESTED if the learner must supply this proposition; GIVEN if the question
  already states it; NOT_REQUESTED if it is outside the question; UNCERTAIN if ambiguous.
- answerStatus: COMPLETE only when the quoted answer expresses the whole proposition;
  INCOMPLETE if any assertion is absent, including when answerQuotesVi is [];
  CONTRADICTED for an incompatible assertion; UNCERTAIN for ambiguous meaning.
GIVEN or NOT_REQUESTED does not make an absent answer proposition COMPLETE. A pronoun or short
value may use the question to identify its referent, but not to invent a separate action, reason,
benefit or commitment. Preserve possibility, negation and conditions when comparing meanings.
A multi-detail question is valid; its reference must answer all requested details. English,
Vietnamese and mixed paraphrases are valid. Do not quote the prompt or fabricate an answer quote.
</task>
""",
    "REVIEW_QUESTIONS": SAFETY + """
<role>Check source fidelity and whether the scoring parts match the actual content.</role>
<task>
Cover every meaning and question exactly once. Join questions and targets by activityId and IDs.
The independent meaningAudit may use broader or narrower claims than requiredParts: its number
of claims and shared claim references do not establish a duplicate, missing or bundled part.
For each meaning:
1. Check every audited claim against the source, with exact evidence, short reasonVi,
   certaintyPreserved and speechActPreserved. Preserve uncertainty and commitments.
2. Check EVERY required part directly, without mapping it to claim IDs. Give exact source evidence,
   reasonVi, sourceSupport and parentSupport (SUPPORTED/UNSUPPORTED/UNCERTAIN). The whole part must
   follow from the transcript and from the original meaningEn respectively. Shared actor/context
   is allowed; adding an assertion absent from the parent is not.
3. Compare the union of parts with the original meaningEn. List any parent assertion absent from
   all parts in uncoveredMeaningEn, including agreement or conditions. List bundledPartIds for
   parts containing several assertions a learner could recall independently.
4. List duplicatePartPairs only when two parts score the same substantive information. Each pair
   names leftPartId, rightPartId and sharedMeaningEn. Two different benefits or alternatives are
   not duplicates because their actor or subject is shared. A broad audit claim can contain both.
Set all original meaning flags from the content, not from claim/part counts. A possibility does
not establish certainty, and a proposal does not establish agreement.
For each question, use frozen questionAudit.requestedEn and actual answerVi for requestedMeaningEn
and answerUnitsVi; evaluate all original question flags against the source and learning profile.
The backend preserves scoped question coverage; do not return or replace question partChecks.
factEn and audit ledgers are claims to verify, not independent evidence. If repairContext exists,
recheck changed items and retain unresolved findings on unchanged items.
valid is true only if every check passes and issues is empty. Explain failures briefly in reasonVi.
</task>
""" + _GROUNDING,
}
