"""Finite v3 repairs with exact item scopes and frozen dialogue/context."""
from . import scenario_assessment as a
from . import scenario_harness as h
from . import scenario_workflow as w
from .observability import observation, _safe, content as trace_content

IMPLEMENTATION_VERSION = "assessment-repair-feedback.v1"


def items(artifact, stage):
    if stage == "TRANSLATE":
        return {("translation", t.activityId, t.segmentId): t for t in artifact.segments}
    return {**{("meaning", act.id, m.id): m for act in artifact.activities for m in act.assessment.meanings},
            **{("question", act.id, q.id): q for act in artifact.activities for q in act.questions}}


def finding(code, stage, key=None, *, semantic=False, reason=None):
    result = h.issue(code, stage, semantic=semantic)
    if key:
        kind, activity, item = key
        result.update(activityId=activity, itemType=kind, itemId=item, repairable=True,
                      path="/".join((stage, activity, kind, item)))
        result["meaningId" if kind == "meaning" else "questionId" if kind == "question" else "segmentId"] = item
    if reason:
        result["reasonVi"] = reason
    return result


def structural(artifact, dialogue, payload, stage, merged=None):
    try:
        if stage == "QUESTIONS":
            if getattr(a.selected_release(payload.promptHash), "COLLECT_ITEM_ERRORS", False):
                a.validate_questions(artifact, dialogue, payload.learningContext, collect_errors=True)
            content = a.attach_questions(dialogue, artifact, payload.learningContext)
            a.validate_content(content, payload.specification, payload.profile, payload.learningContext)
        else:
            a.validate_content(merged, payload.specification, payload.profile, payload.learningContext, artifact)
        return []
    except ValueError as error:
        issues = []
        for invalid in getattr(error, "item_errors", [error]):
            activity, item = getattr(invalid, "activity_id", None), getattr(invalid, "item_id", None)
            key = (getattr(invalid, "item_kind", "question"), activity, item) if activity and item else None
            issues.append(finding(str(invalid).replace(" ", "_"), stage, key))
        return issues


def repair_scope(original, issues, stage, *, flexible=False):
    allowed = {(i["itemType"], i["activityId"], i["itemId"]) for i in issues if i["repairable"]}
    unlocalized = [i for i in issues if not i["repairable"]]
    if flexible and stage == "QUESTIONS":
        # Coverage belongs to the bank. Let the model choose a grounded pair; never relabel it in code.
        if any(i["code"] == "missing_learning_focus_coverage" for i in unlocalized):
            allowed |= items(original, stage).keys()
        unlocalized = [i for i in unlocalized if i["code"] != "missing_learning_focus_coverage"]
        while True:
            before = set(allowed)
            for act in original.activities:
                for question in act.questions:
                    key = ("question", act.id, question.id)
                    targets = {("meaning", act.id, mid) for mid in question.meaningIds}
                    if key in allowed or targets & allowed:
                        allowed |= {key} | targets
            if allowed == before:
                break
    blocks = bool(unlocalized) and not (flexible and stage == "TRANSLATE"
        and all(i["code"].startswith("semantic_") for i in unlocalized))
    return allowed, blocks


def repair_feedback(issues):
    """Explain deterministic contracts without asking the model to infer them from code names."""
    constraints = {
        "missing_learning_focus_coverage": "Use the requested focusSkill in both a meaningful question and its linked scoring target. Choose a suitable activity from the transcript; rewrite the actual question, answer and requiredParts, not only the skill label. Keep other items unchanged.",
        "unanchored_evidence": "Every quoteEn must be an exact substring of its segmentId in this activity. Do not paraphrase quotes or borrow a segment from another activity.",
        "agreement_requires_ordered_evidence_from_both_speakers": "If testing a mutual agreement, cite the proposal and acceptance from both speakers in transcript order. If testing only one speaker's statement or intention, use that speech act consistently; do not invent acceptance.",
        "question_omits_referenced_meaning_evidence": "Question evidence must contain all the evidence of each referenced meaning, with matching segment IDs and exact quotes.",
        "required_part_outside_meaning_evidence": "Each requiredPart quote must be contained in its parent meaning evidence with the same segment ID. Keep only parts actually asked by the question.",
        "inference_support_mismatch": "Keep skill, supportKind and inferenceReasonEn consistent: supported-inference requires grounded premises; direct support uses an empty inference explanation.",
    }
    return [{**issue, **({"constraint": constraints[issue["code"]]} if issue["code"] in constraints else {})}
            for issue in issues]


def review_items(verdict, stage):
    if stage == "TRANSLATE":
        return {("translation", r.activityId, r.segmentId): r for r in verdict.translationChecks}
    return {**{("meaning", r.activityId, r.meaningId): r for r in verdict.meaningChecks},
            **{("question", r.activityId, r.questionId): r for r in verdict.questionChecks}}


def review_status(verdict, artifact, stage):
    if verdict is None:
        return "UNKNOWN", []
    rows = review_items(verdict, stage)
    raw_count = len(verdict.translationChecks) if stage == "TRANSLATE" else len(verdict.meaningChecks) + len(verdict.questionChecks)
    if rows.keys() != items(artifact, stage).keys() or raw_count != len(rows):
        return "UNKNOWN", [finding("review_coverage", stage)]
    if stage == "TRANSLATE":
        categories = [r.category for r in verdict.rubricChecks]
        if len(categories) != 5 or set(categories) != {"SOURCE_ROLES", "DIALOGUE", "TRANSLATIONS", "PROFILE", "LEARNING_GOAL"}:
            return "UNKNOWN", [finding("review_coverage", stage)]
    issues = []
    for key, row in rows.items():
        for flag, value in row.model_dump().items():
            if value is False:
                issues.append(finding("semantic_" + flag, stage, key, semantic=True, reason=row.reasonVi))
        if not row.reasonVi.strip():
            return "UNKNOWN", [finding("review_reason", stage)]
        if key[0] == "question" and (not row.requestedMeaningEn.strip() or not row.answerUnitsVi or any(not x.strip() for x in row.answerUnitsVi)):
            issues.append(finding("semantic_answer_units", stage, key, semantic=True, reason=row.reasonVi))
    if stage == "TRANSLATE":
        for rubric in verdict.rubricChecks:
            if not rubric.reasonVi.strip():
                return "UNKNOWN", [finding("review_reason", stage)]
            if not rubric.passed:
                issues.append(finding("semantic_" + rubric.category.lower(), stage, semantic=True, reason=rubric.reasonVi))
    return ("PASS", []) if verdict.valid and not verdict.issues and not issues else ("FAIL", issues)


def merge_patch(original, patch, allowed, stage):
    if stage == "TRANSLATE":
        return h.merge_patch(original, patch, {(activity, item) for _, activity, item in allowed}, stage)
    replacements = [("meaning", r.activityId, r.meaning.id, r.meaning) for r in patch.meaningReplacements]
    replacements += [("question", r.activityId, r.question.id, r.question) for r in patch.questionReplacements]
    keys = [(kind, activity, item) for kind, activity, item, _ in replacements]
    if len(keys) != len(set(keys)) or set(keys) != allowed:
        raise ValueError("patch targets mismatch")
    by_key = {key: replacement[3] for key, replacement in zip(keys, replacements)}
    candidate = original.model_copy(deep=True)
    for activity in candidate.activities:
        activity.assessment.meanings = [by_key.get(("meaning", activity.id, m.id), m) for m in activity.assessment.meanings]
        activity.questions = [by_key.get(("question", activity.id, q.id), q) for q in activity.questions]
    before, after = items(original, stage), items(candidate, stage)
    if before.keys() != after.keys() or any(h.digest(before[k]) != h.digest(after[k]) for k in before if k not in allowed):
        raise ValueError("patch scope changed")
    return candidate


def reconcile(previous, current, changed, stage):
    if previous is None:
        return current
    before, after = review_items(previous, stage), review_items(current, stage)
    for key in changed:
        if any(value is True and after[key].model_dump().get(flag) is not True
               for flag, value in before[key].model_dump().items()):
            raise ValueError("review regressed")
    verdict = current.model_copy(deep=True)
    for key, row in review_items(verdict, stage).items():
        if key in changed:
            continue
        for flag, value in before[key].model_dump().items():
            if value is False:
                setattr(row, flag, False)
                row.reasonVi = before[key].reasonVi
                verdict.valid = False
    if stage == "TRANSLATE":
        rubrics = {r.category: r for r in previous.rubricChecks}
        for row in verdict.rubricChecks:
            if row.category != "TRANSLATIONS" and not rubrics[row.category].passed:
                row.passed = False
                row.reasonVi = rubrics[row.category].reasonVi
                verdict.valid = False
    return verdict


async def generate(dialogue, payload, call, deadline):
    selected = a.selected_release(payload.promptHash)
    flexible = selected is a.prevention_candidate
    parts = selected.POLICY_VERSION == a.parts_release.POLICY_VERSION
    independent = bool(getattr(selected, "AUDIT_PROTOCOL", None))
    evidenced = bool(getattr(selected, "EVIDENCE_AUDIT", False))
    state = h.State(deadline, policy=selected.HARNESS_POLICY)
    context = {"learningContext": payload.learningContext.model_dump(exclude_none=True)}
    frozen = {"dialogue": h.digest(dialogue), "specification": h.digest(payload.specification),
              "learningContext": h.digest(payload.learningContext)}
    state.artifacts.update(frozen)
    merged = None

    def report():
        return {**state.report(), "implementationVersion": IMPLEMENTATION_VERSION}

    def review_data(stage, artifact):
        if stage == "QUESTIONS":
            return {"content": a.attach_questions(dialogue, artifact, payload.learningContext).model_dump(),
                    "groundedQuestions": artifact.model_dump(), **context}
        return {"inputVi": payload.inputVi, "specification": payload.specification.model_dump(),
                "profile": payload.profile.model_dump(), "dialogue": dialogue.model_dump(),
                "translations": artifact.model_dump(), **context}

    async def review(stage, artifact, *, extra=False, retry_format=True, repair_context=None):
        if stage == "QUESTIONS" and independent:
            from . import scenario_assessment_audit as audit
            data = review_data(stage, artifact)
            ledgers = {}
            verdict = None
            meaning_model, question_model, review_model = audit.models_for(selected)
            stages = (("AUDIT_MEANINGS", meaning_model), ("AUDIT_QUESTIONS", question_model),
                      ("REVIEW_QUESTIONS", review_model))
            for purpose, model in stages:
                stage_data = {**data, **ledgers}
                if purpose == "REVIEW_QUESTIONS" and repair_context is not None:
                    stage_data["repairContext"] = repair_context
                while True:
                    raw = None
                    try:
                        raw = await (state.extra(call, purpose, stage_data, model) if extra or "formatFeedback" in stage_data else call(purpose, stage_data, model))
                        if purpose == "AUDIT_MEANINGS":
                            audit.validate_meanings(raw, artifact)
                            ledgers["meaningAudit"] = raw.model_dump()
                        elif purpose == "AUDIT_QUESTIONS":
                            (audit.validate_question_coverage if evidenced else audit.validate_questions)(raw, artifact)
                            ledgers["questionAudit"] = raw.model_dump()
                        else:
                            normalize = audit.to_direct_review if getattr(selected, "DIRECT_PART_AUDIT", False) else (
                                audit.to_evidence_review if evidenced else audit.to_review)
                            verdict = normalize(raw,
                                meaning_model.model_validate(ledgers["meaningAudit"]),
                                question_model.model_validate(ledgers["questionAudit"]), artifact, dialogue)
                        state.artifacts[purpose + ":" + h.digest(artifact)] = h.digest(raw)
                        status, findings = ("PASS", []) if purpose != "REVIEW_QUESTIONS" else review_status(verdict, artifact, stage)
                        if status == "UNKNOWN":
                            raise ValueError("audit review contract mismatch")
                        state.record(purpose, status, findings)
                        break
                    except ValueError as error:
                        if raw is None and not hasattr(error, "failed_output"):
                            raise
                        state.record(purpose, "UNKNOWN", [finding("audit_contract", stage)])
                        if not retry_format or "QUESTION_AUDIT_CHAIN" in state.format_retries or not state.available(1):
                            contract = w.QualityRejected("invalid independent audit contract")
                            contract.audit_contract = True
                            raise contract from error
                        state.format_retries.add("QUESTION_AUDIT_CHAIN")
                        stage_data["formatFeedback"] = {"failedOutput": raw.model_dump() if raw else getattr(error, "failed_output", None),
                            "schemaIssues": getattr(error, "schema_issues", [{"type": str(error), "loc": []}]),
                            "instruction": "Cover each supplied ID exactly once in the exact schema; do not force passing flags or change artifacts."}
            status, issues = review_status(verdict, artifact, stage)
            return verdict, status, issues
        purpose = "REVIEW_QUESTIONS" if stage == "QUESTIONS" else "REVIEW"
        model = (a.FlexibleQuestionReview if flexible else a.QuestionReview) if stage == "QUESTIONS" else w.PersonalizedReview
        data = review_data(stage, artifact)
        if repair_context is not None:
            data["repairContext"] = repair_context
        verdict = None
        failed_output = schema_issues = None
        try:
            verdict = await (state.extra(call, purpose, data, model) if extra else call(purpose, data, model))
        except ValueError as error:
            failed_output = getattr(error, "failed_output", None)
            schema_issues = getattr(error, "schema_issues", [])
        status, issues = review_status(verdict, artifact, stage)
        state.record(purpose, status, issues)
        if status == "UNKNOWN" and retry_format and purpose not in state.format_retries and state.available(1):
            state.format_retries.add(purpose)
            data["formatFeedback"] = {"failedOutput": verdict.model_dump() if verdict else failed_output,
                "schemaIssues": schema_issues if schema_issues is not None else issues,
                "instruction": "Cover each supplied ID exactly once in the exact schema; do not force passing flags or change artifacts."}
            try:
                verdict = await state.extra(call, purpose, data, model)
            except ValueError:
                verdict = None
            status, issues = review_status(verdict, artifact, stage)
            state.record(purpose, status, issues)
        return verdict, status, issues

    async def repair(stage, original, issues, previous=None):
        allowed, blocks_patch = repair_scope(original, issues, stage, flexible=flexible)
        if independent and stage == "QUESTIONS":
            meanings = {(activity, item) for kind, activity, item in allowed if kind == "meaning"}
            allowed |= {("question", act.id, q.id) for act in original.activities for q in act.questions
                        if any((act.id, mid) in meanings for mid in q.meaningIds)}
        attempt = {"stage": stage, "accepted": False, "attempted": False, "originalHash": h.digest(original)}
        state.attempts.append(attempt)
        unlocalized = [i for i in issues if not i["repairable"]]
        if not allowed or blocks_patch or not state.available(4 if independent and stage == "QUESTIONS" else 2):
            attempt["reason"] = "unlocalized_issue" if not allowed or blocks_patch else "budget_or_deadline"
            return original, previous, None
        if unlocalized:
            attempt["unlocalizedFindings"] = unlocalized
        purpose = "REPAIR_QUESTIONS" if stage == "QUESTIONS" else "REPAIR_TRANSLATIONS"
        targets = [{"itemType": kind, "activityId": activity, "itemId": item,
                    "meaningId" if kind == "meaning" else "questionId" if kind == "question" else "segmentId": item}
                   for kind, activity, item in sorted(allowed)]
        attempt.update(attempted=True, targets=targets, reason="invalid_patch")
        with observation("scenario.repair." + stage.lower(), metadata={"attempt": 1, "originalHash": attempt["originalHash"]}) as span:
            try:
                data = {"dialogue": dialogue.model_dump(),
                    "specification": payload.specification.model_dump(), "failedOutput": original.model_dump(),
                    "issues": repair_feedback(issues) if flexible else issues, "allowedTargets": targets, **context}
                patch_model = (a.PartsQuestionPatch if parts else a.QuestionPatch) if stage == "QUESTIONS" else w.TranslationPatch
                before = items(original, stage)
                while True:
                    patch = candidate = None
                    try:
                        patch = await state.extra(call, purpose, data, patch_model)
                        candidate = merge_patch(original, patch, allowed, stage)
                        attempt["candidateHash"] = h.digest(candidate)
                        errors = structural(candidate, dialogue, payload, stage, merged)
                        feedback = repair_feedback(errors)
                        failed_output = patch.model_dump()
                        retry_allowed, retry_blocked = repair_scope(candidate, errors, stage, flexible=flexible)
                        retryable = not retry_blocked and retry_allowed <= allowed
                    except ValueError as error:
                        # Only a returned draft is correctable; never replay an unknown provider outcome.
                        if patch is None and not hasattr(error, "failed_output"):
                            raise
                        errors = [finding("patch_contract", stage)]
                        failed_output = patch.model_dump() if patch is not None else error.failed_output
                        feedback = getattr(error, "schema_issues", [{"type": str(error), "loc": []}])
                        retryable = True
                    state.record(purpose, "FAIL" if errors else "PASS", errors)
                    if not errors:
                        break
                    attempt["reason"] = "deterministic_validation" if candidate is not None else "invalid_patch"
                    attempt["issues"] = errors
                    if not (flexible and retryable and purpose not in state.format_retries
                            and state.policy.get("maxFormatRetriesPerStage", 0) > 0 and state.available(2)):
                        return original, previous, None
                    # One correction of a rejected draft, reserving a call for independent review.
                    state.format_retries.add(purpose)
                    attempt["rejectedDraftHash"] = h.digest(failed_output)
                    attempt["draftIssues"] = errors
                    data["formatFeedback"] = {"failedOutput": failed_output, "schemaIssues": feedback,
                        "instruction": "Correct this rejected patch against the original failedOutput and same allowedTargets. Return the complete patch; keep every previously correct item unchanged."}
                after = items(candidate, stage)
                changed = {k for k in allowed if h.digest(before[k]) != h.digest(after[k])}
                if not changed:
                    attempt["reason"] = "unchanged_patch"
                    return original, previous, None
                attempt.pop("issues", None)
                repair_context = {"priorFindings": issues, "changedTargets": [t for t in targets if (t["itemType"], t["activityId"], t["itemId"]) in changed],
                    "originalItems": [{"itemType": kind, "activityId": activity, "itemId": item, "value": before[(kind, activity, item)].model_dump()}
                                      for kind, activity, item in sorted(allowed)]}
                verdict, status, semantic = await review(stage, candidate, extra=True, retry_format=False, repair_context=repair_context)
                if status == "UNKNOWN" or any(i["repairable"] and (i["itemType"], i["activityId"], i["itemId"]) in changed for i in semantic):
                    attempt["reason"] = "review_unknown_or_regression"
                    return original, previous, None
                verdict = reconcile(previous, verdict, changed, stage)
                status, semantic = review_status(verdict, candidate, stage)
                state.record("REPAIR_REVIEW", status, semantic)
                attempt.update(accepted=True, reason="accepted" if status == "PASS" else "accepted_with_warnings")
                return candidate, verdict, status
            except ValueError as error:
                if getattr(error, "audit_contract", False):
                    raise
                if flexible:
                    attempt["reason"] = "review_regression" if str(error) == "review regressed" else attempt["reason"]
                    attempt["errorType"] = type(error).__name__
                return original, previous, None
            finally:
                _safe(span, "update", output=trace_content(attempt))

    with observation("scenario.quality_harness", metadata={"harnessPolicyVersion": state.policy["version"],
                     "harnessPolicyHash": h.digest(state.policy)}) as span:
        try:
            source_context = {"specification": payload.specification.model_dump()} if flexible else {}
            questions = await call("QUESTIONS", {"dialogue": dialogue.model_dump(), **source_context, **context},
                                   a.FlexibleQuestions if flexible else a.PartsQuestions if parts else a.Questions)
            errors = structural(questions, dialogue, payload, "QUESTIONS")
            state.record("QUESTIONS", "FAIL" if errors else "PASS", errors)
            question_review = question_status = None
            if errors:
                questions, question_review, question_status = await repair("QUESTIONS", questions, errors)
                if structural(questions, dialogue, payload, "QUESTIONS"):
                    raise w.QualityRejected("invalid assessment after bounded repair")
            merged = a.attach_questions(dialogue, questions, payload.learningContext)
            if question_status is None:
                question_review, question_status, warnings = await review("QUESTIONS", questions)
                if question_status == "FAIL" and warnings:
                    questions, candidate_review, candidate_status = await repair("QUESTIONS", questions, warnings, question_review)
                    if candidate_status is not None:
                        question_review, question_status = candidate_review, candidate_status
                        merged = a.attach_questions(dialogue, questions, payload.learningContext)
            translations = await call("TRANSLATE", {**dialogue.model_dump(), **source_context, **context}, w.Translations)
            errors = structural(translations, dialogue, payload, "TRANSLATE", merged)
            state.record("TRANSLATE", "FAIL" if errors else "PASS", errors)
            translation_review = translation_status = None
            if errors:
                translations, translation_review, translation_status = await repair("TRANSLATE", translations, errors)
                if structural(translations, dialogue, payload, "TRANSLATE", merged):
                    raise w.QualityRejected("invalid translations after bounded repair")
            if translation_status is None:
                translation_review, translation_status, warnings = await review("TRANSLATE", translations)
                if translation_status == "FAIL" and warnings:
                    translations, candidate_review, candidate_status = await repair("TRANSLATE", translations, warnings, translation_review)
                    if candidate_status is not None:
                        translation_review, translation_status = candidate_review, candidate_status
            a.validate_content(merged, payload.specification, payload.profile, payload.learningContext, translations)
            if frozen != {"dialogue": h.digest(dialogue), "specification": h.digest(payload.specification),
                          "learningContext": h.digest(payload.learningContext)}:
                raise w.QualityRejected("immutable harness artifact changed")
            state.artifacts.update(questions=h.digest(questions), translations=h.digest(translations))
            result = {**merged.model_dump(), "translations": translations.model_dump()["segments"],
                      "qualityPassed": question_status == translation_status == "PASS"}
            return result, {"qualityHarness": report(), "reviewAdvisory": {
                "questionsPassed": question_status == "PASS", "reviewPassed": translation_status == "PASS",
                "questionsStatus": question_status, "reviewStatus": translation_status,
                "questions": question_review.model_dump() if question_review else None,
                "review": translation_review.model_dump() if translation_review else None}}
        except w.QualityRejected as error:
            error.quality_harness = report()
            raise
        finally:
            _safe(span, "update", output=trace_content(report()))
