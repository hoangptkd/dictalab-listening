param(
    [string]$RedisSecretPath = 'C:\ProgramData\DictaLab\auth\redis-url.xml',
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Service DictaLab -ErrorAction SilentlyContinue) -or
    -not (Test-Path -LiteralPath 'C:\DictaLab\app\dictalab.jar')) {
    throw 'The existing DictaLab production service is required.'
}
$secretRoot = 'C:\ProgramData\DictaLab\auth'
if ([IO.Path]::GetFullPath($RedisSecretPath) -ne (Join-Path $secretRoot 'redis-url.xml')) {
    throw 'Use the dedicated DPAPI-protected Redis secret file.'
}
$secureUrl = Import-Clixml -LiteralPath $RedisSecretPath
if ($secureUrl -isnot [Security.SecureString]) { throw 'Redis secret must be DPAPI-protected.' }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureUrl)
try { $redisUrl = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secureUrl.Dispose() }
try {
    try { $uri = New-Object Uri($redisUrl) }
    catch { throw 'Invalid Redis URL; secret values are not logged.' }
    if ($uri.Scheme -ne 'rediss' -or -not $uri.Host -or $uri.UserInfo -notmatch ':' -or
        $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -notin @('', '/', '/0')) {
        throw 'A TLS Redis URL using database zero is required.'
    }
    # Spring's URL parser requires an explicit port even for the Redis default.
    $redisUri = New-Object UriBuilder($uri)
    if ($redisUri.Port -lt 0) { $redisUri.Port = 6379 }
    $redisUrl = $redisUri.Uri.AbsoluteUri
    $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey(
        'SYSTEM\CurrentControlSet\Services\DictaLab\Parameters', -not $CheckOnly)
    if (-not $key) { throw 'DictaLab NSSM configuration is missing.' }
    try {
        $previous = [string[]]$key.GetValue('AppEnvironmentExtra')
        if (-not $previous) { throw 'Refuse to replace an empty production environment.' }
        $settings = [ordered]@{}
        foreach ($line in $previous) {
            $parts = $line -split '=', 2
            if ($parts.Count -ne 2) { throw 'Invalid production environment entry.' }
            $settings[$parts[0]] = $parts[1]
        }
        $jwtPath = Join-Path $secretRoot 'jwt-signing-key.xml'
        if ($settings.Contains('JWT_SIGNING_KEY')) {
            $jwtKey = $settings['JWT_SIGNING_KEY']
        } elseif (Test-Path -LiteralPath $jwtPath) {
            $saved = Import-Clixml -LiteralPath $jwtPath
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($saved)
            try { $jwtKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $saved.Dispose() }
        } else {
            $bytes = New-Object byte[] 64
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            $jwtKey = [Convert]::ToBase64String($bytes)
        }
        if ([Convert]::FromBase64String($jwtKey).Length -lt 32) { throw 'Invalid JWT signing key.' }
        if ($CheckOnly) { Write-Output 'REDIS_CLOUD_CONFIG_VALID'; return }

        & icacls.exe $secretRoot /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not protect the auth secret directory.' }
        $backup = Join-Path $secretRoot ('app-env-before-redis-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.xml')
        ConvertTo-SecureString ($previous -join "`n") -AsPlainText -Force | Export-Clixml -LiteralPath $backup
        ConvertTo-SecureString $jwtKey -AsPlainText -Force | Export-Clixml -LiteralPath $jwtPath
        $settings['REDIS_URL'] = $redisUrl
        $settings['JWT_SIGNING_KEY'] = $jwtKey
        $settings['JWT_COOKIE_SECURE'] = 'true'
        $settings['DICTALAB_RATE_LIMIT_BACKEND'] = 'redis'
        $settings['DICTALAB_LISTENING_QUEUE_ENABLED'] = 'false'
        $settings['SPRING_DATA_REDIS_TIMEOUT'] = '2s'
        $settings['SPRING_DATA_REDIS_CONNECT_TIMEOUT'] = '3s'
        $lines = [string[]]@($settings.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
        $key.SetValue('AppEnvironmentExtra', $lines, [Microsoft.Win32.RegistryValueKind]::MultiString)
        Write-Output 'REDIS_CLOUD_CONFIGURED_RESTART_REQUIRED'
        Write-Output ('ENVIRONMENT_BACKUP=' + $backup)
    } finally { $key.Dispose() }
} finally { $redisUrl = $null; $jwtKey = $null }
