"""Read-only Neon fingerprints. Credentials are inherited, never passed as process arguments."""
import json
import os
import pathlib
import shlex
import subprocess
import urllib.parse

environment = {}
for line in pathlib.Path('/etc/dictalab/app.env').read_text(encoding='utf-8').splitlines():
    if line and not line.startswith('#'):
        key, value = line.split('=', 1)
        environment[key] = shlex.split(value)[0] if value != '""' else ''
url = urllib.parse.urlsplit(environment['DB_URL'].removeprefix('jdbc:'))
env = os.environ.copy()
env.update(PGHOST=url.hostname.replace('-pooler.', '.'), PGPORT=str(url.port or 5432),
           PGDATABASE=url.path.lstrip('/'), PGUSER=environment['DB_USERNAME'],
           PGPASSWORD=environment['DB_PASSWORD'], PGSSLMODE='verify-full',
           PGSSLROOTCERT='/etc/ssl/certs/ca-certificates.crt', PGCHANNELBINDING='require', PGCONNECT_TIMEOUT='15')
result = subprocess.run(['psql', '-X', '-qAt', '-f', str(pathlib.Path(__file__).with_name('cloud-fingerprint.sql'))],
                        env=env, capture_output=True, text=True, timeout=90)
if result.returncode:
    raise SystemExit('Cloud fingerprint failed; credential values suppressed')
rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
if not rows or len(rows) < 2:
    raise SystemExit('Cloud fingerprint returned incomplete evidence')
print(json.dumps(rows, sort_keys=True))
