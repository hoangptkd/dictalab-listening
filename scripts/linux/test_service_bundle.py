"""Release integrity checks use synthetic archives and disposable directories."""
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
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


if __name__ == '__main__':
    unittest.main()
