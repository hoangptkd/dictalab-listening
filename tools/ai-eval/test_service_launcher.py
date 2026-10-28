import importlib.util
from pathlib import Path
import subprocess
from unittest.mock import Mock, patch

import pytest

spec = importlib.util.spec_from_file_location('run_service', Path(__file__).resolve().parents[2] / 'scripts/run-service.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def test_explicit_configuration_never_inherits_other_service_secrets(tmp_path):
    path = tmp_path / 'ai.env'
    path.write_text("DICTALAB_AI_REGISTRY_OWNERSHIP=private\nAI_PROVIDER_CONFIG_SOURCE=database\n"
        "AI_PROVIDER_DATABASE_URL=private-fixture\nDICTALAB_AI_JOBS_DATABASE=data/jobs.sqlite3\n", encoding='utf-8')
    result = launcher.child_environment('ai', path, {'PATH': 'runtime', 'DB_PASSWORD': 'core-secret',
        'AI_PROVIDER_DATABASE_URL': 'old-shared-fixture', 'JAVA_TOOL_OPTIONS': '-Dsecret=parent',
        'DICTALAB_AI_GEMINI_API_KEY': 'legacy-secret', 'PYTHONPATH': 'injected'})
    assert result['AI_PROVIDER_DATABASE_URL'] == 'private-fixture'
    assert result['PATH'] == 'runtime'
    assert Path(result['DICTALAB_AI_JOBS_DATABASE']).is_absolute()
    assert not {'DB_PASSWORD', 'JAVA_TOOL_OPTIONS', 'DICTALAB_AI_GEMINI_API_KEY', 'PYTHONPATH'} & result.keys()


@pytest.mark.parametrize('component,text', [
    ('core', 'AI_PROVIDER_ENCRYPTION_KEY=secret'),
    ('core', 'CARTESIA_API_KEY=secret'),
    ('core', 'AI_PROVIDER_CONFIG_SOURCE=database'),
    ('core', 'GEMINI_PROVIDER_B_API_KEY=secret'),
    ('core', 'DICTALAB_AI_MODEL_SERVING_TOKEN=secret'),
    ('ai', 'DB_PASSWORD=core-secret'),
    ('ai', 'DICTALAB_AI_REGISTRY_OWNERSHIP=legacy'),
    ('progress', 'DB_PASSWORD=core-secret'),
    ('model', 'AI_PROVIDER_ENCRYPTION_KEY=secret'),
    ('core', 'JWT_SIGNING_KEY=${PARENT_SECRET}'),
    ('core', 'JAVA_TOOL_OPTIONS=-Dsecret=parent'),
])
def test_cross_service_or_ambient_configuration_is_rejected(tmp_path, component, text):
    path = tmp_path / 'service.env'
    path.write_text(text + '\n', encoding='utf-8')
    with pytest.raises(ValueError):
        launcher.child_environment(component, path, {})


def test_core_keeps_its_credentials_and_literal_quoting_only(tmp_path):
    path = tmp_path / 'core.env'
    path.write_text("DB_PASSWORD='a=b'\nAI_PROVIDER_CONFIG_SOURCE=env\n"
        "DICTALAB_AI_TEXT_TRANSPORT=python\nDICTALAB_AI_SERVICE_TOKEN=token\nDICTALAB_MODEL_DEMO_ENABLED=true\n", encoding='utf-8')
    result = launcher.child_environment('core', path, {'AI_PROVIDER_ENCRYPTION_KEY': 'parent-secret'})
    assert result['DB_PASSWORD'] == 'a=b'
    assert result['DICTALAB_AI_TEXT_TRANSPORT'] == 'python'
    assert 'AI_PROVIDER_ENCRYPTION_KEY' not in result


def stack_configs(tmp_path):
    token = 'fixture-internal-token-at-least-32-chars'
    (tmp_path / 'core.env').write_text('DICTALAB_AI_SERVICE_TOKEN=' + token + '\n'
        'PROGRESS_SERVICE_TOKEN=' + token + '\nDICTALAB_PROGRESS_ENABLED=true\n'
        'DICTALAB_AI_TEXT_TRANSPORT=python\nDICTALAB_AI_PLATFORM_BASE_URL=http://127.0.0.1:8091\n'
        'DICTALAB_PROGRESS_BASE_URL=http://127.0.0.1:8092\n', encoding='utf-8')
    (tmp_path / 'ai.env').write_text('AI_PROVIDER_CONFIG_SOURCE=database\nDICTALAB_AI_REGISTRY_OWNERSHIP=private\n'
        'DICTALAB_AI_JOBS_ENABLED=true\nDICTALAB_AI_TEXT_GATEWAY_ENABLED=true\nDICTALAB_AI_SERVICE_TOKEN=' + token + '\n'
        'DICTALAB_AI_MODEL_SERVING_TOKEN=' + token + '\nDICTALAB_AI_MODEL_SERVING_URL=http://127.0.0.1:8093\n', encoding='utf-8')
    (tmp_path / 'progress.env').write_text('PROGRESS_SERVICE_TOKEN=' + token + '\n', encoding='utf-8')
    (tmp_path / 'model.env').write_text('DICTALAB_MODEL_SERVICE_TOKEN=' + token + '\n', encoding='utf-8')


def test_stack_preflights_all_configs_tokens_routes_and_ports_before_starting(tmp_path, monkeypatch):
    stack_configs(tmp_path)
    monkeypatch.setattr(launcher, 'command', lambda name: ([name], tmp_path))
    with patch.object(launcher.socket, 'socket') as sockets:
        plan, urls = launcher.stack_plan(tmp_path, True)
        assert list(plan) == ['progress', 'ai', 'worker', 'core', 'model']
        assert plan['ai'][0] == plan['worker'][0]
        assert urls['model'].endswith('/health/live')
        assert urls['progress'] == 'http://127.0.0.1:8092/actuator/health/readiness'
        assert sockets.return_value.__enter__.return_value.bind.call_count == 4
        (tmp_path / 'model.env').write_text('DICTALAB_MODEL_SERVICE_TOKEN=different\n', encoding='utf-8')
        with pytest.raises(ValueError, match='Model service token mismatch'):
            launcher.stack_plan(tmp_path, True)
        (tmp_path / 'progress.env').write_text('PROGRESS_SERVICE_TOKEN=wrong\n', encoding='utf-8')
        with pytest.raises(ValueError, match='tokens'):
            launcher.stack_plan(tmp_path)


def test_stack_does_not_spawn_if_preflight_fails(tmp_path, monkeypatch):
    stack_configs(tmp_path)
    monkeypatch.setattr(launcher, 'command', lambda name: ([name], tmp_path))
    with patch.object(launcher.socket, 'socket') as sockets, patch.object(launcher.subprocess, 'Popen') as start:
        sockets.return_value.__enter__.return_value.bind.side_effect = OSError('port occupied')
        with pytest.raises(OSError):
            launcher.run_stack(tmp_path, check_only=True)
        start.assert_not_called()


def test_stack_accepts_the_applications_loopback_url_defaults(tmp_path, monkeypatch):
    stack_configs(tmp_path)
    monkeypatch.setattr(launcher, 'command', lambda name: ([name], tmp_path))
    for filename in ('core.env', 'ai.env'):
        path = tmp_path / filename
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if '_URL=' not in line) + '\n', encoding='utf-8')
    with patch.object(launcher.socket, 'socket'):
        _, urls = launcher.stack_plan(tmp_path, True)
        assert urls['core'] == 'http://127.0.0.1:18081/actuator/health'


@pytest.mark.parametrize('filename,line', [
    ('core.env', 'DICTALAB_AI_PLATFORM_BASE_URL=http://example.com'),
    ('core.env', 'DICTALAB_PROGRESS_BASE_URL=http://127.0.0.1:8099'),
    ('core.env', 'DICTALAB_AI_TEXT_TRANSPORT=legacy'),
    ('ai.env', 'DICTALAB_AI_JOBS_ENABLED=false'),
    ('progress.env', 'PROGRESS_PORT=8091'),
    ('progress.env', 'PROGRESS_PORT=65536'),
])
def test_stack_rejects_wrong_routes_disabled_features_and_conflicting_ports(tmp_path, monkeypatch, filename, line):
    stack_configs(tmp_path)
    monkeypatch.setattr(launcher, 'command', lambda name: ([name], tmp_path))
    with (tmp_path / filename).open('a', encoding='utf-8') as file:
        file.write(line + '\n')
    with patch.object(launcher.socket, 'socket'), pytest.raises(ValueError):
        launcher.stack_plan(tmp_path)


@pytest.mark.parametrize('failure', [None, 'start', 'exit', 'health', 'interrupt'])
def test_stack_check_closes_logs_and_cleans_owned_children_on_every_exit(tmp_path, monkeypatch, failure):
    env = {'DICTALAB_AI_SERVICE_TOKEN': 'fixture-only'}
    plan = {'ai': (env, ['fixture-ai'], tmp_path), 'core': (env, ['fixture-core'], tmp_path)}
    monkeypatch.setattr(launcher, 'stack_plan', lambda *_: (plan, {'ai': 'http://127.0.0.1:8091/health/ready'}))
    processes, logs = [], []
    def start(argv, **kwargs):
        if failure == 'start' and processes:
            raise OSError('synthetic startup failure')
        process = Mock()
        process.poll.return_value = 1 if failure == 'exit' else None
        processes.append(process)
        logs.append(kwargs['stdout'])
        assert kwargs['env'] is env and kwargs['stderr'] is kwargs['stdout']
        assert 'fixture-only' not in argv
        return process
    opener = Mock()
    opener.open.return_value.__enter__ = Mock(return_value=Mock(status=200))
    opener.open.return_value.__exit__ = Mock(return_value=False)
    if failure in ('health', 'interrupt'):
        opener.open.side_effect = OSError('unreachable') if failure == 'health' else KeyboardInterrupt()
    monkeypatch.setattr(launcher.subprocess, 'Popen', start)
    monkeypatch.setattr(launcher.subprocess, 'run', Mock())  # No actual ACL mutation on synthetic files.
    monkeypatch.setattr(launcher.urllib.request, 'build_opener', lambda *_: opener)
    stopped = []
    monkeypatch.setattr(launcher, 'stop_stack', lambda children: stopped.extend(children))
    if failure:
        with pytest.raises(KeyboardInterrupt if failure == 'interrupt' else (RuntimeError, OSError)):
            launcher.run_stack(tmp_path, check_only=True, startup_seconds=0)
    else:
        assert launcher.run_stack(tmp_path, check_only=True) == 0
    assert stopped == processes
    assert logs and all(log.closed for log in logs)


def test_stop_stack_only_targets_its_own_live_process_groups(monkeypatch):
    running, finished = Mock(pid=42001), Mock(pid=42002)
    running.poll.return_value = None
    finished.poll.return_value = 0
    run = Mock()
    killpg = Mock()
    monkeypatch.setattr(launcher.subprocess, 'run', run)
    monkeypatch.setattr(launcher.os, 'killpg', killpg, raising=False)
    launcher.stop_stack([running, finished])
    finished.wait.assert_not_called()
    if launcher.os.name == 'nt':
        assert run.call_args.args[0] == ['taskkill', '/PID', '42001', '/T', '/F']
        killpg.assert_not_called()
    else:
        killpg.assert_called_once_with(42001, launcher.signal.SIGTERM)
        run.assert_not_called()
    running.wait.assert_called_once_with(timeout=10)


def test_stop_failure_does_not_skip_other_owned_processes(monkeypatch):
    first, last = Mock(pid=42001), Mock(pid=42002)
    first.poll.return_value = last.poll.return_value = None
    last.wait.side_effect = subprocess.TimeoutExpired('fixture', 10)
    monkeypatch.setattr(launcher.subprocess, 'run', Mock())
    monkeypatch.setattr(launcher.os, 'killpg', Mock(), raising=False)
    with pytest.raises(RuntimeError, match='could not be stopped'):
        launcher.stop_stack([first, last])
    first.wait.assert_called_once_with(timeout=10)
