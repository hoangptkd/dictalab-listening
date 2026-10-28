"""Offline, loopback-only CPU inference. The AI service owns durable jobs and validation."""
from contextlib import nullcontext
from hashlib import sha256, sha1
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hmac
import importlib.metadata
import json
import os
from pathlib import Path
import threading
import time
from uuid import UUID

REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"
ADAPTER_SHA = "1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf"
ADAPTER_CONFIG_SHA = "64e399fb6fbf554759cc1e4cb5fc6b3247578d4871474136a0e5174f50a9ba41"
ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "data/fine-tuning/hf-cache/hub/models--Qwen--Qwen3.5-4B/snapshots" / REVISION
ADAPTER = ROOT / "data/fine-tuning/colab-transfer-20261025/full07-final-20261026/adapter"
# Publisher metadata at the pinned revision, independently checked on 2026-10-26.
FILES = {
    "model.safetensors-00001-of-00002.safetensors": "26a93f066e1916adb13453dae5a0c707c0fbc71299ed98779571a907b8e74c61",
    "model.safetensors-00002-of-00002.safetensors": "cb544bd9bfae93dc59b0f22b292f5933573854a7f9b97835c67060d7d910e188",
    "tokenizer.json": "5f9e4d4901a92b997e463c1f46055088b6cca5ca61a6522d1b9f64c4bb81cb42",
    "model.safetensors.index.json": "fddda6039f7c1d17260c9e923b8a72fd025d9a86",
    "config.json": "557d961b205319c6a7da5f757f565b69b3967b7d",
    "tokenizer_config.json": "eda48d3e75a8e59a8479ee4ec8b37f76e711d9c1",
    "chat_template.jinja": "a585dec894e63da457d9440ec6aa7caa16d20860",
}
VERSIONS = {"torch": "2.10.0+cpu", "transformers": "5.20.2", "peft": "0.21.2", "safetensors": "0.8.0"}


def file_digest(path, git_blob=False):
    digest = sha1() if git_blob else sha256()
    if git_blob:
        digest.update(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_request(value):
    if not isinstance(value, dict) or set(value) != {"requestId", "variant", "messages", "maxNewTokens", "timeoutSeconds"}:
        raise ValueError("invalid envelope")
    if not isinstance(value["requestId"], str) or str(UUID(value["requestId"])) != value["requestId"]:
        raise ValueError("invalid request ID")
    if value["variant"] not in ("base", "full07"):
        raise ValueError("invalid variant")
    if type(value["maxNewTokens"]) is not int or not 1 <= value["maxNewTokens"] <= 1536:
        raise ValueError("invalid output bound")
    if type(value["timeoutSeconds"]) is not int or not 1 <= value["timeoutSeconds"] <= 1800:
        raise ValueError("invalid deadline")
    messages = value["messages"]
    if not isinstance(messages, list) or len(messages) != 2:
        raise ValueError("two messages required")
    for message, role in zip(messages, ("system", "user")):
        if (not isinstance(message, dict) or set(message) != {"role", "content"} or message["role"] != role
                or not isinstance(message["content"], str) or not 1 <= len(message["content"]) <= 24000):
            raise ValueError("invalid message")
    return value


class CpuEngine:
    def __init__(self):
        self.base = Path(os.environ.get("DICTALAB_MODEL_BASE_PATH", BASE))
        self.adapter = Path(os.environ.get("DICTALAB_MODEL_ADAPTER_PATH", ADAPTER))
        self.model = self.tokenizer = None
        self.lock = threading.Lock()

    def verify(self):
        if {name: importlib.metadata.version(name) for name in VERSIONS} != VERSIONS:
            raise RuntimeError("RUNTIME_VERSION_MISMATCH")
        for name, expected in FILES.items():
            if file_digest(self.base / name, len(expected) == 40) != expected:
                raise RuntimeError("BASE_HASH_MISMATCH")
        if (file_digest(self.adapter / "adapter_model.safetensors") != ADAPTER_SHA
                or file_digest(self.adapter / "adapter_config.json") != ADAPTER_CONFIG_SHA):
            raise RuntimeError("ADAPTER_HASH_MISMATCH")

    def load(self):
        if self.model is not None:
            return
        import psutil
        # FP32 weights alone occupy ~17 GiB; avoid paging or exhausting this shared PC.
        if psutil.virtual_memory().available < 20 * 1024**3:
            raise RuntimeError("INSUFFICIENT_FREE_MEMORY")
        self.verify()
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
        import torch
        from transformers import AutoTokenizer, Qwen3_5ForCausalLM
        from peft import PeftModel
        torch.set_num_threads(8)
        tokenizer = AutoTokenizer.from_pretrained(str(self.base), local_files_only=True, trust_remote_code=False)
        adapter_tokenizer = AutoTokenizer.from_pretrained(str(self.adapter), local_files_only=True, trust_remote_code=False)
        if (tokenizer.backend_tokenizer.to_str() != adapter_tokenizer.backend_tokenizer.to_str()
                or tokenizer.chat_template != adapter_tokenizer.chat_template):
            raise RuntimeError("TOKENIZER_MISMATCH")
        base, info = Qwen3_5ForCausalLM.from_pretrained(str(self.base), local_files_only=True,
            trust_remote_code=False, dtype=torch.float32, device_map={"": "cpu"}, output_loading_info=True)
        if any(info.get(key) for key in ("missing_keys", "mismatched_keys", "error_msgs", "unexpected_keys")):
            raise RuntimeError("WEIGHT_LOADING_MISMATCH")
        model = PeftModel.from_pretrained(base, str(self.adapter), local_files_only=True, is_trainable=False)
        model.requires_grad_(False).eval()
        self.tokenizer, self.model = tokenizer, model

    def generate(self, request):
        started = time.monotonic()
        self.load()
        import torch
        rendered = self.tokenizer.apply_chat_template(request["messages"], tokenize=False,
            add_generation_prompt=True, enable_thinking=False)
        encoded = self.tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
        input_tokens = encoded["input_ids"].shape[-1]
        if input_tokens + request["maxNewTokens"] > 4608:
            raise ValueError("CONTEXT_TOO_LONG")
        remaining = request["timeoutSeconds"] - (time.monotonic() - started)
        if remaining <= 0:
            raise TimeoutError("DEADLINE")
        # Base arm uses the same immutable weights with LoRA disabled. No merged weights.
        with (self.model.disable_adapter() if request["variant"] == "base" else nullcontext()), torch.inference_mode():
            output = self.model.generate(**encoded, do_sample=False, max_new_tokens=request["maxNewTokens"],
                max_time=remaining, use_cache=True, pad_token_id=self.tokenizer.eos_token_id)
        ids = output[0, input_tokens:].tolist()
        elapsed = time.monotonic() - started
        return {"requestId": request["requestId"], "provider": "local_cpu", "model": "Qwen/Qwen3.5-4B",
            "baseRevision": REVISION, "variant": request["variant"],
            "adapterSha256": ADAPTER_SHA if request["variant"] == "full07" else None,
            "baseMode": "adapter-disabled", "dtype": "float32", "device": "cpu", "greedy": True,
            "thinking": False, "versions": VERSIONS, "inputTokens": input_tokens, "outputTokens": len(ids),
            "truncated": bool(ids and len(ids) == request["maxNewTokens"] and ids[-1] != self.tokenizer.eos_token_id),
            "deadlineExceeded": elapsed > request["timeoutSeconds"], "latencyMs": round(elapsed * 1000),
            "raw": self.tokenizer.decode(ids, skip_special_tokens=True)}


def make_server(engine, token, port=8093):
    if not 32 <= len(token) <= 256 or not all(33 <= ord(c) <= 126 for c in token):
        raise ValueError("Model serving requires a 32-256 character ASCII service token")

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass  # Never log request bodies, tokens or raw exception text.

        def respond(self, status, body):
            raw = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def authorized(self):
            return hmac.compare_digest(self.headers.get("X-Service-Token", "").encode(), token.encode())

        def do_GET(self):
            if self.path == "/health/live":
                return self.respond(200, {"status": "ok"})
            if not self.authorized():
                return self.respond(401, {"error": "UNAUTHORIZED"})
            if self.path == "/health/ready":
                return self.respond(200 if engine.model is not None else 503,
                    {"status": "ready" if engine.model is not None else "not_loaded", "baseRevision": REVISION})
            self.respond(404, {"error": "NOT_FOUND"})

        def do_POST(self):
            if not self.authorized():
                return self.respond(401, {"error": "UNAUTHORIZED"})
            if self.path != "/v1/infer":
                return self.respond(404, {"error": "NOT_FOUND"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if self.headers.get("Transfer-Encoding") or not 0 < size <= 64000:
                    return self.respond(413, {"error": "BODY_LIMIT"})
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    return self.respond(415, {"error": "CONTENT_TYPE"})
                body = self.rfile.read(size)
                if len(body) != size:
                    raise ValueError("incomplete body")
                request = validate_request(json.loads(body))
            except (ValueError, TypeError, TimeoutError):
                return self.respond(400, {"error": "INVALID_REQUEST"})
            if not engine.lock.acquire(blocking=False):
                return self.respond(429, {"error": "MODEL_BUSY"})
            try:
                self.respond(200, engine.generate(request))
            except (ValueError, TimeoutError):
                self.respond(422, {"error": "INPUT_OR_DEADLINE"})
            except Exception as error:
                code = str(error) if str(error) in {"INSUFFICIENT_FREE_MEMORY", "RUNTIME_VERSION_MISMATCH",
                    "BASE_HASH_MISMATCH", "ADAPTER_HASH_MISMATCH", "TOKENIZER_MISMATCH", "WEIGHT_LOADING_MISMATCH"} else "INFERENCE_UNAVAILABLE"
                self.respond(503, {"error": code})
            finally:
                engine.lock.release()

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify files and runtime without loading weights")
    args = parser.parse_args()
    engine = CpuEngine()
    if args.verify:
        engine.verify()
        print(json.dumps({"status": "verified", "baseRevision": REVISION, "adapterSha256": ADAPTER_SHA}))
    else:
        server = make_server(engine, os.environ.get("DICTALAB_MODEL_SERVICE_TOKEN", ""),
            int(os.environ.get("DICTALAB_MODEL_PORT", "8093")))
        try:
            server.serve_forever()
        finally:
            server.server_close()
