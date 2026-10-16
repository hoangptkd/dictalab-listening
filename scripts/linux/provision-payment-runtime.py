"""Run once as root before the payment release. Secrets stay in protected app.env.

Existing database owner becomes Flyway-only; runtime receives DML without schema ownership.
No service restart or external payment is performed. Re-running preserves all credentials.
"""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tempfile
import shutil
from urllib.parse import urlparse


def provision():
    path = Path('/etc/dictalab/app.env')
    lines = path.read_text(encoding='utf-8').splitlines()
    config = {key: shlex.split(value)[0] if shlex.split(value) else ''
              for key, value in (line.split('=', 1) for line in lines if line and not line.startswith('#'))}
    owner = config.get('SPRING_FLYWAY_USER', config['DB_USERNAME'])
    password = config.get('SPRING_FLYWAY_PASSWORD', config['DB_PASSWORD'])
    runtime = 'dictalab_runtime'
    runtime_password = config['DB_PASSWORD'] if config['DB_USERNAME'] == runtime else secrets.token_urlsafe(48)
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}', owner) or owner == runtime:
        raise RuntimeError('A separate verified migration owner is required')
    url = urlparse(config['DB_URL'].removeprefix('jdbc:'))
    environment = os.environ.copy()
    environment.update(PGHOST=url.hostname, PGPORT=str(url.port or 5432), PGDATABASE=url.path.lstrip('/'),
                       PGUSER=owner, PGPASSWORD=password, PGSSLMODE='verify-full', PGCONNECT_TIMEOUT='10')
    # Reuse the deployment's verified TLS root settings instead of weakening verification.
    spec = importlib.util.spec_from_file_location('backup', '/opt/dictalab/tools/cloud-backup.py')
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    environment.update(backup.connection_environment())
    environment.update(PGUSER=owner, PGPASSWORD=password)
    def sql(command):
        result = subprocess.run(['/usr/lib/postgresql/18/bin/psql', '-XqAt', '-v', 'ON_ERROR_STOP=1'],
                                input=command, text=True, capture_output=True, env=environment, timeout=60)
        if result.returncode:
            raise RuntimeError('Runtime role provisioning failed; database details suppressed')
        return result.stdout.strip()
    quote = lambda value: "'" + value.replace("'", "''") + "'"
    sql("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dictalab_runtime') THEN CREATE ROLE dictalab_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF; END $$;")
    sql('ALTER ROLE dictalab_runtime PASSWORD ' + quote(runtime_password) + ';')
    sql('GRANT CONNECT ON DATABASE "' + url.path.lstrip('/').replace('"', '""') + '" TO dictalab_runtime;'
        'GRANT USAGE ON SCHEMA public TO dictalab_runtime;'
        'GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO dictalab_runtime;'
        'GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO dictalab_runtime;'
        f'ALTER DEFAULT PRIVILEGES FOR ROLE "{owner}" IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO dictalab_runtime;'
        f'ALTER DEFAULT PRIVILEGES FOR ROLE "{owner}" IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO dictalab_runtime;')
    # If re-run after migration, restore the financial least-privilege grants immediately.
    sql("""DO $$ BEGIN IF to_regclass('public.payment_events') IS NOT NULL THEN
        REVOKE UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES ON payment_events,payment_audit,payment_daily_closes,payment_receipt_sources,payment_allocations,premium_adjustments,refund_attempts,bank_evidence FROM dictalab_runtime;
        REVOKE DELETE,TRUNCATE,TRIGGER,REFERENCES ON ledger_journals,ledger_entries,payment_receipts FROM dictalab_runtime;
        REVOKE UPDATE ON ledger_journals,payment_receipts FROM dictalab_runtime;
        GRANT UPDATE(status) ON ledger_journals TO dictalab_runtime;
        GRANT UPDATE(bank_verified) ON payment_receipts TO dictalab_runtime;
        END IF; END $$;""")
    updates = {'DB_USERNAME': runtime, 'DB_PASSWORD': runtime_password,
               'SPRING_FLYWAY_USER': owner, 'SPRING_FLYWAY_PASSWORD': password,
               'PAYMENT_ENCRYPTION_KEY': config.get('PAYMENT_ENCRYPTION_KEY') or base64.b64encode(secrets.token_bytes(32)).decode(),
               'PAYMENT_BOOTSTRAP_EMAIL': 'hoangptkdhy@gmail.com',
               'PAYMENT_REFUNDS_ENABLED': 'false', 'PAYMENT_PARTIAL_REFUNDS_ENABLED': 'false'}
    content = '\n'.join(line for line in lines if line.split('=', 1)[0] not in updates) + '\n'
    content += '\n'.join(key + '=' + shlex.quote(value) for key, value in updates.items()) + '\n'
    os.umask(0o077)
    snapshot=Path(tempfile.mkdtemp(prefix='payment-config-',dir='/var/lib/dictalab/backups'))
    shutil.copy2(path,snapshot/'app.env')
    os.chmod(snapshot/'app.env',0o600)
    descriptor, temporary = tempfile.mkstemp(prefix='.payment-config-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if Path(temporary).exists():
            Path(temporary).unlink()
    print(json.dumps({'runtimeRole': runtime, 'migrationOwnerSeparate': True,
                      'refundsEnabled': False, 'configMode': oct(path.stat().st_mode & 0o777),'configBackup':str(snapshot)}))


if __name__ == '__main__':
    if os.getuid() != 0:
        raise RuntimeError('Root is required to protect runtime configuration')
    provision()
