"""Locked semantic review rules and development-only calibration examples."""
import copy

VERSION = "conversation-semantic-v2"
CHECKS = ("rolesCorrect", "historyConsistent", "newMeanings", "questionFactsAligned",
          "translationFaithful", "cefrAppropriate", "reasoningGrounded", "noAnswerLeak",
          "informationStatusCorrect", "taskTypeAccurate", "focusFulfilled")
RULES = """
Review source wording independently. Model labels/rationales are claims, not proof.
For each check return true, false, or null when evidence is genuinely insufficient.
Missing/uncertain checks mean REVIEW_REQUIRED, never PASS. Severity is separate:
critical = wrong speaker, unsupported required answer, unsupported/fake inference;
major = misaligned question/slot/fact, wrong taskType, changed arrangement, repeated tested meaning;
minor = localized translation imprecision without changing a required answer. Do not exaggerate.
informationStatusCorrect: a question, suggestion or possible plan is not an agreed rule.
A customer may describe their own constraints or sequence. 'Should I bring a receipt?' can
support 'What document does the customer ask about?', not 'What does the shop require?'.
taskTypeAccurate: classify the requested meaning. gist=overall message; reason_purpose=why/goal;
condition=prerequisite; instruction_sequence=action/order; change_revision=explicit revision;
opinion_attitude=expressed evaluation; inference_prediction=unstated conclusion from linked cues.
A replacement item is direct_detail even if introduced by 'if'. A stated next action is not inference.
Inference needs linked transcript cues and one clearly supported conclusion; no outside guesses.
focusFulfilled: when a genuine type is explicitly requested in practiceFocus, it must occur in a fact
with the correct question and evidence. Do not accept a renamed direct detail as inference.
History preserves the same object/order/booking; old details need not be repeated. New details are
allowed in that encounter. Actual changes need an explicit signal. Unknown speaker stays unknown.
New names/numbers do not make an old tested meaning new. Turn 4 resets the encounter completely.
Translation preserves modality, negation, conditions, quantities, codes and inclusive deadlines:
by Friday evening=chậm nhất vào tối thứ Sáu; before=trước; until=cho đến.
Brother/sister alone does not establish relative age: anh/em trai or chị/em gái is faithful.
Judge task demand, not connector presence. Explicit B1 opinions and cue-based inference are allowed.
Check every question/slot/fact in order, faithful full translation and fact answers, and no UI answer leak.
Do not infer audio tone. Development calibration cases must never be exported as training/holdout data.
"""


def decision(verdict: dict) -> str:
    if not isinstance(verdict, dict):
        return "REVIEW_REQUIRED"
    values = [verdict.get(key) for key in CHECKS]
    if any(value is False for value in values):
        return "FAIL"
    if any(value is not True for value in values) or verdict.get("issues") != []:
        return "REVIEW_REQUIRED"
    return "PASS"


def severity(verdict: dict) -> str:
    if any(verdict.get(key) is False for key in
           ("rolesCorrect", "informationStatusCorrect", "reasoningGrounded")):
        return "critical"
    if verdict.get("severity") == "critical":
        return "critical"
    if any(verdict.get(key) is False for key in
           ("historyConsistent", "newMeanings", "questionFactsAligned", "taskTypeAccurate",
            "cefrAppropriate", "noAnswerLeak", "focusFulfilled")):
        return "major"
    # Translation can change a required answer; only an explicit review may classify it as minor.
    if verdict.get("translationFaithful") is False:
        return "minor" if verdict.get("severity") == "minor" else "major"
    return "none"


def calibration_cases() -> list[dict]:
    # Two positive cases and one negative case per family. Expected labels never enter judge input.
    cases = []

    def task(family, text, translation, question, answers, *, level="A1", speaker="colleague",
             listener="colleague", history=None, focus=""):
        context = {"difficulty": level, "turnNumber": 2, "partnerRole": speaker,
                   "learnerRole": listener, "history": history or [], "practiceFocus": focus}
        value = {"utteranceEn": text, "translationVi": translation, "questionVi": question,
                 "taskVi": "Nghe và trả lời các ý được hỏi.", "skill": "Nắm thông tin cụ thể",
                 "slots": [{"kind": f"meaning_{i}", "askVi": a[0]} for i, a in enumerate(answers)],
                 "facts": [{"textEn": a[1], "textVi": a[2], "evidenceEn": a[3],
                            "taskType": a[4] if len(a) > 4 else "direct_detail"} for a in answers]}
        return {"id": family, "family": family, "expected": "PASS", "context": context, "task": value}

    def triplet(good, mutate, other=None):
        first, bad, last = copy.deepcopy(good), copy.deepcopy(good), copy.deepcopy(other or good)
        first["id"] += "-positive"; bad["id"] += "-negative"; last["id"] += "-positive-2"
        bad["expected"] = "FAIL"; mutate(bad)
        if other is None:
            # A second independently worded UI goal, with identical intended questions.
            last["task"]["taskVi"] = "Nghe thông tin rồi trả lời."
        cases.extend([first, bad, last])

    meeting = task("roles", "I would like a blue shirt. I need size medium for my brother, please.",
        "Tôi muốn một chiếc áo sơ mi xanh. Tôi cần cỡ vừa cho anh/em trai của tôi.",
        "Khách muốn áo màu gì và cỡ nào?", [("Màu áo", "blue", "xanh", "blue"),
        ("Cỡ áo", "medium", "cỡ vừa", "medium")], speaker="customer", listener="shop assistant")
    triplet(meeting, lambda r: r["task"].update(questionVi="Bạn muốn áo màu gì và cỡ nào?"))
    request = task("information_status", "Could you tell me if I should bring my receipt? I can visit on Tuesday after work.",
        "Bạn có thể cho tôi biết liệu tôi có nên mang biên nhận không? Tôi có thể đến vào thứ Ba sau giờ làm.",
        "Khách hỏi về giấy tờ gì và có thể đến ngày nào?", [("Giấy tờ khách hỏi", "receipt", "biên nhận", "receipt"),
        ("Ngày có thể đến", "Tuesday", "thứ Ba", "Tuesday")], speaker="customer", listener="repair technician")
    triplet(request, lambda r: (r["task"].update(questionVi="Cửa hàng bắt buộc mang giấy tờ gì và khách có thể đến ngày nào?"),
        r["task"]["slots"][0].update(askVi="Giấy tờ bắt buộc")))
    base = task("slot_alignment", "Our meeting is in room eight. It starts at ten. Please come to the main building.",
        "Cuộc họp của chúng ta ở phòng tám. Cuộc họp bắt đầu lúc mười giờ. Xin đến tòa nhà chính.",
        "Cuộc họp ở phòng nào và bắt đầu mấy giờ?", [("Phòng họp", "room eight", "phòng tám", "room eight"),
        ("Giờ bắt đầu", "ten", "mười giờ", "ten")])
    triplet(base, lambda r: r["task"]["slots"][0].update(askVi="Người chủ trì cuộc họp"))
    reason = task("task_type", "Please use a box because the clay is still soft. The boxes are under the table. Our assistant can help you pack your work before you leave.",
        "Xin dùng hộp vì đất sét vẫn còn mềm. Hộp ở dưới bàn. Trợ lý có thể giúp bạn đóng gói sản phẩm trước khi rời đi.",
        "Vì sao cần dùng hộp và hộp ở đâu?", [("Lý do dùng hộp", "the clay is still soft", "đất sét vẫn mềm", "the clay is still soft", "reason_purpose"),
        ("Nơi đặt hộp", "under the table", "dưới bàn", "under the table")], level="A2", speaker="workshop employee", listener="participant")
    reason["task"]["skill"] = "Hiểu lý do và mục đích"
    triplet(reason, lambda r: r["task"]["facts"][0].update(taskType="gist"))
    prediction = task("inference", "My train has been cancelled. The next service leaves at noon, and my ticket is valid on it too. I still need to reach the city today. Please bring your notebook for our meeting when you come.",
        "Chuyến tàu của tôi đã bị hủy. Chuyến tiếp theo đi lúc trưa và vé của tôi cũng dùng được cho chuyến đó. Tôi vẫn cần đến thành phố hôm nay. Xin mang sổ cho cuộc họp khi bạn đến.",
        "Người nói có thể đi chuyến nào tiếp theo và bạn cần mang gì?", [("Chuyến có thể đi", "the noon service", "chuyến lúc trưa", "My train has been cancelled.", "inference_prediction"),
        ("Vật cần mang", "your notebook", "sổ của bạn", "your notebook")], level="B1")
    prediction["task"]["skill"] = "Suy luận và dự đoán có căn cứ"
    prediction["task"]["facts"][0]["reasoningEvidence"] = {"segmentsEn": ["My train has been cancelled.",
        "The next service leaves at noon, and my ticket is valid on it too.", "I still need to reach the city today."],
        "rationaleEn": "The cancelled service, valid ticket and need to travel support taking the noon train."}
    triplet(prediction, lambda r: r["task"].update(utteranceEn=r["task"]["utteranceEn"] + " I will take the noon train."))
    continuous = copy.deepcopy(base); continuous["id"] = continuous["family"] = "continuity"
    continuous["context"]["history"] = [{"speaker": "colleague", "utteranceEn": "We have booked room eight for our meeting.",
        "facts": [{"textVi": "cuộc họp", "evidenceEn": "our meeting", "slotKind": "event_type"}]}]
    triplet(continuous, lambda r: r["context"]["history"][0].update(utteranceEn="Our meeting is confirmed in room nine; that booking remains unchanged."))
    deadline = task("translation", "Please send the form by Friday evening. Bring your passport when you visit our office.",
        "Xin gửi mẫu đơn chậm nhất vào tối thứ Sáu. Mang hộ chiếu khi bạn đến văn phòng của chúng tôi.",
        "Hạn gửi mẫu đơn là khi nào và cần mang giấy tờ gì?", [("Hạn gửi", "by Friday evening", "chậm nhất vào tối thứ Sáu", "by Friday evening"),
        ("Giấy tờ", "your passport", "hộ chiếu", "your passport")], level="A2")
    triplet(deadline, lambda r: (r["task"].update(translationVi=r["task"]["translationVi"].replace("chậm nhất vào", "trước")),
        r["task"]["facts"][0].update(textVi="trước tối thứ Sáu")))
    novelty = copy.deepcopy(base); novelty["id"] = novelty["family"] = "novelty_leak"
    triplet(novelty, lambda r: r["task"].update(questionVi="Cuộc họp ở phòng tám và bắt đầu lúc mười giờ phải không?"))
    return cases
