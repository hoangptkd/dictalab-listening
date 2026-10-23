"""Sequential Web account failover, only after an explicit quota rejection."""
from __future__ import annotations

import time
from pathlib import Path

import httpx

from training import generate_teacher_data as gen


class WebQuotaWait(Exception):
    def __init__(self, retry_at: float):
        super().__init__("All Web judge accounts are waiting for quota")
        self.retry_at = retry_at


def attempts(folder: Path, stem: str) -> list[dict]:
    return [gen.read_json(file) for file in sorted(folder.glob(f"{stem}.web-attempt-*.json"))]


def resumable(folder: Path, stem: str, body: dict) -> bool:
    rows = attempts(folder, stem)
    return bool(rows) and all(row.get("outcome") == "QUOTA" and
        row.get("requestHash") == gen.digest(body) for row in rows)


async def dispatch(path: Path, folder: Path, stem: str, body: dict, accounts: list[dict]):
    from training.iterate_teacher import _atomic_json, _bridge_url, _durable_exclusive_json

    if (not accounts or len({a["id"] for a in accounts}) != len(accounts) or
            len({a["url"] for a in accounts}) != len(accounts)):
        raise gen.DatasetError("Web accounts require unique IDs and loopback endpoints")
    for account in accounts:
        _bridge_url(account["url"])
    previous = attempts(folder, stem)
    if previous and not resumable(folder, stem, body):
        raise gen.DatasetError("Web send outcome unresolved; refusing replay")
    state_file = path / "web-account-state.json"
    state = gen.read_json(state_file) if state_file.exists() else {"cooldowns": {}}
    ordered = sorted(accounts, key=lambda a: a["id"] != state.get("activeAccount", accounts[0]["id"]))
    for account in ordered:
        if state["cooldowns"].get(account["id"], 0) > time.time():
            continue
        attempt_file = folder / f"{stem}.web-attempt-{len(previous) + 1:03d}.json"
        row = {"accountId": account["id"], "url": account["url"],
               "requestHash": gen.digest(body), "outcome": "UNKNOWN", "time": time.time()}
        _durable_exclusive_json(attempt_file, row)
        gen.append(path / "sent-index.jsonl", {"kind": "web-account-attempt", "stem": stem,
            "phase": folder.name, **row})
        async with httpx.AsyncClient(follow_redirects=False, trust_env=False, timeout=240) as client:
            response = await client.post(account["url"], json=body)
        envelope = gen.strict_json(response.text)
        row.update(httpStatus=response.status_code, envelope=envelope)
        quota = (response.status_code == 429 and isinstance(envelope, dict) and
                 envelope.get("error", {}).get("code") == "chatgpt_web_quota")
        if quota:
            # The bridge emits this code only for a visible quota notice, never a generic timeout.
            row["outcome"] = "QUOTA"
            _atomic_json(attempt_file, row)
            previous.append(row)
            state["cooldowns"][account["id"]] = time.time() + 900
            _atomic_json(state_file, state)
            continue
        row["outcome"] = "RESPONSE"
        _atomic_json(attempt_file, row)
        state["activeAccount"] = account["id"]
        state["cooldowns"].pop(account["id"], None)
        _atomic_json(state_file, state)
        return response, envelope, {"accountId": account["id"],
            "modelLabel": envelope.get("web_judge", {}).get("modelLabel"),
            "modelIdentity": "UI label only; underlying model unverified",
            "attempt": len(previous) + 1}
    raise WebQuotaWait(min(state["cooldowns"][a["id"]] for a in accounts))
