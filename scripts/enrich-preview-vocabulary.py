#!/usr/bin/env python3
"""Classify lesson proper names and difficult words with Gemini, then update the VPS database."""

from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request


WORD = re.compile(r"[A-Za-z0-9]+(?:[’'-][A-Za-z0-9]+)*")
NON_ENTITIES = {
    "man", "woman", "speaker", "narrator", "monday", "tuesday", "wednesday", "thursday",
    "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december", "english", "spanish",
    "italian", "thai", "chinese", "french", "german", "japanese", "korean", "page", "cool",
    "american", "central american", "british", "canadian", "mexican", "australian", "indian",
    "european", "asian", "african", "spanish", "portuguese", "russian",
}
COMMON_NOT_DIFFICULT = {
    "vocabulary", "finished", "expensive", "beautiful", "different", "important", "interesting",
    "restaurant", "something", "sometimes", "together", "tomorrow", "yesterday", "weekend",
}
TARGETS = {"A1": 2, "A2": 3, "B1": 4, "B2": 5}
REMOTE_QUERY = """
SELECT COALESCE(json_agg(item ORDER BY display_order, id), '[]'::json)
FROM (
    SELECT l.id, l.slug, l.title, l.level, l.display_order,
           string_agg(s.transcript, E'\n' ORDER BY s.position_index) AS transcript
    FROM lessons l
    JOIN lesson_sentences s ON s.lesson_id = l.id
    WHERE l.active = TRUE
    GROUP BY l.id, l.slug, l.title, l.level, l.display_order
) item;
"""
PROMPT = """You are a meticulous English linguist creating preview vocabulary for a CEFR listening test.
Return JSON only: {{"properNames":["exact phrase"],"difficultWords":["exact word or phrase"]}}.

properNames:
- Include every specific person, place, organization, brand, event, or named landmark in the transcript.
- Preserve a multi-word entity as one phrase, for example "New York".
- Exclude weekdays, months, languages, nationalities, speaker labels, sentence-start capitalization,
  contractions, pronouns, grammar forms, and ordinary words.

difficultWords:
- Select at most {target} items genuinely worth previewing for a {level} learner.
- Select only rare, specialized, domain-specific, idiomatic, culturally specific, or clearly above-{level} items.
- Do not fill the quota with ordinary vocabulary. In particular, words like vocabulary, finished,
  expensive, beautiful, different, important, interesting, and restaurant are not difficult enough.
- Exclude every proper name. Prefer the exact inflected form used in the transcript.

Every returned item must be copied exactly from a contiguous span of the transcript. Do not explain.

Lesson title: {title}
Transcript:
{transcript}
"""


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--ssh-user", default="Administrator")
    parser.add_argument("--ssh-key", type=Path, required=True)
    parser.add_argument("--model", default="gemini-3.5-flash-lite")
    parser.add_argument("--api-url", default="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
    parser.add_argument("--cache", type=Path, default=Path("data/ai-preview-cache.json"))
    parser.add_argument("--output-sql", type=Path, default=Path("data/ai-preview-update.sql"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--prompt-api-key", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def normalize(value: str) -> str:
    return " ".join(word.lower().replace("’", "'") for word in WORD.findall(value))


def exact_transcript_phrase(candidate: str, transcript: str) -> str | None:
    wanted = [normalize(word) for word in WORD.findall(candidate)]
    transcript_words = WORD.findall(transcript)
    normalized = [normalize(word) for word in transcript_words]
    if not wanted:
        return None
    for index in range(len(normalized) - len(wanted) + 1):
        if normalized[index:index + len(wanted)] == wanted:
            return " ".join(transcript_words[index:index + len(wanted)])
    return None


def parse_model_result(content: str, transcript: str, level: str) -> dict[str, list[str]]:
    start, end = content.find("{"), content.rfind("}")
    if start < 0 or end < start:
        raise ValueError("AI response does not contain a JSON object")
    payload = json.loads(content[start:end + 1])
    names: list[str] = []
    seen_names: set[str] = set()
    for item in payload.get("properNames", []):
        if not isinstance(item, str):
            continue
        exact = exact_transcript_phrase(item, transcript)
        key = normalize(exact or "")
        if (not exact or len(exact) > 100 or key in NON_ENTITIES or "'" in key
                or key in seen_names):
            continue
        names.append(exact)
        seen_names.add(key)

    name_words = {normalize(word) for name in names for word in WORD.findall(name)}
    difficult: list[str] = []
    seen_difficult: set[str] = set()
    for item in payload.get("difficultWords", []):
        if not isinstance(item, str):
            continue
        exact = exact_transcript_phrase(item, transcript)
        key = normalize(exact or "")
        words = set(key.split())
        if (not exact or len(exact) > 100 or key in COMMON_NOT_DIFFICULT or "'" in key
                or key in seen_names or words & name_words or key in seen_difficult):
            continue
        difficult.append(exact.lower())
        seen_difficult.add(key)
        if len(difficult) == TARGETS[level]:
            break
    return {"properNames": names, "difficultWords": difficult}


def remote_powershell(script: str) -> str:
    return base64.b64encode(script.encode("utf-16-le")).decode("ascii")


def psql_via_ssh(args: argparse.Namespace, sql: str) -> str:
    script = r"""
$ErrorActionPreference = 'Stop'
$secrets = Import-Clixml -LiteralPath 'C:\ProgramData\DictaLab\database-secrets.xml'
$env:PGPASSWORD = $secrets.AppPassword
& 'C:\DictaLab\postgresql-17\bin\psql.exe' -h 127.0.0.1 -U dictalab_app -d dictalab -qAt -v ON_ERROR_STOP=1
exit $LASTEXITCODE
"""
    command = [
        "ssh", "-i", str(args.ssh_key), "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
        f"{args.ssh_user}@{args.ssh_host}", "powershell.exe", "-NoProfile", "-NonInteractive",
        "-EncodedCommand", remote_powershell(script),
    ]
    for attempt in range(3):
        result = subprocess.run(command, input=sql, text=True, encoding="utf-8", capture_output=True)
        if not result.returncode:
            return result.stdout.strip()
        if attempt < 2:
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"Remote PostgreSQL command failed: {result.stderr.strip()}")


def load_cache(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def call_ai(api_key: str, api_url: str, model: str, prompt: str) -> str:
    body = json.dumps({
        "model": model,
        "temperature": 0,
        "max_tokens": 800,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")
    request = urllib.request.Request(api_url, data=body, headers={
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Accept": "application/json",
    })
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                payload = json.load(response)
            return payload["choices"][0]["message"]["content"]
        except (urllib.error.URLError, KeyError, json.JSONDecodeError) as error:
            if attempt == 2:
                raise RuntimeError(f"Gemini request failed after three attempts: {error}") from error
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_sql(results: list[dict]) -> str:
    lesson_ids = [str(item["id"]) for item in results]
    values = []
    for lesson in results:
        position = 1
        for word in lesson["difficultWords"]:
            values.append(f"({lesson['id']}, {position}, {sql_quote(word)}, FALSE)")
            position += 1
        for name in lesson["properNames"]:
            values.append(f"({lesson['id']}, {position}, {sql_quote(name)}, TRUE)")
            position += 1
    insert = ",\n".join(values) if values else "(NULL, NULL, NULL, NULL)"
    return f"""BEGIN;
CREATE TEMP TABLE ai_preview_input (
    lesson_id BIGINT, position_index INTEGER, word VARCHAR(100), proper_name BOOLEAN
) ON COMMIT DROP;
INSERT INTO ai_preview_input VALUES
{insert};
DELETE FROM ai_preview_input WHERE lesson_id IS NULL;
CREATE TEMP TABLE previous_preview ON COMMIT DROP AS
SELECT lesson_id, lower(word) AS normalized_word, audio_start_seconds, audio_end_seconds
FROM lesson_preview_words
WHERE lesson_id IN ({','.join(lesson_ids)});
DELETE FROM lesson_preview_words WHERE lesson_id IN ({','.join(lesson_ids)});
INSERT INTO lesson_preview_words (
    lesson_id, position_index, word, audio_start_seconds, audio_end_seconds, proper_name
)
SELECT n.lesson_id, n.position_index, n.word, p.audio_start_seconds, p.audio_end_seconds, n.proper_name
FROM ai_preview_input n
LEFT JOIN previous_preview p
  ON p.lesson_id = n.lesson_id AND p.normalized_word = lower(n.word)
ORDER BY n.lesson_id, n.position_index;
COMMIT;
"""


def self_test() -> None:
    transcript = "Gabriella visits Leeds on Tuesday. What's cool is that I'm on Page One in New York."
    content = json.dumps({
        "properNames": ["Gabriella", "Leeds", "Tuesday", "What's", "Cool", "Page", "New York"],
        "difficultWords": ["vocabulary", "finished", "expensive", "visits"],
    })
    result = parse_model_result(content, transcript + " Vocabulary finished expensive.", "B1")
    assert result["properNames"] == ["Gabriella", "Leeds", "New York"], result
    assert result["difficultWords"] == ["visits"], result
    print("SELF_TEST_OK")


def main() -> int:
    args = arguments()
    if args.self_test:
        self_test()
        return 0
    api_key = os.environ.get("GEMINI_API_KEY", "")
    lessons = json.loads(psql_via_ssh(args, REMOTE_QUERY))
    cache = load_cache(args.cache)
    results = []
    for index, lesson in enumerate(lessons, start=1):
        cache_key = f"{args.model}:{lesson['id']}:{normalize(lesson['transcript'])}"
        parsed = cache.get(cache_key)
        if parsed is None:
            if args.prompt_api_key and not api_key:
                api_key = getpass.getpass("Gemini API key: ")
            if not api_key:
                raise ValueError("Set GEMINI_API_KEY or use --prompt-api-key")
            prompt = PROMPT.format(
                target=TARGETS[lesson["level"]], level=lesson["level"],
                title=lesson["title"], transcript=lesson["transcript"],
            )
            content = call_ai(api_key, args.api_url, args.model, prompt)
            parsed = parse_model_result(content, lesson["transcript"], lesson["level"])
        else:
            parsed = parse_model_result(json.dumps(parsed), lesson["transcript"], lesson["level"])
        cache[cache_key] = parsed
        save_json(args.cache, cache)
        result = {"id": lesson["id"], "slug": lesson["slug"], **parsed}
        results.append(result)
        print(f"[{index:02d}/{len(lessons):02d}] {lesson['slug']}: "
              f"{len(parsed['difficultWords'])} difficult, {len(parsed['properNames'])} names", flush=True)
    sql = build_sql(results)
    args.output_sql.parent.mkdir(parents=True, exist_ok=True)
    args.output_sql.write_text(sql, encoding="utf-8", newline="\n")
    if args.apply:
        psql_via_ssh(args, sql)
        print(f"DATABASE_UPDATED lessons={len(results)} model={args.model}")
    else:
        print(f"Wrote {args.output_sql}; rerun with --apply after review")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
