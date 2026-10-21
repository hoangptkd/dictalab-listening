"""Actual TLS/token boundary with an ephemeral local certificate; no production secrets."""
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from app.providers.ollama import OllamaProvider


@pytest.mark.asyncio
async def test_private_host_requires_trusted_certificate_and_service_token(tmp_path):
    openssl = shutil.which("openssl")
    if not openssl:
        candidate = Path("C:/Program Files/Git/usr/bin/openssl.exe")
        openssl = str(candidate) if candidate.exists() else None
    if not openssl:
        pytest.skip("OpenSSL is needed to generate an ephemeral TLS test certificate")
    cert, key = tmp_path / "certificate.pem", tmp_path / "private.pem"
    subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
        "-out", str(cert), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)

    class PrivateHost(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            status = 200 if self.headers.get("X-Service-Token") == "ephemeral-test-token" else 401
            payload = {"done": True, "done_reason": "stop", "message": {"content": '{"ok":true}'}}
            body = json.dumps(payload).encode()
            self.send_response(status); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), PrivateHost)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    url = f"https://127.0.0.1:{server.server_port}"
    try:
        trusted = ssl.create_default_context(cafile=str(cert))
        async with httpx.AsyncClient(verify=trusted, trust_env=False) as client:
            provider = OllamaProvider(ollama_url=url, model="test", client=client, service_token="ephemeral-test-token")
            assert await provider.complete([], {}, time.monotonic() + 10, "tls-test") == {"ok": True}
            provider = OllamaProvider(ollama_url=url, model="test", client=client, service_token="wrong")
            from app.providers.base import ProviderError
            with pytest.raises(ProviderError):
                await provider.complete([], {}, time.monotonic() + 10, "tls-test")
        async with httpx.AsyncClient(trust_env=False) as client:
            with pytest.raises(httpx.ConnectError):
                await client.post(url + "/api/chat", json={}, timeout=5)
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
