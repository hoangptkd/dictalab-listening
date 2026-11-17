"""Existing loopback Web judge bridge; never available as an application provider."""
from urllib.parse import urlsplit


class WebJudgeProvider:
    @staticmethod
    async def send(client, endpoint, body):
        uri = urlsplit(endpoint)
        if (uri.scheme != "http" or uri.hostname not in ("localhost", "127.0.0.1", "::1") or
                uri.username or uri.password or uri.query or uri.fragment or uri.path != "/v1/chat/completions"):
            raise ValueError("Web judge requires a loopback chat endpoint")
        # The caller persists its send marker first and handles only explicit quota rejections.
        return await client.post(endpoint, json=body)
