"""Loopback-only, finite-budget HTTP provider. Never contacts an external AI service."""
import argparse
import json
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError


class Provider(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, mode, max_calls, delay):
        super().__init__(("127.0.0.1", port), Handler)
        self.mode, self.max_calls, self.delay = mode, max_calls, delay
        self.calls = 0
        self.lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log learner text, keys, cookies or request bodies.

    def reply(self, status, body, headers=None):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_GET(self):
        if self.path not in ("/health", "/stats"):
            return self.reply(404, {})
        with self.server.lock:
            self.reply(200, {"provider": "fake", "mode": self.server.mode,
                             "calls": self.server.calls, "maxCalls": self.server.max_calls})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            return self.reply(404, {})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 100_000:
                return self.reply(413, {})
            self.connection.settimeout(5)
            request = json.loads(self.rfile.read(length))
            with self.server.lock:
                self.server.calls += 1
                exhausted = self.server.calls > self.server.max_calls
            if exhausted:
                return self.reply(429, {"error": "fake budget exhausted"}, {"Retry-After": "60"})
            mode = self.server.mode
            if mode == "timeout":
                time.sleep(self.server.delay)
            if mode in ("429", "500"):
                return self.reply(int(mode), {"error": "injected fault"}, {"Retry-After": "2"})
            if mode == "malformed":
                content = "{invalid-json"
            else:
                # Only the Listening recall contract is implemented. Unsupported calls fail closed.
                text = request["messages"][1]["content"]
                if not text.startswith("Facts: ") or "\nRecall: " not in text:
                    return self.reply(422, {"error": "unsupported fake operation"})
                facts = json.loads(text[7:].split("\nRecall: ", 1)[0])
                labels = [{"factId": fact["id"], "status": "CAPTURED", "evidenceVi": "synthetic"}
                          for fact in facts]
                content = json.dumps({"facts": labels})
            return self.reply(200, {"choices": [{"message": {"content": content}}]})
        except (ValueError, KeyError, IndexError, TypeError, TimeoutError):
            return self.reply(400, {"error": "invalid fake request"})


class SelfTest(unittest.TestCase):
    def test_http_contract_and_budget(self):
        for mode, expected in (("valid", 200), ("429", 429), ("500", 500), ("malformed", 200)):
            server = Provider(0, mode, 1, 0)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                body = json.dumps({"messages": [{}, {"content": 'Facts: [{"id":"F1"}]\nRecall: fixture'}]}).encode()
                request = Request(f"http://127.0.0.1:{server.server_port}/v1/chat/completions", body,
                                  {"Content-Type": "application/json"})
                def send():
                    try:
                        return urlopen(request, timeout=2)
                    except HTTPError as error:
                        return error
                with send() as response:
                    self.assertEqual(response.status, expected)
                    result = json.load(response)
                    if mode == "valid":
                        self.assertEqual(json.loads(result["choices"][0]["message"]["content"])["facts"][0]["factId"], "F1")
                    if mode == "malformed":
                        with self.assertRaises(ValueError):
                            json.loads(result["choices"][0]["message"]["content"])
                with send() as response:
                    self.assertEqual(response.status, 429)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18380)
    parser.add_argument("--mode", choices=["valid", "429", "500", "malformed", "timeout"], default="valid")
    parser.add_argument("--max-calls", type=int, default=10_000)
    parser.add_argument("--delay", type=float, default=65)
    parser.add_argument("--seconds", type=int, default=10800)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=["fake-provider"], exit=True)
    if not 1024 <= args.port <= 65535 or not 1 <= args.max_calls <= 100_000 or not 1 <= args.seconds <= 14400 or not 0 <= args.delay <= 90:
        parser.error("Finite port/call/runtime/delay budgets required")
    with Provider(args.port, args.mode, args.max_calls, args.delay) as server:
        timer = threading.Timer(args.seconds, server.shutdown)
        timer.daemon = True
        timer.start()
        try:
            server.serve_forever()
        finally:
            timer.cancel()
            print(json.dumps({"provider": "fake", "calls": server.calls, "maxCalls": args.max_calls}))
