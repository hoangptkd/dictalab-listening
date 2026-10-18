param([string]$BaseUrl = 'http://localhost:18081', [long]$LessonId = 31)
$ErrorActionPreference = 'Stop'

# Smoke test with a separate synthetic learner. Never alters an existing learner's scores.
function Post-Json([string]$Path, $Body) {
    $payload = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
    Invoke-RestMethod "$BaseUrl$Path" -Method Post -WebSession $script:qaWeb -Headers $script:qaHeaders `
        -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 120
}
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable qaWeb
$qaHeaders = @{}
$qaHeaders[$csrf.headerName] = $csrf.token
$null = Post-Json '/api/auth/register' @{
    displayName = 'Coach local QA'
    email = "coach-local-$([guid]::NewGuid().ToString('N'))@example.test"
    password = "Qa9$([guid]::NewGuid().ToString('N'))"
}
$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $qaWeb
$qaHeaders[$csrf.headerName] = $csrf.token
$start = Post-Json '/api/v1/practice-sessions' @{lessonId = $LessonId}
$sessionPath = "/api/v1/practice-sessions/$($start.sessionId)"
$sentence = $start.sentence
$errors = 0
while ($sentence) {
    $sentencePath = "$sessionPath/sentences/$($sentence.sentenceId)"
    $null = Post-Json "$sentencePath/replay-events" @{event = 'STARTED'; playbackSpeed = 1.0}
    $null = Post-Json "$sentencePath/replay-events" @{event = 'COMPLETED'}
    foreach ($slot in $sentence.words) {
        if ($slot.prefilled) { continue }
        $wordPath = "$sentencePath/words/$($slot.index)/checks"
        if ($slot.index -eq 1 -and $errors -lt 12) {
            $null = Post-Json $wordPath @{answer = 'other'; responseTimeMs = 1200}
            $null = Post-Json "$sentencePath/classifications" @{wordIndex = $slot.index; errorType = 'KNOWN_BUT_MISSED'}
            $errors++
        }
    }
    # The learner API intentionally withholds transcript before reveal. Use the normal reveal flow in QA.
    $null = Post-Json "$sentencePath/hints" @{type = 'REVEAL_SENTENCE'}
    $null = Post-Json "$sentencePath/replay-events" @{event = 'STARTED'; playbackSpeed = 1.0}
    $null = Post-Json "$sentencePath/replay-events" @{event = 'COMPLETED'}
    $done = Post-Json "$sentencePath/complete" @{timeSpentSeconds = 20}
    $sentence = $done.nextSentence
}
$report = Post-Json "$sessionPath/coach" @{}
for ($poll = 0; $report.source -eq 'QUEUED' -and $poll -lt 90; $poll++) {
    Start-Sleep -Seconds 2
    $report = Invoke-RestMethod "$BaseUrl$sessionPath/coach" -WebSession $qaWeb
}
if ($report.source -ne 'AI') { throw "Expected AI, received $($report.source). Check Coach provider, model and enabled configuration." }
if ($report.weaknesses.Count -gt 3 -or $report.nextActions.Count -gt 3 -or $report.reviewItems.Count -gt 12) {
    throw 'Coach output exceeded UX limits.'
}
if ($report.quickReviewItems.Count -gt 3 -or @($report.quickReviewItems.sentenceId | Select-Object -Unique).Count -ne $report.quickReviewItems.Count) {
    throw 'Quick review must use at most three different sentences.'
}
if ($report.generation.provider -eq 'ollama' -and ($report.weaknesses.Count -gt 1 -or
        @($report.weaknesses | Where-Object { $_.insight.Length -gt 120 }).Count -gt 0)) { throw 'Local insight exceeded its contract.' }
$cached = Invoke-RestMethod "$BaseUrl$sessionPath/coach" -WebSession $qaWeb
if ($cached.source -ne 'AI') { throw 'AI report was not persisted.' }
$detail = Invoke-RestMethod "$BaseUrl/api/v1/history/$($start.sessionId)" -WebSession $qaWeb
if ($detail.firstAttemptAccuracy -ne $done.result.firstAttemptAccuracy) { throw 'AI changed deterministic scoring.' }
$null = Post-Json '/api/auth/logout' @{}
[pscustomobject]@{source = $report.source; sessionId = $start.sessionId; weaknesses = $report.weaknesses.Count;
    reviews = $report.reviewItems.Count; actions = $report.nextActions.Count; scoreUnchanged = $true}
