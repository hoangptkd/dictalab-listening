"""Complete the frozen s0 + s4-slots cell; no product changes or generation retries."""
import argparse
import collections
import concurrent.futures
import copy
import importlib.util
import json
from pathlib import Path
import re
import statistics
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ablation", Path(__file__).with_name("conversation-ablation.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
SOURCE = a.ROOT
ROOT = SOURCE.parent / "conversation-slots-interaction-20261019"
NEW = "s6-baseline-slots"
STAGES = ("s0-baseline", "s4-slots", "s5-temperature", NEW)
VERSION = "listening-ablation-v8-transfer-anchors"
OLD_VERSION = a.JUDGE_VERSION
BASE_ASSESS = a.assess
TRANSFER = a.TRANSFER + """
EVIDENCE THRESHOLD: Do not assume that a definite article ('the parcel'), 'our appointment',
'instead', cancellation, rescheduling or a familiar slot kind refers to the OLD encounter.
These are normal within a self-contained NEW encounter. Reusing a generic setting or slot
kind alone is not proof either. Reject continuation only with a demonstrable old-specific
link: an explicit reference back, or a distinctive combination of old arrangements that is
carried forward/corrected. Cite those actual matching old and new words, not a guess.
For example, cancelling the same Saturday park meeting AND referring again to its cafe
closure/blanket/sandwiches is continuation. 'The parcel is ready at the front desk' alone
does not establish the identity of the old parcel. If independence is ambiguous rather
than demonstrably violated, set transfer=true under the 'only reject demonstrable error'
policy and explain the uncertainty in historyAudit without inventing a silent change.
Do not punish the absence of an explicit reset marker. This does not excuse a wrong role,
translation or answer: evaluate those separately under their own flags.

TRANSFER AUDIT (required for turn 4): return transferAudit with decision equal to
'continuation', 'independent' or 'uncertain', oldLinks:[{oldEvidenceEn,newEvidenceEn}],
and explicitBackreferenceEn (exact candidate substring, or null). Each old link must quote
an exact substring of a HISTORY utterance and an exact substring of the CURRENT utterance
showing the SAME concrete arrangement. Do not quote facts, paraphrases, imagined words,
or repeat one link in different words. Build this audit BEFORE deciding transfer.
Operational rejection threshold: continuation requires at least TWO distinct concrete
old arrangements carried forward/corrected, or an unmistakable reference to the actual
earlier conversation. Generic pronouns, 'the parcel', 'our picnic' and 'instead' are NOT
such references. An event type shared with history is NOT two concrete arrangements.
If that threshold is not met and independence cannot be established, decision='uncertain',
transfer=true, continuesOldEncounter=false. This means NOT PROVEN WRONG, not proven good.
Never fill missing links by mentioning old details that are absent from the candidate.
"""
EXAMPLE_SLOTS = {
    "A1": [("collection_place", "Nơi lấy đồ uống"), ("collection_number", "Số lấy đồ uống")],
    "A2": [("change_reason", "Lý do đổi cách nhận vé"), ("ticket_delivery_method", "Cách nhận vé")],
    "B1": [("collection_condition", "Điều kiện nhận hành lý"), ("required_document", "Giấy tờ cần đưa")],
}


def source(name):
    path = SOURCE / name
    if path.suffix == ".jsonl":
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return json.loads(path.read_text(encoding="utf-8"))


def assess(client, context, output, purpose):
    result = BASE_ASSESS(client, context, output, purpose)
    if context.get("turnNumber") != 4:
        return result
    verdict = result["verdict"]
    audit = verdict.get("transferAudit")
    if (not isinstance(audit, dict) or audit.get("decision") not in ("continuation", "independent", "uncertain")
            or not isinstance(audit.get("oldLinks"), list)):
        raise ValueError("Missing structured transfer evidence")
    links = audit["oldLinks"]
    old_speech = [t["utteranceEn"] for t in context.get("history", [])]
    for link in links:
        if (not isinstance(link, dict) or not isinstance(link.get("oldEvidenceEn"), str)
                or not isinstance(link.get("newEvidenceEn"), str)
                or not link["oldEvidenceEn"] or not link["newEvidenceEn"]
                or not any(link["oldEvidenceEn"] in old for old in old_speech)
                or link["newEvidenceEn"] not in output["utteranceEn"]):
            raise ValueError("Transfer evidence is not an exact old/new substring")
    reference = audit.get("explicitBackreferenceEn")
    if reference is not None and (not isinstance(reference, str) or not reference or reference not in output["utteranceEn"]):
        raise ValueError("Invalid explicit transfer backreference")
    continuation = audit["decision"] == "continuation"
    distinct_old = {v["oldEvidenceEn"] for v in links}
    distinct_new = {v["newEvidenceEn"] for v in links}
    if continuation and not reference and min(len(distinct_old), len(distinct_new)) < 2:
        raise ValueError("Transfer rejected without two distinct anchors or explicit backreference")
    if verdict["transfer"] == continuation or verdict["historyAudit"]["continuesOldEncounter"] != continuation:
        raise ValueError("Transfer decision contradicts evidence audit")
    return result


def variant(old, slots, level):
    result = copy.deepcopy(old)
    result["format"] = copy.deepcopy(slots["format"])
    instruction = result["messages"][0]["content"]
    slot_instruction = slots["messages"][0]["content"]
    # The slot group needs consistent instructions/examples, but not new role/encounter rules.
    start, end = instruction.index("OUTPUT:"), instruction.index("utteranceEn is natural")
    replacement = slot_instruction[slot_instruction.index("OUTPUT IN ORDER:"):slot_instruction.index("utteranceEn is natural")]
    instruction = instruction[:start] + replacement + instruction[end:]
    instruction = instruction.replace(
        "facts=[{id,textVi,evidenceEn}]. A1: exactly 2 facts, IDs f1,f2; A2/B1: 2-3, IDs f1,f2[,f3].",
        "facts=[{textVi,evidenceEn}], in slot order. Do NOT generate IDs. A1 exactly 2 facts; A2/B1 2-3.")
    instruction = instruction.replace("LEVEL: Aim near the middle of levelProfile bounds; code checks length.",
                                      "LEVEL: Use the concrete word target and the supplied levelProfile bounds.")
    result["messages"][0]["content"] = instruction
    original_example = json.loads(result["messages"][2]["content"])
    result["messages"][2]["content"] = a.d.packed({
        "slots": [{"kind": kind, "askVi": ask} for kind, ask in EXAMPLE_SLOTS[level]],
        "utteranceEn": original_example["utteranceEn"],
        "facts": [{k: v for k, v in f.items() if k != "id"} for f in original_example["facts"]],
        **{k: original_example[k] for k in ("questionVi", "translationVi", "taskVi", "skill")}})
    target = re.search(r"Aim for about \d+ English words\.", slots["messages"][-1]["content"]).group()
    result["messages"][-1]["content"] += "\n" + target
    return result


def prepare():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name in ("manifest.json", "model.json", "baseline-contract.json"):
        a.freeze(name, source(name))
    old = {r["id"]: r for r in source("s0-baseline-requests.json")["requests"]}
    slots = {r["id"]: r for r in source("s4-slots-requests.json")["requests"]}
    roles = {r["id"]: r["judgeInput"] for r in source("s4-slots-outputs.jsonl")}
    requests = [dict(copy.deepcopy(item), judgeInput=roles[key],
                    request=variant(item["request"], slots[key]["request"], item["input"]["difficulty"]))
                for key, item in old.items()]
    a.freeze(NEW + "-requests.json", {"stage": NEW, "requests": requests})
    for stage in STAGES[:-1]:
        a.freeze(stage + "-requests.json", source(stage + "-requests.json"))
        existing = {r["key"] for r in a.read(stage + "-outputs.jsonl")}
        for row in source(stage + "-outputs.jsonl"):
            if row["key"] not in existing:
                a.append(stage + "-outputs.jsonl", dict(row, judgeInput=roles[row["id"]], cached=True))
        # For s4/s5 continuation, the effective rubric AND judge input are byte-identical.
        # Baseline must be rejudged: give it the same authored role metadata as all arms.
        if stage != "s0-baseline":
            outputs = {r["key"]: r for r in a.read(stage + "-outputs.jsonl")}
            existing_verdicts = {r["key"] for r in a.read(a.verdict_name(stage))}
            for row in source(stage + "-verdicts-" + OLD_VERSION + ".jsonl"):
                output = outputs[row["key"]]
                if output["input"]["turnNumber"] == 4 or row["key"] in existing_verdicts:
                    continue
                old_output = next(r for r in source(stage + "-outputs.jsonl") if r["key"] == row["key"])
                assert output["judgeInput"] == old_output["judgeInput"]
                a.append(a.verdict_name(stage), dict(row, judgeVersion=VERSION,
                    outputHash=a.d.digest(output), reusedUnchangedContinuationRubric=True,
                    sourceJudgeVersion=OLD_VERSION))
    a.freeze("judge-contract-" + VERSION + ".json", {"version": VERSION, "blind": a.BLIND,
        "rubric": a.RUBRIC, "continuation": a.CONTINUATION, "transfer": a.TRANSFER, "flags": a.FLAGS})
    print("Frozen 15 inputs x 3 seeds. 45 new first outputs; same authored judge context in all arms.", flush=True)


def calibration():
    name = "judge-calibration-" + VERSION + ".json"
    if (ROOT / name).exists():
        if not all(r["matchesExpected"] for r in json.loads((ROOT / name).read_text(encoding="utf-8"))["cases"]):
            raise ValueError("Saved calibration disagrees; preserve it and inspect")
        return
    outputs = {r["key"]: r for r in a.read("s5-temperature-outputs.jsonl")}
    # Assistant-reviewed transfer examples, NOT independently human-labelled gold data.
    expected = {"eval-plans-B1-t4:17": False, "eval-plans-B1-t4:97": False,
                "eval-delivery-A2-t4:17": False, "eval-plans-B1-t4:43": True,
                "eval-delivery-A2-t4:43": True}
    client = a.cloud()
    rows = []
    for key, transfer in expected.items():
        row = outputs[key]
        verdict = a.assess(client, row["judgeInput"], row["output"], "calibrate:" + key)
        rows.append({"key": key, "expectedTransfer": transfer, "judge": verdict,
                     "matchesExpected": verdict["verdict"]["transfer"] == transfer})
        print("CALIBRATION", key, rows[-1]["matchesExpected"], flush=True)
    a.freeze(name, {"version": VERSION, "humanReviewed": 0,
                   "note": "Assistant-reviewed transfer-only calibration; not independent accuracy.", "cases": rows})
    if not all(r["matchesExpected"] for r in rows):
        raise ValueError("Transfer calibration disagrees; inspect before paid evaluation")


def paired(left, right, flags):
    shared = sorted(left.keys() & right.keys())
    passed = lambda v: all(v["judge"]["verdict"][f] for f in flags)
    return {"comparable": len(shared), "leftPass": sum(passed(left[k]) for k in shared),
            "rightPass": sum(passed(right[k]) for k in shared),
            "improved": [k for k in shared if not passed(left[k]) and passed(right[k])],
            "regressed": [k for k in shared if passed(left[k]) and not passed(right[k])]}


def report():
    summaries, verdicts = [], {}
    for stage in STAGES:
        outputs = a.read(stage + "-outputs.jsonl")
        vs = a.read(a.verdict_name(stage))
        good = {v["key"]: v for v in vs if "judge" in v}
        verdicts[stage] = good
        summaries.append({"stage": stage, "outputs": len(outputs), "judged": len(good),
            "generationFailures": sum("failure" in r for r in outputs),
            "judgeFailures": sum("judgeFailure" in v for v in vs),
            "contentPass": sum(all(v["judge"]["verdict"][f] for f in a.CONTENT_FLAGS) for v in good.values()),
            "semanticPass": sum(v["judge"]["pass"] for v in good.values()),
            "errors": dict(collections.Counter(f for v in good.values() for f in a.FLAGS if not v["judge"]["verdict"][f])),
            "hardErrors": dict(collections.Counter(e for v in vs for e in v["hardErrors"])),
            "transferDecisions": dict(collections.Counter(v["judge"]["verdict"]["transferAudit"]["decision"]
                for v in good.values() if "transferAudit" in v["judge"]["verdict"])),
            "medianSeconds": statistics.median(r["seconds"] for r in outputs) if outputs else None,
            "medianPromptTokens": statistics.median(r.get("envelope", {}).get("prompt_eval_count", 0) for r in outputs) if outputs else None,
            "bySeed": {str(seed): {"judged": sum(k.endswith(":" + str(seed)) for k in good),
                "contentPass": sum(all(v["judge"]["verdict"][f] for f in a.CONTENT_FLAGS)
                    for k, v in good.items() if k.endswith(":" + str(seed)))} for seed in a.SEEDS}})
    comparisons = {left + "->" + NEW: {"content": paired(verdicts[left], verdicts[NEW], a.CONTENT_FLAGS),
        "allSemantic": paired(verdicts[left], verdicts[NEW], a.FLAGS)} for left in STAGES[:-1]}
    a.d.save("interaction-summary.json", {"judgeVersion": VERSION, "humanReviewed": 0,
        "stages": summaries, "paired": comparisons})
    print(json.dumps({"stages": summaries, "paired": comparisons}, ensure_ascii=True), flush=True)


def audit():
    expected = {r["id"] + ":" + str(seed) for r in source("manifest.json")["cases"] for seed in a.SEEDS}
    originals = {r["id"]: r for r in source("s0-baseline-requests.json")["requests"]}
    slots = {r["id"]: r for r in source("s4-slots-requests.json")["requests"]}
    for stage in STAGES:
        rows, vs = a.read(stage + "-outputs.jsonl"), a.read(a.verdict_name(stage))
        frozen_requests = {r["id"]: r for r in source(stage + "-requests.json")["requests"]} if stage != NEW else None
        frozen_outputs = {r["key"]: r for r in source(stage + "-outputs.jsonl")} if stage != NEW else None
        assert len(rows) == len(vs) == 45
        assert {r["key"] for r in rows} == {v["key"] for v in vs} == expected
        for row in rows:
            assert row["input"] == originals[row["id"]]["input"]
            if stage == NEW:
                body = variant(originals[row["id"]]["request"], slots[row["id"]]["request"], row["input"]["difficulty"])
                body["options"]["seed"] = row["seed"]
                assert row["requestWireHash"] == a.wire_hash(body)
            else:
                body = copy.deepcopy(frozen_requests[row["id"]]["request"])
                body["options"]["seed"] = row["seed"]
                assert row == dict(frozen_outputs[row["key"]], judgeInput=row["judgeInput"], cached=True)
            assert row["requestHash"] == a.d.digest(body)
            verdict = next(v for v in vs if v["key"] == row["key"])
            assert verdict["judgeVersion"] == VERSION and verdict["outputHash"] == a.d.digest(row)
            assert row["judgeInput"] == next(r for r in a.read(NEW + "-outputs.jsonl") if r["key"] == row["key"])["judgeInput"]
    print("AUDIT PASS: 180 outcomes, 45 new generations, exact paired keys/inputs/judge contexts/provenance.", flush=True)


class VariantTest(unittest.TestCase):
    def test_frozen_fifteen_requests_change_only_slot_group(self):
        old = source("s0-baseline-requests.json")["requests"]
        slots = {r["id"]: r for r in source("s4-slots-requests.json")["requests"]}
        for item in old:
            request = variant(item["request"], slots[item["id"]]["request"], item["input"]["difficulty"])
            self.assertEqual({k: v for k, v in item["request"].items() if k not in ("messages", "format")},
                             {k: v for k, v in request.items() if k not in ("messages", "format")})
            self.assertEqual(0.7, request["options"]["temperature"])
            self.assertEqual(slots[item["id"]]["request"]["format"], request["format"])
            self.assertEqual(["slots", "utteranceEn", "facts", "questionVi", "translationVi", "taskVi", "skill"],
                             list(request["format"]["properties"]))
            self.assertEqual(item["request"]["messages"][1], request["messages"][1])
            self.assertTrue(request["messages"][-1]["content"].startswith(item["request"]["messages"][-1]["content"] + "\nAim for"))
            self.assertNotIn("<turn_brief>", request["messages"][-1]["content"])
            self.assertNotIn("partnerRole", request["messages"][0]["content"])
            example = json.loads(request["messages"][2]["content"])
            original = json.loads(item["request"]["messages"][2]["content"])
            for field in ("utteranceEn", "questionVi", "translationVi", "taskVi", "skill"):
                self.assertEqual(original[field], example[field])
            self.assertEqual(2, len(example["slots"]))
        self.assertEqual(15, len(old))

    def test_paired_excludes_missing_verdicts_and_keeps_regressions(self):
        def verdict(value): return {"judge": {"verdict": {"test": value}}}
        result = paired({"a": verdict(True), "b": verdict(False), "c": verdict(True)},
                        {"a": verdict(False), "b": verdict(True)}, ("test",))
        self.assertEqual({"comparable": 2, "leftPass": 1, "rightPass": 1,
                          "improved": ["b"], "regressed": ["a"]}, result)

    def test_transfer_audit_preserves_uncertainty_and_rejects_invented_quotes(self):
        context = {"turnNumber": 4, "history": [{"utteranceEn": "Saturday at the park."}]}
        output = {"utteranceEn": "Sunday at the library."}
        result = {"verdict": {"transfer": True, "historyAudit": {"continuesOldEncounter": False},
            "transferAudit": {"decision": "uncertain", "oldLinks": [], "explicitBackreferenceEn": None}}}
        with patch.dict(globals(), BASE_ASSESS=lambda *args: copy.deepcopy(result)):
            self.assertEqual("uncertain", assess(None, context, output, "test")["verdict"]["transferAudit"]["decision"])
        result["verdict"]["transferAudit"]["oldLinks"] = [{"oldEvidenceEn": "picnic", "newEvidenceEn": "Sunday"}]
        with patch.dict(globals(), BASE_ASSESS=lambda *args: copy.deepcopy(result)):
            with self.assertRaisesRegex(ValueError, "exact old/new substring"):
                assess(None, context, output, "test")

    def test_transfer_rejection_requires_two_distinct_links_not_just_the_parcel(self):
        context = {"turnNumber": 4, "history": [{"utteranceEn": "The parcel is at reception."}]}
        output = {"utteranceEn": "The parcel is at the side gate."}
        result = {"verdict": {"transfer": False, "historyAudit": {"continuesOldEncounter": True},
            "transferAudit": {"decision": "continuation", "oldLinks": [
                {"oldEvidenceEn": "The parcel", "newEvidenceEn": "The parcel"}], "explicitBackreferenceEn": None}}}
        with patch.dict(globals(), BASE_ASSESS=lambda *args: copy.deepcopy(result)):
            with self.assertRaisesRegex(ValueError, "two distinct anchors"):
                assess(None, context, output, "test")


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("command", choices=("test", "prepare", "run", "report", "audit"))
    args = parser.parse_args()
    if args.command == "test":
        unittest.main(argv=[__file__])
        return
    a.ROOT = ROOT
    a.d.DATA = ROOT
    a.JUDGE_VERSION = VERSION
    a.TRANSFER = TRANSFER
    a.assess = assess
    prepare()
    if args.command in ("report", "audit"):
        if args.command == "audit": audit()
        report()
        return
    if args.command == "prepare": return
    # Separate capped ledger, no top-up, no second paid writer.
    if not (ROOT / "billing-start.json").exists():
        balance = a.d.Cloud().balance()
        a.d.save("billing-start.json", {"initialCredit": balance,
                 "maxCreditSpend": min(4.0, max(0, balance - 0.25)), "time": time.time()})
    calibration()
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        generation = pool.submit(a.run, NEW)
        for stage in STAGES[:-1]:
            a.judge(stage)
            report()
        # Local generation and cloud judging are separate services; exactly one writer each.
        while not generation.done():
            a.judge(NEW)
            report()
            time.sleep(5)
        generation.result()
    a.judge(NEW)
    report()
    audit()


if __name__ == "__main__":
    main()
