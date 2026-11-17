"""Versioned local candidate: bounded metadata selection, with no extra model calls."""
from copy import deepcopy
from functools import cache
import hashlib
import json
from pathlib import Path
import re
from types import ModuleType

ROOT = Path(__file__).parent
SELECTOR_VERSION = "scenario-example-selector.v1"
STAGES = ("EXTRACT", "GENERATE", "QUESTIONS", "REVIEW_QUESTIONS", "TRANSLATE", "REVIEW",
          "GRADE", "REPAIR_QUESTIONS", "REPAIR_TRANSLATIONS")
SCOPES = {"PersonalizedExtraction": "/", "Dialogue": "/", "SparseActivityDraft": "/activities/{activityId}",
          "UsefulCheck": "/checks/{expectedTarget}", "Check": "/checks/{expectedTarget}",
          "Translation": "/segments/{sourceTarget}", "AlignedPartsGrade": "/",
          "SparseQuestionPatch": "/", "PartsQuestionPatch": "/", "TranslationPatch": "/"}


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


@cache
def bank():
    return json.loads((ROOT / "scenario-dynamic-examples.json").read_text(encoding="utf-8"))


def strip_fixed_examples(instruction):
    if "\nEXAMPLES\n" not in instruction:
        return instruction
    before, examples = instruction.split("\nEXAMPLES\n")
    _, after = examples.split("\nINPUT\n", 1)
    return before + "\nINPUT\n" + after


def release(base):
    config = bank()
    result = ModuleType("scenario_prompts_meaning_v5_3")
    vars(result).update({key: deepcopy(value) for key, value in vars(base).items() if key.isupper()})
    result.PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V5_3"
    result.INSTRUCTIONS = {stage: strip_fixed_examples(base.INSTRUCTIONS[stage]) + "\n\n"
                           + config["commonInstruction"] + "\n\n" + config["stageInstructions"][stage]
                           for stage in STAGES}
    result.DYNAMIC_FEWSHOT = dict(bankHash=digest(config), selectorVersion=SELECTOR_VERSION,
        selectorHash=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        maxExamples=3, maxExampleBytes=6000, basePromptHash=config["basePromptHash"],
        exampleIds=[example["id"] for example in config["examples"]])
    return result


@cache
def checked_examples():
    # Lazy imports avoid the assessment -> candidate -> assessment import cycle.
    from . import scenario_assessment as a, scenario_assessment_recovery as r, scenario_workflow as w
    models = {m.__name__: m for m in (w.PersonalizedExtraction, w.Dialogue, a.SparseActivityDraft,
        r.UsefulCheck, r.Check, w.Translation, a.AlignedPartsGrade, a.SparseQuestionPatch,
        a.PartsQuestionPatch, w.TranslationPatch)}
    accepted, rejected, seen = [], [], set()
    for item in bank()["examples"]:
        try:
            if (not item["approvedForRuntime"] or item["id"] in seen or item["stage"] not in STAGES
                    or item["outputScope"] != SCOPES[item["outputModel"]]):
                raise ValueError("unapproved, duplicate or incompatible example")
            models[item["outputModel"]].model_validate(item["output"])
            seen.add(item["id"])
            accepted.append(item)
        except (ValueError, KeyError, TypeError):
            rejected.append(item.get("id", "unknown"))
    return tuple(accepted), tuple(rejected)


def mode_for(stage, data, phase):
    if stage == "REVIEW":
        return "sourceOnly" if data.get("sourceOnly") else "translations"
    if stage == "REPAIR_QUESTIONS":
        return "grade" if phase == "GRADE" or data.get("preserveQuestionPrompts") else "generation"
    if stage == "REPAIR_TRANSLATIONS":
        return "coverage" if "translationCoverage" in data else "translations"
    return "normal"


def signals(stage, data, context):
    """Input cues select teaching operations; they never diagnose the live artifact."""
    tags, texts = set(), []
    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ("inputVi", "textEn", "promptVi") and isinstance(item, str):
                    texts.append(item.casefold())
                elif key not in ("issues", "verificationContext", "formatFeedback", "reasonVi"):
                    visit(item)
            if "requiredParts" in value and len(value["requiredParts"]) > 1:
                tags.update(("multi-part", "question-scope"))
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(data)
    text = " ".join(texts)
    cues = {
        "reason": r"\b(?:because|why|so that)\b|vì sao|tại sao|lý do",
        "deadline": r"\b(?:by|until|no later than|deadline)\b|hạn chót|chậm nhất|trước thứ",
        "time": r"\b(?:when|o.clock|morning|afternoon)\b|lúc mấy|khi nào|mấy giờ",
        "condition": r"\b(?:if|unless|provided|once|after)\b|nếu|chỉ khi|sau khi",
        "negation": r"\b(?:not|never|cannot|unless)\b|không|chưa",
        "quantity": r"\b(?:how many|how much|at most|at least|percent|\d+)\b|bao nhiêu|tối đa|ít nhất",
        "hypothesis": r"\b(?:hypothesis|predict|experiment)\b|giả thuyết|dự đoán|thí nghiệm",
        "uncertainty": r"\b(?:might|may|tentative|provisional|unconfirmed)\b|có thể|chưa xác nhận|tạm giữ",
        "approval": r"\b(?:approv|permission|sign.off)\w*\b|phê duyệt|cho phép",
        "terminology": r"\b(?:studio|server|workshop|calibration|clay)\b|thuật ngữ|hiệu chuẩn",
    }
    tags.update(tag for tag, pattern in cues.items() if re.search(pattern, text))
    if stage == "GENERATE":
        tags.update(("natural-dialogue", "source-roles"))
    if stage == "EXTRACT" and re.search(r"người nghe|chỉ nghe|listener|hai người|2 người", text):
        tags.add("source-roles")
    if context.get("focusSkill") == "supported-inference":
        tags.update(("optional-inference", "direct-valid"))
    if stage == "GRADE":
        tags.update(("comprehension", "paraphrase"))
    if stage.startswith("REPAIR_"):
        # Only actual Harness diagnostics, never agent labels, drive repair routing.
        codes = " ".join(issue.get("code", "") for issue in data.get("issues", []))
        for word, routed in (("scope", ("question-scope",)), ("information_gain", ("information-gain", "unanswerable")),
                             ("speaker", ("speaker-attribution", "source-roles")),
                             ("translation_coverage", ("translation-id", "duplicate-id", "missing-id"))):
            if word in codes:
                tags.update(routed)
        if data.get("translationCoverage"):
            tags.update(("translation-id", "duplicate-id", "missing-id"))
        if data.get("preserveQuestionPrompts"):
            tags.add("grade-preservation")
    return tags


def compose(selected, stage, data, model, context, phase):
    config = selected.DYNAMIC_FEWSHOT
    mode = mode_for(stage, data, phase)
    tags = signals(stage, data, context)
    compatible = {"SparseQuestions": "SparseActivityDraft", "UsefulReview": "UsefulCheck", "Review": "Check",
                  "Translations": "Translation"}.get(model.__name__, model.__name__)
    level = context.get("profile", {}).get("id", "").upper()
    level = {"SUPPORTED": "A2", "STANDARD": "B1", "CHALLENGE": "B2"}.get(level, level)
    levels = ["A1", "A2", "B1", "B2", "C1", "C2"]
    accepted, rejected = checked_examples()
    ranked = []
    for example in accepted:
        if (example["stage"] != stage or example["mode"] != mode
                or example["outputModel"] != compatible):
            continue
        matched = tags.intersection(example["taskTags"])
        if not matched:
            continue
        if (example["level"] in levels and level in levels
                and levels.index(example["level"]) > levels.index(level)):
            continue
        if (context.get("profile", {}).get("inferencePolicy") == "EXPLICIT_ONLY"
                and '"supportKind": "inference"' in json.dumps(example["output"])):
            continue
        score = (len(matched), example["level"].upper() == level,
                 example["program"] == context.get("programCode"))
        ranked.append((score, example))
    ranked.sort(key=lambda row: (tuple(-int(v) for v in row[0]), row[1]["id"]))
    # Keep a relevant clean counterpart when a review example demonstrates a defect.
    if stage in ("REVIEW", "REVIEW_QUESTIONS") and ranked and ranked[0][1]["output"].get("status") == "DEFECT":
        controls = [row for row in ranked[1:] if row[1]["output"].get("status") == "PASS"
                    and set(row[1]["taskTags"]) & set(ranked[0][1]["taskTags"]) & tags]
        if controls:
            control = controls[0]
            ranked.remove(control)
            ranked.insert(1, control)
    chosen, skipped, signatures = [], [], set()
    block = ""
    for _, example in ranked:
        envelope = dict(id=example["id"], outputScope=example["outputScope"],
                        referenceInput=example["input"], expectedOutput=example["output"])
        # Variations in names or topic do not justify repeating the same operation.
        signature = (tuple(sorted(example["taskTags"])), example["outputScope"], example["output"].get("status"))
        trial = "\n<reference_examples>\n" + json.dumps(chosen + [envelope], ensure_ascii=False, separators=(",", ":")) + "\n</reference_examples>"
        if len(chosen) >= config["maxExamples"] or signature in signatures or len(trial.encode("utf-8")) > config["maxExampleBytes"]:
            skipped.append(example["id"])
            continue
        chosen.append(envelope)
        signatures.add(signature)
        block = trial
    clean_missing = (stage in ("REVIEW", "REVIEW_QUESTIONS")
        and any(e["expectedOutput"].get("status") == "DEFECT" for e in chosen)
        and not any(e["expectedOutput"].get("status") == "PASS" for e in chosen))
    selection = dict(bankHash=config["bankHash"], selectorVersion=SELECTOR_VERSION, selectorHash=config["selectorHash"],
        exampleIds=[e["id"] for e in chosen], exampleBytes=len(block.encode("utf-8")), mode=mode,
        matchedTaskTags=sorted(tags), skippedExampleIds=skipped, invalidExampleIds=list(rejected),
        cleanControlMissing=clean_missing, reason="matched" if chosen else "no-compatible-match-within-budget")
    return selected.INSTRUCTIONS[stage] + block, selection
