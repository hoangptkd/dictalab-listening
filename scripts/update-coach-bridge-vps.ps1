param(
    [string]$StagingDirectory = 'C:\DictaLab\staging',
    [Parameter(Mandatory=$true)][string]$ExpectedSha256
)
$ErrorActionPreference = 'Stop'
# Legacy rollback tool only; not called by the current Gemini deployment workflow.
$ProgressPreference = 'SilentlyContinue'
$bridgeRoot = 'C:\DictaLab\coach-bridge'
$taskName = 'DictaLabCoachBridge'
$node = 'C:\Program Files\nodejs\node.exe'
$files = @('index.js', 'start-coach-bridge-vps.ps1', 'apply-chatgpt-web-patch.ps1', 'chatgpt-web-compat.patch')
foreach ($name in $files) {
    if (-not (Test-Path -LiteralPath (Join-Path $StagingDirectory $name) -PathType Leaf)) { throw "Missing staged bridge file: $name" }
}
if ((Get-FileHash -LiteralPath (Join-Path $StagingDirectory 'index.js')).Hash -ne $ExpectedSha256) { throw 'Bridge SHA-256 mismatch.' }
& $node --check (Join-Path $StagingDirectory 'index.js')
if ($LASTEXITCODE -ne 0) { throw 'Bridge syntax check failed.' }
$task = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
$backup = Join-Path 'C:\DictaLab\backups' ('coach-bridge-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null
$previousFiles = @($files | Where-Object { Test-Path -LiteralPath (Join-Path $bridgeRoot $_) })
foreach ($name in $previousFiles) { Copy-Item -LiteralPath (Join-Path $bridgeRoot $name) -Destination (Join-Path $backup $name) }
$originalTimeLimit = $task.Settings.ExecutionTimeLimit

function Stop-Bridge {
    $listener = Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if ($process.Name -ne 'node.exe' -or $process.CommandLine -notmatch 'index\.js(?:\s|$)') { throw 'Port 3000 belongs to another application.' }
    }
    $chromeListener = Get-NetTCPConnection -State Listen -LocalPort 9223 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($chromeListener) {
        $chrome = Get-CimInstance Win32_Process -Filter "ProcessId=$($chromeListener.OwningProcess)"
        if ($chrome.Name -ne 'chrome.exe' -or $chrome.CommandLine -notmatch [regex]::Escape("$bridgeRoot\data\chrome-profile")) { throw 'CDP port belongs to another Chrome profile.' }
    }
    # Close this dedicated Chrome through CDP so its persistent login profile is flushed.
    # Do this before Task Scheduler terminates the Chrome parent process.
    if ($chromeListener -and (Get-NetTCPConnection -State Listen -LocalPort 9223 -ErrorAction SilentlyContinue)) {
        Push-Location -LiteralPath $bridgeRoot
        try {
            & $node -e "require('playwright').chromium.connectOverCDP('http://127.0.0.1:9223').then(async b=>{const c=await b.newBrowserCDPSession();await c.send('Browser.close').catch(()=>{});}).catch(()=>process.exit(1));"
            if ($LASTEXITCODE -ne 0) { throw 'Could not close the bridge Chrome cleanly.' }
        } finally { Pop-Location }
    }
    Stop-ScheduledTask -TaskName $taskName
    if ($listener -and (Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue)) {
        Stop-Process -Id $listener.OwningProcess -Force
    }
    $deadline = (Get-Date).AddSeconds(15)
    while (Get-NetTCPConnection -State Listen -LocalPort 3000,9223 -ErrorAction SilentlyContinue) {
        if ((Get-Date) -ge $deadline) { throw 'Bridge/Chrome ports did not stop.' }
        Start-Sleep -Milliseconds 500
    }
}

function Start-Bridge {
    Start-ScheduledTask -TaskName $taskName
    $deadline = (Get-Date).AddSeconds(60)
    do {
        try {
            if ((Invoke-RestMethod 'http://127.0.0.1:3000/health' -TimeoutSec 3).success) { return }
        } catch {}
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Bridge did not become healthy.'
}

try {
    Stop-Bridge
    foreach ($name in $files) { Copy-Item -LiteralPath (Join-Path $StagingDirectory $name) -Destination (Join-Path $bridgeRoot $name) -Force }
    # A bridge is a long-running process; Task Scheduler must not stop it after 72 hours.
    $task.Settings.ExecutionTimeLimit = 'PT0S'
    Set-ScheduledTask -TaskName $taskName -Settings $task.Settings | Out-Null
    Start-Bridge
    $chromeListener = Get-NetTCPConnection -State Listen -LocalPort 9223 | Select-Object -First 1
    $chrome = Get-CimInstance Win32_Process -Filter "ProcessId=$($chromeListener.OwningProcess)"
    $expectedHeadless = ($task.Actions.Arguments -join ' ') -notmatch '(?:^|\s)-VisibleChrome(?:\s|$)'
    $actualHeadless = $chrome.CommandLine -match '--headless(?:\s|$)'
    if ($actualHeadless -ne $expectedHeadless) { throw 'Chrome did not restart in the configured mode.' }
    Write-Output 'BRIDGE_DEPLOYMENT_OK'
    Write-Output "BRIDGE_BACKUP=$backup"
} catch {
    $failure = $_.Exception.Message
    Stop-Bridge
    foreach ($name in $files) {
        $destination = Join-Path $bridgeRoot $name
        if ($previousFiles -contains $name) { Copy-Item -LiteralPath (Join-Path $backup $name) -Destination $destination -Force }
        elseif (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination }
    }
    $task.Settings.ExecutionTimeLimit = $originalTimeLimit
    Set-ScheduledTask -TaskName $taskName -Settings $task.Settings | Out-Null
    Start-Bridge
    throw "Bridge deployment failed; previous version restored: $failure"
}
