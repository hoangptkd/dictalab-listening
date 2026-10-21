"""T02 — Typed input contract tests for ConversationContext and ConversationTaskRequest.

Verifies that every allowed field validates correctly and that fields outside the
allowlist are silently dropped before reaching the model prompt.
"""
import pytest
from pydantic import ValidationError

from app.schemas import ConversationContext, ConversationTaskRequest


# ---------------------------------------------------------------------------
# ConversationContext — field coverage and constraints
# ---------------------------------------------------------------------------

def test_context_all_fields_accepted():
    ctx = ConversationContext(
        scenario="hotel",
        difficulty="A2",
        turnNumber=2,
        partnerRole="Receptionist",
        learnerRole="Guest",
        levelProfile={"minWords": 45},
        listeningSkills={"detail": True},
        scenarioJourney="Day 1 at hotel.",
        history=[{"utteranceEn": "Hello"}],
        recentPractice=[{"scenario": "cafe"}],
        practiceFocus="Listen for numbers",
        sessionBlueprint={"slots": 2},
        variationSeed=42,
        previouslyTestedDetails=[{"slot": "time"}],
        weakSkills=["detail", "inference"],
    )
    assert ctx.scenario == "hotel"
    assert ctx.difficulty == "A2"
    assert ctx.turnNumber == 2
    assert ctx.variationSeed == 42
    assert ctx.weakSkills == ["detail", "inference"]


def test_context_unknown_fields_are_dropped():
    """Fields not in the allowlist must be silently dropped (extra='ignore')."""
    ctx = ConversationContext(
        scenario="cafe",
        email="user@example.com",       # must be dropped — credential-like
        password="secret123",            # must be dropped
        authToken="Bearer xyz",          # must be dropped
        unknownExtra="anything",         # must be dropped
    )
    raw = ctx.model_dump(exclude_none=True)
    assert "email" not in raw
    assert "password" not in raw
    assert "authToken" not in raw
    assert "unknownExtra" not in raw
    assert raw["scenario"] == "cafe"


def test_context_difficulty_only_accepts_allowed_levels():
    with pytest.raises(ValidationError):
        ConversationContext(difficulty="C1")
    with pytest.raises(ValidationError):
        ConversationContext(difficulty="a1")   # case-sensitive
    # Valid values must not raise
    ConversationContext(difficulty="A1")
    ConversationContext(difficulty="A2")
    ConversationContext(difficulty="B1")


def test_context_turn_number_bounds():
    with pytest.raises(ValidationError):
        ConversationContext(turnNumber=0)
    with pytest.raises(ValidationError):
        ConversationContext(turnNumber=5)
    ConversationContext(turnNumber=1)
    ConversationContext(turnNumber=4)


def test_context_scenario_max_length():
    ConversationContext(scenario="a" * 200)
    with pytest.raises(ValidationError):
        ConversationContext(scenario="a" * 201)


def test_context_partner_and_learner_role_max_length():
    ConversationContext(partnerRole="a" * 120, learnerRole="a" * 120)
    with pytest.raises(ValidationError):
        ConversationContext(partnerRole="a" * 121)
    with pytest.raises(ValidationError):
        ConversationContext(learnerRole="a" * 121)


def test_context_scenario_journey_max_length():
    ConversationContext(scenarioJourney="x" * 4000)
    with pytest.raises(ValidationError):
        ConversationContext(scenarioJourney="x" * 4001)


def test_context_practice_focus_max_length():
    ConversationContext(practiceFocus="x" * 2000)
    with pytest.raises(ValidationError):
        ConversationContext(practiceFocus="x" * 2001)


def test_context_optional_fields_default_to_none():
    ctx = ConversationContext()
    for field in (
        "scenario", "difficulty", "turnNumber", "partnerRole", "learnerRole",
        "levelProfile", "listeningSkills", "scenarioJourney", "history",
        "recentPractice", "practiceFocus", "sessionBlueprint", "variationSeed",
        "previouslyTestedDetails", "weakSkills",
    ):
        assert getattr(ctx, field) is None, f"{field} should default to None"


# ---------------------------------------------------------------------------
# ConversationTaskRequest — top-level validation
# ---------------------------------------------------------------------------

def _valid_payload(**overrides) -> dict:
    base = dict(
        requestId="req-001",
        idempotencyKey="key-001",
        level="A1",
        turnNumber=1,
        promptVersion="V1",
        instruction="Generate a task.",
        context={"scenario": "hotel"},
    )
    base.update(overrides)
    return base


def test_request_valid_payload_parses():
    req = ConversationTaskRequest(**_valid_payload())
    assert req.level == "A1"
    assert req.turnNumber == 1
    assert req.context.scenario == "hotel"


def test_request_missing_required_fields_raises_400_style():
    with pytest.raises(ValidationError):
        ConversationTaskRequest()


def test_request_level_only_accepts_allowed_values():
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(level="B2"))
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(level=""))


def test_request_turn_number_bounds():
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(turnNumber=0))
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(turnNumber=5))


def test_request_instruction_max_length():
    ConversationTaskRequest(**_valid_payload(instruction="x" * 50000))
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(instruction="x" * 50001))


def test_request_extra_fields_rejected():
    """Top-level request uses extra='forbid'; unknown top-level keys are rejected."""
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(unknownTopLevel="bad"))


def test_request_context_credential_fields_dropped():
    """Credential-like keys inside context are silently dropped before reaching the model."""
    req = ConversationTaskRequest(**_valid_payload(context={
        "scenario": "cafe",
        "email": "user@example.com",
        "accessToken": "Bearer secret",
        "cookie": "session=abc",
    }))
    ctx_dict = req.context.model_dump(exclude_none=True)
    assert "email" not in ctx_dict
    assert "accessToken" not in ctx_dict
    assert "cookie" not in ctx_dict
    assert ctx_dict.get("scenario") == "cafe"


def test_request_id_and_key_must_be_non_empty():
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(requestId=""))
    with pytest.raises(ValidationError):
        ConversationTaskRequest(**_valid_payload(idempotencyKey=""))
