"""Exercise the SFT runner on a tiny random Qwen3.5 model, entirely offline."""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import string
import sys

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from training.train_sft_lora import adapter_preflight, file_hash, make_trainer, tokenize_target, write_report


def run_smoke(output: Path) -> dict:
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_DATASETS_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    import torch
    from peft import LoraConfig, PeftModel, get_peft_model
    from tokenizers import Tokenizer, models
    from transformers import PreTrainedTokenizerFast, Qwen3_5ForCausalLM, Qwen3_5TextConfig, set_seed

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
        num_hidden_layers=1, num_attention_heads=2, num_key_value_heads=1, head_dim=16,
        layer_types=["full_attention"], max_position_embeddings=512, tie_word_embeddings=True,
        rope_parameters={"rope_type": "default", "rope_theta": 10000.0,
                         "partial_rotary_factor": 1.0, "mrope_section": [3, 3, 2]},
        pad_token_id=tokenizer.pad_token_id, eos_token_id=tokenizer.eos_token_id, use_cache=False)
    model = Qwen3_5ForCausalLM(model_config)
    model = get_peft_model(model, LoraConfig(r=2, lora_alpha=4, lora_dropout=0.0, bias="none",
                                           task_type="CAUSAL_LM", target_modules=["q_proj", "v_proj"]))
    rows = [{"targetMessageIndex": 2, "lossMaskPolicy": "last-assistant-only", "messages": [
        {"role": "system", "content": "Return JSON."},
        {"role": "user", "content": json.dumps({"number": i})},
        {"role": "assistant", "content": json.dumps({"answer": i})}]} for i in (1, 2)]
    features = [tokenize_target(tokenizer, row, 512) for row in rows]
    preflight = adapter_preflight(model, features[0], tokenizer, output / "preflight-adapter")
    initial = {name: param.detach().clone() for name, param in model.named_parameters() if param.requires_grad}
    config = {"seed": 17, "device": "cpu", "quantization": {"compute_dtype": "float32"},
              "training": {"per_device_batch_size": 1, "gradient_accumulation_steps": 1,
                  "gradient_checkpointing": False, "learning_rate": 0.001, "warmup_ratio": 0.0,
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
    epoch_trainer = make_trainer(model, tokenizer, features, features, config, output / "epoch-loop", smoke=False)
    epoch_result = epoch_trainer.train()
    metrics = epoch_trainer.evaluate()
    if not epoch_trainer.state.best_model_checkpoint or not math.isfinite(metrics["eval_loss"]):
        raise RuntimeError("epoch evaluation or best-checkpoint reload failed")
    report = {"mode": "tiny-random-cpu-smoke", "trainingReady": False, "releaseAllowed": False,
              "realCheckpointLoaded": False, "datasetIsSyntheticFixture": True,
              "steps": result.global_step, "trainingLoss": result.training_loss,
              "maskPolicy": "last-assistant-only", "preflight": preflight,
              "trainerPreservedMask": True,
              "optimizerUpdatedAdapter": True, "freshModelAdapterReload": True,
              "epochLoopSteps": epoch_result.global_step, "bestCheckpointReload": True,
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
