#!/usr/bin/env python3
"""Generate reusable ELLLO sentence cues and the forward Flyway migration.

The legacy sentence manifest already contains reliable alignment metadata. This
script keeps that metadata but points each sentence at its original conversation
recording, so the application does not need one physical MP3 per sentence.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    project_root = Path(__file__).resolve().parents[1]
    parser.add_argument("--project-root", type=Path, default=project_root)
    parser.add_argument("--force", action="store_true", help="replace generated files")
    return parser.parse_args()


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_number(value: float) -> str:
    return f"{float(value):.3f}".rstrip("0").rstrip(".")


def build_cue_manifest(source_manifest: dict) -> dict:
    result = {
        "sourceManifest": "/audio/elllo/manifest.json",
        "generator": "scripts/generate-elllo-segment-migration.py",
        "lessons": [],
    }
    for lesson in source_manifest["lessons"]:
        lesson_copy = {key: value for key, value in lesson.items() if key != "sentences"}
        lesson_copy["sentences"] = []
        for sentence in lesson["sentences"]:
            sentence_copy = {key: value for key, value in sentence.items() if key != "localAudioPath"}
            lesson_copy["sentences"].append(sentence_copy)
        result["lessons"].append(lesson_copy)
    result["summary"] = source_manifest["summary"]
    return result


def build_migration(cue_manifest: dict) -> str:
    lines = [
        "-- Reuse one ELLLO conversation recording for multiple sentence exercises.",
        "-- Sentence boundaries come from static/audio/elllo/segments.json.",
        "",
        "ALTER TABLE lesson_sentences",
        "    ADD COLUMN audio_start_seconds DOUBLE PRECISION;",
        "",
        "ALTER TABLE lesson_sentences",
        "    ADD COLUMN audio_end_seconds DOUBLE PRECISION;",
        "",
        "ALTER TABLE lesson_sentences",
        "    ADD CONSTRAINT ck_sentence_audio_segment CHECK (",
        "        (audio_start_seconds IS NULL AND audio_end_seconds IS NULL)",
        "        OR (audio_start_seconds >= 0 AND audio_end_seconds > audio_start_seconds)",
        "    );",
        "",
    ]
    for lesson in cue_manifest["lessons"]:
        lesson_id = int(lesson["databaseId"])
        for sentence in lesson["sentences"]:
            lines.extend(
                (
                    "UPDATE lesson_sentences",
                    f"SET audio_url = {sql_literal(sentence['sourceAudioPath'])},",
                    f"    audio_start_seconds = {sql_number(sentence['startSeconds'])},",
                    f"    audio_end_seconds = {sql_number(sentence['endSeconds'])}",
                    f"WHERE lesson_id = {lesson_id} AND position_index = {int(sentence['position'])};",
                    "",
                )
            )
    return "\n".join(lines)


def write_generated(path: Path, content: str, force: bool) -> None:
    if path.exists() and not force:
        raise FileExistsError(f"Refusing to replace {path}; pass --force only before Flyway applies it")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def main() -> int:
    args = parse_arguments()
    project_root = args.project_root.resolve()
    segment_path = (
        project_root
        / "src"
        / "main"
        / "resources"
        / "static"
        / "audio"
        / "elllo"
        / "segments.json"
    )
    legacy_path = segment_path.parent.parent / "elllo-sentences" / "manifest.json"
    migration_path = (
        project_root
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
        / "V7__use_conversation_audio_segments.sql"
    )

    source_path = segment_path if segment_path.is_file() else legacy_path
    source_manifest = json.loads(source_path.read_text(encoding="utf-8"))
    cue_manifest = build_cue_manifest(source_manifest)
    sentence_count = sum(len(lesson["sentences"]) for lesson in cue_manifest["lessons"])
    if sentence_count != 910:
        raise ValueError(f"Expected 910 aligned sentences, found {sentence_count}")

    write_generated(
        segment_path,
        json.dumps(cue_manifest, ensure_ascii=False, indent=2) + "\n",
        args.force,
    )
    write_generated(migration_path, build_migration(cue_manifest), args.force)
    print(f"Generated {sentence_count} sentence cues in {segment_path}")
    print(f"Generated Flyway migration in {migration_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
