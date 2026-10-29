"""Run Core Flyway as the protected Aurora owner; runtime never receives DDL access."""
import configparser
import importlib.util
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location('schema_backup', Path(__file__).with_name('cloud-backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
CREDENTIALS = Path('/etc/dictalab/database/migration.aws-credentials')

JAVA = r'''
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
class CoreSchema {
    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        String url = "jdbc:postgresql://" + env.get("PGHOST") + ":" + env.get("PGPORT")
            + "/dictalab_core?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
            + "&connectTimeout=30&socketTimeout=120&options=-c%20lock_timeout%3D3000%20-c%20statement_timeout%3D120000";
        String user = "dictalab_core_owner", password = env.get("PGPASSWORD");
        Class.forName("org.postgresql.Driver");
        var flyway = Flyway.configure().dataSource(url, user, password).cleanDisabled(true)
            .locations("classpath:db/migration", "classpath:db/vendor/postgresql").load();
        flyway.migrate();
        // Reapply the Core portion of ops/aws/grants.sql as the owner, never as runtime.
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO dictalab_core_runtime");
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_core_runtime");
            statement.execute("REVOKE ALL ON ai_provider_pools, ai_provider_accounts, flyway_schema_history FROM dictalab_core_runtime");
            statement.execute("GRANT SELECT ON flyway_schema_history TO dictalab_core_runtime");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO dictalab_core_backup");
            statement.execute("GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_core_backup");
            connection.commit();
        }
        flyway.validate();
        if (flyway.info().pending().length != 0) throw new IllegalStateException("Pending Core migrations");
        System.out.println("CORE_SCHEMA_READY");
    }
}
'''


def extract(jar, destination):
    """Extract only verified JAR migrations and libraries into a private temporary directory."""
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            relative = None
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                relative = Path('lib') / name.removeprefix('BOOT-INF/lib/')
            elif name.startswith('BOOT-INF/classes/db/') and (name.endswith('.sql') or name.endswith('.class')):
                relative = Path('classes') / name.removeprefix('BOOT-INF/classes/')
            if relative is None:
                continue
            if '\\' in name or '..' in relative.parts or relative.is_absolute():
                raise ValueError('Unsafe JAR member')
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with target.open('xb') as output:
                output.write(archive.read(name))
    if not (destination / 'classes/db/migration').is_dir() or not (destination / 'lib').is_dir():
        raise ValueError('Core migration payload missing')


def migrate(jar, evidence):
    backup.services_stopped()
    backup.protected(CREDENTIALS)
    if CREDENTIALS.stat().st_mode & 0o077:
        raise ValueError('Migration credential must be private to root')
    host, port, database, runtime = backup.runtime_database('core')
    if (database != 'dictalab_core' or runtime != 'dictalab_core_runtime'
            or not re.fullmatch(r'[a-zA-Z0-9.-]+', host) or port != '5432'):
        raise ValueError('Expected isolated Aurora Core identity')
    config = configparser.ConfigParser(interpolation=None)
    config.read(CREDENTIALS)
    identity = config['migration']
    import boto3
    client = boto3.client('rds', region_name='ap-southeast-1',
        aws_access_key_id=identity['aws_access_key_id'], aws_secret_access_key=identity['aws_secret_access_key'])
    environment = {'PATH': '/usr/bin:/bin', 'HOME': '/nonexistent', 'LANG': 'C.UTF-8',
        'PGHOST': host, 'PGPORT': port,
        'PGPASSWORD': client.generate_db_auth_token(DBHostname=host, Port=5432, DBUsername='dictalab_core_owner')}
    with tempfile.TemporaryDirectory(prefix='schema-', dir=evidence) as temporary:
        directory = Path(temporary)
        extract(jar, directory)
        source = directory / 'CoreSchema.java'
        source.write_text(JAVA, encoding='utf-8')
        with (evidence / 'core-schema.log').open('xb') as output:
            result = subprocess.run(['/usr/bin/java', '--class-path', str(directory / 'lib/*') + ':'
                + str(directory / 'classes'), str(source)], env=environment,
                stdout=output, stderr=output, timeout=300)
    if result.returncode:
        raise RuntimeError('Core migration failed; inspect protected schema log; no database restore attempted')
    return {'coreSchema': 'migrated-and-granted', 'identity': 'dictalab_core_owner'}
