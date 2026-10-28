"""Build all three business-service artifacts from one Git revision; verify before transfer.

This prepares an offline release. It does not install, start or migrate services.
Build/CI acceptance and real-host resource/backup gates remain separate.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
UNIT_FILES = ('dictalab-ai.service', 'dictalab-ai-worker.service', 'dictalab-progress.service')
SOURCE_PATHS = ('pom.xml', 'src', 'services/progress/pom.xml', 'services/progress/src',
                'services/ai-generation/app', 'services/ai-generation/requirements.lock',
                'artifacts/prompts', 'scripts/verify-production-jar.py',
                *('scripts/linux/' + name for name in UNIT_FILES))
MAX_BYTES = 512 * 1024**2
MAX_FILES = 5000


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_name(value):
    path = PurePosixPath(value)
    if (not value or '\\' in value or ':' in value or path.is_absolute()
            or any(part in ('', '.', '..') for part in value.split('/'))):
        raise ValueError('Unsafe release member path')
    return path


def payload_name(name):
    if name in ('dictalab.jar', 'progress.jar', 'ai/requirements.lock') or name in ('systemd/' + n for n in UNIT_FILES):
        return True
    path = safe_name(name)
    if name.startswith('ai/app/'):
        return path.suffix in ('.py', '.lua') or name.startswith('ai/app/sql/') and path.suffix == '.sql'
    return name.startswith('artifacts/prompts/') and path.suffix == '.json'


def check_jars(core, progress, verifier):
    spec = importlib.util.spec_from_file_location('production_jar', verifier)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(core)
    with zipfile.ZipFile(progress) as jar:
        entries = set(jar.namelist())
        required = {'BOOT-INF/classes/com/dictalab/progress/ProgressApplication.class',
                    'BOOT-INF/classes/db/migration/V1__progress_projection.sql'}
        if not required <= entries or not any(n.startswith('BOOT-INF/lib/') for n in entries):
            raise ValueError('Progress executable JAR is incomplete')


def write_bundle(source, revision, destination):
    core = source / 'target/english-dictation-0.1.0-SNAPSHOT.jar'
    progress = source / 'services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar'
    check_jars(core, progress, source / 'scripts/verify-production-jar.py')
    files = {'dictalab.jar': core, 'progress.jar': progress,
             'ai/requirements.lock': source / 'services/ai-generation/requirements.lock'}
    files.update({'systemd/' + name: source / 'scripts/linux' / name for name in UNIT_FILES})
    for prefix, folder in (('ai/app', source / 'services/ai-generation/app'),
                           ('artifacts/prompts', source / 'artifacts/prompts')):
        for path in sorted(folder.rglob('*')):
            name = prefix + '/' + path.relative_to(folder).as_posix()
            if path.is_symlink():
                raise ValueError('Symlink in release source')
            if path.is_file() and payload_name(name):
                files[name] = path
    manifest = {'schemaVersion': 2, 'sourceRevision': revision,
                'scope': 'Core, Progress and AI; excludes runtime configuration and model weights',
                'files': {name: {'bytes': p.stat().st_size, 'sha256': digest(p)} for name, p in sorted(files.items())}}
    if not re.fullmatch('[a-f0-9]{40}', revision) or len(files) > MAX_FILES or sum(p.stat().st_size for p in files.values()) > MAX_BYTES:
        raise ValueError('Invalid revision or release exceeds bounds')
    created = False
    try:
        with destination.open('xb') as output:
            created = True
            with tarfile.open(fileobj=output, mode='w:gz') as archive:
                data = (json.dumps(manifest, sort_keys=True, indent=2) + '\n').encode()
                entry = tarfile.TarInfo('manifest.json')
                entry.size, entry.mode = len(data), 0o640
                archive.addfile(entry, io.BytesIO(data))
                for name, path in sorted(files.items()):
                    entry = tarfile.TarInfo(name)
                    entry.size, entry.mode = path.stat().st_size, 0o640
                    with path.open('rb') as stream:
                        archive.addfile(entry, stream)
            output.flush()
            os.fsync(output.fileno())
        verify_bundle(destination, digest(destination))
    except Exception:
        if created:
            destination.unlink()
        raise
    return manifest


def verify_bundle(path, expected):
    if not re.fullmatch('[a-f0-9]{64}', expected) or path.is_symlink() or digest(path) != expected:
        raise ValueError('Bundle checksum mismatch')
    with tarfile.open(path, 'r:gz') as archive:
        members, total = [], 0
        for member in archive:
            members.append(member)
            total += member.size
            if len(members) > MAX_FILES + 1 or total > MAX_BYTES + 1024**2:
                raise ValueError('Release exceeds bounds')
        names = [m.name for m in members]
        if (len(members) > MAX_FILES + 1 or len(set(names)) != len(names)
                or sum(m.size for m in members) > MAX_BYTES + 1024**2):
            raise ValueError('Duplicate members or release exceeds bounds')
        for member in members:
            safe_name(member.name)
            if not member.isfile() or member.size < 0 or member.name != 'manifest.json' and not payload_name(member.name):
                raise ValueError('Unexpected release member')
        entry = archive.getmember('manifest.json')
        if entry.size > 1024**2:
            raise ValueError('Release manifest exceeds bounds')
        manifest = json.load(archive.extractfile(entry))
        if manifest.get('schemaVersion') not in (1, 2) or not re.fullmatch('[a-f0-9]{40}', manifest.get('sourceRevision', '')):
            raise ValueError('Invalid release manifest')
        files = manifest['files']
        required = {'dictalab.jar', 'progress.jar', 'ai/requirements.lock', 'ai/app/main.py', 'ai/app/worker.py'}
        if manifest['schemaVersion'] == 2:
            required.update('systemd/' + n for n in UNIT_FILES)
        if set(names) != set(files) | {'manifest.json'} or not required <= set(files):
            raise ValueError('Missing release payload')
        for member in members:
            if member.name == 'manifest.json':
                continue
            expected_file = files[member.name]
            with archive.extractfile(member) as stream:
                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
            if member.size != expected_file['bytes'] or actual != expected_file['sha256']:
                raise ValueError('Release payload checksum mismatch')
    return manifest


def build(revision, destination):
    revision = subprocess.check_output(['git', 'rev-parse', '--verify', '--end-of-options', revision + '^{commit}'], cwd=ROOT, text=True).strip()
    maven = shutil.which('mvn')
    if not maven:
        raise RuntimeError('Maven is required')
    if destination.is_symlink():
        raise ValueError('Release destination cannot be a symlink')
    destination = destination.resolve()
    if destination.exists():
        raise ValueError('Release destination must not exist')
    build_root = ROOT / 'target'
    build_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='service-release-', dir=build_root) as temporary:
        temporary = Path(temporary).resolve()
        if temporary.parent != build_root.resolve():
            raise ValueError('Build directory escaped workspace target')
        snapshot = temporary / 'source.tar'
        with snapshot.open('xb') as stream:
            subprocess.run(['git', 'archive', '--format=tar', revision, '--', *SOURCE_PATHS],
                           cwd=ROOT, stdout=stream, check=True)
        source = temporary / 'source'
        source.mkdir()
        with tarfile.open(snapshot) as archive:
            for member in archive.getmembers():
                safe_name(member.name.rstrip('/'))
                if not member.isfile() and not member.isdir():
                    raise ValueError('Non-regular Git source member')
            archive.extractall(source, filter='data')
        env = {k: v for k, v in os.environ.items() if k.upper() in {
            'PATH', 'PATHEXT', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP',
            'USERPROFILE', 'HOME', 'JAVA_HOME', 'APPDATA', 'LOCALAPPDATA'}}
        for name, args in (('Core', ['-Pproduction-s3']), ('Progress', ['-f', 'services/progress/pom.xml'])):
            print(f'Building {name} from {revision[:12]}...', flush=True)
            result = subprocess.run([maven, '--batch-mode', '--no-transfer-progress', *args,
                                     '-Dmaven.test.skip=true', 'package'], cwd=source, env=env,
                                    capture_output=True, timeout=900)
            if result.returncode:
                raise RuntimeError(name + ' build failed; no release produced')
        manifest = write_bundle(source, revision, destination)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('build')
    prepare.add_argument('--revision', default='HEAD')
    prepare.add_argument('--output', type=Path, required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('bundle', type=Path)
    verify.add_argument('--sha256', required=True)
    args = parser.parse_args()
    try:
        manifest = build(args.revision, args.output) if args.command == 'build' else verify_bundle(args.bundle, args.sha256)
        path = args.output if args.command == 'build' else args.bundle
        print(json.dumps({'sourceRevision': manifest['sourceRevision'], 'sha256': digest(path),
                          'files': len(manifest['files']), 'bytes': path.stat().st_size,
                          'acceptance': 'package_integrity_only_not_deployment'}))
    except Exception as error:
        raise SystemExit('SERVICE_BUNDLE_FAILED: ' + type(error).__name__ + '; no deployment performed') from None


if __name__ == '__main__':
    main()
