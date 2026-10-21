"""Review packet selection: full B1 coverage, six per shadow level, every failure."""
from review_packet import build_packet, select


def _row(key, level, *, types=None, final_errors=(), first_errors=(), state="measured"):
    return {"key": key, "level": level, "turn": 2, "bounds": {"minWords": 8, "maxWords": 35},
            "expectedTaskTypes": types,
            "firstAttempt": {"state": state, "validationErrors": list(first_errors)},
            "repair": {"attempted": False, "succeeded": False, "validationErrors": []},
            "final": {"transport": "ok", "seconds": 1.0, "wordCount": 10, "taskTypes": [],
                      "validationErrors": list(final_errors)},
            "semanticReview": "PENDING_REVIEW"}


def _rows():
    rows = [_row(f"b1-{i}", "B1") for i in range(9)]
    types = ["gist", "argument_relation", "opinion_attitude", "inference_prediction",
             "implicit_intention", "direct_detail", "extra"]
    rows += [_row(f"b2-{i}", "B2", types=[task]) for i, task in enumerate(types)]
    rows += [_row(f"c1-{i}", "C1", types=[types[i % len(types)]]) for i in range(7)]
    rows.append(_row("b2-fail", "B2", types=["extra"], final_errors=["skill:empty"]))
    return rows


def test_shadow_packet_includes_all_b1_six_per_level_and_failures():
    rows = _rows()
    chosen = select(rows, levels=["B1", "B2", "C1"], shadow=True)
    keys = {row["key"] for row in chosen}
    assert {f"b1-{i}" for i in range(9)} <= keys
    assert len([key for key in keys if key.startswith("b2-")]) >= 6
    assert len([key for key in keys if key.startswith("c1-")]) >= 6
    assert "b2-fail" in keys
    assert {"gist", "argument_relation", "opinion_attitude", "inference_prediction",
            "implicit_intention", "direct_detail", "extra"} <= {
        task for row in chosen for task in (row.get("expectedTaskTypes") or [])}


def test_packet_labels_ai_review_as_not_approval(tmp_path):
    table = {"arm": "supported-a1-a2-b1", "datasetHash": "h", "promptVersion": "v", "promptHash": "p",
             "rows": [_row("b1-1:17", "B1")]}
    capture = {"records": [{"id": "b1-1", "seed": 17, "task": {"utteranceEn": "hello there"}, "judgeInput": {"turnNumber": 2}}]}
    packet = build_packet(table, capture, shadow=False)
    assert "AI review is not approval" in packet
    assert "judgeInput" in packet and "hello there" in packet
    assert "## b1-1" in packet


def test_supported_arm_selects_every_sample():
    rows = _rows()
    assert len(select(rows, levels=["A1", "A2", "B1"], shadow=False)) == 9

