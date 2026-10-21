"""Offline regression tests; python scripts/test-conversation-ablation.py."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ablation", Path(__file__).with_name("conversation-ablation.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class AblationTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root_patch = patch.object(a, "ROOT", Path(self.folder.name))
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.folder.cleanup()

    @staticmethod
    def valid_verdict():
        return {k: True for k in a.FLAGS} | {"issues": [], "historyAudit": {
            "previouslyTestedMeanings": ["Old meaning"], "currentlyAskedMeanings": ["Test meaning"],
            "repeatedMeanings": [], "silentChanges": [], "continuesOldEncounter": False}}

    def test_freeze_is_immutable_but_accepts_json_tuple_roundtrip(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(a, "ROOT", Path(folder)):
            a.freeze("test.json", {"seeds": a.SEEDS})
            a.freeze("test.json", {"seeds": a.SEEDS})
            with self.assertRaises(ValueError):
                a.freeze("test.json", {"seeds": [1]})

    def test_wire_hash_preserves_schema_order(self):
        first = {"properties": {"slots": {}, "utteranceEn": {}}}
        second = {"properties": {"utteranceEn": {}, "slots": {}}}
        self.assertEqual(a.d.digest(first), a.d.digest(second))
        self.assertNotEqual(a.wire_hash(first), a.wire_hash(second))

    def test_slot_ids_are_assigned_without_repairing_content(self):
        raw = {"slots": [{"kind": "place", "askVi": "Nơi nhận"}, {"kind": "code", "askVi": "Mã"}],
               "facts": [{"textVi": "Quầy", "evidenceEn": "desk"}, {"textVi": "C", "evidenceEn": "C"}]}
        original = copy.deepcopy(raw)
        result = a.normalized(raw)
        self.assertEqual(["f1", "f2"], [f["id"] for f in result["facts"]])
        self.assertNotIn("slots", result)
        self.assertEqual(original, raw)
        for original_fact, fact in zip(raw["facts"], result["facts"]):
            self.assertEqual(original_fact["textVi"], fact["textVi"])
            self.assertEqual(original_fact["evidenceEn"], fact["evidenceEn"])

    def test_slot_count_mismatch_is_not_silently_fixed(self):
        with self.assertRaises(ValueError):
            a.normalized({"slots": [{}], "facts": [{}, {}]})

    def test_blind_answer_cannot_see_candidate_facts_translation_or_history(self):
        calls = []
        class Client:
            def call(self, model, messages, purpose, max_tokens):
                calls.append(messages)
                return ({"answers": []} if len(calls) == 1 else
                        AblationTest.valid_verdict())
        output = {"utteranceEn": "Listen.", "questionVi": "Hỏi?", "translationVi": "SECRET_TRANSLATION",
                  "facts": [{"textVi": "SECRET_ANSWER"}]}
        verdict = a.assess(Client(), {"history": "SECRET_HISTORY"}, output, "unit")
        blind_data = json.loads(calls[0][1]["content"])
        self.assertEqual({"utteranceEn", "questionVi"}, set(blind_data))
        self.assertTrue(verdict["pass"])

    def test_judge_requires_boolean_flags_and_matching_evidence(self):
        class Client:
            def __init__(self, verdict): self.verdict = verdict
            def call(self, model, messages, purpose, max_tokens):
                return {"answers": []} if purpose.endswith(":blind") else self.verdict
        output = {"utteranceEn": "Test", "questionVi": "Test"}
        for verdict in ({"issues": []}, {k: "true" for k in a.FLAGS} | {"issues": []},
                        {k: k != "transfer" for k in a.FLAGS} | {"issues": []}):
            with self.assertRaises(ValueError):
                a.assess(Client(verdict), {}, output, "unit")

    def test_append_roundtrip_keeps_unicode(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(a, "ROOT", Path(folder)):
            a.append("test.jsonl", {"text": "Câu hỏi tiếng Việt"})
            self.assertEqual([{"text": "Câu hỏi tiếng Việt"}], a.read("test.jsonl"))

    def test_reader_ignores_inflight_tail_but_resume_cannot_append_to_corrupt_tail(self):
        a.append("test.jsonl", {"complete": True})
        with (a.ROOT / "test.jsonl").open("ab") as handle:
            handle.write(b'{"partial":')
        self.assertEqual([{"complete": True}], a.read("test.jsonl"))
        with self.assertRaisesRegex(ValueError, "Incomplete JSONL tail"):
            a.append("test.jsonl", {"new": True})

    def test_history_audit_cannot_silently_disagree_with_continuity(self):
        verdict = self.valid_verdict()
        verdict["historyAudit"]["repeatedMeanings"] = ["Old meaning"]
        class Client:
            def call(self, model, messages, purpose, max_tokens):
                return {"answers": []} if purpose.endswith(":blind") else verdict
        with self.assertRaisesRegex(ValueError, "contradicts continuity"):
            a.assess(Client(), {"turnNumber": 2}, {"utteranceEn": "Test", "questionVi": "Test"}, "unit")

    def test_report_separates_length_structure_and_generation_and_lists_every_error(self):
        stage = "s0-baseline"
        a.freeze(stage + "-requests.json", {})
        for index, errors in enumerate((["length:word-count"], ["schema:fact-textVi"], ["generation:failure"])):
            key = f"case-{index}:17"
            a.append(stage + "-outputs.jsonl", {"key": key, "seed": 17, "seconds": 1,
                **({"failure": "timeout"} if index == 2 else {"output": {}})})
            a.append(a.verdict_name(stage), {"key": key, "hardErrors": errors})
        with patch.object(a.d, "DATA", a.ROOT), patch("builtins.print"):
            a.report()
        result = json.loads((a.ROOT / "summary.json").read_text(encoding="utf-8"))["stages"][0]
        self.assertEqual(1, result["schemaGroundingPass"])
        self.assertEqual(0, result["hardPass"])
        self.assertEqual(1, result["generationFailures"])
        self.assertIn("schema:fact-textVi", (a.ROOT / "progress.md").read_text(encoding="utf-8"))

    def test_report_does_not_call_repeated_meaning_a_wrong_factual_answer(self):
        stage = "s0-baseline"
        a.freeze(stage + "-requests.json", {})
        verdict = self.valid_verdict()
        verdict["continuity"] = False
        verdict["issues"] = [{"type": "continuity", "evidence": "same location", "explanation": "retests old meaning"}]
        a.append(stage + "-outputs.jsonl", {"key": "case:17", "seed": 17, "seconds": 1, "output": {}})
        a.append(a.verdict_name(stage), {"key": "case:17", "hardErrors": [],
            "judge": {"pass": False, "verdict": verdict}})
        with patch.object(a.d, "DATA", a.ROOT), patch("builtins.print"):
            a.report()
        result = json.loads((a.ROOT / "summary.json").read_text(encoding="utf-8"))["stages"][0]
        self.assertEqual(1, result["contentPass"])
        self.assertEqual(0, result["learningPass"])
        self.assertEqual(0, result["semanticPass"])
        self.assertEqual(1, result["bySeed"]["17"]["contentPass"])

    def test_report_includes_future_stages_but_not_unrun_drafts(self):
        a.freeze("draft-requests.json", {})
        a.freeze("s6-next-change-requests.json", {})
        a.append("s6-next-change-outputs.jsonl", {"key": "case:17", "seed": 17, "seconds": 1, "output": {}})
        with patch.object(a.d, "DATA", a.ROOT), patch("builtins.print"):
            a.report()
        stages = json.loads((a.ROOT / "summary.json").read_text(encoding="utf-8"))["stages"]
        self.assertEqual(["s6-next-change"], [s["stage"] for s in stages])

    def test_collector_accepts_five_cases_two_seeds_and_fixed_judge_context(self):
        stage = "quick"
        cases = [{"id": "case-" + str(i), "input": {"turnNumber": 2}} for i in range(5)]
        a.freeze("manifest.json", {"cases": cases, "seeds": [17, 43]})
        a.freeze("model.json", {"digest": "frozen"})
        requests = [dict(r, judgeInput={"turnNumber": 2, "partnerRole": "fixed judge role"},
                        request={"options": {}, "messages": [{"content": "<data>{}</data>"}]}) for r in cases]
        a.freeze(stage + "-requests.json", {"requests": requests})
        def response(request, timeout):
            payload = ({"models": [{"name": "qwen3.5:9b", "digest": "frozen"}]} if isinstance(request, str)
                       else {"done": True, "done_reason": "stop", "message": {"content": "{}"}})
            return io.BytesIO(json.dumps(payload).encode("utf-8"))
        with patch.object(a, "SEEDS", (17, 43)), patch.object(a.urllib.request, "urlopen", side_effect=response), patch("builtins.print"):
            a.run(stage)
            a.run(stage)  # Resume cannot append duplicate outcomes.
        rows = a.read(stage + "-outputs.jsonl")
        self.assertEqual(10, len(rows))
        self.assertEqual(10, len({r["key"] for r in rows}))
        self.assertTrue(all(r["judgeInput"]["partnerRole"] == "fixed judge role" for r in rows))
        self.assertTrue(all("requestWireHash" in r for r in rows))


if __name__ == "__main__":
    unittest.main()
