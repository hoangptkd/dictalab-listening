"""Build, verify and stage three business-service artifacts from one Git revision.

Staging installs the AI runtime but never activates, starts or migrates services.
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
import stat
import subprocess
import sys
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
RELEASES = Path('/opt/dictalab/services/releases')


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


def trusted_directory(path):
    """Only root may modify release ancestors, including newly created parents."""
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('Release directory must be an absolute canonical path')
    if path.parent != path:
        trusted_directory(path.parent)
    try:
        path.mkdir(mode=0o755)
    except FileExistsError:
        pass
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError('Release ancestors must be root-owned directories without group/world write access')


def runtime_check(release):
    # Do not pass production credentials, application settings or Python/pip injection variables.
    env = {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': str(release), 'LANG': 'C.UTF-8',
           'DICTALAB_AI_REGISTRY_DIR': str(release / 'artifacts/prompts')}
    python = release / 'venv/bin/python'
    commands = [
        [sys.executable, '-I', '-m', 'venv', str(release / 'venv')],
        [str(python), '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check',
         'install', '--no-input', '--no-cache-dir', '-r', str(release / 'ai/requirements.lock')],
        [str(python), '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check', 'check'],
        [str(python), '-I', '-B', '-c',
         'import os, sys; sys.path.insert(0, os.getcwd()); '
         'import app.main, app.worker; from app.config import Settings; '
         'from app.registry import load_artifact; from app.conversation_demo import artifact; '
         'from app.registry_migration import schema_checksum; '
         'load_artifact(Settings()); artifact(Settings()); schema_checksum()'],
    ]
    for command in commands:
        result = subprocess.run(command, cwd=release / 'ai', env=env,
                                capture_output=True, timeout=900)
        if result.returncode:
            raise RuntimeError('Isolated AI runtime installation/check failed; release remains private')


def stage(path, expected):
    if sys.platform != 'linux' or os.geteuid() != 0 or sys.version_info < (3, 12):
        raise RuntimeError('Staging requires Linux root and Python 3.12 or later')
    manifest = verify_bundle(path, expected)
    if manifest['schemaVersion'] != 2:
        raise ValueError('Staging requires release format v2')
    trusted_directory(RELEASES)
    # A private verified copy prevents mutation of a transferred bundle during extraction.
    with tempfile.TemporaryDirectory(prefix='.incoming-', dir=RELEASES) as directory:
        snapshot = Path(directory) / 'bundle.tar.gz'
        with path.open('rb') as source, snapshot.open('xb') as output:
            remaining = MAX_BYTES + 2 * 1024**2
            while block := source.read(min(1024**2, remaining + 1)):
                remaining -= len(block)
                if remaining < 0:
                    raise ValueError('Compressed bundle exceeds bounds')
                output.write(block)
        manifest = verify_bundle(snapshot, expected)
        release = RELEASES / manifest['sourceRevision']
        release.mkdir(mode=0o700)  # Exclusive: preserve every existing release, including failed staging.
        try:
            with tarfile.open(snapshot, 'r:gz') as archive:
                for member in archive.getmembers():
                    target = release / member.name
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with archive.extractfile(member) as source, target.open('xb') as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(0o600)
            # Venv scripts embed absolute paths, so install at the final immutable release path.
            runtime_check(release)
            for name, info in manifest['files'].items():
                target = release / name
                if target.is_symlink() or target.stat().st_size != info['bytes'] or digest(target) != info['sha256']:
                    raise ValueError('Installed release payload checksum mismatch')
            ready = {'sourceRevision': manifest['sourceRevision'], 'bundleSha256': expected,
                     'acceptance': 'staged_runtime_only_not_deployment'}
            with (release / 'stage-ready.json').open('x', encoding='utf-8') as output:
                output.write(json.dumps(ready, sort_keys=True) + '\n')
                output.flush()
                os.fsync(output.fileno())
            for target in release.rglob('*'):
                if not target.is_symlink():
                    target.chmod(0o755 if target.is_dir() or target.stat().st_mode & 0o111 else 0o644)
            release.chmod(0o755)  # Publish only after every check succeeds.
        except BaseException:
            release.chmod(0o700)
            (release / 'stage-ready.json').unlink(missing_ok=True)
            raise
    return ready


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('build')
    prepare.add_argument('--revision', default='HEAD')
    prepare.add_argument('--output', type=Path, required=True)
    for name in ('verify', 'stage'):
        command = commands.add_parser(name)
        command.add_argument('bundle', type=Path)
        command.add_argument('--sha256', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'stage':
            print(json.dumps(stage(args.bundle, args.sha256)))
            return
        manifest = build(args.revision, args.output) if args.command == 'build' else verify_bundle(args.bundle, args.sha256)
        path = args.output if args.command == 'build' else args.bundle
        print(json.dumps({'sourceRevision': manifest['sourceRevision'], 'sha256': digest(path),
                          'files': len(manifest['files']), 'bytes': path.stat().st_size,
                          'acceptance': 'package_integrity_only_not_deployment'}))
    except Exception as error:
        raise SystemExit('SERVICE_BUNDLE_FAILED: ' + type(error).__name__ + '; no deployment performed') from None


if __name__ == '__main__':
    main()
