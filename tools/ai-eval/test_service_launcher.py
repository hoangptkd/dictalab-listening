import importlib.util
from pathlib import Path

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
