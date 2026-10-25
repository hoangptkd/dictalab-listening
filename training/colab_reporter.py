"""Read-only Colab telemetry. Upload this file; importing it never starts training or networking."""
from __future__ import annotations

import ast
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

METRICS = {"step", "loss", "grad_norm", "learning_rate", "epoch", "eval_loss", "entropy", "mean_token_accuracy", "num_tokens"}
HASH_KEYS = {"configHash", "datasetHash", "datasetManifestHash", "reviewHash", "environmentLockHash", "inspectionHash", "trainerHash"}


def utc():
    return datetime.now(timezone.utc).isoformat()


def read(path, default=None):
    path = Path(path)
    if not path.is_file():
        return {} if default is None else default
    if path.stat().st_size > 2_000_000:
        raise ValueError("Metadata exceeds 2 MB")
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def bounded(root, value):
    value = Path(value)
    path = (root / value).resolve() if not value.is_absolute() else value.resolve()
    if not path.is_relative_to(root):
        raise ValueError("Config path escapes the selected training root")
    return path


def numbers(row, allowed=METRICS):
    return {k: v for k, v in row.items() if k in allowed and isinstance(v, (int, float))
            and not isinstance(v, bool) and math.isfinite(v)}


def dataset_summary(root, config):
    manifest_path = bounded(root, config["dataset_manifest"])
    manifest, review = read(manifest_path), read(bounded(root, config["dataset_approval"]))
    records = {k: manifest.get("counts", {}).get(k, manifest.get("splits", {}).get(k, {}).get("count", 0)) for k in ("train", "validation", "test")}
    reviewers = {k: v for k, v in review.get("originalArticleReviewers", {}).items() if k in ("AI:Codex", "AI:chatgpt-web") and isinstance(v, int)}
    return {"records": records, "articles": sum(reviewers.values()), "reviewers": reviewers,
            "reportedApproved": review.get("approved") is True and review.get("datasetHash") == manifest["datasetHash"] and review.get("datasetManifestHash") == sha(manifest_path),
            "humanApproved": review.get("humanApproved") is True,
            "reportedHoldout": review.get("holdoutAuditApproved") is True,
            "reportedNearDuplicate": review.get("nearDuplicateAuditApproved") is True}


def log_metrics(path, planned):
    path = Path(path)
    if not path.is_file():
        return [], 0, ""
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - 64_000))
        text = stream.read(64_000).decode("utf-8", errors="replace").replace("\r", "\n")
    history = []
    for match in re.finditer(r"\{[^{}\n]*['\"]loss['\"][^{}\n]*\}", text):
        try:
            item = ast.literal_eval(match.group())
            history.append(numbers(item))
        except (ValueError, SyntaxError):
            pass
    # Weight-loading bars (426/426) are not optimizer progress.
    steps = [int(done) for done, total in re.findall(r"(\d+)\s*/\s*(\d+)\s*\[", text)
             if int(total) == planned and int(done) <= planned]
    step = steps[-1] if steps and history else 0
    if history and step:
        history[-1].setdefault("step", step)
    return history, step, text


def active(root, config):
    # Linux /proc inspection needs no torch import and never controls processes.
    for folder in Path("/proc").glob("[0-9]*"):
        try:
            args = (folder / "cmdline").read_bytes().decode().split("\0")
            if "training.train_sft_lora" in args and "--config" in args:
                configured = Path(args[args.index("--config") + 1])
                cwd = (folder / "cwd").resolve()
                if (cwd / configured).resolve() == config and cwd == root:
                    return True
        except (OSError, ValueError, IndexError, UnicodeError):
            continue
    return False


def telemetry():
    resources, gpu = {}, ""
    try:
        import psutil  # Already installed in the training environment.
        memory = psutil.virtual_memory()
        disk = shutil.disk_usage("/content")
        resources.update(ramTotal=memory.total, ramAvailable=memory.available, ramPercent=memory.percent,
                         diskFree=disk.free, diskTotal=disk.total, cpuPercent=psutil.cpu_percent(), cpuCores=psutil.cpu_count())
    except (ImportError, OSError):
        pass
    try:
        output = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total,memory.used,utilization.gpu",
                                 "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=4, check=True)
        name, total, used, utilization = output.stdout.strip().splitlines()[0].rsplit(",", 3)
        gpu = name.strip()
        resources.update(gpuTotalBytes=int(total) * 1024**2, gpuUsedBytes=int(used) * 1024**2, gpuUtilization=float(utilization))
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        pass
    return gpu, resources


def checkpoints(output, inputs):
    items = []
    folders = sorted((output / "checkpoints").glob("checkpoint-*"),
                     key=lambda p: int(p.name.split("-")[-1]) if re.fullmatch(r"checkpoint-[1-9]\d*", p.name) else -1)
    for folder in folders[-2:]:
        if not re.fullmatch(r"checkpoint-[1-9]\d*", folder.name) or folder.is_symlink():
            continue
        step = int(folder.name.split("-")[-1])
        record = read(folder / "resume-integrity.json")
        state = read(folder / "trainer_state.json")
        files = record.get("files", {})
        required = {"adapter_model.safetensors", "adapter_config.json", "optimizer.pt", "scheduler.pt", "trainer_state.json", "rng_state.pth"}
        valid = required.issubset(files) and record.get("step") == step and state.get("global_step") == step
        for name, digest in files.items():
            if Path(name).name != name or not re.fullmatch(r"[a-f0-9]{64}", str(digest)):
                valid = False
                break
            path = folder / name
            if path.is_symlink() or not path.is_file() or sha(path) != digest:
                valid = False
                break
        stored_inputs = read(output / "run-inputs.json")
        valid = valid and all(stored_inputs.get(k) == v for k, v in inputs.items() if k != "trainerHash")
        items.append({"name": folder.name, "step": step, "integrityReported": bool(valid), "fileCount": len(files)})
    return items


def capture(root, config, status_file, log, phase, *, measure=True):
    """Observe one explicit run only. No dataset text, raw logs, credentials or paths leave this function."""
    import yaml
    root, config = Path(root).resolve(), Path(config).resolve()
    if phase not in ("smoke", "full"):
        raise ValueError("phase must be smoke or full")
    config = bounded(root, config)
    cfg = yaml.safe_load(config.read_text(encoding="utf-8"))
    output = bounded(root, cfg["output_dir"])
    manifest = read(bounded(root, cfg["dataset_manifest"]))
    bindings = {"configHash": sha(config), "datasetHash": manifest["datasetHash"],
                "datasetManifestHash": sha(bounded(root, cfg["dataset_manifest"])),
                "reviewHash": sha(bounded(root, cfg["dataset_approval"])),
                "environmentLockHash": sha(bounded(root, cfg["environment_lock"])),
                "inspectionHash": sha(bounded(root, cfg["lora"]["target_modules_from"])),
                "trainerHash": sha(root / "training/train_sft_lora.py")}
    status = read(status_file)
    restored = status.get("restoredCheckpointSteps", 0)
    if not isinstance(restored, int) or isinstance(restored, bool) or restored < 0:
        raise ValueError("Invalid restored checkpoint step")
    attempt = None
    if status.get("startedAt"):
        identity = json.dumps({"statusFile": Path(status_file).name, "startedAt": status["startedAt"],
                               "bindings": bindings}, sort_keys=True)
        attempt = {"id": "attempt-" + hashlib.sha256(identity.encode()).hexdigest()[:32], "fromStep": restored}
    result = read(output / "run-manifest.json")
    updates = read(output / "optimizer-evidence.json")
    counts = manifest.get("splits", {}).get("train", {}).get("count", 0) or manifest.get("counts", {}).get("train", 0)
    if isinstance(counts, dict): counts = counts.get("records", 0)
    settings = cfg["training"]
    planned = 1 if phase == "smoke" else math.ceil(math.ceil(counts / settings["per_device_batch_size"] / settings["gradient_accumulation_steps"]) * settings["epochs"])
    planned = max(1, planned)
    history, step, text = log_metrics(log, planned)
    progress = read(output / "training-progress.json")
    state = read(output / "checkpoints/trainer_state.json")
    if not state:
        saved = sorted((output / "checkpoints").glob("checkpoint-*"),
                       key=lambda p: int(p.name.split("-")[-1]) if re.fullmatch(r"checkpoint-[1-9]\d*", p.name) else -1)
        if saved: state = read(saved[-1] / "trainer_state.json")
    if progress:
        history, step = progress.get("history", []), progress.get("step", step)
    elif state:
        current_step, current_history = step, history
        history, step = state.get("log_history", history), max(step, state.get("global_step", 0))
        if current_history and current_step > state.get("global_step", 0):
            history = history + [current_history[-1]]
    if result:
        step = result.get("steps", step)
    safe_history = [numbers(item) for item in history][-120:]
    safe_history = [h for h in safe_history if not h.get("step", 0) > step]
    evaluations = [h for h in safe_history if "eval_loss" in h]
    if attempt and restored:
        safe_history = [h for h in safe_history if h.get("step", 0) > restored]
    safe_cfg = {k: cfg[k] for k in ("base_model", "base_revision", "device", "seed", "max_seq_length", "packing", "loss_mask_policy", "assistant_only_loss", "enable_thinking") if k in cfg}
    safe_cfg.update(lora={k: cfg["lora"][k] for k in ("r", "alpha", "dropout") if k in cfg["lora"]},
                    quantization={k: cfg["quantization"][k] for k in ("compute_dtype", "load_in_4bit", "quant_type", "double_quant") if k in cfg["quantization"]},
                    training={k: settings[k] for k in ("epochs", "per_device_batch_size", "gradient_accumulation_steps", "learning_rate", "warmup_ratio", "checkpoint_steps", "scheduler", "gradient_checkpointing", "use_cache") if k in settings})
    artifacts = {}
    for key, file in (("adapterHash", "adapter_model.safetensors"), ("adapterConfigHash", "adapter_config.json")):
        path = output / "adapter" / file
        if result.get(key) and path.is_file() and sha(path) == result[key]: artifacts[key] = result[key]
    bound = (bool(result) and all(result.get(k) == v for k, v in bindings.items() if k != "trainerHash")
             and result.get("mode") == phase and result.get("baseModel") == cfg["base_model"]
             and result.get("baseRevision") == cfg["base_revision"]
             and result.get("truncationCount") == 0 and result.get("maskPolicy") == "last-assistant-only")
    finite = updates.get("finiteLoggedGradients") is True
    new_updates = updates.get("actualOptimizerUpdates", 0)
    # A resume restores optimizer state; those steps are distinct from this process's actual updates.
    resumed_evidence = (not restored or attempt and status.get("completedFullTraining") is True
                        and status.get("step", status.get("steps")) == planned
                        and updates.get("restoredCheckpointSteps") == restored
                        and updates.get("totalVerifiedSteps") == planned and updates.get("tensorComparisonVerified") is True)
    updated = (isinstance(new_updates, int) and not isinstance(new_updates, bool) and new_updates > 0
               and new_updates + restored == step == planned and resumed_evidence
               and updates.get("changedAdapterTensors", 0) > 0)
    validation = numbers(result.get("validationMetrics", {}), {"eval_loss", "eval_runtime", "eval_samples_per_second", "eval_steps_per_second", "epoch"})
    is_active = active(root, config) if measure else False
    failure = "none"
    if is_active:
        disposition = "RUNNING"
    elif status.get("exitCode") == 0 and bound and finite and updated and len(artifacts) == 2 and (phase == "smoke" or result.get("trainingReady") is True and "eval_loss" in validation):
        disposition = "SMOKE_PASSED" if phase == "smoke" else "REPORTED_COMPLETE"
    elif status.get("exitCode") is not None:
        disposition = "FAILED"
        failure = "nonfinite_gradient" if re.search(r"grad_norm[^\n]{0,12}\bnan\b", text, re.I) else "out_of_memory" if "OutOfMemoryError" in text else "missing_update" if result and not updated else "incomplete_evidence" if status.get("exitCode") == 0 else "runtime"
    elif result:
        disposition = "VERIFYING"
    else:
        disposition = "WAITING" if not output.exists() else "INTERRUPTED"
    gpu, resources = telemetry() if measure else (result.get("gpu", ""), {})
    if isinstance(result.get("peakAllocatedBytes"), int): resources["peakAllocatedBytes"] = result["peakAllocatedBytes"]
    report = {"schemaVersion": 1, "sequence": time.time_ns(), "observedAt": utc(), "phase": phase,
              "status": disposition, "step": step, "plannedSteps": planned,
              "model": cfg["base_model"], "revision": cfg["base_revision"], "device": cfg["device"],
              "dtype": cfg["quantization"]["compute_dtype"], "gpu": gpu, "bindings": bindings, "config": safe_cfg,
              "history": safe_history, "validation": validation,
              "optimizerEvidence": {k: v for k, v in updates.items() if k in ("actualOptimizerUpdates", "changedAdapterTensors", "finiteLoggedGradients", "initialAmpScale") and (isinstance(v, bool) or isinstance(v, (int, float)) and math.isfinite(v))},
              "resources": resources, "checkpoints": checkpoints(output, bindings), "artifacts": artifacts, "failure": failure}
    if attempt:
        report["attempt"] = attempt
    if restored and updated:
        report["optimizerEvidence"]["restoredOptimizerSteps"] = restored
    if not report["validation"]:
        if evaluations:
            report["validation"] = numbers(evaluations[-1], {"eval_loss", "epoch"})
    report["dataset"] = dataset_summary(root, cfg)
    for key in ("startedAt", "finishedAt"):
        if status.get(key): report[key] = status[key]
    # Avoid publishing a mixed snapshot while the trainer is replacing configuration files.
    if sha(config) != bindings["configHash"]: raise ValueError("Config changed during capture")
    return report


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Reporter:
    def __init__(self, endpoint, token, root, config, status_file, log, phase="full", interval=15, capture_fn=None):
        url = urllib.parse.urlsplit(endpoint)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or not re.fullmatch(r"/api/training/telemetry/(?:colab|local)-[a-f0-9]{32}", url.path):
            raise ValueError("Use the exact HTTPS endpoint issued by Training Studio")
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token): raise ValueError("Invalid per-run token")
        self.endpoint, self._token = endpoint, token
        self.origin = urllib.parse.urlunsplit((url.scheme, url.netloc, "", "", ""))
        self.options = dict(root=root, config=config, status_file=status_file, log=log, phase=phase)
        self.custom_capture = capture_fn is not None
        self.capture = capture_fn or (lambda: capture(**self.options))
        self.interval = max(15, interval)
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        # Identify this client to the HTTPS gateway; urllib's default UA can be rejected before CSRF.
        self.opener.addheaders = [("User-Agent", "DictaLab-Training-Telemetry/1.0"), ("Accept", "application/json")]
        self.event = threading.Event()
        self.state = {"connected": False, "pendingApproval": False, "lastAcceptedAt": None, "lastError": None}
        self.thread = None

    def send(self, report):
        # Keep the application's CSRF cookie/header protocol; the Colab token cannot log in or control runs.
        with self.opener.open(self.origin + "/api/auth/csrf", timeout=10) as response:
            csrf = json.loads(response.read(16_000))
        if csrf.get("headerName") not in ("X-CSRF-TOKEN", "X-XSRF-TOKEN"): raise ValueError("Unexpected CSRF protocol")
        payload = json.dumps(report, allow_nan=False, separators=(",", ":")).encode()
        if len(payload) > 32_000: raise ValueError("Snapshot exceeds 32 KB")
        request = urllib.request.Request(self.endpoint, data=payload, method="POST", headers={
            "Content-Type": "application/json", "Authorization": "Bearer " + self._token,
            csrf["headerName"]: csrf["token"]})
        with self.opener.open(request, timeout=15) as response:
            ack = json.loads(response.read(16_000))
        if ack.get("sequence") != report["sequence"]: raise ValueError("Server did not acknowledge this snapshot")
        if ack.get("accepted") is False and ack.get("pendingApproval") is True:
            self.state.update(connected=False, pendingApproval=True, lastError="Approve the new attempt in Training Studio; reuse the existing key. Trainer unchanged.")
        elif ack.get("accepted") is True:
            self.state.update(connected=True, pendingApproval=False, lastAcceptedAt=utc(), lastError=None)
        else:
            raise ValueError("Server did not acknowledge this snapshot")

    def once(self):
        try:
            self.send(self.capture())
            return True
        except urllib.error.HTTPError as error:
            self.state.update(connected=False, lastError=f"HTTP {error.code}; inspect server configuration/token. Trainer unchanged.")
            if error.code in (401, 404): self.event.set()
        except Exception as error:
            # Exception strings can contain URLs or credentials from external libraries. Never print them.
            self.state.update(connected=False, lastError=f"{type(error).__name__}; telemetry not confirmed. Trainer unchanged.")
        return False

    def _loop(self):
        while not self.event.wait(self.interval): self.once()

    def follow_attempt(self, *, status_file, log, config=None):
        """Switch observation to a new marker without changing the existing endpoint, key or trainer."""
        if self.custom_capture or self.event.is_set():
            raise ValueError("Only an active Colab reporter can follow another attempt")
        options = {**self.options, "status_file": status_file, "log": log}
        if config is not None:
            options["config"] = config
        # Validate metadata before switching the polling thread's options atomically.
        sample = capture(**options)
        if "attempt" not in sample:
            raise ValueError("The new status marker needs a stable startedAt")
        self.options = options
        return sample["attempt"]

    def start(self):
        if self.thread and self.thread.is_alive(): raise ValueError("This reporter is already active")
        if not self.once(): return dict(self.state)
        self.thread = threading.Thread(target=self._loop, daemon=True, name="training-studio-telemetry")
        self.thread.start()
        return dict(self.state)

    def stop(self):
        """Stop telemetry only. This never pauses or terminates the trainer."""
        self.event.set()
        if self.thread: self.thread.join(timeout=30)
        self._token = ""
        self.state["connected"] = False


def connect(*, endpoint, root, config, status_file, log, phase="full"):
    """Prompt hides the key from notebook source and output. Call before starting a separate train cell."""
    import getpass
    reporter = Reporter(endpoint, getpass.getpass("Training Studio per-run key (hidden): "), root, config, status_file, log, phase)
    print(reporter.start())
    return reporter
