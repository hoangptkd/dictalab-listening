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

# Keep v2 byte-for-byte reproducible for frozen v8 evidence.
VERSION_V3 = "conversation-semantic-v3"
RULES_V3 = RULES + """
Boundary rules (take precedence over broad wording above):
One slot is one requested meaning, not necessarily one word. A location may include a landmark
(the cupboard beside the entrance); a time interval may include its two endpoints. 'When/when by'
can ask for a date and time together; 'what time/how many minutes' asks only for clock time/duration.
An unasked independently testable day, person, place, reason or action in textEn/textVi fails alignment.
An optional grammatical subject or a location's identifying modifier is not a second tested answer.
An opinion answer may name its object and evaluation; independent reasons require a why question.
Requested action/order -> instruction_sequence; requested item/place/time/duration -> direct_detail,
even when the source is an imperative. Requested prerequisite -> condition. Requested action under
an if-clause is still action/order, not the condition. Explicitly contrasted final value -> change_revision.
Gist covers the communicative point of the whole utterance, not a selected reason or detail.
A speaker's tentative plan can be tested as tentative; a request about a policy cannot establish it.
Same time for two events is not itself a contradiction. 'Still' must refer to the SAME established event;
reusing a rehearsal time as an already-set presentation time without evidence is REVIEW_REQUIRED.
Unknown history speaker alone is not grounds for uncertainty when the new task is independently clear.
Common role-neutral Vietnamese address (tôi/bạn) is acceptable; do not assign sibling age or third-person
gender absent source evidence. Unsupported age/gender in English fact answers also fails faithfulness.
For every false/null check cite the offending wording and the violated rule. Do not invent defects
or approve ambiguous cases. Hard schema checks are separate; these fixtures test semantic boundaries.
"""


def rules_for(version: str) -> str:
    if version == VERSION:
        return RULES
    if version == VERSION_V3:
        return RULES_V3
    raise ValueError("unknown semantic rubric version")


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


def judge_generalization_cases() -> list[dict]:
    """Development contrasts for the audit method; never a replacement for frozen gold."""
    cases = []

    def task(name, text, translation, question, answer, answer_vi):
        return {"id": name, "family": name, "semanticProbeOnly": True,
            "expected": "PASS", "expectedSeverity": "none", "expectedCheck": None,
            "context": {"difficulty": "B1", "turnNumber": 2, "partnerRole": "colleague",
                        "learnerRole": "colleague", "history": [], "practiceFocus": ""},
            "task": {"utteranceEn": text, "translationVi": translation, "questionVi": question,
                "taskVi": "Nghe và trả lời câu hỏi.", "skill": "Nắm thông tin cụ thể",
                "slots": [{"kind": name, "askVi": question}],
                "facts": [{"textEn": answer, "textVi": answer_vi,
                           "evidenceEn": text, "taskType": "direct_detail"}]}}

    def pair(good, check, mutate, *, severity="major"):
        bad = copy.deepcopy(good)
        good["id"] += "-valid"
        bad.update(id=bad["id"] + "-invalid", expected="FAIL",
                   expectedCheck=check, expectedSeverity=severity)
        mutate(bad)
        cases.extend([good, bad])

    pair(task("request", "Please wait for me outside the library. I will bring the files.",
        "Hãy đợi tôi bên ngoài thư viện. Tôi sẽ mang hồ sơ.",
        "Hai người gặp nhau ở đâu?", "outside the library", "bên ngoài thư viện"),
        "informationStatusCorrect", lambda r: (
            r["task"].update(questionVi="Hai bên đã cùng xác nhận gặp nhau ở đâu?"),
            r["task"]["slots"][0].update(askVi="Địa điểm hai bên đã cùng xác nhận")), severity="critical")
    pair(task("offer", "I can reserve an aisle seat for you. Would that help?",
        "Tôi có thể đặt trước một ghế cạnh lối đi cho bạn. Như thế có giúp được không?",
        "Người nói có thể đặt loại ghế nào?", "an aisle seat", "một ghế cạnh lối đi"),
        "informationStatusCorrect", lambda r: (
            r["task"].update(questionVi="Người nói đã đặt xong loại ghế nào?"),
            r["task"]["slots"][0].update(askVi="Loại ghế đã đặt xong")), severity="critical")
    policy = task("policy", "Do I need to wear safety boots? I have a pair in my car.",
        "Tôi có cần đi giày bảo hộ không? Tôi có một đôi trong xe.",
        "Người nói hỏi có cần đi loại giày nào?", "safety boots", "giày bảo hộ")
    pair(policy, "informationStatusCorrect", lambda r: (
        r["task"].update(questionVi="Nội quy bắt buộc đi loại giày nào?"),
        r["task"]["slots"][0].update(askVi="Giày bắt buộc theo nội quy")), severity="critical")
    pair(task("supporting_detail", "Put the badge in the drawer near the printer. Mai will collect it.",
        "Đặt thẻ vào ngăn kéo gần máy in. Mai sẽ đến lấy thẻ.",
        "Cần đặt thẻ ở đâu?", "the drawer near the printer", "ngăn kéo gần máy in"),
        "questionFactsAligned", lambda r: r["task"]["facts"][0].update(
            textEn="the drawer near the printer; Mai will collect it",
            textVi="ngăn kéo gần máy in; Mai sẽ đến lấy thẻ"))
    ambiguous = task("lexical_sense", "I saw a bat yesterday. I wrote the word in my notebook.",
        "Hôm qua tôi thấy một con dơi. Tôi đã viết từ đó vào sổ.",
        "Người nói đã thấy gì?", "a bat", "một con dơi")
    ambiguous.update(id="lexical_sense-unresolved", expected="REVIEW_REQUIRED",
                     expectedCheck="translationFaithful")
    resolved = copy.deepcopy(ambiguous)
    resolved.update(id="lexical_sense-resolved", expected="PASS", expectedCheck=None)
    resolved["task"].update(utteranceEn="I saw a bat hanging upside down in the cave.",
                           translationVi="Tôi thấy một con dơi treo mình ngược trong hang.")
    resolved["task"]["facts"][0]["evidenceEn"] = resolved["task"]["utteranceEn"]
    wrong = copy.deepcopy(resolved)
    wrong.update(id="lexical_sense-wrong", expected="FAIL", expectedCheck="translationFaithful",
                 expectedSeverity="major")
    wrong["task"].update(translationVi="Tôi thấy một cây gậy bóng chày treo ngược trong hang.")
    wrong["task"]["facts"][0]["textVi"] = "một cây gậy bóng chày"
    cases.extend([ambiguous, resolved, wrong])
    pair(task("reference", "There are buses at nine and ten. Choose the one you prefer.",
        "Có chuyến xe buýt lúc chín giờ và mười giờ. Hãy chọn chuyến bạn thích hơn.",
        "Có những chuyến xe lúc mấy giờ?", "nine and ten", "chín giờ và mười giờ"),
        "translationFaithful", lambda r: r["task"].update(
            translationVi="Có chuyến xe buýt lúc chín giờ và mười giờ. Hãy chọn món ăn bạn thích hơn."))
    reset = task("encounter_scope", "For the museum visit, I will arrive at eight.",
        "Với chuyến tham quan bảo tàng, tôi sẽ đến lúc tám giờ.",
        "Người nói sẽ đến lúc mấy giờ?", "eight", "tám giờ")
    reset["context"].update(turnNumber=4,
        encounterState={"encounterRule": "new"},
        history=[{"utteranceEn": "For dinner, I will arrive at seven.",
                  "facts": [{"textVi": "Bảy giờ", "evidenceEn": "at seven"}]}])
    pair(reset, "newMeanings", lambda r: (
        r["context"].update(turnNumber=2, encounterState={"encounterRule": "same"}),
        r["task"].update(utteranceEn="For dinner, I will arrive at seven.",
                         translationVi="Với bữa tối, tôi sẽ đến lúc bảy giờ."),
        r["task"]["facts"][0].update(textEn="seven", textVi="bảy giờ", evidenceEn="at seven")))
    pair(task("equivalent_wording", "The parcel is beside the counter.",
        "Kiện hàng ở bên quầy.", "Kiện hàng ở đâu?", "beside the counter", "bên quầy"),
        "translationFaithful", lambda r: (
            r["task"].update(translationVi="Kiện hàng ở bên trong quầy."),
            r["task"]["facts"][0].update(textVi="bên trong quầy")))
    # Shared neutral detail supplies the second slot and B1 length for the real judge adapter.
    # These remain development probes, even though they meet the hard task contract.
    for row in cases:
        value = row["task"]
        value["utteranceEn"] += (" Please call me on Monday afternoon for any further details. "
                                 "I will be at my desk then and have all the necessary information ready.")
        value["translationVi"] += (" Hãy gọi cho tôi vào chiều thứ Hai nếu cần thêm chi tiết. "
                                   "Khi đó tôi sẽ ở bàn làm việc và đã chuẩn bị sẵn mọi thông tin cần thiết.")
        value["questionVi"] += " Bạn có thể gọi lại khi nào?"
        value["slots"].append({"kind": "callback_time", "askVi": "Thời điểm có thể gọi lại"})
        value["facts"].append({"textEn": "Monday afternoon", "textVi": "chiều thứ Hai",
                              "evidenceEn": "on Monday afternoon", "taskType": "direct_detail"})
    return cases


def boundary_cases_v3() -> list[dict]:
    """Six paired semantic boundaries, separate from the unchanged v2 calibration."""
    cases = []

    def pair(name, text, question, slot, answer, kind, check, mutation, *, history=None, turn=2):
        good = {"id": name + "-positive", "family": name, "expected": "PASS",
            "context": {"difficulty": "B1", "turnNumber": turn, "partnerRole": "colleague",
                "learnerRole": "colleague", "history": history or [], "practiceFocus": ""},
            "task": {"utteranceEn": text, "questionVi": question, "taskVi": "Nghe thông tin và trả lời.",
                "skill": "Nắm thông tin cụ thể", "slots": [{"kind": name, "askVi": slot}],
                "facts": [{"textEn": answer, "taskType": kind}]}}
        bad = copy.deepcopy(good)
        bad.update(id=name + "-negative", expected="FAIL", expectedCheck=check)
        mutation(bad)
        cases.extend([good, bad])

    pair("date_time", "Please come at nine on Monday morning.",
        "Bạn cần đến khi nào?", "Thời điểm cần đến", "nine on Monday morning", "direct_detail",
        "questionFactsAligned", lambda r: (r["task"].update(questionVi="Bạn cần đến lúc mấy giờ?"),
            r["task"]["slots"][0].update(askVi="Giờ cần đến")))
    pair("location_modifier", "Leave your coat in the cupboard beside the entrance. Nam will collect it.",
        "Bạn cần để áo khoác ở đâu?", "Nơi để áo khoác", "the cupboard beside the entrance", "direct_detail",
        "questionFactsAligned", lambda r: r["task"]["facts"][0].update(
            textEn="the cupboard beside the entrance; Nam will collect it"))
    pair("action_condition", "If any parts are replaced, please keep the old ones for me.",
        "Nếu thay linh kiện thì cần làm gì với linh kiện cũ?", "Hành động với linh kiện cũ",
        "keep the old parts", "instruction_sequence", "taskTypeAccurate",
        lambda r: r["task"]["facts"][0].update(taskType="condition"))
    pair("duration_instruction", "Please arrive ten minutes early so we can check your booking.",
        "Bạn cần đến sớm bao lâu?", "Khoảng thời gian đến sớm", "ten minutes early", "direct_detail",
        "taskTypeAccurate", lambda r: r["task"]["facts"][0].update(taskType="instruction_sequence"))
    pair("opinion_scope", "I like the breakfast room because it is quiet and the food is fresh.",
        "Người nói có quan điểm gì về phòng ăn sáng?", "Quan điểm về phòng ăn sáng",
        "The speaker likes the breakfast room", "opinion_attitude", "questionFactsAligned",
        lambda r: r["task"]["facts"][0].update(
            textEn="The speaker likes the breakfast room because it is quiet and the food is fresh"))
    pair("tentative_plan", "Could you tell me whether I need a receipt? I may visit on Tuesday.",
        "Người nói có thể đến vào ngày nào?", "Ngày có thể đến", "possibly Tuesday", "direct_detail",
        "informationStatusCorrect", lambda r: (r["task"].update(questionVi="Người nói đã chốt đến ngày nào?"),
            r["task"]["slots"][0].update(askVi="Ngày đã chốt"),
            r["task"]["facts"][0].update(textEn="Tuesday is confirmed")))
    # Semantic probes deliberately omit translations/schema fields so only the named boundary is judged.
    for row in cases:
        row["semanticProbeOnly"] = True
    return cases
