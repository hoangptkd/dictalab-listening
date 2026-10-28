"""Run with python -m unittest discover -s ios/scripts -p 'test_*.py'."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ProjectCheckTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.project = Path(directory.name) / 'ios'
        shutil.copytree(ROOT, self.project, ignore=shutil.ignore_patterns('build', '__pycache__'))

    def check(self):
        return subprocess.run([sys.executable, str(self.project / 'scripts/check-project.py')],
                              capture_output=True, text=True, encoding='utf-8')

    def test_current_project_passes(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_source_not_compiled_by_app_target(self):
        path = self.project / 'DictaLab.xcodeproj/project.pbxproj'
        source = path.read_text(encoding='utf-8')
        source = source.replace('0000000000000000000000CE, 0000000000000000000000CF);',
                                '0000000000000000000000CE);')
        path.write_text(source, encoding='utf-8')
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('DesignSystem.swift is not compiled', result.stderr)

    def test_rejects_duplicate_view_declaration(self):
        path = self.project / 'DictaLab/DictaLabApp.swift'
        source = path.read_text(encoding='utf-8') + '\nstruct AccountView: View {}\n'
        path.write_text(source, encoding='utf-8')
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Duplicate type AccountView', result.stderr)


if __name__ == '__main__':
    unittest.main()
