"""Run with python scripts/test-conversation-distill.py; no model/API calls."""
import copy
import contextlib
import csv
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("distill", Path(__file__).with_name("conversation-distill.py"))
distill = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distill)


class OfflineFilterTest(unittest.TestCase):
    def setUp(self):
        self.context = {"difficulty": "A1", "listeningSkills": {"details": "Nắm thông tin cụ thể"},
                        "levelProfile": {"minWords": 8, "maxWords": 35}, "history": []}
        self.output = distill.task("Please bring your ticket. Wait at the south door for me.",
                                  "Xin mang vé. Đợi tôi ở cửa phía nam.", "Mang gì và đợi ở đâu?",
                                  [("your ticket", "Vé"), ("the south door", "Cửa phía nam")])
        self.contract = {"levels": {"A1": {"exampleOutput": {"utteranceEn": "Example not to copy"}}}}

    def check(self, output=None):
        return distill.check_output(self.context, output or self.output, self.contract)

    def test_valid(self):
        self.assertEqual([], self.check())

    def test_extra_field(self):
        self.output["extra"] = True
        self.assertIn("schema:top-level-fields", self.check())

    def test_non_substring(self):
        self.output["facts"][0]["evidenceEn"] = "your passport"
        self.assertIn("grounding:evidence-not-substring", self.check())

    def test_fact_id(self):
        self.output["facts"][1]["id"] = "f1"
        self.assertIn("schema:fact-ids", self.check())

    def test_a1_fact_count(self):
        self.output["facts"].append(copy.deepcopy(self.output["facts"][0]))
        self.assertIn("schema:fact-count", self.check())

    def test_skill(self):
        self.output["skill"] = "invalid"
        self.assertIn("schema:skill", self.check())

    def test_length(self):
        self.context["levelProfile"]["maxWords"] = 8
        self.assertIn("length:word-count", self.check())

    def test_history_copy(self):
        self.context["history"] = [{"utteranceEn": self.output["utteranceEn"]}]
        self.assertIn("duplicate:history-or-example", self.check())

    def test_null_and_bad_fact(self):
        self.assertEqual(["schema:top-level-fields"], distill.check_output(self.context, None, self.contract))
        self.output["facts"][0] = None
        self.assertIn("schema:fact-fields", self.check())

    def test_fixtures_grounded(self):
        for tasks in distill.FOLLOWUPS.values():
            for task in tasks:
                self.assertEqual([], distill.check_output(self.context, task, self.contract))

    def test_jsonl_unicode_roundtrip(self):
        original = distill.DATA
        with tempfile.TemporaryDirectory() as folder:
            try:
                distill.DATA = Path(folder)
                distill.append("test.jsonl", self.output)
                self.assertEqual([self.output], distill.load("test.jsonl"))
            finally:
                distill.DATA = original

    def test_hash_ignores_object_order(self):
        self.assertEqual(distill.digest({"a": 1, "b": 2}), distill.digest({"b": 2, "a": 1}))

    def test_hard_filter_does_not_claim_semantic_correctness(self):
        self.output["translationVi"] = "Bản dịch sai nhưng đúng kiểu dữ liệu."
        self.assertEqual([], self.check())  # A semantic judge/human review is still mandatory.

    def test_judge_fails_closed_on_missing_or_nonboolean_flags(self):
        cloud = Mock()
        cloud.call.return_value = {"issues": []}
        with self.assertRaises(RuntimeError):
            distill.judge(cloud, self.context, self.output, "unit-only")
        cloud.call.return_value = {k: "true" for k in distill.JUDGE_FIELDS} | {"issues": []}
        with self.assertRaises(RuntimeError):
            distill.judge(cloud, self.context, self.output, "unit-only")

    def test_judge_issues_prevent_acceptance(self):
        cloud = Mock()
        cloud.call.return_value = {k: True for k in distill.JUDGE_FIELDS} | {"issues": ["Specific issue"]}
        self.assertFalse(distill.judge(cloud, self.context, self.output, "unit-only")["pass"])

    def test_current_refilter_and_assistant_rejection_preserve_raw_verdict(self):
        original = distill.DATA
        with tempfile.TemporaryDirectory() as folder:
            try:
                distill.DATA = Path(folder)
                row = {"id": "case-t2", "inputHash": distill.digest(self.context),
                       "output": self.output, "eligible": False,
                       "judge": {"judgeVersion": "old", "pass": False}}
                distill.append("teacher-candidates.jsonl", row)
                provenance = {"id": row["id"], "inputHash": row["inputHash"],
                              "outputHash": distill.digest(self.output)}
                distill.append("teacher-refilter.jsonl", provenance | {
                    "judgeVersion": distill.JUDGE_VERSION, "eligible": True, "hardErrors": [],
                    "judge": {"judgeVersion": distill.JUDGE_VERSION, "pass": True}})
                self.assertTrue(distill.teacher_rows()[0]["eligible"])
                distill.append("assistant-audit.jsonl", provenance | {
                    "decision": "reject", "reason": "A demonstrable semantic error"})
                self.assertFalse(distill.teacher_rows()[0]["eligible"])
                self.assertEqual([row], distill.load("teacher-candidates.jsonl"))
            finally:
                distill.DATA = original

    def test_assistant_review_cannot_apply_to_a_different_output(self):
        original = distill.DATA
        with tempfile.TemporaryDirectory() as folder:
            try:
                distill.DATA = Path(folder)
                distill.append("teacher-candidates.jsonl", {
                    "id": "case-t2", "inputHash": distill.digest(self.context), "output": self.output})
                distill.append("assistant-audit.jsonl", {
                    "id": "case-t2", "inputHash": distill.digest(self.context),
                    "outputHash": "wrong-output", "decision": "reject", "reason": "Test"})
                with self.assertRaisesRegex(RuntimeError, "provenance mismatch"):
                    distill.teacher_rows()
            finally:
                distill.DATA = original

    def test_export_excludes_rejected_history_and_preserves_review_notes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            contract = self.contract | {"instruction": "Test only", "promptVersion": "test",
                                       "catalogue": [{"scenarioId": "demo"}]}
            contract["levels"]["A1"]["exampleInput"] = {}
            common = {"group": "demo/branch/A1", "scenarioId": "demo", "level": "A1", "focus": 1,
                      "longHistory": False, "teacher": "test-teacher", "input": self.context,
                      "inputHash": distill.digest(self.context), "output": self.output,
                      "hardErrors": [], "eligible": True}
            rows = [common | {"id": "bad-t2", "turn": 2, "eligible": False},
                    common | {"id": "bad-t3", "turn": 3},
                    common | {"id": "good-t2", "turn": 2}]
            (root / "human-review-decisions.csv").write_text(
                "id,decision,reviewer,notes,extra\nold,pending,,Keep this,Keep extra\n", encoding="utf-8")
            with patch.object(distill, "DATA", root), patch.object(distill, "contract", return_value=contract), \
                 patch.object(distill, "teacher_rows", return_value=rows), \
                 patch.object(distill.Path, "home", return_value=root / "backup-home"), \
                 contextlib.redirect_stdout(io.StringIO()):
                distill.finalize()
                exported = sum((distill.load(split+".provisional.jsonl")
                                for split in ("train", "validation", "test")), [])
                self.assertEqual(["good-t2"], [r["id"] for r in exported])
                self.assertEqual("last-assistant-only", exported[0]["lossMaskPolicy"])
                summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
                self.assertFalse(summary["trainingReady"])
                self.assertEqual(1, summary["retainedCoverageCells"])
                self.assertEqual(0, summary["humanReviewed"])
            with (root / "human-review-decisions.csv").open(encoding="utf-8-sig", newline="") as handle:
                saved = list(csv.DictReader(handle))
            self.assertEqual("Keep this", saved[0]["notes"])
            self.assertEqual("Keep extra", saved[0]["extra"])
            self.assertTrue((root / "backup-home/Documents/DictaLab-research-backup/conversation-distillation/summary.json").exists())


if __name__ == "__main__":
    unittest.main()
