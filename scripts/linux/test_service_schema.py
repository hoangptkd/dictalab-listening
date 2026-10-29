"""Rehearse the actual owner Flyway/grant program on disposable PostgreSQL 17."""
import importlib.util
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('service_schema', Path(__file__).with_name('service-schema.py'))
schema = importlib.util.module_from_spec(spec)
spec.loader.exec_module(schema)


class SchemaTest(unittest.TestCase):
    def test_extract_rejects_traversal_and_missing_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, member in enumerate(('BOOT-INF/lib/../../../escaped.jar', 'unexpected.txt')):
                jar = root / (str(index) + '.jar')
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(member, b'payload')
                with self.assertRaises(ValueError):
                    schema.extract(jar, root / str(index))
            self.assertFalse((root / 'escaped.jar').exists())

    def test_extract_preserves_sql_and_compiled_java_migrations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / 'sample.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/lib/runtime.jar', b'jar-payload')
                archive.writestr('BOOT-INF/classes/db/migration/V1__init.sql', b'select 1;')
                archive.writestr('BOOT-INF/classes/db/migration/V24__Postgres_uniqueness.class', b'bytecode')
            destination = root / 'extracted'
            schema.extract(jar, destination)
            self.assertTrue((destination / 'classes/db/migration/V1__init.sql').is_file())
            self.assertTrue((destination / 'classes/db/migration/V24__Postgres_uniqueness.class').is_file())

    @unittest.skipUnless(os.environ.get('TEST_CORE_SCHEMA_JAR'), 'Opt-in real owner migration rehearsal')
    def test_owner_migration_grants_and_repeat_run_keep_runtime_isolated(self):
        jar = Path(os.environ['TEST_CORE_SCHEMA_JAR']).resolve(strict=True)
        name = 'dictalab-schema-' + secrets.token_hex(4)
        password = secrets.token_urlsafe(32)
        def run(arguments, **kwargs):
            result = subprocess.run(arguments, capture_output=True, timeout=180, **kwargs)
            self.assertEqual(0, result.returncode, result.stderr.decode(errors='replace')[:2000])
            return result.stdout.decode().strip()
        run(['docker', 'run', '--detach', '--name', name, '--publish', '127.0.0.1::5432',
             '--env', 'POSTGRES_PASSWORD', 'postgres:17-alpine'], env=os.environ | {'POSTGRES_PASSWORD': password})
        try:
            for _ in range(60):
                if subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                                  capture_output=True).returncode == 0:
                    break
                time.sleep(.5)
            else:
                self.fail('PostgreSQL did not accept TCP connections within 30 seconds')
            def sql(text, database='postgres'):
                return run(['docker', 'exec', '-i', '--user', 'postgres', name, 'psql', '-XqAt',
                            '-v', 'ON_ERROR_STOP=1', database], input=text.encode())
            sql("CREATE ROLE dictalab_core_owner LOGIN PASSWORD '" + password + "';"
                "CREATE ROLE dictalab_core_runtime LOGIN; CREATE ROLE dictalab_core_backup LOGIN;"
                "CREATE DATABASE dictalab_core OWNER dictalab_core_owner;")
            port = run(['docker', 'port', name, '5432/tcp']).split(':')[-1]
            with tempfile.TemporaryDirectory() as temporary:
                environment = os.environ | {'PGHOST': '127.0.0.1', 'PGPORT': port, 'PGPASSWORD': password}
                def program(folder):
                    directory = Path(temporary) / folder
                    directory.mkdir()
                    schema.extract(jar, directory)
                    # Only the disposable loopback PostgreSQL server lacks TLS.
                    source = directory / 'CoreSchema.java'
                    source.write_text(schema.JAVA.replace('sslmode=verify-full', 'sslmode=disable'), encoding='utf-8')
                    return directory, [shutil.which('java'), '--class-path', str(directory / 'lib/*') + os.pathsep
                                       + str(directory / 'classes'), str(source)]
                baseline, command = program('baseline45')
                for filename in ('V46__vocabulary.sql', 'V47__vocabulary_starter_content.sql'):
                    (baseline / 'classes/db/migration' / filename).unlink()
                self.assertIn('CORE_SCHEMA_READY', run(command, env=environment))
                checksum_sql = "SELECT version,checksum FROM flyway_schema_history WHERE version::int <= 45 ORDER BY installed_rank;"
                before = sql(checksum_sql, 'dictalab_core')
                _, command = program('candidate47')
                for _ in range(2):
                    self.assertIn('CORE_SCHEMA_READY', run(command, env=environment))
                self.assertEqual(before, sql(checksum_sql, 'dictalab_core'))
            checks = sql("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL;"
                "SELECT has_table_privilege('dictalab_core_runtime','vocab_cards','INSERT'),"
                "has_table_privilege('dictalab_core_backup','vocab_cards','SELECT'),"
                "has_table_privilege('dictalab_core_backup','vocab_cards','UPDATE');"
                "SELECT has_table_privilege('dictalab_core_runtime','flyway_schema_history','SELECT'),"
                "has_table_privilege('dictalab_core_runtime','flyway_schema_history','UPDATE'),"
                "has_table_privilege('dictalab_core_runtime','ai_provider_accounts','SELECT'),"
                "has_schema_privilege('dictalab_core_runtime','public','CREATE');", 'dictalab_core')
            self.assertEqual(['47', 't|t|f', 't|f|f|f'], checks.splitlines())
        finally:
            run(['docker', 'rm', '--force', name])


if __name__ == '__main__':
    unittest.main()
