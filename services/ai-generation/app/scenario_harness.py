"""Versioned scoped repair; in-memory artifacts only, never replay a partial job."""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field

from . import scenario_workflow as w
from .observability import observation, _safe, _root, content as trace_content


def digest(value):
    if hasattr(value, "model_dump"):
        value = value.model_dump(mode="json")
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def issue(code, stage, *, activity=None, item=None, semantic=False):
    field_name = "questionId" if stage == "QUESTIONS" else "segmentId"
    target = {"activityId": activity, field_name: item} if activity and item else {}
    return {"code": code, "stage": stage, "path": "/".join(str(x) for x in (stage, activity, item) if x),
            **target, "severity": "warning" if semantic else "error", "repairable": bool(target)}


@dataclass
class State:
    deadline: float
    policy: dict = field(default_factory=lambda: w.harness_release.HARNESS_POLICY)
    extra_calls: int = 0
    attempts: list = field(default_factory=list)
    validations: list = field(default_factory=list)
    artifacts: dict = field(default_factory=dict)
    format_retries: set = field(default_factory=set)

    def available(self, count):
        return self.extra_calls + count <= self.policy["maxExtraCalls"] and time.monotonic() < self.deadline

    async def extra(self, call, purpose, data, model):
        if not self.available(1):
            raise w.QualityRejected("repair budget or deadline exhausted")
        self.extra_calls += 1
        with observation("scenario.extra." + purpose.lower(), metadata={"callClass": "extra",
                         "extraCallOrdinal": self.extra_calls, "purpose": purpose}):
            return await call(purpose, data, model)

    def record(self, stage, status, issues=()):
        row = {"stage": stage, "status": status, "issues": list(issues)}
        self.validations.append(row)
        with observation("scenario.validate." + stage.lower(), metadata={"status": status,
                         "attempt": len(self.validations), "issueCodes": ",".join(i["code"] for i in issues)[:180]}) as span:
            _safe(span, "update", output=trace_content(row))

    def report(self):
        return {"policyVersion": self.policy["version"],
                "policyHash": digest(self.policy), "extraCalls": self.extra_calls,
                "artifacts": self.artifacts, "validations": self.validations, "attempts": self.attempts}


def question_issues(questions, dialogue, payload, *, detailed=False):
    try:
        merged = w.attach_questions(dialogue, questions, payload.learningContext)
        w.validate_content(merged, payload.specification, payload.profile, flexible=True)
        return []
    except ValueError as error:
        activity, item = getattr(error, "activity_id", None), getattr(error, "question_id", None)
        # This independent content gate runs after the draft gate and has an exact item scope.
        if str(error) == "answer leaked in question":
            return [issue("answer_leak", "QUESTIONS", activity=a.id, item=q.id)
                    for a in questions.activities for q in a.questions if q.answerVi.casefold() in q.promptVi.casefold()]
        codes = {"unanchored fact evidence": "evidence_unanchored",
                 "agreement requires ordered evidence from both speakers": "agreement_evidence_order",
                 "proposal question asserts agreement": "proposal_asserts_agreement",
                 "question skill outside practice profile": "skill_outside_profile",
                 "unsupported inference policy or missing premise explanation": "inference_policy",
                 "inference support mismatch": "inference_support",
                 "duplicate or empty fact": "duplicate_fact", "missing learning focus coverage": "focus_coverage"}
        row = issue(codes.get(str(error), "question_structure"), "QUESTIONS", activity=activity, item=item)
        if detailed:
            row["constraint"] = str(error)
        return [row]


def translation_issues(translations, merged, payload):
    try:
        w.validate_content(merged, payload.specification, payload.profile, translations, True)
        return []
    except ValueError as error:
        if str(error) == "untranslated segment":
            source = {(a.id, s.id): s.textEn for a in merged.activities for s in a.segments}
            return [issue("untranslated_segment", "TRANSLATE", activity=t.activityId, item=t.segmentId)
                    for t in translations.segments if not t.textVi.strip() or
                    (len(source[(t.activityId, t.segmentId)].split()) >= 3 and
                     " ".join(t.textVi.casefold().split()) == " ".join(source[(t.activityId, t.segmentId)].casefold().split()))]
        return [issue("translation_coverage", "TRANSLATE")]


def review_status(verdict, artifact, stage, *, detailed=False):
    """Coverage/schema UNKNOWN differs from a complete review reporting semantic FAIL."""
    if verdict is None:
        return "UNKNOWN", []
    is_questions = stage == "QUESTIONS"
    rows = verdict.questionChecks if is_questions else verdict.translationChecks
    key = "questionId" if is_questions else "segmentId"
    expected = ({(a.id, q.id) for a in artifact.activities for q in a.questions} if is_questions else
                {(t.activityId, t.segmentId) for t in artifact.segments})
    actual = [(r.activityId, getattr(r, key)) for r in rows]
    if len(actual) != len(expected) or set(actual) != expected:
        return "UNKNOWN", [issue("review_coverage", stage)]
    if not is_questions:
        categories = [r.category for r in verdict.rubricChecks]
        if len(categories) != 5 or set(categories) != {"SOURCE_ROLES", "DIALOGUE", "TRANSLATIONS", "PROFILE", "LEARNING_GOAL"}:
            return "UNKNOWN", [issue("review_coverage", stage)]
    issues = []
    for row in rows:
        for flag, value in row.model_dump().items():
            if value is False:
                finding = issue("semantic_" + flag, stage, activity=row.activityId,
                                item=getattr(row, key), semantic=True)
                if detailed:
                    finding["reasonVi"] = row.reasonVi
                issues.append(finding)
    try:
        if is_questions:
            w.validate_question_review.__wrapped__(verdict, artifact, True)
        else:
            w.validate_review.__wrapped__(verdict, artifact)
        return "PASS", []
    except ValueError:
        return "FAIL", issues


def targets(issues, stage):
    key = "questionId" if stage == "QUESTIONS" else "segmentId"
    return {(i["activityId"], i[key]) for i in issues if i["repairable"]}


def merge_patch(original, patch, allowed, stage):
    """Exact allowlist and equality protect every non-targeted artifact before adoption."""
    is_questions = stage == "QUESTIONS"
    rows = patch.replacements
    actual = [(r.activityId, r.question.id if is_questions else r.segmentId) for r in rows]
    if set(actual) != allowed or len(actual) != len(allowed):
        raise ValueError("patch targets mismatch")
    candidate = original.model_copy(deep=True)
    replacements = dict(zip(actual, rows))
    if is_questions:
        for activity in candidate.activities:
            activity.questions = [replacements[(activity.id, q.id)].question if (activity.id, q.id) in replacements else q
                                  for q in activity.questions]
        before = {(a.id, q.id): q for a in original.activities for q in a.questions}
        after = {(a.id, q.id): q for a in candidate.activities for q in a.questions}
    else:
        candidate.segments = [replacements.get((t.activityId, t.segmentId), t) for t in candidate.segments]
        before = {(t.activityId, t.segmentId): t for t in original.segments}
        after = {(t.activityId, t.segmentId): t for t in candidate.segments}
    if set(before) != set(after) or any(digest(before[k]) != digest(after[k]) for k in before if k not in allowed):
        raise ValueError("patch scope changed")
    return candidate


def non_regressing(previous, current, allowed, stage, *, scoped=False):
    if previous is None:
        return True
    key = "questionId" if stage == "QUESTIONS" else "segmentId"
    old_rows = previous.questionChecks if stage == "QUESTIONS" else previous.translationChecks
    new_rows = current.questionChecks if stage == "QUESTIONS" else current.translationChecks
    new = {(r.activityId, getattr(r, key)): r.model_dump() for r in new_rows}
    for row in old_rows:
        if scoped and (row.activityId, getattr(row, key)) not in allowed:
            continue
        values = new.get((row.activityId, getattr(row, key)), {})
        if any(value is True and values.get(flag) is not True for flag, value in row.model_dump().items()):
            return False
    if stage == "TRANSLATE" and not scoped:
        rubrics = {r.category: r.passed for r in current.rubricChecks}
        if any(r.passed and not rubrics.get(r.category, False) for r in previous.rubricChecks):
            return False
    return True


def item_map(artifact, stage):
    return ({(a.id, q.id): q for a in artifact.activities for q in a.questions} if stage == "QUESTIONS" else
            {(t.activityId, t.segmentId): t for t in artifact.segments})


def retain_unrepaired_findings(previous, current, changed, stage):
    """An unchanged item cannot lose an earlier failure merely because the judge changed its mind."""
    if previous is None:
        return current
    verdict = current.model_copy(deep=True)
    key = "questionId" if stage == "QUESTIONS" else "segmentId"
    field_name = "questionChecks" if stage == "QUESTIONS" else "translationChecks"
    old = {(r.activityId, getattr(r, key)): r for r in getattr(previous, field_name)}
    for row in getattr(verdict, field_name):
        ident = (row.activityId, getattr(row, key))
        if ident in changed:
            continue
        before = old[ident]
        for flag, value in before.model_dump().items():
            if value is False:
                setattr(row, flag, False)
                row.reasonVi = before.reasonVi
                verdict.valid = False
    if stage == "TRANSLATE":
        old_rubrics = {r.category: r for r in previous.rubricChecks}
        for row in verdict.rubricChecks:
            # A translation patch cannot repair frozen dialogue/spec/profile defects.
            if row.category != "TRANSLATIONS" and not old_rubrics[row.category].passed:
                row.passed = False
                row.reasonVi = old_rubrics[row.category].reasonVi
                verdict.valid = False
    return verdict


async def generate(dialogue, payload, call, deadline):
    state = State(deadline, policy=w.personalized_release(payload.promptHash).HARNESS_POLICY)
    with observation("scenario.quality_harness", metadata={"harnessPolicyVersion": state.policy["version"],
                     "harnessPolicyHash": digest(state.policy)}) as span:
        outcome = "FAILED_OR_UNKNOWN"
        try:
            result = await _generate(dialogue, payload, call, state)
            outcome = "COMPLETED"
            _safe(_root.get(), "update", metadata={"rejectedGate": "",
                  "harnessOutcome": outcome, "harnessDeterministicStatus": "PASS",
                  "harnessQualityPassed": result[0]["qualityPassed"],
                  "harnessRepairsAccepted": sum(a["accepted"] for a in state.attempts)})
            return result
        finally:
            _safe(span, "update", output=trace_content({"outcome": outcome, **state.report()}))
            last = state.attempts[-1] if state.attempts else {}
            _safe(span, "update", metadata={"outcome": outcome, "extraCalls": state.extra_calls,
                  "lastRepairReason": last.get("reason"), "lastOriginalHash": last.get("originalHash"),
                  "lastCandidateHash": last.get("candidateHash")})


async def _generate(dialogue, payload, call, state):
    scoped = state.policy["version"] == "scenario-quality-harness.v2"
    context = {"learningContext": payload.learningContext.model_dump(exclude_none=True)}
    frozen = {"dialogue": digest(dialogue), "specification": digest(payload.specification),
              "learningContext": digest(payload.learningContext)}
    state.artifacts.update(frozen)

    def question_data(questions):
        return {"content": w.attach_questions(dialogue, questions, payload.learningContext).model_dump(),
                "groundedQuestions": questions.model_dump(), **context}

    def translation_data(translations):
        return {"inputVi": payload.inputVi, "specification": payload.specification.model_dump(),
                "profile": payload.profile.model_dump(), "dialogue": dialogue.model_dump(),
                "translations": translations.model_dump(), **context}

    async def review(stage, artifact, *, extra=False, retry_format=True, repair_context=None):
        purpose = "REVIEW_QUESTIONS" if stage == "QUESTIONS" else "REVIEW"
        model = w.QuestionEvidenceReview if stage == "QUESTIONS" else w.PersonalizedReview
        data = question_data(artifact) if stage == "QUESTIONS" else translation_data(artifact)
        if repair_context is not None:
            data["repairContext"] = repair_context
        verdict = None
        try:
            verdict = await (state.extra(call, purpose, data, model) if extra else call(purpose, data, model))
        except ValueError as error:
            state.record(purpose, "UNKNOWN", [issue("review_schema", stage)])
            if retry_format and purpose not in state.format_retries and state.available(1):
                state.format_retries.add(purpose)
                data["formatFeedback"] = {"failedOutput": getattr(error, "failed_output", None),
                    "schemaIssues": getattr(error, "schema_issues", []),
                    "instruction": "Return the same independent review in the exact schema, within field limits. Do not change the lesson or force passing flags."}
                try:
                    verdict = await state.extra(call, purpose, data, model)
                except ValueError:
                    pass
        status, issues = review_status(verdict, artifact, stage, detailed=scoped)
        # Coverage-only errors can be retried once just like malformed JSON, with actual failed output.
        if (status == "UNKNOWN" and verdict is not None and retry_format
                and purpose not in state.format_retries and state.available(1)):
            state.format_retries.add(purpose)
            state.record(purpose, status, issues)
            data["formatFeedback"] = {"failedOutput": verdict.model_dump(), "schemaIssues": issues,
                                       "instruction": "Cover each supplied ID exactly once using the exact schema. Do not change content or force passing flags."}
            try:
                verdict = await state.extra(call, purpose, data, model)
            except ValueError:
                verdict = None
            status, issues = review_status(verdict, artifact, stage, detailed=scoped)
        state.record(purpose, status, issues)
        return verdict, status, issues

    async def repair(stage, original, issues, previous=None):
        allowed = targets(issues, stage)
        if not allowed or any(not i["repairable"] for i in issues) or not state.available(2):
            state.attempts.append({"stage": stage, "accepted": False, "attempted": False,
                                   "originalHash": digest(original),
                                   "reason": "unlocalized_issue" if not allowed or any(not i["repairable"] for i in issues)
                                             else "budget_or_deadline"})
            return original, previous, None
        purpose = "REPAIR_QUESTIONS" if stage == "QUESTIONS" else "REPAIR_TRANSLATIONS"
        model = w.QuestionPatch if stage == "QUESTIONS" else w.TranslationPatch
        item_key = "questionId" if stage == "QUESTIONS" else "segmentId"
        attempt = {"stage": stage, "targets": [{"activityId": a, item_key: q} for a, q in sorted(allowed)],
                   "issueCodes": sorted({i["code"] for i in issues}), "originalHash": digest(original),
                   "accepted": False, "attempted": True, "reason": "invalid_patch"}
        state.attempts.append(attempt)
        with observation("scenario.repair." + stage.lower(), input={"original": original, "issues": issues},
                         metadata={"attempt": 1, "originalHash": digest(original),
                                   "targets": ",".join(a + "/" + q for a, q in sorted(allowed))[:180]}) as span:
            try:
                patch = await state.extra(call, purpose, {"dialogue": dialogue.model_dump(),
                    "specification": payload.specification.model_dump(), "failedOutput": original.model_dump(),
                    "issues": issues, "allowedTargets": attempt["targets"], **context}, model)
                candidate = merge_patch(original, patch, allowed, stage)
                attempt["candidateHash"] = digest(candidate)
                if attempt["candidateHash"] == attempt["originalHash"]:
                    attempt["reason"] = "unchanged_patch"
                    return original, previous, None
                changed = allowed
                repair_context = None
                if scoped:
                    before, after = item_map(original, stage), item_map(candidate, stage)
                    changed = {k for k in allowed if digest(before[k]) != digest(after[k])}
                    attempt["changedTargets"] = [{"activityId": a, item_key: i} for a, i in sorted(changed)]
                    attempt["unchangedTargets"] = [{"activityId": a, item_key: i} for a, i in sorted(allowed - changed)]
                    repair_context = {"priorFindings": issues, "changedTargets": attempt["changedTargets"],
                        "unchangedTargets": attempt["unchangedTargets"],
                        "originalItems": [{"activityId": a, item_key: i, "value": before[(a, i)].model_dump()}
                                          for a, i in sorted(allowed)]}
                errors = (question_issues(candidate, dialogue, payload, detailed=scoped) if stage == "QUESTIONS" else
                          translation_issues(candidate, merged, payload))
                state.record(purpose, "FAIL" if errors else "PASS", errors)
                if errors:
                    attempt["reason"] = "deterministic_validation"
                    return original, previous, None
                verdict, status, semantic = await review(stage, candidate, extra=True, retry_format=False,
                                                        repair_context=repair_context)
                unresolved_targets = targets(semantic, stage) & changed
                if status == "UNKNOWN" or unresolved_targets or not non_regressing(previous, verdict, changed, stage, scoped=scoped):
                    attempt["reason"] = "review_unknown_or_regression"
                    return original, previous, None
                if scoped:
                    verdict = retain_unrepaired_findings(previous, verdict, changed, stage)
                    status, semantic = review_status(verdict, candidate, stage, detailed=True)
                    state.record("REPAIR_REVIEW", status, semantic)
                    attempt["remainingIssueCodes"] = sorted({i["code"] for i in semantic})
                # A global review false is still advisory, even when localized fixes are accepted.
                attempt.update(accepted=True, reason="accepted_with_warnings" if scoped and status != "PASS" else "accepted")
                return candidate, verdict, status
            except ValueError:
                return original, previous, None
            finally:
                _safe(span, "update", output=trace_content(attempt), metadata={"accepted": attempt["accepted"],
                      "reason": attempt["reason"], "candidateHash": attempt.get("candidateHash")})

    questions = await call("QUESTIONS", {"dialogue": dialogue.model_dump(), **context}, w.PersonalizedQuestions)
    errors = question_issues(questions, dialogue, payload, detailed=scoped)
    state.record("QUESTIONS", "FAIL" if errors else "PASS", errors)
    question_review = None
    question_status = None
    if errors:
        questions, question_review, question_status = await repair("QUESTIONS", questions, errors)
        if question_issues(questions, dialogue, payload, detailed=scoped):
            raise w.QualityRejected("invalid scenario content after bounded repair")
    merged = w.attach_questions(dialogue, questions, payload.learningContext)
    if question_status is None:
        question_review, question_status, warnings = await review("QUESTIONS", questions)
        if warnings and question_status == "FAIL":
            questions, candidate_review, candidate_status = await repair("QUESTIONS", questions, warnings, question_review)
            if candidate_status is not None:
                question_review, question_status = candidate_review, candidate_status
                merged = w.attach_questions(dialogue, questions, payload.learningContext)
    translations = await call("TRANSLATE", {**dialogue.model_dump(), **context}, w.Translations)
    errors = translation_issues(translations, merged, payload)
    state.record("TRANSLATE", "FAIL" if errors else "PASS", errors)
    verdict = None
    review_status_value = None
    if errors:
        translations, verdict, review_status_value = await repair("TRANSLATE", translations, errors)
        if translation_issues(translations, merged, payload):
            raise w.QualityRejected("invalid translations after bounded repair")
    if review_status_value is None:
        verdict, review_status_value, warnings = await review("TRANSLATE", translations)
        if warnings and review_status_value == "FAIL":
            translations, candidate_review, candidate_status = await repair("TRANSLATE", translations, warnings, verdict)
            if candidate_status is not None:
                verdict, review_status_value = candidate_review, candidate_status
    w.validate_content(merged, payload.specification, payload.profile, translations, True)
    if frozen != {"dialogue": digest(dialogue), "specification": digest(payload.specification),
                  "learningContext": digest(payload.learningContext)}:
        raise w.QualityRejected("immutable harness artifact changed")
    state.artifacts.update(questions=digest(questions), translations=digest(translations))
    result = {**merged.model_dump(), "translations": translations.model_dump()["segments"],
              "qualityPassed": question_status == review_status_value == "PASS"}
    return result, {"qualityHarness": state.report(), "reviewAdvisory": {
        "questionsPassed": question_status == "PASS", "reviewPassed": review_status_value == "PASS",
        "questionsStatus": question_status, "reviewStatus": review_status_value,
        "questions": question_review.model_dump() if question_review else None,
        "review": verdict.model_dump() if verdict else None}}
