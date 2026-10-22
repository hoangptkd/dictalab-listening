"""Bounded DeepSeek teacher A/B and gated chains; no retry, repair, fallback or training.

Run prepare, ab, review the blinded packet, then chain once per reviewed round.
The immutable plan permits 60 A/B + 18 chain generation calls in total.
"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import random
import statistics
import sys
import time

from training import generate_teacher_data as gen
from training.semantic_rubric import CHECKS, VERSION, VERSION_V3, rules_for, decision, severity
from jsonschema import Draft202012Validator

sys.path.insert(0, str(gen.ROOT / "services/ai-generation"))
from app.config import Settings
from app.gemini_pool import quota_store
from app.model_gateway import ModelGateway
import httpx
from dotenv import dotenv_values


BASELINE = gen.ROOT / "artifacts/prompts/conversation-cefr-b1-v7-20261021.json"


def settings_from_registry():
    env = dict(dotenv_values(gen.ROOT / ".env")) | dict(os.environ)
    return Settings(primary_provider="deepseek", ai_provider_config_source="database",
        ai_provider_database_url=env["AI_PROVIDER_DATABASE_URL"],
        ai_provider_encryption_key=env["AI_PROVIDER_ENCRYPTION_KEY"],
        gemini_quota_backend="redis",
        gemini_quota_redis_url=env.get("GEMINI_QUOTA_REDIS_URL", "redis://127.0.0.1:16379/0"),
        num_predict=8192, temperature=0.2, timeout_seconds=120, repair_enabled=False)


def prepare(output: Path, settings, *, baseline: Path = BASELINE, candidate: Path | None = None,
            pilot: bool = False, rubric_version: str = VERSION) -> dict:
    if output.exists():
        raise gen.DatasetError("refusing to overwrite/replan a benchmark")
    catalogue = gen.read_json(gen.CATALOGUE)["catalogue"]
    rules = rules_for(rubric_version)
    artifacts = [gen.read_json(baseline), gen.read_json(candidate or gen.ARTIFACT)]
    for artifact in artifacts:
        if artifact["sha256"] != gen.digest({k: v for k, v in artifact.items() if k != "sha256"}):
            raise gen.DatasetError("artifact hash mismatch before benchmark freeze")
    groups = gen.plan_groups(catalogue)
    contexts, chains = [], []
    for level in ("A1", "A2", "B1"):
        selected = sorted((g for g in groups if g["level"] == level), key=lambda g: gen.digest(g["groupId"]))
        for group in selected[:1 if pilot else 5]:
            for turn in (2, 4):
                contexts.append({"group": group, "context": gen.make_context(group, turn, [], artifacts[1], 0)})
        if not pilot:
            chains.extend(selected[5:7])
    cap = len(contexts) * 2 + len(chains) * 3
    sources = settings.sources_for("deepseek")
    if len(sources) != 1:
        raise gen.DatasetError("benchmark requires the single configured DeepSeek account")
    source = sources[0]
    pool = quota_store(settings)
    status = pool.execute(source, op="status")
    policy = settings.registry_pools["deepseek"]
    if source.rpd_limit and source.rpd_limit - status[2] < cap or (
            policy["global_rpd_limit"] and policy["global_rpd_limit"] - status[4] < cap):
        raise gen.DatasetError(f"insufficient remaining quota for the bounded {cap}-call plan; no requests sent")
    entries = [{"id": f"sample-{i:03}", "arm": arm, "group": row["group"],
                "context": copy.deepcopy(row["context"])}
               for i, (arm, row) in enumerate((a, row) for row in contexts for a in (0, 1))]
    random.Random(20261022).shuffle(entries)
    for i, entry in enumerate(entries):
        entry["id"] = f"blind-{i+1:03}"
    plan = {"createdAt": datetime.now(timezone.utc).isoformat(), "entries": entries,
        "chains": chains, "artifacts": artifacts, "artifactHashes": [a["sha256"] for a in artifacts],
        "teacherSystem": gen.TEACHER_BATCH, "teacherSystemHash": gen.digest(gen.TEACHER_BATCH),
        "rubricVersion": rubric_version, "rubricHash": gen.digest(rules), "httpCap": cap,
        "pilotOnly": pilot,
        "developmentGate": {"hardPass": 30, "semanticPass": 27, "perLevel": 9,
            "criticalFailures": 0, "completeChains": 5},
        "provider": "deepseek", "model": settings.active_cloud_model, "sourceId": source.id,
        "quotaBefore": status, "maxOutputTokens": 8192, "temperature": 0.2,
        "intervalSeconds": max(5.1, 60 / min(source.effective_rpm, policy["global_rpm_limit"] or source.effective_rpm) + 0.2),
        "repairEnabled": False, "retryEnabled": False, "fallbackEnabled": False,
        "productionWireRequest": False, "trainingReady": False,
        "comparison": "Same frozen contexts and teacher wrapper; only prompt artifact differs. Development data, never final holdout/SFT.",
        "review": "Delegated AI:Codex; model/arm hidden in review packet; no automatic judge approval."}
    plan["planHash"] = gen.digest(plan)
    output.mkdir(parents=True)
    gen.write_json(output / "plan.json", plan)
    return plan


def load_plan(output: Path) -> dict:
    plan = gen.read_json(output / "plan.json")
    if plan["planHash"] != gen.digest({k: v for k, v in plan.items() if k != "planHash"}):
        raise gen.DatasetError("benchmark plan hash mismatch")
    if plan["rubricHash"] != gen.digest(rules_for(plan["rubricVersion"])):
        raise gen.DatasetError("rubric changed after plan freeze")
    return plan


def reviewed(row: dict, reviews: dict, rubric_version: str | None = None) -> str:
    review = reviews.get(row["id"], {})
    if rubric_version is not None and review.get("rubricVersion") != rubric_version:
        return "REVIEW_REQUIRED"
    if rubric_version == VERSION_V3 and review.get("rubricHash") != gen.digest(rules_for(rubric_version)):
        return "REVIEW_REQUIRED"
    if not row.get("hardPass") or review.get("reviewer") != "AI:Codex":
        return "REVIEW_REQUIRED"
    if review.get("taskHash") != gen.digest(row.get("task")) or review.get("contextHash") != gen.digest(row["context"]):
        return "REVIEW_REQUIRED"
    if set(review.get("checks", {})) != set(CHECKS):
        return "REVIEW_REQUIRED"
    verdict = {**review["checks"], "issues": review.get("issues")}
    return decision(verdict)


def blind_packet(output: Path):
    plan = load_plan(output)
    rows = [gen.read_json(path) for path in sorted(output.glob("*.candidate.json"))]
    # Write a fresh numbered packet; previous review evidence is never overwritten.
    number = len(list(output.glob("review-packet-*.json"))) + 1
    gen.write_json(output / f"review-packet-{number:02}.json", {"rubricVersion": plan["rubricVersion"],
        "rubricHash": plan["rubricHash"], "rubricRules": rules_for(plan["rubricVersion"]),
        "items": [{"id": row["id"], "context": row["context"], "task": row.get("task"),
            "hardPass": row.get("hardPass", False), "taskHash": gen.digest(row.get("task")),
            "contextHash": gen.digest(row["context"])} for row in rows]})


async def generate(output: Path, mode: str, settings):
    plan = load_plan(output)
    if settings.active_cloud_model != plan["model"] or [s.id for s in settings.sources_for("deepseek")] != [plan["sourceId"]]:
        raise gen.DatasetError("provider model/account changed after plan freeze")
    reviews = gen.read_json(output / "reviews.json") if (output / "reviews.json").exists() else {}
    entries = plan["entries"] if mode == "ab" else []
    if mode == "chain":
        for index, group in enumerate(plan["chains"]):
            prior = []
            for turn in (2, 3, 4):
                ident = f"chain-{index+1}-t{turn}"
                path = output / f"{ident}.candidate.json"
                if path.exists():
                    row = gen.read_json(path)
                    if reviewed(row, reviews, plan["rubricVersion"]) != "PASS":
                        break
                    prior.append({**row, "eligible": True})
                    continue
                context = gen.make_context(group, turn, prior, plan["artifacts"][1], index)
                entries.append({"id": ident, "group": group, "context": context, "arm": 1})
                break  # Every dependent turn requires an explicit, hash-bound semantic approval.
    state = {}
    secrets = [s.api_key for s in settings.enabled_cloud_sources]

    def clean(value):
        for secret in secrets:
            value = value.replace(secret, "[redacted]")
        return value

    async def request_hook(request):
        body = gen.strict_json(request.content.decode("utf-8"))
        ledger = output / "api-reservations.jsonl"
        calls = len(ledger.read_text(encoding="utf-8").splitlines()) if ledger.exists() else 0
        if calls >= plan["httpCap"] or body["model"] != plan["model"] or gen._contains_secret(body):
            raise gen.DatasetError("request cap/model/privacy gate failed")
        if body.get("thinking") != {"type": "disabled"}:
            raise gen.DatasetError("benchmark thinking configuration changed")
        gen.write_json(output / f"{state['id']}.request.json", body)
        gen.append(ledger, {"id": state["id"], "time": time.time(), "requestHash": gen.digest(body)})
        state["sent"] = True

    async def response_hook(response):
        await response.aread()
        state["httpStatus"] = response.status_code
        value = clean(response.text)
        try:
            envelope = gen.strict_json(value)
        except ValueError:
            envelope = {"invalidEnvelope": value[:1_000_000]}
        gen.write_json(output / f"{state['id']}.envelope.json", envelope)

    async with httpx.AsyncClient(follow_redirects=False,
            event_hooks={"request": [request_hook], "response": [response_hook]}) as client:
        for entry in entries:
            ident = entry["id"]
            path = output / f"{ident}.candidate.json"
            if path.exists():
                continue
            if (output / f"{ident}.request.json").exists():
                raise gen.DatasetError(f"{ident}: interrupted attempt, outcome unknown; refusing replay")
            ledger = output / "api-reservations.jsonl"
            reservations = [json.loads(line) for line in ledger.read_text(encoding="utf-8").splitlines()] if ledger.exists() else []
            if reservations:
                await asyncio.sleep(max(0, plan["intervalSeconds"] - (time.time() - reservations[-1]["time"])))
            state.clear(); state.update(id=ident, sent=False, httpStatus=None)
            artifact = plan["artifacts"][entry["arm"]]
            context, group = entry["context"], entry["group"]
            schema = artifact.get("contentSchemas", {}).get(group["level"], artifact["contentSchema"])
            full_schema = gen.batch_schema([ident], schema, artifact["translationSchema"])
            payload = {"contentSystem": gen.production_messages(artifact, context, "content")[0]["content"],
                "contentSchema": schema, "translationSystem": artifact["translationSystem"],
                "translationSchema": artifact["translationSchema"],
                "items": [{"id": ident, "context": context, "authoringRules": gen.authoring_rules(context)}]}
            row = {**entry, "hardPass": False, "eligible": False}
            started = time.monotonic()
            try:
                gateway = ModelGateway(settings, client)
                result, _ = await gateway.complete([{"role": "system", "content": plan["teacherSystem"]},
                    {"role": "user", "content": json.dumps(payload, ensure_ascii=False)}], full_schema,
                    time.monotonic() + settings.timeout_seconds, "teacher_batch")
                envelope = gen.read_json(output / f"{ident}.envelope.json")
                choice = envelope["choices"][0]
                if choice.get("finish_reason") != "stop":
                    raise gen.DatasetError("unfinished response")
                strict = gen.strict_json(choice["message"]["content"])
                if strict != result:
                    raise gen.DatasetError("provider parser changed target")
                Draft202012Validator(full_schema).validate(strict)
                item = gen.unpack_batch(strict, [ident])[ident]
                row["raw"] = item
                expected_slots = gen.authoring_rules(context)["slotCount"]
                if len(item["content"]["slots"]) != expected_slots:
                    raise gen.DatasetError("teacher slot count differs from authoringRules")
                row["task"], row["records"] = gen.build_records(item, context, group, artifact,
                    "reviews.json#" + ident, teacher=plan["model"], judge=None,
                    request_reference=f"{ident}.request.json")
                request = gen.read_json(output / f"{ident}.request.json")
                for record in row["records"]:
                    record["reviewVersion"] = plan["rubricVersion"]
                    record["teacherProvenance"].update(requestHash=gen.digest(request),
                        batchResponseHash=gen.digest(strict), envelopeHash=gen.digest(envelope))
                    if mode == "ab":
                        record["developmentOnly"] = True
                row.update(hardPass=True, usage=envelope.get("usage"), returnedModel=envelope.get("model"))
            except Exception as error:
                row["error"] = type(error).__name__ + ": " + clean(str(error))[:2000]
            row.update(httpStatus=state["httpStatus"], sent=state["sent"], seconds=round(time.monotonic()-started, 2))
            gen.write_json(path, row)
            print(json.dumps({"id": ident, "hardPass": row["hardPass"], "httpStatus": row["httpStatus"],
                "error": row.get("error")}), flush=True)
    blind_packet(output)


def report(output: Path) -> dict:
    plan = load_plan(output)
    rows = {r["id"]: r for r in (gen.read_json(p) for p in output.glob("*.candidate.json"))}
    reviews = gen.read_json(output / "reviews.json") if (output / "reviews.json").exists() else {}
    arms = []
    for arm in (0, 1):
        selected = [rows[e["id"]] for e in plan["entries"] if e["arm"] == arm and e["id"] in rows]
        passed = [row for row in selected if reviewed(row, reviews, plan["rubricVersion"]) == "PASS"]
        decisions = Counter(reviewed(row, reviews, plan["rubricVersion"]) for row in selected)
        levels = {level: sum(row["group"]["level"] == level for row in passed) for level in ("A1", "A2", "B1")}
        critical = sum(severity({**reviews.get(row["id"], {}).get("checks", {}),
            "severity": reviews.get(row["id"], {}).get("severity")}) == "critical" for row in selected)
        hard = sum(row["hardPass"] for row in selected)
        arms.append({"artifact": plan["artifacts"][arm]["version"],
            "planned": sum(e["arm"] == arm for e in plan["entries"]), "attempted": len(selected),
            "http200": sum(row.get("httpStatus") == 200 for row in selected), "hardPass": hard,
            "semanticPass": len(passed), "semanticDecisions": dict(decisions),
            "semanticPassByLevel": levels, "criticalFailures": critical,
            "developmentGatePassed": not plan.get("pilotOnly", False) and hard == 30
                and len(passed) >= 27 and min(levels.values()) >= 9 and critical == 0})
    chains, retained = [], []
    for index, group in enumerate(plan["chains"]):
        tasks = [rows.get(f"chain-{index+1}-t{turn}") for turn in (2, 3, 4)]
        passed = all(row and reviewed(row, reviews, plan["rubricVersion"]) == "PASS" for row in tasks)
        chains.append({"group": group["groupId"], "completePass": bool(passed),
            "turns": [{"turn": turn, "state": reviewed(row, reviews, plan["rubricVersion"]) if row else "UNSENT_DEPENDENCY_OR_PENDING"}
                for turn, row in zip((2, 3, 4), tasks)]})
        if passed:
            retained.extend(tasks)
    ledger = output / "api-reservations.jsonl"
    envelopes = [gen.read_json(path) for path in output.glob("*.envelope.json")]
    summary = {"arms": arms, "chains": chains, "chainGroupsPassed": sum(c["completePass"] for c in chains),
        "calls": len(ledger.read_text(encoding="utf-8").splitlines()) if ledger.exists() else 0, "httpCap": plan["httpCap"],
        "pilotOnly": plan.get("pilotOnly", False),
        "candidateGatePassed": arms[1]["developmentGatePassed"] and sum(c["completePass"] for c in chains) >= 5,
        "trainingReady": False, "automaticJudgeEnabled": False,
        "pendingTrainingGates": ["dataset count/coverage/splits", "RAM/hardware preflight", "checkpoint/loss-mask preflight"],
        "retainedChainTasks": len(retained), "reviewer": "AI:Codex", "humanApproved": False}
    summary["httpStatusCounts"] = dict(Counter(str(row.get("httpStatus")) for row in rows.values()))
    summary["returnedModels"] = dict(Counter(envelope.get("model", "unknown") for envelope in envelopes))
    summary["usage"] = {key: sum(envelope.get("usage", {}).get(key, 0) for envelope in envelopes)
        for key in ("prompt_tokens", "completion_tokens", "total_tokens")}
    timings = [row["seconds"] for row in rows.values() if isinstance(row.get("seconds"), (int, float))]
    summary["medianSeconds"] = statistics.median(timings) if timings else None
    summary["semanticIssueCountsNonExclusive"] = {key: sum(review.get("checks", {}).get(key) is False
        for review in reviews.values()) for key in CHECKS}
    summary["reviewHash"] = gen.digest(reviews)
    summary["planHash"] = plan["planHash"]
    number = len(list(output.glob("summary-*.json"))) + 1
    gen.write_json(output / f"summary-{number:02}.json", summary)
    if retained:
        with (output / f"chain-export-{number:02}.jsonl").open("x", encoding="utf-8", newline="\n") as handle:
            for row in retained:
                for record in row["records"]:
                    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage", choices=("prepare", "ab", "chain", "report"), required=True)
    parser.add_argument("--baseline", type=Path, default=BASELINE)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--pilot", action="store_true", help="12 paired calls, no chains or release approval")
    parser.add_argument("--rubric", choices=(VERSION, VERSION_V3), default=VERSION)
    args = parser.parse_args()
    if args.stage == "report":
        print(json.dumps(report(args.output)))
        return
    settings = settings_from_registry()
    if args.stage == "prepare":
        plan = prepare(args.output, settings, baseline=args.baseline, candidate=args.candidate,
                       pilot=args.pilot, rubric_version=args.rubric)
        print(json.dumps({"tasks": len(plan["entries"]), "quotaBefore": plan["quotaBefore"], "cap": plan["httpCap"]}))
    else:
        asyncio.run(generate(args.output, args.stage, settings))


if __name__ == "__main__":
    main()
