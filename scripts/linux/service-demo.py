#!/opt/dictalab/services/current/venv/bin/python
"""Start/stop the on-demand Aurora 17 stack. Status never opens a DB connection."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('demo_release', Path(__file__).resolve().with_name('service-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
INFRA = ('redis-server', 'rabbitmq-server')
TIMER = 'dictalab-demo-stop.timer'


def current_release():
    current = release.backup.CURRENT.resolve(strict=True)
    manifest = json.loads((current / 'manifest.json').read_text())
    marker = json.loads((current / 'stage-ready.json').read_text())
    release.provision.verify_stage(manifest, marker['bundleSha256'])
    if release.backup.digest(release.backup.CORE_JAR) != manifest['files']['dictalab.jar']['sha256']:
        raise RuntimeError('Complete the coordinated initial activation before starting a demo')
    records = [json.loads(path.read_text()) for path in release.backup.SERVICE_BACKUPS.glob('activation-*/activation.json')]
    if (any(record.get('phase') not in ('complete', 'rolled-back', 'recovered-before-switch') for record in records)
            or not any(record.get('phase') == 'complete' and record.get('candidateRevision') == current.name
                       for record in records)):
        raise RuntimeError('A completed activation receipt is required before starting a demo')
    return manifest['sourceRevision']


def operate(action):
    with release.release_lock():
        if action == 'status':
            return {'services': release.run(['systemctl', 'show', *release.STOP_ORDER,
                        '--property=Id,ActiveState,MainPID,MemoryCurrent,NRestarts']).strip(),
                    'memory': release.run(['free', '-m']).strip(),
                    'autoStop': release.run(['systemctl', 'show', TIMER, '--property=ActiveState,NextElapseUSecMonotonic']).strip()}
        # Match this deployment's Aurora major for portable dump/restore rehearsal.
        release.backup.PG_BIN = Path('/usr/lib/postgresql/17/bin')
        if action == 'start':
            states = release.states()
            revision = current_release()
            release.configurations(require_legacy_reads=False)
            if all(value == 'active' for value in states.values()):
                release.wait_ready(list(release.START_ORDER))
                return {'status': 'already-running', 'revision': revision}
            if any(value != 'inactive' for value in states.values()):
                raise RuntimeError('Partial stack requires stop and inspection before restart')
            try:
                for name in INFRA:
                    release.run(['systemctl', 'start', name])
                release.start(list(release.START_ORDER))
                release.run(['systemctl', 'restart', TIMER])
            except Exception:
                try:
                    release.stop_all()
                finally:
                    for name in reversed(INFRA):
                        release.run(['systemctl', 'stop', name])
                raise
            return {'status': 'ready', 'revision': revision, 'maximumSessionMinutes': 60}
        if action == 'backup' and any(value != 'inactive' for value in release.states().values()):
            raise RuntimeError('Stop the demo before an explicit backup; stop creates a backup automatically')
        if action == 'stop':
            release.run(['systemctl', 'stop', TIMER])
            release.stop_all()
        try:
            snapshot = release.backup.service_snapshot()
        finally:
            if action == 'stop':
                for name in reversed(INFRA):
                    release.run(['systemctl', 'stop', name])
        return {'status': 'stopped-and-backed-up' if action == 'stop' else 'backed-up', 'snapshot': snapshot,
                'aurora': 'idle pause expected after five minutes; verify capacity zero in AWS'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'stop', 'status', 'backup'))
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        parser.error('Run as root on the EC2 host')
    os.umask(0o077)
    try:
        print(json.dumps(operate(args.action)))
    except Exception as error:
        raise SystemExit('DEMO_FAILED: ' + type(error).__name__ + '; inspect protected service logs; values suppressed') from None


if __name__ == '__main__':
    main()
