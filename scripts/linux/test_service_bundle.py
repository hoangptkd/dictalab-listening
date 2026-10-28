"""Release integrity checks use synthetic archives and disposable directories."""
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('service_bundle', Path(__file__).with_name('service-bundle.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class ServiceBundleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        files = {'services/ai-generation/app/main.py': 'app = object()',
                 'services/ai-generation/app/worker.py': 'def main(): pass',
                 'services/ai-generation/app/sql/001_provider_registry.sql': 'SELECT 1;',
                 'services/ai-generation/app/quota.lua': 'return 1',
                 'services/ai-generation/requirements.lock': 'fixture==1.0.0',
                 'artifacts/prompts/conversation.json': '{}',
                 'services/ai-generation/app/__pycache__/main.pyc': 'cached',
                 'services/ai-generation/app/.env': 'PRIVATE_MUST_NOT_SHIP',
                 '.env': 'PRIVATE_MUST_NOT_SHIP', 'data/jobs.sqlite3': 'PRIVATE_MUST_NOT_SHIP'}
        for name, content in files.items():
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        verifier = self.source / 'scripts/verify-production-jar.py'
        verifier.parent.mkdir()
        shutil.copyfile(bundle.ROOT / 'scripts/verify-production-jar.py', verifier)
        for name in bundle.UNIT_FILES:
            target = self.source / 'scripts/linux' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(bundle.ROOT / 'scripts/linux' / name, target)
        core = self.source / 'target/english-dictation-0.1.0-SNAPSHOT.jar'
        core.parent.mkdir()
        names = ['BOOT-INF/classes/db/migration/V35__all_audio_s3.sql']
        names += ['BOOT-INF/classes/static/audio/b1-expansion-20261015/' + str(i) + '.html' for i in range(91)]
        names += ['BOOT-INF/classes/static/audio/listening-samples-20261015/index.html']
        with zipfile.ZipFile(core, 'w') as jar:
            for name in names:
                jar.writestr(name, b'fixture')
        progress = self.source / 'services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar'
        progress.parent.mkdir(parents=True)
        with zipfile.ZipFile(progress, 'w') as jar:
            for name in ('BOOT-INF/classes/com/dictalab/progress/ProgressApplication.class',
                         'BOOT-INF/classes/db/migration/V1__progress_projection.sql', 'BOOT-INF/lib/runtime.jar'):
                jar.writestr(name, b'fixture')
        self.output = self.root / 'release.tar.gz'
        self.revision = 'a' * 40

    def create(self):
        return bundle.write_bundle(self.source, self.revision, self.output)

    def mutate(self, change):
        with tarfile.open(self.output) as archive:
            rows = [(m, archive.extractfile(m).read()) for m in archive.getmembers()]
        with tarfile.open(self.output, 'w:gz') as archive:
            for member, data in change(rows):
                archive.addfile(member, io.BytesIO(data) if member.isfile() else None)

    def test_complete_payload_excludes_runtime_secrets_caches_and_weights(self):
        manifest = self.create()
        self.assertEqual(manifest, bundle.verify_bundle(self.output, bundle.digest(self.output)))
        names = set(manifest['files'])
        self.assertIn('ai/app/sql/001_provider_registry.sql', names)
        self.assertIn('ai/app/quota.lua', names)
        self.assertIn('artifacts/prompts/conversation.json', names)
        self.assertTrue({'systemd/' + name for name in bundle.UNIT_FILES} <= names)
        with tarfile.open(self.output) as archive:
            self.assertFalse(any(b'PRIVATE_MUST_NOT_SHIP' in archive.extractfile(m).read() for m in archive.getmembers()))
        self.assertFalse(any('.env' in n or '__pycache__' in n or n.startswith('data/') for n in names))

    def test_existing_release_is_never_overwritten(self):
        self.output.write_bytes(b'existing')
        with self.assertRaises(FileExistsError):
            self.create()
        self.assertEqual(b'existing', self.output.read_bytes())

    def test_payload_corruption_rejected_even_if_outer_checksum_is_recomputed(self):
        self.create()
        def change(rows):
            for member, data in rows:
                if member.name == 'ai/app/main.py':
                    data = b'changed-payload'
                    member.size = len(data)
                yield member, data
        self.mutate(change)
        with self.assertRaisesRegex(ValueError, 'payload checksum'):
            bundle.verify_bundle(self.output, bundle.digest(self.output))

    def test_trusted_outer_checksum_rejects_replaced_archive(self):
        self.create()
        expected = bundle.digest(self.output)
        with self.output.open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'Bundle checksum'):
            bundle.verify_bundle(self.output, expected)

    def test_duplicate_paths_rejected(self):
        self.create()
        self.mutate(lambda rows: [*rows, rows[-1]])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            bundle.verify_bundle(self.output, bundle.digest(self.output))

    def test_traversal_symlink_and_unexpected_payload_rejected(self):
        for name, kind in (('../escaped', tarfile.REGTYPE), ('/escaped', tarfile.REGTYPE),
                           ('C:/escaped', tarfile.REGTYPE), ('ai/app/linked.py', tarfile.SYMTYPE),
                           ('ai/app/key.pem', tarfile.REGTYPE)):
            with self.subTest(name=name):
                if self.output.exists():
                    self.output.unlink()
                self.create()
                def change(rows):
                    member = tarfile.TarInfo(name)
                    member.type, member.linkname = kind, '/etc/passwd'
                    return [*rows, (member, b'')]
                self.mutate(change)
                with self.assertRaises(ValueError):
                    bundle.verify_bundle(self.output, bundle.digest(self.output))

    def test_incomplete_or_wrong_progress_jar_does_not_produce_release(self):
        jar = self.source / 'services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('fixture.txt', 'not Progress')
        with self.assertRaisesRegex(ValueError, 'Progress executable'):
            self.create()
        self.assertFalse(self.output.exists())

    def test_local_audio_reintroduction_is_rejected_before_packaging(self):
        jar = self.source / 'target/english-dictation-0.1.0-SNAPSHOT.jar'
        with zipfile.ZipFile(jar, 'a') as archive:
            archive.writestr('BOOT-INF/classes/static/audio/private.mp3', b'audio')
        with self.assertRaisesRegex(ValueError, 'local audio'):
            self.create()
        self.assertFalse(self.output.exists())

    def test_missing_manifest_payload_is_rejected(self):
        self.create()
        self.mutate(lambda rows: [(m, d) for m, d in rows if m.name != 'ai/app/main.py'])
        with self.assertRaisesRegex(ValueError, 'Missing release payload'):
            bundle.verify_bundle(self.output, bundle.digest(self.output))

    def test_previous_v1_release_can_still_be_verified(self):
        self.create()
        def change(rows):
            for member, data in rows:
                if member.name.startswith('systemd/'):
                    continue
                if member.name == 'manifest.json':
                    manifest = json.loads(data)
                    manifest['schemaVersion'] = 1
                    manifest['files'] = {k: v for k, v in manifest['files'].items() if not k.startswith('systemd/')}
                    data = json.dumps(manifest).encode()
                    member.size = len(data)
                yield member, data
        self.mutate(change)
        self.assertEqual(1, bundle.verify_bundle(self.output, bundle.digest(self.output))['schemaVersion'])

    def test_new_release_requires_all_three_systemd_units(self):
        self.create()
        def change(rows):
            for member, data in rows:
                if member.name == 'systemd/dictalab-progress.service':
                    continue
                if member.name == 'manifest.json':
                    manifest = json.loads(data)
                    del manifest['files']['systemd/dictalab-progress.service']
                    data = json.dumps(manifest).encode()
                    member.size = len(data)
                yield member, data
        self.mutate(change)
        with self.assertRaisesRegex(ValueError, 'Missing release payload'):
            bundle.verify_bundle(self.output, bundle.digest(self.output))


    def test_runtime_check_does_not_inherit_secrets_or_injection_settings(self):
        with patch.dict(os.environ, {'AI_PROVIDER_DATABASE_URL': 'private', 'GEMINI_API_KEY': 'private',
                                     'PYTHONPATH': 'injected', 'PIP_INDEX_URL': 'private'}), \
                patch.object(bundle.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            bundle.runtime_check(self.root)
        self.assertEqual(4, run.call_count)
        for call in run.call_args_list:
            env = call.kwargs['env']
            self.assertEqual({'PATH', 'HOME', 'LANG', 'DICTALAB_AI_REGISTRY_DIR'}, set(env))
            self.assertIn('-I', call.args[0])
            self.assertEqual(self.root / 'ai', call.kwargs['cwd'])


@unittest.skipUnless(sys.platform == 'linux' and hasattr(os, 'geteuid') and os.geteuid() == 0,
                     'Staging filesystem checks require Linux root')
class ServiceStageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir='/root', prefix='service-stage-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        # Reuse the archive fixture; its own temporary directory is cleaned independently.
        fixture = ServiceBundleTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        fixture.create()
        self.releases = self.root / 'services/releases'
        releases = patch.object(bundle, 'RELEASES', self.releases)
        releases.start()
        self.addCleanup(releases.stop)

    def stage(self):
        return bundle.stage(self.fixture.output, bundle.digest(self.fixture.output))

    def test_success_checks_runtime_before_publishing_and_preserves_current(self):
        current = self.root / 'services/current'
        current.parent.mkdir()
        current.symlink_to('previous-release')
        release = self.releases / self.fixture.revision
        def runtime(path):
            self.assertEqual(0o700, stat.S_IMODE(path.stat().st_mode))
            self.assertFalse((path / 'stage-ready.json').exists())
            executable = path / 'venv/bin/python'
            executable.parent.mkdir(parents=True)
            executable.write_text('#!/bin/sh\n', encoding='utf-8')
            executable.chmod(0o700)
        with patch.object(bundle, 'runtime_check', side_effect=runtime) as check:
            result = self.stage()
        check.assert_called_once_with(release)
        self.assertEqual('staged_runtime_only_not_deployment', result['acceptance'])
        self.assertEqual(result, json.loads((release / 'stage-ready.json').read_text()))
        self.assertEqual(Path('previous-release'), current.readlink())
        for path, mode in ((release, 0o755), (release / 'ai/app', 0o755),
                           (release / 'ai/app/main.py', 0o644), (release / 'venv/bin/python', 0o755)):
            self.assertEqual(mode, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(0, path.stat().st_uid)
        with patch.object(bundle, 'runtime_check') as check, self.assertRaises(FileExistsError):
            self.stage()
        check.assert_not_called()
        self.assertEqual(result, json.loads((release / 'stage-ready.json').read_text()))

    def test_corruption_is_rejected_before_creating_release_directories(self):
        checksum = bundle.digest(self.fixture.output)
        with self.fixture.output.open('ab') as output:
            output.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'Bundle checksum'):
            bundle.stage(self.fixture.output, checksum)
        self.assertFalse(self.releases.exists())

    def test_source_changed_during_transfer_is_rejected_before_extraction(self):
        original = bundle.verify_bundle
        def change_after_first_check(path, expected):
            result = original(path, expected)
            if path == self.fixture.output:
                with path.open('ab') as output:
                    output.write(b'changed')
            return result
        with patch.object(bundle, 'verify_bundle', side_effect=change_after_first_check), \
                self.assertRaisesRegex(ValueError, 'Bundle checksum'):
            self.stage()
        self.assertEqual([], list(self.releases.iterdir()))

    def test_unsafe_release_ancestors_are_rejected(self):
        for kind in ('symlink', 'writable'):
            with self.subTest(kind=kind):
                parent = self.root / kind
                if kind == 'symlink':
                    parent.symlink_to(self.root, target_is_directory=True)
                else:
                    parent.mkdir(mode=0o777)
                    parent.chmod(0o777)
                with patch.object(bundle, 'RELEASES', parent / 'releases'), \
                        self.assertRaisesRegex(ValueError, 'root-owned directories'):
                    self.stage()
        self.assertFalse((self.root / 'releases').exists())

    def test_runtime_failure_and_payload_changes_leave_private_unready_release(self):
        for kind in ('runtime', 'payload'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.root) as directory:
                releases = Path(directory) / 'releases'
                def fail_or_tamper(release):
                    if kind == 'runtime':
                        raise RuntimeError('fixture pip/import failure')
                    (release / 'ai/app/main.py').write_bytes(b'changed')
                with patch.object(bundle, 'RELEASES', releases), \
                        patch.object(bundle, 'runtime_check', side_effect=fail_or_tamper), \
                        self.assertRaises((ValueError, RuntimeError)):
                    self.stage()
                release = releases / self.fixture.revision
                self.assertEqual(0o700, stat.S_IMODE(release.stat().st_mode))
                self.assertFalse((release / 'stage-ready.json').exists())

    def test_real_venv_installation_and_import_without_network(self):
        self.fixture.output.unlink()
        source = self.fixture.source / 'services/ai-generation'
        (source / 'requirements.lock').write_text('', encoding='utf-8')
        files = {
            'main.py': 'import os; assert set(os.environ) <= '
                       '{"PATH", "HOME", "LANG", "DICTALAB_AI_REGISTRY_DIR", "LC_CTYPE"}\napp = object()\n',
            'config.py': 'class Settings: pass\n',
            'registry.py': 'def load_artifact(settings): return {}\n',
            'conversation_demo.py': 'def artifact(settings): return {}\n',
            'registry_migration.py': 'def schema_checksum(): return "fixture"\n',
        }
        for name, content in files.items():
            (source / 'app' / name).write_text(content, encoding='utf-8')
        self.fixture.create()
        self.stage()
        release = self.releases / self.fixture.revision
        self.assertTrue((release / 'stage-ready.json').is_file())
        result = subprocess.run([str(release / 'venv/bin/python'), '-I', '-c',
                                 'import sys; print(sys.prefix)'], capture_output=True, text=True, check=True)
        self.assertEqual(str(release / 'venv'), result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
