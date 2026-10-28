"""Bounded Linux-container stack rehearsal with disposable data and an unpaid provider.

Requires built Core/Progress JARs, Docker Engine and k6. Does not read project dotenv
files or contact production. Existing core.js journeys retain auth/CSRF/rate limits.
"""
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import traceback
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
JARS = {'core': ROOT / 'target/english-dictation-0.1.0-SNAPSHOT.jar',
        'progress': ROOT / 'services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar'}


def fake_provider():
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send(self, value):
            raw = json.dumps(value).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            self.send({'data': [{'id': 'fixture-cloud'}]})

        def do_POST(self):
            size = int(self.headers.get('Content-Length', '0'))
            if self.path != '/v1/chat/completions' or not 0 < size <= 200000:
                self.send_error(422)
                return
            self.connection.settimeout(5)
            request = json.loads(self.rfile.read(size))
            try:
                payload = json.loads(request['messages'][-1]['content'])
            except (ValueError, KeyError, TypeError):
                payload = {}
            answer = {'focus': None, 'reviewIds': []} if payload.get('kind') == 'coach' else {'answer': 'synthetic'}
            time.sleep(0.05)
            self.send({'choices': [{'message': {'content': json.dumps(answer)}}],
                       'usage': {'prompt_tokens': 64, 'completion_tokens': 8}})

    ThreadingHTTPServer(('127.0.0.1', 18380), Handler).serve_forever()


def memory_bytes(value):
    parsed = re.fullmatch(r'([0-9]+(?:\.[0-9]+)?)\s*(B|KiB|MiB|GiB)', value.strip())
    if parsed is None:
        raise ValueError('Invalid Docker memory measurement')
    number, unit = parsed.groups()
    return round(float(number) * {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3}[unit])


def summarize(samples, required):
    complete = [s for s in samples if set(s['memoryBytes']) == set(required)]
    if not complete:
        raise ValueError('No simultaneous sample covers every component')
    return {'completeSamples': len(complete),
            'simultaneousPeakBytes': max(sum(s['memoryBytes'].values()) for s in complete),
            'componentPeakBytes': {name: max(s['memoryBytes'][name] for s in samples if name in s['memoryBytes'])
                                   for name in required}}


class Rehearsal:
    def __init__(self, output):
        self.output = output
        self.prefix = 'dictalab-stack-' + uuid4().hex[:10]
        self.containers = {}
        self.network = False
        self.volume = False

    def docker(self, *args, env=None, timeout=120):
        result = subprocess.run(['docker', *args], env=env, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            (self.output / 'docker-error.log').write_text(result.stderr, encoding='utf-8')
            raise RuntimeError('Docker rehearsal command failed; inspect protected rehearsal logs')
        return result.stdout.strip()

    def start(self, name, image, arguments=(), config=None, options=()):
        full = self.prefix + '-' + name
        config = config or {}
        env = dict(os.environ) | config
        self.docker('run', '-d', '--name', full, '--label', 'dictalab.rehearsal=' + self.prefix,
                    *options, *[arg for key in config for arg in ('-e', key)], image, *arguments, env=env)
        self.containers[name] = full
        return full

    def port(self, name, port):
        return int(self.docker('port', self.containers[name], str(port)).rsplit(':', 1)[1])

    def cleanup(self):
        failures = []
        for full in reversed(list(self.containers.values())):
            try:
                label = self.docker('inspect', '--format', '{{index .Config.Labels "dictalab.rehearsal"}}', full)
                if label != self.prefix:
                    raise RuntimeError('Container ownership changed')
                result = subprocess.run(['docker', 'logs', full], capture_output=True, timeout=15)
                (self.output / (full + '.log')).write_bytes(result.stdout + result.stderr)
                self.docker('rm', '-f', full)
            except Exception:
                failures.append(full)
        for kind, created in (('volume', self.volume), ('network', self.network)):
            if created:
                try:
                    self.docker(kind, 'rm', self.prefix)
                except Exception:
                    failures.append(kind)
        if failures:
            raise RuntimeError('Some owned rehearsal resources remain; inspect protected evidence')


def main(args):
    import httpx
    import psycopg
    from psycopg import sql
    sys.path.insert(0, str(ROOT / 'services/ai-generation'))
    from app.registry_migration import initialize
    from app.provider_registry import connect, save_pool, save_source
    if any(not path.is_file() for path in JARS.values()) or not shutil.which('k6'):
        raise ValueError('Build Core/Progress JARs and install k6 first')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    if os.name == 'nt':
        import getpass
        subprocess.run(['icacls', str(output), '/inheritance:r', '/grant:r', getpass.getuser()+':(OI)(CI)F',
                        '*S-1-5-18:(OI)(CI)F'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run = Rehearsal(output)
    samples, stages, errors = [], [], []
    finished = threading.Event()
    sampler = ai_thread = None
    ai_finished = threading.Event()
    ai_completed, ai_errors = [], []
    report = {'schemaVersion': 1, 'passed': False, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'scope': 'Docker Linux; mixed practice/dashboard plus concurrent synthetic cloud Coach jobs; CPU model excluded',
              'artifactSha256': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in JARS.items()},
              'gitCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()}
    password, internal = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    role_passwords = {name: secrets.token_urlsafe(32) for name in ('fixture', 'ai_owner', 'ai_runtime', 'progress_owner')}
    broker_password, learner_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    try:
        run.docker('info', '--format', '{{.ServerVersion}}')
        report['dockerHost'] = json.loads(run.docker('info', '--format',
            '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"architecture":"{{.Architecture}}"}'))
        run.docker('build', '-f', 'services/ai-generation/Dockerfile', '-t', 'dictalab-ai-check', '.', timeout=600)
        run.docker('image', 'inspect', 'dictalab-progress:local')
        run.docker('network', 'create', run.prefix)
        run.network = True
        run.docker('volume', 'create', run.prefix)
        run.volume = True
        network = ['--network', run.prefix]
        run.start('postgres', 'postgres:18-alpine', config={'POSTGRES_PASSWORD': password},
                  options=[*network, '--network-alias', 'postgres', '--memory', '256m', '-p', '127.0.0.1::5432'])
        run.start('redis', 'redis:8.10.2-alpine', ['redis-server', '--maxmemory-policy', 'noeviction'],
                  options=[*network, '--network-alias', 'redis', '--memory', '64m'])
        run.start('rabbit', 'rabbitmq:4.3.6-alpine',
                  config={'RABBITMQ_DEFAULT_USER': 'fixture', 'RABBITMQ_DEFAULT_PASS': broker_password,
                          'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS': '+S 2:2 +A 2'},
                  options=[*network, '--network-alias', 'rabbit', '--memory', '256m'])
        run.start('provider', 'dictalab-ai-check', ['python', '/check/tools/ai-eval/stack_capacity.py', '--fake-provider'],
                  options=[*network, '--memory', '64m', '--mount', f'type=bind,source={Path(__file__).resolve()},target=/check/tools/ai-eval/stack_capacity.py,readonly',
                           '-p', '127.0.0.1::18081', '-p', '127.0.0.1::8091', '-p', '127.0.0.1::8092'])
        pg_port = run.port('postgres', 5432)
        local = f'postgresql://fixture:{role_passwords["fixture"]}@127.0.0.1:{pg_port}/'
        deadline = time.monotonic() + 60
        while True:
            try:
                admin = psycopg.connect(f'postgresql://postgres:{password}@127.0.0.1:{pg_port}/postgres', autocommit=True, connect_timeout=2)
                break
            except psycopg.OperationalError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Fixture database readiness timeout') from None
                time.sleep(0.25)
        with admin:
            for role, role_password in role_passwords.items():
                admin.execute(sql.SQL('CREATE ROLE {} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD {}')
                              .format(sql.Identifier(role), sql.Literal(role_password)))
            for database, role in (('dictalab_capacity', 'fixture'), ('ai', 'ai_owner'), ('progress', 'progress_owner')):
                admin.execute(sql.SQL('CREATE DATABASE {} OWNER {}').format(sql.Identifier(database), sql.Identifier(role)))
                admin.execute(sql.SQL('REVOKE CONNECT ON DATABASE {} FROM PUBLIC').format(sql.Identifier(database)))
            admin.execute('GRANT CONNECT ON DATABASE ai TO ai_runtime')
        for role, database in (('fixture', 'ai'), ('ai_runtime', 'dictalab_capacity'), ('progress_owner', 'dictalab_capacity')):
            try:
                with psycopg.connect(f'postgresql://{role}:{role_passwords[role]}@127.0.0.1:{pg_port}/{database}', connect_timeout=2):
                    raise AssertionError('Cross-service database access was allowed')
            except psycopg.OperationalError:
                pass
        owner_dsn = f'postgresql://ai_owner:{role_passwords["ai_owner"]}@127.0.0.1:{pg_port}/ai'
        initialize(owner_dsn)
        master = base64.b64encode(secrets.token_bytes(32)).decode()
        with connect(owner_dsn, readonly=False) as db:
            save_pool(db, 'openai_compatible', 'fixture-cloud', 240, 10000, True)
            save_source(db, master, dict(id='fixture', platform='openai_compatible', projectId='fixture',
                model='fixture-cloud', apiKey='synthetic-provider-key', enabled=True, rpmLimit=240, operatingRpm=240,
                url='http://127.0.0.1:18380/v1/chat/completions'))
            db.execute('GRANT USAGE ON SCHEMA public TO ai_runtime')
            db.execute('GRANT SELECT ON ai_registry_schema,ai_provider_pools,ai_provider_accounts TO ai_runtime')
        shared = ['--network', 'container:' + run.containers['provider'], '--init', '--read-only', '--tmpfs', '/tmp']
        ai = {'DICTALAB_AI_PRIMARY_PROVIDER': 'openai_compatible',
              'DICTALAB_AI_PRODUCTION_MODE': 'true', 'DICTALAB_AI_CONVERSATION_ENABLED': 'false',
              'DICTALAB_AI_SERVICE_TOKEN': internal, 'GEMINI_QUOTA_REDIS_URL': 'redis://redis:6379/0',
              'DICTALAB_AI_TEXT_GATEWAY_ENABLED': 'true', 'DICTALAB_AI_JOBS_ENABLED': 'true',
              'DICTALAB_AI_JOBS_DATABASE': '/data/jobs.sqlite3', 'DICTALAB_AI_RABBITMQ_URL': f'amqp://fixture:{broker_password}@rabbit:5672/%2F',
              'DICTALAB_AI_REGISTRY_OWNERSHIP': 'private', 'AI_PROVIDER_CONFIG_SOURCE': 'database',
              'AI_PROVIDER_DATABASE_URL': f'postgresql://ai_runtime:{role_passwords["ai_runtime"]}@postgres:5432/ai',
              'AI_PROVIDER_ENCRYPTION_KEY': master,
              'DICTALAB_OPERATIONAL_LIMITS_ENABLED': 'true'}
        run.start('ai', 'dictalab-ai-check', config=ai, options=[*shared, '--memory', '256m', '-v', run.prefix + ':/data'])
        run.start('worker', 'dictalab-ai-check', ['python', '-m', 'app.worker'], config=ai,
                  options=[*shared, '--memory', '256m', '-v', run.prefix + ':/data'])
        run.start('progress', 'dictalab-progress:local', config={'PROGRESS_BIND_ADDRESS': '0.0.0.0', 'PROGRESS_DB_URL': 'jdbc:postgresql://postgres:5432/progress',
                  'PROGRESS_DB_USERNAME': 'progress_owner', 'PROGRESS_DB_PASSWORD': role_passwords['progress_owner'], 'PROGRESS_SERVICE_TOKEN': internal,
                  'RABBITMQ_HOST': 'rabbit', 'RABBITMQ_PORT': '5672', 'RABBITMQ_USER': 'fixture', 'RABBITMQ_PASSWORD': broker_password},
                  options=[*shared, '--memory', '384m', '--mount', f'type=bind,source={JARS["progress"]},target=/app/progress.jar,readonly'])
        core = {'DB_URL': 'jdbc:postgresql://postgres:5432/dictalab_capacity', 'DB_USERNAME': 'fixture', 'DB_PASSWORD': role_passwords['fixture'],
                'REDIS_URL': 'redis://redis:6379/0', 'RABBITMQ_HOST': 'rabbit', 'RABBITMQ_PORT': '5672',
                'RABBITMQ_USER': 'fixture', 'RABBITMQ_PASSWORD': broker_password, 'JWT_SIGNING_KEY': base64.b64encode(secrets.token_bytes(64)).decode(),
                'JWT_COOKIE_SECURE': 'false', 'SPRING_PROFILES_ACTIVE': 'capacity', 'CAPACITY_PASSWORD': learner_password,
                'CAPACITY_ACCOUNTS': str(args.users), 'CAPACITY_SESSIONS_PER_USER': '0', 'DB_POOL_SIZE': '5',
                'DICTALAB_OPERATIONAL_LIMITS_ENABLED': 'true', 'RATE_LIMIT_ENABLED': 'true', 'HTTP_THREADS': '32',
                'DICTALAB_PROGRESS_ENABLED': 'true', 'DICTALAB_PROGRESS_READ_MODE': 'remote', 'PROGRESS_SERVICE_TOKEN': internal,
                'DICTALAB_AI_SERVICE_TOKEN': internal, 'DICTALAB_AI_TEXT_TRANSPORT': 'python',
                'DICTALAB_LOG_FILE': '/tmp/dictalab.json', 'DICTALAB_MEDIA_ROOT': '/tmp/media'}
        run.start('core', 'dictalab-progress:local', ['-Xmx256m', '-XX:MaxMetaspaceSize=160m', '-jar', '/app/core.jar'], config=core,
                  options=[*shared, '--memory', '640m', '--entrypoint', 'java', '--mount', f'type=bind,source={JARS["core"]},target=/app/core.jar,readonly'])

        def sample():
            while not finished.is_set():
                try:
                    reverse = {full: name for name, full in run.containers.items()}
                    rows = run.docker('stats', '--no-stream', '--format', '{{json .}}', *reverse, timeout=12)
                    rows = list(map(json.loads, rows.splitlines()))
                    measured = {reverse[r['Name']]: memory_bytes(r['MemUsage'].split('/')[0]) for r in rows}
                    samples.append({'at': datetime.now(timezone.utc).isoformat(), 'memoryBytes': measured,
                                    'cpuPercent': {reverse[r['Name']]: float(r['CPUPerc'].rstrip('%')) for r in rows}})
                except Exception:
                    errors.append('sample_unavailable')
                finished.wait(2)

        sampler = threading.Thread(target=sample, daemon=True)
        sampler.start()
        base = f'http://127.0.0.1:{run.port("provider", 18081)}'
        ai_url = f'http://127.0.0.1:{run.port("provider", 8091)}'
        progress_url = f'http://127.0.0.1:{run.port("provider", 8092)}'
        with httpx.Client(timeout=3, trust_env=False) as client:
            deadline = time.monotonic() + 180
            pending = [base + '/actuator/health', ai_url + '/health/ready', progress_url + '/actuator/health/readiness']
            while pending:
                running = run.docker('inspect', '--format', '{{.State.Running}}', *run.containers.values())
                if any(state != 'true' for state in running.splitlines()):
                    raise RuntimeError('A rehearsal component exited during startup')
                for url in pending[:]:
                    try:
                        if client.get(url).status_code == 200:
                            pending.remove(url)
                    except httpx.HTTPError:
                        pass
                if time.monotonic() >= deadline:
                    raise RuntimeError('Stack startup timeout')
                time.sleep(0.5)
            assert client.get(base + '/api/v1/dashboard').status_code == 401
            assert client.post(ai_url + '/v1/text/completions', json={}).status_code == 401
            assert client.get(progress_url + '/internal/v1/users/1/dashboard').status_code == 401
        with psycopg.connect(local + 'dictalab_capacity') as db:
            db.execute('UPDATE lessons SET active=FALSE')
            lesson = db.execute("""INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,
                active,content_origin,created_at,updated_at,speaker_count,vocabulary_difficulty,grammar_difficulty,
                sentence_difficulty,assessment_calibrated) VALUES
                ('stack-fixture','Synthetic rehearsal','Synthetic rehearsal','A1','Rehearsal',1,0,TRUE,'ADMIN_UPLOAD',
                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,1,1,1,FALSE) RETURNING id""").fetchone()[0]
            db.execute("INSERT INTO lesson_sentences(lesson_id,position_index,transcript,speech_rate) VALUES (%s,1,'Please meet me tomorrow.',1)", (lesson,))
        print('Disposable Linux stack ready; beginning bounded, unpaid mixed load.', flush=True)
        def ai_workload():
            try:
                with httpx.Client(timeout=10, trust_env=False) as client:
                    while not ai_finished.is_set():
                        job_id, request_id = str(uuid4()), str(uuid4())
                        headers = {'X-Service-Token': internal, 'X-User-Scope': 'a' * 64,
                                   'X-Request-Id': request_id, 'Idempotency-Key': job_id, 'X-AI-Provider': 'openai_compatible'}
                        response = client.post(ai_url + '/v1/coach/reports', headers=headers, json={
                            'jobId': job_id, 'requestId': request_id, 'idempotencyKey': job_id, 'userScope': 'a' * 64,
                            'deadline': (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat(),
                            'payload': {'kind': 'coach', 'level': 'A1', 'candidates': []}})
                        assert response.status_code == 202
                        end = time.monotonic() + 15
                        while True:
                            result = client.get(ai_url + '/v1/jobs/' + job_id, headers=headers)
                            assert result.status_code == 200
                            state = result.json()
                            if state['status'] == 'DONE':
                                assert state['result']['task'] == {'focus': None, 'reviewIds': []}
                                ai_completed.append(job_id)
                                break
                            assert state['status'] not in ('FAILED', 'EXPIRED', 'DEAD') and time.monotonic() < end
                            time.sleep(0.2)
                        ai_finished.wait(1)
            except Exception as error:
                ai_errors.append(type(error).__name__)
        ai_thread = threading.Thread(target=ai_workload, daemon=True)
        ai_thread.start()
        env = {key: value for key, value in os.environ.items() if key.upper() in ('PATH', 'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE')}
        env |= {'BASE_URL': base, 'CAPACITY_PASSWORD': learner_password, 'USERS': str(args.users), 'SECONDS': str(args.seconds),
                'MODE': 'mixed', 'THINK_SECONDS': '1', 'K6_NO_USAGE_REPORT': 'true'}
        with (output / 'k6.log').open('w', encoding='utf-8') as log:
            load = subprocess.run(['k6', 'run', '--summary-export', str(output / 'k6.json'), str(ROOT / 'scripts/load/core.js')],
                                  env=env, stdout=log, stderr=log, timeout=args.seconds + 180)
        stages.append({'users': args.users, 'seconds': args.seconds, 'k6Exit': load.returncode})
        assert load.returncode == 0, 'Mixed workload thresholds failed'
        ai_finished.set()
        ai_thread.join(timeout=20)
        assert not ai_thread.is_alive() and not ai_errors and ai_completed, 'Concurrent AI job workload failed'
        with httpx.Client(timeout=10, trust_env=False) as client:
            result = client.post(ai_url + '/v1/text/completions', headers={'X-Service-Token': internal}, json={
                'requestId': str(uuid4()), 'provider': 'openai_compatible', 'model': 'fixture-cloud', 'instruction': 'Return JSON.',
                'input': 'Synthetic rehearsal.', 'deadline': (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()})
            assert result.status_code == 200 and result.json()['task'] == {'answer': 'synthetic'}
        deadline = time.monotonic() + 30
        with psycopg.connect(local + 'dictalab_capacity', autocommit=True) as db:
            while True:
                completed = db.execute("SELECT count(*) FROM practice_sessions WHERE status='COMPLETED'").fetchone()[0]
                pending = db.execute("SELECT count(*) FROM progress_outbox WHERE status<>'SENT'").fetchone()[0]
                if completed > 0 and pending == 0:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Completed practices or drained outbox evidence missing')
                time.sleep(1)
        with httpx.Client(timeout=5, trust_env=False) as client:
            with psycopg.connect(local + 'dictalab_capacity') as db:
                users = db.execute("SELECT id FROM app_users WHERE email LIKE 'capacity-%@loadtest.local' ORDER BY id").fetchall()
                expected = dict(db.execute("SELECT user_id,count(*) FROM practice_sessions WHERE status='COMPLETED' GROUP BY user_id").fetchall())
            projected = 0
            for (user_id,) in users:
                response = client.get(progress_url + f'/internal/v1/users/{user_id}/dashboard', headers={'X-Service-Token': internal})
                assert response.status_code == 200
                count = response.json()['summary']['completedLessons']
                assert count == expected.get(user_id, 0), 'Per-user projection accounting mismatch'
                projected += count
            assert projected == completed, 'Progress has not caught up with completed practice history'
        state = {name: json.loads(run.docker('inspect', '--format', '{{json .State}}', full)) for name, full in run.containers.items()}
        assert all(row['Running'] and not row['OOMKilled'] for row in state.values())
        cgroups = {}
        for name, full in run.containers.items():
            lines = run.docker('exec', full, 'sh', '-c', 'cat /sys/fs/cgroup/memory.peak; cat /sys/fs/cgroup/memory.events').splitlines()
            cgroups[name] = {'peakBytesIncludingCache': int(lines[0]),
                            'events': {k: int(v) for k, v in (line.split() for line in lines[1:])},
                            'limitBytes': int(run.docker('inspect', '--format', '{{.HostConfig.Memory}}', full))}
        assert all(cgroup['events']['oom_kill'] == 0 for cgroup in cgroups.values()), 'Rehearsal had an OOM kill'
        report.update(passed=True, completedPractices=completed, projectedPractices=projected, pendingOutbox=pending,
                      authGuards=True, privateDatabaseGuards=True, perUserProjectionParity=True, genericAiText=True,
                      resources=summarize(samples, run.containers), cgroups=cgroups,
                      memoryMetric='Docker stats working set, rounded display units; cgroup peak includes cache; host OS/other services excluded',
                      capacityVerdict='measurement_only_not_VPS_acceptance')
    except Exception as error:
        report.update(passed=False, failureType=type(error).__name__,
                      failureLocation=[f'{frame.name}:{frame.lineno}' for frame in traceback.extract_tb(error.__traceback__)])
        raise
    finally:
        finished.set()
        ai_finished.set()
        if ai_thread:
            ai_thread.join(timeout=20)
        if sampler:
            sampler.join(timeout=15)
        report.update(finishedAt=datetime.now(timezone.utc).isoformat(), stages=stages, sampleErrors=len(errors),
                      completedAiJobs=len(ai_completed), aiWorkloadErrors=ai_errors)
        (output / 'samples.json').write_text(json.dumps(samples, indent=2) + '\n', encoding='utf-8')
        try:
            run.cleanup()
            report['cleanupVerified'] = True
        except Exception:
            report.update(passed=False, cleanupVerified=False)
            raise
        finally:
            (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('STACK_REHEARSAL_PASS: mixed journeys, private databases, auth, outbox and projection accounting; evidence ' + str(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fake-provider', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--users', type=int, choices=range(5, 21), default=5)
    parser.add_argument('--seconds', type=int, choices=range(10, 181), default=30)
    parser.add_argument('--output', type=Path, default=ROOT / 'target' / ('stack-capacity-' + datetime.now().strftime('%Y%m%d-%H%M%S')))
    arguments = parser.parse_args()
    if arguments.fake_provider:
        fake_provider()
    else:
        try:
            main(arguments)
        except Exception:
            raise SystemExit('STACK_REHEARSAL_FAILED; inspect protected evidence; no production state changed.') from None
