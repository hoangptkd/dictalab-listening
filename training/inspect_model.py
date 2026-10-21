"""Inspect a Transformers checkpoint before attaching a LoRA adapter."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import re
from pathlib import Path
from typing import Any, Iterable


ATTENTION_NAMES = {"q_proj", "k_proj", "v_proj", "o_proj"}
MLP_NAMES = {"gate_proj", "up_proj", "down_proj", "gate_up_proj"}


def target_module_names(module_names: Iterable[str], *, include_mlp: bool = False) -> list[str]:
    """Return full text projection paths; suffix matching can also select vision layers."""
    allowed = ATTENTION_NAMES | (MLP_NAMES if include_mlp else set())
    result: list[str] = []
    seen: set[str] = set()
    for name in module_names:
        if any(part in {"visual", "vision", "vision_tower", "vision_model"} for part in name.split(".")):
            continue
        leaf = name.rsplit(".", 1)[-1]
        if leaf in allowed and name not in seen:
            result.append(name)
            seen.add(name)
    return result


def inspect(model_id: str, output: Path, *, revision: str, include_mlp: bool = False) -> dict[str, Any]:
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("revision must be an immutable 40-character Hugging Face commit SHA")
    if output.exists():
        raise ValueError("refusing to overwrite existing inspection report")
    try:
        import torch
        from transformers import AutoConfig, AutoTokenizer, Qwen3_5ForCausalLM
    except ImportError as exc:
        raise RuntimeError("inspect_model requires the pinned training environment") from exc
    config = AutoConfig.from_pretrained(model_id, revision=revision, trust_remote_code=False)
    tokenizer = AutoTokenizer.from_pretrained(model_id, revision=revision, trust_remote_code=False)
    text_config = config.get_text_config()
    if text_config.model_type != "qwen3_5_text":
        raise ValueError("this P0 inspection requires a Qwen3.5 text backbone")
    # Shape inspection needs no weight download, GPU allocation or random tensor initialization.
    with torch.device("meta"):
        model = Qwen3_5ForCausalLM(text_config)
    names = [name for name, module in model.named_modules()
             if name and isinstance(module, torch.nn.Linear)]
    attention_targets = target_module_names(names)
    selected = target_module_names(names, include_mlp=include_mlp)
    if not attention_targets:
        raise RuntimeError("no attention projection found; inspect module map before writing a LoRA config")
    samples = [{"role": "system", "content": "Return only JSON."},
               {"role": "user", "content": '{"task":"format check"}'},
               {"role": "assistant", "content": '{"ok":true}'}]
    rendered = tokenizer.apply_chat_template(samples, tokenize=False, enable_thinking=False)
    if not rendered or '{"ok":true}' not in rendered:
        raise RuntimeError("chat template did not preserve the target JSON")
    report = {
        "modelId": model_id,
        "revision": revision,
        "inspectionMode": "config-only-meta-device",
        "weightsLoaded": False,
        "forwardBackwardVerified": False,
        "assistantMaskVerified": False,
        "versions": {p: importlib.metadata.version(p) for p in ("torch", "transformers")},
        "modelType": getattr(config, "model_type", None),
        "architecture": getattr(config, "architectures", None),
        "modelClass": model.__class__.__name__,
        "tokenizerClass": tokenizer.__class__.__name__,
        "hasChatTemplate": bool(getattr(tokenizer, "chat_template", None)),
        "chatTemplateHash": hashlib.sha256(str(tokenizer.chat_template).encode()).hexdigest(),
        "chatTemplateSmokePassed": True,
        "moduleLeaves": names,
        "attentionTargets": attention_targets,
        "selectedTargets": selected,
        "unselectedLinearModules": [name for name in names if name not in selected],
        "includeMlp": include_mlp,
        "parameterCountFromConfig": sum(p.numel() for p in model.parameters()),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-mlp", action="store_true")
    args = parser.parse_args()
    try:
        report = inspect(args.model, args.output, revision=args.revision, include_mlp=args.include_mlp)
    except (OSError, RuntimeError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps({"model": report["modelId"], "targets": report["selectedTargets"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
