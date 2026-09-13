#!/usr/bin/env python3
"""Align ELLLO transcript sentences with their conversation recordings.

The ELLLO Sound Grammar files contain a complete conversation in each MP3. This
script finds pauses with FFmpeg, aligns them with the speaker turns and
punctuation in the supplied transcript, and writes reusable start/end cues.
It does not create one physical MP3 per sentence.
"""

from __future__ import annotations

import argparse
import difflib
import json
import math
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


SPEAKER_LINE = re.compile(r"^([^:\n]{1,40}):\s*(.*)$")
WORD = re.compile(r"[A-Za-z0-9]+(?:[’'-][A-Za-z0-9]+)*")
COMMON_ABBREVIATIONS = {
    "dr",
    "jr",
    "mr",
    "mrs",
    "ms",
    "prof",
    "sr",
    "st",
    "vs",
}
LEVELS = ("A1", "A2", "B1", "B2")


@dataclass(frozen=True)
class Silence:
    start: float
    end: float

    @property
    def duration(self) -> float:
        return self.end - self.start

    @property
    def midpoint(self) -> float:
        return (self.start + self.end) / 2.0


@dataclass(frozen=True)
class SentenceUnit:
    speaker: str
    text: str
    turn_index: int

    @property
    def word_count(self) -> int:
        return len(WORD.findall(self.text))

    @property
    def timing_weight(self) -> float:
        # A fixed phrase cost prevents short reactions such as "Really?" from
        # being assigned an unrealistically tiny slice of audio.
        letters = sum(character.isalpha() or character.isdigit() for character in self.text)
        return 1.25 + self.word_count * 0.20 + letters * 0.035

    @property
    def speech_weight(self) -> float:
        letters = sum(character.isalpha() or character.isdigit() for character in self.text)
        return 6.0 + self.word_count * 2.0 + letters

    @property
    def minimum_speech_seconds(self) -> float:
        letters = sum(character.isalpha() or character.isdigit() for character in self.text)
        return max(0.12, letters * 0.040)


@dataclass(frozen=True)
class CandidateBoundary:
    time: float
    silence_duration: float


@dataclass(frozen=True)
class AlignedSentence:
    speaker: str
    text: str
    start_seconds: float
    end_seconds: float
    boundary_pause_seconds: float | None

    @property
    def duration_seconds(self) -> float:
        return self.end_seconds - self.start_seconds


@dataclass(frozen=True)
class RecognizedWord:
    text: str
    start: float
    end: float


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    default_root = Path(__file__).resolve().parents[1]
    parser.add_argument("--project-root", type=Path, default=default_root)
    parser.add_argument("--ffmpeg", type=Path)
    parser.add_argument("--ffprobe", type=Path)
    parser.add_argument(
        "--analyze-only",
        action="store_true",
        help="calculate boundaries and print validation without creating output files",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="replace audio/elllo/segments.json if it already exists",
    )
    parser.add_argument(
        "--whisper-model",
        default="tiny.en",
        help="faster-whisper model used for word timestamps (default: tiny.en)",
    )
    parser.add_argument(
        "--whisper-library",
        type=Path,
        help="directory containing an optional faster-whisper installation",
    )
    parser.add_argument(
        "--whisper-download-root",
        type=Path,
        help="directory used to cache the selected Whisper model",
    )
    parser.add_argument(
        "--asr-cache",
        type=Path,
        help="JSON cache for word timestamps; defaults to data/elllo-whisper-alignments.json",
    )
    return parser.parse_args()


def resolve_executable(explicit: Path | None, name: str) -> Path:
    if explicit:
        path = explicit.expanduser().resolve()
        if path.is_file():
            return path
        raise FileNotFoundError(f"Cannot find {name}: {path}")

    discovered = shutil.which(name)
    if discovered:
        return Path(discovered)

    local_app_data = Path.home() / "AppData" / "Local"
    matches = sorted(local_app_data.glob(f"Microsoft/WinGet/Packages/**/{name}.exe"))
    if matches:
        return matches[-1]
    raise FileNotFoundError(f"{name} is required but was not found")


def run(command: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def media_duration(ffprobe: Path, audio_path: Path) -> float:
    result = run(
        (
            str(ffprobe),
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        )
    )
    return float(result.stdout.strip())


def normalize_whitespace(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def parse_turns(transcript: str) -> list[tuple[str, str]]:
    turns: list[tuple[str, list[str]]] = []
    for raw_line in transcript.splitlines():
        line = normalize_whitespace(raw_line)
        if not line:
            continue
        match = SPEAKER_LINE.match(line)
        if match:
            turns.append((match.group(1).strip(), [match.group(2).strip()]))
        elif turns:
            turns[-1][1].append(line)
        else:
            turns.append(("Speaker", [line]))

    return [(speaker, normalize_whitespace(" ".join(parts))) for speaker, parts in turns]


def is_sentence_period(text: str, index: int) -> bool:
    before = text[:index]
    previous_match = re.search(r"([A-Za-z]+)$", before)
    previous = previous_match.group(1).lower() if previous_match else ""
    if previous in COMMON_ABBREVIATIONS:
        return False
    if index > 0 and index + 1 < len(text) and text[index - 1].isdigit() and text[index + 1].isdigit():
        return False
    return True


def split_sentences(text: str) -> list[str]:
    text = normalize_whitespace(text)
    if not text:
        return []

    sentences: list[str] = []
    start = 0
    index = 0
    while index < len(text):
        character = text[index]
        is_terminal = character in "!?" or (character == "." and is_sentence_period(text, index))
        if not is_terminal:
            index += 1
            continue

        end = index + 1
        while end < len(text) and text[end] in "!?.”’\"'":
            end += 1
        if end == len(text) or text[end].isspace():
            sentence = normalize_whitespace(text[start:end])
            if sentence:
                sentences.append(sentence)
            start = end
            while start < len(text) and text[start].isspace():
                start += 1
            index = start
        else:
            index += 1

    remainder = normalize_whitespace(text[start:])
    if remainder:
        sentences.append(remainder)
    return sentences


def transcript_units(transcript: str) -> list[SentenceUnit]:
    units: list[SentenceUnit] = []
    for turn_index, (speaker, text) in enumerate(parse_turns(transcript)):
        parts = split_sentences(text) or [text]
        units.extend(SentenceUnit(speaker, part, turn_index) for part in parts if part)
    if not units:
        raise ValueError("Transcript does not contain any spoken text")
    return units


def detect_silences(ffmpeg: Path, audio_path: Path, duration: float) -> list[Silence]:
    # A short minimum catches punctuation pauses while the conservative noise
    # threshold avoids treating softly spoken words as silence.
    filters = ("silencedetect=noise=-35dB:d=0.055",)
    detected: list[Silence] = []
    for audio_filter in filters:
        result = subprocess.run(
            (
                str(ffmpeg),
                "-hide_banner",
                "-nostats",
                "-i",
                str(audio_path),
                "-af",
                audio_filter,
                "-f",
                "null",
                "NUL" if sys.platform == "win32" else "/dev/null",
            ),
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        pending_start: float | None = None
        for line in result.stderr.splitlines():
            start_match = re.search(r"silence_start:\s*([0-9.]+)", line)
            if start_match:
                pending_start = float(start_match.group(1))
            end_match = re.search(
                r"silence_end:\s*([0-9.]+)\s*\|\s*silence_duration:\s*([0-9.]+)",
                line,
            )
            if end_match:
                end = min(duration, float(end_match.group(1)))
                silence_length = float(end_match.group(2))
                start = pending_start if pending_start is not None else max(0.0, end - silence_length)
                if end > start:
                    detected.append(Silence(max(0.0, start), end))
                pending_start = None

    return merge_similar_silences(detected)


def merge_similar_silences(silences: Iterable[Silence]) -> list[Silence]:
    ordered = sorted(silences, key=lambda silence: silence.midpoint)
    merged: list[Silence] = []
    for silence in ordered:
        if merged and abs(merged[-1].midpoint - silence.midpoint) <= 0.14:
            current = merged[-1]
            # Prefer the wider interval, because it is less likely to be an
            # unvoiced consonant incorrectly identified as a pause.
            if silence.duration > current.duration:
                merged[-1] = silence
        else:
            merged.append(silence)
    return merged


def boundary_candidates(silences: Sequence[Silence], duration: float) -> list[CandidateBoundary]:
    return [
        CandidateBoundary(silence.midpoint, silence.duration)
        for silence in silences
        if silence.start > 0.12 and silence.end < duration - 0.12
    ]


def active_speech_seconds(start: float, end: float, silences: Sequence[Silence]) -> float:
    overlaps: list[tuple[float, float]] = []
    for silence in silences:
        overlap_start = max(start, silence.start)
        overlap_end = min(end, silence.end)
        if overlap_end > overlap_start:
            overlaps.append((overlap_start, overlap_end))
    if not overlaps:
        return end - start

    overlaps.sort()
    silent_seconds = 0.0
    current_start, current_end = overlaps[0]
    for overlap_start, overlap_end in overlaps[1:]:
        if overlap_start <= current_end:
            current_end = max(current_end, overlap_end)
        else:
            silent_seconds += current_end - current_start
            current_start, current_end = overlap_start, overlap_end
    silent_seconds += current_end - current_start
    return max(0.0, end - start - silent_seconds)


def align_boundaries(
    units: Sequence[SentenceUnit],
    candidates: Sequence[CandidateBoundary],
    silences: Sequence[Silence],
    duration: float,
) -> list[CandidateBoundary]:
    required = len(units) - 1
    if required == 0:
        return []
    if len(candidates) < required:
        raise ValueError(f"only {len(candidates)} pauses for {len(units)} transcript sentences")

    weights = [unit.timing_weight for unit in units]
    total_weight = sum(weights)
    speech_weights = [unit.speech_weight for unit in units]
    total_speech_weight = sum(speech_weights)
    total_active_speech = active_speech_seconds(0.0, duration, silences)
    expected_active_speech = [
        total_active_speech * speech_weight / total_speech_weight
        for speech_weight in speech_weights
    ]
    cumulative = 0.0
    expected: list[float] = []
    for weight in weights[:-1]:
        cumulative += weight
        expected.append(duration * cumulative / total_weight)

    average_segment = duration / len(units)
    infinity = float("inf")
    previous_costs = [infinity] * len(candidates)
    parents: list[list[int]] = [[-1] * len(candidates) for _ in range(required)]

    def speech_cost(unit_index: int, start: float, end: float) -> float:
        active = active_speech_seconds(start, end, silences)
        unit = units[unit_index]
        if active < unit.minimum_speech_seconds:
            return infinity
        expected_active = expected_active_speech[unit_index]
        scale = max(0.35, expected_active * 0.85)
        return ((active - expected_active) / scale) ** 2 * 0.65

    for boundary_index in range(required):
        current_costs = [infinity] * len(candidates)
        is_turn_boundary = units[boundary_index].turn_index != units[boundary_index + 1].turn_index
        remaining_boundaries = required - boundary_index - 1

        for candidate_index, candidate in enumerate(candidates):
            if candidate_index < boundary_index:
                continue
            if len(candidates) - candidate_index - 1 < remaining_boundaries:
                continue

            scale = max(0.75, average_segment * (1.35 if is_turn_boundary else 1.0))
            position_cost = ((candidate.time - expected[boundary_index]) / scale) ** 2
            pause_cap = 0.9 if is_turn_boundary else 0.55
            pause_reward = min(candidate.silence_duration, pause_cap) * (1.75 if is_turn_boundary else 0.65)
            short_turn_penalty = (
                max(0.0, 0.16 - candidate.silence_duration) * 8.0 if is_turn_boundary else 0.0
            )
            local_cost = position_cost - pause_reward + short_turn_penalty

            if boundary_index == 0:
                if candidate.time < 0.35:
                    continue
                previous_cost = speech_cost(0, 0.0, candidate.time)
                previous_index = -1
                previous_time = 0.0
            else:
                previous_cost = infinity
                previous_index = -1
                previous_time = 0.0
                for possible_parent in range(boundary_index - 1, candidate_index):
                    cost = previous_costs[possible_parent]
                    if not math.isfinite(cost):
                        continue
                    parent_time = candidates[possible_parent].time
                    segment_duration = candidate.time - parent_time
                    if segment_duration < 0.30:
                        continue
                    transcript_audio_cost = speech_cost(boundary_index, parent_time, candidate.time)
                    if not math.isfinite(transcript_audio_cost):
                        continue
                    expected_segment = expected[boundary_index] - expected[boundary_index - 1]
                    rhythm_cost = ((segment_duration - expected_segment) / max(0.9, expected_segment * 1.8)) ** 2
                    total = cost + rhythm_cost * 0.25 + transcript_audio_cost
                    if total < previous_cost:
                        previous_cost = total
                        previous_index = possible_parent
                        previous_time = parent_time
                if not math.isfinite(previous_cost):
                    continue

            if candidate.time - previous_time < 0.30:
                continue
            if boundary_index == required - 1 and duration - candidate.time < 0.30:
                continue
            current_costs[candidate_index] = previous_cost + local_cost
            parents[boundary_index][candidate_index] = previous_index

        previous_costs = current_costs

    final_costs = []
    for candidate_index, candidate in enumerate(candidates):
        tail_cost = speech_cost(len(units) - 1, candidate.time, duration)
        final_costs.append(previous_costs[candidate_index] + tail_cost)
    best_index = min(range(len(candidates)), key=final_costs.__getitem__)
    if not math.isfinite(final_costs[best_index]):
        raise ValueError("could not build a monotonic pause alignment")

    selected: list[CandidateBoundary] = []
    candidate_index = best_index
    for boundary_index in range(required - 1, -1, -1):
        selected.append(candidates[candidate_index])
        candidate_index = parents[boundary_index][candidate_index]
    selected.reverse()
    return selected


def align_sentences(
    units: Sequence[SentenceUnit],
    boundaries: Sequence[CandidateBoundary],
    duration: float,
) -> list[AlignedSentence]:
    cut_times = [0.0, *(boundary.time for boundary in boundaries), duration]
    aligned: list[AlignedSentence] = []
    for index, unit in enumerate(units):
        pause = boundaries[index].silence_duration if index < len(boundaries) else None
        aligned.append(
            AlignedSentence(
                speaker=unit.speaker,
                text=unit.text,
                start_seconds=round(cut_times[index], 3),
                end_seconds=round(cut_times[index + 1], 3),
                boundary_pause_seconds=round(pause, 3) if pause is not None else None,
            )
        )
    return aligned


def normalized_word(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower().replace("’", "'"))


def align_word_sequences(
    expected: Sequence[str], recognized: Sequence[RecognizedWord]
) -> tuple[list[int | None], float]:
    expected_normalized = [normalized_word(word) for word in expected]
    recognized_normalized = [normalized_word(word.text) for word in recognized]
    expected_count = len(expected_normalized)
    recognized_count = len(recognized_normalized)

    costs = [[0.0] * (recognized_count + 1) for _ in range(expected_count + 1)]
    operations = [[""] * (recognized_count + 1) for _ in range(expected_count + 1)]
    for expected_index in range(1, expected_count + 1):
        costs[expected_index][0] = float(expected_index)
        operations[expected_index][0] = "delete"
    for recognized_index in range(1, recognized_count + 1):
        costs[0][recognized_index] = float(recognized_index)
        operations[0][recognized_index] = "insert"

    for expected_index in range(1, expected_count + 1):
        for recognized_index in range(1, recognized_count + 1):
            expected_word = expected_normalized[expected_index - 1]
            recognized_word = recognized_normalized[recognized_index - 1]
            similarity = difflib.SequenceMatcher(None, expected_word, recognized_word).ratio()
            substitution_cost = 0.0 if expected_word == recognized_word else 1.10 - similarity * 0.35
            choices = (
                (
                    costs[expected_index - 1][recognized_index - 1] + substitution_cost,
                    "diagonal",
                ),
                (costs[expected_index - 1][recognized_index] + 1.0, "delete"),
                (costs[expected_index][recognized_index - 1] + 1.0, "insert"),
            )
            costs[expected_index][recognized_index], operations[expected_index][recognized_index] = min(
                choices, key=lambda choice: choice[0]
            )

    mapping: list[int | None] = [None] * expected_count
    strong_matches = 0
    expected_index = expected_count
    recognized_index = recognized_count
    while expected_index > 0 or recognized_index > 0:
        operation = operations[expected_index][recognized_index]
        if operation == "diagonal":
            expected_word = expected_normalized[expected_index - 1]
            recognized_word = recognized_normalized[recognized_index - 1]
            similarity = difflib.SequenceMatcher(None, expected_word, recognized_word).ratio()
            if similarity >= 0.45:
                mapping[expected_index - 1] = recognized_index - 1
            if similarity >= 0.72:
                strong_matches += 1
            expected_index -= 1
            recognized_index -= 1
        elif operation == "delete":
            expected_index -= 1
        elif operation == "insert":
            recognized_index -= 1
        else:
            raise ValueError("ASR word alignment backtracking failed")

    match_rate = strong_matches / expected_count if expected_count else 0.0
    return mapping, match_rate


def best_pause_between(
    minimum: float,
    maximum: float,
    silences: Sequence[Silence],
) -> CandidateBoundary:
    if maximum < minimum:
        raise ValueError(
            f"ASR word timestamps overlap at a sentence boundary: {minimum:.3f} > {maximum:.3f}"
        )
    choices = [
        (
            min(silence.end, maximum) - max(silence.start, minimum),
            silence,
        )
        for silence in silences
        if min(silence.end, maximum) > max(silence.start, minimum)
    ]
    if choices:
        overlap, silence = max(choices, key=lambda item: item[0])
        overlap_start = max(silence.start, minimum)
        overlap_end = min(silence.end, maximum)
        return CandidateBoundary((overlap_start + overlap_end) / 2.0, overlap)
    return CandidateBoundary((minimum + maximum) / 2.0, max(0.0, maximum - minimum))


def align_sentences_with_asr(
    units: Sequence[SentenceUnit],
    recognized: Sequence[RecognizedWord],
    silences: Sequence[Silence],
    duration: float,
) -> tuple[list[AlignedSentence], float]:
    expected_words: list[str] = []
    word_ranges: list[tuple[int, int]] = []
    for unit in units:
        start = len(expected_words)
        expected_words.extend(WORD.findall(unit.text))
        word_ranges.append((start, len(expected_words)))

    mapping, match_rate = align_word_sequences(expected_words, recognized)
    if match_rate < 0.68:
        raise ValueError(f"ASR matched only {match_rate:.1%} of transcript words")

    sentence_recognized_ranges: list[tuple[int, int] | None] = []
    for start, end in word_ranges:
        indices = [index for index in mapping[start:end] if index is not None]
        sentence_recognized_ranges.append((min(indices), max(indices)) if indices else None)

    matched_sentence_indices = [
        index for index, recognized_range in enumerate(sentence_recognized_ranges)
        if recognized_range is not None
    ]
    if not matched_sentence_indices:
        raise ValueError("ASR could not timestamp any transcript sentence")

    # ELLLO occasionally prints a short interjection that is not present in the
    # MP3. If Whisper found no corresponding word and the surrounding interval
    # is also silent, omit that transcript-only sentence instead of generating
    # an impossible dictation exercise.
    dropped_indices: set[int] = set()
    sentence_index = 0
    while sentence_index < len(units):
        if sentence_recognized_ranges[sentence_index] is not None:
            sentence_index += 1
            continue
        missing_start = sentence_index
        while (
            sentence_index < len(units)
            and sentence_recognized_ranges[sentence_index] is None
        ):
            sentence_index += 1
        missing_end = sentence_index - 1
        if missing_start == 0 or missing_end == len(units) - 1:
            dropped_indices.update(range(missing_start, missing_end + 1))
            continue
        left_range = sentence_recognized_ranges[missing_start - 1]
        right_range = sentence_recognized_ranges[missing_end + 1]
        assert left_range is not None and right_range is not None
        gap_start = recognized[left_range[1]].end
        gap_end = recognized[right_range[0]].start
        active_gap = active_speech_seconds(gap_start, gap_end, silences)
        required_speech = sum(
            units[index].minimum_speech_seconds
            for index in range(missing_start, missing_end + 1)
        )
        if active_gap < required_speech * 0.75:
            dropped_indices.update(range(missing_start, missing_end + 1))

    if dropped_indices:
        filtered_units = [unit for index, unit in enumerate(units) if index not in dropped_indices]
        return align_sentences_with_asr(filtered_units, recognized, silences, duration)

    if matched_sentence_indices[0] != 0 or matched_sentence_indices[-1] != len(units) - 1:
        raise ValueError("ASR missed the first or last transcript sentence")

    boundary_overrides: dict[int, CandidateBoundary] = {}
    sentence_index = 0
    while sentence_index < len(units):
        if sentence_recognized_ranges[sentence_index] is not None:
            sentence_index += 1
            continue
        missing_start = sentence_index
        while (
            sentence_index < len(units)
            and sentence_recognized_ranges[sentence_index] is None
        ):
            sentence_index += 1
        missing_end = sentence_index - 1
        left_sentence = missing_start - 1
        right_sentence = missing_end + 1
        left_range = sentence_recognized_ranges[left_sentence]
        right_range = sentence_recognized_ranges[right_sentence]
        assert left_range is not None and right_range is not None
        gap_start = recognized[left_range[1]].end
        gap_end = recognized[right_range[0]].start
        number_missing = missing_end - missing_start + 1
        available_pauses = [
            CandidateBoundary(silence.midpoint, silence.duration)
            for silence in silences
            if silence.midpoint > gap_start and silence.midpoint < gap_end
        ]
        previous_time = gap_start
        for offset in range(number_missing + 1):
            expected_time = gap_start + (gap_end - gap_start) * (offset + 0.5) / (number_missing + 1)
            eligible = [
                pause for pause in available_pauses
                if pause.time > previous_time + 0.04
            ]
            remaining = number_missing - offset
            eligible = [
                pause for pause in eligible
                if sum(other.time > pause.time + 0.04 for other in available_pauses) >= remaining
            ]
            if eligible:
                chosen = min(
                    eligible,
                    key=lambda pause: abs(pause.time - expected_time) - min(pause.silence_duration, 0.5) * 0.25,
                )
            else:
                chosen = CandidateBoundary(expected_time, 0.0)
            boundary_overrides[left_sentence + offset] = chosen
            previous_time = chosen.time

    first_range = sentence_recognized_ranges[0]
    assert first_range is not None
    first_recognized_index = first_range[0]
    first_word = recognized[first_recognized_index]
    clip_start = max(0.0, first_word.start - 0.18)
    if first_recognized_index > 0:
        preceding_word = recognized[first_recognized_index - 1]
        clip_start = max(clip_start, (preceding_word.end + first_word.start) / 2.0)

    boundaries: list[CandidateBoundary] = []
    for sentence_index in range(len(units) - 1):
        if sentence_index in boundary_overrides:
            boundaries.append(boundary_overrides[sentence_index])
            continue
        current_range = sentence_recognized_ranges[sentence_index]
        next_range = sentence_recognized_ranges[sentence_index + 1]
        assert current_range is not None and next_range is not None
        current_last = recognized[current_range[1]]
        next_first = recognized[next_range[0]]
        boundaries.append(best_pause_between(current_last.end, next_first.start, silences))

    last_range = sentence_recognized_ranges[-1]
    assert last_range is not None
    last_recognized_index = last_range[1]
    last_word = recognized[last_recognized_index]
    clip_end = min(duration, last_word.end + 0.22)
    if last_recognized_index + 1 < len(recognized):
        following_word = recognized[last_recognized_index + 1]
        clip_end = min(clip_end, (last_word.end + following_word.start) / 2.0)

    cut_times = [clip_start, *(boundary.time for boundary in boundaries), clip_end]
    aligned: list[AlignedSentence] = []
    for sentence_index, unit in enumerate(units):
        start = cut_times[sentence_index]
        end = cut_times[sentence_index + 1]
        if end - start < 0.25:
            raise ValueError(f"ASR produced a clip shorter than 0.25 seconds for: {unit.text}")
        pause = boundaries[sentence_index].silence_duration if sentence_index < len(boundaries) else None
        aligned.append(
            AlignedSentence(
                speaker=unit.speaker,
                text=unit.text,
                start_seconds=round(start, 3),
                end_seconds=round(end, 3),
                boundary_pause_seconds=round(pause, 3) if pause is not None else None,
            )
        )
    return aligned, match_rate


class WhisperTranscriber:
    def __init__(self, model_name: str, library: Path | None, download_root: Path | None):
        if library:
            sys.path.insert(0, str(library.resolve()))
        try:
            from faster_whisper import WhisperModel
        except ImportError as error:
            raise RuntimeError(
                "faster-whisper is not installed; provide --whisper-library or omit --whisper-model"
            ) from error

        self.model = WhisperModel(
            model_name,
            device="cpu",
            compute_type="int8",
            download_root=str(download_root.resolve()) if download_root else None,
        )

    def transcribe(self, audio_path: Path) -> list[RecognizedWord]:
        segments, _ = self.model.transcribe(
            str(audio_path),
            language="en",
            beam_size=5,
            word_timestamps=True,
            vad_filter=False,
            condition_on_previous_text=False,
        )
        words: list[RecognizedWord] = []
        for segment in segments:
            for word in segment.words or ():
                text = normalize_whitespace(word.word)
                if text:
                    words.append(RecognizedWord(text, float(word.start), float(word.end)))
        if not words:
            raise ValueError(f"Whisper returned no words for {audio_path}")
        return words


def process_manifest(
    project_root: Path,
    ffmpeg: Path,
    ffprobe: Path,
    transcriber: WhisperTranscriber | None,
    asr_cache: dict[str, list[dict]],
) -> dict:
    static_root = project_root / "src" / "main" / "resources" / "static"
    manifest_path = static_root / "audio" / "elllo" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    result = {
        "sourceManifest": "/audio/elllo/manifest.json",
        "generator": "scripts/split-elllo-sentences.py",
        "lessons": [],
    }
    level_orders = {level: 0 for level in LEVELS}
    lesson_database_id = 29
    total_source_duration = 0.0
    total_output_duration = 0.0

    for lesson in manifest["lessons"]:
        level = lesson["level"]
        level_orders[level] += 1
        lesson_result = {
            "databaseId": lesson_database_id,
            "sourceId": lesson["id"],
            "slug": f"elllo-sentences-{lesson['id'].lower()}",
            "title": lesson["title"],
            "level": level,
            "displayOrder": level_orders[level],
            "sourcePage": lesson["sourcePage"],
            "sentences": [],
        }
        position = 1
        lesson_duration = 0.0

        for conversation in lesson["conversations"]:
            relative_source = conversation["localAudioPath"].removeprefix("/")
            source_path = static_root / Path(relative_source)
            if not source_path.is_file():
                raise FileNotFoundError(f"Missing source audio: {source_path}")
            duration = media_duration(ffprobe, source_path)
            units = transcript_units(conversation["transcript"])
            silences = detect_silences(ffmpeg, source_path, duration)
            candidates = boundary_candidates(silences, duration)
            alignment_mode = "silence"
            asr_match_rate: float | None = None
            try:
                if transcriber:
                    cache_key = relative_source.replace("\\", "/")
                    cached_words = asr_cache.get(cache_key)
                    if cached_words is None:
                        recognized = transcriber.transcribe(source_path)
                        asr_cache[cache_key] = [
                            {"text": word.text, "start": word.start, "end": word.end}
                            for word in recognized
                        ]
                    else:
                        recognized = [
                            RecognizedWord(item["text"], float(item["start"]), float(item["end"]))
                            for item in cached_words
                        ]
                    aligned, asr_match_rate = align_sentences_with_asr(
                        units, recognized, silences, duration
                    )
                    alignment_mode = "whisper-word-timestamps"
                else:
                    boundaries = align_boundaries(units, candidates, silences, duration)
                    aligned = align_sentences(units, boundaries, duration)
            except ValueError as error:
                raise ValueError(
                    f"{lesson['id']} conversation {conversation['number']}: {error}"
                ) from error

            for sentence_number, sentence in enumerate(aligned, start=1):
                lesson_result["sentences"].append(
                    {
                        "position": position,
                        "conversation": int(conversation["number"]),
                        "sentenceInConversation": sentence_number,
                        "speaker": sentence.speaker,
                        "text": sentence.text,
                        "wordCount": len(WORD.findall(sentence.text)),
                        "startSeconds": sentence.start_seconds,
                        "endSeconds": sentence.end_seconds,
                        "durationSeconds": round(sentence.duration_seconds, 3),
                        "boundaryPauseSeconds": sentence.boundary_pause_seconds,
                        "alignmentMode": alignment_mode,
                        "asrMatchRate": round(asr_match_rate, 4) if asr_match_rate is not None else None,
                        "sourceAudioPath": conversation["localAudioPath"],
                    }
                )
                position += 1
                lesson_duration += sentence.duration_seconds

            total_source_duration += duration

        lesson_result["durationSeconds"] = round(lesson_duration, 3)
        lesson_result["estimatedMinutes"] = max(8, math.ceil(lesson_duration / 60.0 * 5.0))
        total_output_duration += lesson_duration
        result["lessons"].append(lesson_result)
        lesson_database_id += 1

    result["summary"] = {
        "lessonCount": len(result["lessons"]),
        "sentenceCount": sum(len(lesson["sentences"]) for lesson in result["lessons"]),
        "sourceDurationSeconds": round(total_source_duration, 3),
        "outputDurationSeconds": round(total_output_duration, 3),
    }
    return result


def validate_result(result: dict, static_root: Path) -> None:
    sentences = [sentence for lesson in result["lessons"] for sentence in lesson["sentences"]]
    problems: list[str] = []
    for sentence in sentences:
        if sentence["wordCount"] == 0:
            problems.append(f"no words: {sentence['sourceAudioPath']}")
        if sentence["durationSeconds"] < 0.30:
            problems.append(f"segment shorter than 0.30 seconds: {sentence['sourceAudioPath']}")
        if sentence["wordCount"] > 24:
            problems.append(
                f"more than 24 words ({sentence['wordCount']}): {sentence['text']}"
            )
        audio_path = static_root / sentence["sourceAudioPath"].removeprefix("/")
        if not audio_path.is_file() or audio_path.stat().st_size == 0:
            problems.append(f"missing source audio: {audio_path}")
    if problems:
        preview = "\n".join(f"- {problem}" for problem in problems[:30])
        extra = f"\n... and {len(problems) - 30} more" if len(problems) > 30 else ""
        raise ValueError(f"Segmentation validation failed:\n{preview}{extra}")


def print_summary(result: dict) -> None:
    all_sentences = [sentence for lesson in result["lessons"] for sentence in lesson["sentences"]]
    print(
        f"Prepared {result['summary']['lessonCount']} lessons and "
        f"{result['summary']['sentenceCount']} sentence cues."
    )
    print("Level  Lessons  Sentences  Min words  Avg words  Max words  Avg seconds")
    for level in LEVELS:
        lessons = [lesson for lesson in result["lessons"] if lesson["level"] == level]
        sentences = [sentence for lesson in lessons for sentence in lesson["sentences"]]
        words = [sentence["wordCount"] for sentence in sentences]
        durations = [sentence["durationSeconds"] for sentence in sentences]
        print(
            f"{level:<5}  {len(lessons):>7}  {len(sentences):>9}  {min(words):>9}  "
            f"{sum(words) / len(words):>9.1f}  {max(words):>9}  "
            f"{sum(durations) / len(durations):>11.1f}"
        )


def main() -> int:
    args = parse_arguments()
    project_root = args.project_root.resolve()
    ffmpeg = resolve_executable(args.ffmpeg, "ffmpeg")
    ffprobe = resolve_executable(args.ffprobe, "ffprobe")
    transcriber = None
    asr_cache: dict[str, list[dict]] = {}
    asr_cache_path = (args.asr_cache or (project_root / "data" / "elllo-whisper-alignments.json")).resolve()
    if args.whisper_model:
        if asr_cache_path.is_file():
            asr_cache = json.loads(asr_cache_path.read_text(encoding="utf-8"))
        transcriber = WhisperTranscriber(
            args.whisper_model,
            args.whisper_library,
            args.whisper_download_root,
        )
    try:
        result = process_manifest(
            project_root,
            ffmpeg,
            ffprobe,
            transcriber,
            asr_cache,
        )
    finally:
        if transcriber:
            asr_cache_path.parent.mkdir(parents=True, exist_ok=True)
            asr_cache_path.write_text(
                json.dumps(asr_cache, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
    static_root = project_root / "src" / "main" / "resources" / "static"
    validate_result(result, static_root)
    print_summary(result)

    if not args.analyze_only:
        output_manifest = static_root / "audio" / "elllo" / "segments.json"
        if output_manifest.exists() and not args.force:
            raise FileExistsError(f"Refusing to replace {output_manifest}; pass --force")
        output_manifest.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Sentence cues: {output_manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
