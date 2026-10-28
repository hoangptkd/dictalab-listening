"""Quiescent three-service activation with optional reviewed application-only rollback.

Never restores databases, media, ledgers, Redis or RabbitMQ. Production release,
capacity, restore and compatibility gates must pass before invoking this tool.
"""
import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


HERE = Path(__file__).resolve().parent
provision = module('service_release_provision', HERE / 'service-provision.py')
backup = module('service_release_backup', HERE / 'cloud-backup.py')
legacy = module('service_release_legacy', HERE / 'release.py')
launcher = module('service_release_launcher', HERE.parent / 'run-service.py')
LOCK = Path('/var/lib/dictalab-service-backups/.release.lock')
STOP_ORDER = ('dictalab', 'dictalab-ai', 'dictalab-ai-worker', 'dictalab-progress')
START_ORDER = tuple(reversed(STOP_ORDER))
URLS = {'dictalab': ('http://127.0.0.1:18082/actuator/health', 'UP'),
        'dictalab-progress': ('http://127.0.0.1:8092/actuator/health/readiness', 'UP'),
        'dictalab-ai': ('http://127.0.0.1:8091/health/ready', 'ready')}


def run(command):
    result = subprocess.run(command, env=provision.ENV, capture_output=True, text=True, timeout=720)
    if result.returncode:
        raise RuntimeError('Coordinated release command failed; output suppressed')
    return result.stdout


@contextmanager
def release_lock():
    import fcntl
    provision.ancestors(LOCK.parent)
    descriptor = os.open(LOCK, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1:
            raise ValueError('Release lock must be a private root-owned regular file')
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def states():
    result = {}
    for name in STOP_ORDER:
        output = run(['systemctl', 'show', name, '--property=LoadState,ActiveState,MainPID,FragmentPath,DropInPaths,NeedDaemonReload'])
        values = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
        if (set(values) != {'LoadState', 'ActiveState', 'MainPID', 'FragmentPath', 'DropInPaths', 'NeedDaemonReload'}
                or values['LoadState'] != 'loaded' or values['DropInPaths']
                or values['NeedDaemonReload'] != 'no'
                or values['FragmentPath'] != str(backup.UNITS / (name + '.service'))
                or values['ActiveState'] not in ('active', 'inactive')
                or not values['MainPID'].isdigit()
                or (int(values['MainPID']) > 0) != (values['ActiveState'] == 'active')):
            raise ValueError('Expected installed stable service states without overrides')
        result[name] = values['ActiveState']
    return result


def configurations():
    configs = {name: launcher.validate_config(name, backup.runtime_settings(name)) for name in ('core', 'ai', 'progress')}
    core, ai, progress = (configs[name] for name in ('core', 'ai', 'progress'))
    for left, right in ((core.get('DICTALAB_AI_SERVICE_TOKEN'), ai.get('DICTALAB_AI_SERVICE_TOKEN')),
                        (core.get('PROGRESS_SERVICE_TOKEN'), progress.get('PROGRESS_SERVICE_TOKEN'))):
        if not left or left != right or not 32 <= len(left) <= 256 or not all(33 <= ord(c) <= 126 for c in left):
            raise ValueError('Internal service tokens are invalid or inconsistent')
    if (core.get('DICTALAB_AI_TEXT_TRANSPORT') != 'python' or core.get('DICTALAB_PROGRESS_ENABLED') != 'true'
            or core.get('DICTALAB_PROGRESS_READ_MODE', 'legacy') != 'legacy'
            or ai.get('DICTALAB_AI_JOBS_ENABLED') != 'true' or ai.get('DICTALAB_AI_TEXT_GATEWAY_ENABLED') != 'true'
            or ai.get('DICTALAB_AI_PRODUCTION_MODE') != 'true'
            or core.get('DICTALAB_MODEL_DEMO_ENABLED', 'false') != 'false'
            or ai.get('DICTALAB_AI_MODEL_DEMO_ENABLED', 'false') != 'false'
            or ai.get('DICTALAB_AI_REGISTRY_DIR', str(backup.CURRENT / 'artifacts/prompts')) != str(backup.CURRENT / 'artifacts/prompts')
            or ai.get('DICTALAB_AI_JOBS_DATABASE') != str(backup.AI_STATE / 'jobs.sqlite3')
            or core.get('DICTALAB_AI_PLATFORM_BASE_URL', 'http://127.0.0.1:8091').rstrip('/') != 'http://127.0.0.1:8091'
            or core.get('DICTALAB_PROGRESS_BASE_URL', 'http://127.0.0.1:8092').rstrip('/') != 'http://127.0.0.1:8092'):
        raise ValueError('Release requires cloud/VPS routing, private durable AI jobs and legacy dashboard reads')
    if ai.get('DICTALAB_AI_PRIMARY_PROVIDER', 'auto') not in ('auto', 'gemini', 'deepseek', 'openai_compatible'):
        raise ValueError('VPS activation requires cloud inference')
    identities = [backup.runtime_database(name) for name in configs]
    if len({item[:3] for item in identities}) != 3 or len({item[3] for item in identities}) != 3:
        raise ValueError('Three distinct runtime databases and roles are required')
    for name in configs:
        backup.service_environment(name)
    return {str(path): backup.digest(path) for path in (backup.SERVICE_CONFIG,
            *(backup.SERVICE_ENVS / name for name in ('app.env', 'ai.env', 'progress.env')),
            *(backup.UNITS / (name + '.service') for name in STOP_ORDER))}


def health(names):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        for name in names:
            if name not in URLS:
                continue
            url, status = URLS[name]
            with opener.open(url, timeout=5) as response:
                if response.status != 200 or response.url != url or json.loads(response.read(16384)).get('status') != status:
                    return False
        if 'dictalab-ai-worker' in names:
            metrics = URLS['dictalab-ai'][0].removesuffix('/health/ready') + '/metrics'
            with opener.open(metrics, timeout=5) as response:
                if response.status != 200 or response.url != metrics or not re.search(
                        r'^dictalab_ai_worker_up 1\s*$', response.read(512000).decode(), re.MULTILINE):
                    return False
        return True
    except Exception:
        return False


def wait_ready(names, *, fresh_worker=False):
    begin = time.monotonic()
    while time.monotonic() - begin < 180:
        current = states()
        if all(current[name] == 'active' for name in names) and (
                not fresh_worker or time.monotonic() - begin >= 16) and health(names):
            return
        time.sleep(3)
    raise RuntimeError('Service readiness deadline exceeded')


def stop_all():
    errors = []
    for name in STOP_ORDER:
        try:
            run(['systemctl', 'stop', name])
        except Exception:
            errors.append(name)
    backup.services_stopped()
    if errors:
        raise RuntimeError('A stop failed; processes are now verified inactive')


def start(names):
    for name in START_ORDER:
        if name in names:
            run(['systemctl', 'reset-failed', name])
            run(['systemctl', 'start', name])
            if name == 'dictalab-ai':
                wait_ready([n for n in names if n != 'dictalab'], fresh_worker='dictalab-ai-worker' in names)
    wait_ready(names)


def switch_pointer(target):
    current = backup.CURRENT
    temporary = current.with_name('.current-release-pending')
    temporary.symlink_to(target, target_is_directory=True)
    try:
        os.replace(temporary, current)
        descriptor = os.open(current.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def financial_rollback_safe(previous, candidate):
    # Reuse the existing financial-write guard with no inherited PG/Python injection.
    code = ('import importlib.util, pathlib, sys; s=importlib.util.spec_from_file_location("guard",sys.argv[1]); '
            'm=importlib.util.module_from_spec(s); s.loader.exec_module(m); '
            'print(m.rollback_safe(pathlib.Path(sys.argv[2]),pathlib.Path(sys.argv[3])))')
    return run([sys.executable, '-I', '-B', '-c', code, str(HERE / 'release.py'), str(previous), str(candidate)]).strip() == 'True'


def receipt(directory, data, phase):
    data['phase'] = phase
    pending = directory / '.activation-pending.json'
    with pending.open('x', encoding='utf-8') as output:
        json.dump(data, output, sort_keys=True)
        output.flush()
        os.fsync(output.fileno())
    pending.chmod(0o600)
    os.replace(pending, directory / 'activation.json')
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def activate(path, expected, *, initial=False, rollback_compatible=False):
    if sys.platform != 'linux' or os.geteuid() != 0 or sys.version_info < (3, 12):
        raise RuntimeError('Coordinated activation requires Linux root and Python 3.12+')
    with release_lock():
        return activate_locked(path, expected, initial=initial, rollback_compatible=rollback_compatible)


def activate_locked(path, expected, *, initial, rollback_compatible):
    candidate_manifest = provision.bundle.verify_bundle(path, expected)
    candidate = provision.verify_stage(candidate_manifest, expected)
    if not backup.CURRENT.is_symlink():
        raise ValueError('Prepare an explicit initial current link before activation')
    previous = backup.CURRENT.resolve(strict=True)
    provision.ancestors(previous)
    old_manifest = json.loads((previous / 'manifest.json').read_text(encoding='utf-8'))
    old_ready = json.loads((previous / 'stage-ready.json').read_text(encoding='utf-8'))
    if provision.verify_stage(old_manifest, old_ready['bundleSha256']) != previous:
        raise ValueError('Previous release provenance mismatch')
    if initial != (candidate == previous):
        raise ValueError('Initial activation uses the prepared release; upgrades need a different staged release')
    for name in provision.bundle.UNIT_FILES:
        installed = backup.UNITS / name
        provision.protected(installed)
        if provision.bundle.digest(installed) != candidate_manifest['files']['systemd/' + name]['sha256']:
            raise ValueError('Unit changes require separate reviewed installation before activation')
    provision.protected(backup.UNITS / 'dictalab.service')
    provision.protected(backup.CORE_JAR)
    provision.ancestors(backup.CORE_JAR.parent)
    provision.protected(backup.SERVICE_BACKUPS, private=True, directory=True)
    for directory in backup.SERVICE_BACKUPS.glob('activation-*'):
        provision.protected(directory, private=True, directory=True)
        record = directory / 'activation.json'
        if not record.exists():
            raise RuntimeError('Unfinished activation requires inspection before another release')
        provision.protected(record, private=True)
        if json.loads(record.read_text(encoding='utf-8')).get('phase') not in (
                'complete', 'rolled-back', 'recovered-before-switch'):
            raise RuntimeError('Unfinished activation requires inspection before another release')
    before = states()
    required = {name: 'active' if not initial or name == 'dictalab' else 'inactive' for name in STOP_ORDER}
    if before != required:
        raise RuntimeError('Baseline service state/health is not ready for release')
    config_hashes = configurations()
    if not health([n for n in STOP_ORDER if before[n] == 'active']):
        raise RuntimeError('Baseline service state/health is not ready for release')
    baseline_hash = backup.digest(backup.CORE_JAR)
    directory = Path(tempfile.mkdtemp(prefix='activation-', dir=backup.SERVICE_BACKUPS))
    data = {'candidateRevision': candidate.name, 'previousRevision': previous.name, 'bundleSha256': expected,
            'previousCoreSha256': baseline_hash, 'initial': initial, 'previousStates': before,
            'applicationRollbackReviewed': rollback_compatible, 'stateRestore': 'never attempted'}
    receipt(directory, data, 'prepared')
    switched = False
    snapshot = None
    prior_active = [name for name in START_ORDER if before[name] == 'active']
    def unchanged():
        if any(backup.digest(Path(name)) != value for name, value in config_hashes.items()):
            raise RuntimeError('Runtime configuration changed during release')
    try:
        stop_all()
        receipt(directory, data, 'quiescent')
        snapshot = backup.service_snapshot()
        data['snapshot'] = snapshot
        receipt(directory, data, 'backed-up')
        unchanged()
        provision.verify_stage(candidate_manifest, expected)
        if backup.digest(backup.CORE_JAR) != baseline_hash or backup.CURRENT.resolve(strict=True) != previous:
            raise RuntimeError('Baseline changed during release preparation')
        receipt(directory, data, 'switching')
        switched = True  # A failed atomic replacement/fsync may already have switched a file.
        switch_pointer(candidate)
        legacy.replace(candidate / 'dictalab.jar', backup.CORE_JAR)
        start(list(START_ORDER))
        unchanged()
        provision.verify_stage(candidate_manifest, expected)
        if (backup.digest(backup.CORE_JAR) != candidate_manifest['files']['dictalab.jar']['sha256']
                or backup.CURRENT.resolve(strict=True) != candidate):
            raise RuntimeError('Installed Core checksum mismatch')
        receipt(directory, data, 'complete')
    except Exception:
        if switched:
            try:
                stop_all()
                unchanged()
                if not rollback_compatible or not snapshot:
                    raise RuntimeError('Application compatibility has not been reviewed')
                saved = Path(snapshot['backupDirectory'])
                backup.verify_service_snapshot(saved, snapshot['manifestSha256'])
                if backup.digest(saved / 'dictalab.jar') != baseline_hash:
                    raise RuntimeError('Snapshot does not match the previous Core artifact')
                if not financial_rollback_safe(saved / 'dictalab.jar', candidate / 'dictalab.jar'):
                    raise RuntimeError('Financial rollback guard rejected the previous binary')
                provision.verify_stage(old_manifest, old_ready['bundleSha256'])
                legacy.replace(saved / 'dictalab.jar', backup.CORE_JAR)
                switch_pointer(previous)
                start(prior_active)
                if backup.digest(backup.CORE_JAR) != baseline_hash or states() != before:
                    raise RuntimeError('Previous Core checksum mismatch')
                receipt(directory, data, 'rolled-back')
            except Exception:
                # Do not rewind data or hide a failed/uncertain stop behind a success report.
                try:
                    stop_all()
                except Exception:
                    pass
                receipt(directory, data, 'forward-repair-required')
                raise RuntimeError('Activation/rollback failed; inspect retained receipt, service state and snapshot; no state restored') from None
            raise RuntimeError('Activation rejected; previous application state resumed; live data retained') from None
        try:
            unchanged()
            if backup.digest(backup.CORE_JAR) != baseline_hash or backup.CURRENT.resolve(strict=True) != previous:
                raise RuntimeError('Baseline no longer matches')
            start(prior_active)
            if states() != before:
                raise RuntimeError('Original process states were not restored')
            receipt(directory, data, 'recovered-before-switch')
        except Exception:
            receipt(directory, data, 'forward-repair-required')
            raise RuntimeError('Preparation failed and baseline readiness is uncertain; inspect retained receipt') from None
        raise RuntimeError('Preparation rejected; original application resumed without switching') from None
    return {'releaseRevision': candidate.name, 'receipt': str(directory / 'activation.json'),
            'snapshot': snapshot, 'acceptance': 'process-readiness-only-not-production-workflow-acceptance'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--initial', action='store_true')
    parser.add_argument('--rollback-compatible', action='store_true',
                        help='Assert reviewed backward-compatible migrations/contracts/state; financial guard still applies')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        print(json.dumps(activate(args.bundle, args.sha256, initial=args.initial, rollback_compatible=args.rollback_compatible)))
    except Exception as error:
        raise SystemExit('SERVICE_RELEASE_FAILED: ' + type(error).__name__ + '; inspect protected activation receipts and service state; values suppressed') from None


if __name__ == '__main__':
    main()
