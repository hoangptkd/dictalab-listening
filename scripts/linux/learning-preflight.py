"""Read-only deployment evidence; never print connection settings or credentials."""
import importlib.util
import json
import pathlib
import shlex
import subprocess
import urllib.parse

settings = {}
for line in pathlib.Path('/etc/dictalab/app.env').read_text().splitlines():
    if line and not line.startswith('#'):
        name, value = line.split('=', 1)
        settings[name] = shlex.split(value)[0]
spec = importlib.util.spec_from_file_location('backup', '/opt/dictalab/tools/cloud-backup.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
query = """
SELECT json_build_object('version',version(),
 'flyway',(SELECT max(installed_rank) FROM flyway_schema_history),
 'users',(SELECT count(*) FROM app_users),
 'sentences',(SELECT count(*) FROM lesson_sentences),
 'attempts',(SELECT count(*) FROM word_attempts),
 'schemaBytes',pg_database_size(current_database()));
"""
result = subprocess.run(['/usr/lib/postgresql/18/bin/psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-c', query],
                        env=backup.connection_environment(), capture_output=True, text=True, timeout=30)
if result.returncode:
    raise RuntimeError('Preflight DB read failed; output suppressed')
data = json.loads(result.stdout)
data['learningEnabled'] = settings.get('DICTALAB_LEARNING_ENABLED', 'false')
data['learningQueueEnabled'] = settings.get('DICTALAB_LEARNING_QUEUE_ENABLED', 'false')
data['rabbitConfigured'] = bool(settings.get('RABBITMQ_URL') or settings.get('RABBITMQ_HOST'))
if settings.get('RABBITMQ_URL'):
    address = urllib.parse.urlsplit(settings['RABBITMQ_URL'])
    data['rabbitHost'] = address.hostname
    data['rabbitPort'] = address.port
else:
    data['rabbitHost'] = settings.get('RABBITMQ_HOST', 'localhost')
    data['rabbitPort'] = settings.get('RABBITMQ_PORT', '15672')
print(json.dumps(data))
