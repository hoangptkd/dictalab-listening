# Shared text-to-speech

TTS is independent of conversation enablement and of the text AI provider. Cartesia
remains the default. The second adapter, `openai_compatible`, implements the
`POST /v1/audio/speech` request contract with `response_format=wav`. It can use any
configured endpoint implementing that contract and returning the audio format below.
This does not imply support for services with unrelated speech APIs.

## Selection and credentials

Set `DICTALAB_TTS_PROVIDER=cartesia` or `openai_compatible`. Set the same value in
Core, AI API and worker. `DICTALAB_TTS_ENABLED=false` disables new speech generation;
it does not delete existing audio. These settings do not change application usage
limit policy, authentication, ownership, provider circuits or the AI emergency stop.

`DICTALAB_AI_TTS_MODE` still selects **execution location** (`legacy`, `shadow`,
`canary`, `python`), not the speech provider. Existing canary and shadow semantics
remain. No provider is called automatically as a fallback after an uncertain result.

Cartesia keeps the existing `CARTESIA_API_KEY`, `CARTESIA_MODEL`, `CARTESIA_VOICE`,
`CARTESIA_VERSION`, `TTS_URL`, and `TTS_CONCURRENCY` Core environment settings.
Existing `dictalab.conversation.cartesia-*` property overrides are compatibility
aliases; new Core configuration can use `dictalab.tts.cartesia.*` directly.
Cartesia no longer depends on `dictalab.conversation.enabled`.

For the alternate adapter configure:

| Setting | Meaning |
| --- | --- |
| `DICTALAB_TTS_OPENAI_URL` | Complete speech endpoint; default `https://api.openai.com/v1/audio/speech` |
| `DICTALAB_TTS_OPENAI_API_KEY` | Separate speech credential, never the text provider key |
| `DICTALAB_TTS_OPENAI_MODEL` | Model supported by that endpoint; required |
| `DICTALAB_TTS_OPENAI_VOICE` | Voice supported by that endpoint; required |

Store credentials in protected runtime configuration, never command arguments,
source control or reports. In an isolated deployment, speech credentials belong
only to AI API/worker; Core keeps the selector and internal service token and uses
`DICTALAB_AI_TTS_MODE=python`. The service launcher rejects speech keys in isolated
Core configuration. For the direct legacy execution path, Core needs its selected
speech credential as before. Remote endpoints require HTTPS; loopback HTTP is
allowed for local tests. Redirects are not followed.

Python accepts `DICTALAB_TTS_*` above, or `DICTALAB_AI_TTS_PROVIDER`,
`DICTALAB_AI_TTS_ENABLED` and `DICTALAB_AI_OPENAI_TTS_{URL,API_KEY,MODEL,VOICE}`.
Cartesia's Python settings remain `DICTALAB_AI_CARTESIA_{URL,API_KEY,MODEL,VOICE,VERSION}`.
Compose forwards the shared settings to both AI containers.

## Reuse from another feature

For direct Core execution, inject `TtsService` and use `enabled()` and
`synthesize(text)`. Feature code must not inject `CartesiaClient` or construct
provider requests. `TtsProvider` is the adapter interface; the shared HTTP transport
owns timeouts, bounded bodies and separate provider circuits.

For AI service execution, use the existing authenticated `/v1/tts/jobs` job contract
with `{"kind":"tts","text":"At nine."}`. Conversation retains its existing
rollout routing; other features can reuse `AiPlatformClient.complete("tts", ...)`
and `audio(result)`. This internal API is not a new public endpoint accepting
arbitrary unauthenticated text. Feature callers retain authorization, emergency-stop
checks and admission before dispatch, and ownership checks before storing/serving.

All adapters return WAV PCM16, mono, 24 kHz, at most 3,000,000 bytes, from input of
1–1,200 characters. Invalid types, rates, channels, truncated bodies and oversized
audio fail closed. Streaming WAV size sentinels are finalized only after successful
HTTP EOF. No resampling or new audio dependencies are introduced; an alternate
endpoint must produce this format.

## Frozen scenario voices and existing audio

Old recipes without a `provider` field retain Cartesia, their serialized content,
recipe hashes and historical prompt hash. New alternate recipes include
`"provider":"openai_compatible"`, `"apiVersion":""` and two distinct voice names
(for example `alloy` and `nova`, if supported by the chosen endpoint). Cartesia
recipes keep UUID voices and their dated API version. Recipes must be approved in
AI settings and captured in the scenario session before use.

Changing the default provider does not reinterpret frozen recipes or regenerate
stored audio. Keep the old provider credential while those lessons can still need
new audio. Core verifies each scenario result against its captured provider, model,
speaker, voice and recipe hash. Generic job fingerprints include the selected
provider/model/voice/endpoint; rotating a key does not change content identity.

## Checks

```text
mvn -Dtest=TtsServiceTest,AiPlatformClientTest,ScenarioContentTest,ScenarioEngineIntegrationTest,ConversationIntegrationTest,AiBackgroundJobsIntegrationTest test
cd services/ai-generation
python -m pytest tests/test_tts_providers.py tests/test_jobs.py -q
```

These checks use local mock providers and temporary test databases, not paid APIs.
The alternate request contract follows the [OpenAI Speech API reference](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create).
