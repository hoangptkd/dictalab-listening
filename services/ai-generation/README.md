# DictaLab AI generation service

This service is an optional boundary for conversation generation. Spring Boot remains the owner of
authentication, sessions, history, quota and persistence. The default provider is unchanged; set
`DICTALAB_CONVERSATION_GENERATION_PROVIDER=python` only after this service and Ollama are ready.

Run locally:

```text
python -m venv .venv
pip install -e ".[test]"
uvicorn app.main:app --port 8091
```

Configuration uses `DICTALAB_AI_` variables. Set `DICTALAB_AI_SERVICE_TOKEN` and the matching Java
`DICTALAB_CONVERSATION_PYTHON_SERVICE_TOKEN` when the endpoint is reachable beyond loopback.
