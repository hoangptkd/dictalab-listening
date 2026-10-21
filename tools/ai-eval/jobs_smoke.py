"""Real Java -> HTTP API -> RabbitMQ -> Python worker smoke with unpaid fake providers.

Runs local and production routing, a 1/5/10/20 concurrency sweep, and host loss.
Set AI_TEST_RABBITMQ_URL to a disposable broker. Otherwise starts/removes its own Docker container.
"""
import concurrent.futures
import io
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
import urllib.error
import urllib.request
import uuid
import wave
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)


def main():
    broker = os.environ.get("AI_TEST_RABBITMQ_URL")
    container = None
    processes = []
    fake = None
    evidence = []
    try:
        if not broker:
            container = "dictalab-ai-check-" + uuid.uuid4().hex[:10]
            subprocess.run(["docker", "run", "--detach", "--rm", "--name", container,
                "-p", "127.0.0.1::5672", "rabbitmq:4.3.6-alpine"], check=True, stdout=subprocess.DEVNULL)
            port = subprocess.check_output(["docker", "port", container, "5672"], text=True).strip().rsplit(":", 1)[1]
            broker = f"amqp://guest:guest@127.0.0.1:{port}/%2F"
        import pika
        deadline = time.monotonic() + 60
        while True:
            try:
                params = pika.URLParameters(broker); params.socket_timeout = 2; params.stack_timeout = 3
                connection = pika.BlockingConnection(params); connection.close(); break
            except pika.exceptions.AMQPError:
                if time.monotonic() > deadline:
                    raise RuntimeError("test broker readiness deadline exceeded") from None
                time.sleep(0.2)

        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(24000); wav.writeframes(b"\x00\x00" * 240)
        audio = output.getvalue()
        calls = []
        unavailable = threading.Event()

        class FakeProvider(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send(self, body, content_type="application/json", status=200):
                if not isinstance(body, bytes):
                    body = json.dumps(body).encode()
                self.send_response(status); self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

            def do_GET(self):
                if self.path == "/v1/models":
                    self.send({"data": [{"id": "test-cloud"}]})
                else:
                    self.send({"models": [{"name": "qwen3.5:9b", "digest": "a" * 64}]})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(self.path)
                if unavailable.is_set():
                    self.send({"error": "test host unavailable"}, status=503); return
                if self.path == "/tts/bytes":
                    self.send(audio, "audio/wav"); return
                payload = json.loads(body["messages"][-1]["content"])
                if payload["kind"] == "coach":
                    candidate = payload["candidates"][0]
                    result = {"focus": {"pattern": candidate["pattern"], "evidenceIds": [candidate["id"]],
                              "insight": "Bạn đã bỏ lỡ từ quen trong câu."}, "reviewIds": []}
                else:
                    result = {"facts": [{"factId": fact["id"], "status": "CAPTURED", "explanationVi": "Đúng."} for fact in payload["facts"]]}
                if self.path == "/api/chat":
                    self.send({"done": True, "done_reason": "stop", "message": {"content": json.dumps(result)}})
                else:
                    assert self.headers.get("Authorization") == "Bearer test-only-cloud"
                    self.send({"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]})

        fake = ThreadingHTTPServer(("127.0.0.1", 0), FakeProvider)
        threading.Thread(target=fake.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory() as directory:
            for production in (False, True):
                provider = "shopaikey" if production else "ollama"
                port = free_port(); url = f"http://127.0.0.1:{port}"
                env = {k: v for k, v in os.environ.items() if not k.startswith("DICTALAB_AI_")}
                env.update({"DICTALAB_AI_JOBS_ENABLED": "true", "DICTALAB_AI_JOBS_DATABASE": str(Path(directory) / (provider + ".sqlite3")),
                    "DICTALAB_AI_RABBITMQ_URL": broker, "DICTALAB_AI_CONVERSATION_ENABLED": "false",
                    "DICTALAB_AI_PRODUCTION_MODE": str(production).lower(), "DICTALAB_AI_SERVICE_TOKEN": "smoke-only-token",
                    "DICTALAB_AI_CLOUD_API_KEY": "test-only-cloud", "DICTALAB_AI_CLOUD_MODEL": "test-cloud",
                    "DICTALAB_AI_CLOUD_BASE_URL": f"http://127.0.0.1:{fake.server_port}",
                    "DICTALAB_AI_OLLAMA_URL": f"http://127.0.0.1:{fake.server_port}",
                    "DICTALAB_AI_CARTESIA_API_KEY": "test-only-tts", "DICTALAB_AI_CARTESIA_URL": f"http://127.0.0.1:{fake.server_port}/tts/bytes"})
                with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as log:
                    api = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
                        cwd=ROOT / "services/ai-generation", env=env, stdout=log, stderr=log)
                    worker = subprocess.Popen([sys.executable, "-m", "app.worker"], cwd=ROOT / "services/ai-generation", env=env, stdout=log, stderr=log)
                    processes += [api, worker]
                    ready_deadline = time.monotonic() + 40
                    while True:
                        try:
                            with urllib.request.urlopen(url + "/health/ready", timeout=5) as response:
                                if response.status == 200: break
                        except OSError:
                            if time.monotonic() > ready_deadline or api.poll() is not None or worker.poll() is not None:
                                log.seek(0); raise RuntimeError(log.read()[-3000:])
                            time.sleep(0.1)
                    before = len(calls)
                    try:
                        subprocess.run([shutil.which("mvn"), "-q", "-Dtest=AiPlatformSmokeTest", f"-Dai.jobs.smoke.url={url}",
                            f"-Dai.jobs.smoke.provider={provider}", "test"], cwd=ROOT, check=True, timeout=180)
                        assert len(calls) - before == 3, "duplicate delivery must not repeat a provider call"

                        def run_job(_=0):
                            job_id = str(uuid.uuid4())
                            payload = {"schemaVersion": "1", "jobId": job_id, "requestId": job_id, "userScope": "a" * 64,
                                "idempotencyKey": job_id, "deadline": (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat(),
                                "payload": {"kind": "review", "facts": [{"id": "F1", "text": "nine"}], "recall": "nine"}}
                            headers = {"Content-Type": "application/json", "X-Service-Token": "smoke-only-token", "X-User-Scope": "a" * 64,
                                "X-AI-Provider": provider, "X-Request-Id": job_id, "Idempotency-Key": job_id}
                            started = time.monotonic()
                            req = urllib.request.Request(url + "/v1/review/tasks", data=json.dumps(payload).encode(), headers=headers)
                            with urllib.request.urlopen(req, timeout=10) as response:
                                state = json.load(response)
                            while state["status"] in ("PENDING", "RUNNING"):
                                if time.monotonic() - started > 60: raise RuntimeError("job load deadline exceeded")
                                time.sleep(0.1)
                                with urllib.request.urlopen(urllib.request.Request(url + "/v1/jobs/" + job_id, headers=headers), timeout=5) as response:
                                    state = json.load(response)
                            return state["status"], time.monotonic() - started

                        for concurrency in (1, 5, 10, 20):
                            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                                results = list(pool.map(run_job, range(concurrency)))
                            assert all(status == "DONE" for status, _ in results), results
                            durations = sorted(duration for _, duration in results)
                            evidence.append({"provider": provider, "concurrency": concurrency, "samples": len(results),
                                "p50": durations[int((len(durations) - 1) * .50)], "p95": durations[int((len(durations) - 1) * .95)],
                                "p99": durations[int((len(durations) - 1) * .99)], "failed": 0, "environment": "synthetic-fake-provider"})
                        before = len(calls); unavailable.set()
                        assert run_job()[0] == "FAILED"
                        assert len(calls) - before == 1, "host loss must not repeat ambiguous calls"
                        unavailable.clear()
                        stop(worker)
                        worker = subprocess.Popen([sys.executable, "-m", "app.worker"], cwd=ROOT / "services/ai-generation", env=env, stdout=log, stderr=log)
                        processes.append(worker)
                        assert run_job()[0] == "DONE", "worker restart should resume pending outbox work"
                    except Exception:
                        log.seek(0); print(log.read()[-4000:]); raise
                    finally:
                        stop(worker); stop(api)
                print(f"{provider}: Java/API/RabbitMQ/worker, duplicate, load 1/5/10/20, host loss and restart passed")
        report = ROOT / "target/ai-platform-jobs-load.json"
        report.parent.mkdir(exist_ok=True)
        report.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    finally:
        for process in processes: stop(process)
        if fake is not None: fake.shutdown(); fake.server_close()
        if container: subprocess.run(["docker", "stop", container], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
