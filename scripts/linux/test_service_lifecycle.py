"""Production lifecycle checks; systemd, RDS and dumps are simulated here."""
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('service_lifecycle', Path(__file__).with_name('service-lifecycle.py'))
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class LifecycleTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux', 'Installed Linux command uses a symlink')
    def test_installed_symlink_resolves_the_tools_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            link = Path(directory) / 'dictalab-services'
            link.symlink_to(Path(lifecycle.__file__).resolve())
            result = subprocess.run([sys.executable, str(link), '--help'], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('start,stop,status,backup,boot', result.stdout)

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        context = patch.object(lifecycle, 'MAINTENANCE', Path(directory.name) / 'maintenance')
        context.start()
        self.addCleanup(context.stop)
        lifecycle.MAINTENANCE.touch()
        self.real_current_release = lifecycle.current_release
        self.events = []
        self.states = {name: 'inactive' for name in lifecycle.release.STOP_ORDER}
        patches = [patch.object(lifecycle.release, 'release_lock', side_effect=nullcontext),
                   patch.object(lifecycle, 'current_release', return_value='a' * 40),
                   patch.object(lifecycle.release, 'configurations', return_value={}),
                   patch.object(lifecycle.release, 'states', side_effect=lambda: dict(self.states)),
                   patch.object(lifecycle, 'database_state', return_value='available'),
                   patch.object(lifecycle, 'database', side_effect=lambda action: self.events.append('database-'+action) or ('available' if action=='start' else 'stopped')),
                   patch.object(lifecycle.release, 'run', side_effect=lambda c: self.events.append(c) or ''),
                   patch.object(lifecycle.release, 'start', side_effect=lambda n: self.events.append('start-business')),
                   patch.object(lifecycle.release, 'stop_all', side_effect=lambda: self.events.append('stop-business')),
                   patch.object(lifecycle.release.backup, 'service_snapshot',
                                side_effect=lambda: self.events.append('backup') or {'manifestSha256': 'b' * 64})]
        for context in patches:
            context.start()
            self.addCleanup(context.stop)

    def test_start_requires_completed_activation_for_the_verified_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            current = root / ('a' * 40)
            current.mkdir()
            (current / 'manifest.json').write_text(json.dumps({'files': {'dictalab.jar': {'sha256': 'hash'}},
                                                            'sourceRevision': current.name}))
            (current / 'stage-ready.json').write_text(json.dumps({'bundleSha256': 'bundle'}))
            activation = root / 'activation-check'
            activation.mkdir()
            with patch.object(lifecycle.release.backup, 'CURRENT', current), \
                    patch.object(lifecycle.release.backup, 'SERVICE_BACKUPS', root), \
                    patch.object(lifecycle.release.provision, 'verify_stage'), \
                    patch.object(lifecycle.release.backup, 'digest', return_value='hash'):
                for phase in (None, 'forward-repair-required', 'complete'):
                    if phase:
                        (activation / 'activation.json').write_text(json.dumps(
                            {'phase': phase, 'candidateRevision': current.name}))
                    if phase == 'complete':
                        self.assertEqual(current.name, self.real_current_release())
                    else:
                        with self.assertRaises(RuntimeError):
                            self.real_current_release()

    def test_status_never_opens_databases_or_starts_processes(self):
        lifecycle.operate('status')
        lifecycle.release.backup.service_snapshot.assert_not_called()
        lifecycle.release.configurations.assert_not_called()
        lifecycle.release.release_lock.assert_not_called()
        self.assertTrue(all(c[0] in ('systemctl', 'free') for c in self.events))

    def test_start_orders_infrastructure_then_business_without_a_timer(self):
        self.assertEqual('ready', lifecycle.operate('start')['status'])
        self.assertEqual(['database-start', ['systemctl', 'start', 'redis-server'], ['systemctl', 'start', 'rabbitmq-server'],
                          'start-business'], self.events)
        self.assertFalse(lifecycle.MAINTENANCE.exists())

    def test_boot_in_maintenance_does_not_wake_database(self):
        self.assertEqual('maintenance', lifecycle.operate('boot')['status'])
        self.assertTrue(lifecycle.MAINTENANCE.exists())
        self.assertEqual([], self.events)
        lifecycle.release.configurations.assert_not_called()

    def test_boot_resumes_only_when_operator_previously_enabled_application(self):
        lifecycle.persist(lifecycle.MAINTENANCE, False)
        self.assertEqual('ready', lifecycle.operate('boot')['status'])
        self.assertFalse(lifecycle.MAINTENANCE.exists())

    def test_partial_stack_is_rejected_before_any_start(self):
        self.states['dictalab'] = 'active'
        with self.assertRaises(RuntimeError):
            lifecycle.operate('start')
        self.assertEqual([], self.events)

    def test_start_failure_stops_business_and_infrastructure(self):
        with patch.object(lifecycle.release, 'start', side_effect=RuntimeError('unhealthy')), self.assertRaises(RuntimeError):
            lifecycle.operate('start')
        self.assertEqual(['stop-business', ['systemctl', 'stop', 'rabbitmq-server'],
                          ['systemctl', 'stop', 'redis-server']], self.events[-3:])
        self.assertTrue(lifecycle.MAINTENANCE.exists())

    def test_stop_backs_up_after_business_stops_then_stops_infrastructure(self):
        def snapshot():
            self.assertTrue(lifecycle.MAINTENANCE.exists())
            self.events.append('backup')
            return {}
        lifecycle.release.backup.service_snapshot.side_effect = snapshot
        self.assertEqual('stopped-and-backed-up', lifecycle.operate('stop')['status'])
        self.assertEqual(['stop-business', 'backup',
                          ['systemctl', 'stop', 'rabbitmq-server'], ['systemctl', 'stop', 'redis-server'],
                          'database-stop'], self.events)

    def test_failed_backup_still_stops_infrastructure_and_never_reports_success(self):
        with patch.object(lifecycle.release.backup, 'service_snapshot', side_effect=RuntimeError('dump failed')), self.assertRaises(RuntimeError):
            lifecycle.operate('stop')
        self.assertEqual([['systemctl', 'stop', 'rabbitmq-server'], ['systemctl', 'stop', 'redis-server']], self.events[-2:])
        self.assertTrue(lifecycle.MAINTENANCE.exists())
        lifecycle.database.assert_not_called()

    def test_repeated_stop_does_not_wake_a_stopped_cluster_for_backup(self):
        lifecycle.database_state.return_value = 'stopped'
        self.assertEqual('already-stopped', lifecycle.operate('stop')['status'])
        lifecycle.release.backup.service_snapshot.assert_not_called()
        lifecycle.database.assert_not_called()

    def test_database_start_failure_keeps_maintenance_and_stops_clients(self):
        lifecycle.database.side_effect = RuntimeError('AWS denied start')
        with self.assertRaises(RuntimeError):
            lifecycle.operate('start')
        self.assertTrue(lifecycle.MAINTENANCE.exists())
        lifecycle.release.start.assert_not_called()

    def test_explicit_backup_refuses_active_writers(self):
        self.states['dictalab'] = 'active'
        with self.assertRaises(RuntimeError):
            lifecycle.operate('backup')
        lifecycle.release.backup.service_snapshot.assert_not_called()


class DatabaseControlTest(unittest.TestCase):
    def test_control_waits_for_aws_state_and_only_targets_the_configured_cluster(self):
        from unittest.mock import Mock
        for action, states in (('start', ['stopping', 'stopped', 'starting', 'available']),
                               ('stop', ['starting', 'available', 'stopping', 'stopped'])):
            client = Mock()
            with patch.object(lifecycle, 'cluster_client', return_value=client), \
                    patch.object(lifecycle, 'database_state', side_effect=states), \
                    patch.object(lifecycle.time, 'sleep'):
                self.assertEqual(states[-1], lifecycle.database(action))
                getattr(client, action+'_db_cluster').assert_called_once_with(DBClusterIdentifier=lifecycle.CLUSTER)

    def test_unsafe_state_never_sends_a_mutation(self):
        from unittest.mock import Mock
        client = Mock()
        with patch.object(lifecycle, 'cluster_client', return_value=client), \
                patch.object(lifecycle, 'database_state', return_value='deleting'), self.assertRaises(RuntimeError):
            lifecycle.database('start')
        client.start_db_cluster.assert_not_called()


if __name__ == '__main__':
    unittest.main()
