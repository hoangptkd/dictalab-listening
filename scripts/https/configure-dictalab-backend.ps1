$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$nssm = (Get-Command nssm.exe -ErrorAction Stop).Source
$original = (& $nssm get DictaLab AppParameters | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $original -notmatch 'dictalab\.jar') {
    throw "Could not read the existing DictaLab service parameters."
}

$secure = '-jar "C:\DictaLab\app\dictalab.jar" ' +
    '--server.port=18082 --server.address=127.0.0.1 ' +
    '--server.servlet.session.cookie.secure=true ' +
    '--server.forward-headers-strategy=framework ' +
    '--dictalab.room.allowed-origins=https://160.191.244.27:18443'

if ($original -eq $secure) {
    Write-Output "BACKEND_ALREADY_CONFIGURED"
    exit 0
}

$changed = $false
try {
    & $nssm set DictaLab AppParameters $secure | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not update DictaLab service parameters." }
    $changed = $true
    Restart-Service DictaLab -ErrorAction Stop

    $deadline = (Get-Date).AddMinutes(3)
    do {
        try {
            if ((Invoke-RestMethod -Uri 'http://127.0.0.1:18082/actuator/health' -TimeoutSec 5).status -eq 'UP') {
                Write-Output "BACKEND_HTTPS_READY"
                exit 0
            }
        } catch { }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    throw "DictaLab did not become healthy on port 18082."
} catch {
    $reason = $_.Exception.Message
    if ($changed) {
        & $nssm set DictaLab AppParameters $original | Out-Null
        Restart-Service DictaLab -ErrorAction SilentlyContinue
    }
    throw "Backend migration failed and service parameters were restored: $reason"
}
