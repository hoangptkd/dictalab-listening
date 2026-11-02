"""Disposable browser fixture: real admin API/SQL, synthetic Core login and Google responses."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
# Never inherit real provider configuration into this synthetic server process.
for name in list(os.environ):
    if name.startswith(('DICTALAB_AI_', 'AI_PROVIDER_', 'GEMINI_')):
        os.environ.pop(name)
sys.path[:0] = [str(ROOT / 'services/ai-generation'), str(ROOT / 'services/ai-generation/tests')]
import httpx
from fastapi.testclient import TestClient
from test_provider_admin import postgres_settings, MASTER
from app.config import Settings
from app.provider_registry import connect
from app import main, provider_admin

container = 'dictalab-gemini-browser-qa'
password = secrets.token_urlsafe(24)
env = dict(os.environ, POSTGRES_PASSWORD=password)
subprocess.run(['docker', 'run', '--rm', '-d', '--name', container, '-p', '127.0.0.1:15549:5432',
    '-e', 'POSTGRES_PASSWORD', 'postgres:18.4-alpine'], env=env, check=True, stdout=subprocess.DEVNULL)
fixture = None
try:
    import psycopg
    os.environ['TEST_PROVIDER_ADMIN_DSN'] = f'postgresql://postgres:{password}@127.0.0.1:15549/postgres'
    for _ in range(30):
        try:
            with psycopg.connect(os.environ['TEST_PROVIDER_ADMIN_DSN'], connect_timeout=1):
                pass
            break
        except psycopg.OperationalError:
            time.sleep(.5)
    else:
        raise RuntimeError('Disposable PostgreSQL did not become ready')
    fixture = postgres_settings.__wrapped__()
    cfg = next(fixture)
    main.settings = Settings(ai_provider_config_source='database', ai_provider_database_url=cfg.reader_dsn,
        ai_provider_encryption_key=MASTER, registry_ownership='private', primary_provider='gemini',
        gemini_quota_backend='memory', provider_admin_token='m' * 40, service_token='s' * 40,
        provider_admin_database_url=cfg.provider_admin_database_url,
        provider_telemetry_database_url=cfg.provider_telemetry_database_url)
    control = dict(failGet=False, delayWrite=0, probeStatus=200)
    original_client = httpx.AsyncClient
    def google(request):
        status = control['probeStatus']
        return httpx.Response(status, json={'data': [{'id': 'gemini-3.5-flash-lite'}]})
    provider_admin.httpx = SimpleNamespace(AsyncClient=lambda **kwargs: original_client(transport=httpx.MockTransport(google), **kwargs))

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(ROOT / 'src/main/resources/static'), **kwargs)
        def log_message(self, *args):
            pass
        def reply(self, code, data):
            body = json.dumps(data).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def handle_api(self):
            if self.path.startswith('/api/auth/csrf'):
                self.reply(200, dict(headerName='X-CSRF-TOKEN', token='fixture-only'))
            elif self.path.startswith('/api/auth/me'):
                self.reply(200, dict(id=7, role='ADMIN', displayName='QA Admin', email='qa@example.test'))
            elif self.path.startswith('/api/admin/lessons/page'):
                self.reply(200, dict(items=[], page=0, size=20, total=0))
            elif self.path.startswith('/api/admin/lessons/filters'):
                self.reply(200, [])
            elif self.path.startswith('/__qa'):
                data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
                if data.pop('reset', False):
                    from app.provider_registry import save_pool
                    with connect(cfg.owner_dsn, readonly=False) as db:
                        for table in ('ai_provider_usage','ai_provider_audit','ai_provider_labels','ai_provider_accounts','ai_provider_pools'):
                            db.execute('DELETE FROM '+table)
                        db.execute('UPDATE ai_provider_management SET revision=0')
                        save_pool(db,'gemini','gemini-3.5-flash-lite',0,0,True)
                    control.update(failGet=False,delayWrite=0,probeStatus=200)
                if data.pop('noPool', False):
                    with connect(cfg.owner_dsn, readonly=False) as db:
                        db.execute("DELETE FROM ai_provider_pools WHERE platform='gemini'")
                if data.pop('conflict', False):
                    with connect(cfg.owner_dsn, readonly=False) as db:
                        db.execute('UPDATE ai_provider_management SET revision=revision+1')
                if data.pop('usage', False):
                    from app.provider_admin import record_attempt
                    from uuid import uuid4
                    from app.config import GeminiSource
                    source = GeminiSource(id='source-a', project_id='fixture-project', api_key='fake-only', enabled=True)
                    record_attempt(cfg, uuid4(), source, 'success', True, dict(input_tokens=100, output_tokens=20), .05)
                if data.pop('history', False):
                    from app.provider_admin import audit
                    with connect(cfg.owner_dsn, readonly=False) as db:
                        for index in range(60):
                            audit(db,7,'source-a','updated',dict(name=dict(before=f'old-{index}',after=f'new-{index}')))
                        audit(db,0,None,'operator_updated',dict(sourceIds=['source-a','source-b'],platforms=['gemini']))
                control.update(data)
                self.reply(200, dict(ok=True))
            elif self.path.startswith('/api/admin/gemini'):
                if self.command == 'GET' and control['failGet']:
                    control['failGet'] = False
                    self.reply(503, dict(message='QA: simulated unavailable registry'))
                    return True
                if self.command != 'GET':
                    time.sleep(control['delayWrite'])
                    control['delayWrite'] = 0
                    if self.headers.get('X-CSRF-TOKEN') != 'fixture-only':
                        self.reply(403, dict(message='CSRF required'))
                        return True
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                response = client.request(self.command, self.path.replace('/api/admin/gemini', '/v1/admin/gemini', 1),
                    content=body, headers={'Content-Type': 'application/json', 'X-Provider-Admin-Token': 'm'*40, 'X-Admin-Actor': '7'})
                data = response.json()
                if response.status_code >= 400:
                    data = dict(message=data.get('error', data.get('detail', 'Unavailable')))
                self.reply(response.status_code, data)
            else:
                return False
            return True
        def do_GET(self):
            if self.handle_api():
                return
            if self.path.startswith('/admin/'):
                self.path = '/admin.html'
            return super().do_GET()
        def do_POST(self):
            if not self.handle_api(): self.reply(404, {})
        do_PUT = do_POST
        do_DELETE = do_POST

    with TestClient(main.app) as client:
        print('Gemini browser fixture ready at http://127.0.0.1:18089/admin/gemini', flush=True)
        from threading import Thread
        from gemini_admin_ui_checks import run_checks, browser
        server = ThreadingHTTPServer(('127.0.0.1', 18089), Handler)
        Thread(target=server.serve_forever, daemon=True).start()
        try:
            run_checks()
        finally:
            try:
                browser('close')
            finally:
                server.shutdown()
                server.server_close()
finally:
    try:
        if fixture:
            fixture.close()
    finally:
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL)
