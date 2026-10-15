"""Filesystem checks use disposable directories; systemd and network are mocked."""
import importlib.util
import pathlib
import os
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', pathlib.Path(__file__).with_name('release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = pathlib.Path(self.directory.name)
        self.app = root / 'app.jar'
        self.app.write_bytes(b'old-artifact')
        self.env = root / 'app.env'
        self.env.write_bytes(b'protected-fixture')
        self.candidate = root / 'candidate.jar'
        self.candidate.write_bytes(b'new-artifact')
        self.backups = root / 'backups'
        self.backups.mkdir()
        for key, value in [('APP', self.app), ('ENV', self.env), ('BACKUPS', self.backups)]:
            context = patch.object(release, key, value)
            context.start()
            self.addCleanup(context.stop)
        self.calls = []

    def replace(self, source, destination):
        # Windows has no POSIX chown/O_DIRECTORY; exercise actual copy/atomic replace separately on Linux.
        destination.write_bytes(source.read_bytes())

    def test_checksum_rejection_does_not_stop_service_or_touch_data(self):
        with patch.object(release, 'run') as run:
            with self.assertRaises(ValueError):
                release.activate(self.candidate, '0' * 64)
            run.assert_not_called()
        self.assertEqual(b'old-artifact', self.app.read_bytes())
        self.assertEqual([], list(self.backups.iterdir()))

    @unittest.skipUnless(os.name == 'posix', 'Directory fsync requires POSIX; run this check on Linux')
    def test_real_atomic_replace_and_failed_copy_cleanup(self):
        with patch.object(release.shutil, 'chown'):
            release.replace(self.candidate, self.app)
        self.assertEqual(b'new-artifact', self.app.read_bytes())
        with patch.object(release.shutil, 'copyfileobj', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                release.replace(self.candidate, self.app)
        self.assertEqual(b'new-artifact', self.app.read_bytes())
        self.assertFalse(self.app.with_name(self.app.name + '.release-pending').exists())

    def test_health_failure_restores_previous_artifact_and_keeps_backup(self):
        with patch.object(release, 'health', return_value=True), \
             patch.object(release, 'run', side_effect=lambda cmd: self.calls.append(cmd)), \
             patch.object(release, 'replace', side_effect=self.replace), \
             patch.object(release, 'wait_ready', side_effect=[RuntimeError('new is unhealthy'), None]):
            with self.assertRaisesRegex(RuntimeError, 'previous artifact verified'):
                release.activate(self.candidate, release.digest(self.candidate))
        self.assertEqual(b'old-artifact', self.app.read_bytes())
        self.assertEqual(2, self.calls.count(['systemctl', 'start', 'dictalab']))
        snapshot = next(self.backups.iterdir())
        self.assertEqual(b'protected-fixture', (snapshot / 'app.env').read_bytes())

    def test_backup_failure_resumes_old_service_without_activating_candidate(self):
        def run(cmd):
            self.calls.append(cmd)
            if '--quiescent' in cmd:
                raise RuntimeError('backup failed')
        with patch.object(release, 'health', return_value=True), patch.object(release, 'run', side_effect=run), \
             patch.object(release, 'wait_ready'):
            with self.assertRaisesRegex(RuntimeError, 'previous artifact verified'):
                release.activate(self.candidate, release.digest(self.candidate))
        self.assertEqual(b'old-artifact', self.app.read_bytes())
        self.assertIn(['systemctl', 'start', 'dictalab'], self.calls)

    def test_uncertain_stop_failure_still_resumes_the_previous_service(self):
        def run(cmd):
            self.calls.append(cmd)
            if cmd == ['systemctl', 'stop', 'dictalab']:
                raise RuntimeError('stop timed out after stopping service')
        with patch.object(release, 'health', return_value=True), patch.object(release, 'run', side_effect=run), \
             patch.object(release, 'wait_ready'):
            with self.assertRaisesRegex(RuntimeError, 'previous artifact verified'):
                release.activate(self.candidate, release.digest(self.candidate))
        self.assertEqual(b'old-artifact', self.app.read_bytes())
        self.assertIn(['systemctl', 'start', 'dictalab'], self.calls)
        self.assertFalse(any('--quiescent' in cmd for cmd in self.calls))

    def test_success_requires_quiescent_backup_before_switch(self):
        def replace(source, destination):
            self.assertTrue(any('--quiescent' in cmd for cmd in self.calls))
            self.replace(source, destination)
        with patch.object(release, 'health', return_value=True), \
             patch.object(release, 'run', side_effect=lambda cmd: self.calls.append(cmd)), \
             patch.object(release, 'replace', side_effect=replace), patch.object(release, 'wait_ready'):
            result = release.activate(self.candidate, release.digest(self.candidate))
        self.assertEqual(b'new-artifact', self.app.read_bytes())
        self.assertTrue(pathlib.Path(result['rollbackDirectory']).is_dir())


if __name__ == '__main__':
    unittest.main()
