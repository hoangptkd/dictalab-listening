"""Synthetic metadata only. No GPU, notebook execution, dataset text or network requests."""
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from training import colab_reporter as reporter


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


@pytest.fixture
def fixture(tmp_path):
    cfg = {"base_model": "Qwen/Qwen3.5-4B", "base_revision": "a" * 40,
           "dataset_manifest": "manifest.json", "dataset_approval": "review.json", "environment_lock": "lock.json",
           "device": "cuda", "output_dir": "output", "seed": 17, "max_seq_length": 4608,
           "packing": False, "loss_mask_policy": "last-assistant-only", "assistant_only_loss": True, "enable_thinking": False,
           "lora": {"r": 16, "alpha": 32, "dropout": .05, "target_modules_from": "inspection.json"},
           "quantization": {"compute_dtype": "float16"},
           "training": {"epochs": 2, "per_device_batch_size": 1, "gradient_accumulation_steps": 8, "learning_rate": .0001, "checkpoint_steps": 10}}
    config = tmp_path / "config.yaml"
    config.write_text(yaml.safe_dump(cfg), encoding="utf-8")
    save(tmp_path / "manifest.json", {"datasetHash": "b" * 64, "counts": {"train": 8}})
    for name in ("review.json", "lock.json", "inspection.json", "training/train_sft_lora.py"):
        save(tmp_path / name, {})
    options = dict(root=tmp_path, config=config, status_file=tmp_path / "status.json", log=tmp_path / "log", phase="smoke", measure=False)
    return SimpleNamespace(root=tmp_path, config=config, output=tmp_path / "output", options=options)


def completed(fixture, phase="smoke"):
    f = fixture
    f.options["phase"] = phase
    first = reporter.capture(**f.options)
    adapter = f.output / "adapter"
    save(adapter / "adapter_model.safetensors", {"synthetic": True})
    save(adapter / "adapter_config.json", {"synthetic": True})
    steps = 1 if phase == "smoke" else 2
    result = {**{k: v for k, v in first["bindings"].items() if k != "trainerHash"},
              "steps": steps, "mode": phase, "trainingReady": phase == "full", "baseModel": first["model"],
              "baseRevision": first["revision"], "maskPolicy": "last-assistant-only", "truncationCount": 0,
              "adapterHash": reporter.sha(adapter / "adapter_model.safetensors"),
              "adapterConfigHash": reporter.sha(adapter / "adapter_config.json"),
              "validationMetrics": {"eval_loss": .7} if phase == "full" else {}}
    save(f.output / "run-manifest.json", result)
    save(f.root / "status.json", {"exitCode": 0})
    save(f.output / "optimizer-evidence.json", {"actualOptimizerUpdates": steps, "changedAdapterTensors": 32, "finiteLoggedGradients": True, "initialAmpScale": 1.0})


def test_waiting_run_has_no_fake_optimizer_steps_from_model_loading(fixture):
    fixture.options["log"].write_text("Loading weights 426/426 [00:01]", encoding="utf-8")
    report = reporter.capture(**fixture.options)
    assert report["status"] == "WAITING" and report["step"] == 0 and report["plannedSteps"] == 1
    assert not report["resources"] and not report["artifacts"]


@pytest.mark.parametrize("fault", ["gradient", "zero_update", "adapter", "config", "mask"])
def test_exit_zero_never_overrides_missing_update_or_changed_artifacts(fixture, fault):
    completed(fixture)
    if fault == "adapter":
        (fixture.output / "adapter/adapter_model.safetensors").write_bytes(b"corrupt")
    elif fault == "config":
        fixture.config.write_text(fixture.config.read_text() + "# changed\n", encoding="utf-8")
    elif fault == "mask":
        value = reporter.read(fixture.output / "run-manifest.json"); value["truncationCount"] = 1
        save(fixture.output / "run-manifest.json", value)
    else:
        value = reporter.read(fixture.output / "optimizer-evidence.json")
        value["finiteLoggedGradients" if fault == "gradient" else "actualOptimizerUpdates"] = False if fault == "gradient" else 0
        save(fixture.output / "optimizer-evidence.json", value)
    assert reporter.capture(**fixture.options)["status"] == "FAILED"


def test_smoke_and_full_remain_separate_and_never_read_dataset_text(fixture):
    completed(fixture)
    report = reporter.capture(**fixture.options)
    assert report["status"] == "SMOKE_PASSED" and report["step"] == report["plannedSteps"] == 1
    assert not report["validation"] and "log" not in report
    assert str(fixture.root) not in json.dumps(report)
    completed(fixture, "full")
    report = reporter.capture(**fixture.options)
    assert report["status"] == "REPORTED_COMPLETE" and report["step"] == report["plannedSteps"] == 2


def test_nan_gradient_smoke_fails_even_if_older_manifest_says_success(fixture):
    completed(fixture)
    (fixture.output / "optimizer-evidence.json").unlink()
    fixture.options["log"].write_text("{'loss': 0.4, 'grad_norm': nan}\n1/1 [00:01]", encoding="utf-8")
    report = reporter.capture(**fixture.options)
    assert report["status"] == "FAILED" and report["failure"] == "nonfinite_gradient"
    json.dumps(report, allow_nan=False)


def test_manifest_without_wrapper_exit_waits_for_evidence_instead_of_claiming_failure_or_success(fixture):
    completed(fixture)
    (fixture.root / "status.json").unlink()
    assert reporter.capture(**fixture.options)["status"] == "VERIFYING"


@pytest.mark.parametrize("url", ["http://example.com/api/training/telemetry/" + "colab-" + "a" * 32,
                                  "https://example.com/other", "https://secret@example.com/api/training/telemetry/colab-" + "a" * 32,
                                  "https://example.com/api/training/telemetry/colab-" + "a" * 32 + "?token=secret"])
def test_network_destination_rejects_plaintext_credentials_and_arbitrary_routes(fixture, url):
    with pytest.raises(ValueError):
        reporter.Reporter(url, "x" * 43, **{k: v for k, v in fixture.options.items() if k != "measure"})


def test_telemetry_failure_never_launches_or_terminates_trainer(fixture, monkeypatch):
    r = reporter.Reporter("https://example.com/api/training/telemetry/colab-" + "a" * 32, "x" * 43,
                          **{k: v for k, v in fixture.options.items() if k != "measure"})
    monkeypatch.setattr(reporter, "capture", lambda **options: {})
    def fail(report): raise ValueError("private-secret")
    monkeypatch.setattr(r, "send", fail)
    assert not r.once() and "private-secret" not in str(r.state)
    assert not r.thread
    r.stop()


def test_csrf_and_snapshot_identify_the_client_without_exposing_token(fixture):
    from email.message import Message
    from io import BytesIO
    import urllib.request
    import urllib.response

    requests = []

    class Gateway(urllib.request.HTTPSHandler):
        handler_order = 100

        def https_open(self, request):
            requests.append(request)
            if request.get_method() == "GET":
                body = {"headerName": "X-XSRF-TOKEN", "token": "csrf-value"}
            else:
                body = {"accepted": True, "sequence": 123}
            response = urllib.response.addinfourl(BytesIO(json.dumps(body).encode()), Message(), request.full_url, 200)
            response.msg = "OK"
            return response

    client = reporter.Reporter("https://example.com/api/training/telemetry/colab-" + "a" * 32, "x" * 43,
                               **{k: v for k, v in fixture.options.items() if k != "measure"})
    client.opener.add_handler(Gateway())
    client.send({"sequence": 123})
    assert len(requests) == 2
    for request in requests:
        assert request.get_header("User-agent") == "DictaLab-Training-Telemetry/1.0"
        assert request.get_header("Accept") == "application/json"
        assert "x" * 43 not in request.full_url
    assert not requests[0].has_header("Authorization")
    assert requests[1].get_header("Authorization") == "Bearer " + "x" * 43
    assert requests[1].get_header("X-xsrf-token") == "csrf-value"


def test_attempt_identity_survives_reporter_restart_but_changes_with_marker_or_code(fixture):
    save(fixture.root / "status.json", {"startedAt": "2026-10-26T00:00:00Z", "restoredCheckpointSteps": 0})
    first = reporter.capture(**fixture.options)
    assert first["attempt"]["fromStep"] == 0
    assert reporter.capture(**fixture.options)["attempt"] == first["attempt"]
    save(fixture.root / "training/train_sft_lora.py", {"patched": True})
    changed = reporter.capture(**fixture.options)
    assert changed["attempt"]["id"] != first["attempt"]["id"]
    save(fixture.root / "status.json", {"startedAt": "2026-10-26T01:00:00Z", "restoredCheckpointSteps": 0})
    assert reporter.capture(**fixture.options)["attempt"]["id"] != changed["attempt"]["id"]
    assert str(fixture.root) not in json.dumps(changed)


def test_resume_completion_counts_only_proven_restored_steps_and_new_updates(fixture):
    completed(fixture, "full")
    status = {"exitCode": 0, "startedAt": "2026-10-26T00:00:00Z", "restoredCheckpointSteps": 1,
              "step": 2, "completedFullTraining": True}
    updates = reporter.read(fixture.output / "optimizer-evidence.json")
    updates.update(actualOptimizerUpdates=1, restoredCheckpointSteps=1, totalVerifiedSteps=2, tensorComparisonVerified=True)
    save(fixture.output / "optimizer-evidence.json", updates)
    save(fixture.root / "status.json", status)
    value = reporter.capture(**fixture.options)
    assert value["status"] == "REPORTED_COMPLETE"
    assert value["optimizerEvidence"]["actualOptimizerUpdates"] == 1
    assert value["optimizerEvidence"]["restoredOptimizerSteps"] == value["attempt"]["fromStep"] == 1
    for missing in ("restoredCheckpointSteps", "totalVerifiedSteps", "tensorComparisonVerified"):
        invalid = dict(updates); invalid.pop(missing)
        save(fixture.output / "optimizer-evidence.json", invalid)
        assert reporter.capture(**fixture.options)["status"] == "FAILED"
    save(fixture.output / "optimizer-evidence.json", updates)
    status["completedFullTraining"] = False
    save(fixture.root / "status.json", status)
    assert reporter.capture(**fixture.options)["status"] == "FAILED"


def test_validation_metrics_can_be_displayed_before_final_manifest(fixture):
    save(fixture.output / "training-progress.json", {"step": 1, "history": [{"step": 1, "eval_loss": .38, "epoch": 1}]})
    assert reporter.capture(**fixture.options)["validation"] == {"eval_loss": .38, "epoch": 1}


def test_resumed_history_never_attributes_restored_training_points_to_new_source(fixture):
    fixture.options["phase"] = "full"
    save(fixture.root / "status.json", {"startedAt": "2026-10-26T01:00:00Z", "restoredCheckpointSteps": 1})
    save(fixture.output / "training-progress.json", {"step": 2, "history": [
        {"step": 1, "loss": .5}, {"step": 1, "eval_loss": .4, "epoch": 1}, {"step": 2, "loss": .3}]})
    value = reporter.capture(**fixture.options)
    assert value["history"] == [{"step": 2, "loss": .3}]
    assert value["validation"] == {"eval_loss": .4, "epoch": 1}


def test_pending_approval_keeps_reporter_polling_without_claiming_connected(fixture):
    from email.message import Message
    from io import BytesIO
    import urllib.request
    import urllib.response

    class Gateway(urllib.request.HTTPSHandler):
        handler_order = 100

        def https_open(self, request):
            body = ({"headerName": "X-XSRF-TOKEN", "token": "csrf"} if request.get_method() == "GET"
                    else {"accepted": False, "pendingApproval": True, "sequence": 123})
            response = urllib.response.addinfourl(BytesIO(json.dumps(body).encode()), Message(), request.full_url, 200)
            response.msg = "OK"
            return response

    client = reporter.Reporter("https://example.com/api/training/telemetry/colab-" + "a" * 32, "x" * 43,
                               **{k: v for k, v in fixture.options.items() if k != "measure"}, capture_fn=lambda: {"sequence": 123})
    client.opener.add_handler(Gateway())
    try:
        state = client.start()
        assert not state["connected"] and state["pendingApproval"]
        assert client.thread.is_alive() and not client.event.is_set()
        assert "x" * 43 not in json.dumps(state)
    finally:
        client.stop()


def test_follow_attempt_keeps_existing_credential_endpoint_and_never_controls_training(fixture, monkeypatch):
    options = {k: v for k, v in fixture.options.items() if k != "measure"}
    client = reporter.Reporter("https://example.com/api/training/telemetry/colab-" + "a" * 32, "x" * 43, **options)
    marker = fixture.root / "resume-status.json"
    save(marker, {"startedAt": "2026-10-26T01:00:00Z", "restoredCheckpointSteps": 0})
    monkeypatch.setattr(reporter, "telemetry", lambda: ("Test GPU", {}))
    monkeypatch.setattr(reporter.subprocess, "Popen", lambda *a, **kw: pytest.fail("Reporter controlled a process"))
    previous = (client.endpoint, client._token)
    result = client.follow_attempt(status_file=marker, log=fixture.root / "new-log")
    assert result["id"].startswith("attempt-")
    assert client.capture()["attempt"] == result
    assert (client.endpoint, client._token) == previous
    client.stop()
    with pytest.raises(ValueError):
        client.follow_attempt(status_file=marker, log=fixture.root / "new-log")
