"""Publish one local run to Training Studio over HTTPS. Never starts or controls a trainer."""
import argparse
import getpass
import math
import time

from training import studio
from training import colab_reporter as transport


def capture(run_id):
    path = studio.find_marker(run_id)
    row = studio.describe(path, detail=True)
    if not row["configBound"] or row["device"] != "cpu":
        raise ValueError("Publisher requires a bound local CPU config")
    config_path, cfg = studio.config_for(studio.read(path))
    bindings = studio.inputs_for(config_path, cfg)
    bindings.pop("smoke")
    bindings["trainerHash"] = studio.sha(studio.ROOT / "training/train_sft_lora.py")
    settings = cfg["training"]
    config = {k: cfg[k] for k in ("base_model", "base_revision", "device", "seed", "max_seq_length", "packing", "loss_mask_policy", "assistant_only_loss", "enable_thinking")}
    config.update(lora={k: cfg["lora"][k] for k in ("r", "alpha", "dropout")},
                  quantization={k: cfg["quantization"][k] for k in ("compute_dtype", "load_in_4bit", "quant_type", "double_quant") if k in cfg["quantization"]},
                  training={k: settings[k] for k in ("epochs", "per_device_batch_size", "gradient_accumulation_steps", "learning_rate", "warmup_ratio", "checkpoint_steps", "scheduler", "gradient_checkpointing", "use_cache") if k in settings})
    history = [transport.numbers(h) for h in row["history"]][-120:]
    gradients = [h["grad_norm"] for h in history if "grad_norm" in h]
    checkpoint_step = max((c["step"] for c in row["checkpoints"] if c["verified"]), default=0)
    evidence = {"finiteLoggedGradients": bool(gradients) and all(math.isfinite(v) for v in gradients)}
    if checkpoint_step == row["step"] and checkpoint_step > 0 or row["completionVerified"]:
        evidence["actualOptimizerUpdates"] = row["step"]
    snapshot = studio.snapshot()
    resources = {k: v for k, v in snapshot["resources"].items() if k in ("cpuPercent", "cpuCores", "ramTotal", "ramAvailable", "ramPercent", "diskFree", "diskTotal") and isinstance(v, (int, float))}
    status = {"SUCCEEDED": "REPORTED_COMPLETE", "DRAFT": "WAITING", "STARTING": "RUNNING", "PAUSE_REQUESTED": "RUNNING", "STOP_REQUESTED": "RUNNING", "UNKNOWN": "INTERRUPTED"}.get(row["status"], row["status"])
    report = {"schemaVersion": 1, "sequence": time.time_ns(), "observedAt": studio.now(), "phase": "full", "status": status,
              "step": row["step"], "plannedSteps": row["plannedSteps"], "model": row["model"], "revision": row["revision"],
              "device": "cpu", "dtype": row["dtype"], "gpu": "", "bindings": bindings, "config": config,
              "history": history, "validation": transport.numbers(row["validation"], {"eval_loss", "eval_runtime", "eval_samples_per_second", "eval_steps_per_second", "epoch"}),
              "optimizerEvidence": evidence, "resources": resources,
              "checkpoints": [{"name": c["name"], "step": c["step"], "integrityReported": c["verified"], "fileCount": len(c.get("files", []))} for c in row["checkpoints"][:2]],
              "artifacts": {}, "failure": "runtime" if row.get("error") else "none"}
    report["dataset"] = transport.dataset_summary(studio.ROOT, cfg)
    if row["completionVerified"]:
        manifest = studio.read(studio.contained(cfg["output_dir"]) / "run-manifest.json")
        report["artifacts"] = {k: manifest[k] for k in ("adapterHash", "adapterConfigHash")}
    for name in ("startedAt", "finishedAt"):
        if row.get(name): report[name] = row[name]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--endpoint")
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    if args.list:
        for row in studio.snapshot()["runs"]:
            if row["configBound"]: print(row["id"], row["name"], row["status"], row["step"])
        return
    if not args.run_id or not args.endpoint: parser.error("--run-id and --endpoint are required")
    # Check the local identity before asking for a secret. No trainer is ever dispatched.
    capture(args.run_id)
    client = transport.Reporter(args.endpoint, getpass.getpass("Training Studio per-run key (hidden): "),
                                None, None, None, None, capture_fn=lambda: capture(args.run_id))
    try:
        if args.once:
            client.once(); print(client.state)
            return
        print(client.start())
        while client.thread and client.thread.is_alive() and not client.event.wait(30):
            pass
    except KeyboardInterrupt:
        pass
    finally:
        client.stop()


if __name__ == "__main__":
    main()
