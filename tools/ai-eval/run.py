"""Replay redacted captured outputs; never calls a paid judge or changes production."""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from metrics import score
from report import build_report, compare


def replay(dataset, capture):
    contexts = {c["id"]: c["context"] for c in dataset["cases"]}
    rows = []
    for record in capture["records"]:
        context = contexts[record["id"]]
        rows.append({"key": f'{record["id"]}:{record["seed"]}', "seconds": record.get("seconds"),
                     "metrics": score(record.get("task"), context, record.get("verdict"),
                                      record.get("transport", "ok"))})
    return build_report(dataset, rows, capture["metadata"], capture.get("capturedAt"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--reviews", type=Path)
    args = parser.parse_args()
    load = lambda path: json.loads(path.read_text(encoding="utf-8"))
    result = replay(load(args.dataset), load(args.capture))
    if args.reviews:
        reviews = load(args.reviews)
        if reviews["datasetHash"] != result["datasetHash"]:
            raise ValueError("review dataset hash mismatch")
        samples = reviews["stages"][result["metadata"]["promptVersion"]]
        for sample in result["reviewSample"]:
            reviewed = samples.get(sample["key"])
            if reviewed:
                sample.update(reviewed)
                sample["reviewer"] = "ai"
                sample["reviewReference"] = args.reviews.as_posix() + "#" + result["metadata"]["promptVersion"] + "/" + sample["key"]
    if args.baseline:
        result["gate"] = compare(load(args.baseline), result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("datasetHash", "coverage", "verdict_status")}))
    if args.baseline and not result["gate"]["releaseAllowed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
