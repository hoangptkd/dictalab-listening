"""Bounded V5 recovery using the existing generation, review and patch stages.

Quotes establish provenance, not semantic truth. A material diagnosis needs a second
source-based review; patches are reviewed as complete artifacts, including neighbors.
"""
from __future__ import annotations

import time
from collections import Counter
from typing import Literal

from pydantic import Field

from . import scenario_assessment as a, scenario_assessment_harness as old, scenario_harness as h, scenario_workflow as w
from .coach import StrictModel


class SourceQuote(StrictModel):
    activityId: Literal["a1", "a2", "a3"]
    segmentId: str = Field(min_length=1, max_length=16)
    quoteEn: str = Field(min_length=1, max_length=600)


class PartCheck(StrictModel):
    meaningId: a.MeaningId
    partId: str = Field(pattern=r"^p[1-4]$")
    requiredByQuestion: bool
    sourceSupportsPart: bool
    reasonVi: str = Field(min_length=1, max_length=400)


class Check(StrictModel):
    itemType: Literal["dialogue", "meaning", "question", "translation"]
    activityId: Literal["a1", "a2", "a3"]
    itemId: str = Field(min_length=1, max_length=16)
    status: Literal["PASS", "DEFECT", "ADVISORY", "UNKNOWN"]
    code: str = Field(min_length=1, max_length=80)
    artifactQuote: str = Field(max_length=600)
    sourceEvidence: list[SourceQuote] = Field(max_length=4)
    requestQuote: str = Field(max_length=400)
    reasonVi: str = Field(min_length=1, max_length=600)
    derivedAnswerVi: str = Field(max_length=400)
    answerSupport: Literal["direct", "synthesis", "inference", "not-answerable", "not-applicable"]
    answerMatchesDerived: bool
    partChecks: list[PartCheck] = Field(max_length=12)


class Review(StrictModel):
    checks: list[Check] = Field(min_length=1, max_length=60)


class UsefulCheck(Check):
    addsInformationBeyondQuestion: bool


class UsefulReview(Review):
    checks: list[UsefulCheck] = Field(min_length=1, max_length=60)


class Bank(StrictModel):
    # GRADE reviews one saved activity; generation still uses FlexibleQuestions.
    activities: list[a.FlexibleActivityDraft] = Field(min_length=1, max_length=3)


class SparseBank(Bank):
    activities: list[a.SparseActivityDraft] = Field(min_length=1, max_length=3)


def merge_sparse_patch(original, patch, allowed):
    """Remove only diagnosed dependency targets; retain stable IDs of all survivors."""
    removals = {(r.itemType, r.activityId, r.itemId) for r in patch.removals}
    if len(removals) != len(patch.removals) or not removals <= allowed:
        raise ValueError("patch removals outside scope")
    replacements = {("meaning", r.activityId, r.meaning.id) for r in patch.meaningReplacements}
    replacements |= {("question", r.activityId, r.question.id) for r in patch.questionReplacements}
    if replacements & removals or replacements | removals != allowed:
        raise ValueError("patch targets mismatch")
    # Reuse the exact-replacement gate before applying the explicit removals.
    original_items = old.items(original, "QUESTIONS")
    full = patch.model_copy(deep=True)
    for kind, activity, item in removals:
        if kind == "meaning":
            full.meaningReplacements.append(a.PartsMeaningReplacement(activityId=activity, meaning=original_items[(kind, activity, item)]))
        else:
            full.questionReplacements.append(a.QuestionReplacement(activityId=activity, question=original_items[(kind, activity, item)]))
    candidate = old.merge_patch(original, full, allowed, "QUESTIONS")
    for act in candidate.activities:
        act.questions = [q for q in act.questions if ("question", act.id, q.id) not in removals]
        act.assessment.meanings = [m for m in act.assessment.meanings if ("meaning", act.id, m.id) not in removals]
    return type(original).model_validate(candidate.model_dump())


def merge_translation_coverage(original, patch, allowed, dialogue):
    """Rebuild the source ID inventory while preserving every unscoped translation."""
    replacements = {(t.activityId, t.segmentId): t for t in patch.replacements} if patch else {}
    targets = {(act, item) for _, act, item in allowed}
    if replacements.keys() != targets or patch and len(patch.replacements) != len(targets):
        raise ValueError("patch targets mismatch")
    before = {(t.activityId, t.segmentId): t for t in original.segments}
    expected = [(act.id, segment.id) for act in dialogue.activities for segment in act.segments]
    return w.Translations(segments=[replacements[key] if key in targets else before[key] for key in expected])


def inventory(dialogue, artifact, stage):
    source = {("dialogue", act.id, act.id): act for act in dialogue.activities}
    return {**source, **(old.items(artifact, stage) if artifact is not None else {})}


def contains(value, quote):
    if hasattr(value, "model_dump"):
        value = value.model_dump()
    if isinstance(value, str):
        return quote in value
    if isinstance(value, dict):
        return any(contains(v, quote) for v in value.values())
    if isinstance(value, list):
        return any(contains(v, quote) for v in value)
    return False


def review_findings(review, dialogue, artifact, stage, input_vi, *, optional_inference=False):
    bank = inventory(dialogue, artifact, stage)
    rows = {(c.itemType, c.activityId, c.itemId): c for c in review.checks}
    if rows.keys() != bank.keys() or len(rows) != len(review.checks):
        raise ValueError("review coverage mismatch")
    segments = {(act.id, s.id): s.textEn for act in dialogue.activities for s in act.segments}
    def checked(key, row):
        material = row.status == "DEFECT"
        if not row.reasonVi.strip() or row.status == "UNKNOWN":
            raise ValueError("review unresolved")
        if any(e.quoteEn not in segments.get((e.activityId, e.segmentId), "") for e in row.sourceEvidence):
            raise ValueError("review source evidence unanchored")
        if key[0] == "question":
            question = bank[key]
            expected = {(mid, part.id) for mid in question.meaningIds
                        for part in bank[("meaning", key[1], mid)].requiredParts}
            actual = {(p.meaningId, p.partId) for p in row.partChecks}
            if not row.derivedAnswerVi.strip() or actual != expected or len(actual) != len(row.partChecks):
                raise ValueError("review answer/part coverage mismatch")
            material |= (any(not p.requiredByQuestion or not p.sourceSupportsPart for p in row.partChecks)
                         or not row.answerMatchesDerived or row.answerSupport == "not-answerable"
                         or not getattr(row, "addsInformationBeyondQuestion", True)
                         or not optional_inference and question.skill == "supported-inference" and row.answerSupport != "inference")
            if row.answerSupport == "not-applicable" or not row.sourceEvidence:
                raise ValueError("missing independent answer support")
        elif row.partChecks or row.derivedAnswerVi or row.answerSupport != "not-applicable":
            raise ValueError("answer/parts belong only to questions")
        if not material:
            return None
        quote = row.artifactQuote or (bank[key].promptVi if key[0] == "question" else "")
        if (not quote.strip() or not contains(bank[key], quote)
                or not (row.sourceEvidence or row.requestQuote.strip())
                or any(e.quoteEn not in segments.get((e.activityId, e.segmentId), "") for e in row.sourceEvidence)
                or row.requestQuote and row.requestQuote not in input_vi):
            raise ValueError("review evidence unanchored")
        issue = old.finding(row.code, "GENERATE" if key[0] == "dialogue" else stage, key, semantic=True, reason=row.reasonVi)
        issue.update(artifactQuote=quote, sourceEvidence=[e.model_dump() for e in row.sourceEvidence], requestQuote=row.requestQuote)
        if key[0] == "question":
            issue.update(derivedAnswerVi=row.derivedAnswerVi, answerSupport=row.answerSupport,
                         answerMatchesDerived=row.answerMatchesDerived, declaredSkill=bank[key].skill,
                         partChecks=[p.model_dump() for p in row.partChecks])
            if not optional_inference and bank[key].skill == "supported-inference" and row.answerSupport != "inference":
                issue.update(code="semantic_support_classification",
                             constraint="The independently classified answer is not an inference. Rewrite this question and linked targets to genuinely test the requested skill using supported premises, or use the appropriate allowed skill while preserving bank focus coverage.")
            if not getattr(row, "addsInformationBeyondQuestion", True):
                issue.update(code="semantic_no_information_gain",
                             constraint="This answer repeats information already in the question. Remove this question and its unused meaning instead of inventing a replacement to meet a count.")
        return issue

    findings, errors = [], []
    for key, row in rows.items():
        try:
            issue = checked(key, row)
            if issue:
                findings.append(issue)
        except ValueError as error:
            errors.append(dict(itemType=key[0], activityId=key[1], itemId=key[2], error=str(error)))
    return findings, errors


class Recovery:
    def __init__(self, payload, call, deadline, *, grade=False):
        self.payload, self.call, self.deadline = payload, call, deadline
        self.base_calls = 1 if grade else 6
        self.selected = a.selected_release(payload.promptHash)
        self.sparse = self.selected in (a.sparse_candidate, a.dynamic_candidate)
        self.policy = self.selected.HARNESS_POLICY
        self.limit = self.policy["gradeMaxCalls"] if grade else self.policy["maxCalls"]
        self.plan, self.validations, self.attempts, self.disputes = [], [], [], []
        self.patch_feedback = {}
        self.original_source = None

    def reserve(self, count):
        if len(self.plan) + count > self.limit or time.monotonic() >= self.deadline:
            raise w.QualityRejected("recovery budget or deadline exhausted")

    async def invoke(self, purpose, data, model, dialogue=None):
        self.reserve(1)
        self.plan.append(dict(purpose=purpose, sourceHash=h.digest(dialogue) if dialogue else None))
        return await self.call(purpose, data, model)

    def report(self, decision="UNRESOLVED"):
        return dict(policyVersion=self.policy["version"], policyHash=h.digest(self.policy), decision=decision,
                    callPlan=self.plan, extraCalls=max(0, len(self.plan)-self.base_calls), validations=self.validations,
                    attempts=self.attempts, disputedFindings=self.disputes, originalSourceHash=self.original_source)

    def data(self, dialogue, artifact, stage):
        data = dict(inputVi=self.payload.inputVi, learningContext=self.payload.learningContext.model_dump(),
                    dialogue=dialogue.model_dump(), profile=self.payload.profile.model_dump())
        if self.payload.specification:
            data["specification"] = self.payload.specification.model_dump()
        if artifact is None:
            data["sourceOnly"] = True
        else:
            data["groundedQuestions" if stage == "QUESTIONS" else "translations"] = artifact.model_dump()
            if stage == "QUESTIONS":
                # Derive support independently: a supplied "inference" label must
                # not anchor the reviewer into approving explicit recall as inference.
                for activity in data["groundedQuestions"]["activities"]:
                    for item in [*activity["questions"], *activity["assessment"]["meanings"]]:
                        for name in ("skill", "supportKind", "inferenceReasonEn"):
                            item.pop(name, None)
        data["expectedChecks"] = [dict(itemType=kind, activityId=act, itemId=item)
                                  for kind, act, item in inventory(dialogue, artifact, stage)]
        return data

    async def certify(self, dialogue, artifact, stage, *, focused=False):
        purpose = "REVIEW_QUESTIONS" if stage == "QUESTIONS" else "REVIEW"
        data = self.data(dialogue, artifact, stage)
        if focused:
            data["verificationContext"] = dict(instruction="This is a focused check of explanations and unstated conclusions. Derive each answer from source without using existing labels. A repetition of the premise is not a cause. An explicitly spoken answer is not an inference. Check keys and evidence as well as question wording.")
        prior = None
        # One initial review, at most two evidence/format verification calls.
        for _ in range(3):
            review = None
            try:
                review = await self.invoke(purpose, data, UsefulReview if self.sparse and stage == "QUESTIONS" else Review, dialogue)
                findings, row_errors = review_findings(review, dialogue, artifact, stage, self.payload.inputVi, optional_inference=self.sparse)
            except ValueError as error:
                if review is None and not hasattr(error, "failed_output"):
                    raise
                self.validations.append(dict(stage=purpose, status="UNKNOWN", issues=[str(error)]))
                data["verificationContext"] = dict(contractError=str(error), instruction="Recheck the full artifact against source; return exact coverage and anchored evidence.")
                prior = None
                continue
            self.validations.append(dict(stage=purpose, status="UNKNOWN" if row_errors else "FAIL" if findings else "PASS", issues=findings, reviewerErrors=row_errors))
            if row_errors and not findings:
                data["verificationContext"] = dict(contractErrors=row_errors, instruction="Copy quotes exactly from the indicated items; cover the full expectedChecks inventory and verify source meaning.")
                continue
            if not findings:
                if prior:
                    self.disputes.extend(prior)
                if stage == "QUESTIONS" and not focused and len(artifact.activities) > 1:
                    selected = []
                    for act in artifact.activities:
                        questions = [q for q in act.questions if q.skill in (("reason",) if self.sparse else ("reason", "supported-inference"))]
                        if questions:
                            mids = {mid for q in questions for mid in q.meaningIds}
                            selected.append(act.model_copy(update={"questions": questions,
                                "assessment": act.assessment.model_copy(update={"meanings": [m for m in act.assessment.meanings if m.id in mids]})}))
                    if selected:
                        # A review projection, never a replacement bank: the original
                        # inventory and exact patch scopes remain authoritative.
                        return await self.certify(dialogue, (SparseBank if self.sparse else Bank).model_construct(activities=selected), stage, focused=True)
                return []
            identity = lambda i: (i["itemType"], i["activityId"], i["itemId"])
            if prior is not None and {identity(i) for i in findings} <= {identity(i) for i in prior}:
                return [{**i, "confirmed": True} for i in findings]
            prior = findings
            data["verificationContext"] = dict(suspectedFindings=findings,
                instruction="Independently derive the answer from source and actual question first. Confirm only a material defect demonstrated by the quotes and full context. Refute false accusations with a concrete reason; recheck every neighbor too.")
        raise w.QualityRejected("review could not verify diagnosis within budget")

    async def patch(self, dialogue, original, issues, stage, *, grade=False):
        if sum(attempt["stage"] == stage for attempt in self.attempts) >= self.policy["maxPatchPassesPerStage"]:
            raise w.QualityRejected("stage recovery attempts exhausted")
        coverage = stage == "TRANSLATE" and any(i["code"] == "translation_coverage_mismatch" for i in issues)
        allowed, blocked = old.repair_scope(original, [i for i in issues if not coverage or i["code"] != "translation_coverage_mismatch"], stage, flexible=True)
        if coverage:
            counts = Counter((t.activityId, t.segmentId) for t in original.segments)
            expected = [(act.id, segment.id) for act in dialogue.activities for segment in act.segments]
            allowed |= {("translation", act, item) for act, item in expected if counts[(act, item)] != 1}
        if (not allowed and not coverage) or blocked:
            raise w.QualityRejected("recovery diagnosis not localizable")
        # Reserve a patch plus two reviews; no unbounded replays of failed provider calls.
        self.reserve(3)
        targets = [dict(itemType=k, activityId=act, itemId=item) for k, act, item in sorted(allowed)]
        attempt = dict(stage=stage, attempted=True, accepted=False, originalHash=h.digest(original), targets=targets)
        self.attempts.append(attempt)
        data = {**self.data(dialogue, original, stage), "failedOutput": original.model_dump(),
                "issues": old.repair_feedback(issues), "allowedTargets": targets, "preserveQuestionPrompts": grade}
        if coverage:
            data["translationCoverage"] = dict(expectedTargets=[dict(activityId=act, segmentId=item) for act, item in expected],
                instruction="Translate each missing or duplicated source ID in allowedTargets exactly once. Use that ID's source text, not the mislabeled translation. Unlisted unique translations stay unchanged; invalid extra IDs are discarded by the merger.")
        feedback = self.patch_feedback.get(stage)
        if feedback and feedback[0] == attempt["originalHash"]:
            data["formatFeedback"] = feedback[1]
        purpose = "REPAIR_QUESTIONS" if stage == "QUESTIONS" else "REPAIR_TRANSLATIONS"
        # Missing bank coverage does not diagnose any existing question as useless.
        # Keep every activity intact while the model chooses a grounded focus question.
        focus_only = stage == "QUESTIONS" and all(i["code"] == "missing_learning_focus_coverage" for i in issues)
        patch_model = (a.SparseQuestionPatch if self.sparse and not grade and not focus_only else a.PartsQuestionPatch) if stage == "QUESTIONS" else w.TranslationPatch
        try:
            if coverage and not allowed:
                # Extra IDs alone need removal, not another paid model call; full review still follows.
                candidate = merge_translation_coverage(original, None, allowed, dialogue)
                attempt["candidateHash"] = h.digest(candidate)
                return candidate, attempt
            patch = await self.invoke(purpose, data, patch_model, dialogue)
            candidate = (merge_translation_coverage(original, patch, allowed, dialogue) if coverage else
                         merge_sparse_patch(original, patch, allowed) if isinstance(patch, a.SparseQuestionPatch) else old.merge_patch(original, patch, allowed, stage))
            if grade:
                before = old.items(original, stage)
                for key, q in old.items(candidate, stage).items():
                    if key[0] == "question" and (q.promptVi != before[key].promptVi or q.meaningIds != before[key].meaningIds):
                        raise ValueError("cannot change a question already answered by learner")
            attempt["candidateHash"] = h.digest(candidate)
            self.patch_feedback.pop(stage, None)
            return candidate, attempt
        except ValueError as error:
            attempt["reason"] = str(error)
            if not hasattr(error, "failed_output") and 'patch' not in locals():
                raise
            self.patch_feedback[stage] = (attempt["originalHash"], dict(
                failedOutput=patch.model_dump() if 'patch' in locals() else error.failed_output,
                schemaIssues=getattr(error, "schema_issues", [{"type": str(error), "loc": []}]),
                instruction="Correct this rejected patch against the unchanged failedOutput and allowedTargets. Preserve valid neighboring items and retain the required items in every activity."))
            return original, attempt


async def generate(payload, call, deadline):
    r = Recovery(payload, call, deadline)
    dialogue = questions = translations = None
    retained_questions = retained_translations = None
    affected = None
    source_repaired = False
    generation_data = dict(inputVi=payload.inputVi, specification=payload.specification.model_dump(),
                           profile=payload.profile.model_dump(), learningContext=payload.learningContext.model_dump())
    try:
        # At most one source repair. Changing a source invalidates all of its dependents.
        for revision in range(2):
            previous = dialogue
            try:
                dialogue = await r.invoke("GENERATE", generation_data, w.Dialogue, previous)
                if r.original_source is None:
                    r.original_source = h.digest(dialogue)
                if previous is not None:
                    if [act.id for act in dialogue.activities] != [act.id for act in previous.activities]:
                        raise ValueError("source activity IDs changed")
                    for before, after in zip(previous.activities, dialogue.activities):
                        if before.id not in affected and h.digest(before) != h.digest(after):
                            raise ValueError("source patch outside dependency scope")
                w.validate_dialogue(dialogue, payload.specification, payload.profile, flexible=True)
            except ValueError as error:
                if revision or dialogue is None and not hasattr(error, "failed_output"):
                    raise w.QualityRejected("source recovery failed") from error
                affected = {"a1", "a2", "a3"}
                generation_data["recoveryContext"] = dict(failedOutput=dialogue.model_dump() if dialogue else error.failed_output,
                    allowedActivityIds=sorted(affected), issues=[str(error)])
                r.attempts.append(dict(stage="GENERATE", attempted=True, accepted=False, reason="source_contract"))
                continue
            source_repaired = revision > 0
            # Source must be sound before any questions, keys or translations depend on it.
            source_issues = await r.certify(dialogue, None, "TRANSLATE")
            if source_issues:
                if revision:
                    raise w.QualityRejected("source defects remain after recovery")
                affected = {act.id for act in dialogue.activities if act.id >= min(i["activityId"] for i in source_issues)}
                r.reserve(6)
                generation_data["recoveryContext"] = dict(failedOutput=dialogue.model_dump(), allowedActivityIds=sorted(affected),
                    issues=source_issues, instruction="Repair confirmed source defects against the original request. Keep other activities unchanged.")
                r.attempts.append(dict(stage="GENERATE", attempted=True, accepted=False, originalHash=h.digest(dialogue), targets=sorted(affected)))
                continue
            r.reserve(4)
            questions = await r.invoke("QUESTIONS", {**generation_data, "dialogue": dialogue.model_dump()}, a.SparseQuestions if r.sparse else a.FlexibleQuestions, dialogue)
            if retained_questions is not None:
                questions.activities = [act if act.id in affected else next(a for a in retained_questions.activities if a.id == act.id) for act in questions.activities]
            source_issues = []
            for pass_number in range(3):
                structural = old.structural(questions, dialogue, payload, "QUESTIONS")
                if structural:
                    r.validations.append(dict(stage="QUESTIONS", status="FAIL", issues=structural))
                issues = structural or await r.certify(dialogue, questions, "QUESTIONS")
                source_issues = [i for i in issues if i["stage"] == "GENERATE"]
                if source_issues or not issues:
                    break
                if pass_number == 2:
                    raise w.QualityRejected("question recovery exhausted")
                questions, attempt = await r.patch(dialogue, questions, issues, "QUESTIONS")
                # Acceptance is recorded only after complete structural and semantic recheck.
                after = old.structural(questions, dialogue, payload, "QUESTIONS")
                checked = after or await r.certify(dialogue, questions, "QUESTIONS")
                if after:
                    r.validations.append(dict(stage="REPAIR_QUESTIONS", status="FAIL", issues=after))
                attempt["remainingFindings"] = checked
                attempt.update(accepted=not checked, reason="resolved" if not checked else "remaining_or_new_defects")
                if not checked:
                    break
                if any(i["stage"] == "GENERATE" for i in checked):
                    source_issues = [i for i in checked if i["stage"] == "GENERATE"]
                    break
            if not source_issues:
                merged = a.attach_questions(dialogue, questions, payload.learningContext)
                translations = await r.invoke("TRANSLATE", {**dialogue.model_dump(), **generation_data}, w.Translations, dialogue)
                if retained_translations is not None:
                    saved = {(t.activityId, t.segmentId): t for t in retained_translations.segments}
                    translations.segments = [t if t.activityId in affected else saved[(t.activityId, t.segmentId)] for t in translations.segments]
                for pass_number in range(3):
                    structural = old.structural(translations, dialogue, payload, "TRANSLATE", merged)
                    if structural:
                        r.validations.append(dict(stage="TRANSLATE", status="FAIL", issues=structural))
                    issues = structural or await r.certify(dialogue, translations, "TRANSLATE")
                    source_issues = [i for i in issues if i["stage"] == "GENERATE"]
                    if source_issues or not issues:
                        break
                    if pass_number == 2:
                        raise w.QualityRejected("translation recovery exhausted")
                    translations, attempt = await r.patch(dialogue, translations, issues, "TRANSLATE")
                    after = old.structural(translations, dialogue, payload, "TRANSLATE", merged)
                    checked = after or await r.certify(dialogue, translations, "TRANSLATE")
                    if after:
                        r.validations.append(dict(stage="REPAIR_TRANSLATIONS", status="FAIL", issues=after))
                    attempt["remainingFindings"] = checked
                    attempt.update(accepted=not checked, reason="resolved" if not checked else "remaining_or_new_defects")
                    if not checked:
                        break
                    if any(i["stage"] == "GENERATE" for i in checked):
                        source_issues = [i for i in checked if i["stage"] == "GENERATE"]
                        break
            if source_issues:
                if revision:
                    raise w.QualityRejected("source defects remain after recovery")
                affected = {i["activityId"] for i in source_issues}
                # A later activity may depend on an earlier decision. Rebuild the suffix.
                affected = {act.id for act in dialogue.activities if act.id >= min(affected)}
                r.reserve(5)
                retained_questions, retained_translations = questions, translations
                generation_data["recoveryContext"] = dict(failedOutput=dialogue.model_dump(),
                    allowedActivityIds=sorted(affected), issues=source_issues,
                    instruction="Repair source defects against the original learner request, never to justify a bad answer. Keep every other activity byte-for-byte unchanged.")
                r.attempts.append(dict(stage="GENERATE", attempted=True, accepted=False, originalHash=h.digest(dialogue), targets=sorted(affected)))
                continue
            a.validate_content(merged, payload.specification, payload.profile, payload.learningContext, translations)
            result = {**merged.model_dump(), "translations": translations.model_dump()["segments"], "qualityPassed": True}
            if source_repaired:
                for attempt in r.attempts:
                    if attempt["stage"] == "GENERATE":
                        attempt.update(accepted=True, reason="source_and_dependents_revalidated")
            report = r.report("ACCEPTED")
            report.update(sourceHash=h.digest(dialogue), contentHash=h.digest(result))
            return result, {"qualityHarness": report}
        raise w.QualityRejected("source recovery exhausted")
    except w.QualityRejected as error:
        error.quality_harness = r.report()
        raise


async def grade(payload, call, deadline):
    r = Recovery(payload, call, deadline, grade=True)
    activity = payload.activity.model_copy(deep=True)
    dialogue = w.Dialogue.model_construct(activities=[w.DialogueActivity.model_validate(activity.model_dump(include={"id", "segments"}))])
    r.original_source = h.digest(dialogue)
    # Saved public questions omit draft-only fields. These are not learner evidence.
    draft = (a.SparseActivityDraft if r.sparse else a.FlexibleActivityDraft).model_validate(dict(id=activity.id, assessment=activity.assessment.model_dump(), questions=[
        {**q.model_dump(exclude={"type"}), "factEn": next(m.meaningEn for m in activity.assessment.meanings if m.id == q.meaningIds[0]), "speechAct": "statement"}
        for q in activity.questions]))
    bank = (SparseBank if r.sparse else Bank)(activities=[draft])
    try:
        issues = []
        try:
            a.validate_questions(bank, dialogue, payload.learningContext, collect_errors=True, require_focus=False)
        except ValueError as error:
            for invalid in getattr(error, "item_errors", [error]):
                activity_id, item_id = getattr(invalid, "activity_id", None), getattr(invalid, "item_id", None)
                key = (getattr(invalid, "item_kind", "question"), activity_id, item_id) if activity_id and item_id else None
                issues.append(old.finding(str(invalid).replace(" ", "_"), "QUESTIONS", key))
        if not issues:
            issues = await r.certify(dialogue, bank, "QUESTIONS")
        if any(i["stage"] == "GENERATE" for i in issues):
            raise w.QualityRejected("saved source requires regeneration; learner not scored")
        repair_metadata = {}
        if issues:
            bank, attempt = await r.patch(dialogue, bank, issues, "QUESTIONS", grade=True)
            a.validate_questions(bank, dialogue, payload.learningContext, require_focus=False)
            remaining = await r.certify(dialogue, bank, "QUESTIONS")
            attempt.update(accepted=not remaining, reason="resolved" if not remaining else "remaining_or_new_defects")
            if remaining:
                raise w.QualityRejected("key recovery failed; learner not scored")
            fixed = bank.activities[0]
            activity.assessment = fixed.assessment
            activity.questions = [a.Question(**q.model_dump(exclude={"factEn", "speechAct"}), type="MEANING_RECALL_VI") for q in fixed.questions]
            repair_metadata["assessmentRepair"] = dict(originalHash=h.digest(payload.activity), activity=activity.model_dump())
        checked_payload = payload.model_copy(update={"activity": activity})
        raw = await r.invoke("GRADE", dict(activity=activity.model_dump(), assessmentSubmission=payload.assessmentSubmission.model_dump(),
                             learningContext=payload.learningContext.model_dump()), a.AlignedPartsGrade, dialogue)
        try:
            result = a.derive_grade(raw, checked_payload)
        except ValueError as error:
            raise w.QualityRejected("invalid grade evidence; learner not scored") from error
        return result.model_dump(), {"qualityHarness": r.report("ACCEPTED"), **repair_metadata}
    except w.QualityRejected as error:
        error.quality_harness = r.report()
        raise
