$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $projectRoot "src/main/resources/static/audio/elllo/manifest.json"
$manifest = Get-Content -Raw -Encoding utf8 $manifestPath | ConvertFrom-Json

function ConvertTo-SqlLiteral {
    param([AllowEmptyString()][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function ConvertTo-SpokenTranscript {
    param([string]$Transcript)

    $utterances = $Transcript -split "`r?`n" | ForEach-Object {
        ($_ -replace '^[^:]+:\s*', '').Trim()
    } | Where-Object { $_ }

    return (($utterances -join ' ') -replace '\s+', ' ').Trim()
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('-- Import the locally downloaded ELLLO CEFR starter pack.')
$lines.Add('-- Generated from static/audio/elllo/manifest.json; do not edit after Flyway applies it.')
$lines.Add('')
$lines.Add("UPDATE lessons")
$lines.Add("SET active = FALSE")
$lines.Add("WHERE slug IN (")
$lines.Add("    'morning-market', 'my-small-apartment', 'weekend-train', 'dinner-plan',")
$lines.Add("    'remote-work-routine', 'museum-volunteer', 'urban-gardens', 'digital-privacy'")
$lines.Add(");")
$lines.Add('')
$lines.Add('INSERT INTO lessons (id, slug, title, description, level, topic, estimated_minutes, display_order, active) VALUES')

$lessonRows = [System.Collections.Generic.List[string]]::new()
$lessonIdBySlug = @{}
$nextLessonId = 9
$orderByLevel = @{}
foreach ($level in 'A1', 'A2', 'B1', 'B2') {
    $orderByLevel[$level] = 0
}

foreach ($lesson in $manifest.lessons) {
    $level = [string]$lesson.level
    $orderByLevel[$level]++
    $slug = 'elllo-' + ([string]$lesson.id).ToLowerInvariant()
    $lessonIdBySlug[$slug] = $nextLessonId
    $description = "Human-recorded ELLLO conversations for " + [string]$lesson.title + "."
    $estimatedMinutes = [Math]::Max(4, [Math]::Ceiling($lesson.conversations.Count * 1.5))
    $lessonRows.Add("    (" + $nextLessonId + ", " +
        (ConvertTo-SqlLiteral $slug) + ", " +
        (ConvertTo-SqlLiteral ([string]$lesson.title)) + ", " +
        (ConvertTo-SqlLiteral $description) + ", " +
        (ConvertTo-SqlLiteral $level) + ", 'ELLLO Grammar', " +
        $estimatedMinutes + ", " + $orderByLevel[$level] + ", TRUE)")
    $nextLessonId++
}
$lines.Add(($lessonRows -join ",`n") + ';')
$lines.Add('ALTER TABLE lessons ALTER COLUMN id RESTART WITH 29;')
$lines.Add('')
$lines.Add('INSERT INTO lesson_sentences (id, lesson_id, position_index, transcript, audio_url, speech_rate) VALUES')

$sentenceRows = [System.Collections.Generic.List[string]]::new()
$nextSentenceId = 25
foreach ($lesson in $manifest.lessons) {
    $slug = 'elllo-' + ([string]$lesson.id).ToLowerInvariant()
    foreach ($conversation in $lesson.conversations) {
        $transcript = ConvertTo-SpokenTranscript ([string]$conversation.transcript)
        $sentenceRows.Add("    (" + $nextSentenceId + ", " +
            $lessonIdBySlug[$slug] + ", " +
            [int]$conversation.number + ", " +
            (ConvertTo-SqlLiteral $transcript) + ", " +
            (ConvertTo-SqlLiteral ([string]$conversation.localAudioPath)) + ", 1.0)")
        $nextSentenceId++
    }
}
$lines.Add(($sentenceRows -join ",`n") + ';')
$lines.Add('ALTER TABLE lesson_sentences ALTER COLUMN id RESTART WITH 104;')

$lines -join "`n"
