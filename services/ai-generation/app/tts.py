"""Bounded Cartesia WAV transport. Audio is returned to Java, never a public URL."""
import base64
import io
import time
import wave
from typing import Literal

from pydantic import Field, field_validator

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
    body = {"model_id": settings.cartesia_model, "transcript": payload.text, "language": "en",
            "voice": {"mode": "id", "id": settings.cartesia_voice},
            "output_format": {"container": "wav", "encoding": "pcm_s16le", "sample_rate": 24000}}
    timeout = min(45, deadline - time.monotonic())
    if timeout <= 0:
        raise TimeoutError("TTS deadline exhausted")
    async with client.stream("POST", settings.cartesia_url, json=body, timeout=timeout,
            headers={"Authorization": "Bearer " + settings.cartesia_api_key,
                     "Cartesia-Version": settings.cartesia_version}) as response:
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
