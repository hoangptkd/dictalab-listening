"""Offline measurements, immutable baseline and candidate content preservation."""
import copy
import json
import time

import httpx
import pytest

from app.cache_observation import request_messages, usage_details
from app.config import Settings
from app.model_gateway import ModelGateway
from app import scenario_assessment as a, scenario_evaluation as e


@pytest.mark.parametrize("cached,status", [(None, "not-reported"), (0, "zero"), (7, "hit"),
    (-1, "invalid"), (21, "invalid"), (True, "invalid"), (1.5, "invalid"), ("7", "invalid"), (2**63, "invalid")])
def test_cache_measurements_distinguish_missing_zero_invalid_and_subset(cached, status):
    usage = dict(prompt_tokens=20, completion_tokens=9, completion_tokens_details=dict(reasoning_tokens=4))
    if cached is not None:
        usage["prompt_tokens_details"] = dict(cached_tokens=cached)
    result = usage_details(dict(usage=usage, model="gemini-3.5-flash-lite"))
    assert result["cache_usage_status"] == status
    assert result["output_tokens"] == 9 and result["reasoning_tokens"] == 4
    if status in ("hit", "zero"):
        assert result["cached_input_tokens"] + result["uncached_input_tokens"] == 20
    else:
        assert "cached_input_tokens" not in result and "uncached_input_tokens" not in result


def test_malformed_usage_and_reasoning_never_fabricate_measurements():
    assert usage_details(dict(usage=None)) == dict(cache_usage_status="not-reported")
    result = usage_details(dict(usage=dict(prompt_tokens=True, completion_tokens=-1,
        prompt_tokens_details=dict(cached_tokens=0), completion_tokens_details=dict(reasoning_tokens=3))))
    assert result == dict(cache_usage_status="invalid")


async def test_gateway_preserves_raw_cache_usage_and_hashes_on_parse_error():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={
        "model": "gemini-3.5-flash-lite", "choices": [{"message": {"content": "invalid"}}],
        "usage": {"prompt_tokens": 20, "completion_tokens": 5, "prompt_tokens_details": {"cached_tokens": 12}}}))) as client:
        gateway = ModelGateway(Settings(primary_provider="gemini", gemini_api_key="private-test-key"), client)
        gateway.observed_attempts = []
        with pytest.raises(Exception, match="invalid_json"):
            await gateway.complete([dict(role="system", content="private-test-prompt")], {}, time.monotonic()+5, "test")
    attempt = gateway.observed_attempts[0]
    assert attempt["cached_input_tokens"] == 12 and attempt["uncached_input_tokens"] == 8
    assert attempt["response_model"] == "gemini-3.5-flash-lite"
    assert all(len(attempt[k]) == 64 for k in ("wireRequestHash", "prefixHash", "systemHash", "schemaHash"))
    assert "private-test" not in json.dumps(attempt)


def test_candidate_preserves_all_nine_instructions_examples_and_actual_data():
    data = dict(inputVi="Situation", verificationContext={"contractErrors": ["quote mismatch"]},
                dialogue={"activities": ["a2", "a1"]}, learningContext={"profile": "a1"})
    original = copy.deepcopy(data)
    for instruction in a.sparse_candidate.INSTRUCTIONS.values():
        baseline = request_messages(instruction, data)
        assert baseline == [dict(role="system", content=instruction), dict(role="user", content=json.dumps(data, ensure_ascii=False))]
        candidate = request_messages(instruction, data, "implicit.v1")
        assert json.loads(candidate[-1]["content"]) == data
        assert list(json.loads(candidate[-1]["content"]))[0] == "learningContext"
        if "\nEXAMPLES\n" in instruction:
            before, rest = instruction.split("\nEXAMPLES\n", 1)
            examples, after = rest.split("\nINPUT\n", 1)
            assert candidate[0]["content"] == before + "\nINPUT\n" + after
            assert candidate[1] == dict(role="user", content="<reference_examples>\n"+examples+"\n</reference_examples>")
    assert data == original
    assert a.prompt_hash(a.sparse_candidate) == "5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b"


def test_candidate_preserves_dynamic_examples_without_reordering_or_selection():
    instruction = 'Rules\n<reference_examples>\n[{"id":"b"},{"id":"a"}]\n</reference_examples>'
    messages = request_messages(instruction, {}, "implicit.v1")
    assert messages[0]["content"] == "Rules"
    assert messages[1]["content"] == instruction.split("Rules\n", 1)[1]
    with pytest.raises(ValueError):
        request_messages(instruction, {}, "unknown")


def test_nested_provider_source_participates_in_snapshot_hash(monkeypatch, tmp_path):
    module = tmp_path / "app"
    (module / "providers").mkdir(parents=True)
    (module / "scenario_evaluation.py").write_text("fixture", encoding="utf-8")
    provider = module / "providers/gemini.py"
    provider.write_text("before", encoding="utf-8")
    monkeypatch.setattr(e, "__file__", str(module / "scenario_evaluation.py"))
    before = e.build_hash()
    provider.write_text("after", encoding="utf-8")
    assert e.build_hash() != before
