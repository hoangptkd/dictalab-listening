"""Candidate-only blind ledgers and deterministic relation rollup; no persisted schema changes."""
from typing import Annotated, Literal

from pydantic import Field

from . import scenario_assessment as a
from . import scenario_workflow as w
from .coach import StrictModel

ActivityId = Literal["a1", "a2", "a3"]
ClaimId = Annotated[str, Field(pattern=r"^c[1-6]$")]
PartId = Annotated[str, Field(pattern=r"^p[1-4]$")]
PROJECTION_VERSION = "blind-parent-and-prompt-ledgers.v1"
DERIVATION_VERSION = "source-parent-parts-request-answer.v1"


class Claim(StrictModel):
    id: ClaimId
    textEn: str = Field(min_length=1, max_length=300)


class MeaningRow(StrictModel):
    activityId: ActivityId
    meaningId: a.MeaningId
    claims: list[Claim] = Field(min_length=1, max_length=6)


class MeaningAudit(StrictModel):
    meanings: list[MeaningRow] = Field(min_length=9, max_length=18)


class QuestionRow(StrictModel):
    activityId: ActivityId
    questionId: a.QuestionId
    requestedEn: str = Field(min_length=1, max_length=200)
    givenEn: str = Field(max_length=400)


class QuestionAudit(StrictModel):
    questions: list[QuestionRow] = Field(min_length=9, max_length=18)


class ClaimCheck(StrictModel):
    claimId: ClaimId
    sourceSupport: Literal["SUPPORTED", "UNSUPPORTED", "UNCERTAIN"]
    evidence: list[w.Evidence] = Field(max_length=3)


class PartCheck(StrictModel):
    partId: PartId
    claimIds: list[ClaimId] = Field(max_length=6)
    relation: Literal["EQUIVALENT", "MISMATCH", "UNCERTAIN"]


class AuditedMeaningCheck(a.MeaningCheck):
    claimChecks: list[ClaimCheck] = Field(min_length=1, max_length=6)
    partChecks: list[PartCheck] = Field(min_length=1, max_length=4)


class QuestionPartCheck(StrictModel):
    meaningId: a.MeaningId
    partId: PartId
    requestStatus: Literal["REQUESTED", "GIVEN", "NOT_REQUESTED", "UNCERTAIN"]
    answerStatus: Literal["COMPLETE", "INCOMPLETE", "CONTRADICTED", "UNCERTAIN"]


class AuditedQuestionCheck(w.QuestionEvidenceCheck):
    partChecks: list[QuestionPartCheck] = Field(min_length=1, max_length=12)


class AuditedQuestionReview(a.QuestionReview):
    meaningChecks: list[AuditedMeaningCheck] = Field(min_length=9, max_length=18)
    questionChecks: list[AuditedQuestionCheck] = Field(min_length=9, max_length=18)


# Separate schemas keep historical prompt fingerprints unchanged.
class QuestionCoverageCheck(QuestionPartCheck):
    reasonVi: str = Field(min_length=1, max_length=300)
    answerQuotesVi: list[str] = Field(max_length=3)


class QuestionCoverageRow(QuestionRow):
    partChecks: list[QuestionCoverageCheck] = Field(min_length=1, max_length=12)


class QuestionCoverageAudit(StrictModel):
    questions: list[QuestionCoverageRow] = Field(min_length=9, max_length=18)


class FidelityClaimCheck(ClaimCheck):
    reasonVi: str = Field(min_length=1, max_length=300)
    certaintyPreserved: bool
    speechActPreserved: bool


class FidelityMeaningCheck(AuditedMeaningCheck):
    claimChecks: list[FidelityClaimCheck] = Field(min_length=1, max_length=6)
    uncoveredMeaningEn: list[str] = Field(max_length=6)
    bundledPartIds: list[PartId] = Field(max_length=4)


class EvidenceQuestionReview(a.QuestionReview):
    meaningChecks: list[FidelityMeaningCheck] = Field(min_length=9, max_length=18)


class EvidenceFirstCoverageCheck(StrictModel):
    meaningId: a.MeaningId
    partId: PartId
    answerQuotesVi: list[str] = Field(max_length=3)
    reasonVi: str = Field(min_length=1, max_length=300)
    requestStatus: Literal["REQUESTED", "GIVEN", "NOT_REQUESTED", "UNCERTAIN"]
    answerStatus: Literal["COMPLETE", "INCOMPLETE", "CONTRADICTED", "UNCERTAIN"]


class EvidenceFirstCoverageRow(QuestionRow):
    partChecks: list[EvidenceFirstCoverageCheck] = Field(min_length=1, max_length=12)


class EvidenceFirstCoverageAudit(StrictModel):
    questions: list[EvidenceFirstCoverageRow] = Field(min_length=9, max_length=18)


class DirectPartCheck(StrictModel):
    partId: PartId
    evidence: list[w.Evidence] = Field(max_length=3)
    reasonVi: str = Field(min_length=1, max_length=300)
    sourceSupport: Literal["SUPPORTED", "UNSUPPORTED", "UNCERTAIN"]
    parentSupport: Literal["SUPPORTED", "UNSUPPORTED", "UNCERTAIN"]


class DuplicatePartPair(StrictModel):
    leftPartId: PartId
    rightPartId: PartId
    sharedMeaningEn: str = Field(min_length=1, max_length=300)


class DirectMeaningCheck(a.MeaningCheck):
    claimChecks: list[FidelityClaimCheck] = Field(min_length=1, max_length=6)
    partChecks: list[DirectPartCheck] = Field(min_length=1, max_length=4)
    uncoveredMeaningEn: list[str] = Field(max_length=6)
    bundledPartIds: list[PartId] = Field(max_length=4)
    duplicatePartPairs: list[DuplicatePartPair] = Field(max_length=6)


class DirectQuestionReview(a.QuestionReview):
    meaningChecks: list[DirectMeaningCheck] = Field(min_length=9, max_length=18)


def models_for(selected):
    if getattr(selected, "DIRECT_PART_AUDIT", False):
        return MeaningAudit, EvidenceFirstCoverageAudit, DirectQuestionReview
    if getattr(selected, "EVIDENCE_AUDIT", False):
        return MeaningAudit, QuestionCoverageAudit, EvidenceQuestionReview
    return MeaningAudit, QuestionAudit, AuditedQuestionReview


def project_question_coverage(data):
    questions = []
    for activity in data["groundedQuestions"]["activities"]:
        meanings = {m["id"]: m for m in activity["assessment"]["meanings"]}
        for question in activity["questions"]:
            questions.append({"activityId": activity["id"], "questionId": question["id"],
                "promptVi": question["promptVi"], "answerVi": question["answerVi"],
                "requiredParts": [{"meaningId": mid, "partId": p["id"], "meaningEn": p["meaningEn"]}
                    for mid in question["meaningIds"] for p in meanings[mid]["requiredParts"]]})
    return {"questions": questions, **{k: data[k] for k in ("formatFeedback",) if k in data}}


def validate_question_coverage(ledger, artifact):
    rows = validate_questions(ledger, artifact)
    for activity in artifact.activities:
        meanings = {m.id: m for m in activity.assessment.meanings}
        for question in activity.questions:
            key = (activity.id, question.id)
            expected = {(mid, p.id) for mid in question.meaningIds for p in meanings[mid].requiredParts}
            checks = _index(rows[key].partChecks, lambda p: (p.meaningId, p.partId), expected)
            for check in checks.values():
                if not check.reasonVi.strip():
                    raise ValueError("blank question coverage reason")
                quotes = check.answerQuotesVi
                if len(set(quotes)) != len(quotes) or any(not q.strip() or q not in question.answerVi for q in quotes):
                    raise ValueError("unanchored reference answer evidence")
                if check.answerStatus == "COMPLETE" and not quotes:
                    raise ValueError("complete reference coverage lacks answer evidence")
    return rows


def to_evidence_review(comparison, meanings, coverage, artifact, dialogue):
    rows = validate_question_coverage(coverage, artifact)
    _index(comparison.questionChecks, lambda q: (q.activityId, q.questionId), rows.keys())
    targets = {(act.id, m.id): m for act in artifact.activities for m in act.assessment.meanings}
    _index(comparison.meaningChecks, lambda m: (m.activityId, m.meaningId), targets.keys())
    meaning_checks = []
    for row in comparison.meaningChecks:
        parts = {p.id for p in targets[(row.activityId, row.meaningId)].requiredParts}
        if (len(set(row.bundledPartIds)) != len(row.bundledPartIds)
                or not set(row.bundledPartIds) <= parts
                or any(not claim.strip() for claim in row.uncoveredMeaningEn)
                or any(not claim.reasonVi.strip() for claim in row.claimChecks)):
            raise ValueError("invalid source or part audit diagnostics")
        claims = []
        for claim in row.claimChecks:
            value = claim.model_dump(exclude={"reasonVi", "certaintyPreserved", "speechActPreserved"})
            if not claim.certaintyPreserved or not claim.speechActPreserved:
                value["sourceSupport"] = "UNSUPPORTED"
            claims.append(value)
        meaning_checks.append({**row.model_dump(exclude={"uncoveredMeaningEn", "bundledPartIds"}),
                               "claimChecks": claims})
    # Only the scoped audit owns request/answer coverage; final review cannot overwrite it.
    legacy = AuditedQuestionReview.model_validate({**comparison.model_dump(), "meaningChecks": meaning_checks,
        "questionChecks": [{**q.model_dump(), "partChecks": [
            p.model_dump(exclude={"reasonVi", "answerQuotesVi"}) for p in rows[(q.activityId, q.questionId)].partChecks]}
            for q in comparison.questionChecks]})
    requests = QuestionAudit(questions=[QuestionRow(**r.model_dump(exclude={"partChecks"})) for r in coverage.questions])
    normalized = to_review(legacy, meanings, requests, artifact, dialogue)
    mrows = {(r.activityId, r.meaningId): r for r in normalized.meaningChecks}
    qrows = {(r.activityId, r.questionId): r for r in normalized.questionChecks}
    for row in comparison.meaningChecks:
        target = mrows[(row.activityId, row.meaningId)]
        if row.uncoveredMeaningEn:
            target.groundingSupported = False
            target.reasonVi = (target.reasonVi + " | Thiếu ý: " + "; ".join(row.uncoveredMeaningEn))[:300]
            normalized.valid = False
        if row.bundledPartIds:
            target.distinctMeaning = False
            target.reasonVi = (target.reasonVi + " | Gộp ý độc lập: " + ",".join(row.bundledPartIds))[:300]
            normalized.valid = False
    for key, row in rows.items():
        failures = [p for p in row.partChecks if p.requestStatus != "REQUESTED" or p.answerStatus != "COMPLETE"]
        if failures:
            p = failures[0]
            qrows[key].reasonVi = (f"{p.meaningId}/{p.partId} {p.requestStatus}/{p.answerStatus}: " + p.reasonVi)[:300]
    return normalized


def to_direct_review(comparison, meanings, coverage, artifact, dialogue):
    parents = validate_meanings(meanings, artifact)
    rows = validate_question_coverage(coverage, artifact)
    targets = {(act.id, m.id): m for act in artifact.activities for m in act.assessment.meanings}
    _index(comparison.meaningChecks, lambda r: (r.activityId, r.meaningId), targets.keys())
    _index(comparison.questionChecks, lambda r: (r.activityId, r.questionId), rows.keys())
    diagnostics = {"claimChecks", "partChecks", "uncoveredMeaningEn", "bundledPartIds", "duplicatePartPairs"}
    normalized = a.QuestionReview.model_validate({**comparison.model_dump(),
        "meaningChecks": [r.model_dump(exclude=diagnostics) for r in comparison.meaningChecks]})
    mrows = {(r.activityId, r.meaningId): r for r in normalized.meaningChecks}
    source = {act.id: {s.id: s.textEn for s in act.segments} for act in dialogue.activities}

    def lower(row, flag, detail):
        setattr(row, flag, False)
        row.reasonVi = (detail + " | " + row.reasonVi)[:300]

    for row in comparison.meaningChecks:
        key = (row.activityId, row.meaningId)
        target, out = targets[key], mrows[key]
        claims = _index(row.claimChecks, lambda c: c.claimId, {c.id for c in parents[key].claims})
        parts = _index(row.partChecks, lambda p: p.partId, {p.id for p in target.requiredParts})
        for check in [*claims.values(), *parts.values()]:
            quotes = [(e.segmentId, e.quoteEn) for e in check.evidence]
            if (not check.reasonVi.strip() or len(quotes) != len(set(quotes))
                    or any(not quote.strip() or sid not in source[key[0]] or quote not in source[key[0]][sid]
                           for sid, quote in quotes)):
                raise ValueError("unanchored direct audit evidence or blank reason")
            if check.sourceSupport == "SUPPORTED" and not quotes:
                raise ValueError("supported direct audit item lacks evidence")
            supported = check.sourceSupport == "SUPPORTED"
            if isinstance(check, FidelityClaimCheck):
                supported = supported and check.certaintyPreserved and check.speechActPreserved
            if not supported:
                lower(out, "meaningSupported", "Ý không được nguồn hỗ trợ đầy đủ.")
                lower(out, "groundingSupported", "Cần đối chiếu lại bằng chứng nguồn.")
        for part in parts.values():
            if part.parentSupport != "SUPPORTED":
                lower(out, "groundingSupported", f"Phần {part.partId} không khớp ý gốc.")
        if (len(set(row.bundledPartIds)) != len(row.bundledPartIds)
                or not set(row.bundledPartIds) <= parts.keys()
                or any(not text.strip() for text in row.uncoveredMeaningEn)):
            raise ValueError("invalid direct part diagnostics")
        pairs = set()
        for pair in row.duplicatePartPairs:
            ids = frozenset((pair.leftPartId, pair.rightPartId))
            if len(ids) != 2 or not ids <= parts.keys() or ids in pairs or not pair.sharedMeaningEn.strip():
                raise ValueError("invalid duplicate part pair")
            pairs.add(ids)
        # Exact duplicates are checkable without assuming the blind ledger's granularity.
        texts = [" ".join(p.meaningEn.casefold().split()) for p in target.requiredParts]
        if pairs or len(set(texts)) != len(texts):
            lower(out, "distinctMeaning", "Có phần chấm lặp cùng một thông tin.")
        if row.bundledPartIds:
            lower(out, "distinctMeaning", "Gộp ý độc lập: " + ",".join(row.bundledPartIds))
        if row.uncoveredMeaningEn:
            lower(out, "groundingSupported", "Thiếu ý: " + "; ".join(row.uncoveredMeaningEn))
    for out in normalized.questionChecks:
        row = rows[(out.activityId, out.questionId)]
        out.requestedMeaningEn = row.requestedEn
        failures = [p for p in row.partChecks if p.requestStatus != "REQUESTED" or p.answerStatus != "COMPLETE"]
        if failures:
            p = failures[0]
            lower(out, "questionMatchesFact", f"{p.meaningId}/{p.partId} {p.requestStatus}/{p.answerStatus}: {p.reasonVi}")
        if any(p.answerStatus == "CONTRADICTED" for p in row.partChecks):
            lower(out, "answerSupported", "Đáp án mâu thuẫn với phần cần trả lời.")
    normalized.valid = comparison.valid and not normalized.issues and all(value is not False
        for row in [*normalized.meaningChecks, *normalized.questionChecks] for value in row.model_dump().values())
    return normalized


def project(purpose, data):
    # Whitelists are rebuilt even for retries; prior outputs belong only in formatFeedback.
    if purpose == "AUDIT_MEANINGS":
        return {"meanings": [{"activityId": act["id"], "meaningId": m["id"], "meaningEn": m["meaningEn"]}
                for act in data["groundedQuestions"]["activities"] for m in act["assessment"]["meanings"]],
                **{k: data[k] for k in ("formatFeedback",) if k in data}}
    if purpose == "AUDIT_QUESTIONS":
        return {"questions": [{"activityId": act["id"], "questionId": q["id"], "promptVi": q["promptVi"]}
                for act in data["groundedQuestions"]["activities"] for q in act["questions"]],
                **{k: data[k] for k in ("formatFeedback",) if k in data}}
    return data


def _index(rows, key, expected):
    indexed = {key(row): row for row in rows}
    if len(indexed) != len(rows) or indexed.keys() != expected:
        keys = [key(row) for row in rows]
        raise ValueError("audit ID coverage mismatch: "
            f"missing={sorted(set(expected) - indexed.keys())[:6]}; "
            f"unexpected={sorted(indexed.keys() - set(expected))[:6]}; "
            f"duplicates={sorted({k for k in keys if keys.count(k) > 1})[:6]}")
    return indexed


def validate_meanings(ledger, questions):
    expected = {(act.id, m.id) for act in questions.activities for m in act.assessment.meanings}
    rows = _index(ledger.meanings, lambda r: (r.activityId, r.meaningId), expected)
    for row in rows.values():
        texts = [" ".join(c.textEn.casefold().split()) for c in row.claims]
        if [c.id for c in row.claims] != [f"c{i+1}" for i in range(len(row.claims))] or not all(texts) or len(set(texts)) != len(texts):
            raise ValueError("audit claim IDs or text mismatch")
    return rows


def validate_questions(ledger, questions):
    expected = {(act.id, q.id) for act in questions.activities for q in act.questions}
    rows = _index(ledger.questions, lambda r: (r.activityId, r.questionId), expected)
    if any(not r.requestedEn.strip() for r in rows.values()):
        raise ValueError("blank audit request")
    return rows


def to_review(comparison, meanings, questions, artifact, dialogue):
    parents = validate_meanings(meanings, artifact)
    requests = validate_questions(questions, artifact)
    source = {act.id: {s.id: s.textEn for s in act.segments} for act in dialogue.activities}
    targets = {(act.id, m.id): m for act in artifact.activities for m in act.assessment.meanings}
    prompts = {(act.id, q.id): q for act in artifact.activities for q in act.questions}
    checks = _index(comparison.meaningChecks, lambda r: (r.activityId, r.meaningId), targets.keys())
    qchecks = _index(comparison.questionChecks, lambda r: (r.activityId, r.questionId), prompts.keys())
    normalized = a.QuestionReview.model_validate({**comparison.model_dump(),
        "meaningChecks": [{k: v for k, v in r.model_dump().items() if k not in ("claimChecks", "partChecks")} for r in comparison.meaningChecks],
        "questionChecks": [{k: v for k, v in r.model_dump().items() if k != "partChecks"} for r in comparison.questionChecks]})
    mrows = {(r.activityId, r.meaningId): r for r in normalized.meaningChecks}
    qrows = {(r.activityId, r.questionId): r for r in normalized.questionChecks}

    def lower(row, flag, code):
        setattr(row, flag, False)
        row.reasonVi = ("Không đạt đối chiếu độc lập: " + code)[:300]

    for key, row in checks.items():
        target, out = targets[key], mrows[key]
        claims = {c.id for c in parents[key].claims}
        claim_checks = _index(row.claimChecks, lambda c: c.claimId, claims)
        parts = _index(row.partChecks, lambda p: p.partId, {p.id for p in target.requiredParts})
        for claim in claim_checks.values():
            quotes = [(e.segmentId, e.quoteEn) for e in claim.evidence]
            if len(quotes) != len(set(quotes)) or any(not quote.strip() or sid not in source[key[0]] or quote not in source[key[0]][sid] for sid, quote in quotes):
                raise ValueError("unanchored audit evidence")
            if claim.sourceSupport == "SUPPORTED" and not quotes:
                raise ValueError("supported audit claim lacks evidence")
            if claim.sourceSupport != "SUPPORTED":
                for flag in ("meaningSupported", "groundingSupported"):
                    lower(out, flag, f"AUDIT_SOURCE:{key[0]}/{key[1]}/{claim.claimId}:{claim.sourceSupport}")
        coverage = {cid: 0 for cid in claims}
        for part in parts.values():
            if len(set(part.claimIds)) != len(part.claimIds) or not set(part.claimIds) <= claims:
                raise ValueError("audit part claim references mismatch")
            for cid in part.claimIds:
                coverage[cid] += 1
            if not part.claimIds or part.relation != "EQUIVALENT":
                lower(out, "groundingSupported", f"AUDIT_PART:{key[0]}/{key[1]}/{part.partId}:{part.relation}")
            if len(part.claimIds) > 1:
                lower(out, "distinctMeaning", f"AUDIT_BUNDLE:{key[0]}/{key[1]}/{part.partId}")
        for cid, count in coverage.items():
            if count == 0:
                lower(out, "groundingSupported", f"AUDIT_PARENT_GAP:{key[0]}/{key[1]}/{cid}")
            if count > 1:
                lower(out, "distinctMeaning", f"AUDIT_DUPLICATE:{key[0]}/{key[1]}/{cid}")
    for key, row in qchecks.items():
        target, out = prompts[key], qrows[key]
        expected = {(*key, mid, p.id) for mid in target.meaningIds for p in targets[(key[0], mid)].requiredParts}
        parts = _index(row.partChecks, lambda p: (*key, p.meaningId, p.partId), expected)
        out.requestedMeaningEn = requests[key].requestedEn
        for part in parts.values():
            label = f"{key[0]}/{key[1]}/{part.meaningId}/{part.partId}"
            if part.requestStatus != "REQUESTED" or part.answerStatus != "COMPLETE":
                lower(out, "questionMatchesFact", f"AUDIT_REQUEST:{label}:{part.requestStatus}/{part.answerStatus}")
            if part.answerStatus == "CONTRADICTED":
                lower(out, "answerSupported", f"AUDIT_ANSWER:{label}")
    normalized.valid = comparison.valid and not normalized.issues and all(value is not False for row in [*mrows.values(), *qrows.values()] for value in row.model_dump().values())
    return normalized
