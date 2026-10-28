"""Private snapshot checks; opt-in PostgreSQL test restores only disposable databases."""
from contextlib import closing
import importlib.util
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('cloud_backup', Path(__file__).with_name('cloud-backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
REAL_RUN = backup.run


@unittest.skipUnless(sys.platform == 'linux' and hasattr(os, 'geteuid') and os.geteuid() == 0,
                     'Requires Linux root; CI uses a disposable container')
class CloudBackupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir='/root', prefix='service-backup-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name, relative in {'SERVICE_CONFIG': 'config/backup.pg_service.conf',
                'SERVICE_BACKUPS': 'backups', 'SERVICE_ENVS': 'config', 'CORE_STATE': 'core',
                'AI_STATE': 'ai', 'CORE_JAR': 'core.jar', 'CURRENT': 'services/current',
                'UNITS': 'units'}.items():
            context = patch.object(backup, name, self.root / relative)
            context.start()
            self.addCleanup(context.stop)
        for path in (backup.SERVICE_BACKUPS, backup.SERVICE_ENVS, backup.AI_STATE, backup.UNITS,
                     backup.CORE_STATE / 'media', backup.CORE_STATE / 'personal-content'):
            path.mkdir(parents=True, mode=0o700)
        self.ca = self.root / 'ca.pem'
        self.ca.write_text('fixture-CA', encoding='utf-8')
        self.configuration()
        backup.CORE_JAR.write_bytes(b'old-core-fixture')
        (backup.CORE_STATE / 'media/fixture.wav').write_bytes(b'synthetic-media')
        (backup.CORE_STATE / 'media/empty').mkdir()
        release = backup.CURRENT.parent / 'releases' / ('a' * 40)
        release.mkdir(parents=True)
        for name in ('manifest.json', 'stage-ready.json', 'progress.jar'):
            (release / name).write_bytes(b'previous-release-fixture')
        backup.CURRENT.symlink_to(release)
        for name in backup.SERVICES:
            (backup.UNITS / (name + '.service')).write_text('[Service]\n', encoding='utf-8')
        self.ledger = sqlite3.connect(backup.AI_STATE / 'jobs.sqlite3')
        self.addCleanup(self.ledger.close)
        self.ledger.executescript('''PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;
            CREATE TABLE jobs (id TEXT, status TEXT, result TEXT);
            INSERT INTO jobs VALUES ('job-1', 'running', NULL);
            CREATE TABLE demo_reservations (job_id TEXT PRIMARY KEY);
            INSERT INTO demo_reservations VALUES ('job-1');
            CREATE TABLE text_admissions (id TEXT PRIMARY KEY, deadline REAL);
            INSERT INTO text_admissions VALUES ('request-1', 1234567890);
            CREATE TABLE demo_budget (reserved INTEGER, calls INTEGER);
            INSERT INTO demo_budget VALUES (5000, 2);''')
        self.commands = []
        run = patch.object(backup, 'run', side_effect=self.fake_run)
        run.start()
        self.addCleanup(run.stop)

    @staticmethod
    def private(path, content):
        path.write_text(content, encoding='utf-8')
        path.chmod(0o600)

    def configuration(self, port=5432):
        self.private(backup.SERVICE_CONFIG, '\n'.join(
            f'[{name}]\nhost=localhost\nport={port}\ndbname={name}\nuser={name}_backup\n'
            f'password=synthetic-test-password\nsslmode=verify-full\nsslrootcert={self.ca}\nchannel_binding=require\n'
            for name in ('core', 'ai', 'progress')))
        for name, prefix, filename in (('core', '', 'app.env'), ('progress', 'PROGRESS_', 'progress.env')):
            self.private(backup.SERVICE_ENVS / filename,
                f'{prefix}DB_URL=jdbc:postgresql://localhost:{port}/{name}\n'
                f'{prefix}DB_USERNAME={name}_runtime\n{prefix}DB_PASSWORD=synthetic-runtime-password\n')
        self.private(backup.SERVICE_ENVS / 'ai.env',
            f'AI_PROVIDER_DATABASE_URL=postgresql://ai_runtime:synthetic-runtime-password@localhost:{port}/ai\n'
            f'AI_PROVIDER_CONFIG_SOURCE=database\nDICTALAB_AI_REGISTRY_OWNERSHIP=private\n'
            f'DICTALAB_AI_JOBS_DATABASE={backup.AI_STATE / "jobs.sqlite3"}\n')

    def fake_run(self, command, environment=None):
        self.commands.append((command, environment))
        if command[0] == 'systemctl':
            return 'LoadState=loaded\nActiveState=inactive\nMainPID=0\n'
        if Path(command[0]).name == 'pg_dump':
            Path(command[-1]).write_bytes(b'synthetic-pg-archive')
        return 'synthetic archive list'

    def create(self):
        result = backup.service_snapshot()
        return Path(result['backupDirectory']), result['manifestSha256']

    def test_wal_jobs_budget_and_admission_survive_without_touching_source(self):
        os.chown(backup.AI_STATE, 65534, 65534)
        for path in backup.AI_STATE.iterdir():
            os.chown(path, 65534, 65534)
        originals = {p.name: (backup.digest(p), p.stat().st_uid, p.stat().st_mode) for p in backup.AI_STATE.iterdir()}
        self.assertGreater((backup.AI_STATE / 'jobs.sqlite3-wal').stat().st_size, 0)
        directory, checksum = self.create()
        manifest = backup.verify_service_snapshot(directory, checksum)
        self.assertEqual('archive-and-sqlite-integrity-only', manifest['restoreAcceptance'])
        self.assertEqual(originals, {p.name: (backup.digest(p), p.stat().st_uid, p.stat().st_mode)
                                    for p in backup.AI_STATE.iterdir()})
        with closing(sqlite3.connect(directory / 'jobs.sqlite3')) as restored:
            for table in ('jobs', 'demo_reservations', 'text_admissions', 'demo_budget'):
                self.assertEqual(self.ledger.execute('SELECT * FROM ' + table).fetchall(),
                                 restored.execute('SELECT * FROM ' + table).fetchall())
        self.assertTrue((directory / 'media/empty').is_dir())
        self.assertFalse(any(p.name.endswith(('-wal', '-shm')) for p in directory.rglob('*')))
        self.assertEqual(b'old-core-fixture', (directory / 'dictalab.jar').read_bytes())
        self.assertEqual(8, sum(command[0] == 'systemctl' for command, _ in self.commands))

    def test_noninactive_or_uninstalled_service_rejected_before_backup(self):
        for state in ('LoadState=loaded\nActiveState=active\nMainPID=77\n',
                      'LoadState=not-found\nActiveState=inactive\nMainPID=0\n'):
            with self.subTest(state=state), patch.object(backup, 'run', return_value=state), \
                    self.assertRaisesRegex(RuntimeError, 'must be inactive'):
                self.create()
        self.assertEqual([], list(backup.SERVICE_BACKUPS.iterdir()))

    def test_writable_ca_is_rejected(self):
        self.ca.chmod(0o666)
        with self.assertRaisesRegex(ValueError, 'Database CA'):
            self.create()
        self.assertEqual([], list(backup.SERVICE_BACKUPS.iterdir()))

    def test_wrong_database_runtime_role_and_ledger_path_are_rejected(self):
        good = backup.SERVICE_CONFIG.read_text()
        for source, target in (('dbname=ai', 'dbname=other_ai'), ('user=ai_backup', 'user=ai_runtime')):
            self.private(backup.SERVICE_CONFIG, good.replace(source, target))
            with self.assertRaisesRegex(ValueError, 'match its runtime database'):
                self.create()
        self.private(backup.SERVICE_CONFIG, good)
        with (backup.SERVICE_ENVS / 'ai.env').open('a') as output:
            output.write('DICTALAB_AI_JOBS_DATABASE=/wrong/ledger.sqlite3\n')
        with self.assertRaises(ValueError):
            self.create()
        self.assertEqual([], list(backup.SERVICE_BACKUPS.iterdir()))

    def test_database_tls_identity_and_secret_isolation(self):
        with patch.dict(os.environ, {'PGPASSWORD': 'private', 'PGHOST': 'wrong', 'PGSSLMODE': 'disable'}):
            env = backup.service_environment('ai')
        self.assertNotIn('PGPASSWORD', env)
        self.assertNotIn('PGHOST', env)
        self.assertNotIn('PGSSLMODE', env)
        self.assertEqual('ai', env['PGSERVICE'])
        good = backup.SERVICE_CONFIG.read_text()
        for source, target in (('sslmode=verify-full', 'sslmode=require'),
                               ('channel_binding=require', 'channel_binding=prefer'),
                               ('dbname=progress', 'dbname=core'), ('user=progress_backup', 'user=core_backup'),
                               ('host=localhost', 'host=example-pooler.test')):
            with self.subTest(target=target):
                self.private(backup.SERVICE_CONFIG, good.replace(source, target))
                with self.assertRaises(ValueError):
                    self.create()
        self.assertEqual([], list(backup.SERVICE_BACKUPS.iterdir()))

    def test_iam_backup_uses_fresh_tokens_and_never_passes_secrets_in_arguments(self):
        host = 'cluster.example.ap-southeast-1.rds.amazonaws.com'
        credentials = self.root / 'aws-credentials'
        self.private(credentials, '[backup]\naws_access_key_id=synthetic-id\naws_secret_access_key=synthetic-key\n')
        text = backup.SERVICE_CONFIG.read_text().replace('host=localhost', 'host=' + host)
        text = text.replace('password=synthetic-test-password',
            f'auth=iam\nregion=ap-southeast-1\naws_profile=backup\naws_shared_credentials_file={credentials}')
        self.private(backup.SERVICE_CONFIG, text.replace('channel_binding=require', 'channel_binding=prefer'))
        for path in backup.SERVICE_ENVS.iterdir():
            if path.name.endswith('.env'):
                self.private(path, path.read_text().replace('localhost', host))
        sdk = Mock()
        signer = sdk.Session.return_value.client.return_value
        signer.generate_db_auth_token.side_effect = ['token-one', 'token-two']
        with patch.dict(sys.modules, {'boto3': sdk}), patch.dict(os.environ, {'PGHOST': 'wrong'}):
            first = backup.service_environment('ai')
            second = backup.service_environment('ai')
        self.assertEqual('token-one', first['PGPASSWORD'])
        self.assertEqual('token-two', second['PGPASSWORD'])
        self.assertEqual(host, second['PGHOST'])
        self.assertEqual('verify-full', second['PGSSLMODE'])
        self.assertNotIn('PGSERVICEFILE', second)
        self.assertEqual([], self.commands)
        credentials.chmod(0o644)
        with self.assertRaises(ValueError):
            backup.service_environment('ai')

    def test_private_permissions_and_symlinks_are_rejected(self):
        backup.SERVICE_CONFIG.chmod(0o644)
        with self.assertRaises(ValueError):
            self.create()
        backup.SERVICE_CONFIG.chmod(0o600)
        self.ledger.close()
        ledger = backup.AI_STATE / 'jobs.sqlite3'
        ledger.unlink()
        ledger.symlink_to(backup.CORE_JAR)
        with self.assertRaises(ValueError):
            self.create()
        self.assertTrue(all(not (p / 'manifest.json').exists() for p in backup.SERVICE_BACKUPS.iterdir()))

    def test_failed_dump_and_new_writer_never_publish_success(self):
        def failing(command, environment=None):
            if Path(command[0]).name == 'pg_dump':
                raise RuntimeError('fixture dump denied')
            return self.fake_run(command, environment)
        with patch.object(backup, 'run', side_effect=failing), self.assertRaises(RuntimeError):
            self.create()
        with patch.object(backup, 'services_stopped', side_effect=[None, RuntimeError('writer restarted')]), \
                self.assertRaises(RuntimeError):
            self.create()
        self.assertTrue(all(not (p / 'manifest.json').exists() for p in backup.SERVICE_BACKUPS.iterdir()))

    def test_verify_rejects_manifest_payload_and_membership_changes(self):
        for kind in ('manifest', 'payload', 'extra'):
            with self.subTest(kind=kind):
                directory, checksum = self.create()
                if kind == 'manifest':
                    (directory / 'manifest.json').write_bytes(b'{}')
                elif kind == 'payload':
                    (directory / 'ai.dump').write_bytes(b'changed')
                else:
                    self.private(directory / 'unexpected.env', 'fixture')
                with self.assertRaises(ValueError):
                    backup.verify_service_snapshot(directory, checksum)

    @unittest.skipUnless(os.environ.get('TEST_SERVICE_BACKUP_POSTGRES') == '1',
                         'Opt-in disposable PostgreSQL 18 + TLS restore rehearsal')
    def test_real_postgres_tls_dump_and_three_database_restore(self):
        import pwd
        uid, gid = (pwd.getpwnam('postgres').pw_uid, pwd.getpwnam('postgres').pw_gid)
        with tempfile.TemporaryDirectory(prefix='service-backup-postgres-', dir='/tmp') as temporary:
            cluster = Path(temporary)
            cluster.chmod(0o755)
            socket_dir = cluster / 'socket'
            socket_dir.mkdir(mode=0o700)
            os.chown(socket_dir, uid, gid)
            data = cluster / 'data'
            data.mkdir(mode=0o700)
            os.chown(data, uid, gid)
            def execute(command, **kwargs):
                return subprocess.run(command, capture_output=True, text=True, check=True,
                                      timeout=45, **kwargs).stdout
            execute(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                     '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                     '-keyout', str(cluster / 'server.key'), '-out', str(cluster / 'server.crt')])
            os.chown(cluster / 'server.key', uid, gid)
            (cluster / 'server.key').chmod(0o600)
            shutil.copyfile(cluster / 'server.crt', self.ca)
            pg = Path(shutil.which('pg_dump')).parent
            execute([str(pg / 'initdb'), '-D', str(data), '-U', 'postgres',
                     '--auth-local=trust', '--auth-host=scram-sha-256'], user=uid, group=gid, extra_groups=[])
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            self.configuration(port)
            with (cluster / 'server.log').open('w') as log:
                server = subprocess.Popen([str(pg / 'postgres'), '-D', str(data),
                    '-c', 'listen_addresses=127.0.0.1', '-c', 'port=' + str(port),
                    '-c', 'unix_socket_directories=' + str(socket_dir), '-c', 'ssl=on',
                    '-c', 'ssl_cert_file=' + str(cluster / 'server.crt'),
                    '-c', 'ssl_key_file=' + str(cluster / 'server.key')],
                    user=uid, group=gid, extra_groups=[], stdout=log, stderr=log)
                try:
                    deadline = time.monotonic() + 30
                    while subprocess.run([str(pg / 'pg_isready'), '-h', str(socket_dir), '-p', str(port),
                                          '-U', 'postgres'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                        if time.monotonic() > deadline or server.poll() is not None:
                            self.fail('Disposable PostgreSQL startup failed')
                        time.sleep(0.1)
                    def sql(database, statement):
                        return execute([str(pg / 'psql'), '-X', '-At', '-v', 'ON_ERROR_STOP=1',
                                        '-h', str(socket_dir), '-p', str(port), '-U', 'postgres', '-d', database],
                                       input=statement)
                    for name in ('core', 'ai', 'progress'):
                        sql('postgres', f"CREATE ROLE {name}_backup LOGIN PASSWORD 'synthetic-test-password';\n"
                            f'CREATE DATABASE {name} OWNER {name}_backup;\n'
                            f'CREATE DATABASE restored_{name} OWNER {name}_backup;')
                        sql(name, f'SET ROLE {name}_backup; CREATE TABLE preserved (id INTEGER PRIMARY KEY, value TEXT); '
                            f"INSERT INTO preserved VALUES (1, 'fixture-private-history-{name}'), (2, 'v1:encrypted-fixture-{name}');")
                    def actual_run(command, environment=None):
                        return self.fake_run(command, environment) if command[0] == 'systemctl' else REAL_RUN(command, environment)
                    with patch.object(backup, 'PG_BIN', pg), patch.object(backup, 'run', side_effect=actual_run):
                        directory, checksum = self.create()
                        backup.verify_service_snapshot(directory, checksum)
                        for name in ('core', 'ai', 'progress'):
                            REAL_RUN([str(pg / 'pg_restore'), '--exit-on-error', '--no-owner', '--no-acl',
                                      '--dbname=restored_' + name, str(directory / (name + '.dump'))],
                                     backup.service_environment(name))
                            query = 'SELECT id, value FROM preserved ORDER BY id;'
                            self.assertEqual(sql(name, query), sql('restored_' + name, query))
                    self.assertIsNone(server.poll())
                finally:
                    server.terminate()
                    try:
                        server.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
