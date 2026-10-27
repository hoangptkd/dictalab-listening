"""Run one local service with an explicit dotenv file and an isolated child environment."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
# Preserve platform/runtime discovery, never inherited application secrets or JVM injection flags.
SYSTEM_ENV = frozenset(('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA', 'PROGRAMFILES',
    'PROGRAMFILES(X86)', 'JAVA_HOME', 'SYSTEMDRIVE', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE'))
CORE_PROVIDER_SECRETS = frozenset(('AI_PROVIDER_DATABASE_URL', 'AI_PROVIDER_CONTAINER_DATABASE_URL',
    'AI_PROVIDER_ENCRYPTION_KEY', 'GEMINI_API_KEY', 'GEMINI_PROVIDERS_JSON', 'CARTESIA_API_KEY',
    'DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'DICTALAB_AI_MODEL_SERVING_TOKEN', 'DICTALAB_MODEL_SERVICE_TOKEN'))


def child_environment(component, path, inherited=None):
    if not path.is_file():
        raise ValueError('Explicit runtime configuration file is missing')
    # interpolate=False prevents ${...} from silently importing credentials from the parent.
    config = dict(dotenv_values(path, interpolate=False))
    for key, value in config.items():
        if value is None or '\n' in value or '\r' in value or '${' in value:
            raise ValueError('Runtime configuration requires literal single-line values')
        upper = key.upper()
        if upper in SYSTEM_ENV or upper in ('PYTHONPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            raise ValueError('Runtime configuration cannot replace platform or injection settings')
        if component == 'core':
            if (upper in CORE_PROVIDER_SECRETS or upper.startswith(('GEMINI_PROVIDER_',
                    'DICTALAB_AI_GEMINI_', 'DICTALAB_AI_CARTESIA_', 'POSTGRES_'))
                    or upper.startswith('DICTALAB_MODEL_') and upper != 'DICTALAB_MODEL_DEMO_ENABLED'
                    or upper.startswith('AI_PROVIDER_') and (upper != 'AI_PROVIDER_CONFIG_SOURCE' or value != 'env')):
                raise ValueError('Core runtime must not receive provider or management credentials')
        elif component in ('ai', 'worker'):
            if not upper.startswith(('DICTALAB_AI_', 'AI_PROVIDER_', 'GEMINI_')) and upper != 'DICTALAB_OPERATIONAL_LIMITS_ENABLED':
                raise ValueError('AI runtime configuration contains another service setting')
        elif component == 'progress':
            if not upper.startswith(('PROGRESS_', 'RABBITMQ_')):
                raise ValueError('Progress runtime configuration contains another service setting')
        elif component == 'model':
            if not upper.startswith('DICTALAB_MODEL_'):
                raise ValueError('Model runtime configuration contains another service setting')
        else:
            raise ValueError('Unknown service')
    if component in ('ai', 'worker'):
        if config.get('DICTALAB_AI_REGISTRY_OWNERSHIP') != 'private' or config.get('AI_PROVIDER_CONFIG_SOURCE') != 'database':
            raise ValueError('Isolated AI runtime requires the private registry')
        jobs = Path(config.get('DICTALAB_AI_JOBS_DATABASE', 'data/ai-jobs.sqlite3'))
        config['DICTALAB_AI_JOBS_DATABASE'] = str(jobs if jobs.is_absolute() else ROOT / jobs)
    inherited = os.environ if inherited is None else inherited
    result = {key: value for key, value in inherited.items() if key.upper() in SYSTEM_ENV}
    return result | config | {'PYTHONUTF8': '1', 'PYTHONIOENCODING': 'utf-8'}


def command(component):
    if component == 'ai':
        return [sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8091'], ROOT / 'services/ai-generation'
    if component == 'worker':
        return [sys.executable, '-m', 'app.worker'], ROOT / 'services/ai-generation'
    if component == 'model':
        python = ROOT / 'data/fine-tuning/.venv-cpu/Scripts/python.exe'
        if not python.is_file():
            raise ValueError('Pinned CPU runtime is missing')
        return [str(python), str(ROOT / 'services/model-serving/server.py')], ROOT
    jar = ROOT / ('target/english-dictation-0.1.0-SNAPSHOT.jar' if component == 'core'
        else 'services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar')
    if not jar.is_file():
        raise ValueError('Packaged service JAR is missing; build it first')
    return ['java', '-Xmx384m' if component == 'core' else '-Xmx256m', '-jar', str(jar)], ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=('core', 'ai', 'worker', 'progress', 'model'))
    parser.add_argument('--env-file', type=Path, required=True)
    args = parser.parse_args()
    try:
        env = child_environment(args.component, args.env_file)
        argv, cwd = command(args.component)
    except (ValueError, OSError):
        raise SystemExit('Service configuration rejected; check its private dotenv file and build. Values suppressed.') from None
    try:
        return subprocess.call(argv, cwd=cwd, env=env)
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    sys.exit(main())
