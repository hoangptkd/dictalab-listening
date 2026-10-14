"""Protected PostgreSQL 18 backup. Quiescent mode includes all local media and the JAR."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import subprocess
import tempfile
import urllib.parse


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--quiescent', action='store_true')
    args = parser.parse_args()
    if os.getuid() != 0:
        raise RuntimeError('Protected backup requires root')
    os.umask(0o077)
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
