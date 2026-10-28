"""Real identities/filesystem in a disposable Docker container; systemd calls simulated.

docker run --rm --network none -e TEST_SERVICE_PROVISION_DISPOSABLE=1 \
  -v "$PWD:/workspace:ro" -w /workspace/scripts/linux dictalab-ai-check \
  python -m unittest -v test_service_provision
"""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_service_bundle as fixtures

spec = importlib.util.spec_from_file_location('service_provision', Path(__file__).with_name('service-provision.py'))
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)


@unittest.skipUnless(sys.platform == 'linux' and hasattr(os, 'geteuid') and os.geteuid() == 0
                     and os.environ.get('TEST_SERVICE_PROVISION_DISPOSABLE') == '1'
                     and Path('/.dockerenv').is_file(), 'Requires explicit disposable Docker root environment')
class ServiceProvisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import grp
        try:
            grp.getgrnam('dictalab')
        except KeyError:
            subprocess.run(['groupadd', '--system', 'dictalab'], check=True, capture_output=True)
        cls.core_gid = grp.getgrnam('dictalab').gr_gid

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir='/opt', prefix='service-provision-check-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.root.chmod(0o755)
        self.base = self.root / 'dictalab'
        self.base.mkdir(mode=0o750)
        self.base.chmod(0o750)
        for name in ('app', 'tools'):
            directory = self.base / name
            directory.mkdir(mode=0o750)
            directory.chmod(0o750)
            os.chown(directory, 0, self.core_gid)
        (self.base / 'app/dictalab.jar').write_bytes(b'original Core artifact')
        self.units = self.root / 'units'
        self.config = self.root / 'config'
        self.units.mkdir()
        self.config.mkdir()
        for name in ('app.env', 'ai.env', 'progress.env'):
            (self.config / name).write_bytes(b'PRIVATE_FIXTURE=kept\n')
            (self.config / name).chmod(0o600)
        self.state = self.root / 'ai-state'
        self.backups = self.root / 'service-backups'
        self.calls = []
        self.real_run = provision.run
        fixture = fixtures.ServiceBundleTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.create()
        self.fixture = fixture
        self.checksum = provision.bundle.digest(fixture.output)
        for module, name, value in ((provision, 'BASE', self.base), (provision, 'UNITS', self.units),
                                    (provision, 'CONFIG', self.config), (provision, 'STATE', self.state),
                                    (provision, 'BACKUPS', self.backups),
                                    (provision.bundle, 'RELEASES', self.base / 'services/releases')):
            item = patch.object(module, name, value)
            item.start()
            self.addCleanup(item.stop)
        def runtime(path):
            python = path / 'venv/bin/python'
            python.parent.mkdir(parents=True)
            python.symlink_to(Path(sys.executable).resolve())
        with patch.object(provision.bundle, 'runtime_check', side_effect=runtime):
            provision.bundle.stage(fixture.output, self.checksum)
        self.release = self.base / 'services/releases' / fixture.revision
        self.initial = {p: p.read_bytes() for p in self.config.iterdir()}
        item = patch.object(provision, 'run', side_effect=self.run_command)
        item.start()
        self.addCleanup(item.stop)

    def run_command(self, command):
        self.calls.append(command)
        if command[:2] == ['systemctl', 'show']:
            if 'DropInPaths' in command[-1]:
                return 'LoadState=not-found\nActiveState=inactive\nMainPID=0\nDropInPaths=\n'
            return 'LoadState=loaded\nActiveState=inactive\nMainPID=0\n'
        if command[:2] == ['systemctl', 'daemon-reload'] or command[0] == 'systemd-analyze':
            return ''
        return self.real_run(command)

    def prepare(self):
        return provision.prepare(self.fixture.output, self.checksum)

    def assert_untouched(self):
        self.assertFalse((self.base / 'services/current').exists())
        self.assertEqual([], list(self.units.iterdir()))
        self.assertEqual(0o750, stat.S_IMODE(self.base.stat().st_mode))
        self.assertFalse(self.state.exists())
        self.assertEqual(self.initial, {p: p.read_bytes() for p in self.config.iterdir()})
        self.assertEqual(b'original Core artifact', (self.base / 'app/dictalab.jar').read_bytes())

    def test_real_locked_identities_and_private_state_without_service_activation(self):
        result = self.prepare()
        self.assertEqual('initial-filesystem-and-inactive-units-only-not-deployment', result['acceptance'])
        self.assertEqual(self.release, (self.base / 'services/current').resolve())
        user = provision.account('dictalab-ai')
        other = provision.account('dictalab-progress')
        self.assertNotEqual(user.pw_uid, other.pw_uid)
        self.assertNotEqual(self.core_gid, user.pw_gid)
        self.assertNotEqual(self.core_gid, other.pw_gid)
        self.assertEqual((user.pw_uid, user.pw_gid, 0o700),
                         (self.state.stat().st_uid, self.state.stat().st_gid, stat.S_IMODE(self.state.stat().st_mode)))
        self.assertEqual((0, 0o700), (self.backups.stat().st_uid, stat.S_IMODE(self.backups.stat().st_mode)))
        for name in provision.bundle.UNIT_FILES:
            target = self.units / name
            self.assertEqual((self.release / 'systemd' / name).read_bytes(), target.read_bytes())
            self.assertEqual((0, 0o644), (target.stat().st_uid, stat.S_IMODE(target.stat().st_mode)))
        self.assertFalse(any(c[0] == 'systemctl' and any(x in c for x in ('start', 'stop', 'enable', 'restart')) for c in self.calls))
        self.assertEqual(self.initial, {p: p.read_bytes() for p in self.config.iterdir()})
        self.assertEqual(b'original Core artifact', (self.base / 'app/dictalab.jar').read_bytes())
        # Real unprivileged access proves traversal and state isolation, not only mode numbers.
        for name in ('dictalab-ai', 'dictalab-progress'):
            self.real_run(['runuser', '-u', name, '--', sys.executable, '-I', '-c',
                'from pathlib import Path; import sys; r, s, c, b = map(Path, sys.argv[1:5]); '
                'assert (r / "ai/app/main.py").is_file(); assert not __import__("os").access(c, 4); '
                'assert not __import__("os").access(b, 4); '
                'assert __import__("os").access(s, 2) == (sys.argv[5] == "dictalab-ai")',
                str(self.base / 'services/current'), str(self.state), str(self.base / 'app/dictalab.jar'),
                str(self.backups), name])
        with self.assertRaisesRegex(ValueError, 'existing current'):
            self.prepare()

    def test_existing_current_and_installed_unit_rejected_before_mutation(self):
        current = self.base / 'services/current'
        current.symlink_to('keep-existing-even-if-dangling')
        with self.assertRaisesRegex(ValueError, 'existing current'):
            self.prepare()
        self.assertEqual(Path('keep-existing-even-if-dangling'), current.readlink())
        current.unlink()
        unit = self.units / provision.bundle.UNIT_FILES[0]
        unit.write_bytes(b'pre-existing unit')
        with self.assertRaisesRegex(ValueError, 'Existing units'):
            self.prepare()
        self.assertEqual(b'pre-existing unit', unit.read_bytes())
        unit.unlink()
        wants = self.units / 'multi-user.target.wants'
        wants.mkdir()
        enabled = wants / provision.bundle.UNIT_FILES[0]
        enabled.symlink_to('/nonexistent/dictalab-ai.service')
        with self.assertRaisesRegex(ValueError, 'Existing units'):
            self.prepare()
        self.assertEqual(Path('/nonexistent/dictalab-ai.service'), enabled.readlink())
        self.assertFalse((self.base / 'services/current').exists())

    def test_payload_marker_and_permissions_rejected_before_mutation(self):
        targets = [(self.release / 'ai/app/main.py', b'changed'),
                   (self.release / 'stage-ready.json', b'{}')]
        for path, content in targets:
            original = path.read_bytes()
            path.write_bytes(content)
            with self.assertRaises(ValueError):
                self.prepare()
            self.assert_untouched()
            path.write_bytes(original)
        config = self.config / 'ai.env'
        config.chmod(0o644)
        with self.assertRaises(ValueError):
            self.prepare()
        self.assert_untouched()

    def test_running_or_overridden_unit_is_rejected_before_mutation(self):
        for result in ('LoadState=loaded\nActiveState=active\nMainPID=23\nDropInPaths=\n',
                       'LoadState=not-found\nActiveState=inactive\nMainPID=0\nDropInPaths=/run/override.conf\n'):
            with patch.object(provision, 'run', return_value=result), self.assertRaises(ValueError):
                self.prepare()
            self.assert_untouched()

    def test_existing_state_is_never_reowned_or_overwritten(self):
        self.state.mkdir(mode=0o700)
        (self.state / 'jobs.sqlite3').write_bytes(b'preserve committed state')
        with self.assertRaisesRegex(ValueError, 'Existing AI state'):
            self.prepare()
        self.assertEqual(b'preserve committed state', (self.state / 'jobs.sqlite3').read_bytes())
        self.assertEqual(0, self.state.stat().st_uid)
        self.assertFalse((self.base / 'services/current').exists())

    def test_valid_existing_ledger_and_accounts_are_preserved(self):
        user = provision.account('dictalab-ai', create=True)
        self.state.mkdir(mode=0o700)
        os.chown(self.state, user.pw_uid, user.pw_gid)
        ledger = self.state / 'jobs.sqlite3'
        ledger.write_bytes(b'committed ledger requiring separate schema verification')
        ledger.chmod(0o600)
        os.chown(ledger, user.pw_uid, user.pw_gid)
        before = ledger.stat()
        self.prepare()
        after = ledger.stat()
        self.assertEqual(b'committed ledger requiring separate schema verification', ledger.read_bytes())
        self.assertEqual((before.st_uid, before.st_gid, before.st_mode, before.st_mtime_ns),
                         (after.st_uid, after.st_gid, after.st_mode, after.st_mtime_ns))
        self.assertFalse(any(c[0] == 'useradd' and c[-1] == 'dictalab-ai' for c in self.calls[1:]))

    def test_command_environment_excludes_inherited_credentials_and_injection(self):
        with patch.dict(os.environ, {'AI_PROVIDER_DATABASE_URL': 'private', 'SYSTEMD_UNIT_PATH': 'injected',
                                     'PYTHONPATH': 'injected', 'LD_PRELOAD': 'injected'}), \
                patch.object(provision.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'ok')) as command:
            self.real_run(['getent', 'shadow', 'dictalab-ai'])
        self.assertEqual(provision.ENV, command.call_args.kwargs['env'])

    def test_unexpected_active_unit_after_reload_never_reports_success(self):
        def unexpected(command):
            if command[:2] == ['systemctl', 'show'] and 'DropInPaths' not in command[-1]:
                return 'LoadState=loaded\nActiveState=active\nMainPID=77\n'
            return self.run_command(command)
        with patch.object(provision, 'run', side_effect=unexpected), self.assertRaisesRegex(RuntimeError, 'remain inactive'):
            self.prepare()

    def test_unsafe_identity_and_venv_links_are_rejected(self):
        provision.account('dictalab-ai', create=True)
        with patch.object(os, 'getgrouplist', return_value=[0]), self.assertRaisesRegex(ValueError, 'isolated locked'):
            self.prepare()
        self.assert_untouched()
        private = self.root / 'unsafe-python'
        private.write_bytes(b'not a trusted executable')
        private.chmod(0o777)
        python = self.release / 'venv/bin/python'
        python.unlink()
        python.symlink_to(private)
        with self.assertRaisesRegex(ValueError, 'Root-owned protected'):
            self.prepare()
        self.assert_untouched()

    def test_nonisolated_runtime_and_unreadable_code_are_rejected(self):
        venv = self.release / 'venv'
        original = self.release / 'venv-original'
        venv.rename(original)
        venv.symlink_to('/usr/local', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'standard protected venv links'):
            self.prepare()
        self.assert_untouched()
        venv.unlink()
        original.rename(venv)
        code = self.release / 'ai/app/main.py'
        code.chmod(0o600)
        with self.assertRaisesRegex(ValueError, 'read staged code'):
            self.prepare()
        self.assert_untouched()

    def test_failed_validation_leaves_partial_install_inactive_without_success(self):
        original = self.run_command
        def fail(command):
            if command[0] == 'systemd-analyze':
                raise RuntimeError('fixture verification failure')
            return original(command)
        with patch.object(provision, 'run', side_effect=fail), self.assertRaisesRegex(RuntimeError, 'fixture'):
            self.prepare()
        self.assertEqual(self.release, (self.base / 'services/current').resolve())
        self.assertEqual(3, len(list(self.units.iterdir())))
        self.assertFalse(any(c[0] == 'systemctl' and any(x in c for x in ('daemon-reload', 'start', 'enable')) for c in self.calls))
        self.assertEqual(self.initial, {p: p.read_bytes() for p in self.config.iterdir()})


if __name__ == '__main__':
    unittest.main()
