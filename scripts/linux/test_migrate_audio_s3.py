"""Regression: S3-only published assets survive subsequent prepare/activate batches."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import MagicMock, patch

# Tests do not contact AWS or require the Linux runtime SDK on a developer machine.
try:
    import boto3
except ImportError:
    for name in ('boto3', 'botocore', 'botocore.config', 'botocore.exceptions'):
        sys.modules[name] = types.ModuleType(name)
    sys.modules['botocore.config'].Config = MagicMock()
    sys.modules['botocore.exceptions'].ClientError = type('ClientError', (Exception,), {})

spec = importlib.util.spec_from_file_location('audio_migration', Path(__file__).with_name('migrate-audio-s3.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class S3OnlyMigrationTest(unittest.TestCase):
    def test_existing_alias_without_local_file_is_verified_and_preserved(self):
        item = {'audio_url': '/media/existing.wav', 'object_name': 'legacy/existing.wav',
                'sha256': 'a' * 64, 'byte_count': 44}
        config = {'DICTALAB_MEDIA_S3_BUCKET': 'bucket', 'DICTALAB_MEDIA_S3_PREFIX': 'audio/'}
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory) / 'plan.json'
            with patch.object(m, 'inventory', return_value={}), patch.object(m, 'conversation_rows', return_value=[]), \
                 patch.object(m.zipfile, 'ZipFile'), patch.object(m, 'verify_object') as verify, \
                 patch.object(m, 'database', return_value=json.dumps([item])):
                m.prepare(plan, config, MagicMock())
                self.assertEqual([item], json.loads(plan.read_text())['aliases'])
                verify.assert_called_once()
            with patch.object(m, 'stopped'), patch.object(m, 'inventory', return_value={}), \
                 patch.object(m, 'conversation_rows', return_value=[]), patch.object(m.zipfile, 'ZipFile'), \
                 patch.object(m, 'unchanged_content', return_value={'lessons': 'unchanged'}), \
                 patch.object(m, 'verify_object') as verify, patch.object(m, 'database', return_value='1') as db:
                m.activate(plan, config, MagicMock())
                self.assertTrue(json.loads(plan.read_text())['activated'])
                verify.assert_called_once()
                self.assertIn('INSERT INTO audio_s3_aliases', db.call_args.args[0])
            with patch.object(m, 'stopped'), patch.object(m, 'inventory', return_value={}), \
                 patch.object(m.zipfile, 'ZipFile'), patch.object(m, 'unchanged_content', return_value={}), \
                 patch.object(m, 'database', return_value='0'):
                with self.assertRaisesRegex(RuntimeError, 'alias changed'):
                    m.activate(plan, config, MagicMock())


if __name__ == '__main__':
    unittest.main()
