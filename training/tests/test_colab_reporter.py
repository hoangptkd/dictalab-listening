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
