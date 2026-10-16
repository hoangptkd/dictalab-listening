"""Activate a verified Linux release with quiescent backup and application rollback.

Run as root: python3 release.py /home/ubuntu/dictalab-release-<id> <sha256>
Only additive, backwards-compatible migrations may use application-only rollback.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile
import importlib.util

APP = pathlib.Path('/opt/dictalab/app/dictalab.jar')
ENV = pathlib.Path('/etc/dictalab/app.env')
BACKUPS = pathlib.Path('/var/lib/dictalab/backups')
TOOLS = pathlib.Path('/opt/dictalab/tools')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=240)
    if result.returncode:
        raise RuntimeError('Release command failed; command output suppressed')


def health():
    try:
        with urllib.request.urlopen('http://127.0.0.1:18082/actuator/health', timeout=5) as response:
            return response.status == 200 and json.loads(response.read(4096)).get('status') == 'UP'
    except Exception:
        return False


def wait_ready():
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if health():
            return
        time.sleep(3)
    raise RuntimeError('Release readiness deadline exceeded')


def rollback_safe(previous, candidate):
    marker = 'BOOT-INF/classes/com/dictalab/service/PaymentOperations.class'
    def understands_payments(path):
        if not zipfile.is_zipfile(path):
            return False
        with zipfile.ZipFile(path) as archive:
            return marker in archive.namelist()
    if understands_payments(previous) or not understands_payments(candidate):
        return True
    spec = importlib.util.spec_from_file_location('payment_release_database', TOOLS / 'migrate-audio-s3.py')
    storage = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(storage)
    exists = storage.database("SELECT to_regclass('public.ledger_journals') IS NOT NULL;").strip()
    if exists == 'f':
        return True
    return storage.database('SELECT count(*) FROM ledger_journals;').strip() == '0'


def replace(source, destination):
    temporary = destination.with_name(destination.name + '.release-pending')
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError('Pending artifact already exists; inspect before resuming')
    created = False
    try:
        with temporary.open('xb') as output:
            created = True
            with source.open('rb') as input_stream:
                shutil.copyfileobj(input_stream, output)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o640)
        shutil.chown(temporary, user='root', group='dictalab')
        os.replace(temporary, destination)
    finally:
        if created and temporary.exists():
            temporary.unlink()
    descriptor = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def activate(candidate, expected):
    if not re.fullmatch(r'[a-fA-F0-9]{64}', expected) or digest(candidate) != expected.lower():
        raise ValueError('Release checksum mismatch')
    if not health():
        raise RuntimeError('Existing production service is not ready')
    os.umask(0o077)
    backup = pathlib.Path(tempfile.mkdtemp(prefix='release-', dir=BACKUPS))
    shutil.copy2(APP, backup / 'dictalab.jar')
    shutil.copy2(ENV, backup / 'app.env')
    previous = digest(APP)
    stopped = False
    switched = False
    try:
        # A failed/timed-out stop has an uncertain outcome; always attempt to resume service.
        stopped = True
        run(['systemctl', 'stop', 'dictalab'])
        run(['python3', str(TOOLS / 'cloud-backup.py'), '--quiescent'])
        # Recheck after backup; never activate an artifact changed during preparation.
        if digest(candidate) != expected.lower():
            raise ValueError('Candidate changed during backup')
        switched = True
        replace(candidate, APP)
        if digest(APP) != expected.lower():
            raise RuntimeError('Installed artifact checksum mismatch')
        run(['systemctl', 'reset-failed', 'dictalab'])
        run(['systemctl', 'start', 'dictalab'])
        wait_ready()
    except Exception:
        if switched:
            # Stop the new writer before evaluating rollback compatibility or changing its JAR.
            run(['systemctl', 'stop', 'dictalab'])
            try:
                compatible = rollback_safe(backup / 'dictalab.jar', candidate)
            except Exception:
                compatible = False
            if not compatible:
                raise RuntimeError('Release needs forward repair: financial writes exist or could not be ruled out; new artifact retained, service stopped, backup preserved') from None
        try:
            if switched:
                replace(backup / 'dictalab.jar', APP)
        finally:
            if stopped:
                run(['systemctl', 'reset-failed', 'dictalab'])
                run(['systemctl', 'start', 'dictalab'])
                wait_ready()
        if digest(APP) != previous:
            raise RuntimeError('Rollback artifact verification failed') from None
        raise RuntimeError('Release rejected; previous artifact verified and service ready') from None
    return {'artifactSha256': expected.lower(), 'rollbackDirectory': str(backup),
            'databaseRollback': 'not attempted; additive migrations retained'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', type=pathlib.Path)
    parser.add_argument('sha256')
    args = parser.parse_args()
    if os.getuid() != 0:
        raise RuntimeError('Release requires root')
    stage = args.stage.resolve(strict=True)
    if stage.parent != pathlib.Path('/home/ubuntu') or not re.fullmatch(r'dictalab-release-[a-z0-9-]+', stage.name):
        raise ValueError('Unexpected release directory')
    candidate = stage / 'dictalab.jar'
    if candidate.is_symlink() or not candidate.is_file():
        raise ValueError('Candidate must be a regular file')
    print(json.dumps(activate(candidate, args.sha256)))


if __name__ == '__main__':
    main()
