"""Bounded Cartesia WAV transport. Audio is returned to Java, never a public URL."""
import base64
import io
import time
import wave
from typing import Literal

from pydantic import Field, field_validator, model_validator

from .coach import StrictModel
from .redaction import redact_string

MAX_AUDIO_BYTES = 3_000_000
PROMPT_VERSION = "TTS_WAV_V1"


class TtsInput(StrictModel):
    kind: Literal["tts"] = "tts"
    text: str = Field(min_length=1, max_length=1200)

    @field_validator("text")
    @classmethod
    def safe_text(cls, value):
        return redact_string(value)


class ScenarioTtsRecipe(StrictModel):
    id: str = Field(pattern=r"^[a-zA-Z0-9_.-]{1,64}$")
    model: str = Field(pattern=r"^[a-zA-Z0-9_.-]{1,100}$")
    apiVersion: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    language: Literal["en"]
    outputFormat: Literal["wav-pcm16-mono-24000"]
    voices: list[str] = Field(min_length=2, max_length=2)

    @model_validator(mode="after")
    def valid_voices(self):
        import re
        if len(set(self.voices)) != 2 or any(not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", v) for v in self.voices):
            raise ValueError("recipe requires two distinct voice UUIDs")
        return self


class ScenarioTtsInput(StrictModel):
    kind: Literal["scenario_tts"] = "scenario_tts"
    text: str = Field(min_length=1, max_length=1200)
    speakerId: str = Field(pattern=r"^[a-z][a-z0-9-]{0,15}$")
    voiceId: str = Field(min_length=36, max_length=36)
    recipe: ScenarioTtsRecipe

    @field_validator("text")
    @classmethod
    def unchanged_safe_text(cls, value):
        if redact_string(value) != value or "[REDACTED]" in value:
            raise ValueError("TTS_TEXT_REDACTED")
        return value

    @model_validator(mode="after")
    def approved_voice(self):
        if self.voiceId not in self.recipe.voices:
            raise ValueError("voice is not in the recipe")
        return self


def validate_wav(audio: bytes):
    if not 44 <= len(audio) <= MAX_AUDIO_BYTES:
        raise ValueError("invalid audio size")
    if audio[:4] != b"RIFF" or audio[8:12] != b"WAVE" or int.from_bytes(audio[4:8], "little") + 8 != len(audio):
        raise ValueError("invalid WAV signature or length")
    try:
        with wave.open(io.BytesIO(audio), "rb") as wav:
            if (wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 24000
                    or wav.getcomptype() != "NONE" or wav.getnframes() < 1
                    or len(wav.readframes(wav.getnframes())) != wav.getnframes() * 2):
                raise ValueError("invalid PCM audio")
    except (wave.Error, EOFError) as exc:
        raise ValueError("invalid WAV") from exc


def finalize_wav(audio: bytes) -> bytes:
    # Cartesia streams WAV with unknown RIFF/data sizes; finalize only after HTTP EOF.
    if (44 <= len(audio) <= MAX_AUDIO_BYTES and audio[:4] == b"RIFF"
            and audio[8:12] == b"WAVE" and audio[4:8] == b"\xff" * 4):
        offset = 12
        while offset + 8 <= len(audio):
            chunk = audio[offset:offset + 4]
            size = int.from_bytes(audio[offset + 4:offset + 8], "little")
            if chunk == b"data":
                if size == 0xFFFFFFFF:
                    size = len(audio) - offset - 8
                    if size < 2 or size % 2:
                        raise ValueError("invalid PCM audio")
                    finalized = bytearray(audio)
                    finalized[4:8] = (len(audio) - 8).to_bytes(4, "little")
                    finalized[offset + 4:offset + 8] = size.to_bytes(4, "little")
                    audio = bytes(finalized)
                break
            offset += 8 + size + size % 2
    validate_wav(audio)
    return audio


async def synthesize(payload, settings, client, deadline):
    if not settings.cartesia_api_key:
        raise ValueError("TTS is not configured")
    recipe = payload.recipe if isinstance(payload, ScenarioTtsInput) else None
    if recipe is not None and recipe not in settings.scenario_tts_recipes:
        raise ValueError("scenario TTS recipe is not approved")
    body = {"model_id": recipe.model if recipe else settings.cartesia_model, "transcript": payload.text, "language": "en",
            "voice": {"mode": "id", "id": payload.voiceId if recipe else settings.cartesia_voice},
            "output_format": {"container": "wav", "encoding": "pcm_s16le", "sample_rate": 24000}}
    timeout = min(45, deadline - time.monotonic())
    if timeout <= 0:
        raise TimeoutError("TTS deadline exhausted")
    async with client.stream("POST", settings.cartesia_url, json=body, timeout=timeout,
            headers={"Authorization": "Bearer " + settings.cartesia_api_key,
                     "Cartesia-Version": recipe.apiVersion if recipe else settings.cartesia_version}) as response:
        response.raise_for_status()
        if response.headers.get("content-type", "").split(";")[0].lower() not in ("audio/wav", "audio/x-wav", "audio/wave"):
            raise ValueError("invalid audio content type")
        audio = bytearray()
        async for chunk in response.aiter_bytes():
            audio.extend(chunk)
            if len(audio) > MAX_AUDIO_BYTES:
                raise ValueError("audio exceeds limit")
    audio = finalize_wav(bytes(audio))
    return {"contentType": "audio/wav", "size": len(audio), "audioBase64": base64.b64encode(audio).decode()}
