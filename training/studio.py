"""Local Training Studio boundary. JSON stdin/stdout; no shell commands or remote execution."""
from __future__ import annotations

import argparse
import ast
import contextlib
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

import psutil
import yaml

from training.train_sft_lora import replace_runtime_file

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data/fine-tuning"
STUDIO = DATA / "studio"
ACTIVE = {"STARTING", "RUNNING", "PAUSE_REQUESTED", "STOP_REQUESTED"}


class StudioError(ValueError):
    pass


def now():
    return datetime.now(timezone.utc).isoformat()


def contained(value, parent=None):
    parent = DATA if parent is None else parent
    path = Path(value)
    path = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
    if not path.is_relative_to(parent.resolve()):
        raise StudioError("Đường dẫn nằm ngoài thư mục dữ liệu train.")
    return path


def read(path, default=None):
    path = contained(path)
    if not path.is_file():
        return {} if default is None else default
    if path.stat().st_size > 2_000_000:
        raise StudioError("Metadata vượt giới hạn cho phép.")
    def invalid_number(value):
        raise StudioError("Metadata có chỉ số không hữu hạn.")
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=invalid_number)


def save(path, obj):
    path = contained(path)
    temporary = path.with_suffix(path.suffix + f".{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(obj, ensure_ascii=False, allow_nan=False, indent=2) + "\n", encoding="utf-8")
    replace_runtime_file(temporary, path)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def public_text(text):
    text = str(text).replace(str(ROOT), "<workspace>").replace(str(ROOT).replace("\\", "/"), "<workspace>")
    text = re.sub(r"(?i)(?:[A-Z]:[\\/])?Users[\\/][^\\/\s]+", "<user>", text)
    text = re.sub(r"(?i)\bBearer\s+[^\s,;\"']+", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)((?:api[_-]?key|authorization|password|secret|access[_-]?token|service[_-]?token)[\"']?\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;]+)", r"\1[REDACTED]", text)
    text = re.sub(r"\b(?:hf_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9_-]{12,})\b", "[REDACTED]", text)
    return text


def log_tail(path, limit=48_000):
    path = contained(path)
    if not path.is_file():
        return ""
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - limit))
        raw = stream.read(limit).decode("utf-8", errors="replace")
    # Model loading progress uses carriage returns rather than newlines.
    return public_text(raw.replace("\r", "\n"))[-limit:]


def metrics_from_log(text):
    rows = []
    for match in re.finditer(r"\{[^{}\n]*['\"]loss['\"][^{}\n]*\}", text):
        try:
            value = ast.literal_eval(match.group())
            row = {}
            for k, v in value.items():
                try:
                    number = float(v)
                    if math.isfinite(number):
                        row[k] = number
                except (ValueError, TypeError):
                    pass
            if "loss" in row:
                rows.append(row)
        except (ValueError, SyntaxError):
            pass
    # Historical Trainer logs lack global_step; never infer it from the number of tail lines.
    progress = re.findall(r"(\d+)\s*/\s*(\d+)\s*\[", text)
    step = int(progress[-1][0]) if progress else 0
    if rows and step:
        rows[-1].setdefault("step", step)
    return rows, step


def config_for(marker):
    if marker.get("config"):
        path = contained(marker["config"])
    else:
        command = marker.get("command", [])
        if "--config" not in command:
            raise StudioError("Lần train chưa có cấu hình xác định.")
        path = contained(command[command.index("--config") + 1])
    config = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise StudioError("Cấu hình train không hợp lệ.")
    if (any(not isinstance(config.get(k), dict) for k in ("training", "lora", "quantization"))
            or any(not isinstance(config.get(k), str) for k in ("base_model", "output_dir", "dataset_manifest", "dataset_approval", "environment_lock"))
            or not isinstance(config.get("lora", {}).get("method", "lora"), str)):
        raise StudioError("Cấu hình train thiếu metadata bắt buộc.")
    return path, config


def marker_paths():
    paths = list(DATA.glob("*/sft-attempt*.json")) + list((STUDIO / "jobs").glob("*.json"))
    return sorted((contained(p) for p in paths if p.name != "sft-attempt-result.json"), key=lambda p: str(p))[-500:]


def key(path):
    return hashlib.sha256(str(contained(path).relative_to(DATA)).encode()).hexdigest()[:24]


def find_marker(run_id):
    if not re.fullmatch(r"[a-f0-9]{24}", str(run_id)):
        raise StudioError("Mã lần train không hợp lệ.")
    for path in marker_paths():
        if key(path) == run_id:
            return path
    raise StudioError("Không tìm thấy lần train.")


def process_matches(marker):
    try:
        process = psutil.Process(int(marker.get("pid", 0)))
        if not marker.get("processCreated") or abs(process.create_time() - marker["processCreated"]) > 1:
            return False
        return "training.studio" in process.cmdline() and "--worker" in process.cmdline()
    except (psutil.Error, ValueError, TypeError):
        return False


def existing_trainers():
    found = []
    for process in psutil.process_iter(["pid", "name", "cmdline", "cwd"]):
        try:
            args = process.info["cmdline"] or []
            relevant = "training.train_sft_lora" in args or ("training.studio" in args and "--worker" in args)
            if relevant and Path(process.info["cwd"] or ".").resolve() == ROOT.resolve():
                found.append(process.pid)
        except (psutil.Error, OSError):
            continue
    # Windows virtualenv launchers stay alive above the actual Python worker.
    ancestors = set()
    for pid in found:
        try:
            ancestors.update(p.pid for p in psutil.Process(pid).parents())
        except psutil.Error:
            pass
    return [pid for pid in found if pid not in ancestors]


def inputs_for(path, config):
    return {"configHash": sha(path), "datasetManifestHash": sha(contained(config["dataset_manifest"])),
            "reviewHash": sha(contained(config["dataset_approval"])),
            "environmentLockHash": sha(contained(ROOT / config["environment_lock"], ROOT / "training")),
            "inspectionHash": sha(contained(config["lora"]["target_modules_from"])),
            "datasetHash": read(config["dataset_manifest"])["datasetHash"], "smoke": False}


def checkpoints(config_path, config, verify=False):
    output = contained(config["output_dir"])
    expected = inputs_for(config_path, config)
    result = []
    for folder in sorted((output / "checkpoints").glob("checkpoint-*"), key=lambda p: p.name):
        folder = contained(folder, output)
        if not re.fullmatch(r"checkpoint-[1-9]\d*", folder.name):
            continue
        item = {"name": folder.name, "step": int(folder.name.split("-")[-1]), "verified": False,
                "integrityRecorded": (folder / "resume-integrity.json").is_file()}
        if verify:
            try:
                from training.train_sft_lora import check_resume_checkpoint
                check_resume_checkpoint(output, folder, expected)
                item.update(verified=True, algorithm="SHA-256", files=[
                    {"name": name, "sha256": value, "bytes": (folder / name).stat().st_size}
                    for name, value in read(folder / "resume-integrity.json")["files"].items()])
            except (ValueError, OSError, KeyError):
                item["error"] = "Checkpoint chưa hoàn chỉnh, hash đã đổi hoặc khác cấu hình gốc."
        result.append(item)
    return sorted(result, key=lambda item: item["step"], reverse=True)


def safe_config(config):
    return {field: config.get(field) for field in ("base_model", "base_revision", "device", "seed", "max_seq_length",
            "packing", "loss_mask_policy", "assistant_only_loss", "enable_thinking", "lora", "quantization", "training")}


def describe(path, detail=False):
    marker = read(path)
    config_path, config = config_for(marker)
    output = contained(config["output_dir"])
    log_path = contained(marker.get("log", path.with_suffix(".log")))
    log = log_tail(log_path)
    history, step = metrics_from_log(log)
    progress = read(output / "training-progress.json") if marker.get("studio") and marker.get("status") != "DRAFT" else {}
    if marker.get("progress") and marker.get("finishedAt"):
        progress = marker["progress"]
    if progress:
        history = progress.get("history", [])
        step = progress.get("step", step)
    # The config used by attempts 01/02 was subsequently corrected. Do not retrofit a later checkpoint.
    config_bound = marker.get("configSha256") == sha(config_path)
    pause = read(path.parent / "sft-pause.json")
    if pause.get("attempt") == path.name:
        history = pause.get("latestMetrics", history)
        step = pause.get("verifiedOptimizerStep", step)
    checkpoint_list = checkpoints(config_path, config, verify=True) if config_bound else []
    newest_verified_step = max((c["step"] for c in checkpoint_list if c["verified"]), default=0)
    if marker.get("status") == "DRAFT":
        checkpoint_list = []
    elif marker.get("finishedAt") or pause.get("attempt") == path.name:
        # Sessions share one resumable output; later saves do not belong to an earlier session.
        checkpoint_list = [c for c in checkpoint_list if c["step"] <= step]
    if checkpoint_list and not history:
        state = read(output / "checkpoints" / checkpoint_list[0]["name"] / "trainer_state.json")
        history = state.get("log_history", [])
        step = state.get("global_step", step)
    status = marker.get("disposition", marker.get("status", "UNKNOWN"))
    if pause.get("attempt") == path.name and not marker.get("studio"):
        status = pause.get("status", status)
    alive = process_matches(marker) if marker.get("studio") else False
    if status in ACTIVE and not alive:
        status = "INTERRUPTED"
    if alive:
        request = read(output / "control-request.json")
        status = {"pause": "PAUSE_REQUESTED", "stop": "STOP_REQUESTED"}.get(request.get("action"), "RUNNING")
    manifest = read(output / "run-manifest.json") if config_bound else {}
    completion_verified = False
    if status == "SUCCEEDED":
        try:
            completion_verified = (manifest.get("trainingReady") is True and manifest.get("steps", 0) > 0
                and all(manifest.get(k) == v for k, v in inputs_for(config_path, config).items() if k != "smoke")
                and math.isfinite(float(manifest["trainingLoss"]))
                and math.isfinite(float(manifest["validationMetrics"]["eval_loss"]))
                and sha(output / "adapter/adapter_model.safetensors") == manifest["adapterHash"]
                and sha(output / "adapter/adapter_config.json") == manifest["adapterConfigHash"])
        except (OSError, ValueError, KeyError, TypeError):
            pass
        if completion_verified:
            step = manifest["steps"]
        else:
            status = "UNKNOWN"
    latest = next((m for m in reversed(history) if "loss" in m), {})
    validation = manifest.get("validationMetrics", {}) if completion_verified else {}
    if not validation:
        validation = next((m for m in reversed(history) if "eval_loss" in m), {})
    dataset = read(config["dataset_manifest"])
    train_count = dataset.get("splits", {}).get("train", {}).get("count", 0)
    if not train_count:
        train_count = dataset.get("counts", {}).get("train", 0)
    if isinstance(train_count, dict):
        train_count = train_count.get("records", 0)
    planned = marker.get("plannedOptimizerSteps") or progress.get("maxSteps")
    settings = config.get("training", {})
    if not planned and isinstance(train_count, int) and train_count:
        planned = math.ceil(math.ceil(train_count / settings["per_device_batch_size"] / settings["gradient_accumulation_steps"]) * settings["epochs"])
    error = "Kết quả cuối chưa khớp manifest, metrics hữu hạn hoặc hash adapter." if marker.get("status") == "SUCCEEDED" and not completion_verified else None
    if status in ("FAILED", "INTERRUPTED"):
        if "sequence length" in log or "truncation is forbidden" in log or "4123" in log:
            error = "Không qua kiểm tra độ dài chuỗi; không truncate dữ liệu."
        elif marker.get("exitCode") == 1073807364:
            error = "Windows 0x40010004 · nguyên nhân chưa xác minh."
        else:
            error = marker.get("error", "Tiến trình đã kết thúc; xem log để kiểm tra nguyên nhân.")
    result = {"id": key(path), "name": marker.get("name", path.stem), "runId": config.get("run_id"),
              "model": config.get("base_model"), "revision": config.get("base_revision"),
              "method": config.get("lora", {}).get("method", "lora"), "device": config.get("device"),
              "dtype": config.get("quantization", {}).get("compute_dtype"), "status": status,
              "step": step, "plannedSteps": planned, "metrics": latest, "validation": validation,
              "startedAt": marker.get("startedAt"), "finishedAt": marker.get("finishedAt"),
              "pausedAt": pause.get("pausedAt") if pause.get("attempt") == path.name else marker.get("pausedAt"),
              "datasetHash": dataset.get("datasetHash"), "configHash": sha(config_path),
              "checkpoints": checkpoint_list, "error": public_text(error) if error else None,
              "completionVerified": completion_verified,
              "active": alive, "configBound": config_bound,
              "canStart": marker.get("studio") is True and config_bound and status == "DRAFT" and not output.exists(),
              "canResume": bool(config_bound and step >= newest_verified_step and any(c["verified"] for c in checkpoint_list) and not manifest and not alive and status in ("PAUSED_BY_USER", "STOPPED_BY_USER", "INTERRUPTED", "FAILED")),
              "canPause": bool(alive and status == "RUNNING")}
    if detail:
        result.update(config=safe_config(config), history=history[-500:], log=log,
                      worker={"alive": alive, "managedByStudio": bool(marker.get("studio"))},
                      result={**{k: manifest.get(k) for k in ("steps", "trainingLoss", "validationMetrics", "adapterHash")},
                              "trainingReady": completion_verified})
        result["canResume"] = result["canResume"] and any(c["verified"] for c in checkpoint_list)
    else:
        result["checkpoints"] = [{k: v for k, v in c.items() if k != "files"} for c in checkpoint_list]
    return result


def dataset_info(config):
    manifest_path = contained(config["dataset_manifest"])
    manifest = read(manifest_path)
    review = read(config["dataset_approval"])
    same = review.get("datasetHash") == manifest.get("datasetHash") and review.get("datasetManifestHash") == sha(manifest_path)
    counts = {}
    for split, item in manifest.get("splits", {}).items():
        counts[split] = item.get("count")
    if not counts:
        counts = manifest.get("counts", {})
    campaign = manifest_path.parent.parent.parent
    audit = read(campaign / "final-audit.json")
    return {"id": key(manifest_path), "name": campaign.name, "hash": manifest.get("datasetHash"),
            "approved": same and review.get("approved") is True,
            "records": counts, "articlesByLevel": audit.get("status", {}).get("counts", {}),
            "articles": audit.get("status", {}).get("approvedTasks"),
            "reviewers": review.get("originalArticleReviewers", {}), "humanApproved": review.get("humanApproved", False),
            "auditPassed": audit.get("passed"), "holdoutApproved": review.get("holdoutAuditApproved"),
            "nearDuplicateApproved": review.get("nearDuplicateAuditApproved")}


def snapshot():
    runs, warnings, datasets = [], [], {}
    paths = marker_paths()
    launched_drafts = set()
    for path in paths:
        try:
            launched_drafts.add(read(path).get("sourceRunId"))
        except (ValueError, OSError, TypeError, AttributeError):
            pass
    for path in paths:
        try:
            if key(path) in launched_drafts and read(path).get("status") == "DRAFT":
                continue
            row = describe(path)
            runs.append(row)
            _, config = config_for(read(path))
            info = dataset_info(config)
            datasets[info["hash"]] = info
        except (ValueError, OSError, KeyError, TypeError):
            warnings.append("Có metadata không đọc được; mục đó chưa được xác minh.")
    runs.sort(key=lambda r: r.get("startedAt") or "9999", reverse=True)
    memory = psutil.virtual_memory()
    disk = psutil.disk_usage(str(DATA))
    return {"observedAt": now(), "runs": runs, "datasets": list(datasets.values()), "warnings": warnings,
            "resources": {"cpuPercent": psutil.cpu_percent(interval=0.15), "cpuCores": psutil.cpu_count(),
                          "ramTotal": memory.total, "ramAvailable": memory.available, "ramPercent": memory.percent,
                          "diskFree": disk.free, "diskTotal": disk.total,
                          "gpu": None, "gpuReason": "Chưa kết nối bộ đo GPU; các lần train hiện tại dùng CPU.",
                          "scope": "Máy chạy Training Studio; không phải chỉ riêng tiến trình train"},
            "externalWorkerCount": len(existing_trainers()),
            "capabilities": {"create": True, "models": [{"id": "Qwen/Qwen3.5-4B", "revision": "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a"}],
                             "modelNote": "Trainer đã kiểm chứng hỗ trợ Qwen3.5-4B. Các model khác cần bộ kiểm tra tương ứng trước khi chạy."}}


@contextlib.contextmanager
def studio_lock(wait_seconds=0):
    STUDIO.mkdir(parents=True, exist_ok=True)
    with (STUDIO / "worker.lock").open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        deadline = time.monotonic() + wait_seconds
        while True:
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError as exc:
                if time.monotonic() >= deadline:
                    raise StudioError("Đã có worker hoặc thao tác train đang xử lý.") from exc
                time.sleep(0.05)
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def number(params, field, default, low, high, integer=False):
    value = params.get(field, default)
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
        raise StudioError(f"Tham số {field} phải nằm trong khoảng {low}–{high}.")
    if integer and type(value) is not int:
        raise StudioError(f"Tham số {field} phải là số nguyên.")
    return value


def create_draft(body, actor):
    source = find_marker(body.get("sourceRunId"))
    _, template = config_for(read(source))
    name = str(body.get("name", "")).strip()
    if not 1 <= len(name) <= 100 or any(ord(c) < 32 for c in name):
        raise StudioError("Tên lần train cần 1–100 ký tự.")
    params = body.get("parameters", {})
    if not isinstance(params, dict):
        raise StudioError("Tham số train không hợp lệ.")
    known = {"epochs", "learningRate", "batchSize", "accumulation", "rank", "alpha", "dropout", "maxLength", "seed", "warmup"}
    if set(params) - known or set(body) - {"sourceRunId", "name", "parameters"}:
        raise StudioError("Không hỗ trợ ghi đè model, đường dẫn hoặc lệnh chạy.")
    info = dataset_info(template)
    if not info["approved"]:
        raise StudioError("Dataset chưa có phê duyệt khớp hash.")
    config = copy.deepcopy(template)
    if config.get("device") != "cpu" or config.get("base_model") != "Qwen/Qwen3.5-4B":
        raise StudioError("Chọn cấu hình CPU Qwen3.5-4B đã được kiểm chứng.")
    run_id = "studio-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:8]
    config.update(run_id=run_id, output_dir=f"data/fine-tuning/runs/{run_id}",
                  max_seq_length=number(params, "maxLength", 4608, 4608, 16384, True),
                  seed=number(params, "seed", 17, 0, 2147483647, True))
    config["training"].update(epochs=number(params, "epochs", 2, 0.1, 20),
                              learning_rate=number(params, "learningRate", 0.0001, 0.0000001, 0.01),
                              per_device_batch_size=number(params, "batchSize", 1, 1, 16, True),
                              gradient_accumulation_steps=number(params, "accumulation", 8, 1, 128, True),
                              warmup_ratio=number(params, "warmup", 0.05, 0, 1), checkpoint_steps=1)
    config["lora"].update(r=number(params, "rank", 16, 1, 128, True),
                          alpha=number(params, "alpha", 32, 1, 256, True),
                          dropout=number(params, "dropout", 0.05, 0, 0.9))
    folder = STUDIO / "configs"
    folder.mkdir(parents=True, exist_ok=True)
    config_path = folder / f"{run_id}.yaml"
    config_path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False), encoding="utf-8")
    jobs = STUDIO / "jobs"
    jobs.mkdir(exist_ok=True)
    path = jobs / f"{run_id}.json"
    save(path, {"studio": True, "status": "DRAFT", "name": name, "config": str(config_path.relative_to(ROOT)),
                "configSha256": sha(config_path), "createdAt": now(), "createdBy": str(actor),
                "log": str((STUDIO / "logs" / f"{run_id}.log").relative_to(ROOT))})
    return {"id": key(path), "status": "DRAFT", "message": "Đã tạo cấu hình nháp; chưa khởi chạy train."}


def start_worker(path, action, body, actor):
    if body.get("confirm") is not True or body.get("allowBelowMemoryEstimate") is not True:
        raise StudioError("Cần xác nhận chạy thật và quyền thử dưới ước lượng bộ nhớ.")
    with studio_lock():
        if existing_trainers():
            raise StudioError("Đã có worker train; không khởi chạy trùng.")
        item = describe(path, detail=True)
        if body.get("configHash") != item["configHash"]:
            raise StudioError("Cấu hình đã thay đổi; tải lại trước khi xác nhận.")
        config_path, config = config_for(read(path))
        if action == "start" and not item["canStart"]:
            raise StudioError("Chỉ khởi chạy nháp mới có output chưa tồn tại.")
        if action == "resume" and not item["canResume"]:
            raise StudioError("Không có checkpoint hợp lệ để tiếp tục cùng cấu hình.")
        # This performs real data/review checks without loading or tokenizing any model.
        from training.train_sft_lora import checked_inputs, verify_environment
        checked_inputs(config_path)
        verify_environment(ROOT / config["environment_lock"])
        new_id = "session-" + uuid.uuid4().hex
        jobs, logs = STUDIO / "jobs", STUDIO / "logs"
        jobs.mkdir(parents=True, exist_ok=True)
        logs.mkdir(exist_ok=True)
        job = jobs / f"{new_id}.json"
        marker = {"studio": True, "status": "STARTING", "name": item["name"], "config": str(config_path.relative_to(ROOT)),
                  "configSha256": sha(config_path), "startedAt": now(), "requestedBy": str(actor),
                  "sourceRunId": item["id"], "log": str((logs / f"{new_id}.log").relative_to(ROOT)),
                  "plannedOptimizerSteps": item["plannedSteps"], "operation": action}
        if action == "resume":
            marker["resumeCheckpoint"] = next(c["name"] for c in item["checkpoints"] if c["verified"])
        save(job, marker)
        flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        executable = Path(sys.executable)
        pythonw = executable.with_name("pythonw.exe")
        executable = pythonw if os.name == "nt" and pythonw.is_file() else executable
        env = os.environ.copy()
        env.update(HF_HOME=str(DATA / "hf-cache"), HF_HUB_OFFLINE="1", HF_DATASETS_OFFLINE="1", PYTHONUTF8="1", PYTHONUNBUFFERED="1")
        try:
            with contained(marker["log"]).open("xb") as stream:
                process = subprocess.Popen([str(executable), "-m", "training.studio", "--worker", key(job)], cwd=ROOT,
                    env=env, stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                    creationflags=flags, start_new_session=os.name != "nt")
            marker.update(pid=process.pid, launcherPid=process.pid, processCreated=psutil.Process(process.pid).create_time())
            save(job, marker)
        except (OSError, psutil.Error):
            marker.update(status="FAILED", error="Không khởi tạo được worker.", finishedAt=now())
            save(job, marker)
            raise StudioError("Không khởi tạo được worker; đã giữ dấu vết lỗi.")
    return {"id": key(job), "status": "STARTING", "message": "Đã gửi lệnh khởi chạy; theo dõi các gate và log."}


def worker(run_id):
    path = find_marker(run_id)
    # Parent records PID and creation time before releasing the exclusive launch lock.
    for _ in range(100):
        if read(path).get("launcherPid"):
            break
        time.sleep(0.05)
    with studio_lock(wait_seconds=5):
        marker = read(path)
        self_process = psutil.Process()
        family = {os.getpid(), os.getppid()}
        try:
            family.update(p.pid for p in self_process.parents())
        except psutil.Error:
            pass
        if marker.get("status") != "STARTING" or marker.get("launcherPid") not in family or any(p not in family for p in existing_trainers()):
            raise StudioError("Worker identity hoặc quyền sở hữu không hợp lệ.")
        config_path, config = config_for(marker)
        output = contained(config["output_dir"])
        marker.update(status="RUNNING", pid=os.getpid(), processCreated=self_process.create_time())
        save(path, marker)
        try:
            if sha(config_path) != marker.get("configSha256"):
                raise StudioError("Cấu hình đã thay đổi sau khi nhận lệnh chạy.")
            if marker.get("resumeCheckpoint"):
                # Keep old request and disposition evidence; only retire the request when explicitly resuming.
                for filename in ("control-request.json", "pause-result.json"):
                    source = output / filename
                    if source.exists():
                        source.rename(output / f"{filename}.{uuid.uuid4().hex}.history")
            from training.train_sft_lora import run
            report = run(config_path, attempt_below_memory_estimate=True,
                         resume_from_checkpoint=output / "checkpoints" / marker["resumeCheckpoint"] if marker.get("resumeCheckpoint") else None)
            marker.update(status=report.get("status", "SUCCEEDED"), steps=report.get("steps"))
            if report.get("status"):
                marker["pausedAt"] = now()
        except Exception:
            import traceback
            traceback.print_exc()
            marker.update(status="FAILED", error="Trainer kết thúc với lỗi; xem log và checkpoint đã giữ.")
        marker["finishedAt"] = now()
        marker["progress"] = read(output / "training-progress.json")
        save(path, marker)


def handle(request):
    action = request.get("action", "snapshot")
    if action == "snapshot":
        return snapshot()
    if action == "detail":
        return describe(find_marker(request.get("id")), detail=True)
    if action == "create":
        return create_draft(request.get("body", {}), request.get("actor", "admin"))
    if action in ("start", "resume"):
        return start_worker(find_marker(request.get("id")), action, request.get("body", {}), request.get("actor", "admin"))
    if action in ("pause", "stop"):
        path = find_marker(request.get("id"))
        marker = read(path)
        if request.get("body", {}).get("confirm") is not True:
            raise StudioError("Cần xác nhận thao tác điều khiển.")
        if not process_matches(marker):
            raise StudioError("Worker không còn chạy hoặc không do Studio quản lý.")
        config_path, config = config_for(marker)
        if sha(config_path) != request.get("body", {}).get("configHash"):
            raise StudioError("Cấu hình đã thay đổi; tải lại trước khi xác nhận.")
        output = contained(config["output_dir"])
        if not (output / "run-inputs.json").is_file():
            raise StudioError("Worker đang tiền kiểm; chờ khởi tạo output trước khi yêu cầu dừng an toàn.")
        save(output / "control-request.json", {"action": action, "requestedAt": now(), "requestedBy": str(request.get("actor", "admin"))})
        return {"status": "PAUSE_REQUESTED" if action == "pause" else "STOP_REQUESTED",
                "message": "Đang chờ hoàn tất optimizer step và lưu checkpoint. Worker vẫn đang tính."}
    raise StudioError("Thao tác không được hỗ trợ.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker")
    args = parser.parse_args()
    if args.worker:
        worker(args.worker)
        return
    try:
        payload = sys.stdin.read(32_769)
        if len(payload) > 32_768:
            raise StudioError("Yêu cầu vượt giới hạn.")
        result = {"ok": True, "data": handle(json.loads(payload))}
    except StudioError as exc:
        result = {"ok": False, "error": str(exc)}
    except Exception:
        result = {"ok": False, "error": "Không đọc được runtime train; kiểm tra cấu hình và metadata trên máy chủ."}
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__":
    main()
