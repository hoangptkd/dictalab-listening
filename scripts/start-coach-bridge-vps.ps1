param([switch]$VisibleChrome)
# Legacy rollback tool only. The current application uses Gemini and does not start this bridge.
$ErrorActionPreference = 'Stop'
$bridgeRoot = 'C:\DictaLab\coach-bridge'
$env:CHROME_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$env:HOST = '127.0.0.1'
$env:PORT = '3000'
$env:CDP_PORT = '9223'
# Use -VisibleChrome for the first login or when ChatGPT asks for verification.
$env:CHROME_HEADLESS = if ($VisibleChrome) { 'false' } else { 'true' }
if (-not $env:BRIDGE_DEBUG) { $env:BRIDGE_DEBUG = 'false' }

Set-Location -LiteralPath $bridgeRoot
# Source checkouts need patching; release copies ship the reviewed, patched index.js.
if (Test-Path -LiteralPath (Join-Path $bridgeRoot '.git')) {
    & (Join-Path $PSScriptRoot 'apply-chatgpt-web-patch.ps1') -BridgeRoot $bridgeRoot
}
& 'C:\Program Files\nodejs\node.exe' index.js
if ($LASTEXITCODE -ne 0) { throw "Coach bridge stopped with exit code $LASTEXITCODE." }
