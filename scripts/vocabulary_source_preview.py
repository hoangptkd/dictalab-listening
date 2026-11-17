"""Validate and compare local chunk snapshots without changing the vocabulary catalog."""

import argparse
import hashlib
import json
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

MAX_BYTES = 2 * 1024 * 1024
MAX_CHUNKS = 1000
LEVELS = {"A1", "A2", "B1", "B2", "C1", "C2"}


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalized(value):
    return unicodedata.normalize("NFC", value).strip()


def require_string(value, path, limit):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError(f"{path}: expected nonempty text of at most {limit} characters")
    if any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
        raise ValueError(f"{path}: control character")
    return value


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_snapshot(path):
    raw = Path(path).read_bytes()
    if len(raw) > MAX_BYTES:
        raise ValueError(f"Snapshot exceeds {MAX_BYTES} bytes")
    root = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=no_duplicate_keys)
    if not isinstance(root, dict) or not isinstance(root.get("parts"), dict) or not root["parts"]:
        raise ValueError("parts: expected a nonempty object")
    records = {}
    topics = set()
    subtopics = set()
    for part_key, part in root["parts"].items():
        require_string(part_key, "parts key", 120)
        if not isinstance(part, dict) or not isinstance(part.get("topics"), list):
            raise ValueError(f"{part_key}.topics: expected an array")
        for topic in part["topics"]:
            if not isinstance(topic, dict):
                raise ValueError(f"{part_key}: invalid topic")
            code = require_string(topic.get("code"), f"{part_key}.topic.code", 120)
            require_string(topic.get("name_english"), f"{part_key}.{code}.name_english", 500)
            topic_key = (normalized(part_key), normalized(code))
            if topic_key in topics:
                raise ValueError(f"Duplicate topic code: {part_key}/{code}")
            topics.add(topic_key)
            if not isinstance(topic.get("sub_topics"), list):
                raise ValueError(f"{part_key}.{code}.sub_topics: expected an array")
            for subtopic in topic["sub_topics"]:
                if not isinstance(subtopic, dict):
                    raise ValueError(f"{part_key}.{code}: invalid subtopic")
                name = require_string(subtopic.get("name"), f"{part_key}.{code}.subtopic.name", 500)
                scope = (*topic_key, normalized(name))
                if scope in subtopics:
                    raise ValueError(f"Duplicate subtopic: {part_key}/{code}/{name}")
                subtopics.add(scope)
                if not isinstance(subtopic.get("chunks"), list):
                    raise ValueError(f"{part_key}.{code}.{name}.chunks: expected an array")
                for chunk in subtopic["chunks"]:
                    if not isinstance(chunk, dict):
                        raise ValueError(f"{part_key}.{code}.{name}: invalid chunk")
                    english = require_string(chunk.get("english"), "chunk.english", 200)
                    vietnamese = require_string(chunk.get("vietnamese"), "chunk.vietnamese", 1000)
                    notes = chunk.get("notes")
                    if not isinstance(notes, str) or len(notes) > 1000:
                        raise ValueError("chunk.notes: expected text of at most 1000 characters")
                    identity = [*scope, normalized(english), normalized(vietnamese)]
                    source_id = digest(json.dumps(identity, ensure_ascii=False, separators=(",", ":")))
                    if source_id in records:
                        raise ValueError(f"Duplicate chunk identity: {source_id}")
                    content_hash = digest(json.dumps([english, vietnamese, notes], ensure_ascii=False, separators=(",", ":")))
                    records[source_id] = {"id": source_id, "content_hash": content_hash,
                                          "scope": scope, "english": english,
                                          "vietnamese": vietnamese, "notes": notes}
                    if len(records) > MAX_CHUNKS:
                        raise ValueError(f"Snapshot exceeds {MAX_CHUNKS} chunks")
    return {"sha256": hashlib.sha256(raw).hexdigest(), "parts": len(root["parts"]),
            "topics": len(topics), "subtopics": len(subtopics), "records": records}


def classify_status(records, path):
    if path is None:
        return {"unclassified": len(records), "needs_review": 0, "stale": 0, "by_level": {}}
    raw = Path(path).read_bytes()
    if len(raw) > MAX_BYTES:
        raise ValueError("Classification file exceeds size limit")
    labels = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=no_duplicate_keys)
    if not isinstance(labels, dict) or len(labels) > MAX_CHUNKS:
        raise ValueError("Classifications must be an object with at most 1000 entries")
    counts = Counter()
    stale = review = 0
    for source_id, label in labels.items():
        if not isinstance(label, dict) or label.get("level") not in LEVELS:
            raise ValueError(f"Invalid classification for {source_id}")
        if not isinstance(label.get("needs_review"), bool):
            raise ValueError(f"Missing review flag for {source_id}")
        for field in ("method", "model", "version", "content_hash"):
            require_string(label.get(field), f"classification.{field}", 200)
        record = records.get(source_id)
        if record is None or record["content_hash"] != label["content_hash"]:
            stale += 1
            continue
        counts[label["level"]] += 1
        review += int(label["needs_review"])
    return {"unclassified": len(records) - sum(counts.values()), "needs_review": review,
            "stale": stale, "by_level": dict(sorted(counts.items()))}


def preview(current, baseline=None, classifications=None, baseline_classifications=None):
    records = current["records"]
    result = {"source_sha256": current["sha256"], "parts": current["parts"],
              "topics": current["topics"], "subtopics": current["subtopics"],
              "chunks": len(records), "english_with_slash": sum("/" in r["english"] for r in records.values()),
              "with_notes": sum(bool(r["notes"].strip()) for r in records.values()),
              "classification": classify_status(records, classifications)}
    if baseline is not None:
        before = baseline["records"]
        old_ids, new_ids = set(before), set(records)
        removed, added = old_ids - new_ids, new_ids - old_ids
        changed = sorted(item for item in old_ids & new_ids if before[item]["content_hash"] != records[item]["content_hash"])
        # A changed meaning or phrase may be a new sense. Suggest matches for review only.
        candidates = defaultdict(list)
        for item in added:
            row = records[item]
            candidates[(*row["scope"], normalized(row["english"]).casefold())].append(item)
        possible = []
        for item in sorted(removed):
            row = before[item]
            matches = candidates.get((*row["scope"], normalized(row["english"]).casefold()), [])
            if matches:
                possible.append({"old_id": item, "candidate_ids": sorted(matches)})
        result["diff"] = {"previous_sha256": baseline["sha256"], "added": sorted(added),
                          "removed": sorted(removed), "changed": changed,
                          "possible_matches_require_review": possible}
        if baseline_classifications is not None:
            if classifications is None:
                raise ValueError("Current classifications are required when comparing classifications")
            classify_status(before, baseline_classifications)
            old_labels = json.loads(Path(baseline_classifications).read_text(encoding="utf-8-sig"), object_pairs_hook=no_duplicate_keys)
            new_labels = json.loads(Path(classifications).read_text(encoding="utf-8-sig"), object_pairs_hook=no_duplicate_keys)
            result["diff"]["classification_changes"] = [
                {"id": item, "old_level": old_labels[item]["level"], "new_level": new_labels[item]["level"]}
                for item in sorted(old_ids & new_ids & old_labels.keys() & new_labels.keys())
                if old_labels[item]["content_hash"] == before[item]["content_hash"]
                and new_labels[item]["content_hash"] == records[item]["content_hash"]
                and old_labels[item]["level"] != new_labels[item]["level"]
            ]
    elif baseline_classifications is not None:
        raise ValueError("A baseline snapshot is required when comparing classifications")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", help="Path to a locally obtained UTF-8 source snapshot")
    parser.add_argument("--baseline", help="Previous local snapshot for read-only comparison")
    parser.add_argument("--classifications", help="Local, versioned per-item classification JSON")
    parser.add_argument("--baseline-classifications", help="Previous classifications for a read-only comparison")
    args = parser.parse_args(argv)
    try:
        current = parse_snapshot(args.snapshot)
        old = parse_snapshot(args.baseline) if args.baseline else None
        result = preview(current, old, args.classifications, args.baseline_classifications)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError) as error:
        print(f"Preview failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
