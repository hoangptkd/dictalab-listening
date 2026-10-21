"""Export only synthetic benchmark fields; runtime/account/wire logs are excluded."""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "services" / "ai-generation"))
from app.redaction import redact
from report import digest


def export(source, destination):
    def read(name):
        path = source / name
        return [json.loads(s) for s in path.read_text(encoding="utf-8").splitlines() if s.strip()] if path.suffix == ".jsonl" else json.loads(path.read_text(encoding="utf-8"))

    def write(name, value):
        destination.mkdir(parents=True, exist_ok=True)
        (destination / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    manifest = read("manifest.json")
    first = read("r1-split-translation-v2-outputs.jsonl")
    contexts = {r["id"]: redact(r["judgeInput"]) for r in first}
    dataset = {"version": 1, "seeds": manifest["seeds"], "cases": [
        {"id": case["id"], "context": contexts[case["id"]]} for case in manifest["cases"]]}
    write("dataset.json", dataset)
    model = read("model.json")
    for stage in ("r1-split-translation-v2", "r2-slot-contract-v2"):
        requests = read(stage + "-requests.json")["requests"]
        verdicts = {r["key"]: r for r in read(stage + "-verdicts-listening-refinement-v14-audit-derived-flags.jsonl")}
        rows = read(stage + "-outputs.jsonl")
        records = []
        for row in rows:
            v = verdicts.get(row["key"], {})
            records.append({"id": row["id"], "seed": row["seed"], "task": redact(row.get("rawOutput")),
                            "verdict": v.get("judge", {}).get("verdict", "invalid_verdict" if v else "missing"),
                            "transport": "ok" if row.get("rawOutput") else "error", "seconds": row.get("seconds")})
        write(stage + ".json", {
            "capturedAt": datetime.fromtimestamp(rows[0]["startedAt"], timezone.utc).isoformat(),
            "metadata": {"model": "qwen3.5:9b", "modelDigest": model.get("digest", model.get("modelDigest")),
                         "promptVersion": stage, "promptHash": digest([r["request"]["messages"][0] for r in requests]),
                         "config": {"content": requests[0]["request"]["options"], "translationTemperature": 0.1,
                                    "translationNumCtx": 4096, "translationNumPredict": 800},
                         "judgeVersion": "listening-refinement-v14-audit-derived-flags", "labels": "AI; no human approval"},
            "records": records})
    return digest(dataset)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(export(args.source, args.destination))
