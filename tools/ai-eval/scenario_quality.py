"""One-shot synthetic Scenario capture; offline report/replay never call a provider.

Follows the eval-only observer pattern in attempt_trace.py. Production is untouched.
Capture preserves parsed replies BEFORE domain validation, plus bounded wire content
for malformed replies. No credentials, request headers or full Settings are persisted.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services/ai-generation"))
from app import scenario_workflow as workflow
from app.config import Settings
from app.jobs import JobRequest
from app.providers.base import ProviderHttpError
from app.redaction import redact_string

DEFAULT_CASES = ROOT / "services/ai-generation/tests/fixtures/evaluation/scenario-quality-v1.json"
PROGRAM = ROOT / "src/main/resources/scenario/programs-v1.json"


def now():
    return datetime.now(timezone.utc).isoformat()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def load(path):
    return Path(path).read_text(encoding="utf-8")


def read(path):
    return json.loads(load(path))


def write(path, value):
    # Exclusive creation makes both repeated capture and accidental overwrites fail closed.
    with Path(path).open("xb") as handle:
        handle.write(encoded(value))


def cases(path):
    dataset = read(path)
    rows = dataset.get("cases", [])
    if dataset.get("syntheticOnly") is not True or not 1 <= len(rows) <= 100:
        raise ValueError("only explicit synthetic corpora with 1..100 cases are supported")
    ids = set()
    for row in rows:
        ident = row.get("id", "")
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", ident) or ident in ids:
            raise ValueError("invalid or duplicate case ID")
        if (row.get("profileId") not in {"supported", "standard", "challenge"}
                or type(row.get("expectedAllowed")) is not bool
                or not isinstance(row.get("inputVi"), str) or not 1 <= len(row["inputVi"]) <= 2000
                or redact_string(row["inputVi"]) != row["inputVi"]):
            raise ValueError("invalid case or input contains contact/credential-like data")
        ids.add(ident)
    return dataset


def failure(error):
    # Do not persist exception strings from HTTP/database clients: they can contain credentials.
    record = {"type": type(error).__name__}
    if re.fullmatch(r"[a-z_]+:scenario_(extract|extract_review|generate|questions|translate|review):[a-z_]+(?::[0-9]+)?", str(error)):
        record["providerCode"] = str(error)
    if isinstance(error, workflow.QualityRejected):
        record["reason"] = str(error)
        if type(error.__cause__) is ValueError:
            record["validationReason"] = redact_string(str(error.__cause__))[:400]
    schema_error = error.__cause__ if hasattr(error.__cause__, "errors") else error
    if hasattr(schema_error, "errors"):
        record["schemaErrors"] = [{"loc": e["loc"], "type": e["type"]}
                                  for e in schema_error.errors(include_input=False)]
    if isinstance(error, ProviderHttpError):
        record["httpStatus"] = error.status
    return record


class CaptureBudgetExhausted(RuntimeError):
    pass


async def phase(folder, payload, settings, budget):
    folder.mkdir()
    write(folder / "input.json", payload.model_dump(mode="json"))
    write(folder / "started.json", {"at": now(), "jobId": str(uuid4())})
    original = workflow.ModelGateway
    index = 0

    class RecordedGateway(original):
        async def complete(self, messages, schema, deadline, purpose, **options):
            nonlocal index
            if budget["calls"] >= budget["maxCalls"] or time.monotonic() >= budget["deadline"]:
                raise CaptureBudgetExhausted("capture budget exhausted before provider call")
            index += 1
            budget["calls"] += 1
            prefix = f"{index:02d}-{purpose}"
            write(folder / (prefix + "-request.json"), {"at": now(), "purpose": purpose,
                  "messages": messages, "schema": schema, "options": options,
                  "maxOutputTokens": self._settings.num_predict})
            started = time.monotonic()
            wires = []

            async def wire(response):
                await response.aread()
                item = {"httpStatus": response.status_code, "bodySha256": digest(response.content)}
                try:
                    body = response.json()
                    # Preserve only model text and usage, never auth headers, URL or arbitrary error bodies.
                    if response.is_success:
                        item["model"] = body.get("model")
                        item["usage"] = body.get("usage")
                        choices = body.get("choices") or []
                        if choices: item["outputText"] = choices[0].get("message", {}).get("content")
                        elif "message" in body: item["outputText"] = body["message"].get("content")
                except (ValueError, AttributeError, TypeError):
                    if response.is_success: item["unparsedBodyRedacted"] = redact_string(response.text[:64000])
                wires.append(item)

            self._client.event_hooks["response"].append(wire)
            try:
                raw, provider = await super().complete(messages, schema, min(deadline, budget["deadline"]), purpose, **options)
                write(folder / (prefix + "-response.json"), {"at": now(), "seconds": round(time.monotonic()-started, 3),
                      "provider": provider, "model": self.last_model, "sourceId": self.last_source_id, "raw": raw})
                return raw, provider
            except Exception as error:
                write(folder / (prefix + "-error.json"), failure(error))
                raise
            finally:
                self._client.event_hooks["response"].remove(wire)
                write(folder / (prefix + "-wire.json"), wires)

    started = time.monotonic()
    identifier = read(folder / "started.json")["jobId"]
    event = JobRequest(jobId=identifier, requestId=identifier, userScope="e" * 64,
                       idempotencyKey=identifier, deadline=datetime.now(timezone.utc)+timedelta(seconds=240), payload=payload)
    try:
        with patch.object(workflow, "ModelGateway", RecordedGateway):
            result = await workflow.execute(event, settings)
        outcome = {"status": "SUCCEEDED", "result": result}
    except Exception as error:
        outcome = {"status": "REJECTED" if isinstance(error, workflow.QualityRejected) else
                   ("BUDGET_STOPPED" if index else "NOT_STARTED") if isinstance(error, CaptureBudgetExhausted) else "UNKNOWN",
                   "error": failure(error)}
    outcome.update(at=now(), seconds=round(time.monotonic()-started, 3), calls=index)
    write(folder / "outcome.json", outcome)
    return outcome


async def capture(dataset_path, output, settings, *, max_calls=64, max_seconds=1200, baseline=None):
    dataset = cases(dataset_path)
    if not settings.scenario_enabled:
        raise ValueError("Scenario execution must be explicitly enabled in protected configuration")
    output = Path(output).resolve()
    # Do not write private captures outside ignored data/.
    if not output.is_relative_to(ROOT / "data"):
        raise ValueError("captures must live under ignored repository data/")
    if baseline is not None:
        verify(baseline)
        if read(Path(baseline) / "manifest.json")["datasetHash"] != digest(encoded(dataset)):
            raise ValueError("baseline corpus differs")
    program = next(p for p in read(PROGRAM) if p["code"] == "general-communication")
    output.mkdir(parents=True, exist_ok=False)
    source = (ROOT / "services/ai-generation/app/scenario_workflow.py").read_bytes()
    harness_source = Path(__file__).read_bytes()
    settings_safe = {"provider": settings.selected_provider, "model": workflow.metadata(settings)["model"],
                     "numCtx": settings.num_ctx, "modelDigest": workflow.metadata(settings)["modelDigest"],
                     "pool": [{"id": s.id, "platform": s.platform, "model": s.model, "endpointHash": digest(s.url.encode())}
                              for s in settings.sources_for(settings.selected_provider)]}
    manifest = {"format": "scenario-capture.v1", "createdAt": now(), "datasetHash": digest(encoded(dataset)),
                "promptHash": workflow.prompt_hash(), "workflowSourceHash": digest(source),
                "harnessSourceHash": digest(harness_source), "pythonVersion": sys.version.split()[0],
                "libraries": {name: importlib.metadata.version(name) for name in ("pydantic", "pydantic-settings", "httpx")},
                "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "programHash": program["definitionHash"], "settings": settings_safe,
                "maxCalls": max_calls, "maxSeconds": max_seconds, "seed": None,
                "baselineIntegrityHash": digest((Path(baseline) / "integrity.json").read_bytes()) if baseline else None,
                "notice": "Cloud sampling has no guaranteed deterministic seed. Raw captures are immutable evidence, not release approval."}
    write(output / "manifest.json", manifest)
    write(output / "cases.json", dataset)
    write(output / "program.json", program)
    (output / "workflow.source.py").write_bytes(source)
    (output / "harness.source.py").write_bytes(harness_source)
    write(output / "instructions.json", workflow.INSTRUCTIONS)
    budget = {"calls": 0, "maxCalls": max_calls, "deadline": time.monotonic()+max_seconds}
    for case in dataset["cases"]:
        folder = output / case["id"]
        folder.mkdir()
        profile = next(p for p in program["difficultyProfiles"] if p["id"] == case["profileId"])
        profile = {k: profile[k] for k in ("id", "minWordsPerActivity", "maxWordsPerActivity", "maxWordsPerSession")}
        payload = workflow.ScenarioInput(phase="EXTRACT", contractVersion="scenario-job.v1",
                      promptHash=workflow.prompt_hash(), programHash=program["definitionHash"], profile=profile, inputVi=case["inputVi"])
        extract = await phase(folder / "EXTRACT", payload, settings, budget)
        print(case["id"], "EXTRACT", extract["status"], flush=True)
        if extract["status"] == "SUCCEEDED" and extract["result"]["task"]["allowed"]:
            payload = payload.model_copy(update={"phase": "GENERATE", "specification": workflow.Specification.model_validate(extract["result"]["task"])})
            generated = await phase(folder / "GENERATE", payload, settings, budget)
            print(case["id"], "GENERATE", generated["status"], flush=True)
    write(output / "finished.json", {"at": now(), "modelCalls": budget["calls"]})
    hashes = {str(p.relative_to(output)).replace("\\", "/"): digest(p.read_bytes()) for p in output.rglob("*") if p.is_file()}
    write(output / "integrity.json", hashes)
    (output / "derived").mkdir()


def verify(output):
    root = Path(output)
    if not (root / "integrity.json").exists():
        raise ValueError("capture is incomplete/unsealed; inspect started/outcome files without issuing fresh calls")
    inventory = read(root / "integrity.json")
    actual = {str(p.relative_to(root)).replace("\\", "/") for p in root.rglob("*") if p.is_file()
              and not p.is_relative_to(root / "derived") and p != root / "integrity.json"}
    if actual != set(inventory): raise ValueError("unsealed/missing evidence files")
    for name, expected in inventory.items():
        target = (root / name).resolve()
        if not target.is_relative_to(root.resolve()) or digest(target.read_bytes()) != expected:
            raise ValueError("capture integrity mismatch: " + name)


def report(output, *, allow_incomplete=False):
    output = Path(output)
    sealed = (output / "integrity.json").exists()
    if sealed or not allow_incomplete: verify(output)
    rows = []
    for case in read(output / "cases.json")["cases"]:
        row = {**case, "phases": {}}
        for name in ("EXTRACT", "GENERATE"):
            folder = output / case["id"] / name
            if not folder.exists(): continue
            outcome = read(folder / "outcome.json") if (folder / "outcome.json").exists() else {"status": "UNKNOWN_INTERRUPTED"}
            row["phases"][name] = outcome
            row["phases"][name]["replies"] = [{"artifact": str(p.relative_to(output)), **read(p)}
                                               for p in sorted(folder.glob("*-response.json"))]
        rows.append(row)
    finished = read(output / "finished.json") if (output / "finished.json").exists() else None
    failures, generated, expected_generated = [], 0, 0
    for row in rows:
        extract = row["phases"].get("EXTRACT", {})
        allowed = extract.get("result", {}).get("task", {}).get("allowed")
        correct = extract.get("status") == "SUCCEEDED" and allowed is row["expectedAllowed"]
        generation = row["phases"].get("GENERATE")
        if row["expectedAllowed"]:
            expected_generated += 1
            passed = bool(generation and generation["status"] == "SUCCEEDED"
                          and generation.get("result", {}).get("task", {}).get("qualityPassed") is True)
            generated += int(passed)
            correct = correct and passed
        else:
            correct = correct and generation is None
        if not correct: failures.append(row["id"])
    acceptance = {"passed": bool(sealed and finished and not failures), "expectedCases": len(rows),
                  "failedCaseIds": failures, "generationsExpected": expected_generated,
                  "generationsSucceeded": generated, "notice": "Automatic gates only; not independent human or release approval."}
    return {"manifest": read(output / "manifest.json"), "evidenceIntegrity": "VERIFIED" if sealed else "UNSEALED_INCOMPLETE",
            "finished": finished, "acceptance": acceptance, "cases": rows}


async def replay(output):
    output = Path(output)
    verify(output)
    manifest = read(output / "manifest.json")
    if manifest["promptHash"] != workflow.prompt_hash():
        raise ValueError("workflow pin changed; use report for archived evidence, not a different workflow as exact replay")
    # Offline construction intentionally avoids env credentials/registry loading. No live gateway is used.
    settings = Settings.model_construct(scenario_enabled=True, primary_provider=manifest["settings"]["provider"],
                  gemini_model=manifest["settings"]["model"], model=manifest["settings"]["model"],
                  model_digest=manifest["settings"]["modelDigest"])
    rows = []
    for folder in sorted(output.glob("*/*")):
        if not folder.is_dir() or folder.name not in ("EXTRACT", "GENERATE"): continue
        requests = sorted(folder.glob("*-request.json"))
        consumed = []

        class Playback:
            def __init__(self, *_): self.last_model = manifest["settings"]["model"]

            async def complete(self, messages, schema, deadline, purpose, **options):
                index = len(consumed)
                if index >= len(requests): raise ValueError("unrecorded call; replay will not contact a provider")
                saved = read(requests[index])
                if (saved["messages"] != messages or saved["schema"] != schema
                        or saved["purpose"] != purpose or saved["options"] != options):
                    raise ValueError("replay request differs from archived call")
                consumed.append(purpose)
                response = requests[index].with_name(requests[index].name.replace("-request.json", "-response.json"))
                if not response.exists(): raise RuntimeError("captured provider failure; no parsed reply available")
                row = read(response)
                self.last_model = row["model"]
                return row["raw"], row["provider"]

        payload = workflow.ScenarioInput.model_validate(read(folder / "input.json"))
        identifier = read(folder / "started.json")["jobId"]
        event = JobRequest(jobId=identifier, requestId=identifier, userScope="e"*64, idempotencyKey=identifier,
                           deadline=datetime.now(timezone.utc)+timedelta(seconds=240), payload=payload)
        try:
            with patch.object(workflow, "ModelGateway", Playback): result = await workflow.execute(event, settings)
            state = "SUCCEEDED"
            equal = result == read(folder / "outcome.json").get("result")
        except workflow.QualityRejected as error:
            saved = read(folder / "outcome.json")
            state, equal = "REJECTED", saved["status"] == "REJECTED" and saved.get("error") == failure(error)
        except Exception:
            state, equal = "UNREPLAYABLE", False
        rows.append({"caseId": folder.parent.name, "phase": folder.name, "status": state,
                     "sameOutcome": equal, "archivedCallsConsumed": len(consumed)})
    return {"networkCalls": 0, "promptHash": workflow.prompt_hash(), "phases": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    live = sub.add_parser("capture")
    live.add_argument("--dataset", type=Path, default=DEFAULT_CASES)
    live.add_argument("--output", type=Path, required=True)
    live.add_argument("--max-calls", type=int, default=64)
    live.add_argument("--baseline", type=Path)
    live.add_argument("--max-seconds", type=int, default=1200)
    for name in ("report", "replay"):
        command = sub.add_parser(name)
        command.add_argument("--capture", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
        if name == "report": command.add_argument("--allow-incomplete", action="store_true")
        if name == "report": command.add_argument("--require-pass", action="store_true")
    args = parser.parse_args()
    if args.action == "capture":
        if not 1 <= args.max_calls <= 100 or not 1 <= args.max_seconds <= 3600: parser.error("invalid capture budget")
        asyncio.run(capture(args.dataset, args.output, Settings(), max_calls=args.max_calls, max_seconds=args.max_seconds, baseline=args.baseline))
    else:
        value = report(args.capture, allow_incomplete=args.allow_incomplete) if args.action == "report" else asyncio.run(replay(args.capture))
        write(args.output, value)
        print(args.action, "saved; provider calls 0")
        if args.action == "report" and args.require_pass and not value["acceptance"]["passed"]:
            raise SystemExit(2)


if __name__ == "__main__":
    main()
