param(
    [string]$ArtifactPath = 'C:\DictaLab\staging\dictalab-turn.exe',
    [Parameter(Mandatory=$true)][string]$ExpectedSha256,
    [string]$PublicIp = '160.191.244.27'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ((Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash -ne $ExpectedSha256) { throw 'TURN artifact hash mismatch' }
if (-not (Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $PublicIp)) { throw 'Public IP is not assigned locally' }
$turnRoot = 'C:\DictaLab\turn'
$secretRoot = 'C:\ProgramData\DictaLab\turn'
$binary = Join-Path $turnRoot 'dictalab-turn.exe'
$secretPath = Join-Path $secretRoot 'shared-secret.txt'
$nssm = (Get-Command nssm -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $turnRoot,$secretRoot,(Join-Path $turnRoot 'logs') -Force | Out-Null
& icacls $secretRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)R' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Secret directory ACL failed' }
& icacls (Join-Path $turnRoot 'logs') /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Log directory ACL failed' }
if (-not (Test-Path -LiteralPath $secretPath)) {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($secretPath,[Convert]::ToBase64String($bytes),(New-Object Text.UTF8Encoding($false)))
}
$secret = [IO.File]::ReadAllText($secretPath).Trim()
if ($secret.Length -lt 32) { throw 'TURN secret invalid' }
$service = Get-Service DictaLabTURN -ErrorAction SilentlyContinue
if ($service) {
    $configured = (& $nssm get DictaLabTURN Application).Trim()
    if ($configured -ne $binary) { throw 'An unrelated TURN service already exists' }
    Stop-Service DictaLabTURN
    $service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))
    Copy-Item -LiteralPath $binary -Destination ($binary+'.previous') -Force
}
Copy-Item -LiteralPath $ArtifactPath -Destination $binary -Force
function Set-Nssm([string]$Name,[string]$Field,[string[]]$Values) {
    & $nssm set $Name $Field @Values | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not configure $Name/$Field" }
}
if (-not $service) {
    & $nssm install DictaLabTURN $binary | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'TURN service installation failed' }
}
Set-Nssm DictaLabTURN AppParameters @('--public-ip', $PublicIp, '--secret-file', $secretPath)
Set-Nssm DictaLabTURN ObjectName @('NT AUTHORITY\LocalService')
Set-Nssm DictaLabTURN Start @('SERVICE_AUTO_START')
Set-Nssm DictaLabTURN AppExit @('Default','Restart')
Set-Nssm DictaLabTURN AppRestartDelay @('5000')
Set-Nssm DictaLabTURN AppStdout @((Join-Path $turnRoot 'logs\stdout.log'))
Set-Nssm DictaLabTURN AppStderr @((Join-Path $turnRoot 'logs\stderr.log'))
Set-Nssm DictaLabTURN AppRotateFiles @('1')
Set-Nssm DictaLabTURN AppRotateOnline @('1')
Set-Nssm DictaLabTURN AppRotateBytes @('10485760')
foreach ($rule in @(@('DictaLab TURN UDP','UDP','3478'),@('DictaLab TURN TCP','TCP','3478'),@('DictaLab TURN relay','UDP','49160-49959'))) {
    if (-not (Get-NetFirewallRule -DisplayName $rule[0] -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $rule[0] -Direction Inbound -Action Allow -Protocol $rule[1] -LocalPort $rule[2] -Program $binary | Out-Null
    }
}
Start-Service DictaLabTURN
Start-Sleep -Seconds 2
if (-not (Get-NetUDPEndpoint -LocalPort 3478 -ErrorAction SilentlyContinue) -or -not (Get-NetTCPConnection -LocalPort 3478 -State Listen -ErrorAction SilentlyContinue)) { throw 'TURN listeners are not ready' }
# Save the original environment as DPAPI-protected data. Never print secret values.
$envLines = @(& $nssm get DictaLab AppEnvironmentExtra)
if ($LASTEXITCODE -ne 0) { throw 'Could not read application environment' }
$saved = Join-Path $secretRoot ('app-env-before-turn-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.xml')
ConvertTo-SecureString ($envLines -join "`n") -AsPlainText -Force | Export-Clixml -LiteralPath $saved
$newLines = @($envLines | Where-Object { $_ -notmatch '^DICTALAB_TURN_(URL|SECRET|TTL_SECONDS)=' })
$newLines += "DICTALAB_TURN_URL=turn:${PublicIp}:3478?transport=udp,turn:${PublicIp}:3478?transport=tcp"
$newLines += "DICTALAB_TURN_SECRET=$secret"
$newLines += 'DICTALAB_TURN_TTL_SECONDS=3600'
Set-Nssm DictaLab AppEnvironmentExtra $newLines
Write-Output 'TURN_SERVICE_READY_APP_RESTART_REQUIRED'
Write-Output ('TURN_SERVICE='+ (Get-Service DictaLabTURN).Status)
Write-Output ('ENVIRONMENT_BACKUP='+$saved)
