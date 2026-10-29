#!/opt/dictalab/services/current/venv/bin/python
"""Control database access and production maintenance. Status never connects to SQL."""
import argparse
import configparser
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location('lifecycle_release', Path(__file__).resolve().with_name('service-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
INFRA = ('redis-server', 'rabbitmq-server')
MAINTENANCE = Path('/etc/caddy/dictalab-maintenance')
OPERATOR_CREDENTIALS = Path('/etc/dictalab/database/backup.aws-credentials')
CLUSTER = 'dictalab-aurora-demo'


def cluster_client():
    import boto3
    release.provision.protected(OPERATOR_CREDENTIALS, private=True)
    config = configparser.ConfigParser(interpolation=None)
    config.read(OPERATOR_CREDENTIALS)
    identity = config['backup']
    return boto3.client('rds', region_name='ap-southeast-1',
                        aws_access_key_id=identity['aws_access_key_id'],
                        aws_secret_access_key=identity['aws_secret_access_key'])


def database_state(client=None):
    return (client or cluster_client()).describe_db_clusters(DBClusterIdentifier=CLUSTER)['DBClusters'][0]['Status']


def database(action):
    client = cluster_client()
    expected = 'available' if action == 'start' else 'stopped'
    deadline = time.monotonic() + 900
    requested = False
    while time.monotonic() < deadline:
        state = database_state(client)
        if state == expected:
            return state
        if not requested and state == ('stopped' if action == 'start' else 'available'):
            getattr(client, action + '_db_cluster')(DBClusterIdentifier=CLUSTER)
            requested = True
        elif state not in ('starting', 'stopping', 'stopped', 'available'):
            raise RuntimeError('Aurora is not in a safe start/stop state')
        time.sleep(10)
    raise RuntimeError('Aurora state deadline exceeded; maintenance retained; inspect AWS status')


def persist(path, present):
    if present:
        with path.open('w', encoding='ascii') as output:
            output.write('1\n')
            output.flush()
            os.fsync(output.fileno())
        path.chmod(0o644)
    else:
        path.unlink(missing_ok=True)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def current_release():
    current = release.backup.CURRENT.resolve(strict=True)
    manifest = json.loads((current / 'manifest.json').read_text())
    marker = json.loads((current / 'stage-ready.json').read_text())
    release.provision.verify_stage(manifest, marker['bundleSha256'])
    if release.backup.digest(release.backup.CORE_JAR) != manifest['files']['dictalab.jar']['sha256']:
        raise RuntimeError('Complete the coordinated initial activation before starting services')
    records = [json.loads(path.read_text()) for path in release.backup.SERVICE_BACKUPS.glob('activation-*/activation.json')]
    if (any(record.get('phase') not in ('complete', 'rolled-back', 'recovered-before-switch') for record in records)
            or not any(record.get('phase') == 'complete' and record.get('candidateRevision') == current.name
                       for record in records)):
        raise RuntimeError('A completed activation receipt is required before starting services')
    return manifest['sourceRevision']


def operate(action):
    if action == 'status':
        return {'services': release.run(['systemctl', 'show', *release.STOP_ORDER,
                    '--property=Id,ActiveState,MainPID,MemoryCurrent,NRestarts']).strip(),
                'memory': release.run(['free', '-m']).strip(),
                'maintenance': MAINTENANCE.exists(), 'resumeOnBoot': not MAINTENANCE.exists(),
                'aurora': database_state()}
    with release.release_lock():
        if action == 'boot':
            if MAINTENANCE.exists():
                return {'status': 'maintenance'}
            action = 'start'
        # Match this deployment's Aurora major for portable dump/restore rehearsal.
        release.backup.PG_BIN = Path('/usr/lib/postgresql/17/bin')
        if action == 'start':
            persist(MAINTENANCE, True)
            states = release.states()
            revision = current_release()
            release.configurations(require_legacy_reads=False)
            if all(value == 'active' for value in states.values()):
                release.wait_ready(list(release.START_ORDER))
                persist(MAINTENANCE, False)
                return {'status': 'already-running', 'revision': revision}
            if any(value != 'inactive' for value in states.values()):
                raise RuntimeError('Partial stack requires stop and inspection before restart')
            try:
                database('start')
                for name in INFRA:
                    release.run(['systemctl', 'start', name])
                release.start(list(release.START_ORDER))
                persist(MAINTENANCE, False)
            except Exception:
                persist(MAINTENANCE, True)
                try:
                    release.stop_all()
                finally:
                    for name in reversed(INFRA):
                        release.run(['systemctl', 'stop', name])
                raise
            return {'status': 'ready', 'revision': revision}
        if action == 'backup' and any(value != 'inactive' for value in release.states().values()):
            raise RuntimeError('Stop services before an explicit backup; stop creates a backup automatically')
        if action == 'stop':
            persist(MAINTENANCE, True)
            release.stop_all()
            if database_state() == 'stopped':
                for name in reversed(INFRA):
                    release.run(['systemctl', 'stop', name])
                return {'status': 'already-stopped', 'aurora': 'stopped'}
        if database_state() != 'available':
            raise RuntimeError('Aurora must be available for a verified backup')
        try:
            snapshot = release.backup.service_snapshot()
        finally:
            if action == 'stop':
                for name in reversed(INFRA):
                    release.run(['systemctl', 'stop', name])
        state = database('stop') if action == 'stop' else database_state()
        return {'status': 'stopped-and-backed-up' if action == 'stop' else 'backed-up',
                'snapshot': snapshot, 'aurora': state}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'stop', 'status', 'backup', 'boot'))
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        parser.error('Run as root on the EC2 host')
    os.umask(0o077)
    try:
        print(json.dumps(operate(args.action)))
    except Exception as error:
        raise SystemExit('SERVICE_OPERATION_FAILED: ' + type(error).__name__ + '; inspect protected service logs; values suppressed') from None


if __name__ == '__main__':
    main()
