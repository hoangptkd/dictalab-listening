"""Concise stage contracts with evidence-based review and no atomic-meaning flag."""
import copy
import hashlib
import json

from . import scenario_prompts_personalized_fewshot_v6 as previous
from .scenario_prompts_personalized_fewshot import MARKER, compile_instructions
from .scenario_prompts_v5 import SAFETY

PROMPT_VERSION = "SCENARIO_PERSONALIZED_FEWSHOT_V7_GRADE_V5"
QUESTION_REVIEW_VERSION = 2
GRADE = previous.GRADE
STAGES = previous.STAGES
ASSET = copy.deepcopy(previous.ASSET)
ASSET['version'] = 'scenario-personalized-fewshot-examples.v3'
for _example in ASSET['examples']:
    if _example['stage'] == 'REVIEW_QUESTIONS':
        for _check in _example['expectedOutput']['questionChecks']:
            _check.pop('atomicMeaning')
EXAMPLES = ASSET['examples']
EXAMPLE_SET_VERSION = ASSET['version']
EXAMPLE_SET_HASH = hashlib.sha256(json.dumps(
    ASSET, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
EXAMPLE_IDS = previous.EXAMPLE_IDS

_TASKS = {
    'EXTRACT': """Extract a minimal scenario specification from inputVi using learningContext.
Preserve the user's topic, roles, relationships, constraints and uncertainty. For a skill-only request,
choose a suitable setting and identify it as fictional background. Select a genre that fits the goal.
Use two fictional characters. learnerMode is participant only when the user assigns the learner an
in-scene role; bind learnerCharacterId to that role. Otherwise use listener and learnerCharacterId=null.
Separate providedFacts (verbatim inputVi quotes), inferredFacts (tentative interpretations with exact
supporting input quotes), and assumptionsVi (minimal fictional background). Inferred facts plus assumptions
must total at most four. Distinguish the learner's learning objective from the characters' communicative goal.
Use natural Vietnamese for Vietnamese fields. topicIds may be empty. Sparse but understandable requests are valid.
Return only the supplied extraction schema.""",
    'GENERATE': """Write only an English dialogue for the confirmed specification and learningContext.
Use activities a1,a2,a3 in order, each with 2-6 segments and both supplied speaker IDs.
Develop one coherent exchange suited to its genre and goal. Keep responses relevant and references clear.
Preserve roles, supplied facts and constraints. Keep the distinction between facts, opinions, possibilities,
proposals and confirmed commitments. Add plausible details only when they support the goal without changing the brief.
Match vocabulary, syntax, information density and inference burden to learningContext.profile and focusSkill.
Use writingBudget.targetWordsPerActivity as a guide; avoid padding and repetition.
Return only Dialogue: no questions, answers, evidence or learning points.""",
    'QUESTIONS': """Create questions, reference answers and evidence from the completed dialogue without rewriting it.
Use q1,q2 in each of a1,a2,a3. Test distinct, useful meanings supported within the corresponding activity.
Use skills allowed by learningContext.profile and cover focusSkill at least once.
Keep factEn and speechAct faithful to speaker attribution, conditions and certainty.
For supportKind use direct for explicit information, synthesis for a supported combined meaning, or inference
for a conclusion entailed by cited premises. Use inference only when the profile permits it, with
skill=supported-inference and a concise inferenceReasonEn; otherwise leave inferenceReasonEn empty.
Every quoteEn must occur verbatim in its identified segment in the same activity and support the answer.
Write natural Vietnamese questions and complete, concise reference answers. Do not disclose the answer
in the question or require outside knowledge. Reference language does not restrict learner answer language.
Return only supported questions in the supplied schema; never invent information to fill the count.""",
    'REVIEW_QUESTIONS': """Independently audit every question against its activity's transcript.
Derive requestedMeaningEn and answerUnitsVi before comparing the draft answer, fact, speech act and evidence.
Return one check per activityId/questionId with these nine boolean checks:
- answerSupported: the reference fully and correctly answers the question.
- distinctMeaning: the question does not repeat another question's tested meaning.
- factSupported: factEn follows from the transcript with the correct speaker, conditions and certainty.
- questionMatchesFact: the question tests the stated fact.
- speechActSupported: the speech-act label matches what the speakers actually said.
- noAnswerLeak: the question does not supply its answer.
- vietnameseNatural: the question and reference use natural Vietnamese.
- skillAppropriate: the question tests its declared, allowed skill; the session covers focusSkill.
- groundingSupported: citations belong to the same activity and support the answer and supportKind;
  inference must follow from the cited premises without outside knowledge or a competing equally plausible answer.
Use reasonVi to identify concrete support or defects. Mark unsupported or uncertain checks false and list issues.
valid=true requires all six questions to pass every check with no issues. Report findings without repairing content.""",
    'TRANSLATE': """Translate every dialogue segment into natural Vietnamese, preserving each activityId/segmentId exactly once.
Preserve the full meaning, terminology, numbers, negation, conditions and degree of certainty without adding information.
Translate ordinary English words; source proper names and identifiers may remain unchanged.
Return only the supplied Translations schema.""",
    'REVIEW': """Independently review the dialogue and translations against inputVi, the specification and learningContext.
Return exactly five rubricChecks:
- SOURCE_ROLES: supplied facts, constraints and character roles are preserved.
- DIALOGUE: the exchange is coherent, consistent and develops the communicative goal.
- TRANSLATIONS: every segment is translated completely and faithfully.
- PROFILE: vocabulary, syntax, information density and inference burden fit the supplied profile.
- LEARNING_GOAL: the content serves the user's topic and learning objective.
For every segment, check meaningPreserved, terminologyCorrect, vietnameseOnly and vietnameseNatural.
Source proper names and identifiers may remain unchanged. Cite concrete words or discrepancies in reasonVi.
Set affected flags and rubrics false for defects or uncertainty and list issues. valid=true requires all five
rubrics and every segment check to pass with no issues. Do not infer quality from valid JSON or a prior review,
and do not repair the content.""",
}
_base = {stage: SAFETY + '\n\nTASK\n' + task + '\n\n' + MARKER
         + '\n\nINPUT\nThe next user-role message contains the actual JSON input.'
         for stage, task in _TASKS.items()}
_base['GRADE'] = GRADE
INSTRUCTIONS = compile_instructions(EXAMPLES, _base)
for _stage in STAGES:
    INSTRUCTIONS[_stage] = INSTRUCTIONS[_stage].replace(
        'EXAMPLES\n', 'EXAMPLES\nExample inputs omit runtime metadata and duplicate question copies. '
        'Use the actual learningContext; output examples are complete.\n', 1)
