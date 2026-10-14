param([string]$BaseUrl = 'https://160.191.244.27:18443', [switch]$WaitForChrome)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http
$handler = New-Object Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$anonymous = New-Object Net.Http.HttpClient
$anonymous.Timeout = [TimeSpan]::FromSeconds(30)
$run = [guid]::NewGuid().ToString('N')
$email = 's3-release-' + $run + '@loadtest.local'
$slug = 's3-release-' + $run
$password = 'Smoke9' + [guid]::NewGuid().ToString('N')
$account = $null; $lesson = $null; $sentenceId = $null; $audioUrl = $null; $signed = $null
$probePath = 'C:\ProgramData\DictaLab\auth\s3-browser-probe.xml'
$donePath = 'C:\ProgramData\DictaLab\auth\s3-browser-probe.done'
if ((Test-Path -LiteralPath $probePath) -or (Test-Path -LiteralPath $donePath)) {
    throw 'A previous browser probe needs inspection before retrying.'
}
$secrets = Import-Clixml C:\ProgramData\DictaLab\database-secrets.xml
function Query([string]$Sql) {
    $env:PGPASSWORD = $secrets.AppPassword
    try {
        $result = @(& C:\DictaLab\postgresql-17\bin\psql.exe -X -h 127.0.0.1 -U dictalab_app -d dictalab -v ON_ERROR_STOP=1 -At -c $Sql)
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic SQL check or cleanup failed.' }
        return $result
    } finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
}
function Send([string]$Path, [string]$Method = 'GET', $Content = $null, [bool]$Protect = $false) {
    $request = New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::new($Method), ($BaseUrl + $Path))
    if ($Content) { $request.Content = $Content }
    if ($Protect) {
        $csrf = Json '/api/auth/csrf'
        $null = $request.Headers.TryAddWithoutValidation($csrf.headerName, $csrf.token)
    }
    try { return $client.SendAsync($request).GetAwaiter().GetResult() }
    finally { $request.Dispose() }
}
function Json([string]$Path, [string]$Method = 'GET', $Body = $null) {
    $content = if ($null -ne $Body) { [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 6 -Compress), [Text.Encoding]::UTF8, 'application/json') } else { $null }
    $response = Send $Path $Method $content ($Method -ne 'GET')
    try {
        if (-not $response.IsSuccessStatusCode) { throw "HTTP $([int]$response.StatusCode) for $Method $Path" }
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($text) { return $text | ConvertFrom-Json }
    } finally { $response.Dispose() }
}
try {
    $account = Json '/api/auth/register' 'POST' @{displayName='S3 release smoke'; email=$email; password=$password}
    if (-not $account.id -or $account.role -ne 'USER') { throw 'Synthetic registration failed.' }
    $denied = Send '/api/admin/lessons'
    try { if ([int]$denied.StatusCode -ne 403) { throw 'Learner admin access was not rejected.' } } finally { $denied.Dispose() }
    'S3_LEARNER_ADMIN_PROTECTION=OK'
    $null = Json '/api/auth/logout' 'POST'
    $null = Query "UPDATE app_users SET role='ADMIN' WHERE id=$([long]$account.id) AND email='$email' AND role='USER';"
    $admin = Json '/api/auth/login' 'POST' @{email=$email; password=$password}
    if ($admin.role -ne 'ADMIN') { throw 'Synthetic admin login failed.' }
    $lesson = Json '/api/admin/lessons' 'POST' @{slug=$slug;title='S3 release smoke';description='Isolated storage probe';level='A1';topic='Storage check';estimatedMinutes=1;displayOrder=9999}
    if ($lesson.published) { throw 'Synthetic lesson must remain unpublished.' }
    $lesson = Json "/api/admin/lessons/$($lesson.id)/sentences" 'POST' @{transcript='Audio storage check.';speechRate=1.0}
    $sentenceId = [long]$lesson.sentences[0].id
    $endpoint = "/api/admin/lessons/$($lesson.id)/sentences/$sentenceId/audio"
    # Generate a valid one-second PCM tone in memory; no user audio is touched.
    $stream = New-Object IO.MemoryStream
    $writer = New-Object IO.BinaryWriter($stream)
    foreach ($part in @('RIFF')) { $writer.Write([Text.Encoding]::ASCII.GetBytes($part)) }
    $writer.Write([int]16036); $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
    $writer.Write([int]16); $writer.Write([int16]1); $writer.Write([int16]1)
    $writer.Write([int]8000); $writer.Write([int]16000); $writer.Write([int16]2); $writer.Write([int16]16)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('data')); $writer.Write([int]16000)
    for ($i=0; $i -lt 8000; $i++) { $writer.Write([int16](4000 * [math]::Sin(2 * [math]::PI * 440 * $i / 8000))) }
    $wave = $stream.ToArray(); $writer.Dispose(); $stream.Dispose()
    function Multipart {
        $multipart = New-Object Net.Http.MultipartFormDataContent
        $part = [Net.Http.ByteArrayContent]::new($wave)
        $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('audio/wav')
        $multipart.Add($part, 'file', 'storage-check.wav')
        return ,$multipart
    }
    $denied = Send $endpoint 'POST' (Multipart) $false
    try {
        if ([int]$denied.StatusCode -ne 403 -or -not $denied.Headers.Contains('X-CSRF-Error')) { throw 'Audio CSRF rejection failed.' }
    } finally { $denied.Dispose() }
    'S3_UPLOAD_CSRF_PROTECTION=OK'
    $response = Send $endpoint 'POST' (Multipart) $true
    try {
        if ([int]$response.StatusCode -ne 200) { throw "Audio upload failed: HTTP $([int]$response.StatusCode)" }
        $uploaded = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $audioUrl = $uploaded.sentences[0].audioUrl
    } finally { $response.Dispose() }
    if ($audioUrl -notmatch '^/media/s3/[0-9a-f-]{36}\.wav$') { throw 'Upload did not return a stable S3 reference.' }
    $dbUrl = @(Query "SELECT audio_url FROM lesson_sentences WHERE id=$sentenceId AND lesson_id=$([long]$lesson.id);")[0]
    if ($dbUrl -ne $audioUrl) { throw 'Database audio reference mismatch.' }
    'PRODUCTION_ADMIN_UPLOAD_TO_S3=OK'
    $response = Send $audioUrl
    try {
        if ([int]$response.StatusCode -ne 307 -or $response.Headers.CacheControl.ToString() -ne 'no-store') { throw 'Private media redirect mismatch.' }
        $signed = $response.Headers.Location.AbsoluteUri
    } finally { $response.Dispose() }
    $response = $anonymous.GetAsync($BaseUrl + $audioUrl).GetAwaiter().GetResult()
    try { if ([int]$response.StatusCode -ne 401) { throw 'Anonymous app media access was not rejected.' } } finally { $response.Dispose() }
    $response = $anonymous.GetAsync($signed).GetAwaiter().GetResult()
    try {
        $download = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne 200 -or [Convert]::ToBase64String($download) -ne [Convert]::ToBase64String($wave)) { throw 'Playback bytes mismatch.' }
    } finally { $response.Dispose() }
    'PRODUCTION_AUTHENTICATED_PLAYBACK_BYTES=OK'
    $legacy = @(Query "SELECT audio_url FROM lesson_sentences WHERE audio_url LIKE '/media/%' AND audio_url NOT LIKE '/media/s3/%' LIMIT 1;")
    if (-not $legacy.Count) { throw 'Expected legacy audio fixture is missing.' }
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, ($BaseUrl + $legacy[0]))
    $request.Headers.Range = [Net.Http.Headers.RangeHeaderValue]::new(0, 3)
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try { if ([int]$response.StatusCode -ne 206) { throw 'Legacy audio range playback failed.' } } finally { $response.Dispose(); $request.Dispose() }
    'PRODUCTION_LEGACY_AUDIO_RANGE=OK'
    if ($WaitForChrome) {
        ConvertTo-SecureString $signed -AsPlainText -Force | Export-Clixml -LiteralPath $probePath
        'CHROME_AUDIO_PROBE_READY'
        $deadline = (Get-Date).AddSeconds(180)
        while (-not (Test-Path -LiteralPath $donePath) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
        if (-not (Test-Path -LiteralPath $donePath) -or [IO.File]::ReadAllText($donePath) -ne 'OK') { throw 'Chrome playback probe did not pass.' }
        'PRODUCTION_CHROME_WEB_AUDIO=OK'
    }
} finally {
    if ($sentenceId -and $lesson) {
        $null = Json "/api/admin/lessons/$($lesson.id)/sentences/$sentenceId" 'DELETE'
        if ($signed) {
            $response = $anonymous.GetAsync($signed).GetAwaiter().GetResult()
            try { if ([int]$response.StatusCode -notin @(403,404)) { throw 'Synthetic S3 object was not deleted.' } } finally { $response.Dispose() }
            'PRODUCTION_SYNTHETIC_S3_OBJECT_DELETED=OK'
        }
    }
    try { $null = Json '/api/auth/logout' 'POST' } catch { Write-Warning 'Synthetic logout needs inspection.' }
    if ($email -notmatch '^s3-release-[a-f0-9]{32}@loadtest\.local$' -or $slug -notmatch '^s3-release-[a-f0-9]{32}$') { throw 'Unsafe cleanup target.' }
    $null = Query "BEGIN; DELETE FROM lessons WHERE slug='$slug' AND active=FALSE; DELETE FROM account_tokens WHERE user_id IN (SELECT id FROM app_users WHERE email='$email'); DELETE FROM account_audit WHERE actor_id IN (SELECT id FROM app_users WHERE email='$email') OR subject_id IN (SELECT id FROM app_users WHERE email='$email'); DELETE FROM app_users WHERE email='$email'; COMMIT;"
    'PRODUCTION_SYNTHETIC_DB_CLEANUP=OK'
    # These two exact probe artifacts contain only this run's temporary signed URL/status.
    foreach ($path in @($probePath,$donePath)) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force } }
    $password = $null; $signed = $null
    $client.Dispose(); $handler.Dispose(); $anonymous.Dispose()
}
