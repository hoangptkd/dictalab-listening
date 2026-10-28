"""Run an isolated local service, or supervise the microservice stack with explicit configs."""
import argparse
from contextlib import ExitStack
import getpass
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
# Preserve platform/runtime discovery, never inherited application secrets or JVM injection flags.
SYSTEM_ENV = frozenset(('PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
    'USERPROFILE', 'HOME', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA', 'PROGRAMFILES',
    'PROGRAMFILES(X86)', 'JAVA_HOME', 'SYSTEMDRIVE', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE'))
CORE_PROVIDER_SECRETS = frozenset(('AI_PROVIDER_DATABASE_URL', 'AI_PROVIDER_CONTAINER_DATABASE_URL',
    'AI_PROVIDER_ENCRYPTION_KEY', 'GEMINI_API_KEY', 'GEMINI_PROVIDERS_JSON', 'CARTESIA_API_KEY',
    'DEEPSEEK_API_KEY', 'OPENAI_API_KEY', 'DICTALAB_AI_MODEL_SERVING_TOKEN', 'DICTALAB_MODEL_SERVICE_TOKEN'))


def validate_config(component, config):
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
    return config


def child_environment(component, path, inherited=None):
    from dotenv import dotenv_values
    if not path.is_file():
        raise ValueError('Explicit runtime configuration file is missing')
    # interpolate=False prevents ${...} from silently importing credentials from the parent.
    config = validate_config(component, dict(dotenv_values(path, interpolate=False)))
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


def stack_plan(directory, with_model=False):
    names = ['progress', 'ai', 'worker', 'core'] + (['model'] if with_model else [])
    plan = {}
    for name in names:
        env = child_environment(name, directory / ('ai.env' if name == 'worker' else name + '.env'))
        argv, cwd = command(name)
        plan[name] = (env, argv, cwd)
    core, ai, progress = (plan[name][0] for name in ('core', 'ai', 'progress'))
    for left, right in ((core.get('DICTALAB_AI_SERVICE_TOKEN'), ai.get('DICTALAB_AI_SERVICE_TOKEN')),
                        (core.get('PROGRESS_SERVICE_TOKEN'), progress.get('PROGRESS_SERVICE_TOKEN'))):
        if not left or left != right or not 32 <= len(left) <= 256 or not all(33 <= ord(c) <= 126 for c in left):
            raise ValueError('Internal service tokens are missing, invalid or inconsistent')
    if (core.get('DICTALAB_PROGRESS_ENABLED') != 'true' or ai.get('DICTALAB_AI_JOBS_ENABLED') != 'true'
            or core.get('DICTALAB_AI_TEXT_TRANSPORT') != 'python' or ai.get('DICTALAB_AI_TEXT_GATEWAY_ENABLED') != 'true'):
        raise ValueError('Stack requires Progress publishing and Python text/jobs routing')
    ports = {'core': int(core.get('PORT', '18081')), 'ai': 8091,
             'progress': int(progress.get('PROGRESS_PORT', '8092'))}
    if with_model:
        model = plan['model'][0]
        ports['model'] = int(model.get('DICTALAB_MODEL_PORT', '8093'))
        token = model.get('DICTALAB_MODEL_SERVICE_TOKEN', '')
        if (ai.get('DICTALAB_AI_MODEL_SERVING_TOKEN') != token or not 32 <= len(token) <= 256
                or not all(33 <= ord(c) <= 126 for c in token)):
            raise ValueError('Model service token mismatch')
        if ai.get('DICTALAB_AI_MODEL_SERVING_URL', 'http://127.0.0.1:8093').rstrip('/') != f"http://127.0.0.1:{ports['model']}":
            raise ValueError('Model URL must match the supervised loopback endpoint')
    if len(set(ports.values())) != len(ports) or any(not 1 <= port <= 65535 for port in ports.values()):
        raise ValueError('Stack ports must be distinct valid ports')
    for key, component, default in (('DICTALAB_AI_PLATFORM_BASE_URL', 'ai', 'http://127.0.0.1:8091'),
                                    ('DICTALAB_PROGRESS_BASE_URL', 'progress', 'http://127.0.0.1:8092')):
        if core.get(key, default).rstrip('/') != f'http://127.0.0.1:{ports[component]}':
            raise ValueError('Core URL must match the supervised loopback endpoint')
    for port in ports.values():
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    paths = {'core': '/actuator/health', 'ai': '/health/ready',
             'progress': '/actuator/health/readiness', 'model': '/health/live'}
    urls = {name: f'http://127.0.0.1:{port}{paths[name]}' for name, port in ports.items()}
    return plan, urls


def stop_stack(children):
    failures = 0
    for process in reversed(children):
        if process.poll() is not None:
            continue
        # Terminate only the process tree/group created by this launcher, including venv shims.
        try:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
            else:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                if os.name != 'nt':
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                else:
                    process.kill()
                process.wait(timeout=5)
        except (OSError, subprocess.SubprocessError):
            failures += 1
    if failures:
        raise RuntimeError('Some owned processes could not be stopped; inspect service logs and processes')


def run_stack(directory, *, with_model=False, check_only=False, startup_seconds=120):
    plan, pending = stack_plan(directory.resolve(), with_model)
    logs = directory.resolve() / 'logs'
    logs.mkdir(parents=True, exist_ok=True, mode=0o700)
    children = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with ExitStack() as handles:
        try:
            for name, (env, argv, cwd) in plan.items():
                fd, path = tempfile.mkstemp(prefix=name + '-', suffix='.log', dir=logs)
                log = handles.enter_context(os.fdopen(fd, 'w', encoding='utf-8'))
                if os.name == 'nt':
                    subprocess.run(['icacls', path, '/inheritance:r', '/grant:r', getpass.getuser()+':F', '*S-1-5-18:F'],
                                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=log, stderr=log,
                    start_new_session=os.name != 'nt',
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                children.append(process)
                print(f'{name}: started; protected log {Path(path).name}', flush=True)
            deadline = time.monotonic() + startup_seconds
            while True:
                for name, process in zip(plan, children):
                    if process.poll() is not None:
                        raise RuntimeError(f'{name}: exited; inspect its protected log')
                for name, url in list(pending.items()):
                    try:
                        with opener.open(url, timeout=1) as response:
                            if response.status == 200:
                                pending.pop(name)
                    except OSError:
                        pass
                if not pending:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Startup deadline exceeded; inspect protected service logs')
                time.sleep(0.25)
            print('Core, AI and Progress health checks passed; worker process is alive.', flush=True)
            if with_model:
                print('Model server is live; weights load on first inference. Model readiness is not asserted.', flush=True)
            if check_only:
                return 0
            print('Stack running. Ctrl+C stops only the processes started here.', flush=True)
            while True:
                for name, process in zip(plan, children):
                    if process.poll() is not None:
                        raise RuntimeError(f'{name}: exited; inspect its protected log')
                time.sleep(0.5)
        finally:
            stop_stack(children)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=('core', 'ai', 'worker', 'progress', 'model', 'stack'))
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--config-dir', type=Path, help='Stack only: directory with protected core/ai/progress/model.env')
    parser.add_argument('--with-model', action='store_true', help='Stack only: start the lazy-loading CPU model server')
    parser.add_argument('--check-only', action='store_true', help='Stack only: check startup, then stop owned processes')
    args = parser.parse_args()
    if args.component == 'stack':
        if args.config_dir is None or args.env_file is not None:
            parser.error('stack requires --config-dir and does not accept --env-file')
        try:
            return run_stack(args.config_dir, with_model=args.with_model, check_only=args.check_only)
        except KeyboardInterrupt:
            return 130
        except Exception:
            raise SystemExit('Stack failed; check protected configs, ports and service logs. Values suppressed.') from None
    if args.env_file is None or args.config_dir is not None or args.with_model or args.check_only:
        parser.error('single service requires --env-file; stack options do not apply')
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
