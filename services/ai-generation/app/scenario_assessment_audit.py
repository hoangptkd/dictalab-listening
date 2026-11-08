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
        raise ValueError("audit ID coverage mismatch")
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
        expected = {(mid, p.id) for mid in target.meaningIds for p in targets[(key[0], mid)].requiredParts}
        parts = _index(row.partChecks, lambda p: (p.meaningId, p.partId), expected)
        out.requestedMeaningEn = requests[key].requestedEn
        for part in parts.values():
            label = f"{key[0]}/{key[1]}/{part.meaningId}/{part.partId}"
            if part.requestStatus != "REQUESTED" or part.answerStatus != "COMPLETE":
                lower(out, "questionMatchesFact", f"AUDIT_REQUEST:{label}:{part.requestStatus}/{part.answerStatus}")
            if part.answerStatus == "CONTRADICTED":
                lower(out, "answerSupported", f"AUDIT_ANSWER:{label}")
    normalized.valid = comparison.valid and not normalized.issues and all(value is not False for row in [*mrows.values(), *qrows.values()] for value in row.model_dump().values())
    return normalized
