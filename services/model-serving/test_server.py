import importlib.util
import json
from pathlib import Path
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

import pytest

spec = importlib.util.spec_from_file_location("model_server", Path(__file__).with_name("server.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def payload(**updates):
    return {"requestId": str(uuid4()), "variant": "full07", "messages": [
        {"role": "system", "content": "Return JSON."}, {"role": "user", "content": "Demo"}],
        "maxNewTokens": 50, "timeoutSeconds": 30, **updates}


def test_contract_rejects_unknown_fields_roles_ids_and_unbounded_work():
    assert module.validate_request(payload())
    for updates in ({"variant": "cloud"}, {"maxNewTokens": True}, {"maxNewTokens": 1537},
                    {"timeoutSeconds": 1801}, {"requestId": "bad"}, {"extra": "bad"},
                    {"messages": [{"role": "user", "content": "x"}]}):
        with pytest.raises((ValueError, TypeError)):
            module.validate_request(payload(**updates))


def test_hash_algorithms_match_known_fixtures(tmp_path):
    path = tmp_path / "fixture"
    path.write_bytes(b"hello")
    assert module.file_digest(path, True) == "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"
    assert module.file_digest(path) == "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"


def test_http_auth_busy_errors_and_provenance():
    class FakeEngine:
        model = None
        lock = threading.Lock()
        def generate(self, value):
            if value["maxNewTokens"] == 1:
                raise RuntimeError("private path and token must never leave process")
            return {"raw": "{}", "requestId": value["requestId"], "variant": value["variant"], "provider": "test_fixture"}
    engine, token = FakeEngine(), "test-model-token-at-least-32-characters"
    server = module.make_server(engine, token, 0)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    def call(path, body=None, auth=token):
        request = Request(f"http://127.0.0.1:{server.server_port}" + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"X-Service-Token": auth, "Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=3)
        except HTTPError as error:
            response = error
        with response:
            return response.code, json.loads(response.read())
    try:
        assert call("/health/live", auth="")[0] == 200
        assert call("/health/ready")[0] == 503
        assert call("/v1/infer", payload(), auth="")[0] == 401
        request = payload()
        status, result = call("/v1/infer", request)
        assert status == 200 and result["requestId"] == request["requestId"]
        assert result["provider"] == "test_fixture"
        with engine.lock:
            assert call("/v1/infer", payload())[0] == 429
        assert call("/v1/infer", payload(maxNewTokens=1)) == (503, {"error": "INFERENCE_UNAVAILABLE"})
        assert call("/v1/infer", payload(extra=True))[0] == 400
    finally:
        server.shutdown()
        server.server_close()
        worker.join(3)
