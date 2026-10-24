from __future__ import annotations

import json
import os

import pytest

from training.prepare_dataset import DatasetError
from training.tests.test_prepare_dataset import _prepare, _row, _write
from training.train_sft_lora import check_cpu_memory, check_resume_checkpoint, checked_inputs, file_hash, load_bundle, pad_batch, tokenize_target, validate_review, verify_environment


class CharacterTokenizer:
    def apply_chat_template(self, messages, *, add_generation_prompt=False, **kwargs):
        assert kwargs["enable_thinking"] is False
        return "".join(f"<{m['role']}>{m['content']}<end>" for m in messages) + ("<assistant>" if add_generation_prompt else "")

    def __call__(self, text, **kwargs):
        assert kwargs["add_special_tokens"] is False
        return {"input_ids": list(text.encode("utf-8"))}


def _example():
    return {"targetMessageIndex": 2, "lossMaskPolicy": "last-assistant-only", "messages": [
        {"role": "system", "content": "System instructions."},
        {"role": "user", "content": '{"answer":1}'},
        {"role": "assistant", "content": '{"answer":1}'}]}


def test_mask_excludes_system_and_identical_json_in_user():
    row, tokenizer = _example(), CharacterTokenizer()
    features = tokenize_target(tokenizer, row, 1024)
    prefix = tokenizer.apply_chat_template(row["messages"][:-1], add_generation_prompt=True, enable_thinking=False)
    count = len(prefix.encode("utf-8"))
    assert features["labels"][:count] == [-100] * count
    assert bytes(features["labels"][count:]).decode() == '{"answer":1}<end>'
    assert features["labels"][count:] == features["input_ids"][count:]


def test_no_silent_truncation_or_full_sequence_loss():
    with pytest.raises(DatasetError, match="truncation is forbidden"):
        tokenize_target(CharacterTokenizer(), _example(), 5)
    row = _example()
    row["lossMaskPolicy"] = "full-sequence"
    with pytest.raises(DatasetError, match="final assistant"):
        tokenize_target(CharacterTokenizer(), row, 1024)


def test_changed_template_or_thinking_prefix_is_rejected():
    class WrongTemplate(CharacterTokenizer):
        def apply_chat_template(self, messages, **kwargs):
            rendered = super().apply_chat_template(messages, **kwargs)
            return rendered + "<think>" if kwargs.get("add_generation_prompt") else rendered
    with pytest.raises(DatasetError, match="hidden reasoning"):
        tokenize_target(WrongTemplate(), _example(), 1024)


def test_boundary_merging_fails_without_guessing_mask():
    class BoundaryTokenizer(CharacterTokenizer):
        def __call__(self, text, **kwargs):
            ids = super().__call__(text, **kwargs)["input_ids"]
            if text.endswith("<end>"):
                ids[0] += 1
            return {"input_ids": ids}
    with pytest.raises(DatasetError, match="ambiguous token boundary"):
        tokenize_target(BoundaryTokenizer(), _example(), 1024)


def test_padding_keeps_real_eos_supervised_when_pad_equals_eos():
    batch = pad_batch([{"input_ids": [5, 2], "labels": [-100, 2]},
                       {"input_ids": [4, 6, 2], "labels": [-100, 6, 2]}], 2)
    assert batch["input_ids"][0] == [5, 2, 2]
    assert batch["labels"][0] == [-100, 2, -100]
    assert batch["attention_mask"][0] == [1, 1, 0]
    with pytest.raises(DatasetError, match="entirely masked"):
        pad_batch([{"input_ids": [5], "labels": [-100]}], 2)


@pytest.fixture
def prepared(tmp_path):
    rows = [_row(i) for i in range(30)]
    source, review = _write(tmp_path, rows)
    _prepare(source, review, tmp_path / "out", forced_test_groups={"group-0"})
    return tmp_path / "out/manifest.json"


def test_frozen_bundle_integrity_and_holdout(prepared):
    manifest, splits = load_bundle(prepared)
    assert manifest["trainingReady"] is False
    assert sum(map(len, splits.values())) == 30
    path = prepared.parent / "train.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    rows[0]["messages"][-1]["content"] = '{"changed":true}'
    path.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    with pytest.raises(DatasetError, match="input/output hash"):
        load_bundle(prepared)


def test_provisional_manifest_fails(prepared):
    manifest = json.loads(prepared.read_text(encoding="utf-8"))
    manifest["datasetReady"] = False
    prepared.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(DatasetError, match="provisional"):
        load_bundle(prepared)


def test_review_requires_bound_hash_and_all_pending_audits(prepared):
    manifest, splits = load_bundle(prepared)
    review = {"approved": True, "reviewerReference": "fixture-review", "datasetHash": manifest["datasetHash"],
              "datasetManifestHash": file_hash(prepared), "coverageApproved": True,
              "nearDuplicateAuditApproved": True, "holdoutAuditApproved": True}
    with pytest.raises(DatasetError, match="200 approved"):
        validate_review(review, manifest, prepared, splits)
    review["holdoutAuditApproved"] = False
    with pytest.raises(DatasetError, match="holdoutAuditApproved"):
        validate_review(review, manifest, prepared, splits)
    review["datasetHash"] = "other-dataset"
    with pytest.raises(DatasetError, match="bound"):
        validate_review(review, manifest, prepared, splits)


def test_real_cpu_sft_and_adapter_round_trip(tmp_path):
    if os.environ.get("SFT_RUN_RUNTIME_TESTS") != "1":
        pytest.skip("opt-in: requires the isolated CPU training lock")
    from training.smoke_cpu import run_smoke
    report = run_smoke(tmp_path / "tiny-cpu")
    assert report["steps"] == 1
    assert report["preflight"]["finiteGradients"]
    assert report["optimizerUpdatedAdapter"] and report["freshModelAdapterReload"]
    assert report["gradientCheckpointing"]
    assert report["recoveryCheckpointVerified"] and report["resumedOptimizerState"]
    assert report["pauseFinishedStepAndSavedState"] and report["pauseSkippedPartialEpochEvaluation"]
    assert not report["trainingReady"] and not report["realCheckpointLoaded"]


def test_template_config_cannot_load_a_floating_checkpoint(tmp_path):
    import yaml
    from training.prepare_dataset import ROOT
    config = yaml.safe_load((ROOT / "training/configs/conversation-sft-p0.yaml").read_text(encoding="utf-8"))
    config["base_revision"] = "main"
    path = tmp_path / "floating.yaml"
    path.write_text(yaml.safe_dump(config), encoding="utf-8")
    with pytest.raises(DatasetError, match="immutable revision"):
        checked_inputs(path)


def test_cpu_4b_config_reaches_data_gate_without_cuda(monkeypatch):
    import training.train_sft_lora as runner
    from training.prepare_dataset import ROOT
    def stop_at_data(path):
        raise DatasetError("reached reviewed dataset gate")
    monkeypatch.setattr(runner, "load_bundle", stop_at_data)
    with pytest.raises(DatasetError, match="reached reviewed dataset gate"):
        checked_inputs(ROOT / "training/configs/conversation-sft-p0.yaml")


@pytest.mark.parametrize("change", ["quantized", "half", "unknown_device", "old_model"])
def test_incompatible_cpu_settings_fail_before_data(tmp_path, change):
    import yaml
    from training.prepare_dataset import ROOT
    config = yaml.safe_load((ROOT / "training/configs/conversation-sft-p0.yaml").read_text(encoding="utf-8"))
    if change == "quantized":
        config["lora"]["method"] = "qlora"
        config["quantization"]["load_in_4bit"] = True
    elif change == "half":
        config["quantization"]["compute_dtype"] = "float16"
    elif change == "unknown_device":
        config["device"] = "auto"
    else:
        config["base_model"] = "Qwen/Qwen3.5-9B"
    path = tmp_path / "invalid.yaml"
    path.write_text(yaml.safe_dump(config), encoding="utf-8")
    with pytest.raises(DatasetError, match="CPU training|device must|base model must"):
        checked_inputs(path)


def test_cpu_memory_guard_stops_before_weights_and_requires_inspected_count():
    gib = 1024**3
    inspection = {"parameterCountFromConfig": gib}
    with pytest.raises(DatasetError, match="checkpoint was not loaded"):
        check_cpu_memory(inspection, 12 * gib - 1)
    assert check_cpu_memory(inspection, 12 * gib)["estimatedRequiredBytes"] == 12 * gib
    with pytest.raises(DatasetError, match="inspected parameter count"):
        check_cpu_memory({}, 32 * gib)


@pytest.mark.parametrize("field,value", [("epochs", 0), ("learning_rate", -1), ("use_cache", True),
                                        ("checkpoint_steps", 0), ("checkpoint_steps", True)])
def test_invalid_training_settings_fail_before_reading_data(tmp_path, field, value):
    import yaml
    from training.prepare_dataset import ROOT
    config = yaml.safe_load((ROOT / "training/configs/conversation-sft-p0.yaml").read_text(encoding="utf-8"))
    config["base_revision"] = "a" * 40
    config["training"][field] = value
    path = tmp_path / "invalid.yaml"
    path.write_text(yaml.safe_dump(config), encoding="utf-8")
    with pytest.raises(DatasetError, match=field):
        checked_inputs(path)


def test_environment_lock_is_exact_and_does_not_accept_arbitrary_urls(tmp_path, monkeypatch):
    import training.train_sft_lora as runner
    path = tmp_path / "environment.lock"
    packages = ("torch", "transformers", "trl", "peft", "datasets", "accelerate", "safetensors", "pyyaml")
    path.write_text("\n".join(f"{p}==1.0" for p in packages), encoding="utf-8")
    monkeypatch.setattr(runner.importlib.metadata, "version", lambda p: "1.0")
    assert len(verify_environment(path)) == 8
    path.write_text("torch @ https://untrusted.invalid/package.whl", encoding="utf-8")
    with pytest.raises(DatasetError, match="exact package pins"):
        verify_environment(path)


@pytest.mark.parametrize("fault", [None, "changed-data", "partial", "corrupt-optimizer", "wrong-step", "outside-run"])
def test_resume_requires_bound_complete_checkpoint(tmp_path, fault):
    output = tmp_path / "run"
    checkpoint = output / "checkpoints/checkpoint-3"
    checkpoint.mkdir(parents=True)
    inputs = {"configHash": "frozen-config", "datasetHash": "approved-data"}
    (output / "run-inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
    names = ("adapter_model.safetensors", "adapter_config.json", "optimizer.pt", "scheduler.pt", "rng_state.pth")
    for name in names:
        (checkpoint / name).write_bytes(b"checkpoint-fixture")
    (checkpoint / "trainer_state.json").write_text(json.dumps({"global_step": 3}), encoding="utf-8")
    integrity = {"step": 3, "files": {f.name: file_hash(f) for f in checkpoint.iterdir()}}
    if fault == "wrong-step":
        integrity["step"] = 2
    (checkpoint / "resume-integrity.json").write_text(json.dumps(integrity), encoding="utf-8")
    if fault == "changed-data":
        inputs = {**inputs, "datasetHash": "other-data"}
    elif fault == "partial":
        (checkpoint / "scheduler.pt").unlink()
    elif fault == "corrupt-optimizer":
        (checkpoint / "optimizer.pt").write_bytes(b"damaged")
    elif fault == "outside-run":
        output = tmp_path / "other-run"
    if fault:
        with pytest.raises(DatasetError, match="resume"):
            check_resume_checkpoint(output, checkpoint, inputs)
    else:
        assert check_resume_checkpoint(output, checkpoint, inputs) == checkpoint.resolve()


def test_validator_hash_survives_windows_linux_checkout_line_endings(tmp_path, monkeypatch):
    import training.prepare_dataset as preparation
    path = tmp_path / "validator.py"
    monkeypatch.setattr(preparation, "VALIDATOR_PATH", path)
    path.write_bytes(b"def validate():\r\n    return []\r\n")
    windows = preparation.validator_hash()
    path.write_bytes(b"def validate():\n    return []\n")
    assert preparation.validator_hash() == windows
