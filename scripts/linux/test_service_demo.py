"""On-demand lifecycle checks; systemd and dumps are simulated here."""
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('service_demo', Path(__file__).with_name('service-demo.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class DemoTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux', 'Installed Linux command uses a symlink')
    def test_installed_symlink_resolves_the_tools_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            link = Path(directory) / 'dictalab-demo'
            link.symlink_to(Path(demo.__file__).resolve())
            result = subprocess.run([sys.executable, str(link), '--help'], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('start,stop,status,backup', result.stdout)

    def setUp(self):
        self.real_current_release = demo.current_release
        self.events = []
        self.states = {name: 'inactive' for name in demo.release.STOP_ORDER}
        patches = [patch.object(demo.release, 'release_lock', side_effect=nullcontext),
                   patch.object(demo, 'current_release', return_value='a' * 40),
                   patch.object(demo.release, 'configurations', return_value={}),
                   patch.object(demo.release, 'states', side_effect=lambda: dict(self.states)),
                   patch.object(demo.release, 'run', side_effect=lambda c: self.events.append(c) or ''),
                   patch.object(demo.release, 'start', side_effect=lambda n: self.events.append('start-business')),
                   patch.object(demo.release, 'stop_all', side_effect=lambda: self.events.append('stop-business')),
                   patch.object(demo.release.backup, 'service_snapshot',
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
            with patch.object(demo.release.backup, 'CURRENT', current), \
                    patch.object(demo.release.backup, 'SERVICE_BACKUPS', root), \
                    patch.object(demo.release.provision, 'verify_stage'), \
                    patch.object(demo.release.backup, 'digest', return_value='hash'):
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
        demo.operate('status')
        demo.release.backup.service_snapshot.assert_not_called()
        demo.release.configurations.assert_not_called()
        self.assertTrue(all(c[0] in ('systemctl', 'free') for c in self.events))

    def test_start_orders_infrastructure_business_and_session_limit(self):
        self.assertEqual('ready', demo.operate('start')['status'])
        self.assertEqual([['systemctl', 'start', 'redis-server'], ['systemctl', 'start', 'rabbitmq-server'],
                          'start-business', ['systemctl', 'restart', demo.TIMER]], self.events)

    def test_partial_stack_is_rejected_before_any_start(self):
        self.states['dictalab'] = 'active'
        with self.assertRaises(RuntimeError):
            demo.operate('start')
        self.assertEqual([], self.events)

    def test_start_failure_stops_business_and_infrastructure(self):
        with patch.object(demo.release, 'start', side_effect=RuntimeError('unhealthy')), self.assertRaises(RuntimeError):
            demo.operate('start')
        self.assertEqual(['stop-business', ['systemctl', 'stop', 'rabbitmq-server'],
                          ['systemctl', 'stop', 'redis-server']], self.events[-3:])

    def test_stop_backs_up_after_business_stops_then_stops_infrastructure(self):
        self.assertEqual('stopped-and-backed-up', demo.operate('stop')['status'])
        self.assertEqual([['systemctl', 'stop', demo.TIMER], 'stop-business', 'backup',
                          ['systemctl', 'stop', 'rabbitmq-server'], ['systemctl', 'stop', 'redis-server']], self.events)

    def test_failed_backup_still_stops_infrastructure_and_never_reports_success(self):
        with patch.object(demo.release.backup, 'service_snapshot', side_effect=RuntimeError('dump failed')), self.assertRaises(RuntimeError):
            demo.operate('stop')
        self.assertEqual([['systemctl', 'stop', 'rabbitmq-server'], ['systemctl', 'stop', 'redis-server']], self.events[-2:])

    def test_explicit_backup_refuses_active_writers(self):
        self.states['dictalab'] = 'active'
        with self.assertRaises(RuntimeError):
            demo.operate('backup')
        demo.release.backup.service_snapshot.assert_not_called()


if __name__ == '__main__':
    unittest.main()
