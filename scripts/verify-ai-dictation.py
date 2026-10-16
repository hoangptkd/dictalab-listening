"""Check generated WAV/segment integrity and compare offline ASR with reviewed text.

Uses the existing faster-whisper library/model and alignment implementation.
Does not call external APIs, publish content, or access application credentials.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import struct
import sys
import wave


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--library", type=Path, default=Path("data/faster-whisper-lib"))
    parser.add_argument("--models", type=Path, default=Path("data/whisper-models"))
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("dictalab_audio_alignment", Path(__file__).with_name("split-elllo-sentences.py"))
    alignment = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = alignment
    spec.loader.exec_module(alignment)
    transcriber = alignment.WhisperTranscriber("tiny.en", args.library, args.models)
    manifest = json.loads((args.directory / "manifest.json").read_text(encoding="utf-8"))
    results = []
    for lesson in manifest["lessons"]:
        audio = args.directory / lesson["audioFile"]
        assert hashlib.sha256(audio.read_bytes()).hexdigest() == lesson["sha256"], "Audio hash mismatch"
        with wave.open(str(audio), "rb") as source:
            assert source.getparams()[:3] == (1, 2, 24000), "Unexpected WAV format"
            assert math.isclose(source.getnframes() / source.getframerate(), lesson["durationSeconds"], abs_tol=1/24000)
            previous = 0
            for sentence in lesson["sentences"]:
                start, end = sentence["audioStartSeconds"], sentence["audioEndSeconds"]
                assert start >= previous and end > start and end <= lesson["durationSeconds"], "Segment overlap/out of bounds"
                source.setpos(round(start * 24000))
                samples = struct.unpack("<" + "h" * round((end-start)*24000), source.readframes(round((end-start)*24000)))
                rms = math.sqrt(sum(sample*sample for sample in samples)/len(samples))/32768
                assert rms > 0.002, "Silent segment"
                previous = end
        expected = [word for sentence in lesson["sentences"] for word in alignment.WORD.findall(sentence["text"])]
        recognized = transcriber.transcribe(audio)
        mapping, match_rate = alignment.align_word_sequences(expected, recognized)
        item = {"slug":lesson["slug"], "durationSeconds":lesson["durationSeconds"], "wordCount":len(expected),
                "alignmentMatchRate":round(match_rate,4), "unmatchedExpectedWords":[word for word,index in zip(expected,mapping) if index is None],
                "recognizedText":" ".join(word.text for word in recognized)}
        results.append(item)
        print(json.dumps(item,ensure_ascii=False),flush=True)
    report = {"method":"Offline faster-whisper tiny.en and existing token sequence alignment; not a human listening review or CEFR calibration", "lessons":results}
    (args.directory / "audio-check.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    assert all(item["alignmentMatchRate"] >= 0.90 for item in results), "ASR discrepancy needs review before publishing"
    print("AUDIO_CHECK_OK: hashes, PCM, timing, non-silent sentences, ASR alignment")


if __name__ == "__main__":
    main()
