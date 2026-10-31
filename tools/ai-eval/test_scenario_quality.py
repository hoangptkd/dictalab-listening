"""Capture keeps failed replies; report/replay are offline and cannot resubmit."""
import asyncio
import copy
import json
from pathlib import Path
import tempfile

import httpx
import pytest

import scenario_quality as quality
from app.config import Settings

EXAMPLE = json.loads((quality.ROOT / "src/test/resources/scenario/workflow-example.json").read_text(encoding="utf-8"))


@pytest.fixture
def output():
    with tempfile.TemporaryDirectory(prefix="scenario-quality-test-", dir=quality.ROOT / "data") as folder:
        yield Path(folder) / "capture"


def responses():
    return [EXAMPLE["specification"], {"learningPointsVi": ["fact"]*6,
            "content": {"activities": EXAMPLE["content"]["activities"]}},
            {"segments": EXAMPLE["content"]["translations"]},
            {"questionChecks": [{"activityId": a["id"], "questionId": q["id"], "reasonVi": "Supported distinct meaning",
                "answerSupported": True, "distinctMeaning": True}
                for a in EXAMPLE["content"]["activities"] for q in a["questions"]], "valid": True, "issues": []}]


def run_capture(monkeypatch, output, replies=None, max_calls=32):
    data = quality.cases(quality.DEFAULT_CASES)
    data["cases"] = [{**data["cases"][0], "inputVi": "Tôi muốn đặt bánh để nhận ngày mai"}]
    dataset = output.parent / "cases.json"
    quality.write(dataset, data)
    replies = copy.deepcopy(responses() if replies is None else replies)
    seen = []

    class FakeGateway:
        def __init__(self, settings, client):
            self._settings, self._client = settings, client
            self.last_model, self.last_source_id = settings.model, None

        async def complete(self, messages, schema, deadline, purpose, **options):
            seen.append(purpose)
            response = replies[len(seen)-1]
            if isinstance(response, Exception): raise response
            return response, "ollama"

    monkeypatch.setattr(quality.workflow, "ModelGateway", FakeGateway)
    asyncio.run(quality.capture(dataset, output, Settings(scenario_enabled=True), max_calls=max_calls))
    return seen, dataset


def test_capture_report_replay_preserve_same_outputs_without_network(monkeypatch, output):
    seen, dataset = run_capture(monkeypatch, output)
    assert len(seen) == 4
    original = (output / "food-allergy/GENERATE/02-scenario_translate-response.json").read_bytes()
    assert quality.report(output)["cases"][0]["phases"]["GENERATE"]["status"] == "SUCCEEDED"
    # Replay replaces the gateway entirely, so even the live fake must never be called again.
    replay = asyncio.run(quality.replay(output))
    assert replay["networkCalls"] == 0 and all(row["sameOutcome"] for row in replay["phases"])
    assert len(seen) == 4
    assert (output / "food-allergy/GENERATE/02-scenario_translate-response.json").read_bytes() == original
    with pytest.raises(FileExistsError):
        asyncio.run(quality.capture(dataset, output, Settings(scenario_enabled=True)))
    assert len(seen) == 4


def test_rejected_generated_content_is_kept_before_validation(monkeypatch, output):
    replies = copy.deepcopy(responses())
    replies[1]["content"]["activities"][0]["segments"][0]["textEn"] = "one"
    replies[1]["content"]["activities"][0]["segments"][1]["textEn"] = "two"
    seen, _ = run_capture(monkeypatch, output, replies)
    assert seen == ["scenario_extract", "scenario_generate"]
    saved = quality.report(output)["cases"][0]["phases"]["GENERATE"]
    assert saved["status"] == "REJECTED" and saved["error"]["validationReason"] == "activity word limit"
    assert saved["replies"][0]["raw"] == replies[1]
    assert all(r["sameOutcome"] for r in asyncio.run(quality.replay(output))["phases"])


def test_capture_does_not_retry_unknown_and_does_not_log_credential_messages(monkeypatch, output):
    seen, _ = run_capture(monkeypatch, output, [TimeoutError("Bearer do-not-save-this-secret")])
    assert len(seen) == 1
    saved = quality.report(output)["cases"][0]["phases"]["EXTRACT"]
    assert saved["status"] == "UNKNOWN"
    assert saved["error"] == {"type": "TimeoutError"}
    assert "do-not-save-this-secret" not in (output / "food-allergy/EXTRACT/outcome.json").read_text()


def test_budget_stop_is_distinct_from_sent_unknown(monkeypatch, output):
    seen, _ = run_capture(monkeypatch, output, max_calls=1)
    assert seen == ["scenario_extract"]
    assert quality.report(output)["cases"][0]["phases"]["GENERATE"]["status"] == "NOT_STARTED"


def test_budget_stop_after_generation_keeps_partial_reply(monkeypatch, output):
    seen, _ = run_capture(monkeypatch, output, max_calls=2)
    assert seen == ["scenario_extract", "scenario_generate"]
    saved = quality.report(output)["cases"][0]["phases"]["GENERATE"]
    assert saved["status"] == "BUDGET_STOPPED" and saved["calls"] == 1
    assert saved["replies"][0]["raw"] == responses()[1]


@pytest.mark.parametrize("status", [200, 503])
def test_wire_retains_malformed_reply_without_headers_or_error_body(monkeypatch, output, status):
    actual_client = httpx.AsyncClient
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(status, headers={"X-Private": "header-secret"},
            json={"model": "fixture", "done": True, "done_reason": "stop",
                  "message": {"content": "not valid JSON"}, "private": "body-secret"})

    monkeypatch.setattr(httpx, "AsyncClient", lambda **options: actual_client(
        **options, transport=httpx.MockTransport(handler)))
    data = quality.cases(quality.DEFAULT_CASES)
    data["cases"] = data["cases"][:1]
    dataset = output.parent / "cases.json"
    quality.write(dataset, data)
    asyncio.run(quality.capture(dataset, output, Settings(scenario_enabled=True,
        primary_provider="ollama", ollama_service_token="request-secret")))
    assert len(seen) == 1
    folder = output / "food-allergy/EXTRACT"
    wire = quality.read(folder / "01-scenario_extract-wire.json")[0]
    assert wire["httpStatus"] == status and len(wire["bodySha256"]) == 64
    assert wire.get("outputText") == ("not valid JSON" if status == 200 else None)
    assert not (folder / "01-scenario_extract-response.json").exists()
    text = "".join(p.read_text(encoding="utf-8") for p in folder.glob("*.json"))
    assert all(secret not in text for secret in ("header-secret", "body-secret", "request-secret"))
    assert quality.report(output)["cases"][0]["phases"]["EXTRACT"]["status"] == "UNKNOWN"


def test_refusal_never_runs_generation(monkeypatch, output):
    spec = {**EXAMPLE["specification"], "allowed": False}
    seen, _ = run_capture(monkeypatch, output, [spec])
    assert seen == ["scenario_extract"]
    assert "GENERATE" not in quality.report(output)["cases"][0]["phases"]


def test_integrity_checks_reject_modified_or_added_evidence(monkeypatch, output):
    run_capture(monkeypatch, output)
    (output / "derived/notes.json").write_text("{}", encoding="utf-8")
    quality.verify(output)  # analysis is separate from sealed provider evidence
    extra = output / "extra.json"
    extra.write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="unsealed"): quality.verify(output)
    extra.unlink()
    nested = output / "food-allergy/integrity.json"
    nested.write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="unsealed"): quality.verify(output)
    nested.unlink()
    target = output / "food-allergy/EXTRACT/outcome.json"
    target.write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="integrity mismatch"): quality.verify(output)


def test_corpus_covers_three_profiles_and_rejects_contact_data(tmp_path):
    dataset = quality.cases(quality.DEFAULT_CASES)
    assert len(dataset["cases"]) == 8
    assert {c["profileId"] for c in dataset["cases"]} == {"supported", "standard", "challenge"}
    dataset["cases"][0]["inputVi"] = "Contact user@example.com"
    target = tmp_path / "cases.json"
    quality.write(target, dataset)
    with pytest.raises(ValueError): quality.cases(target)


def test_partial_capture_report_never_claims_integrity_or_success(monkeypatch, output):
    seen, _ = run_capture(monkeypatch, output)
    (output / "integrity.json").unlink()
    (output / "finished.json").unlink()
    (output / "food-allergy/GENERATE/outcome.json").unlink()
    with pytest.raises(ValueError, match="incomplete"): quality.report(output)
    partial = quality.report(output, allow_incomplete=True)
    assert partial["evidenceIntegrity"] == "UNSEALED_INCOMPLETE" and partial["finished"] is None
    assert partial["cases"][0]["phases"]["GENERATE"]["status"] == "UNKNOWN_INTERRUPTED"
    assert len(partial["cases"][0]["phases"]["GENERATE"]["replies"]) == 3
    assert len(seen) == 4
