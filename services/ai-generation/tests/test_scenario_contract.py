"""The same raw JSON cases run in Java. No network, paid provider or model is used."""
import hashlib
import json
from pathlib import Path

import pytest

from app.scenario_contract import parse_goal, validate_target

ROOT = Path(__file__).resolve().parents[3]
PROGRAMS = json.loads((ROOT / "src/main/resources/scenario/programs-v1.json").read_text(encoding="utf-8"))
CASES = json.loads((ROOT / "src/test/resources/scenario/goal-contract.json").read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["name"])
def test_goal_boundary_matches_java(case):
    accepted = False
    try:
        goal = parse_goal(case["raw"].encode("utf-8"))
        definition = next((p for p in PROGRAMS if (p["code"], p["version"]) == (goal.programCode, goal.programVersion)), None)
        if definition is None:
            raise ValueError("Unknown program")
        validate_target(goal, definition)
        accepted = True
    except ValueError:
        pass
    assert accepted == case["valid"]


def test_definition_hashes_use_cross_language_canonical_v1():
    for program in PROGRAMS:
        content = {k: v for k, v in program.items() if k != "definitionHash"}
        raw = json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        assert hashlib.sha256(raw).hexdigest() == program["definitionHash"]
