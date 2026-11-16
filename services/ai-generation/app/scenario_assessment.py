"""Isolated v3 scenario contracts. Core selects checks; this worker never advances a lesson."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
from functools import cache
from typing import Annotated, Literal

import httpx
from pydantic import Field, model_validator

from . import scenario_workflow as w
from .scenario_prompts import historical_release
legacy_release = historical_release('scenario_prompts_meaning_v1')
previous_release = historical_release('scenario_prompts_meaning_v2')
release = historical_release('scenario_prompts_meaning_v3')
grader_candidate = historical_release('scenario_prompts_meaning_v3_1')
parts_release = historical_release('scenario_prompts_meaning_v4')
parts_v4_1 = historical_release('scenario_prompts_meaning_v4_1')
parts_v4_3 = historical_release('scenario_prompts_meaning_v4_3')
parts_candidate = historical_release('scenario_prompts_meaning_v4_4')
parts_consistency_candidate = historical_release('scenario_prompts_meaning_v4_5')
projected_grader_candidate = historical_release('scenario_prompts_meaning_v3_2')
projected_parts_candidate = historical_release('scenario_prompts_meaning_v4_6')
independent_audit_candidate = historical_release('scenario_prompts_meaning_v4_7')
evidence_audit_candidate = historical_release('scenario_prompts_meaning_v4_11')
direct_audit_candidate = historical_release('scenario_prompts_meaning_v4_12')
scoped_release = historical_release('scenario_prompts_meaning_v4_13')
scoped_fewshot_candidate = historical_release('scenario_prompts_meaning_v4_14')
prevention_candidate = historical_release('scenario_prompts_meaning_v4_15')
alignment_candidate = historical_release('scenario_prompts_meaning_v4_16')
scope_candidate = historical_release('scenario_prompts_meaning_v4_17')
fresh_candidate = historical_release('scenario_prompts_meaning_v5_0')
recovery_candidate = historical_release('scenario_prompts_meaning_v5_1')
sparse_candidate = historical_release('scenario_prompts_meaning_v5_2')
from .coach import StrictModel
from .model_gateway import ModelGateway
from .observability import trace_gate
from .redaction import redact_string
from .scenario_contract import LearningContext, PracticeProfile

logger = logging.getLogger("dictalab.ai.scenario")
MeaningId = Annotated[str, Field(pattern=r"^m[1-6]$")]
QuestionId = Annotated[str, Field(pattern=r"^q[1-6]$")]


class AssessmentProfile(PracticeProfile):
    maxQuestionsPerSession: int = Field(ge=1, le=18)


class AssessmentContext(LearningContext):
    contractVersion: Literal["scenario-learning-context.v2"]
    profile: AssessmentProfile


class Meaning(StrictModel):
    id: MeaningId
    meaningEn: str = Field(min_length=1, max_length=300)
    skill: w.Skill
    supportKind: Literal["direct", "synthesis", "inference"]
    inferenceReasonEn: str = Field(max_length=400)
    evidence: list[w.Evidence] = Field(min_length=1, max_length=3)


class Assessment(StrictModel):
    policyVersion: Literal["scenario-meaning-assessment.v1"]
    meanings: list[Meaning] = Field(min_length=3, max_length=6)


class Question(w.PersonalizedQuestion):
    id: QuestionId
    meaningIds: list[MeaningId] = Field(min_length=1, max_length=3)


class QuestionDraft(w.PersonalizedQuestionDraft):
    id: QuestionId
    meaningIds: list[MeaningId] = Field(min_length=1, max_length=3)


class ActivityDraft(StrictModel):
    id: Literal["a1", "a2", "a3"]
    assessment: Assessment
    questions: list[QuestionDraft] = Field(min_length=3, max_length=6)


class Questions(StrictModel):
    activities: list[ActivityDraft] = Field(min_length=3, max_length=3)


class Activity(w.DialogueActivity):
    questions: list[Question] = Field(min_length=3, max_length=6)
    assessment: Assessment


class Content(StrictModel):
    activities: list[Activity] = Field(min_length=3, max_length=3)


class MeaningCheck(StrictModel):
    activityId: Literal["a1", "a2", "a3"]
    meaningId: MeaningId
    reasonVi: str = Field(min_length=1, max_length=300)
    meaningSupported: bool
    importantMeaning: bool
    distinctMeaning: bool
    skillAppropriate: bool
    groundingSupported: bool


class QuestionReview(StrictModel):
    meaningChecks: list[MeaningCheck] = Field(min_length=9, max_length=18)
    questionChecks: list[w.QuestionEvidenceCheck] = Field(min_length=9, max_length=18)
    valid: bool
    issues: list[str] = Field(max_length=20)


class MeaningReplacement(StrictModel):
    activityId: Literal["a1", "a2", "a3"]
    meaning: Meaning


class QuestionReplacement(StrictModel):
    activityId: Literal["a1", "a2", "a3"]
    question: QuestionDraft


class QuestionPatch(StrictModel):
    meaningReplacements: list[MeaningReplacement] = Field(max_length=18)
    questionReplacements: list[QuestionReplacement] = Field(max_length=18)


class Submission(StrictModel):
    mode: Literal["RECAP", "CHECKS"]
    recapText: str = Field(max_length=1500)
    answers: list[w.Answer] = Field(max_length=4)
    meaningIds: list[MeaningId] = Field(min_length=1, max_length=6)


class LearnerEvidence(StrictModel):
    responseId: Literal["recap", "q1", "q2", "q3", "q4", "q5", "q6"]
    quote: str = Field(min_length=1, max_length=500)


class GradeItem(StrictModel):
    meaningId: MeaningId
    status: Literal["CAPTURED", "PARTIAL", "MISSED", "INCORRECT", "UNCERTAIN", "UNASSESSED"]
    evidence: list[LearnerEvidence] = Field(max_length=6)


class Grade(StrictModel):
    meanings: list[GradeItem] = Field(min_length=1, max_length=6)


class RequiredPart(StrictModel):
    id: Annotated[str, Field(pattern=r"^p[1-4]$")]
    meaningEn: str = Field(min_length=1, max_length=300)
    evidence: list[w.Evidence] = Field(min_length=1, max_length=3)


class PartsMeaning(Meaning):
    requiredParts: list[RequiredPart] = Field(min_length=1, max_length=4)


class PartsAssessment(Assessment):
    policyVersion: Literal["scenario-meaning-assessment.v2"]
    meanings: list[PartsMeaning] = Field(min_length=3, max_length=6)


class PartsActivityDraft(ActivityDraft):
    assessment: PartsAssessment


class PartsQuestions(Questions):
    activities: list[PartsActivityDraft] = Field(min_length=3, max_length=3)


class PartsActivity(Activity):
    assessment: PartsAssessment


class PartsContent(Content):
    activities: list[PartsActivity] = Field(min_length=3, max_length=3)


class PartsMeaningReplacement(MeaningReplacement):
    meaning: PartsMeaning


class PartsQuestionPatch(QuestionPatch):
    meaningReplacements: list[PartsMeaningReplacement] = Field(max_length=18)


class FlexibleAssessment(PartsAssessment):
    meanings: list[PartsMeaning] = Field(min_length=2, max_length=6)


class FlexibleActivityDraft(PartsActivityDraft):
    assessment: FlexibleAssessment
    questions: list[QuestionDraft] = Field(min_length=2, max_length=6)


class FlexibleQuestions(PartsQuestions):
    activities: list[FlexibleActivityDraft] = Field(min_length=3, max_length=3)


class FlexibleActivity(PartsActivity):
    assessment: FlexibleAssessment
    questions: list[Question] = Field(min_length=2, max_length=6)


class FlexibleContent(PartsContent):
    activities: list[FlexibleActivity] = Field(min_length=3, max_length=3)


class SparseAssessment(PartsAssessment):
    meanings: list[PartsMeaning] = Field(min_length=1, max_length=6)


class SparseActivityDraft(FlexibleActivityDraft):
    assessment: SparseAssessment
    questions: list[QuestionDraft] = Field(min_length=1, max_length=6)


class SparseQuestions(FlexibleQuestions):
    activities: list[SparseActivityDraft] = Field(min_length=3, max_length=3)


class SparseActivity(FlexibleActivity):
    assessment: SparseAssessment
    questions: list[Question] = Field(min_length=1, max_length=6)


class SparseContent(FlexibleContent):
    activities: list[SparseActivity] = Field(min_length=3, max_length=3)


class RemovedTarget(StrictModel):
    activityId: Literal["a1", "a2", "a3"]
    itemType: Literal["question", "meaning"]
    itemId: str = Field(pattern=r"^(?:q|m)[1-6]$")


class SparseQuestionPatch(PartsQuestionPatch):
    removals: list[RemovedTarget] = Field(max_length=36)


class FlexibleQuestionReview(QuestionReview):
    meaningChecks: list[MeaningCheck] = Field(min_length=6, max_length=18)
    questionChecks: list[w.QuestionEvidenceCheck] = Field(min_length=6, max_length=18)


class PartScopeCheck(StrictModel):
    meaningId: MeaningId
    partId: Annotated[str, Field(pattern=r"^p[1-4]$")]
    requiredByQuestion: bool
    reasonVi: str = Field(min_length=1, max_length=400)


class AlignedQuestionCheck(w.QuestionEvidenceCheck):
    partChecks: list[PartScopeCheck] = Field(min_length=1, max_length=12)


class AlignedQuestionReview(FlexibleQuestionReview):
    questionChecks: list[AlignedQuestionCheck] = Field(min_length=6, max_length=18)


class PartGrade(StrictModel):
    partId: Annotated[str, Field(pattern=r"^p[1-4]$")]
    status: Literal["CAPTURED", "PARTIAL", "INCORRECT", "UNCERTAIN", "ABSENT"]
    evidence: list[LearnerEvidence] = Field(max_length=6)


class PartsGradeItem(StrictModel):
    meaningId: MeaningId
    parts: list[PartGrade] = Field(min_length=1, max_length=4)


class PartsGrade(StrictModel):
    meanings: list[PartsGradeItem] = Field(min_length=1, max_length=6)


class AlignedPartsGrade(StrictModel):
    assessmentIssue: str = Field(max_length=800)
    meanings: list[PartsGradeItem] = Field(max_length=6)

    @model_validator(mode="after")
    def exclusive_result(self):
        if self.assessmentIssue.strip():
            if self.meanings:
                raise ValueError("invalid assessment cannot also return learner scores")
        elif not self.meanings or self.assessmentIssue:
            raise ValueError("valid assessment requires scores and an empty issue")
        return self


class DerivedGradeItem(GradeItem):
    evidence: list[LearnerEvidence] = Field(max_length=24)
    parts: list[PartGrade] = Field(min_length=1, max_length=4)


class DerivedGrade(Grade):
    meanings: list[DerivedGradeItem] = Field(min_length=1, max_length=6)


class ScenarioAssessmentInput(StrictModel):
    kind: Literal["scenario"] = "scenario"
    phase: Literal["EXTRACT", "GENERATE", "GRADE"]
    contractVersion: Literal["scenario-job.v3"]
    promptHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    programHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    profile: w.PersonalizedProfile
    inputVi: str = Field(default="", max_length=2000)
    specification: w.PersonalizedSpecification | None = None
    activity: Activity | PartsActivity | FlexibleActivity | SparseActivity | None = None
    learningContext: AssessmentContext
    assessmentSubmission: Submission | None = None

    @model_validator(mode="after")
    def phase_input(self):
        selected = selected_release(self.promptHash)
        context = self.learningContext
        if context.programHash != self.programHash or any(getattr(context.profile, key) != getattr(self.profile, key)
                for key in ("id", "minWordsPerActivity", "maxWordsPerActivity", "maxWordsPerSession")):
            raise ValueError("learning context fingerprint/profile mismatch")
        if self.phase == "EXTRACT":
            if not self.inputVi.strip() or self.specification is not None or self.activity is not None or self.assessmentSubmission is not None:
                raise ValueError("invalid extraction input")
        elif self.phase == "GENERATE":
            if self.specification is None or not self.specification.allowed or self.activity is not None or self.assessmentSubmission is not None:
                raise ValueError("invalid generation input")
        else:
            if self.specification is not None or self.activity is None or self.assessmentSubmission is None:
                raise ValueError("invalid grade input")
            if (selected.POLICY_VERSION == parts_release.POLICY_VERSION) != isinstance(self.activity, PartsActivity):
                raise ValueError("assessment policy does not match pinned release")
            if selected is not sparse_candidate and (len(self.activity.questions) < 2 or len(self.activity.assessment.meanings) < 2):
                raise ValueError("historical assessment requires at least two items")
            if selected not in (prevention_candidate, alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate) and (len(self.activity.questions) < 3 or len(self.activity.assessment.meanings) < 3):
                raise ValueError("historical assessment requires at least three items")
            validate_submission(self.activity, self.assessmentSubmission)
        self.inputVi = redact_string(self.inputVi)
        return self


def response_schema(model, *, legacy=False, minimum_targets=False, answer_first=False):
    if model in (QuestionReview, FlexibleQuestionReview, AlignedQuestionReview):
        schema = model.model_json_schema()
        # Same Gemini audit-array limitation as historical reviews; counts remain locally enforced.
        for value in schema["properties"].values():
            if value.get("type") == "array":
                value.pop("minItems", None)
                value.pop("maxItems", None)
    else:
        schema = w.response_schema(model)
    if not legacy:
        # Gemini's nested bounded arrays can exceed its grammar compiler state budget.
        # Pydantic still enforces every original bound before any result is accepted.
        def remove_array_bounds(value):
            if isinstance(value, dict):
                if value.get("type") == "array":
                    value.pop("minItems", None)
                    value.pop("maxItems", None)
                for child in value.values():
                    remove_array_bounds(child)
            elif isinstance(value, list):
                for child in value:
                    remove_array_bounds(child)
        remove_array_bounds(schema)
    if minimum_targets and model in (PartsQuestions, FlexibleQuestions):
        # These two lower bounds compile on Gemini; nested upper bounds did not.
        flexible = model is FlexibleQuestions
        for name, field in ((("FlexibleAssessment", "meanings"), ("FlexibleActivityDraft", "questions")) if flexible
                            else (("PartsAssessment", "meanings"), ("PartsActivityDraft", "questions"))):
            schema["$defs"][name]["properties"][field]["minItems"] = 2 if flexible else 3
    if answer_first:
        # Match the requested generation sequence without changing persisted contracts or bounds.
        orders = {
            "FlexibleActivityDraft": ("id", "questions", "assessment"),
            "QuestionDraft": ("id", "promptVi", "answerVi", "factEn", "evidence", "skill", "speechAct", "supportKind", "inferenceReasonEn", "meaningIds"),
            "AlignedQuestionReview": ("questionChecks", "meaningChecks", "valid", "issues"),
            "AlignedQuestionCheck": ("activityId", "questionId", "requestedMeaningEn", "answerUnitsVi", "partChecks", "reasonVi"),
            "PartScopeCheck": ("meaningId", "partId", "reasonVi", "requiredByQuestion"),
            "PartsQuestionPatch": ("questionReplacements", "meaningReplacements"),
        }
        for node in (schema, *schema.get("$defs", {}).values()):
            order = orders.get(node.get("title"))
            if order:
                props = node["properties"]
                node["properties"] = {key: props[key] for key in (*order, *props) if key in props}
                node["required"] = [key for key in node["properties"] if key in node.get("required", [])]
    return schema


@cache
def prompt_hash(selected=release):
    parts = selected.POLICY_VERSION == parts_release.POLICY_VERSION
    flexible = selected in (prevention_candidate, alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate)
    questions_model = SparseQuestions if selected is sparse_candidate else FlexibleQuestions if flexible else PartsQuestions if parts else Questions
    content_model = SparseContent if selected is sparse_candidate else FlexibleContent if flexible else PartsContent if parts else Content
    review_model = AlignedQuestionReview if selected in (alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate) else FlexibleQuestionReview if flexible else QuestionReview
    grade_model = AlignedPartsGrade if selected in (alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate) else PartsGrade if parts else Grade
    models = (AssessmentContext, w.PersonalizedSpecification, w.PersonalizedExtraction, w.Dialogue,
              questions_model, content_model, w.Translations,
              review_model, w.PersonalizedReview, Submission, grade_model)
    legacy = selected is legacy_release
    wire = {"legacy": legacy, "minimum_targets": getattr(selected, "MINIMUM_TARGETS", False)}
    if selected in (scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate):
        wire["answer_first"] = True
    manifest = {"version": selected.PROMPT_VERSION, "assessmentPolicyVersion": selected.POLICY_VERSION,
                "instructions": selected.INSTRUCTIONS, "outputTokens": selected.OUTPUT_TOKENS,
                "schemas": {m.__name__: m.model_json_schema() for m in models},
                "reviewResponseSchemas": {m.__name__: response_schema(m, **wire) for m in (review_model, w.PersonalizedReview)},
                "harness": {"policy": selected.HARNESS_POLICY,
                            "schemas": {m.__name__: response_schema(m, **wire) for m in
                                        (SparseQuestionPatch if selected is sparse_candidate else PartsQuestionPatch if parts else QuestionPatch, w.TranslationPatch)}}}
    if not legacy:
        manifest["responseSchemas"] = {m.__name__: response_schema(m, **wire) for m in
            (w.PersonalizedExtraction, w.Dialogue, questions_model, review_model,
             w.Translations, w.PersonalizedReview, grade_model)}
    if selected in (scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate):
        # Canonical hash sorting otherwise hides property order from the release fingerprint.
        manifest["responseOrder"] = "question-answer-before-targets.v1"
    if getattr(selected, "COLLECT_ITEM_ERRORS", False):
        manifest["structuralFindings"] = "all-localized-items.v1"
    if getattr(selected, "INPUT_PROJECTION", None):
        manifest["inputProjection"] = selected.INPUT_PROJECTION
    if getattr(selected, "EXAMPLE_SET_HASH", None):
        manifest["fewShotExamples"] = {"version": selected.EXAMPLE_SET_VERSION,
            "hash": selected.EXAMPLE_SET_HASH, "idsByStage": selected.EXAMPLE_IDS}
    if selected in (recovery_candidate, sparse_candidate):
        from .scenario_assessment_recovery import Review, UsefulReview
        manifest["recoveryReviewSchema"] = response_schema(UsefulReview if selected is sparse_candidate else Review)
        manifest["recoveryProtocol"] = "verified-dependency-repair.v2;focused-explanation-support.v1"
    if parts:
        manifest["derivedGradeSchema"] = DerivedGrade.model_json_schema()
        manifest["aggregation"] = "all-captured;any-uncertain;any-correct-partial;any-incorrect;mode-omission.v1"
    if getattr(selected, "AUDIT_PROTOCOL", None):
        from . import scenario_assessment_audit as audit
        evidenced = getattr(selected, "EVIDENCE_AUDIT", False)
        audit_models = audit.models_for(selected)
        manifest["independentAudit"] = {"protocol": selected.AUDIT_PROTOCOL,
            "projection": "actual-question-answer-parts.v2" if evidenced else audit.PROJECTION_VERSION,
            "derivation": "frozen-coverage-direct-part-alignment.v3" if getattr(selected, "DIRECT_PART_AUDIT", False) else
                          "frozen-coverage-source-fidelity-parts.v2" if evidenced else audit.DERIVATION_VERSION,
            "schemas": {m.__name__: m.model_json_schema() for m in audit_models},
            "responseSchemas": {m.__name__: response_schema(m, **wire) for m in audit_models}}
    return hashlib.sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def selected_release(requested_hash):
    for selected in (sparse_candidate, recovery_candidate, fresh_candidate, scope_candidate, alignment_candidate, prevention_candidate, scoped_fewshot_candidate, scoped_release, direct_audit_candidate, evidence_audit_candidate, independent_audit_candidate, projected_parts_candidate, projected_grader_candidate, parts_consistency_candidate, grader_candidate, parts_candidate, parts_v4_3, parts_v4_1, parts_release, release, previous_release, legacy_release):
        if requested_hash == prompt_hash(selected):
            return selected
    raise ValueError("scenario prompt mismatch")


def project_input(selected, purpose, data):
    """Candidate-only context reduction; validation still uses the complete original payload."""
    if getattr(selected, "AUDIT_PROTOCOL", None) and purpose in ("AUDIT_MEANINGS", "AUDIT_QUESTIONS"):
        from . import scenario_assessment_audit as audit
        if purpose == "AUDIT_QUESTIONS" and getattr(selected, "EVIDENCE_AUDIT", False):
            return audit.project_question_coverage(data)
        return audit.project(purpose, data)
    if not getattr(selected, "INPUT_PROJECTION", None):
        return data
    if selected in (recovery_candidate, sparse_candidate) and purpose in ("REVIEW_QUESTIONS", "REVIEW"):
        return data
    if purpose == "GRADE":
        submission = data["assessmentSubmission"]
        admitted = set(submission["meaningIds"])
        question_ids = {answer["questionId"] for answer in submission["answers"]}
        meanings = []
        for meaning in data["activity"]["assessment"]["meanings"]:
            if meaning["id"] in admitted:
                target = {key: meaning[key] for key in ("id", "meaningEn")}
                if "requiredParts" in meaning:
                    target["requiredParts"] = [{key: part[key] for key in ("id", "meaningEn")}
                                               for part in meaning["requiredParts"]]
                meanings.append(target)
        questions = [{key: question[key] for key in ("id", "meaningIds", "promptVi")}
                     for question in data["activity"]["questions"] if question["id"] in question_ids]
        if selected in (alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate):
            # Source is needed to detect an invalid key, never to fabricate learner evidence.
            return {"activity": {"id": data["activity"]["id"], "segments": data["activity"]["segments"],
                                  "assessment": {"meanings": meanings}, "questions": questions},
                    "assessmentSubmission": submission}
        return {"activity": {"id": data["activity"]["id"], "assessment": {"meanings": meanings},
                             "questions": questions}, "assessmentSubmission": submission}
    if purpose == "REVIEW_QUESTIONS" and selected in (projected_parts_candidate, independent_audit_candidate, evidence_audit_candidate, direct_audit_candidate):
        questions, targets = [], []
        content = {activity["id"]: activity for activity in data["content"]["activities"]}
        for activity in data["groundedQuestions"]["activities"]:
            grounding = []
            for question in activity["questions"]:
                questions.append({"activityId": activity["id"], **{key: question[key] for key in
                                  ("id", "meaningIds", "promptVi", "answerVi")}})
                grounding.append({key: value for key, value in question.items()
                                  if key not in ("promptVi", "answerVi", "meaningIds")})
            targets.append({"activityId": activity["id"], "segments": content[activity["id"]]["segments"],
                            "assessment": activity["assessment"], "questionGrounding": grounding})
        return {"questions": questions, "targets": targets, "learningContext": data["learningContext"],
                **{key: data[key] for key in ("repairContext", "formatFeedback") if key in data},
                **({key: data[key] for key in ("meaningAudit", "questionAudit") if key in data}
                   if getattr(selected, "AUDIT_PROTOCOL", None) else {})}
    if purpose == "REVIEW_QUESTIONS" and selected in (scoped_release, scoped_fewshot_candidate, prevention_candidate, alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate):
        drafts = {activity["id"]: activity for activity in data["groundedQuestions"]["activities"]}
        # Keep draft speechAct/factEn for review; public content strips these fields.
        return {"content": {"activities": [{**activity, "questions": drafts[activity["id"]]["questions"]}
                    for activity in data["content"]["activities"]]},
                **{key: data[key] for key in ("learningContext", "repairContext", "formatFeedback") if key in data}}
    return data


def metadata(settings, requested_hash=None):
    selected = selected_release(requested_hash or prompt_hash())
    result = w.metadata(settings)
    return {**result, "promptHash": prompt_hash(selected), "promptVersion": selected.PROMPT_VERSION,
            "assessmentPolicyVersion": selected.POLICY_VERSION}


def _invalid(message, activity=None, item=None, kind="question"):
    error = ValueError(message)
    error.activity_id, error.item_id, error.item_kind = activity, item, kind
    return error


def _support(item, context, activity, kind, *, optional_inference=False):
    if item.skill not in context.profile.skills:
        raise _invalid("skill outside practice profile", activity, item.id, kind)
    inferred = item.supportKind == "inference"
    if (not optional_inference and inferred != (item.skill == "supported-inference") or
            (inferred and (context.profile.inferencePolicy != "SUPPORTED_INFERENCE" or not item.inferenceReasonEn.strip())) or
            (not inferred and item.inferenceReasonEn)):
        raise _invalid("inference support mismatch", activity, item.id, kind)


def _evidence(item, segments, activity, kind):
    if len({(e.segmentId, e.quoteEn) for e in item.evidence}) != len(item.evidence):
        raise _invalid("duplicate evidence", activity, item.id, kind)
    for evidence in item.evidence:
        if (not evidence.quoteEn.strip() or evidence.segmentId not in segments
                or evidence.quoteEn not in segments[evidence.segmentId].textEn):
            raise _invalid("unanchored evidence", activity, item.id, kind)


_QUESTION_SCOPE_MARKERS = {
    "person": re.compile(r"\b(?:ai|những ai|người nào|bộ phận nào|phòng ban nào|đội nào|nhóm nào|who|whom|which people|which team|which department)\b", re.I),
    "place": re.compile(r"\b(?:ở đâu|nơi nào|địa điểm nào|phòng nào|where|which place|which room|which location)\b", re.I),
    "time": re.compile(r"\b(?:khi nào|lúc mấy giờ|mấy giờ|ngày nào|bao giờ|when|what time|which day|what date)\b", re.I),
    "reason": re.compile(r"\b(?:vì sao|tại sao|nhằm mục đích gì|mục đích gì|lý do gì|why|what reason|what purpose|for what purpose)\b", re.I),
    "amount": re.compile(r"\b(?:bao nhiêu|giá bao nhiêu|mức nào|how much|how many|what amount|what price|cost)\b", re.I),
    "certainty": re.compile(r"\b(?:có chắc|đã xác nhận|có thể|liệu|chắc chắn|certain|confirmed|possible|likely|uncertain)\b", re.I),
    "action": re.compile(r"\b(?:làm gì|đề xuất gì|chọn gì|yêu cầu gì|what did|what will|what does|what is .* going to|what .* request|what .* recommend)\b", re.I),
}

_PART_SCOPE_MARKERS = {
    # Match a requested dimension only when the wording makes it an answer
    # unit.  Bare nouns such as ``staff`` or ``time`` are often context for
    # another answer and must not become a false scope violation.
    "person": re.compile(r"\b(?:with|from|by|among|với|bởi)\s+(?:the\s+)?(?:[a-z][\w-]*\s+){0,3}(?:people|team|department|group|leads?|manager|staff|customer|client|recipient|colleagues?|bạn bè|đồng nghiệp)\b", re.I),
    "place": re.compile(r"\b(?:at|in|on|from|to|near|ở|tại|đến)\s+(?:the\s+)?(?:[a-z][\w-]*\s+){0,3}(?:place|room|venue|location|site|cafe|café|office|station|airport|restaurant|shelf|window|địa điểm|phòng)\b", re.I),
    "time": re.compile(r"\b(?:at|on|by|before|after|during|around|next|this|last|vào|trước|sau|trong)\s+(?:[a-z][\w-]*\s+){0,2}(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|morning|afternoon|evening|noon|o'clock|am|pm|today|tomorrow|yesterday|giờ|ngày|tuần|tháng|sáng|chiều|tối)\b", re.I),
    "reason": re.compile(r"\b(?:why|reason|purpose|because|so that|to ensure|in order to|goal|aim|lý do|mục đích|vì|để)\b", re.I),
    "amount": re.compile(r"\b(?:price|cost|amount|number|quantity|pounds?|dollars?|euros?|percent|how much|how many|giá|số lượng|bao nhiêu)\b", re.I),
    "certainty": re.compile(r"\b(?:certain|confirmed|possible|likely|unlikely|uncertain|may|might|could|must|definitely|nghi ngờ|có thể|chắc chắn|xác nhận)\b", re.I),
    "action": re.compile(r"\b(?:will|plans? to|proposes? to|suggests?\s+\w+ing|offers? to|agrees? to|decides? to|establish|set up|recommend|request|ask for|change|send|buy|meet|sync with|work with|thực hiện|làm việc với|đề xuất|thiết lập|kiểm tra)\b", re.I),
}


def question_scope_findings(questions, dialogue):
    """Return generic source-output scope defects before reviewer or repair runs.

    This is deliberately conservative: it checks only explicit scope dimensions. It
    never compares names, topics or individual scenarios and does not infer a gold
    answer from a keyword list.
    """
    findings = []
    by_source = {activity.id for activity in dialogue.activities}
    for activity in questions.activities:
        if activity.id not in by_source:
            continue
        meaning_by_id = {meaning.id: meaning for meaning in activity.assessment.meanings}
        for question in activity.questions:
            asked = {name for name, marker in _QUESTION_SCOPE_MARKERS.items() if marker.search(question.promptVi)}
            if not asked:
                continue
            for meaning_id in question.meaningIds:
                meaning = meaning_by_id[meaning_id]
                if not isinstance(meaning, PartsMeaning):
                    continue
                for part in meaning.requiredParts:
                    part_scope = {name for name, marker in _PART_SCOPE_MARKERS.items() if marker.search(part.meaningEn)}
                    extra = part_scope - asked
                    if extra:
                        error = _invalid(
                            "required part includes information outside question scope: " + ",".join(sorted(extra)),
                            activity.id, meaning.id, "meaning")
                        error.question_id = question.id
                        error.part_id = part.id
                        findings.append(error)
    return findings


@trace_gate
def validate_questions(questions, dialogue, context, *, collect_errors=False, require_focus=True):
    item_errors = []
    sparse = isinstance(questions, SparseQuestions) or any(isinstance(act, (SparseActivityDraft, SparseActivity)) for act in questions.activities)
    require_focus = require_focus and not (sparse and context.focusSkill == "supported-inference")
    if [a.id for a in questions.activities] != [a.id for a in dialogue.activities]:
        raise _invalid("question activity mismatch")
    total_questions = 0
    focus_meaning = focus_question = False
    for source, activity in zip(dialogue.activities, questions.activities):
        if activity.assessment.policyVersion != questions.activities[0].assessment.policyVersion:
            raise _invalid("mixed assessment policies", activity.id)
        meanings = activity.assessment.meanings
        if (len({m.id for m in meanings}) != len(meanings) if sparse else [m.id for m in meanings] != [f"m{i + 1}" for i in range(len(meanings))]):
            raise _invalid("meaning IDs mismatch", activity.id)
        if (len({q.id for q in activity.questions}) != len(activity.questions) if sparse else [q.id for q in activity.questions] != [f"q{i + 1}" for i in range(len(activity.questions))]):
            raise _invalid("question IDs mismatch", activity.id)
        segments = {s.id: s for s in source.segments}
        normalized = [" ".join(m.meaningEn.casefold().split()) for m in meanings]
        if not all(normalized) or len(set(normalized)) != len(normalized):
            raise _invalid("duplicate or empty meaning", activity.id)
        by_id = {m.id: m for m in meanings}
        covered = set()
        prompts = set()
        for meaning in meanings:
            try:
                _support(meaning, context, activity.id, "meaning", optional_inference=sparse)
                _evidence(meaning, segments, activity.id, "meaning")
                if isinstance(meaning, PartsMeaning):
                    if [p.id for p in meaning.requiredParts] != [f"p{i + 1}" for i in range(len(meaning.requiredParts))]:
                        raise _invalid("required part IDs mismatch", activity.id, meaning.id, "meaning")
                    labels = [" ".join(p.meaningEn.casefold().split()) for p in meaning.requiredParts]
                    if not all(labels) or len(set(labels)) != len(labels):
                        raise _invalid("duplicate or empty required part", activity.id, meaning.id, "meaning")
                    for part in meaning.requiredParts:
                        try:
                            _evidence(part, segments, activity.id, "meaning")
                        except ValueError as invalid:
                            invalid.item_id = meaning.id
                            raise
                        if any(not any(pe.segmentId == me.segmentId and pe.quoteEn in me.quoteEn
                                       for me in meaning.evidence) for pe in part.evidence):
                            raise _invalid("required part outside meaning evidence", activity.id, meaning.id, "meaning")
                focus_meaning |= meaning.skill == context.focusSkill
            except ValueError as error:
                if not collect_errors:
                    raise
                item_errors.append(error)
        for question in activity.questions:
            try:
                _support(question, context, activity.id, "question", optional_inference=sparse)
                _evidence(question, segments, activity.id, "question")
                focus_question |= question.skill == context.focusSkill
                refs = set(question.meaningIds)
                if len(refs) != len(question.meaningIds) or not refs <= by_id.keys():
                    raise _invalid("invalid meaning references", activity.id, question.id)
                if any(not any(qe.segmentId == me.segmentId and me.quoteEn in qe.quoteEn for qe in question.evidence)
                       for mid in refs for me in by_id[mid].evidence):
                    raise _invalid("question omits referenced meaning evidence", activity.id, question.id)
                covered |= refs
                prompt = " ".join(question.promptVi.casefold().split())
                if not prompt or prompt in prompts or not question.answerVi.strip():
                    raise _invalid("duplicate or empty question", activity.id, question.id)
                prompts.add(prompt)
                if question.answerVi.casefold() in question.promptVi.casefold():
                    raise _invalid("answer leaked in question", activity.id, question.id)
                if isinstance(question, QuestionDraft):
                    if not question.factEn.strip():
                        raise _invalid("empty fact", activity.id, question.id)
                    if question.speechAct == "proposal" and re.search(
                            r"\b(?:họ|cả hai|hai người|hai bên)\s+(?:đã\s+)?(?:thống nhất|đồng ý|chốt|quyết định|xác nhận)\b",
                            question.promptVi, re.IGNORECASE):
                        raise _invalid("proposal question asserts agreement", activity.id, question.id)
                    if question.speechAct == "agreement":
                        positions = {s.id: i for i, s in enumerate(source.segments)}
                        ordered = [positions[e.segmentId] for e in question.evidence]
                        if len({segments[e.segmentId].speakerId for e in question.evidence}) < 2 or ordered != sorted(ordered):
                            raise _invalid("agreement requires ordered evidence from both speakers", activity.id, question.id)
            except ValueError as error:
                if not collect_errors:
                    raise
                item_errors.append(error)
        if not item_errors and covered != by_id.keys():
            raise _invalid("meaning coverage mismatch", activity.id)
        total_questions += len(activity.questions)
    if collect_errors:
        # Report missing coverage alongside item faults, so a repair sees both in one pass.
        if require_focus and (not any(m.skill == context.focusSkill for act in questions.activities for m in act.assessment.meanings) or not any(
                q.skill == context.focusSkill for act in questions.activities for q in act.questions)):
            item_errors.append(_invalid("missing learning focus coverage"))
    if item_errors:
        error = ValueError("invalid question items")
        error.item_errors = item_errors
        raise error
    if total_questions > context.profile.maxQuestionsPerSession:
        raise _invalid("question bank exceeds practice profile")
    if require_focus and (not focus_meaning or not focus_question):
        raise _invalid("missing learning focus coverage")


def attach_questions(dialogue, questions, context):
    validate_questions(questions, dialogue, context)
    content_model, activity_model = ((SparseContent, SparseActivity) if isinstance(questions, SparseQuestions) else (FlexibleContent, FlexibleActivity) if isinstance(questions, FlexibleQuestions)
        else (PartsContent, PartsActivity) if isinstance(questions, PartsQuestions) else (Content, Activity))
    return content_model(activities=[activity_model(**source.model_dump(), assessment=draft.assessment,
        questions=[Question(**q.model_dump(exclude={"factEn", "speechAct"}), type="MEANING_RECALL_VI")
                   for q in draft.questions]) for source, draft in zip(dialogue.activities, questions.activities)])


@trace_gate
def validate_content(content, specification, profile, context, translations=None):
    # Length remains writing guidance; all speaker, ID and source checks remain active.
    w.validate_dialogue(content, specification, profile, flexible=True)
    validate_questions(content, content, context)
    if translations is not None:
        expected = {(a.id, s.id): s.textEn for a in content.activities for s in a.segments}
        actual = {(t.activityId, t.segmentId) for t in translations.segments}
        if actual != expected.keys() or len(actual) != len(translations.segments):
            raise ValueError("translation coverage mismatch")
        for translation in translations.segments:
            source = expected[(translation.activityId, translation.segmentId)]
            if not translation.textVi.strip() or (len(source.split()) >= 3 and
                    " ".join(source.casefold().split()) == " ".join(translation.textVi.casefold().split())):
                raise _invalid("untranslated segment", translation.activityId, translation.segmentId, "translation")


def validate_submission(activity, submission):
    meanings = {m.id for m in activity.assessment.meanings}
    admitted = set(submission.meaningIds)
    if len(admitted) != len(submission.meaningIds) or not admitted <= meanings:
        raise ValueError("invalid admitted meaning IDs")
    if submission.mode == "RECAP":
        if not submission.recapText.strip() or submission.answers or admitted != meanings:
            raise ValueError("invalid recap submission")
    else:
        by_id = {q.id: q for q in activity.questions}
        ids = [a.questionId for a in submission.answers]
        if submission.recapText or not ids or len(ids) != len(set(ids)) or not set(ids) <= by_id.keys():
            raise ValueError("invalid checks submission")
        if admitted != {mid for qid in ids for mid in by_id[qid].meaningIds}:
            raise ValueError("admitted meaning coverage mismatch")


@trace_gate
def validate_grade(grade, payload):
    submission = payload.assessmentSubmission
    expected = set(submission.meaningIds)
    if len({m.meaningId for m in grade.meanings}) != len(grade.meanings) or {m.meaningId for m in grade.meanings} != expected:
        raise ValueError("grade meaning IDs mismatch")
    responses = {"recap": submission.recapText} if submission.mode == "RECAP" else {a.questionId: a.textVi for a in submission.answers}
    checks = {q.id: set(q.meaningIds) for q in payload.activity.questions}
    for meaning in grade.meanings:
        if meaning.status in ("CAPTURED", "PARTIAL", "INCORRECT") and not meaning.evidence:
            raise ValueError("missing learner evidence")
        if meaning.status in ("UNASSESSED", "MISSED") and meaning.evidence:
            raise ValueError("unexpected learner evidence")
        if submission.mode == "RECAP" and meaning.status == "MISSED" or submission.mode == "CHECKS" and meaning.status == "UNASSESSED":
            raise ValueError("invalid omission status")
        seen = set()
        for evidence in meaning.evidence:
            pair = (evidence.responseId, evidence.quote)
            if (not evidence.quote.strip() or evidence.responseId not in responses or
                    evidence.quote not in responses[evidence.responseId] or pair in seen):
                raise ValueError("invented or duplicate learner evidence")
            seen.add(pair)
            if submission.mode == "CHECKS" and meaning.meaningId not in checks[evidence.responseId]:
                raise ValueError("learner evidence from unrelated check")


def aggregate_parts(statuses, mode):
    if not statuses:
        raise ValueError("missing required part statuses")
    if all(s == "CAPTURED" for s in statuses):
        return "CAPTURED"
    if "UNCERTAIN" in statuses:
        return "UNCERTAIN"
    if any(s in ("CAPTURED", "PARTIAL") for s in statuses):
        return "PARTIAL"
    if "INCORRECT" in statuses:
        return "INCORRECT"
    return "UNASSESSED" if mode == "RECAP" else "MISSED"


@trace_gate
def derive_grade(grade, payload):
    if isinstance(grade, AlignedPartsGrade) and grade.assessmentIssue:
        raise w.QualityRejected("invalid assessment targets; learner grade not recorded")
    expected = {m.id: m for m in payload.activity.assessment.meanings if m.id in payload.assessmentSubmission.meaningIds}
    if len(grade.meanings) != len(expected) or {m.meaningId for m in grade.meanings} != expected.keys():
        raise ValueError("grade meaning IDs mismatch")
    results = []
    for item in grade.meanings:
        part_ids = {p.id for p in expected[item.meaningId].requiredParts}
        if len(item.parts) != len(part_ids) or {p.partId for p in item.parts} != part_ids:
            raise ValueError("grade required part IDs mismatch")
        evidence = {}
        for part in item.parts:
            if (part.status == "ABSENT") != (not part.evidence):
                raise ValueError("invalid required part evidence")
            # Reuse the historical exact learner-quote and related-question trust boundary per part.
            status = aggregate_parts([part.status], payload.assessmentSubmission.mode)
            validate_grade(Grade(meanings=[GradeItem(meaningId=item.meaningId, status=status, evidence=part.evidence)]),
                payload.model_copy(update={"assessmentSubmission": payload.assessmentSubmission.model_copy(update={"meaningIds": [item.meaningId]})}))
            for quote in part.evidence:
                evidence[(quote.responseId, quote.quote)] = quote
        results.append(DerivedGradeItem(meaningId=item.meaningId, parts=item.parts,
            status=aggregate_parts([p.status for p in item.parts], payload.assessmentSubmission.mode), evidence=list(evidence.values())))
    return DerivedGrade(meanings=results)


async def execute(event, settings, *, observer=None):
    """Use the existing gateway and fallback rules; never replay completed stages."""
    if not settings.scenario_enabled:
        raise w.QualityRejected("scenario execution disabled")
    remaining = min(240, event.deadline.timestamp() - time.time())
    if remaining <= 0:
        raise TimeoutError("scenario deadline")
    deadline = time.monotonic() + remaining
    payload = event.payload
    selected = selected_release(payload.promptHash)
    context = {"learningContext": payload.learningContext.model_dump(exclude_none=True)}
    calls = []
    call_settings = settings
    async with httpx.AsyncClient(timeout=remaining, limits=httpx.Limits(max_connections=settings.effective_http_max_connections)) as client:
        async def call(purpose, data, model):
            nonlocal call_settings
            data = project_input(selected, purpose, data)
            stage = {"REPAIR_QUESTIONS": "QUESTIONS", "REPAIR_TRANSLATIONS": "TRANSLATE"}.get(purpose, purpose)
            gateway = ModelGateway(call_settings.model_copy(update={"num_predict": selected.OUTPUT_TOKENS[stage]}), client)
            if observer is not None:
                gateway.observed_attempts = []
            schema = response_schema(model, legacy=selected is legacy_release,
                                     minimum_targets=getattr(selected, "MINIMUM_TARGETS", False),
                                     answer_first=selected in (scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate))
            if purpose == "GENERATE":
                schema["$defs"]["Segment"]["properties"]["speakerId"]["enum"] = [c.id for c in payload.specification.characters]
            captured = {"stage": purpose, "input": data, "schema": schema, "instruction": selected.INSTRUCTIONS[purpose]}
            if observer is not None:
                observer({**captured, "status": "RUNNING"})
            try:
                raw, provider = await asyncio.wait_for(gateway.complete(
                    [{"role": "system", "content": selected.INSTRUCTIONS[purpose]},
                     {"role": "user", "content": json.dumps(data, ensure_ascii=False)}], schema, deadline,
                    "scenario_" + purpose.lower(), temperature=0,
                    **({"allow_model_fallback": True} if call_settings.gemini_fallback_models else {})), max(0.01, deadline - time.monotonic()))
            except Exception as error:
                if observer is not None:
                    observer({**captured, "status": "ERROR", "error": type(error).__name__,
                              "httpStatus": getattr(error, "status", None), "attempts": gateway.observed_attempts})
                logger.warning("scenario_call_failed job_id=%s phase=%s error_type=%s http_status=%s provider=%s model=%s source_id=%s completed_calls=%s",
                    event.jobId, purpose, type(error).__name__, getattr(error, "status", None), settings.selected_provider,
                    gateway.last_model, getattr(gateway, "last_source_id", None), len(calls))
                raise
            if observer is not None:
                observer({**captured, "status": "RETURNED", "output": raw, "model": gateway.last_model,
                          "attempts": gateway.observed_attempts, "fallbacks": gateway.model_fallbacks})
            calls.append({"purpose": purpose, "provider": provider, "model": gateway.last_model,
                **({"exampleSetHash": selected.EXAMPLE_SET_HASH, "exampleIds": selected.EXAMPLE_IDS[purpose]}
                   if getattr(selected, "EXAMPLE_SET_HASH", None) else {})})
            if getattr(gateway, "model_fallbacks", []):
                chain = call_settings.gemini_fallback_models
                pools = {p: dict(policy) for p, policy in call_settings.registry_pools.items()}
                if "gemini" in pools:
                    pools["gemini"]["default_model"] = gateway.last_model
                call_settings = call_settings.model_copy(update={"gemini_model": gateway.last_model, "registry_pools": pools,
                    "gemini_fallback_models": chain[chain.index(gateway.last_model) + 1:]})
                call_settings._registry_snapshot = True
            try:
                return model.model_validate(raw)
            except ValueError as invalid:
                error = w.QualityRejected("invalid scenario response")
                error.failed_output = raw
                error.schema_issues = [{"type": issue["type"], "loc": list(issue["loc"])}
                    for issue in invalid.errors(include_input=False, include_url=False)[:12]]
                raise error from invalid

        harness_metadata = {}
        if payload.phase == "EXTRACT":
            extracted = await call("EXTRACT", {"inputVi": payload.inputVi, **context}, w.PersonalizedExtraction)
            try:
                result = w.validate_extraction(extracted, payload.inputVi).model_dump()
            except ValueError as invalid:
                raise w.QualityRejected("invalid extraction provenance") from invalid
        elif selected in (recovery_candidate, sparse_candidate):
            from . import scenario_assessment_recovery as recovery
            result, harness_metadata = await (recovery.grade(payload, call, deadline) if payload.phase == "GRADE" else recovery.generate(payload, call, deadline))
        elif payload.phase == "GRADE":
            grade = await call("GRADE", {"activity": payload.activity.model_dump(),
                "assessmentSubmission": payload.assessmentSubmission.model_dump(), **context},
                AlignedPartsGrade if selected in (alignment_candidate, scope_candidate, fresh_candidate, recovery_candidate, sparse_candidate) else PartsGrade if isinstance(payload.activity, PartsActivity) else Grade)
            try:
                if isinstance(payload.activity, PartsActivity):
                    grade = derive_grade(grade, payload)
                else:
                    validate_grade(grade, payload)
            except w.QualityRejected:
                raise
            except ValueError as invalid:
                raise w.QualityRejected("invalid grade evidence") from invalid
            result = grade.model_dump()
        else:
            dialogue = await call("GENERATE", {"inputVi": payload.inputVi, "specification": payload.specification.model_dump(),
                "profile": payload.profile.model_dump(), **context, "writingBudget": {"targetWordsPerActivity": min(
                    (payload.profile.minWordsPerActivity + payload.profile.maxWordsPerActivity) // 2,
                    payload.profile.maxWordsPerSession // 3)}}, w.Dialogue)
            try:
                w.validate_dialogue(dialogue, payload.specification, payload.profile, flexible=True)
            except ValueError as invalid:
                raise w.QualityRejected("invalid dialogue") from invalid
            from .scenario_assessment_harness import generate
            result, harness_metadata = await generate(dialogue, payload, call, deadline)
    return {**metadata(settings, payload.promptHash), **harness_metadata, "generationId": str(event.jobId), "requestId": str(event.requestId),
            "programHash": payload.programHash, "calls": calls, "task": result}
