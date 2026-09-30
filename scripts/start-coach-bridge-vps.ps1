$ErrorActionPreference = 'Stop'
$bridgeRoot = 'C:\DictaLab\coach-bridge'
$env:CHROME_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$env:HOST = '127.0.0.1'
$env:PORT = '3000'
$env:CDP_PORT = '9223'

Set-Location -LiteralPath $bridgeRoot
& 'C:\Program Files\nodejs\node.exe' index.js
if ($LASTEXITCODE -ne 0) { throw "Coach bridge stopped with exit code $LASTEXITCODE." }
