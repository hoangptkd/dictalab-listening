"""Refactoring preserves all admitted job pins and exposes one editable release."""
import json
from pathlib import Path

import pytest

from app import scenario_assessment as a, scenario_prompts as prompts, scenario_workflow as w


def test_effective_prompt_and_schema_hashes_are_unchanged_for_saved_jobs():
    expected = json.loads((Path(__file__).parent / 'fixtures/scenario-historical-prompt-hashes.json').read_text())
    for name, value in expected.items():
        if name == 'workflow':
            actual = w.prompt_hash()
        elif name == 'personalized':
            actual = w.personalized_prompt_hash()
        elif name.startswith('scenario_prompts_meaning_'):
            actual = a.prompt_hash(prompts.historical_release(name))
        else:
            actual = w.personalized_prompt_hash(prompts.historical_release(name))
        assert actual == value, name
    assert a.scope_candidate is prompts.CURRENT
    assert a.prompt_hash(a.alignment_candidate) == 'ce9b6398767cd1cdc927e0f0e442984373a05d058470e616a7d9a3a758669ab8'
    assert a.prompt_hash(a.prevention_candidate) == 'f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89'
    assert a.prompt_hash(a.scoped_release) == 'b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087'


def test_unknown_or_corrupt_historical_prompt_never_falls_back_to_current(tmp_path, monkeypatch):
    with pytest.raises(ValueError, match='Unknown historical'):
        prompts.historical_release('unknown')
    monkeypatch.setattr(prompts, 'ROOT', tmp_path)
    (tmp_path / 'scenario-prompt-history.json.gz').write_bytes(b'changed')
    prompts._history.cache_clear()
    try:
        with pytest.raises(ValueError, match='artifact mismatch'):
            prompts._history()
    finally:
        prompts._history.cache_clear()
