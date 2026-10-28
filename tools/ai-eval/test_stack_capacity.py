import importlib.util
from pathlib import Path
from unittest.mock import Mock

import pytest

spec = importlib.util.spec_from_file_location('stack_capacity', Path(__file__).with_name('stack_capacity.py'))
capacity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capacity)


@pytest.mark.parametrize('raw,expected', [('0B', 0), ('1.5KiB', 1536), ('2 MiB', 2097152), ('1GiB', 1073741824)])
def test_docker_memory_units(raw, expected):
    assert capacity.memory_bytes(raw) == expected


@pytest.mark.parametrize('raw', ['unavailable', '-1MiB', 'NaNMiB', '1TiB', ''])
def test_missing_or_invalid_memory_is_never_counted_as_zero(raw):
    with pytest.raises(ValueError):
        capacity.memory_bytes(raw)


def test_peak_uses_simultaneous_complete_samples_and_retains_component_peaks():
    result = capacity.summarize([
        {'memoryBytes': {'core': 100}},  # Incomplete observations cannot prove the combined peak.
        {'memoryBytes': {'core': 20, 'progress': 40}},
        {'memoryBytes': {'core': 30, 'progress': 15}},
    ], ['core', 'progress'])
    assert result == {'completeSamples': 2, 'simultaneousPeakBytes': 60,
                      'componentPeakBytes': {'core': 100, 'progress': 40}}
    with pytest.raises(ValueError, match='No simultaneous sample'):
        capacity.summarize([{'memoryBytes': {'core': 100}}], ['core', 'progress'])


def test_cleanup_rejects_resources_whose_ownership_changed_and_continues(tmp_path, monkeypatch):
    run = capacity.Rehearsal(tmp_path)
    run.containers = {'core': run.prefix + '-core', 'ai': run.prefix + '-ai'}
    calls = []
    def docker(*args, **kwargs):
        calls.append(args)
        if args[0] == 'inspect':
            return 'other-owner' if args[-1].endswith('-ai') else run.prefix
        return ''
    monkeypatch.setattr(run, 'docker', docker)
    monkeypatch.setattr(capacity.subprocess, 'run', Mock(return_value=Mock(stdout=b'', stderr=b'')))
    with pytest.raises(RuntimeError, match='resources remain'):
        run.cleanup()
    assert ('rm', '-f', run.prefix + '-core') in calls
    assert ('rm', '-f', run.prefix + '-ai') not in calls
