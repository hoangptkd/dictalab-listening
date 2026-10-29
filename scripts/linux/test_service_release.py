"""Real staged files/atomic replacement/locks; systemd, backup and health are simulated."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import http.server
import threading
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_service_bundle as fixtures

spec = importlib.util.spec_from_file_location('service_release', Path(__file__).with_name('service-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


@unittest.skipUnless(sys.platform == 'linux' and hasattr(os, 'geteuid') and os.geteuid() == 0,
                     'Coordinated release checks require Linux root')
class ServiceReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir='/opt', prefix='service-release-check-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.root.chmod(0o755)
        self.base = self.root / 'dictalab'
        self.base.mkdir(mode=0o751)
        self.base.chmod(0o751)
        self.units, self.config, self.backups = (self.root / n for n in ('units', 'config', 'backups'))
        for path in (self.units, self.config, self.backups):
            path.mkdir(mode=0o700)
        for name, value in ((release.provision, {'BASE': self.base}),
                             (release.provision.bundle, {'RELEASES': self.base / 'services/releases'}),
                             (release.backup, {'CURRENT': self.base / 'services/current', 'CORE_JAR': self.base / 'app/dictalab.jar',
                                              'SERVICE_ENVS': self.config, 'SERVICE_BACKUPS': self.backups,
                                              'UNITS': self.units, 'AI_STATE': self.root / 'ai-state'}),
                             (release, {'LOCK': self.backups / '.release.lock'})):
            for key, item in value.items():
                context = patch.object(name, key, item)
                context.start()
                self.addCleanup(context.stop)
        fixture = fixtures.ServiceBundleTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        def runtime(path):
            python = path / 'venv/bin/python'
            python.parent.mkdir(parents=True)
            python.symlink_to(Path(sys.executable).resolve())
        self.stage_runtime = runtime
        fixture.create()
        with patch.object(release.provision.bundle, 'runtime_check', side_effect=runtime):
            release.provision.bundle.stage(fixture.output, release.provision.bundle.digest(fixture.output))
        self.previous = self.base / 'services/releases' / fixture.revision
        fixture.output.rename(fixture.output.with_name('old-release.tar.gz'))
        fixture.revision = 'b' * 40
        (fixture.source / 'services/ai-generation/app/main.py').write_bytes(b'app = "new-version"')
        fixture.create()
        self.sha256 = release.provision.bundle.digest(fixture.output)
        with patch.object(release.provision.bundle, 'runtime_check', side_effect=runtime):
            release.provision.bundle.stage(fixture.output, self.sha256)
        self.candidate = self.base / 'services/releases' / fixture.revision
        release.backup.CURRENT.symlink_to(self.previous)
        release.backup.CORE_JAR.parent.mkdir()
        release.backup.CORE_JAR.write_bytes(b'original Core binary')
        for name in release.provision.bundle.UNIT_FILES:
            shutil.copyfile(self.candidate / 'systemd' / name, self.units / name)
        (self.units / 'dictalab.service').write_bytes(b'[Service]\n')
        self.state = {name: 'active' for name in release.STOP_ORDER}
        self.calls = []
        self.real_run = release.run
        self.real_health = release.health
        self.saved = self.backups / 'services-fixture'
        self.original_snapshot = {'backupDirectory': str(self.saved), 'manifestSha256': 'f' * 64,
                                  'acceptance': 'archive-and-sqlite-integrity-only'}
        self.configs = {
            'core': {'DICTALAB_AI_SERVICE_TOKEN': 't' * 32, 'PROGRESS_SERVICE_TOKEN': 'p' * 32,
                     'DICTALAB_PROGRESS_ENABLED': 'true', 'DICTALAB_AI_TEXT_TRANSPORT': 'python'},
            'ai': {'DICTALAB_AI_SERVICE_TOKEN': 't' * 32, 'DICTALAB_AI_REGISTRY_OWNERSHIP': 'private',
                   'AI_PROVIDER_CONFIG_SOURCE': 'database', 'DICTALAB_AI_PRODUCTION_MODE': 'true',
                   'DICTALAB_AI_JOBS_ENABLED': 'true', 'DICTALAB_AI_TEXT_GATEWAY_ENABLED': 'true',
                   'DICTALAB_AI_JOBS_DATABASE': str(release.backup.AI_STATE / 'jobs.sqlite3')},
            'progress': {'PROGRESS_SERVICE_TOKEN': 'p' * 32}}
        for name in ('app.env', 'ai.env', 'progress.env', 'backup.pg_service.conf'):
            (self.config / name).write_bytes(b'protected unchanged configuration')
            (self.config / name).chmod(0o600)
        context = patch.object(release.backup, 'SERVICE_CONFIG', self.config / 'backup.pg_service.conf')
        context.start()
        self.addCleanup(context.stop)
        patches = [(release, 'run', self.run_command), (release.backup, 'run', lambda cmd, env=None: self.run_command(cmd)),
                   (release, 'health', lambda names: True), (release, 'wait_ready', lambda *a, **kw: None),
                   (release.backup, 'service_snapshot', self.snapshot),
                   (release.backup, 'verify_service_snapshot', lambda *a: {}),
                   (release.backup, 'runtime_settings', lambda name: dict(self.configs[name])),
                   (release.backup, 'runtime_database', lambda name: ('localhost', '5432', name, name + '_runtime')),
                   (release.backup, 'service_environment', lambda name: {}),
                   (release.legacy.shutil, 'chown', lambda *a, **kw: None)]
        for target, name, implementation in patches:
            context = patch.object(target, name, side_effect=implementation)
            context.start()
            self.addCleanup(context.stop)

    def run_command(self, command):
        self.calls.append(command)
        if command[:2] == ['systemctl', 'show']:
            name = command[2]
            values = {'LoadState': 'loaded', 'ActiveState': self.state[name],
                      'MainPID': '123' if self.state[name] == 'active' else '0',
                      'FragmentPath': str(self.units / (name + '.service')), 'DropInPaths': '', 'NeedDaemonReload': 'no'}
            return '\n'.join(key + '=' + values[key] for key in command[-1].split('=', 1)[1].split(','))
        if command[:2] == ['systemctl', 'stop']:
            self.state[command[2]] = 'inactive'
        elif command[:2] == ['systemctl', 'start']:
            self.state[command[2]] = 'active'
        elif command[0] == sys.executable:
            return 'True\n'
        return ''

    def snapshot(self):
        self.assertEqual(set(self.state.values()), {'inactive'})
        self.saved.mkdir(mode=0o700)
        shutil.copyfile(release.backup.CORE_JAR, self.saved / 'dictalab.jar')
        return dict(self.original_snapshot)

    def activate(self, **kwargs):
        return release.activate(self.fixture.output, self.sha256, **kwargs)

    def record(self):
        return json.loads(next(self.backups.glob('activation-*/activation.json')).read_text())

    def test_successful_upgrade_switches_code_after_backup_and_keeps_live_state(self):
        result = self.activate()
        self.assertEqual(self.candidate, release.backup.CURRENT.resolve())
        self.assertEqual((self.candidate / 'dictalab.jar').read_bytes(), release.backup.CORE_JAR.read_bytes())
        self.assertEqual('complete', self.record()['phase'])
        self.assertEqual('never attempted', self.record()['stateRestore'])
        self.assertEqual('process-readiness-only-not-production-workflow-acceptance', result['acceptance'])
        self.assertEqual(list(release.STOP_ORDER), [c[2] for c in self.calls if c[:2] == ['systemctl', 'stop']])
        self.assertEqual(list(release.START_ORDER), [c[2] for c in self.calls if c[:2] == ['systemctl', 'start']])

    def test_owner_migration_runs_after_snapshot_before_switch_and_preserves_remote_reads(self):
        self.configs['core']['DICTALAB_PROGRESS_READ_MODE'] = 'remote'
        def migrate(jar, evidence):
            self.assertTrue(release.MAINTENANCE.exists())
            self.assertEqual({'inactive'}, set(self.state.values()))
            self.assertTrue((self.saved / 'dictalab.jar').exists())
            self.assertEqual(self.previous, release.backup.CURRENT.resolve())
            self.assertEqual(self.candidate / 'dictalab.jar', jar)
            return {'coreSchema': 'migrated-and-granted'}
        real_module = release.module
        from types import SimpleNamespace
        with patch.object(release, 'MAINTENANCE', self.root / 'maintenance'), \
                patch.object(release, 'module', side_effect=lambda name, path:
                SimpleNamespace(migrate=migrate) if name == 'service_release_schema' else real_module(name, path)):
            self.activate(rollback_compatible=True, migrate_core=True, maintenance=True)
            self.assertTrue(release.MAINTENANCE.exists())
        self.assertEqual('complete', self.record()['phase'])
        self.assertEqual({'coreSchema': 'migrated-and-granted'}, self.record()['schema'])

    def test_owner_migration_requires_review_and_failure_resumes_baseline_without_switch(self):
        with self.assertRaisesRegex(ValueError, 'reviewed compatible'):
            self.activate(migrate_core=True)
        from types import SimpleNamespace
        def fail(*args):
            raise RuntimeError('owner migration failed')
        with patch.object(release, 'module', return_value=SimpleNamespace(migrate=fail)), \
                self.assertRaisesRegex(RuntimeError, 'Preparation rejected'):
            self.activate(migrate_core=True, rollback_compatible=True)
        self.assertEqual('recovered-before-switch', self.record()['phase'])
        self.assertEqual(self.previous, release.backup.CURRENT.resolve())
        self.assertEqual({'active'}, set(self.state.values()))

    def test_initial_failure_with_review_resumes_only_original_core(self):
        release.backup.CURRENT.unlink()
        release.backup.CURRENT.symlink_to(self.candidate)
        for name in self.state:
            self.state[name] = 'active' if name == 'dictalab' else 'inactive'
        with patch.object(release, 'wait_ready', side_effect=[RuntimeError('new unhealthy'), None]), \
                self.assertRaisesRegex(RuntimeError, 'previous application state resumed'):
            self.activate(initial=True, rollback_compatible=True)
        self.assertEqual(b'original Core binary', release.backup.CORE_JAR.read_bytes())
        self.assertEqual('rolled-back', self.record()['phase'])
        self.assertEqual('active', self.state['dictalab'])
        self.assertEqual({'inactive'}, {s for n, s in self.state.items() if n != 'dictalab'})

    def test_reviewed_upgrade_failure_rolls_back_pointer_and_binary_without_state_restore(self):
        with patch.object(release, 'wait_ready', side_effect=[RuntimeError('new unhealthy'), None, None]), \
                self.assertRaisesRegex(RuntimeError, 'previous application state resumed'):
            self.activate(rollback_compatible=True)
        self.assertEqual(self.previous, release.backup.CURRENT.resolve())
        self.assertEqual(b'original Core binary', release.backup.CORE_JAR.read_bytes())
        self.assertEqual('rolled-back', self.record()['phase'])
        self.assertTrue(all(c[0] == 'systemctl' or c[0] == sys.executable for c in self.calls))

    def test_unreviewed_or_financially_unsafe_rollback_keeps_candidate_stopped(self):
        with patch.object(release, 'wait_ready', side_effect=RuntimeError('new unhealthy')), \
                self.assertRaisesRegex(RuntimeError, 'Activation/rollback failed'):
            self.activate()
        self.assertEqual('forward-repair-required', self.record()['phase'])
        self.assertEqual({'inactive'}, set(self.state.values()))
        self.assertEqual(self.candidate, release.backup.CURRENT.resolve())
        with self.assertRaisesRegex(RuntimeError, 'Unfinished activation'):
            self.activate(initial=True)

    def test_financial_guard_cannot_be_bypassed_by_compatibility_assertion(self):
        with patch.object(release, 'wait_ready', side_effect=RuntimeError('new unhealthy')), \
                patch.object(release, 'financial_rollback_safe', return_value=False) as guard, \
                self.assertRaisesRegex(RuntimeError, 'Activation/rollback failed'):
            self.activate(rollback_compatible=True)
        guard.assert_called_once()
        self.assertEqual(self.candidate, release.backup.CURRENT.resolve())
        self.assertEqual({'inactive'}, set(self.state.values()))

    def test_financial_guard_unknown_outcome_fails_closed(self):
        with patch.object(release, 'wait_ready', side_effect=RuntimeError('new unhealthy')), \
                patch.object(release, 'financial_rollback_safe', side_effect=RuntimeError('database unavailable')), \
                self.assertRaisesRegex(RuntimeError, 'Activation/rollback failed'):
            self.activate(rollback_compatible=True)
        self.assertEqual('forward-repair-required', self.record()['phase'])
        self.assertEqual({'inactive'}, set(self.state.values()))

    def test_real_legacy_financial_helper_runs_without_inherited_injection(self):
        with patch.object(release, 'run', side_effect=self.real_run), \
                patch.dict(os.environ, {'PYTHONPATH': 'private', 'PGSERVICE': 'private', 'LD_PRELOAD': 'private'}):
            self.assertTrue(release.financial_rollback_safe(self.previous / 'dictalab.jar', self.candidate / 'dictalab.jar'))

    def test_backup_failure_resumes_original_without_switch(self):
        with patch.object(release.backup, 'service_snapshot', side_effect=RuntimeError('dump failed')), \
                self.assertRaisesRegex(RuntimeError, 'without switching'):
            self.activate()
        self.assertEqual(self.previous, release.backup.CURRENT.resolve())
        self.assertEqual(b'original Core binary', release.backup.CORE_JAR.read_bytes())
        self.assertEqual('recovered-before-switch', self.record()['phase'])

    def test_uncertain_stop_is_recovered_without_switching(self):
        failed = False
        def uncertain(command):
            nonlocal failed
            result = self.run_command(command)
            if command[:2] == ['systemctl', 'stop'] and not failed:
                failed = True
                raise RuntimeError('timed out after stop')
            return result
        with patch.object(release, 'run', side_effect=uncertain), self.assertRaisesRegex(RuntimeError, 'without switching'):
            self.activate()
        self.assertEqual(self.previous, release.backup.CURRENT.resolve())
        self.assertEqual({'active'}, set(self.state.values()))

    def test_initial_success_starts_all_services_from_prepared_pointer(self):
        release.backup.CURRENT.unlink()
        release.backup.CURRENT.symlink_to(self.candidate)
        self.state = {n: 'active' if n == 'dictalab' else 'inactive' for n in self.state}
        self.activate(initial=True)
        self.assertEqual({'active'}, set(self.state.values()))
        self.assertEqual('complete', self.record()['phase'])

    def test_initial_cold_host_snapshots_before_starting_and_records_inactive_baseline(self):
        release.backup.CURRENT.unlink()
        release.backup.CURRENT.symlink_to(self.candidate)
        self.state = {name: 'inactive' for name in self.state}
        self.activate(initial=True)
        self.assertEqual({'active'}, set(self.state.values()))
        self.assertEqual('complete', self.record()['phase'])
        self.assertTrue(self.saved.is_dir())
        self.assertEqual({'inactive'}, set(self.record()['previousStates'].values()))

    def test_on_demand_configuration_can_resume_verified_remote_reads(self):
        self.configs['core']['DICTALAB_PROGRESS_READ_MODE'] = 'remote'
        release.configurations(require_legacy_reads=False)
        with self.assertRaises(ValueError):
            release.configurations()

    def test_first_start_does_not_reset_a_never_loaded_inactive_unit(self):
        self.state = {name: 'inactive' for name in self.state}
        release.start(list(release.START_ORDER))
        self.assertFalse(any(c[:2] == ['systemctl', 'reset-failed'] for c in self.calls))
        self.assertEqual({'active'}, set(self.state.values()))

    def test_real_health_http_rejects_redirects_and_requires_worker_heartbeat(self):
        worker_up = True
        redirect = False
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(handler):
                if redirect:
                    handler.send_response(302)
                    handler.send_header('Location', '/redirected')
                    handler.end_headers()
                    return
                if handler.path == '/metrics' and handler.headers.get('X-Service-Token') != 't' * 32:
                    handler.send_response(401)
                    handler.end_headers()
                    return
                handler.send_response(200)
                handler.end_headers()
                if handler.path == '/metrics':
                    handler.wfile.write(b'dictalab_ai_worker_up ' + (b'1' if worker_up else b'0') + b'\n')
                else:
                    handler.wfile.write(b'{"status":"ready"}')
            def log_message(*args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f'http://127.0.0.1:{server.server_port}/health/ready'
            with patch.dict(release.URLS, {'dictalab-ai': (url, 'ready')}):
                self.assertTrue(self.real_health(['dictalab-ai', 'dictalab-ai-worker']))
                worker_up = False
                self.assertFalse(self.real_health(['dictalab-ai', 'dictalab-ai-worker']))
                worker_up = True
                self.configs['ai'].pop('DICTALAB_AI_SERVICE_TOKEN')
                self.assertFalse(self.real_health(['dictalab-ai', 'dictalab-ai-worker']))
                redirect = True
                self.assertFalse(self.real_health(['dictalab-ai']))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_checksum_routing_and_unit_drift_rejected_before_stopping(self):
        with self.assertRaises(ValueError):
            release.activate(self.fixture.output, '0' * 64)
        self.configs['core']['DICTALAB_PROGRESS_READ_MODE'] = 'unsupported'
        with self.assertRaises(ValueError):
            self.activate()
        self.configs['core'].pop('DICTALAB_PROGRESS_READ_MODE')
        (self.units / 'dictalab-ai.service').write_bytes(b'changed unit')
        with self.assertRaises(ValueError):
            self.activate()
        self.assertFalse(any(c[:2] == ['systemctl', 'stop'] for c in self.calls))

    def test_real_lock_rejects_concurrent_release(self):
        with release.release_lock(), self.assertRaises(BlockingIOError):
            self.activate()

    def test_stale_loaded_units_rejected_before_stopping(self):
        def stale(command):
            return self.run_command(command).replace('NeedDaemonReload=no', 'NeedDaemonReload=yes')
        with patch.object(release, 'run', side_effect=stale), self.assertRaisesRegex(ValueError, 'stable service states'):
            self.activate()
        self.assertFalse(any(c[:2] == ['systemctl', 'stop'] for c in self.calls))

    def test_configuration_change_after_snapshot_blocks_switch(self):
        original = self.snapshot
        def change():
            result = original()
            (self.config / 'app.env').write_bytes(b'concurrent operator edit')
            return result
        with patch.object(release.backup, 'service_snapshot', side_effect=change), \
                self.assertRaisesRegex(RuntimeError, 'baseline readiness is uncertain'):
            self.activate()
        self.assertEqual(self.previous, release.backup.CURRENT.resolve())
        self.assertEqual('forward-repair-required', self.record()['phase'])


if __name__ == '__main__':
    unittest.main()
