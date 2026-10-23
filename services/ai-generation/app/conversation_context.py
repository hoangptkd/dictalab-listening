"""Source-backed encounter index; never infer settled facts from a question."""
import copy


def encounter_state(context: dict) -> dict:
    transfer = context.get("turnNumber") == 4
    turns = []
    for number, turn in enumerate([] if transfer else context.get("history") or [], 1):
        turns.append({"sourceTurn": number, "speaker": turn.get("speaker", "unknown"),
            "utteranceEn": turn.get("utteranceEn", ""),
            "testedMeanings": [{key: copy.deepcopy(fact[key]) for key in
                ("textVi", "evidenceEn", "slotKind", "askVi", "taskType") if key in fact}
                for fact in turn.get("facts", [])]})
    return {"speaker": context.get("partnerRole", "unknown"),
        "listener": context.get("learnerRole", "unknown"),
        "currentObject": None, "sourceTurns": turns,
        "informationStatus": "Read source wording: requests/proposals are not settled agreements; unknown stays unknown.",
        "encounterRule": "new" if transfer else "same"}
