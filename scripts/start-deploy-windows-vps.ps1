$ErrorActionPreference = "Stop"

$scriptPath = "C:\DictaLab\staging\deploy-windows-vps.ps1"
$stdoutPath = "C:\DictaLab\staging\deploy.stdout.log"
$stderrPath = "C:\DictaLab\staging\deploy.stderr.log"

$existing = Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -match "\\deploy-windows-vps\.ps1(?:\s|$)" }
if ($existing) {
    Write-Output "DEPLOY_ALREADY_RUNNING"
    exit 0
}

Set-Content -LiteralPath $stdoutPath -Value "" -Encoding utf8
Set-Content -LiteralPath $stderrPath -Value "" -Encoding utf8
$process = Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-ExecutionPolicy", "Bypass",
    "-NoProfile",
    "-NonInteractive",
    "-File", $scriptPath
) -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru

Write-Output "DEPLOY_STARTED=$($process.Id)"
