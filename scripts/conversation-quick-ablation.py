"""Leave-one-component-out, five frozen cases x two seeds. Does not change product code."""
import collections
import copy
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import unittest

spec = importlib.util.spec_from_file_location("ablation", Path(__file__).with_name("conversation-ablation.py"))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
SOURCE = a.ROOT
ROOT = SOURCE.parent / "conversation-quick-ablation-20261019"
SEEDS = (17, 43)
CASES = ("eval-cafe-A1-t2", "eval-hotel-A2-t4", "eval-directions-B1-t3",
         "eval-plans-A2-t3", "eval-delivery-B1-t2")
STAGES = ("q0-current", "q1-no-roles", "q2-no-brief", "q3-no-fewshot")
PAIR_NOTE = "\nThe first user/assistant pair is an example, not the current task. Generate for the LAST user only."


def data_message(message):
    return json.loads(message.split("<data>", 1)[1].split("</data>", 1)[0])


def drop_roles(context):
    for key in ("partnerRole", "learnerRole", "speaker"):
        context.pop(key, None)
    for turn in context.get("history", []):
        turn.pop("speaker", None)


def variant(request, original_input, name):
    result = copy.deepcopy(request)
    messages = result["messages"]
    system = messages[0]["content"]
    current = data_message(messages[-1]["content"])
    tail = messages[-1]["content"].split("</data>", 1)[1]
    if name == "q1-no-roles":
        system = system.replace("partnerRole and learnerRole: authored speaker/listener roles for the NEXT utterance.\n", "")
        system = system.replace("History may include speaker and questionVi; unknown legacy speakers must not be guessed.\n", "History may include questionVi.\n")
        start, end = system.index("ROLES:"), system.index("Both taskVi", system.index("ROLES:"))
        system = system[:start] + system[end:]
        drop_roles(current)
        example = json.loads(messages[1]["content"])
        drop_roles(example)
        messages[1]["content"] = a.d.packed(example)
        tail = re.sub(r" Speaker: .*?; listener: .*?\. Aim for", " Aim for", tail)
    elif name == "q2-no-brief":
        system = system.replace("previouslyTestedDetails: partial index of tested answers, NOT all settled arrangements.\n", "")
        system = system.replace("weakSkills: skills with non-captured facts. The final turn_brief specifies this turn's encounter rule.\n", "")
        system = system.replace("LEVEL: Use the concrete word target in turn_brief and the supplied levelProfile bounds.",
                                "LEVEL: Use the concrete word target and the supplied levelProfile bounds.")
        system = system.replace("ENCOUNTER: Follow the final turn_brief and the saved journey when supplied.",
                                "ENCOUNTER: Follow the saved journey when supplied.")
        for key in ("history", "scenarioJourney", "sessionBlueprint"):
            if key in original_input:
                current[key] = copy.deepcopy(original_input[key])
        for key in ("previouslyTestedDetails", "weakSkills"):
            current.pop(key, None)
        # Keep the s4 concrete word target, not the s2 stage-specific brief/context transformation.
        target = re.search(r"Aim for about \d+ English words\.", tail).group()
        tail = "\n" + target
    elif name == "q3-no-fewshot":
        messages[:] = [messages[0], messages[-1]]
        system = system.replace(PAIR_NOTE, "")
        system = system.replace("Examples demonstrate format; do not copy their content.\n", "")
    else:
        raise ValueError(name)
    messages[0]["content"] = system
    if name != "q3-no-fewshot":
        prefix = messages[-1]["content"].split("<data>", 1)[0]
        messages[-1]["content"] = prefix + "<data>" + a.d.packed(current) + "</data>" + tail
    return result


def prepare():
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((SOURCE / "manifest.json").read_text(encoding="utf-8"))
    a.freeze("manifest.json", dict(manifest, seeds=SEEDS,
             cases=[r for r in manifest["cases"] if r["id"] in CASES],
             note="Quick development ablation; 10 cached baseline outputs, 30 new first outputs; not a held-out test."))
    for name in ("model.json", "baseline-contract.json", "judge-calibration-" + a.JUDGE_VERSION + ".json"):
        a.freeze(name, json.loads((SOURCE / name).read_text(encoding="utf-8")))
    source_requests = json.loads((SOURCE / "s5-temperature-requests.json").read_text(encoding="utf-8"))
    source_outputs = {r["key"]: r for r in read_source("s5-temperature-outputs.jsonl")}
    source_verdicts = {r["key"]: r for r in read_source(a.verdict_name("s5-temperature"))}
    for stage in STAGES:
        requests = []
        for item in source_requests["requests"]:
            if item["id"] not in CASES:
                continue
            row = copy.deepcopy(item)
            row["judgeInput"] = source_outputs[item["id"] + ":17"]["judgeInput"]
            if stage != STAGES[0]:
                row["request"] = variant(row["request"], row["input"], stage)
            requests.append(row)
        a.freeze(stage + "-requests.json", {"stage": stage, "requests": requests})
    for seed in SEEDS:
        for case in CASES:
            key = case + ":" + str(seed)
            if key in {r["key"] for r in a.read(STAGES[0] + "-outputs.jsonl")}:
                continue
            row = dict(source_outputs[key], stage=STAGES[0], sourceStage="s5-temperature", cached=True)
            a.append(STAGES[0] + "-outputs.jsonl", row)
            verdict = dict(source_verdicts[key], stage=STAGES[0], outputHash=a.d.digest(row), cached=True)
            a.append(a.verdict_name(STAGES[0]), verdict)
    print("Frozen 5 cases x 2 seeds; cached baseline=10; new generations=30", flush=True)


def read_source(name):
    return [json.loads(line) for line in (SOURCE / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def report():
    summaries = []
    baseline = {r["key"]: r["judge"]["pass"] for r in a.read(a.verdict_name(STAGES[0])) if "judge" in r}
    for stage in STAGES:
        rows = a.read(stage + "-outputs.jsonl")
        verdicts = a.read(a.verdict_name(stage))
        judged = [v for v in verdicts if "judge" in v]
        current = {v["key"]: v["judge"]["pass"] for v in judged}
        shared = baseline.keys() & current.keys()
        summaries.append({"stage": stage, "outputs": len(rows), "generationFailures": sum("failure" in r for r in rows),
            "judged": len(judged), "judgeFailures": sum("judgeFailure" in v for v in verdicts),
            "contentPass": sum(all(v["judge"]["verdict"][f] for f in a.CONTENT_FLAGS) for v in judged),
            "semanticPass": sum(current.values()),
            "semanticErrors": dict(collections.Counter(f for v in judged for f in a.FLAGS if not v["judge"]["verdict"][f])),
            "hardErrors": dict(collections.Counter(e for v in verdicts for e in v["hardErrors"])),
            "errorCases": {f: sorted(v["key"] for v in judged if not v["judge"]["verdict"][f]) for f in a.FLAGS},
            "bySeed": {str(seed): {"judged": sum(v["key"].endswith(":" + str(seed)) for v in judged),
                "contentPass": sum(all(v["judge"]["verdict"][f] for f in a.CONTENT_FLAGS)
                                   for v in judged if v["key"].endswith(":" + str(seed))),
                "semanticPass": sum(v["judge"]["pass"] for v in judged if v["key"].endswith(":" + str(seed)))}
                for seed in SEEDS},
            "pairedToCurrent": {"comparable": len(shared), "improved": sorted(k for k in shared if current[k] and not baseline[k]),
                                "regressed": sorted(k for k in shared if baseline[k] and not current[k])}})
    a.d.save("quick-summary.json", {"cases": CASES, "seeds": SEEDS, "humanReviewed": 0,
                                  "judgeVersion": a.JUDGE_VERSION, "stages": summaries})
    print(json.dumps(summaries, ensure_ascii=True, indent=2), flush=True)


def audit():
    keys = {case + ":" + str(seed) for case in CASES for seed in SEEDS}
    baseline = {r["id"]: r for r in json.loads((ROOT / (STAGES[0] + "-requests.json")).read_text(encoding="utf-8"))["requests"]}
    for stage in STAGES:
        requests = {r["id"]: r for r in json.loads((ROOT / (stage + "-requests.json")).read_text(encoding="utf-8"))["requests"]}
        rows = a.read(stage + "-outputs.jsonl")
        verdicts = a.read(a.verdict_name(stage))
        assert len(rows) == len(verdicts) == 10
        assert {r["key"] for r in rows} == {v["key"] for v in verdicts} == keys
        assert {r["id"] for r in rows} == set(CASES)
        for row in rows:
            item = requests[row["id"]]
            original = baseline[row["id"]]
            assert row["input"] == item["input"] == original["input"]
            assert row["judgeInput"] == original["judgeInput"]
            expected = original["request"] if stage == STAGES[0] else variant(original["request"], original["input"], stage)
            assert a.wire_hash(item["request"]) == a.wire_hash(expected)
            body = copy.deepcopy(item["request"])
            body["options"]["seed"] = row["seed"]
            assert a.d.digest(body) == row["requestHash"]
            if not row.get("cached"):
                assert a.wire_hash(body) == row["requestWireHash"]
            verdict = next(v for v in verdicts if v["key"] == row["key"])
            assert verdict["outputHash"] == a.d.digest(row)
            assert verdict["judgeVersion"] == a.JUDGE_VERSION
    print("Verified 40 outcomes: 10 cached + 30 new; same five inputs, two seeds, judge context; request hashes match.")


class VariantTest(unittest.TestCase):
    def test_only_one_component_changes_and_no_product_settings_change(self):
        source = json.loads((SOURCE / "s5-temperature-requests.json").read_text(encoding="utf-8"))
        for item in source["requests"]:
            original = item["request"]
            for stage in STAGES[1:]:
                result = variant(original, item["input"], stage)
                self.assertEqual({k:v for k,v in original.items() if k != "messages"},
                                 {k:v for k,v in result.items() if k != "messages"})
                current = data_message(result["messages"][-1]["content"])
                if stage == "q1-no-roles":
                    self.assertNotIn("partnerRole", current)
                    self.assertNotIn("Speaker:", result["messages"][-1]["content"])
                    self.assertNotIn("ROLES:", result["messages"][0]["content"])
                    self.assertIn("<turn_brief>", result["messages"][-1]["content"])
                    self.assertEqual(4, len(result["messages"]))
                elif stage == "q2-no-brief":
                    self.assertEqual(item["input"]["history"], current["history"])
                    self.assertNotIn("<turn_brief>", result["messages"][-1]["content"])
                    self.assertIn("Aim for about", result["messages"][-1]["content"])
                    self.assertIn("partnerRole", current)
                    self.assertEqual(4, len(result["messages"]))
                else:
                    self.assertEqual(2, len(result["messages"]))
                    self.assertEqual(original["messages"][-1]["content"], result["messages"][-1]["content"])
        self.assertEqual(5, len(CASES))
        self.assertEqual(2, len(SEEDS))


def main():
    if sys.argv[1:] == ["test"]:
        unittest.main(argv=[sys.argv[0]])
        return
    a.ROOT = ROOT
    a.SEEDS = SEEDS
    a.d.DATA = ROOT
    if sys.argv[1:] == ["audit"]:
        audit()
        report()
        return
    prepare()
    # A separate capped ledger. Reuse the judge, never auto-top-up or reset prior spending.
    client = a.d.Cloud()
    if not (ROOT / "billing-start.json").exists():
        balance = client.balance()
        a.d.save("billing-start.json", {"initialCredit": balance,
                 "maxCreditSpend": min(2.0, max(0, balance - 0.25)), "time": time.time()})
    for stage in STAGES[1:]:
        a.run(stage)
        a.judge(stage)
        report()
    report()
    audit()


if __name__ == "__main__":
    main()
