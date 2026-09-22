param(
    [Parameter(Mandatory = $true)]
    [string]$AdminEmails,
    [string]$ServiceName = "DictaLab",
    [string]$MediaRoot = "C:\DictaLab\media"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($AdminEmails)) {
    throw "AdminEmails cannot be empty. Separate multiple addresses with commas."
}
if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
    throw "Windows service '$ServiceName' does not exist."
}
if (-not (Get-Command nssm.exe -ErrorAction SilentlyContinue)) {
    throw "nssm.exe was not found in PATH."
}

$normalizedEmails = ($AdminEmails.Split(",") |
    ForEach-Object { $_.Trim().ToLowerInvariant() } |
    Where-Object { $_ }) -join ","
if (-not $normalizedEmails) {
    throw "No valid administrator email address was provided."
}

$resolvedMediaRoot = [System.IO.Path]::GetFullPath($MediaRoot)
if (-not $resolvedMediaRoot.StartsWith("C:\DictaLab\", [StringComparison]::OrdinalIgnoreCase)) {
    throw "MediaRoot must be located inside C:\DictaLab\."
}
New-Item -ItemType Directory -Force -Path $resolvedMediaRoot | Out-Null

[Environment]::SetEnvironmentVariable("DICTALAB_ADMIN_EMAILS", $normalizedEmails, "Machine")
[Environment]::SetEnvironmentVariable("DICTALAB_MEDIA_ROOT", $resolvedMediaRoot, "Machine")

$nssm = (Get-Command nssm.exe).Source
$existingEnvironment = @(& $nssm get $ServiceName AppEnvironmentExtra)
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the current environment for service '$ServiceName'."
}
$serviceEnvironment = @($existingEnvironment | Where-Object {
    $_ -and
    -not $_.StartsWith("DICTALAB_ADMIN_EMAILS=", [StringComparison]::OrdinalIgnoreCase) -and
    -not $_.StartsWith("DICTALAB_MEDIA_ROOT=", [StringComparison]::OrdinalIgnoreCase)
})
$serviceEnvironment += "DICTALAB_ADMIN_EMAILS=$normalizedEmails"
$serviceEnvironment += "DICTALAB_MEDIA_ROOT=$resolvedMediaRoot"
& $nssm set $ServiceName AppEnvironmentExtra $serviceEnvironment | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not update the environment for service '$ServiceName'."
}

Restart-Service -Name $ServiceName -Force
$service = Get-Service -Name $ServiceName
$service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(45))

Write-Output "ADMIN_CONFIGURATION_OK"
Write-Output "ADMIN_EMAILS=$normalizedEmails"
Write-Output "MEDIA_ROOT=$resolvedMediaRoot"
Write-Output "Sign out and sign in again to refresh the ADMIN role."
