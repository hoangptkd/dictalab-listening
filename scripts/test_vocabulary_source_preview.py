import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("preview", Path(__file__).with_name("vocabulary_source_preview.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


def snapshot(chunks):
    return {"parts": {"part1": {"topics": [{"code": "1", "name_english": "Greetings",
            "sub_topics": [{"name": "First meeting", "chunks": chunks}]}]}}}


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def write(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def test_identity_ignores_order_and_keeps_distinct_senses(self):
        chunks = [{"english": "bank", "vietnamese": "ngân hàng", "notes": ""},
                  {"english": "bank", "vietnamese": "bờ sông", "notes": ""}]
        first = preview.parse_snapshot(self.write("first.json", snapshot(chunks)))
        second = preview.parse_snapshot(self.write("second.json", snapshot(list(reversed(chunks)))))
        self.assertEqual(set(first["records"]), set(second["records"]))
        self.assertEqual(2, len(first["records"]))
        self.assertEqual([], preview.preview(second, first)["diff"]["added"])

    def test_changes_and_possible_matches_are_reported_without_matching_automatically(self):
        old = [{"english": "bank", "vietnamese": "ngân hàng", "notes": "formal"},
               {"english": "hello", "vietnamese": "xin chào", "notes": ""}]
        new = [{"english": "bank", "vietnamese": "bờ sông", "notes": ""},
               {"english": "hello", "vietnamese": "xin chào", "notes": "friendly"}]
        baseline = preview.parse_snapshot(self.write("baseline.json", snapshot(old)))
        current = preview.parse_snapshot(self.write("current.json", snapshot(new)))
        diff = preview.preview(current, baseline)["diff"]
        self.assertEqual((1, 1, 1), (len(diff["added"]), len(diff["removed"]), len(diff["changed"])))
        self.assertEqual(diff["removed"][0], diff["possible_matches_require_review"][0]["old_id"])
        self.assertEqual(diff["added"], diff["possible_matches_require_review"][0]["candidate_ids"])

    def test_classification_requires_provenance_and_marks_outdated_results(self):
        chunks = [{"english": "Hello / Hi", "vietnamese": "Xin chào", "notes": "informal"}]
        source = preview.parse_snapshot(self.write("source.json", snapshot(chunks)))
        record = next(iter(source["records"].values()))
        label = {"level": "A1", "needs_review": True, "method": "AI assistance", "model": "model",
                 "version": "v1", "content_hash": record["content_hash"]}
        file = self.write("labels.json", {record["id"]: label})
        self.assertEqual({"unclassified": 0, "needs_review": 1, "stale": 0, "by_level": {"A1": 1}},
                         preview.classify_status(source["records"], file))
        label["content_hash"] = "outdated"
        self.write("labels.json", {record["id"]: label})
        self.assertEqual({"unclassified": 1, "needs_review": 0, "stale": 1, "by_level": {}},
                         preview.classify_status(source["records"], file))
        label.pop("model")
        self.write("labels.json", {record["id"]: label})
        with self.assertRaises(ValueError):
            preview.classify_status(source["records"], file)

    def test_invalid_or_duplicate_input_fails_without_modifying_files(self):
        chunk = {"english": "hello", "vietnamese": "xin chào", "notes": ""}
        path = self.write("duplicate.json", snapshot([chunk, chunk]))
        original = path.read_bytes()
        with self.assertRaisesRegex(ValueError, "Duplicate chunk identity"):
            preview.parse_snapshot(path)
        self.assertEqual(original, path.read_bytes())
        with self.assertRaises(ValueError):
            preview.parse_snapshot(self.write("missing.json", snapshot([{"english": "hello"}])))
        invalid = self.root / "bad.json"
        invalid.write_text('{"parts":{},"parts":{}}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
            preview.parse_snapshot(invalid)
        invalid.write_bytes(b"\xff")
        with self.assertRaises(UnicodeError):
            preview.parse_snapshot(invalid)

    def test_level_changes_are_reported_only_for_current_labels(self):
        chunk = {"english": "hello", "vietnamese": "xin chào", "notes": ""}
        current = preview.parse_snapshot(self.write("current.json", snapshot([chunk])))
        record = next(iter(current["records"].values()))
        label = {"level": "A1", "needs_review": False, "method": "AI assistance", "model": "model",
                 "version": "v1", "content_hash": record["content_hash"]}
        old = self.write("old-labels.json", {record["id"]: label})
        label["level"] = "A2"
        new = self.write("new-labels.json", {record["id"]: label})
        changes = preview.preview(current, current, new, old)["diff"]["classification_changes"]
        self.assertEqual([{"id": record["id"], "old_level": "A1", "new_level": "A2"}], changes)
        label["content_hash"] = "outdated"
        self.write("new-labels.json", {record["id"]: label})
        self.assertEqual([], preview.preview(current, current, new, old)["diff"]["classification_changes"])

    def test_checked_in_snapshot_has_complete_current_classifications(self):
        folder = Path(__file__).resolve().parents[1] / "src/main/resources/vocabulary/daily-conversation"
        source = preview.parse_snapshot(folder / "source.json")
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["sha256"], source["sha256"])
        self.assertEqual((719, 31, 96), (len(source["records"]), source["topics"], source["subtopics"]))
        labels = preview.classify_status(source["records"], folder / "cefr-reference-v1.json")
        self.assertEqual((0, 0), (labels["unclassified"], labels["stale"]))
        self.assertEqual(719, sum(labels["by_level"].values()))


if __name__ == "__main__":
    unittest.main()
