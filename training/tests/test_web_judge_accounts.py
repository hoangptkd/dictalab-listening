import json

import httpx
import pytest

from training import generate_teacher_data as gen
from training import web_judge_accounts as web


ACCOUNTS = [{"id": "a", "url": "http://127.0.0.1:3000/v1/chat/completions"},
            {"id": "b", "url": "http://127.0.0.1:3001/v1/chat/completions"}]
BODY = {"messages": [{"role": "user", "content": "Unchanged judgment input"}]}
CLIENT = httpx.AsyncClient


def transport(monkeypatch, respond):
    monkeypatch.setattr(web.httpx, "AsyncClient", lambda **kwargs: CLIENT(
        transport=httpx.MockTransport(respond), **kwargs))


@pytest.mark.asyncio
async def test_quota_switch_reuses_input_and_records_account(tmp_path, monkeypatch):
    calls = []
    def respond(request):
        calls.append(request)
        assert json.loads(request.content) == BODY
        if request.url.port == 3000:
            return httpx.Response(429, json={"error": {"code": "chatgpt_web_quota"}})
        return httpx.Response(200, json={"choices": [], "web_judge": {"modelLabel": "Visible model B"}})
    transport(monkeypatch, respond)
    response, _, metadata = await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS)
    assert response.status_code == 200 and metadata["accountId"] == "b"
    assert metadata["modelLabel"] == "Visible model B" and len(calls) == 2
    assert [a["outcome"] for a in web.attempts(tmp_path, "case")] == ["QUOTA", "RESPONSE"]
    with pytest.raises(gen.DatasetError, match="refusing replay"):
        await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS)


@pytest.mark.asyncio
async def test_both_quotas_wait_then_resume_same_case(tmp_path, monkeypatch):
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(429, json={"error": {"code": "chatgpt_web_quota"}})
    transport(monkeypatch, respond)
    for _ in range(2):
        with pytest.raises(web.WebQuotaWait):
            await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS)
    assert len(calls) == 2 and web.resumable(tmp_path, "case", BODY)
    assert not web.resumable(tmp_path, "case", {"messages": []})
    monkeypatch.setattr(web.time, "time", lambda: 10**12)
    transport(monkeypatch, lambda _: httpx.Response(200, json={"choices": []}))
    assert (await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS))[2]["accountId"] == "a"


@pytest.mark.asyncio
@pytest.mark.parametrize("fault", ["timeout", "generic-429", "bad-json"])
async def test_ambiguous_or_nonquota_error_never_switches_or_replays(tmp_path, monkeypatch, fault):
    calls = []
    def respond(request):
        calls.append(request)
        if fault == "timeout":
            raise httpx.ReadTimeout("uncertain send")
        if fault == "bad-json":
            return httpx.Response(502, text="gateway error")
        return httpx.Response(429, json={"error": {"code": "other_error"}})
    transport(monkeypatch, respond)
    if fault == "generic-429":
        assert (await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS))[0].status_code == 429
    else:
        with pytest.raises((httpx.ReadTimeout, ValueError)):
            await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS)
    with pytest.raises(gen.DatasetError, match="refusing replay"):
        await web.dispatch(tmp_path, tmp_path, "case", BODY, ACCOUNTS)
    assert len(calls) == 1
