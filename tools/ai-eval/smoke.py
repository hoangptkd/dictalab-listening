"""Run the real Java -> FastAPI -> fake Ollama contract, without paid providers."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
CONTENT = {"slots": [{"kind": "time", "askVi": "When?"}, {"kind": "place", "askVi": "Where?"}],
           "utteranceEn": "Please meet at nine in the main lobby.",
           "facts": [{"textEn": "nine", "evidenceEn": "at nine"}, {"textEn": "lobby", "evidenceEn": "in the main lobby"}],
           "questionVi": "When and where?", "taskVi": "Listen.", "skill": "details"}
TRANSLATION = {"translationVi": "At nine in the lobby.", "facts": [{"textVi": "nine"}, {"textVi": "lobby"}]}


def main():
    artifact = json.loads((ROOT / "artifacts/prompts/conversation-cefr-b1-v7-20261021.json").read_text(encoding="utf-8"))
    calls = []

    class FakeOllama(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send(self, payload):
            body = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.send({"models": [artifact["model"]]})

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append(body)
            translation = "translationVi" in body["format"]["properties"]
            self.send({"done": True, "done_reason": "stop", "message": {"content": json.dumps(TRANSLATION if translation else CONTENT)}})

    fake = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
    threading.Thread(target=fake.serve_forever, daemon=True).start()
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    env = {k: v for k, v in os.environ.items() if not k.startswith("DICTALAB_AI_")}
    env.update({"DICTALAB_AI_OLLAMA_URL": f"http://127.0.0.1:{fake.server_port}",
                "DICTALAB_AI_SERVICE_TOKEN": "smoke-only-token", "DICTALAB_AI_TIMEOUT_SECONDS": "10"})
    process = None
    try:
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as log:
            process = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
                                       cwd=ROOT / "services/ai-generation", env=env, stdout=log, stderr=log)
            url = f"http://127.0.0.1:{port}"
            readiness_deadline = time.monotonic() + 30
            while time.monotonic() < readiness_deadline:
                try:
                    with urllib.request.urlopen(url + "/health/ready", timeout=5) as response:
                        if response.status == 200:
                            break
                except OSError:
                    if process.poll() is not None:
                        log.seek(0)
                        raise RuntimeError(log.read()[-4000:])
                    time.sleep(0.1)
            else:
                log.seek(0)
                print(log.read()[-4000:])
                raise RuntimeError("AI Platform smoke readiness deadline exceeded")
            maven = shutil.which("mvn")
            if not maven:
                raise RuntimeError("Maven not found")
            try:
                subprocess.run([maven, "-q", "-Dtest=ConversationPythonSmokeTest", f"-Dai.platform.smoke.url={url}", "test"],
                               cwd=ROOT, check=True, timeout=240)
            except subprocess.CalledProcessError:
                log.seek(0)
                print(log.read()[-4000:])
                raise
            assert len(calls) == 2, "expected exactly English + translation calls"
            assert calls[0]["options"]["temperature"] == 0.3
            assert calls[1]["options"]["temperature"] == 0.1
            print("Java -> Python -> fake Ollama smoke passed; exactly two model calls")
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        fake.shutdown()
        fake.server_close()


if __name__ == "__main__":
    main()
