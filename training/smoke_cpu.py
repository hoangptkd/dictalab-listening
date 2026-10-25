"""Exercise the SFT runner on a tiny random Qwen3.5 model, entirely offline."""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import string
import sys
from unittest.mock import patch

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from training.train_sft_lora import (adapter_preflight, check_resume_checkpoint, file_hash, make_trainer,
                                     tokenize_target, validate_checkpoint_integrity, write_report)


def run_smoke(output: Path) -> dict:
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_DATASETS_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    import torch
    from peft import LoraConfig, PeftModel, get_peft_model
    from tokenizers import Tokenizer, models
    from transformers import PreTrainedTokenizerFast, Qwen3_5ForCausalLM, Qwen3_5TextConfig, TrainerCallback, set_seed

    output.mkdir(parents=True, exist_ok=False)
    set_seed(17)
    torch.set_num_threads(min(4, torch.get_num_threads()))
    vocab = {token: i for i, token in enumerate(["[UNK]", "[PAD]", "[EOS]", *string.printable])}
    raw = Tokenizer(models.BPE(vocab=vocab, merges=[], unk_token="[UNK]"))
    template = ("{% for m in messages %}{{ '<|' + m['role'] + '|>\\n' + m['content'] + '[EOS]\\n' }}{% endfor %}"
                "{% if add_generation_prompt %}{{ '<|assistant|>\\n' }}{% endif %}")
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=raw, unk_token="[UNK]", pad_token="[PAD]",
                                       eos_token="[EOS]", chat_template=template)
    model_config = Qwen3_5TextConfig(vocab_size=len(tokenizer), hidden_size=32, intermediate_size=64,
        num_hidden_layers=2, num_attention_heads=2, num_key_value_heads=1, head_dim=16,
        linear_num_key_heads=1, linear_num_value_heads=2,
        linear_key_head_dim=16, linear_value_head_dim=16, linear_conv_kernel_dim=4,
        layer_types=["linear_attention", "full_attention"], max_position_embeddings=512, tie_word_embeddings=True,
        rope_parameters={"rope_type": "default", "rope_theta": 10000.0,
                         "partial_rotary_factor": 1.0, "mrope_section": [3, 3, 2]},
        pad_token_id=tokenizer.pad_token_id, eos_token_id=tokenizer.eos_token_id, use_cache=False)
    model = Qwen3_5ForCausalLM(model_config)
    model = get_peft_model(model, LoraConfig(r=2, lora_alpha=4, lora_dropout=0.0, bias="none",
                                           task_type="CAUSAL_LM", target_modules=["q_proj", "v_proj"]))
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    rows = [{"targetMessageIndex": 2, "lossMaskPolicy": "last-assistant-only", "messages": [
        {"role": "system", "content": "Return JSON."},
        {"role": "user", "content": json.dumps({"number": i})},
        {"role": "assistant", "content": json.dumps({"answer": i})}]} for i in (1, 2)]
    features = [tokenize_target(tokenizer, row, 512) for row in rows]
    preflight = adapter_preflight(model, features[0], tokenizer, output / "preflight-adapter")
    initial = {name: param.detach().clone() for name, param in model.named_parameters() if param.requires_grad}
    config = {"seed": 17, "device": "cpu", "quantization": {"compute_dtype": "float32"},
              "training": {"per_device_batch_size": 1, "gradient_accumulation_steps": 1,
                  "gradient_checkpointing": True, "learning_rate": 0.001, "warmup_ratio": 0.0,
                  "scheduler": "constant", "epochs": 1}}
    trainer = make_trainer(model, tokenizer, features, features, config, output / "checkpoints", smoke=True)
    if trainer.train_dataset[0]["labels"] != features[0]["labels"]:
        raise RuntimeError("trainer changed precomputed loss labels")
    result = trainer.train()
    if not any(not torch.equal(initial[name], p) for name, p in model.named_parameters() if name in initial):
        raise RuntimeError("optimizer step did not update any LoRA parameter")
    trainer.save_model(str(output / "adapter"))
    tokenizer.save_pretrained(output / "adapter")
    trainer.save_state()
    restored = PeftModel.from_pretrained(Qwen3_5ForCausalLM(model_config), output / "adapter")
    for name, tensor in restored.named_parameters():
        if name in initial and not torch.equal(tensor, model.get_parameter(name)):
            raise RuntimeError("saved adapter failed to reload on a fresh base model")
    config["training"]["checkpoint_steps"] = 1
    config["training"]["epochs"] = 2
    epoch_output = output / "epoch-loop"
    epoch_trainer = make_trainer(model, tokenizer, features, features, config, epoch_output / "checkpoints", smoke=False)
    run_inputs = {"fixture": "tiny-random-cpu", "seed": 17}
    (epoch_output / "run-inputs.json").write_text(json.dumps(run_inputs), encoding="utf-8")
    mirror = (output / "drive-mirror").resolve()

    class StopAfterFirstStep(TrainerCallback):
        def on_step_begin(self, args, state, control, **kwargs):
            if state.global_step == 0:
                (epoch_output / "control-request.json").write_text(json.dumps({"action": "pause"}), encoding="utf-8")
            return control

    epoch_trainer.add_callback(StopAfterFirstStep())
    with patch.dict(os.environ, {"DICTALAB_CHECKPOINT_MIRROR": str(mirror)}):
        first_result = epoch_trainer.train()
    if first_result.global_step != 1:
        raise RuntimeError("recovery fixture did not stop after its first optimizer step")
    progress = json.loads((epoch_output / "training-progress.json").read_text(encoding="utf-8"))
    if progress["step"] != 1 or any("eval_loss" in row for row in progress["history"]):
        raise RuntimeError("pause did not preserve step progress or evaluated a partial epoch")
    checkpoint = check_resume_checkpoint(epoch_output, epoch_output / "checkpoints/checkpoint-1", run_inputs)
    (epoch_output / "control-request.json").rename(epoch_output / "control-request.paused.json")
    epoch_trainer = make_trainer(model, tokenizer, features, features, config, epoch_output / "checkpoints", smoke=False)
    with patch.dict(os.environ, {"DICTALAB_CHECKPOINT_MIRROR": str(mirror)}):
        epoch_result = epoch_trainer.train(resume_from_checkpoint=str(checkpoint))
    if epoch_result.global_step != 4:
        raise RuntimeError("recovery did not continue from the saved optimizer step")
    optimizer_steps = {int(value["step"].item()) for value in epoch_trainer.optimizer.state.values() if "step" in value}
    if optimizer_steps != {4}:
        raise RuntimeError("recovery reset the optimizer instead of restoring its state")
    for step in (1, 2, 3, 4):
        saved = check_resume_checkpoint(mirror, mirror / f"checkpoints/checkpoint-{step}", run_inputs)
        state = json.loads((saved / "trainer_state.json").read_text(encoding="utf-8"))
        if step % 2 == 0 and not any(row.get("step") == step and "eval_loss" in row for row in state["log_history"]):
            raise RuntimeError("epoch checkpoint was mirrored before its validation")
    final_checkpoint = epoch_output / "checkpoints/checkpoint-4"
    if validate_checkpoint_integrity(final_checkpoint) != validate_checkpoint_integrity(mirror / "checkpoints/checkpoint-4"):
        raise RuntimeError("final epoch checkpoint differs from its immutable mirror")
    metrics = epoch_trainer.evaluate()
    if not epoch_trainer.state.best_model_checkpoint or not math.isfinite(metrics["eval_loss"]):
        raise RuntimeError("epoch evaluation or best-checkpoint reload failed")
    report = {"mode": "tiny-random-cpu-smoke", "trainingReady": False, "releaseAllowed": False,
              "realCheckpointLoaded": False, "datasetIsSyntheticFixture": True,
              "steps": result.global_step, "trainingLoss": result.training_loss,
              "maskPolicy": "last-assistant-only", "preflight": preflight,
              "trainerPreservedMask": True,
              "gradientCheckpointing": True,
              "optimizerUpdatedAdapter": True, "freshModelAdapterReload": True,
              "epochLoopSteps": epoch_result.global_step, "bestCheckpointReload": True,
              "recoveryCheckpointVerified": True, "resumedOptimizerState": True,
              "pauseFinishedStepAndSavedState": True, "pauseSkippedPartialEpochEvaluation": True,
              "epochMirrorAfterEvaluation": True, "driveRecoverySteps": [1, 2, 3, 4],
              "adapterHash": file_hash(output / "adapter/adapter_model.safetensors")}
    write_report(output, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run_smoke(args.output), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
