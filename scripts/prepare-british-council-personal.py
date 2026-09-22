#!/usr/bin/env python3
"""Build an external DictaLab import manifest for personal British Council lessons.

The downloaded MP3/PDF files and the generated manifest stay outside the JAR and
outside Git. Transcript sentences are aligned to the original MP3 with Whisper
word timestamps; no physical per-sentence audio files are created.
"""

from __future__ import annotations

import argparse
from collections import Counter
import importlib.util
import json
import math
import re
import sys
from pathlib import Path

DEFAULT_PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LIBRARY_ROOT = DEFAULT_PROJECT_ROOT / "data" / "faster-whisper-lib"
if DEFAULT_LIBRARY_ROOT.is_dir():
    sys.path.insert(0, str(DEFAULT_LIBRARY_ROOT))

import pdfplumber


WORD = re.compile(r"[A-Za-z0-9]+(?:[’'-][A-Za-z0-9]+)*")
SPEAKER = re.compile(r"^([^:\n]{1,40}):\s*(.*)$")
PAGE_NOISE = re.compile(r"^(?:©?\s*\d{4}\s+British Council|www\.britishcouncil\.org.*)$", re.I)
SHORT_FILLERS = {"ah", "er", "hmm", "mm", "oh", "uh", "um"}
COMMON_WORDS = {
    "about", "after", "again", "also", "another", "anything", "because", "before", "being",
    "better", "could", "didn't", "doesn't", "doing", "don't", "every", "going", "good", "great",
    "having", "here", "just", "know", "like", "little", "maybe", "more", "much", "need", "never",
    "other", "people", "really", "right", "should", "something", "still", "thanks", "that's", "their",
    "there", "these", "thing", "think", "those", "through", "today", "very", "want", "well", "what",
    "when", "where", "which", "while", "would", "you're", "your"
}


def arguments() -> argparse.Namespace:
    root = DEFAULT_PROJECT_ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=root)
    parser.add_argument("--source-root", type=Path, default=root / "downloads" / "british-council")
    parser.add_argument("--output", type=Path, default=root / "downloads" / "british-council" / "import-manifest.json")
    parser.add_argument("--whisper-library", type=Path, default=root / "data" / "faster-whisper-lib")
    parser.add_argument("--whisper-model", default="tiny.en")
    parser.add_argument("--whisper-download-root", type=Path, default=root / "data" / "whisper-models")
    parser.add_argument("--asr-cache", type=Path, default=root / "data" / "british-council-whisper-alignments.json")
    parser.add_argument("--ffmpeg", type=Path)
    parser.add_argument("--ffprobe", type=Path)
    parser.add_argument("--force-transcribe", action="store_true")
    parser.add_argument("--maximum-words", type=int, default=23)
    parser.add_argument("--minimum-words", type=int, default=5)
    parser.add_argument("--content-revision", default="v3")
    return parser.parse_args()


def load_alignment_module(project_root: Path):
    path = project_root / "scripts" / "split-elllo-sentences.py"
    spec = importlib.util.spec_from_file_location("dictalab_audio_alignment", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load alignment helpers from {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def normalize(value: str) -> str:
    value = value.replace("’", "'").replace("‘", "'")
    value = re.sub(r"(?<=[A-Za-z0-9])\uFFFD(?=[A-Za-z0-9])", "'", value)
    value = value.replace("\uFFFD", " - ")
    return re.sub(r"\s+", " ", value).strip()


def extract_transcript(pdf_path: Path) -> str:
    with pdfplumber.open(pdf_path) as document:
        text = "\n".join(page.extract_text() or "" for page in document.pages)
    start = re.search(r"(?im)^Transcript\s*$", text)
    if not start:
        raise ValueError(f"Transcript heading not found in {pdf_path.name}")
    remainder = text[start.end():]
    end = re.search(r"(?im)^Answers\s*$", remainder)
    if not end:
        raise ValueError(f"Answers heading not found after transcript in {pdf_path.name}")
    lines = []
    for raw in remainder[:end.start()].splitlines():
        if re.search(r"\d{4}\s+British Council", raw, re.I):
            continue
        line = normalize(raw)
        if line and not PAGE_NOISE.match(line):
            lines.append(line)
    transcript = "\n".join(lines)
    if len(WORD.findall(transcript)) < 10:
        raise ValueError(f"Transcript is unexpectedly short in {pdf_path.name}")
    return transcript


def chunks_by_word_count(text: str, maximum_words: int) -> list[str]:
    remaining = normalize(text)
    chunks = []
    while len(WORD.findall(remaining)) > maximum_words:
        matches = list(WORD.finditer(remaining))
        # Do not create a one- or two-word tail merely because the source
        # sentence is only slightly longer than the maximum.
        words_to_keep = maximum_words
        if len(matches) <= maximum_words + 2:
            words_to_keep = len(matches) - 3
        cut = matches[words_to_keep - 1].end()
        chunks.append(normalize(remaining[:cut]))
        remaining = normalize(remaining[cut:])
    if remaining:
        chunks.append(remaining)
    return chunks


def split_long_units(alignment, transcript: str, maximum_words: int):
    result = []
    for unit in alignment.transcript_units(transcript):
        normalized_words = [word.lower() for word in WORD.findall(unit.text)]
        if len(normalized_words) == 1 and normalized_words[0] in SHORT_FILLERS:
            continue
        if unit.word_count <= maximum_words:
            result.append(unit)
            continue
        clauses = [normalize(part) for part in re.split(r"(?<=[,;:])\s+", unit.text) if normalize(part)]
        if len(clauses) == 1:
            clauses = chunks_by_word_count(unit.text, maximum_words)
        else:
            expanded_clauses = []
            for clause in clauses:
                expanded_clauses.extend(chunks_by_word_count(clause, maximum_words))
            clauses = expanded_clauses
        chunks = []
        current = ""
        for clause in clauses:
            candidate = normalize(f"{current} {clause}")
            if current and len(WORD.findall(candidate)) > maximum_words:
                chunks.append(current)
                current = clause
            else:
                current = candidate
        if current:
            chunks.append(current)
        if len(chunks) > 1 and len(WORD.findall(chunks[-1])) < 3:
            chunks[-2:] = chunks_by_word_count(normalize(f"{chunks[-2]} {chunks[-1]}"), maximum_words)
        result.extend(alignment.SentenceUnit(unit.speaker, chunk, unit.turn_index) for chunk in chunks)
    return result


def merge_short_units(alignment, units, minimum_words: int, maximum_words: int):
    """Group short replies, but never attach them to a full sentence."""
    merged = []
    index = 0
    while index < len(units):
        current = units[index]
        if current.word_count >= minimum_words:
            merged.append(current)
            index += 1
            continue
        text = current.text
        next_index = index + 1
        while next_index < len(units) and units[next_index].word_count < minimum_words:
            candidate = normalize(f"{text} {units[next_index].text}")
            if len(WORD.findall(candidate)) > min(12, maximum_words):
                break
            text = candidate
            next_index += 1
        if next_index == index + 1:
            if next_index < len(units) and units[next_index].turn_index == current.turn_index:
                candidate = normalize(f"{text} {units[next_index].text}")
                if len(WORD.findall(candidate)) <= maximum_words:
                    merged.append(alignment.SentenceUnit(current.speaker, candidate, current.turn_index))
                    index = next_index + 1
                    continue
            if merged and merged[-1].turn_index == current.turn_index:
                candidate = normalize(f"{merged[-1].text} {text}")
                if len(WORD.findall(candidate)) <= maximum_words:
                    previous = merged[-1]
                    merged[-1] = alignment.SentenceUnit(previous.speaker, candidate, previous.turn_index)
                    index = next_index
                    continue
        merged.append(alignment.SentenceUnit(current.speaker, text, current.turn_index))
        index = next_index
    return merged


def select_preview_words(level: str, aligned, recognized) -> list[dict]:
    occurrences = []
    for sentence_index, sentence in enumerate(aligned):
        occurrences.extend(
            (word, sentence_index, word_index)
            for word_index, word in enumerate(WORD.findall(sentence.text))
        )
    expected = [word for word, _, _ in occurrences]
    normalized = [word.lower().replace("’", "'") for word in expected]
    recognized_by_word = {}
    for timing in recognized:
        key = normalize(timing.text).lower().strip(".,!?;:\"'")
        recognized_by_word.setdefault(key, []).append(timing)
    counts = Counter(normalized)
    target = {"A2": 3, "B1": 4, "B2": 5}[level]
    minimum_length = {"A2": 7, "B1": 8, "B2": 9}[level]
    suffixes = ("tion", "sion", "ment", "ance", "ence", "ity", "ous", "ive", "ology", "ism")

    candidates = []
    for index, (word, _, word_index) in enumerate(occurrences):
        value = normalized[index]
        if value not in recognized_by_word or value in COMMON_WORDS or len(value) < minimum_length - 2:
            continue
        if not value.isalpha() or (word_index > 0 and word[:1].isupper()):
            continue
        score = len(value) * 2 + (8 if counts[value] == 1 else 0)
        score += 5 if value.endswith(suffixes) else 0
        score -= max(0, minimum_length - len(value)) * 4
        candidates.append((score, index, value))

    selected = []
    seen = set()
    for _, _, word in sorted(candidates, reverse=True):
        if word in seen:
            continue
        timing = recognized_by_word[word][0]
        selected.append({
            "word": word,
            "audioStartSeconds": round(max(0.0, timing.start - 0.12), 3),
            "audioEndSeconds": round(timing.end + 0.18, 3),
        })
        seen.add(word)
        if len(selected) == target:
            break
    return selected


def lesson_catalog(source_root: Path) -> list[dict]:
    lessons: dict[tuple[str, str], dict] = {}
    for manifest_path in sorted(source_root.glob("*/*manifest*.json")):
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
        level = payload["level"]
        for lesson in payload["lessons"]:
            lessons[(level, lesson["slug"])] = lesson | {"level": level}
    return [lessons[key] for key in sorted(lessons)]


def load_cache(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    args = arguments()
    if args.minimum_words < 1 or args.maximum_words < args.minimum_words:
        raise ValueError("maximum-words must be at least minimum-words, and minimum-words must be positive")
    project_root = args.project_root.resolve()
    source_root = args.source_root.resolve()
    output = args.output.resolve()
    alignment = load_alignment_module(project_root)
    ffmpeg = alignment.resolve_executable(args.ffmpeg, "ffmpeg")
    ffprobe = alignment.resolve_executable(args.ffprobe, "ffprobe")
    transcriber = alignment.WhisperTranscriber(
        args.whisper_model, args.whisper_library, args.whisper_download_root
    )
    cache = {} if args.force_transcribe else load_cache(args.asr_cache)
    catalog = lesson_catalog(source_root)
    if len(catalog) != 36:
        raise ValueError(f"Expected 36 unique lessons, found {len(catalog)}")

    result = {
        "schemaVersion": 1,
        "contentOrigin": "BRITISH_COUNCIL_PERSONAL",
        "sourceName": "British Council LearnEnglish",
        "usage": "Personal study only",
        "lessons": [],
    }
    level_order = {"A2": 100, "B1": 100, "B2": 100}
    total_sentences = 0
    for lesson in catalog:
        level = lesson["level"]
        slug = lesson["slug"]
        audio_filename = lesson["files"]["audio"]["filename"]
        worksheet_filename = lesson["files"]["worksheet"]["filename"]
        audio_path = source_root / level / audio_filename
        worksheet_path = source_root / level / worksheet_filename
        transcript = extract_transcript(worksheet_path)
        units = split_long_units(alignment, transcript, args.maximum_words)
        units = merge_short_units(alignment, units, args.minimum_words, args.maximum_words)
        duration = alignment.media_duration(ffprobe, audio_path)
        silences = alignment.detect_silences(ffmpeg, audio_path, duration)
        cache_key = f"{level}/{audio_filename}"
        cached = cache.get(cache_key)
        if cached is None:
            recognized = transcriber.transcribe(audio_path)
            cache[cache_key] = [
                {"text": word.text, "start": word.start, "end": word.end}
                for word in recognized
            ]
            save_json(args.asr_cache, cache)
        else:
            recognized = [
                alignment.RecognizedWord(item["text"], float(item["start"]), float(item["end"]))
                for item in cached
            ]
        aligned, match_rate = alignment.align_sentences_with_asr(units, recognized, silences, duration)
        if not aligned:
            raise ValueError(f"No aligned sentences produced for {level}/{slug}")
        if any(item.end_seconds <= item.start_seconds for item in aligned):
            raise ValueError(f"Invalid timestamps produced for {level}/{slug}")
        if any(len(WORD.findall(item.text)) > args.maximum_words for item in aligned):
            raise ValueError(f"Sentence exceeds {args.maximum_words} words in {level}/{slug}")
        preview_words = select_preview_words(level, aligned, recognized)
        if len(preview_words) < 2:
            raise ValueError(f"Fewer than two preview words found in {level}/{slug}")

        level_order[level] += 1
        words = sum(len(WORD.findall(item.text)) for item in aligned)
        speech_seconds = sum(item.end_seconds - item.start_seconds for item in aligned)
        wpm = round(words * 60 / speech_seconds) if speech_seconds else 0
        speaker_names = {unit.speaker for unit in units if unit.speaker != "Speaker"}
        speaker_count = max(1, len(speaker_names))
        result["lessons"].append({
            "slug": f"british-council-personal-{level.lower()}-{slug}-{args.content_revision}",
            "title": lesson["title"],
            "description": f"British Council {level} listening practice (personal study).",
            "level": level,
            "topic": "British Council",
            "estimatedMinutes": max(1, math.ceil(duration / 60)),
            "displayOrder": level_order[level],
            "sourceUrl": lesson["sourceUrl"],
            "audioPath": f"{level}/{audio_filename}",
            "durationSeconds": round(duration, 3),
            "speechRateWpm": wpm,
            "speakerCount": speaker_count,
            "accentProfile": "British and international English",
            "vocabularyDifficulty": {"A2": 2, "B1": 3, "B2": 4}[level],
            "grammarDifficulty": {"A2": 2, "B1": 3, "B2": 4}[level],
            "sentenceDifficulty": {"A2": 2, "B1": 3, "B2": 4}[level],
            "assessmentCalibrated": False,
            "alignmentMatchRate": round(match_rate, 4),
            "previewWords": preview_words,
            "sentences": [
                {
                    "position": index,
                    "transcript": item.text,
                    "audioStartSeconds": item.start_seconds,
                    "audioEndSeconds": item.end_seconds,
                    "speechRate": 1.0,
                }
                for index, item in enumerate(aligned, start=1)
            ],
        })
        total_sentences += len(aligned)
        print(f"[{len(result['lessons']):02d}/36] {level} {slug}: {len(aligned)} sentences, match={match_rate:.1%}", flush=True)

    save_json(output, result)
    print(f"Wrote {output} with 36 lessons and {total_sentences} sentences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
