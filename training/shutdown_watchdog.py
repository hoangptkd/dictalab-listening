"""Opt-in Windows shutdown guard for a dedicated collection worker, never enabled by imports."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from training.prepare_dataset import DatasetError, ROOT


def shutdown_reason(state: dict, worker_alive: bool) -> str | None:
    if state.get("status") in {"BLOCKED", "WAITING_QUOTA", "WAITING_DEEPSEEK_QUOTA",
                               "WAITING_GEMINI_QUOTA", "WAITING_WEB_QUOTA", "CANDIDATE_CAP"}:
        return state["status"]
    if state.get("status") == "RUNNING" and not worker_alive:
        return "WORKER_EXITED_UNEXPECTEDLY"
    return None


def request_shutdown(reason: str, evidence: Path, *, execute=subprocess.run) -> None:
    # This function is called only by an explicitly armed watchdog or the operator.
    report = {"reason": reason, "requestedAt": time.time(), "command": ["shutdown.exe", "/s", "/f", "/t", "60"]}
    campaign = evidence.parent
    if (campaign / "manifest.json").exists() and (campaign / "state.json").exists():
        from training import collect_reviewed as collect
        manifest, _ = collect.load(campaign)
        report["checkpoint"] = collect.status(campaign)
        report["missing"] = {split: {level: target - report["checkpoint"]["counts"][split][level]
            for level in collect.LEVELS} for split, target in manifest["targetsPerLevel"].items()}
        # Sum known teacher usage once per candidate, including purged rejections.
        known = {}
        rows = {row["id"]: row for row in collect.decisions(campaign)}
        for file in (campaign / "candidates").glob("*.teacher-result.json"):
            row = json.loads(file.read_text(encoding="utf-8"))
            rows[row["id"]] = row
        for row in rows.values():
            usage = row.get("usage") or row.get("allUsage", {}).get("teacher-envelope") or {}
            for key, value in usage.items():
                if type(value) in (int, float):
                    known[key] = known.get(key, 0) + value
        report["knownTeacherUsage"] = known
        report["usageLimit"] = "Known numeric teacher usage only; unavailable usage and Web cost are not estimated."
    with evidence.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    result = execute(report["command"], capture_output=True, text=True, check=False)
    report.update(returnCode=result.returncode, stderr=result.stderr[:2000])
    evidence.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if result.returncode:
        raise DatasetError("Windows rejected the shutdown request; inspect shutdown evidence")


def watch(campaign: Path, pid: int, created: float) -> None:
    import psutil
    campaign = campaign.resolve()
    if (ROOT / "data/fine-tuning").resolve() not in campaign.parents:
        raise DatasetError("watchdog requires a private collection directory")
    while True:
        state = json.loads((campaign / "state.json").read_text(encoding="utf-8"))
        try:
            worker = psutil.Process(pid)
            alive = worker.is_running() and abs(worker.create_time() - created) < 0.01
        except psutil.NoSuchProcess:
            alive = False
        reason = shutdown_reason(state, alive)
        if reason and not alive:
            request_shutdown(reason, campaign / "shutdown-request.json")
            return
        if state.get("status") in {"READY", "AWAITING_CODEX_REVIEW", "COMPLETE"}:
            # Collection completion must proceed to dataset audit/SFT, not shut down here.
            return
        time.sleep(2)


def arm(campaign: Path) -> subprocess.Popen:
    import psutil
    import sys
    log = (campaign / "watchdog.log").open("ab")
    try:
        return subprocess.Popen([sys.executable, "-m", "training.shutdown_watchdog", "--campaign", str(campaign),
            "--worker-pid", str(os.getpid()), "--worker-created", str(psutil.Process().create_time())],
            cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    finally:
        log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", type=Path, required=True)
    parser.add_argument("--worker-pid", type=int, required=True)
    parser.add_argument("--worker-created", type=float, required=True)
    args = parser.parse_args()
    watch(args.campaign, args.worker_pid, args.worker_created)
