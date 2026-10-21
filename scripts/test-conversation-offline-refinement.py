"""Offline checks: python -X utf8 scripts/test-conversation-offline-refinement.py."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("refinement", Path(__file__).with_name("conversation-offline-refinement.py"))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class RefinementTest(unittest.TestCase):
    def context(self, turn=2):
        return {"scenario": "Gọi món ở quán", "difficulty": "A1", "turnNumber": turn,
            "levelProfile": {"minWords": 8, "maxWords": 35}, "listeningSkills": {"details": "Nắm thông tin cụ thể"},
            "practiceFocus": "", "partnerRole": "customer", "learnerRole": "server",
            "history": [{"utteranceEn": "I want tea and a sandwich.", "facts": [
                {"textVi": "Trà", "evidenceEn": "tea"}, {"textVi": "Bánh mì", "evidenceEn": "sandwich"}]}]}

    def output(self):
        return {"utteranceEn": "I will pay with cash. Please pack it in a box.",
            "questionVi": "Khách trả bằng gì và muốn đóng gói trong vật gì?",
            "translationVi": "Tôi sẽ trả bằng tiền mặt. Xin đóng gói trong hộp.", "taskVi": "Nghe yêu cầu của khách.",
            "facts": [{"id": "f1", "textVi": "Tiền mặt", "evidenceEn": "cash"},
                      {"id": "f2", "textVi": "Hộp", "evidenceEn": "a box"}]}

    def verdict(self, turn=2):
        return {f: True for f in r.a.FLAGS} | {"issues": [],
            "roleAudit": {"decision": "correct", "englishQuote": "I will pay", "questionQuote": "Khách", "reason": "Customer speaks; question describes customer."},
            "questionAudit": {"requestedMeanings": ["payment", "container"], "leakedAnswerQuotes": [], "slotMismatches": []},
            "translationAudit": {"errors": []},
            "factAudit": [{"factIndex": 1, "requestedPhraseVi": "Khách trả bằng gì", "requestedKind": "item", "evidenceSupportsAnswer": True, "unaskedTextVi": []},
                          {"factIndex": 2, "requestedPhraseVi": "muốn đóng gói trong vật gì", "requestedKind": "item", "evidenceSupportsAnswer": True, "unaskedTextVi": []}],
            "historyAudit": {"previouslyTestedMeanings": ["drink", "food"], "currentlyAskedMeanings": ["payment", "container"], "repeatedMeanings": [], "silentChanges": [], "continuesOldEncounter": turn != 4},
            "transferAudit": {"decision": "independent" if turn == 4 else "not_applicable", "oldLinks": [], "explicitBackreferenceEn": None, "backreferenceKind": "none", "reason": "Test control."}}

    def test_transfer_allowlist_excludes_story_from_all_nested_fields(self):
        context = self.context(4)
        original = dict(context, recentPractice=[{"story": "SECRET_OLD_STORY"}],
                        previouslyTestedDetails=[{"evidenceEn": "SECRET_OLD_STORY"}],
                        sessionBlueprint={"journey": "SECRET_OLD_STORY"}, weakSkills=["details"])
        result = r.state_context(original, context, "eval-cafe-A1-t4")
        self.assertEqual([], result["history"])
        self.assertNotIn("SECRET_OLD_STORY", json.dumps(result))
        self.assertEqual(["Trà", "Bánh mì"], result["independentEncounter"]["avoidAnswerValuesVi"])
        self.assertEqual(context["history"], original["history"])

    def test_state_retains_full_history_for_continuation_and_exact_sources(self):
        context = self.context()
        result = r.state_context(context, context, "eval-cafe-A1-t2")
        self.assertEqual(context["history"], result["history"])
        self.assertEqual(["ordered_drink", "ordered_food"], result["conversationState"]["alreadyAskedMeanings"])
        self.assertEqual("tea", result["conversationState"]["establishedArrangements"][0]["evidenceEn"])

    def test_translation_is_low_temperature_and_receives_roles_without_history(self):
        raw = {"utteranceEn": "Exit C, code 482.", "facts": [{"textEn": "C", "evidenceEn": "C"}], "questionVi": "Lối nào?"}
        request = r.translation_request(raw, self.context(), 43)
        self.assertEqual(.1, request["options"]["temperature"])
        data = json.loads(request["messages"][-1]["content"])
        self.assertEqual("customer", data["partnerRole"])
        self.assertNotIn("history", data)
        self.assertEqual(raw["utteranceEn"], data["utteranceEn"])

    def test_combine_only_inserts_translations_never_repairs_english(self):
        raw = {"slots": [{"kind": "exit", "askVi": "Lối ra"}, {"kind": "code", "askVi": "Mã"}],
               "utteranceEn": "Use exit C with code 482.", "questionVi": "Lối nào, mã gì?",
               "facts": [{"textEn": "exit C", "evidenceEn": "exit C"}, {"textEn": "482", "evidenceEn": "482"}]}
        original = copy.deepcopy(raw)
        result = r.combine(raw, {"translationVi": "Dùng lối ra C với mã 482.", "facts": [{"textVi": "Lối ra C"}, {"textVi": "482"}]})
        self.assertEqual(original, raw)
        self.assertEqual(raw["utteranceEn"], result["utteranceEn"])
        self.assertEqual(raw["questionVi"], result["questionVi"])
        self.assertEqual([f["evidenceEn"] for f in raw["facts"]], [f["evidenceEn"] for f in result["facts"]])
        self.assertEqual(["f1", "f2"], [f["id"] for f in r.a.normalized(result)["facts"]])
        with self.assertRaises(ValueError):
            r.combine(raw, {"translationVi": "x", "facts": []})

    def test_english_first_schema_and_example_keep_the_same_fact_field_order(self):
        context = self.context()
        original = {"model": "qwen3.5:9b", "options": {"temperature": .7},
            "format": {"properties": {"translationVi": {}, "facts": {"items": {
                "properties": {"textVi": {}, "evidenceEn": {}}, "required": ["textVi", "evidenceEn"]}}},
                "required": ["translationVi", "facts"]},
            "messages": [{"role": "system", "content": "facts=[{textVi,evidenceEn}]"},
                {"role": "user", "content": "example"}, {"role": "assistant", "content": json.dumps(self.output())},
                {"role": "user", "content": "<data>" + json.dumps(context) + "</data>"}]}
        before = copy.deepcopy(original)
        changed = r.variant(original, context, "eval-cafe-A1-t2", r.STAGES[1])
        self.assertEqual(before, original)
        self.assertEqual(.7, changed["options"]["temperature"])
        self.assertNotIn("translationVi", changed["format"]["properties"])
        self.assertEqual(["textEn", "evidenceEn"], list(changed["format"]["properties"]["facts"]["items"]["properties"]))
        example = json.loads(changed["messages"][2]["content"])
        self.assertEqual(["textEn", "evidenceEn"], list(example["facts"][0]))
        self.assertNotIn("translationVi", example)

    def test_consistent_control_and_uncertain_transfer_are_distinct(self):
        r.verify(self.verdict(), self.context(), self.output())
        v = self.verdict(4)
        v["transferAudit"]["decision"] = "uncertain"
        r.verify(v, self.context(4), self.output())
        self.assertEqual("uncertain", v["transferAudit"]["decision"])
        self.assertTrue(v["transfer"])

    def test_invented_quotes_are_unknown_not_content_failures(self):
        v = self.verdict()
        v["roleAudit"]["englishQuote"] = "INVENTED"
        with self.assertRaisesRegex(ValueError, "Inexact role"):
            r.verify(v, self.context(), self.output())

    def test_quote_transport_allows_only_anchored_capitalization_changes(self):
        self.assertTrue(r.has_quote("hôm nay", "Hôm nay có mưa."))
        self.assertTrue(r.has_quote("cash", "Cash cash"))  # Exact match needs no disambiguation.
        self.assertFalse(r.has_quote("CASH", "Cash cash"))  # Two case-insensitive matches.
        for quote in (None, "", "hom nay", "Hôm nay!", "489", "cá nóng"):
            self.assertFalse(r.has_quote(quote, "Hôm nay có mưa, mã 482, cá ấm."))

    def test_translation_source_quote_case_does_not_change_error_flag(self):
        v = self.verdict()
        v["translationAccurate"] = False
        v["issues"] = [{"type": "translationAccurate", "evidence": "cash", "explanation": "Test error."}]
        v["translationAudit"]["errors"] = [{"englishQuote": "cash", "vietnameseQuote": "tiền mặt", "reason": "Test error."}]
        out = self.output()
        out["translationVi"] = "Tiền mặt."
        r.verify(v, self.context(), out)
        self.assertFalse(v["translationAccurate"])

    def test_leakage_cannot_coexist_with_aligned_flag(self):
        v = self.verdict()
        v["questionAudit"]["leakedAnswerQuotes"] = ["Khách"]
        with self.assertRaisesRegex(ValueError, "Question flag"):
            r.verify(v, self.context(), self.output())

    def test_wrong_role_cannot_be_silently_passed(self):
        v = self.verdict()
        v["roleAudit"]["decision"] = "wrong"
        with self.assertRaisesRegex(ValueError, "Role flag"):
            r.verify(v, self.context(), self.output())

    def test_per_fact_unasked_content_cannot_be_silently_passed(self):
        v = self.verdict()
        v["factAudit"][0]["unaskedTextVi"] = ["Tiền mặt"]
        with self.assertRaisesRegex(ValueError, "Unasked fact"):
            r.verify(v, self.context(), self.output())

    def test_turn_four_never_requires_old_continuity(self):
        v = self.verdict(4)
        v["continuity"] = False
        v["issues"] = [{"type": "continuity", "evidence": "old", "explanation": "wrong rule"}]
        with self.assertRaisesRegex(ValueError, "applicability"):
            r.verify(v, self.context(4), self.output())

    def test_earlier_visit_reference_cannot_bypass_two_anchor_requirement(self):
        v = self.verdict(4)
        v["transfer"] = False
        v["issues"] = [{"type": "transfer", "evidence": "I will pay", "explanation": "claims old encounter"}]
        v["transferAudit"].update(decision="continuation", backreferenceKind="earlier_visit", explicitBackreferenceEn="I will pay")
        v["historyAudit"]["continuesOldEncounter"] = True
        with self.assertRaisesRegex(ValueError, "two same-meaning"):
            r.verify(v, self.context(4), self.output())

    def test_configure_is_idempotent(self):
        r.configure()
        first = r.a.CONTINUATION
        r.configure()
        self.assertEqual(first, r.a.CONTINUATION)

    def test_failed_repair_does_not_fall_back_to_old_favourable_content(self):
        verdict = self.verdict()
        verdict["continuity"] = False
        old = {"judge": {"verdict": verdict, "pass": False}}
        result = r.post_repair({"a": old, "b": old}, {}, {"a"})
        self.assertEqual(1, result["judged"])
        self.assertEqual(1, result["contentPass"])
        self.assertEqual(["a"], result["unknownAfterAttempt"])

    def test_literal_code_case_diagnostic_is_separate_and_not_a_general_capitalization_rule(self):
        row = {"rawOutput": {"slots": [{"kind": "wifi_password"}, {"kind": "person_name"}]}, "output": {
            "facts": [{"textVi": "Sunshine123", "evidenceEn": "sunshine123"}, {"textVi": "Lily", "evidenceEn": "lily"}]}}
        self.assertEqual([{"factIndex": 1, "evidenceEn": "sunshine123", "textVi": "Sunshine123"}], r.literal_case_errors(row))
        row["output"]["facts"][0]["textVi"] = "sunshine123"
        self.assertEqual([], r.literal_case_errors(row))

    def test_role_authority_applies_to_every_case_without_mutating_raw_main_verdict(self):
        main = self.verdict()
        role = {"decision": "wrong", "englishQuote": "I will pay", "questionQuote": "Khách", "reason": "Actor mismatch."}
        before = copy.deepcopy(main)
        final, diagnostic = r.apply_role_audit(main, role)
        self.assertEqual(before, main)
        self.assertTrue(diagnostic["flag"])
        self.assertFalse(final["rolesConsistent"])
        self.assertEqual("rolesConsistent", final["issues"][-1]["type"])

    def test_main_role_opinion_does_not_override_current_only_specialist(self):
        main = self.verdict()
        main["rolesConsistent"] = False
        main["translationAccurate"] = False
        main["issues"] = [{"type": "rolesConsistent", "evidence": "old", "explanation": "old opinion"},
                          {"type": "translationAccurate", "evidence": "x", "explanation": "other issue"}]
        final, _ = r.apply_role_audit(main, self.verdict()["roleAudit"])
        self.assertTrue(final["rolesConsistent"])
        self.assertFalse(final["translationAccurate"])
        self.assertEqual(["translationAccurate"], [i["type"] for i in final["issues"]])

    def test_explicit_audit_errors_drive_flags_without_changing_raw_or_upgrading_rejections(self):
        v = self.verdict()
        v["questionAudit"]["slotMismatches"] = ["Fact does not answer this slot"]
        v["factAudit"][0]["unaskedTextVi"] = ["Tiền mặt"]
        v["translationAccurate"] = False
        v["issues"] = [{"type": "translationAccurate", "evidence": "cash", "explanation": "Other demonstrated error."}]
        before = copy.deepcopy(v)
        projected, raw = r.apply_content_audits(v)
        self.assertEqual(before, v)
        self.assertTrue(raw["flags"]["questionFactsAligned"])
        self.assertFalse(projected["questionFactsAligned"])
        self.assertFalse(projected["atomicFacts"])
        self.assertFalse(projected["translationAccurate"])
        self.assertEqual(3, len(projected["issues"]))
        r.verify(projected, self.context(), self.output())

    def test_repair_retains_complete_last_user_input_and_brief(self):
        context = self.context()
        original = {"id": "eval-cafe-A1-t2", "input": context, "judgeInput": context,
            "request": {"options": {"temperature": .7}, "format": {"schema": "unchanged"},
                "messages": [{"role": "system", "content": "instruction"}, {"role": "user", "content":
                    "Create\n<data>" + r.a.d.packed(context) + "</data>\n<turn_brief>same encounter</turn_brief>"}]}}
        row = {"seed": 17, "englishOutput": {"utteranceEn": "I pay with cash.", "facts": [{"textEn": "cash"}]},
               "output": self.output()}
        v = {"judge": {"verdict": {"issues": [{"type": "translationAccurate", "evidence": "cash", "explanation": "fix"}]}}}
        before = copy.deepcopy(original)
        repaired = r.make_repair_request(original, row, v)
        self.assertEqual(before, original)
        messages = repaired["request"]["messages"]
        self.assertEqual(len(original["request"]["messages"]), len(messages))
        last = messages[-1]["content"]
        data = json.loads(last.split("<data>")[1].split("</data>")[0])
        self.assertEqual(context, {k: v for k, v in data.items() if k not in ("validationError", "previousInvalidPrompt")})
        self.assertEqual(row["englishOutput"], data["previousInvalidPrompt"])
        self.assertIsInstance(data["validationError"], str)
        self.assertTrue(last.endswith("<turn_brief>same encounter</turn_brief>"))
        self.assertEqual(original["request"]["format"], repaired["request"]["format"])


if __name__ == "__main__":
    unittest.main()
