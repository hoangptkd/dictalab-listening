"""Protected PostgreSQL backup; opt-in services mode also snapshots private AI state.

Services mode requires all four systemd processes already inactive. It never stops,
starts or restores production services/databases, Redis or RabbitMQ.
"""
import argparse
import configparser
from contextlib import closing
import datetime
import hashlib
import json
import os
import pathlib
import re
import shlex
import shutil
import sqlite3
import stat
import subprocess
import tempfile
import urllib.parse

PG_BIN = pathlib.Path('/usr/lib/postgresql/18/bin')
SERVICES = ('dictalab', 'dictalab-ai', 'dictalab-ai-worker', 'dictalab-progress')
SERVICE_CONFIG = pathlib.Path('/etc/dictalab/backup.pg_service.conf')
SERVICE_BACKUPS = pathlib.Path('/var/lib/dictalab-service-backups')
SERVICE_ENVS = pathlib.Path('/etc/dictalab')
CORE_STATE = pathlib.Path('/var/lib/dictalab')
AI_STATE = pathlib.Path('/var/lib/dictalab-ai')
CORE_JAR = pathlib.Path('/opt/dictalab/app/dictalab.jar')
CURRENT = pathlib.Path('/opt/dictalab/services/current')
UNITS = pathlib.Path('/etc/systemd/system')


def connection_environment():
    settings = {}
    for line in pathlib.Path('/etc/dictalab/app.env').read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            name, value = line.split('=', 1)
            settings[name] = shlex.split(value)[0]
    url = urllib.parse.urlsplit(settings['DB_URL'].removeprefix('jdbc:'))
    return os.environ | dict(PGHOST=url.hostname.replace('-pooler.', '.'), PGPORT=str(url.port or 5432),
        PGDATABASE=url.path.lstrip('/'), PGUSER=settings['DB_USERNAME'], PGPASSWORD=settings['DB_PASSWORD'],
        PGSSLMODE='verify-full', PGSSLROOTCERT='/etc/ssl/certs/ca-certificates.crt',
        PGCHANNELBINDING='require', PGCONNECT_TIMEOUT='15', PGOPTIONS='-c statement_timeout=60000 -c lock_timeout=3000')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(command, environment=None):
    result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=180)
    if result.returncode:
        raise RuntimeError('Backup operation failed; credential values suppressed')
    return result.stdout


def stopped():
    state = run(['systemctl', 'show', 'dictalab', '--property=ActiveState', '--value']).strip()
    if state != 'inactive':
        raise RuntimeError('Quiescent snapshot requires dictalab.service inactive')


def protected(path, *, directory=False):
    info = path.lstat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError('Service backup files/directories require root ownership and private permissions')


def services_stopped():
    for name in SERVICES:
        result = run(['systemctl', 'show', name, '--property=LoadState,ActiveState,MainPID'],
                     {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/nonexistent', 'LANG': 'C.UTF-8'})
        fields = dict(line.split('=', 1) for line in result.splitlines() if '=' in line)
        if fields != {'LoadState': 'loaded', 'ActiveState': 'inactive', 'MainPID': '0'}:
            raise RuntimeError('All installed business-service processes must be inactive before snapshot')


def runtime_database(name):
    path = SERVICE_ENVS / {'core': 'app.env', 'ai': 'ai.env', 'progress': 'progress.env'}[name]
    protected(path)
    settings = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, value = line.split('=', 1)
        parts = shlex.split(value)
        if key in settings or len(parts) > 1:
            raise ValueError('Runtime environment requires unique literal single-line assignments')
        settings[key] = parts[0] if parts else ''
    if name == 'ai':
        if (settings.get('DICTALAB_AI_REGISTRY_OWNERSHIP') != 'private'
                or settings.get('AI_PROVIDER_CONFIG_SOURCE') != 'database'
                or settings.get('DICTALAB_AI_JOBS_DATABASE', str(AI_STATE / 'jobs.sqlite3')) != str(AI_STATE / 'jobs.sqlite3')):
            raise ValueError('Snapshot requires private AI ownership and the unit ledger path')
        url = urllib.parse.urlsplit(settings['AI_PROVIDER_DATABASE_URL'])
        user = urllib.parse.unquote(url.username or '')
    else:
        prefix = 'PROGRESS_' if name == 'progress' else ''
        url = urllib.parse.urlsplit(settings[prefix + 'DB_URL'].removeprefix('jdbc:'))
        user = settings[prefix + 'DB_USERNAME']
    if url.scheme not in ('postgres', 'postgresql') or not url.hostname or not user:
        raise ValueError('Explicit PostgreSQL runtime identity is required')
    return url.hostname.lower(), str(url.port or 5432), urllib.parse.unquote(url.path.lstrip('/')), user


def service_environment(name):
    protected(SERVICE_CONFIG)
    config = configparser.ConfigParser(interpolation=None)
    config.read_string(SERVICE_CONFIG.read_text(encoding='utf-8'))
    if config.defaults() or set(config.sections()) != {'core', 'ai', 'progress'}:
        raise ValueError('Explicit independent core/ai/progress backup services are required')
    identities, roles = set(), set()
    for section in config.values():
        if not section.name or section.name == 'DEFAULT':
            continue
        if (set(section) != {'host', 'port', 'dbname', 'user', 'password', 'sslmode', 'sslrootcert', 'channel_binding'}
                or section['sslmode'] != 'verify-full' or section['channel_binding'] != 'require'
                or not section['host'] or any(c in section['host'] for c in '/,= \r\n')
                or '-pooler.' in section['host'] or not 1 <= int(section['port']) <= 65535
                or not re.fullmatch(r'[a-zA-Z0-9_-]{1,63}', section['dbname'])
                or not section['user'] or not section['password']
                or not pathlib.Path(section['sslrootcert']).is_absolute()
                or not pathlib.Path(section['sslrootcert']).is_file()):
            raise ValueError('Backup requires direct PostgreSQL endpoints, explicit credentials and verified TLS')
        certificate = pathlib.Path(section['sslrootcert']).stat()
        if certificate.st_uid != 0 or certificate.st_mode & 0o022:
            raise ValueError('Database CA must be root-owned without group/world write access')
        identity = (section['host'].lower(), section['port'], section['dbname'])
        runtime = runtime_database(section.name)
        if identity != runtime[:3] or section['user'] == runtime[3]:
            raise ValueError('Backup target must match its runtime database and use a separate backup role')
        identities.add(identity)
        roles.add(section['user'])
    if len(identities) != 3 or len(roles) != 3:
        raise ValueError('Each business service needs its own database and backup role')
    return {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/nonexistent', 'LANG': 'C.UTF-8',
            'PGSERVICEFILE': str(SERVICE_CONFIG), 'PGSERVICE': name, 'PGCONNECT_TIMEOUT': '15',
            'PGOPTIONS': '-c statement_timeout=120000 -c lock_timeout=3000'}


def copy_private(source, target):
    if source.is_symlink() or not source.is_file():
        raise ValueError('Snapshot source must be a regular file')
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    before = digest(source)
    with source.open('rb') as input_stream, target.open('xb') as output:
        shutil.copyfileobj(input_stream, output)
    target.chmod(0o600)
    if digest(source) != before or digest(target) != before:
        raise RuntimeError('Snapshot source changed or copy checksum mismatch')


def service_snapshot():
    protected(SERVICE_BACKUPS, directory=True)
    environments = {name: service_environment(name) for name in ('core', 'ai', 'progress')}
    services_stopped()
    if shutil.disk_usage(SERVICE_BACKUPS).free < 2 * 1024**3:
        raise RuntimeError('Less than 2 GiB free for backup')
    directory = pathlib.Path(tempfile.mkdtemp(prefix='services-', dir=SERVICE_BACKUPS))
    # The root-only container protects credentials even when a later step fails.
    for name, environment in environments.items():
        dump = directory / (name + '.dump')
        run([str(PG_BIN / 'pg_dump'), '-Fc', '--no-owner', '--no-acl', '-f', str(dump)], environment)
        dump.chmod(0o600)
        run([str(PG_BIN / 'pg_restore'), '--list', str(dump)], environment)
    for name in ('app.env', 'ai.env', 'progress.env'):
        protected(SERVICE_ENVS / name)
        copy_private(SERVICE_ENVS / name, directory / 'config' / name)
    copy_private(SERVICE_CONFIG, directory / 'config/backup.pg_service.conf')
    copy_private(CORE_JAR, directory / 'dictalab.jar')
    if not CURRENT.is_symlink():
        raise ValueError('Current release must be an explicit symlink before a coordinated snapshot')
    release = CURRENT.resolve(strict=True)
    if release.parent != CURRENT.parent / 'releases' or not re.fullmatch('[a-f0-9]{40}', release.name):
        raise ValueError('Current release escaped the releases directory')
    for name in ('manifest.json', 'stage-ready.json', 'progress.jar'):
        copy_private(release / name, directory / 'release' / name)
    for name in SERVICES:
        copy_private(UNITS / (name + '.service'), directory / 'systemd' / (name + '.service'))
    for name in ('media', 'personal-content'):
        source = CORE_STATE / name
        if source.is_symlink() or not source.is_dir():
            raise ValueError('Required Core media directory is missing or linked')
        (directory / name).mkdir(mode=0o700)
        for path in source.rglob('*'):
            if path.is_symlink() or not path.is_file() and not path.is_dir():
                raise ValueError('Non-regular media snapshot member')
            relative = directory / name / path.relative_to(source)
            if path.is_dir():
                relative.mkdir(mode=0o700, parents=True, exist_ok=True)
            else:
                copy_private(path, relative)
    ledger = AI_STATE / 'jobs.sqlite3'
    if ledger.is_symlink() or not ledger.is_file():
        raise ValueError('AI job ledger is required before a coordinated snapshot')
    # Work on private DB+WAL copies: opening a WAL DB as root can create root-owned
    # SHM files beside the live ledger and prevent the AI account from restarting.
    with tempfile.TemporaryDirectory(prefix='.sqlite-input-', dir=directory) as temporary:
        copied = pathlib.Path(temporary) / ledger.name
        copy_private(ledger, copied)
        wal = ledger.with_name(ledger.name + '-wal')
        if wal.exists() or wal.is_symlink():
            copy_private(wal, copied.with_name(copied.name + '-wal'))
        with closing(sqlite3.connect(copied.as_uri() + '?mode=ro', uri=True)) as source, \
                closing(sqlite3.connect(directory / 'jobs.sqlite3')) as destination:
            source.backup(destination, pages=1024)
            destination.execute('PRAGMA journal_mode=DELETE')
            if destination.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
                raise RuntimeError('SQLite snapshot integrity check failed')
    (directory / 'jobs.sqlite3').chmod(0o600)
    services_stopped()
    manifest = {'schemaVersion': 1, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'releaseRevision': release.name, 'consistency': 'business-writers-quiescent',
                'restoreAcceptance': 'archive-and-sqlite-integrity-only',
                'externalState': 'Redis and RabbitMQ must be retained; not snapshotted or rewound',
                'files': {path.relative_to(directory).as_posix(): {'bytes': path.stat().st_size,
                          'sha256': digest(path)} for path in sorted(directory.rglob('*')) if path.is_file()}}
    with (directory / 'manifest.json').open('x', encoding='utf-8') as output:
        json.dump(manifest, output, sort_keys=True, indent=2)
        output.flush()
        os.fsync(output.fileno())
    (directory / 'manifest.json').chmod(0o600)
    expected = digest(directory / 'manifest.json')
    verify_service_snapshot(directory, expected)
    return {'backupDirectory': str(directory), 'manifestSha256': expected,
            'acceptance': manifest['restoreAcceptance']}


def verify_service_snapshot(directory, expected):
    protected(directory, directory=True)
    protected(directory / 'manifest.json')
    if not re.fullmatch('[a-f0-9]{64}', expected) or digest(directory / 'manifest.json') != expected:
        raise ValueError('Service snapshot manifest checksum mismatch')
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    required = {'core.dump', 'ai.dump', 'progress.dump', 'jobs.sqlite3', 'dictalab.jar',
                'release/manifest.json', 'release/stage-ready.json', 'release/progress.jar',
                'config/backup.pg_service.conf', *('config/' + n for n in ('app.env', 'ai.env', 'progress.env')),
                *('systemd/' + n + '.service' for n in SERVICES)}
    if manifest['schemaVersion'] != 1 or not required <= set(manifest['files']):
        raise ValueError('Incomplete service snapshot')
    actual = set()
    for path in directory.rglob('*'):
        protected(path, directory=path.is_dir())
        if path.is_file() and path != directory / 'manifest.json':
            actual.add(path.relative_to(directory).as_posix())
    if actual != set(manifest['files']):
        raise ValueError('Unexpected or missing service snapshot member')
    for name, info in manifest['files'].items():
        path = directory / name
        if info['bytes'] != path.stat().st_size or info['sha256'] != digest(path):
            raise ValueError('Service snapshot payload checksum mismatch')
    for name in ('core', 'ai', 'progress'):
        run([str(PG_BIN / 'pg_restore'), '--list', str(directory / (name + '.dump'))],
            {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/nonexistent', 'LANG': 'C.UTF-8'})
    with closing(sqlite3.connect((directory / 'jobs.sqlite3').as_uri() + '?mode=ro', uri=True)) as db:
        if db.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise RuntimeError('SQLite snapshot integrity check failed')
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--quiescent', action='store_true')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--services', action='store_true')
    mode.add_argument('--verify-services', type=pathlib.Path)
    parser.add_argument('--sha256')
    args = parser.parse_args()
    if args.sha256 and not args.verify_services:
        parser.error('--sha256 requires --verify-services')
    if os.getuid() != 0:
        raise RuntimeError('Protected backup requires root')
    os.umask(0o077)
    if args.services or args.verify_services:
        if args.quiescent:
            parser.error('Services modes are separate from legacy --quiescent backups')
        try:
            if args.services:
                print(json.dumps(service_snapshot()))
            else:
                if not args.sha256:
                    parser.error('--verify-services requires --sha256')
                manifest = verify_service_snapshot(args.verify_services.absolute(), args.sha256)
                print(json.dumps({'verified': True, 'acceptance': manifest['restoreAcceptance']}))
        except Exception as error:
            raise SystemExit('SERVICE_BACKUP_FAILED: ' + type(error).__name__ + '; no services or databases changed') from None
        return
    root = pathlib.Path('/var/lib/dictalab/backups')
    if shutil.disk_usage(root).free < 2 * 1024**3:
        raise RuntimeError('Less than 2 GiB free for backup')
    if args.quiescent:
        stopped()
    directory = pathlib.Path(tempfile.mkdtemp(prefix='cloud-', dir=root))
    dump = directory / 'database.dump'
    run(['/usr/lib/postgresql/18/bin/pg_dump', '-Fc', '--no-owner', '--no-acl', '-f', str(dump)], connection_environment())
    run(['/usr/lib/postgresql/18/bin/pg_restore', '--list', str(dump)])
    files = [{'path': dump.name, 'bytes': dump.stat().st_size, 'sha256': digest(dump)}]
    if args.quiescent:
        for name in ('media', 'personal-content'):
            source = pathlib.Path('/var/lib/dictalab') / name
            if not source.is_dir():
                raise RuntimeError('Required media directory missing')
            for path in source.rglob('*'):
                if path.is_symlink():
                    raise RuntimeError('Symlink in media snapshot')
                if path.is_file():
                    relative = pathlib.Path(name) / path.relative_to(source)
                    target = directory / relative
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    shutil.copy2(path, target)
                    expected = digest(path)
                    if digest(target) != expected:
                        raise RuntimeError('Media checksum mismatch')
                    files.append({'path': relative.as_posix(), 'bytes': target.stat().st_size, 'sha256': expected})
        jar = pathlib.Path('/opt/dictalab/app/dictalab.jar')
        shutil.copy2(jar, directory / 'dictalab.jar')
        files.append({'path': 'dictalab.jar', 'bytes': jar.stat().st_size, 'sha256': digest(jar)})
        stopped()
    manifest = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'consistency': 'quiescent-db-media' if args.quiescent else 'database-only', 'files': files}
    with (directory / 'manifest.json').open('x', encoding='utf-8') as stream:
        json.dump(manifest, stream, indent=2)
    # Publish freshness only after every requested check passes; partial backups stay inspectable.
    temporary = root / '.backup-success.tmp'
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump({'backup': str(directory), **manifest}, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o640)
    shutil.chown(temporary, user='root', group='dictalab')
    os.replace(temporary, root / 'backup-success.json')
    print('CLOUD_BACKUP=' + str(directory))
    print('CLOUD_BACKUP_CONSISTENCY=' + manifest['consistency'])
    print('CLOUD_BACKUP_SHA256=' + digest(dump))


if __name__ == '__main__':
    main()
