param(
    [switch]$ReceiveCredential,
    [switch]$Configure,
    [string]$Bucket = 'dictalab-audio-20261004-6ac4f43c'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($ReceiveCredential -eq $Configure) { throw 'Choose exactly one operation.' }
if ($Bucket -ne 'dictalab-audio-20261004-6ac4f43c') { throw 'Unexpected production bucket.' }
$secretRoot = 'C:\ProgramData\DictaLab\auth'
$credentialPath = Join-Path $secretRoot 's3-credentials'
if ($ReceiveCredential) {
    # Credentials arrive on encrypted SSH stdin, never command arguments or logs.
    try { $credential = [Console]::In.ReadToEnd() | ConvertFrom-Json }
    catch { throw 'Invalid credential input; values are not logged.' }
    if ($credential.AccessKeyId -notmatch '^AKIA[A-Z0-9]{16}$' -or
        $credential.SecretAccessKey -cnotmatch '^[A-Za-z0-9/+=]{40}$') {
        throw 'Invalid AWS credential format.'
    }
    if (Test-Path -LiteralPath $credentialPath) { throw 'Refuse to overwrite an existing credential.' }
    New-Item -ItemType Directory -Force -Path $secretRoot | Out-Null
    & icacls.exe $secretRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect credential directory.' }
    $stream = [IO.File]::Open($credentialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $text = "[dictalab-audio]`naws_access_key_id=$($credential.AccessKeyId)`naws_secret_access_key=$($credential.SecretAccessKey)`n"
        $bytes = [Text.Encoding]::ASCII.GetBytes($text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose(); $credential = $null; $text = $null; $bytes = $null }
    'AWS_CREDENTIAL_RECEIVED_ACL_PROTECTED'
    return
}
if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) { throw 'Protected AWS credential is missing.' }
$svc = Get-CimInstance Win32_Service -Filter "Name='DictaLab'"
if ($svc.StartName -ne 'LocalSystem') { throw 'Credential ACL requires the existing LocalSystem service.' }
$key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Services\DictaLab\Parameters', $true)
try {
    $previous = [string[]]$key.GetValue('AppEnvironmentExtra')
    if (-not $previous) { throw 'Refuse to replace an empty service environment.' }
    $settings = [ordered]@{}
    foreach ($line in $previous) {
        $parts = $line -split '=', 2
        if ($parts.Count -ne 2) { throw 'Invalid existing environment entry.' }
        $settings[$parts[0]] = $parts[1]
    }
    foreach ($name in @('AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN')) {
        if ($settings.Contains($name) -or [Environment]::GetEnvironmentVariable($name, 'Machine')) {
            throw 'Existing AWS environment credentials require inspection before profile activation.'
        }
    }
    $backup = Join-Path $secretRoot ('app-env-before-s3-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.xml')
    ConvertTo-SecureString ($previous -join "`n") -AsPlainText -Force | Export-Clixml -LiteralPath $backup
    $settings['AWS_SHARED_CREDENTIALS_FILE'] = $credentialPath
    $settings['AWS_PROFILE'] = 'dictalab-audio'
    $settings['DICTALAB_MEDIA_S3_ENABLED'] = 'true'
    $settings['DICTALAB_MEDIA_S3_BUCKET'] = $Bucket
    $settings['DICTALAB_MEDIA_S3_REGION'] = 'us-east-1'
    $settings['DICTALAB_MEDIA_S3_PREFIX'] = 'dictalab/audio/'
    $settings['DICTALAB_MEDIA_S3_SIGNED_URL_DURATION'] = '30m'
    $lines = [string[]]@($settings.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
    $key.SetValue('AppEnvironmentExtra', $lines, [Microsoft.Win32.RegistryValueKind]::MultiString)
    'S3_CONFIGURED_RESTART_REQUIRED'
    'ENVIRONMENT_BACKUP=' + $backup
} finally { if ($key) { $key.Dispose() } }
