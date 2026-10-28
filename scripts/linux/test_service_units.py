"""Validate the real unit files with systemd, using a disposable filesystem root."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

UNITS = ('dictalab-ai.service', 'dictalab-ai-worker.service', 'dictalab-progress.service',
         'dictalab-production.service')


@unittest.skipUnless(shutil.which('systemd-analyze'), 'Requires Linux systemd-analyze')
class ServiceUnitTest(unittest.TestCase):
    def test_systemd_accepts_units_and_dependency_graph(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            units = root / 'etc/systemd/system'
            units.mkdir(parents=True)
            for name in UNITS:
                shutil.copyfile(Path(__file__).with_name(name), units / name)
            for name in ('sysinit', 'basic', 'shutdown', 'multi-user', 'network-online'):
                (units / (name + '.target')).write_text('[Unit]\nDescription=Disposable target\n', encoding='utf-8')
            for executable in ('usr/bin/java', 'opt/dictalab/services/current/venv/bin/python', 'usr/local/sbin/dictalab-services'):
                path = root / executable
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'#!/bin/sh\nexit 0\n')
                path.chmod(0o755)
            result = subprocess.run(['systemd-analyze', 'verify', '--man=no', '--generators=no',
                                     '--root=' + str(root), *[str(units / name) for name in UNITS]],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
