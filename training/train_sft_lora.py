"""Fail-closed SFT/LoRA runner. CPU tests do not require model dependencies."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from training.prepare_dataset import ROOT, DatasetError, digest, validator_hash


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_object(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise DatasetError(f"expected JSON object: {path.name}")
    return value


def load_bundle(manifest_path: Path) -> tuple[dict, dict[str, list[dict]]]:
    manifest = read_object(manifest_path)
    if manifest.get("datasetReady") is not True:
        raise DatasetError("datasetReady is not true; provisional data cannot train")
    if manifest.get("contract", {}).get("validatorHash") != validator_hash():
        raise DatasetError("production validator changed; reprepare and review the dataset")
    splits = {}
    ids, groups = set(), {}
    for split in ("train", "validation", "test"):
        rows = [json.loads(line) for line in (manifest_path.parent / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()
                if line.strip()]
        if not rows or len(rows) != manifest.get("counts", {}).get(split):
            raise DatasetError(f"{split}: empty split or count mismatch")
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get("id"), str) or row["id"] in ids:
                raise DatasetError("invalid or duplicate prepared row id")
            ids.add(row["id"])
            group = row.get("groupId")
            if not isinstance(group, str) or not group or groups.setdefault(group, split) != split:
                raise DatasetError("group leakage or missing groupId")
            if row.get("reviewStatus") != "approved" or not row.get("reviewerReference") or not row.get("semanticReviewReference"):
                raise DatasetError("prepared row is missing review evidence")
            messages = row.get("messages")
            if (not isinstance(messages, list) or len(messages) != 3 or row.get("targetMessageIndex") != 2
                    or row.get("lossMaskPolicy") != "last-assistant-only"):
                raise DatasetError("invalid prepared messages or loss policy")
            for message, role in zip(messages, ("system", "user", "assistant")):
                if not isinstance(message, dict) or message.get("role") != role or not isinstance(message.get("content"), str):
                    raise DatasetError("invalid prepared role/content")
            if row.get("inputHash") != digest(messages[:-1]) or row.get("outputHash") != digest(json.loads(messages[-1]["content"])):
                raise DatasetError("prepared input/output hash mismatch")
        if sorted({r["groupId"] for r in rows}) != manifest.get("groups", {}).get(split):
            raise DatasetError(f"{split}: group manifest mismatch")
        splits[split] = rows
    if digest(splits) != manifest.get("datasetHash"):
        raise DatasetError("dataset hash mismatch; frozen data was modified")
    forced = set(manifest.get("splitPolicy", {}).get("forcedTestGroups", []))
    if not forced or any(groups.get(group, "test") != "test" for group in forced):
        raise DatasetError("canonical/adversarial holdout is missing or leaked")
    return manifest, splits


def validate_review(review: dict, manifest: dict, manifest_path: Path, splits: dict) -> None:
    if (review.get("approved") is not True or not isinstance(review.get("reviewerReference"), str)
            or not review["reviewerReference"].strip()):
        raise DatasetError("training review is not approved with a reviewer reference")
    if review.get("datasetHash") != manifest["datasetHash"] or review.get("datasetManifestHash") != file_hash(manifest_path):
        raise DatasetError("training review is not bound to this dataset/manifest")
    for field in ("coverageApproved", "nearDuplicateAuditApproved", "holdoutAuditApproved"):
        if review.get(field) is not True:
            raise DatasetError(f"training review gate is pending: {field}")
    if sum(len(rows) for rows in splits.values()) < 200:
        raise DatasetError("P0 requires at least 200 approved target records")
    for split in ("train", "validation"):
        rows = splits[split]
        if {r.get("level") for r in rows} != {"A1", "A2", "B1"} or {r.get("purpose") for r in rows} != {"content", "translation"}:
            raise DatasetError(f"{split}: A1/A2/B1 and both purposes are required")
    if {r.get("turnNumber") for r in splits["train"]} != {2, 3, 4}:
        raise DatasetError("train: turns 2/3/4 are required")


def verify_environment(lock_path: Path) -> dict[str, str]:
    pins = {}
    for line in lock_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+!-]+)", line)
        if not match:
            raise DatasetError("environment lock must contain exact package pins only")
        name, version = match.groups()
        if importlib.metadata.version(name) != version:
            raise DatasetError(f"environment differs from lock: {name}")
        pins[name.lower().replace("_", "-")] = version
    if not {"torch", "transformers", "trl", "peft", "datasets", "accelerate", "safetensors", "pyyaml"} <= pins.keys():
        raise DatasetError("environment lock is missing training dependencies")
    return pins


def checked_inputs(config_path: Path) -> tuple[dict, dict, dict, dict]:
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise DatasetError("training config must be an object")
    if config.get("base_model") != "Qwen/Qwen3.5-4B" or not re.fullmatch(r"[a-f0-9]{40}", str(config.get("base_revision", ""))):
        raise DatasetError("base model must be Qwen3.5-4B with an immutable revision")
    for field, expected in (("packing", False), ("repair_enabled", False), ("fallback_enabled", False),
                            ("assistant_only_loss", True), ("enable_thinking", False), ("require_loss_mask_smoke", True)):
        if config.get(field) is not expected:
            raise DatasetError(f"invalid training invariant: {field}")
    if config.get("loss_mask_policy") != "last-assistant-only":
        raise DatasetError("last-assistant-only loss mask is required")
    if type(config.get("max_seq_length")) is not int or config["max_seq_length"] < 16:
        raise DatasetError("max_seq_length must be an integer >= 16")
    settings, lora, quant = config["training"], config["lora"], config["quantization"]
    for field in ("per_device_batch_size", "gradient_accumulation_steps"):
        if type(settings.get(field)) is not int or settings[field] < 1:
            raise DatasetError(f"{field} must be a positive integer")
    for field in ("epochs", "learning_rate"):
        value = settings.get(field)
        if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
            raise DatasetError(f"{field} must be finite and positive")
    if type(settings.get("warmup_ratio")) not in (int, float) or not 0 <= settings["warmup_ratio"] <= 1:
        raise DatasetError("warmup_ratio must be between 0 and 1")
    if settings.get("use_cache") is not False or type(settings.get("gradient_checkpointing")) is not bool:
        raise DatasetError("use_cache must be false and gradient_checkpointing must be explicit")
    if any(type(lora.get(k)) is not int or lora[k] < 1 for k in ("r", "alpha")) or lora.get("bias") != "none":
        raise DatasetError("positive LoRA rank/alpha and bias=none are required")
    if type(lora.get("dropout")) not in (int, float) or not 0 <= lora["dropout"] < 1:
        raise DatasetError("LoRA dropout must be in [0, 1)")
    if (lora.get("method"), quant.get("load_in_4bit")) not in (("lora", False), ("qlora", True)):
        raise DatasetError("inconsistent LoRA/QLoRA quantization settings")
    if lora["method"] == "qlora" and (quant.get("quant_type") != "nf4" or quant.get("double_quant") is not True):
        raise DatasetError("P0 QLoRA requires NF4 and double quantization")
    device, dtype = config.get("device"), quant.get("compute_dtype")
    if device not in ("cpu", "cuda"):
        raise DatasetError("device must be cpu or cuda")
    if device == "cpu" and (lora["method"] != "lora" or dtype != "float32"):
        raise DatasetError("CPU training requires unquantized LoRA with float32")
    if device == "cuda" and dtype not in ("bfloat16", "float16"):
        raise DatasetError("CUDA training requires an explicit bfloat16 or float16 compute dtype")
    manifest_path = ROOT / config["dataset_manifest"]
    manifest, splits = load_bundle(manifest_path)
    validate_review(read_object(ROOT / config["dataset_approval"]), manifest, manifest_path, splits)
    inspection = read_object(ROOT / config["lora"]["target_modules_from"])
    if inspection.get("modelId") != config["base_model"] or inspection.get("revision") != config["base_revision"]:
        raise DatasetError("model inspection belongs to another checkpoint")
    targets = inspection.get("selectedTargets")
    if (inspection.get("chatTemplateSmokePassed") is not True or not isinstance(targets, list) or not targets
            or any(not isinstance(name, str) or "." not in name or any(p in name.split(".") for p in ("visual", "vision_model", "vision_tower")) for name in targets)):
        raise DatasetError("inspection has no reviewed text-only target paths")
    return config, manifest, splits, inspection


def tokenize_target(tokenizer: Any, row: dict, max_length: int) -> dict[str, list[int]]:
    messages = row["messages"]
    if (len(messages) != 3 or [m.get("role") for m in messages] != ["system", "user", "assistant"]
            or row.get("targetMessageIndex") != 2 or row.get("lossMaskPolicy") != "last-assistant-only"):
        raise DatasetError("tokenizer requires one final assistant target")
    prefix = tokenizer.apply_chat_template(messages[:-1], tokenize=False, add_generation_prompt=True, enable_thinking=False)
    full = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False, enable_thinking=False)
    if not full.startswith(prefix) or not full[len(prefix):].startswith(messages[-1]["content"]):
        raise DatasetError("chat template changes the prefix or inserts hidden reasoning")
    prompt_ids = tokenizer(prefix, add_special_tokens=False)["input_ids"]
    input_ids = tokenizer(full, add_special_tokens=False)["input_ids"]
    if not prompt_ids or input_ids[:len(prompt_ids)] != prompt_ids or len(input_ids) <= len(prompt_ids):
        raise DatasetError("ambiguous token boundary; refusing to guess the assistant mask")
    if len(input_ids) > max_length:
        raise DatasetError(f"sequence length {len(input_ids)} exceeds {max_length}; truncation is forbidden")
    labels = [-100] * len(prompt_ids) + input_ids[len(prompt_ids):]
    return {"input_ids": input_ids, "attention_mask": [1] * len(input_ids), "labels": labels}


def pad_batch(features: list[dict], pad_token_id: int) -> dict[str, list[list[int]]]:
    if not features or type(pad_token_id) is not int:
        raise DatasetError("non-empty batch and pad token id are required")
    length = max(len(f["input_ids"]) for f in features)
    result = {"input_ids": [], "attention_mask": [], "labels": []}
    for feature in features:
        ids, labels = feature["input_ids"], feature["labels"]
        if not ids or len(ids) != len(labels) or not any(v != -100 for v in labels):
            raise DatasetError("invalid or entirely masked example")
        if any(label != -100 and label != token for token, label in zip(ids, labels)):
            raise DatasetError("labels differ from unshifted input tokens")
        padding = length - len(ids)
        result["input_ids"].append(ids + [pad_token_id] * padding)
        result["attention_mask"].append([1] * len(ids) + [0] * padding)
        result["labels"].append(labels + [-100] * padding)
    return result


class TargetCollator:
    def __init__(self, pad_token_id: int):
        self.pad_token_id = pad_token_id

    def __call__(self, features):
        import torch
        return {key: torch.tensor(value, dtype=torch.long)
                for key, value in pad_batch(features, self.pad_token_id).items()}


def make_trainer(model, tokenizer, train_rows, validation_rows, config: dict, output: Path, *, smoke: bool):
    from datasets import Dataset
    from trl import SFTConfig, SFTTrainer

    settings = config["training"]
    dtype = config["quantization"]["compute_dtype"]
    updates = math.ceil(len(train_rows) / settings["per_device_batch_size"] / settings["gradient_accumulation_steps"]) * settings["epochs"]
    args = SFTConfig(
        output_dir=str(output), seed=config["seed"], data_seed=config["seed"],
        use_cpu=config["device"] == "cpu", bf16=dtype == "bfloat16", fp16=dtype == "float16",
        per_device_train_batch_size=settings["per_device_batch_size"], per_device_eval_batch_size=1,
        gradient_accumulation_steps=1 if smoke else settings["gradient_accumulation_steps"],
        gradient_checkpointing=settings["gradient_checkpointing"],
        gradient_checkpointing_kwargs={"use_reentrant": False},
        learning_rate=settings["learning_rate"], warmup_steps=0 if smoke else math.ceil(updates * settings["warmup_ratio"]),
        lr_scheduler_type=settings["scheduler"], num_train_epochs=settings["epochs"],
        max_steps=1 if smoke else -1, optim="adamw_torch", logging_steps=1,
        save_strategy="no" if smoke else "epoch", eval_strategy="no" if smoke else "epoch",
        save_total_limit=2, load_best_model_at_end=not smoke, metric_for_best_model="eval_loss",
        report_to="none", push_to_hub=False,
        dataloader_pin_memory=config["device"] != "cpu", dataloader_num_workers=0,
        remove_unused_columns=False, max_length=None, packing=False, padding_free=False,
        # Labels are built and checked here, so TRL must not construct a second mask.
        assistant_only_loss=False, completion_only_loss=False,
        dataset_kwargs={"skip_prepare_dataset": True},
    )
    return SFTTrainer(model=model, args=args, processing_class=tokenizer,
                      data_collator=TargetCollator(tokenizer.pad_token_id),
                      train_dataset=Dataset.from_list(train_rows),
                      eval_dataset=Dataset.from_list(validation_rows))


def adapter_preflight(model, feature: dict, tokenizer, output: Path) -> dict:
    import torch
    from peft import get_peft_model_state_dict, set_peft_model_state_dict
    from safetensors.torch import load_file

    trainable = [(name, p) for name, p in model.named_parameters() if p.requires_grad]
    if not trainable or any("lora_" not in name for name, _ in trainable):
        raise DatasetError("adapter has zero trainable parameters or trains base weights")
    model.train()
    batch = {k: v.to(model.device) for k, v in TargetCollator(tokenizer.pad_token_id)([feature]).items()}
    loss = model(**batch).loss
    if not torch.isfinite(loss):
        raise DatasetError("non-finite preflight loss")
    loss.backward()
    grads = [p.grad for _, p in trainable if p.grad is not None]
    if not grads or any(not torch.isfinite(g).all() for g in grads) or not any(torch.count_nonzero(g).item() for g in grads):
        raise DatasetError("adapter gradients are missing, zero or non-finite")
    model.zero_grad(set_to_none=True)
    model.save_pretrained(output, safe_serialization=True)
    saved = load_file(str(output / "adapter_model.safetensors"))
    before = get_peft_model_state_dict(model)
    if set(saved) != set(before) or any(not torch.equal(before[k].detach().cpu(), saved[k]) for k in saved):
        raise DatasetError("adapter serialization round trip failed")
    loaded = set_peft_model_state_dict(model, saved)
    if loaded.unexpected_keys:
        raise DatasetError("adapter reload returned unexpected parameters")
    return {"loss": loss.item(), "trainableParameters": sum(p.numel() for _, p in trainable),
            "finiteGradients": True, "adapterRoundTrip": True}


def write_report(output: Path, report: dict) -> None:
    with (output / "run-manifest.json").open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def check_cpu_memory(inspection: dict, available_bytes: int) -> dict:
    count = inspection.get("parameterCountFromConfig")
    if type(count) is not int or count <= 0:
        raise DatasetError("CPU memory preflight requires an inspected parameter count")
    # Admission estimate only: float32 base plus workspace; peak usage needs a real smoke.
    required = count * 4 + 8 * 1024**3
    if available_bytes < required:
        raise DatasetError(f"CPU RAM preflight: {available_bytes / 1024**3:.1f} GiB available, "
                           f"at least {required / 1024**3:.1f} GiB estimated; checkpoint was not loaded")
    return {"availableBytesBeforeLoad": available_bytes, "estimatedRequiredBytes": required}


def run(config_path: Path, *, check_only: bool = False, smoke: bool = False) -> dict:
    config, manifest, splits, inspection = checked_inputs(config_path)
    versions = verify_environment(ROOT / config["environment_lock"])
    input_hashes = {"configHash": file_hash(config_path),
                    "datasetManifestHash": file_hash(ROOT / config["dataset_manifest"]),
                    "reviewHash": file_hash(ROOT / config["dataset_approval"]),
                    "environmentLockHash": file_hash(ROOT / config["environment_lock"]),
                    "inspectionHash": file_hash(ROOT / config["lora"]["target_modules_from"])}
    if check_only:
        return {"offlineChecksPassed": True, "datasetHash": manifest["datasetHash"], "trainingReady": False}
    if int(os.environ.get("WORLD_SIZE", "1")) != 1:
        raise DatasetError("P0 supports a single training process/device")
    import torch
    from transformers import AutoTokenizer, BitsAndBytesConfig, Qwen3_5ForCausalLM, set_seed
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

    device = config["device"]
    dtype_name = config["quantization"]["compute_dtype"]
    memory = {}
    if device == "cuda":
        if not torch.cuda.is_available():
            raise DatasetError("CUDA is unavailable; the 4B checkpoint was not loaded")
        if dtype_name == "bfloat16" and not torch.cuda.is_bf16_supported():
            raise DatasetError("GPU does not support bf16; review an fp16 config explicitly")
        torch.cuda.reset_peak_memory_stats()
    else:
        import psutil
        memory = check_cpu_memory(inspection, psutil.virtual_memory().available)
    set_seed(config["seed"])
    tokenizer = AutoTokenizer.from_pretrained(config["base_model"], revision=config["base_revision"], trust_remote_code=False)
    if hashlib.sha256(str(tokenizer.chat_template).encode()).hexdigest() != inspection.get("chatTemplateHash"):
        raise DatasetError("tokenizer chat template differs from inspection")
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    # Test targets are integrity-checked above but never tokenized for training or model selection.
    tokenized = {s: [tokenize_target(tokenizer, row, config["max_seq_length"]) for row in splits[s]]
                 for s in ("train", "validation")}
    output = ROOT / config["output_dir"]
    output.mkdir(parents=True, exist_ok=False)
    kwargs = {"revision": config["base_revision"], "trust_remote_code": False,
              "dtype": getattr(torch, dtype_name), "device_map": {"": device}, "output_loading_info": True}
    method = config["lora"]["method"]
    quant = config["quantization"]
    if method == "qlora" and quant["load_in_4bit"] is True:
        kwargs["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True,
            bnb_4bit_quant_type=quant["quant_type"], bnb_4bit_use_double_quant=quant["double_quant"],
            bnb_4bit_compute_dtype=getattr(torch, dtype_name))
    elif method != "lora" or quant["load_in_4bit"] is not False:
        raise DatasetError("inconsistent LoRA/QLoRA quantization settings")
    model, loading = Qwen3_5ForCausalLM.from_pretrained(config["base_model"], **kwargs)
    if loading.get("missing_keys") or loading.get("mismatched_keys"):
        raise DatasetError("checkpoint did not load every text parameter")
    targets = inspection["selectedTargets"]
    if not set(targets) <= {name for name, _ in model.named_modules()}:
        raise DatasetError("inspected target module is absent from loaded checkpoint")
    model.config.use_cache = False
    if method == "qlora":
        model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=config["training"]["gradient_checkpointing"],
                                                gradient_checkpointing_kwargs={"use_reentrant": False})
    lora = config["lora"]
    model = get_peft_model(model, LoraConfig(r=lora["r"], lora_alpha=lora["alpha"],
        lora_dropout=lora["dropout"], bias=lora["bias"], revision=config["base_revision"],
        task_type="CAUSAL_LM", target_modules=targets))
    if config["training"]["gradient_checkpointing"]:
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    preflight = adapter_preflight(model, max(tokenized["train"], key=lambda row: len(row["input_ids"])),
                                  tokenizer, output / "preflight-adapter")
    set_seed(config["seed"])
    trainer = make_trainer(model, tokenizer, tokenized["train"], tokenized["validation"], config, output / "checkpoints", smoke=smoke)
    result = trainer.train()
    if result.global_step < 1 or not math.isfinite(result.training_loss):
        raise DatasetError("training did not perform a finite optimizer step")
    trainer.save_model(str(output / "adapter"))
    tokenizer.save_pretrained(output / "adapter")
    trainer.save_state()
    trainer.save_metrics("train", result.metrics)
    validation_metrics = {} if smoke else trainer.evaluate()
    if not smoke:
        if not math.isfinite(validation_metrics["eval_loss"]):
            raise DatasetError("validation loss is non-finite")
        trainer.save_metrics("validation", validation_metrics)
    report = {"mode": "smoke" if smoke else "train", "trainingReady": not smoke, "releaseAllowed": False,
              "baseModel": config["base_model"], "baseRevision": config["base_revision"],
              "datasetHash": manifest["datasetHash"], **input_hashes,
              "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "versions": versions, "python": sys.version.split()[0], "cuda": torch.version.cuda,
              "device": device, "computeDtype": dtype_name, "cpuMemoryPreflight": memory,
              "gpu": torch.cuda.get_device_name(0) if device == "cuda" else None,
              "peakAllocatedBytes": torch.cuda.max_memory_allocated() if device == "cuda" else None,
              "maskPolicy": "last-assistant-only", "truncationCount": 0, "preflight": preflight,
              "steps": result.global_step, "trainingLoss": result.training_loss,
              "validationMetrics": validation_metrics,
              "adapterHash": file_hash(output / "adapter/adapter_model.safetensors"),
              "adapterConfigHash": file_hash(output / "adapter/adapter_config.json")}
    write_report(output, report)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="offline integrity/review/environment checks only")
    parser.add_argument("--smoke", action="store_true", help="one optimizer step, never a release candidate")
    args = parser.parse_args(argv)
    try:
        report = run(args.config, check_only=args.check, smoke=args.smoke)
    except (OSError, ValueError, KeyError, ImportError) as exc:
        print(f"train_sft_lora: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({key: report[key] for key in ("trainingReady", "datasetHash")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
