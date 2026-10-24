"""Control-boundary fixtures only: these tests never load the real model or dataset."""
import os
from types import SimpleNamespace

import pytest
import yaml

from training import studio
from training import train_sft_lora as runner


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    monkeypatch.setattr(studio, "ROOT", tmp_path)
    monkeypatch.setattr(studio, "DATA", tmp_path / "data/fine-tuning")
    monkeypatch.setattr(studio, "STUDIO", studio.DATA / "studio")
    monkeypatch.setattr(studio, "existing_trainers", lambda: [])
    campaign = studio.DATA / "fixture"
    campaign.mkdir(parents=True)
    manifest = campaign / "export/bundle/manifest.json"
    manifest.parent.mkdir(parents=True)
    studio.save(manifest, {"datasetHash": "fixture-hash", "counts": {"train": 8, "validation": 2, "test": 2}})
    review = campaign / "review.json"
    studio.save(review, {"approved": True, "datasetHash": "fixture-hash", "datasetManifestHash": studio.sha(manifest)})
    inspection = campaign / "inspection.json"
    studio.save(inspection, {})
    lock = tmp_path / "training/environment.lock"
    lock.parent.mkdir()
    lock.write_text("fixture", encoding="utf-8")
    config = {"run_id": "fixture", "base_model": "Qwen/Qwen3.5-4B", "base_revision": "a" * 40,
              "device": "cpu", "max_seq_length": 4608, "seed": 17,
              "dataset_manifest": str(manifest.relative_to(tmp_path)), "dataset_approval": str(review.relative_to(tmp_path)),
              "environment_lock": "training/environment.lock", "output_dir": "data/fine-tuning/runs/fixture",
              "lora": {"target_modules_from": str(inspection.relative_to(tmp_path)), "method": "lora", "r": 16, "alpha": 32},
              "quantization": {"compute_dtype": "float32"},
              "training": {"epochs": 2, "per_device_batch_size": 1, "gradient_accumulation_steps": 8}}
    config_path = campaign / "config.yaml"
    config_path.write_text(yaml.safe_dump(config), encoding="utf-8")
    marker = campaign / "sft-attempt-03.json"
    studio.save(marker, {"status": "FAILED", "config": str(config_path.relative_to(tmp_path)),
                         "configSha256": studio.sha(config_path), "exitCode": -1})
    return SimpleNamespace(config=config, config_path=config_path, marker=marker, campaign=campaign,
                           output=studio.DATA / "runs/fixture", source=studio.key(marker))


def checkpoint(runtime, step=1):
    folder = runtime.output / f"checkpoints/checkpoint-{step}"
    folder.mkdir(parents=True)
    for name in ("adapter_model.safetensors", "adapter_config.json", "optimizer.pt", "scheduler.pt", "rng_state.pth"):
        (folder / name).write_bytes(b"synthetic-state")
    studio.save(folder / "trainer_state.json", {"global_step": step, "log_history": [{"step": step, "loss": .8}]})
    studio.save(folder / "resume-integrity.json", {"step": step, "files": {f.name: studio.sha(f) for f in folder.iterdir()}})
    studio.save(runtime.output / "run-inputs.json", studio.inputs_for(runtime.config_path, runtime.config))
    return folder


def test_paths_and_ids_cannot_escape_runtime(runtime):
    with pytest.raises(studio.StudioError):
        studio.contained("data/fine-tuning/../../../private")
    with pytest.raises(studio.StudioError):
        studio.find_marker("../../config")
    with pytest.raises(studio.StudioError):
        studio.contained(runtime.config_path, runtime.output)


def test_atomic_metadata_replace_tolerates_brief_windows_reader_lock(runtime, monkeypatch):
    from pathlib import Path
    source, target = runtime.campaign / "new.tmp", runtime.campaign / "existing.json"
    source.write_text("new", encoding="utf-8")
    target.write_text("old", encoding="utf-8")
    original = Path.replace
    attempts = []
    def briefly_locked(path, destination):
        attempts.append(1)
        if len(attempts) == 1:
            assert target.read_text(encoding="utf-8") == "old"
            raise PermissionError("reader has not released the file yet")
        return original(path, destination)
    monkeypatch.setattr(Path, "replace", briefly_locked)
    monkeypatch.setattr(runner.time, "sleep", lambda seconds: None)
    runner.replace_runtime_file(source, target)
    assert len(attempts) == 2 and target.read_text(encoding="utf-8") == "new"


def test_logs_redact_authorization_quoted_secrets_and_user_paths():
    text = studio.public_text('Authorization: Bearer abc-private-token\n{"password": "some secret"}\napi_key=private-value\nC:\\Users\\Person\\private')
    assert not any(s in text for s in ("abc-private-token", "some secret", "private-value", "Person"))


def test_string_metrics_are_finite_and_step_not_inferred_from_tail():
    rows, step = studio.metrics_from_log("{'loss': '0.7', 'grad_norm': 'nan', 'learning_rate': '1e-5'}")
    assert rows == [{"loss": .7, "learning_rate": .00001}] and step == 0
    rows, step = studio.metrics_from_log("{'loss': '0.7'}\r 2/200 [50:30<]")
    assert step == 2 and rows[-1]["step"] == 2


@pytest.mark.parametrize("fault", [False, True])
def test_pause_disposition_and_native_checkpoint_gate(runtime, fault):
    folder = checkpoint(runtime)
    studio.save(runtime.campaign / "sft-pause.json", {"attempt": runtime.marker.name, "status": "PAUSED_BY_USER",
                "verifiedOptimizerStep": 1, "latestMetrics": [{"step": 1, "loss": .8}]})
    if fault:
        (folder / "optimizer.pt").write_bytes(b"corrupt")
    summary = studio.describe(runtime.marker)
    assert summary["status"] == "PAUSED_BY_USER" and summary["step"] == 1
    assert summary["canResume"] is not fault
    assert summary["checkpoints"][0]["verified"] is not fault


def test_changed_config_never_borrows_checkpoint_or_starts_draft(runtime):
    checkpoint(runtime)
    marker = studio.read(runtime.marker)
    marker.update(studio=True, status="DRAFT")
    studio.save(runtime.marker, marker)
    runtime.config_path.write_text(runtime.config_path.read_text(encoding="utf-8") + "# changed\n", encoding="utf-8")
    summary = studio.describe(runtime.marker)
    assert not summary["configBound"] and not summary["canStart"] and not summary["canResume"]
    assert not summary["checkpoints"]


def test_historical_pause_never_resumes_older_state_or_borrows_future_validation(runtime):
    checkpoint(runtime, 1)
    checkpoint(runtime, 2)
    studio.save(runtime.campaign / "sft-pause.json", {"attempt": runtime.marker.name, "status": "PAUSED_BY_USER",
                "verifiedOptimizerStep": 1, "latestMetrics": [{"step": 1, "loss": .8}]})
    summary = studio.describe(runtime.marker)
    assert summary["step"] == 1 and summary["checkpoints"][0]["step"] == 1
    assert not summary["canResume"]
    studio.save(runtime.output / "run-manifest.json", {"trainingReady": True, "steps": 200,
                "validationMetrics": {"eval_loss": .2}})
    summary = studio.describe(runtime.marker)
    assert summary["status"] == "PAUSED_BY_USER" and not summary["validation"]


def test_create_only_draft_with_distinct_output_and_preserved_source(runtime):
    before = runtime.config_path.read_bytes()
    result = studio.create_draft({"sourceRunId": runtime.source, "name": "Thử nghiệm", "parameters": {"rank": 8}}, 42)
    marker = studio.read(studio.find_marker(result["id"]))
    path, config = studio.config_for(marker)
    assert result["status"] == "DRAFT" and not (studio.ROOT / config["output_dir"]).exists()
    assert marker["createdBy"] == "42" and config["lora"]["r"] == 8
    assert config["training"]["checkpoint_steps"] == 1
    assert runtime.config_path.read_bytes() == before and path != runtime.config_path


@pytest.mark.parametrize("parameters", [{"rank": True}, {"maxLength": 4096}, {"epochs": float("nan")}, {"command": "anything"}])
def test_unsafe_parameters_rejected_before_creating_runtime(runtime, parameters):
    with pytest.raises(studio.StudioError):
        studio.create_draft({"sourceRunId": runtime.source, "name": "bad", "parameters": parameters}, 1)
    assert not studio.STUDIO.exists()


def test_exclusive_lock_rejects_second_owner(runtime):
    with studio.studio_lock():
        with pytest.raises(studio.StudioError):
            with studio.studio_lock():
                pytest.fail("second owner acquired worker lock")


def test_duplicate_worker_blocks_launch_before_any_mutation(runtime, monkeypatch):
    monkeypatch.setattr(studio, "existing_trainers", lambda: [123])
    with pytest.raises(studio.StudioError, match="worker train"):
        studio.start_worker(runtime.marker, "resume", {"confirm": True, "allowBelowMemoryEstimate": True}, 1)
    assert not (studio.STUDIO / "jobs").exists()


def test_pause_requires_live_identity_confirmation_and_matching_config(runtime, monkeypatch):
    monkeypatch.setattr(studio, "process_matches", lambda marker: True)
    checkpoint(runtime)
    request = {"action": "pause", "id": runtime.source, "actor": 42,
               "body": {"confirm": True, "configHash": studio.sha(runtime.config_path)}}
    result = studio.handle(request)
    assert result["status"] == "PAUSE_REQUESTED"
    assert studio.read(runtime.output / "control-request.json")["requestedBy"] == "42"
    request["body"]["configHash"] = "changed"
    with pytest.raises(studio.StudioError):
        studio.handle(request)
    monkeypatch.setattr(studio, "process_matches", lambda marker: False)
    with pytest.raises(studio.StudioError):
        studio.handle(request)


def test_process_identity_checks_creation_time_before_accepting_reused_pid(runtime, monkeypatch):
    monkeypatch.setattr(studio.psutil, "Process", lambda pid: SimpleNamespace(create_time=lambda: 123, cmdline=lambda: ["python", "-m", "training.studio", "--worker", "id"]))
    assert not studio.process_matches({"pid": 100, "processCreated": 100})
    assert studio.process_matches({"pid": 100, "processCreated": 123})


@pytest.mark.parametrize("launcher", ["self", "parent"])
def test_worker_claims_verified_launcher_family_and_keeps_pause_result(runtime, monkeypatch, launcher):
    marker = studio.read(runtime.marker)
    marker.update(studio=True, status="STARTING", launcherPid=os.getpid() if launcher == "self" else os.getppid())
    studio.save(runtime.marker, marker)
    checkpoint(runtime)
    def fake_run(path, **kwargs):
        studio.save(runtime.output / "training-progress.json", {"step": 2, "history": [{"step": 2, "loss": .6}]})
        return {"status": "PAUSED_BY_USER", "steps": 2}
    monkeypatch.setattr(runner, "run", fake_run)
    studio.worker(runtime.source)
    saved = studio.read(runtime.marker)
    assert saved["status"] == "PAUSED_BY_USER" and saved["pid"] == os.getpid()
    assert saved["progress"]["step"] == 2


def test_config_change_after_launch_fails_before_trainer(runtime, monkeypatch):
    marker = studio.read(runtime.marker)
    marker.update(studio=True, status="STARTING", launcherPid=os.getpid(), configSha256="wrong")
    studio.save(runtime.marker, marker)
    monkeypatch.setattr(runner, "run", lambda *args, **kwargs: pytest.fail("trainer called with changed config"))
    studio.worker(runtime.source)
    assert studio.read(runtime.marker)["status"] == "FAILED"


def test_start_uses_fixed_detached_worker_and_verified_gates(runtime, monkeypatch):
    created = studio.create_draft({"sourceRunId": runtime.source, "name": "new", "parameters": {}}, 42)
    path = studio.find_marker(created["id"])
    calls = []
    monkeypatch.setattr(runner, "checked_inputs", lambda config: calls.append("data-gate"))
    monkeypatch.setattr(runner, "verify_environment", lambda lock: calls.append("environment-gate"))
    def launch(args, **kwargs):
        calls.append((args, kwargs))
        return SimpleNamespace(pid=os.getpid())
    monkeypatch.setattr(studio.subprocess, "Popen", launch)
    result = studio.start_worker(path, "start", {"confirm": True, "allowBelowMemoryEstimate": True,
             "configHash": studio.describe(path)["configHash"]}, 42)
    assert calls[:2] == ["data-gate", "environment-gate"]
    args, options = calls[2]
    assert args[1:4] == ["-m", "training.studio", "--worker"] and "shell" not in options
    assert options["env"]["HF_HUB_OFFLINE"] == "1" and options["stdin"] == studio.subprocess.DEVNULL
    assert result["id"] != created["id"] and result["status"] == "STARTING"
    assert not (studio.ROOT / studio.config_for(studio.read(path))[1]["output_dir"]).exists()


def test_final_success_requires_finite_metrics_bound_inputs_and_adapter_hash(runtime):
    runtime.output.mkdir(parents=True)
    adapter = runtime.output / "adapter"
    adapter.mkdir()
    for name in ("adapter_model.safetensors", "adapter_config.json"):
        (adapter / name).write_bytes(b"synthetic-final-adapter")
    manifest = {**studio.inputs_for(runtime.config_path, runtime.config), "trainingReady": True, "steps": 2,
                "trainingLoss": .7, "validationMetrics": {"eval_loss": .8},
                "adapterHash": studio.sha(adapter / "adapter_model.safetensors"),
                "adapterConfigHash": studio.sha(adapter / "adapter_config.json")}
    studio.save(runtime.output / "run-manifest.json", manifest)
    marker = studio.read(runtime.marker)
    marker.update(status="SUCCEEDED")
    studio.save(runtime.marker, marker)
    assert studio.describe(runtime.marker)["completionVerified"]
    (adapter / "adapter_model.safetensors").write_bytes(b"corrupt")
    summary = studio.describe(runtime.marker)
    assert summary["status"] == "UNKNOWN" and not summary["completionVerified"]
