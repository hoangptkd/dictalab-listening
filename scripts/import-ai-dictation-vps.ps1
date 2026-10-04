param(
    [Parameter(Mandatory=$true)][string]$BatchDirectory,
    [string]$MediaRoot = 'C:\DictaLab\media'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

# One-time content import, never restarts/deploys the app and never changes old lessons.
$batchPath = (Resolve-Path -LiteralPath $BatchDirectory).Path
if (-not $batchPath.StartsWith('C:\DictaLab\staging\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Batch must be inside the DictaLab staging directory.'
}
$mediaPath = [IO.Path]::GetFullPath($MediaRoot).TrimEnd('\')
if ($mediaPath -ne 'C:\DictaLab\media') { throw 'Unexpected production media directory.' }
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $batchPath 'manifest.json') | ConvertFrom-Json
if ($manifest.level -ne 'B1' -or $manifest.lessons.Count -ne 3) { throw 'Unexpected content batch.' }
$slugs = @()
foreach ($lesson in $manifest.lessons) {
    if ($lesson.slug -notmatch '^ai-b1-(changing-travel-plans|returning-an-order|planning-a-team-event)-20261002$' -or $slugs -contains $lesson.slug) {
        throw 'Unexpected/duplicate lesson slug.'
    }
    $slugs += $lesson.slug
    if ($lesson.sentences.Count -ne 12 -or $lesson.audioFile -notmatch '^[0-9a-f-]{36}\.wav$') { throw 'Invalid audio manifest.' }
    $source = Join-Path $batchPath $lesson.audioFile
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $lesson.sha256) { throw 'Audio hash mismatch.' }
    $destination = Join-Path $mediaPath $lesson.audioFile
    if ((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $lesson.sha256) {
        throw 'Existing media differs; refusing to overwrite.'
    }
}

& 'C:\Program Files\nodejs\node.exe' 'C:\DictaLab\staging\create-b1-dictation.cjs' sql $batchPath
if ($LASTEXITCODE -ne 0) { throw 'Content/segment validation failed.' }

# Reuse the existing verified pg_dump backup before any content database mutation.
& 'C:\DictaLab\staging\backup-windows-vps.ps1'
foreach ($lesson in $manifest.lessons) {
    $source = Join-Path $batchPath $lesson.audioFile
    $destination = Join-Path $mediaPath $lesson.audioFile
    if (-not (Test-Path -LiteralPath $destination)) { Copy-Item -LiteralPath $source -Destination $destination }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $lesson.sha256) { throw 'Copied media hash mismatch.' }
}

$env:PGPASSWORD = (Import-Clixml -LiteralPath 'C:\ProgramData\DictaLab\database-secrets.xml').AppPassword
$env:PGCLIENTENCODING = 'UTF8'
try {
    & 'C:\DictaLab\postgresql-17\bin\psql.exe' -h 127.0.0.1 -U dictalab_app -d dictalab -q -v ON_ERROR_STOP=1 -f (Join-Path $batchPath 'draft-import.sql')
    if ($LASTEXITCODE -ne 0) { throw 'Draft import failed; SQL transaction rolls back without modifying old lessons. Copied audio is retained for retry.' }
    & 'C:\DictaLab\postgresql-17\bin\psql.exe' -h 127.0.0.1 -U dictalab_app -d dictalab -qAt -v ON_ERROR_STOP=1 -c "SELECT l.id,l.slug,l.active,COUNT(s.id) FROM lessons l JOIN lesson_sentences s ON s.lesson_id=l.id WHERE l.slug IN ('ai-b1-changing-travel-plans-20261002','ai-b1-returning-an-order-20261002','ai-b1-planning-a-team-event-20261002') GROUP BY l.id ORDER BY l.id"
    if ($LASTEXITCODE -ne 0) { throw 'Draft verification query failed.' }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PGCLIENTENCODING -ErrorAction SilentlyContinue
}
Write-Output 'DRAFT_IMPORT_OK: three new lessons, existing lessons unchanged, media hashes verified'
