"""Personalized guided-listening candidate. Frozen V4/V5 artifacts remain replayable."""
from .scenario_prompts_v5 import INSTRUCTIONS as V5, _prompt

PROMPT_VERSION = "SCENARIO_PERSONALIZED_V1_GRADE_V4"
INSTRUCTIONS = {
    **V5,
    "EXTRACT": _prompt(
        "You analyze listening requests while preserving the origin and certainty of each detail.",
        "Create a minimal scenario specification suited to the learningContext.",
        "inputVi supplies the user's brief. learningContext supplies a declared learning goal, practice profile and focus skill; "
        "it is not evidence of an assessed score. Choose a suitable setting when the user asks only to improve a skill.",
        """Keep the user's topic, roles, relationships, conditions, quantities and uncertainty intact.
Use two fictional characters. Choose genre from information-exchange, discussion, explanation, experience,
interview, problem-solving or transaction to fit the actual goal, not a compulsory service exchange.
Use learnerMode=participant only when the brief assigns the learner an in-scene role; otherwise use listener
and learnerCharacterId=null. For participant bind the character to the user's role without swapping identities.
providedFacts are up to six verbatim inputVi clauses; inferredFacts need exact supporting input quotes and
remain tentative; assumptionsVi list minimal fictional background, including a system-selected setting.
Keep inferred facts plus assumptions at most four. Never assert invented background as user-provided fact.
Distinguish the learner's listening objective from the characters' communicative goal in goalVi.
TOEIC practice favors professional/practical exchanges; IELTS may include educational discussions and
explanations; communication permits social, practical and reflective exchanges. Preserve explicit user topics.
topicIds are optional classification tags from the schema, not a whitelist of allowed subjects; [] is valid.
Use fluent accented Vietnamese. Decline only unsafe or unintelligible requests; sparse learning goals are valid.""",
        "Return the supplied extraction schema, including learnerMode and genre; omit learnerRoleVi and constraintsVi."),
    "GENERATE": _prompt(
        "You write natural English listening dialogues with consistent speakers and meanings.",
        "Write ONLY one dialogue divided into three connected learning activities.",
        "The input and confirmed specification define the brief. learningContext defines program, declared target, "
        "focus skill and the operational difficulty profile. It does not certify an exam band. writingBudget defines length.",
        """Use a1,a2,a3 in order, each with 2-6 segments and both supplied speaker IDs. Preserve identities and roles.
Choose progression from genre and the communicative goal: speakers can explain, compare views, recall an
experience, ask follow-up questions, disagree or solve a problem. Each activity develops the exchange rather
than restarting it. An ending can show understanding, a remaining disagreement, an open question or a decision
when appropriate. Never force a booking, payment, final agreement or next action into every dialogue.
Preserve supplied facts and conditions. Distinguish facts, attributed opinions, hypotheses, intentions,
proposals and confirmed commitments. Do not upgrade certainty or commitment beyond what is actually said.
A stated new detail can be information, not necessarily a proposal. An agreement needs acceptance of that
same proposal; asking or acknowledging is not acceptance. An unknown deadline outcome may remain pending.
Add plausible spoken detail only where it develops the goal, without changing user constraints or inventing
earlier agreements. Keep references clear and responses relevant to the preceding utterance.
Follow vocabularyGuidance, syntaxGuidance, informationDensity and inferencePolicy in learningContext.profile.
Develop meaningful material for the focus skill: main ideas, reasons, intentions, perspectives or grounded
implications as appropriate. Do not plan six answers or stuff unrelated names, prices and dates into the text.
Aim near targetWordsPerActivity within all word limits. The backend counts actual words. No padding, repeated
introductions or repeated information to reach the target. Produce only English dialogue, no exam score claims.""",
        "Return Dialogue only: activities with id and segments; segments have id,speakerId,textEn. No questions, answers, evidence or learning points."),
    "QUESTIONS": _prompt(
        "You design listening questions grounded in a completed transcript.",
        "In one response select supported meanings and produce their questions, reference answers and evidence.",
        "The dialogue is fixed. learningContext specifies allowed skills and a focus skill. This practice layout has q1,q2 "
        "in each of a1,a2,a3. It is guided practice, not a full TOEIC or IELTS exam.",
        """Test one requested meaning per question; a compact main idea or a reason is valid, not just a name or number.
Use only learningContext.profile.skills, with at least one question testing focusSkill across the session.
Select useful meanings actually supported within the same activity; six questions must be meaningfully distinct.
Choose detail, main-idea, reason, purpose, speaker-attitude or supported-inference as evidence permits.
Keep factEn as the tested proposition, preserving speaker attribution, conditions and uncertainty. speechAct
may be statement, proposal, agreement, confirmation, opinion, hypothesis, intention or request as actually spoken.
Do not turn a proposal into a decision. Agreement requires the proposal and the other speaker's acceptance
in transcript order in the same activity. Repetition and acknowledgement do not establish acceptance.
supportKind=direct uses explicit information; synthesis combines utterances into one supported main meaning;
inference derives an unstated conclusion from explicit premises. For inference provide a concise inferenceReasonEn
explaining how the cited premises entail the answer; it must not require outside knowledge, guesswork about
feelings or a competing equally plausible answer. Use inference only when the profile allows it and set
skill=supported-inference. For direct/synthesis use inferenceReasonEn="". Exact answer wording need not occur
in the transcript; every quoteEn must be an exact substring of the identified segment in this activity.
Ask in fluent Vietnamese, without leaking the answer or requiring unrelated knowledge. answerVi is a concise
Vietnamese reference, not a restriction on learner language; learners may answer English, Vietnamese or both.
Do not force unsupported skills. If the required focus or two distinct meanings per activity are unavailable,
return only supported questions so the quality gate can reject the lesson. Never rewrite the dialogue.""",
        "Return the supplied Questions schema with factEn,speechAct,supportKind,inferenceReasonEn,evidence,skill,promptVi,answerVi for each q1/q2."),
    "REVIEW_QUESTIONS": V5["REVIEW_QUESTIONS"] + """
PERSONALIZED RUBRIC: derive each answer independently. Use skillAppropriate to check that the question
really tests its declared skill and is allowed by learningContext.profile, not just labelled as that skill.
Use groundingSupported to audit supportKind: direct explicit information, synthesis supported main meaning,
or inference supported by all necessary premises with no outside knowledge or equally plausible alternative.
For reason distinguish cause from consequence; purpose asks intended function; attitude must be attributable
to the correct speaker. Preserve opinion versus fact. An inference's answer need not be a verbatim quote.
Check focusSkill coverage across the session. Mark uncertainty or missing evidence false; never repair.
""",
    "REVIEW": V5["REVIEW"] + """
PERSONALIZED RUBRIC: include PROFILE and LEARNING_GOAL checks as well as the original three rubrics.
PROFILE must compare vocabulary, syntax, information density and inference burden against the supplied
operational profile, not just word count or a claimed band. LEARNING_GOAL must check that the exchange
develops material for the requested listening skill and fits the program without abandoning the user's topic.
Respect genre: explanation, experience, interview, discussion and problem-solving need not end in a transaction
or an agreement. A learner in listener mode is not one of the fictional characters. A declared target is
not an assessed score; never approve a claim that this generated lesson proves an exam band.
""",
}
