param(
    [string]$ArtifactPath = "C:\DictaLab\staging\dictalab-new.jar",
    [string]$ExpectedSha256 = "",
    [string]$ServiceName = "DictaLab",
    [string]$HealthUrl = "http://127.0.0.1:18082/actuator/health",
    [int]$HealthTimeoutSeconds = 180,
    [ValidateRange(512,16384)][int]$MinimumReleaseMemoryMiB = 1000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$appDirectory = "C:\DictaLab\app"
$deployedJar = Join-Path $appDirectory "dictalab.jar"
$previousJar = Join-Path $appDirectory "dictalab.previous.jar"

function Stop-AppService {
    $service = Get-Service -Name $ServiceName -ErrorAction Stop
    if ($service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        Stop-Service -Name $ServiceName -Force -ErrorAction Stop
        $service.WaitForStatus(
            [System.ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(45)
        )
    }
}

function Wait-AppHealth {
    param([Parameter(Mandatory = $true)][int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 5
            if ($health.status -eq "UP") {
                return $true
            }
        } catch {
            # The service can reject connections while the JVM and Flyway start.
        }

        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)

    return $false
}

if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
    throw "Deployment artifact was not found: $ArtifactPath"
}

$artifact = Get-Item -LiteralPath $ArtifactPath
if ($artifact.Length -lt 1MB) {
    throw "Deployment artifact is unexpectedly small: $($artifact.Length) bytes"
}

$actualSha256 = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
if ($ExpectedSha256 -and
    -not $actualSha256.Equals($ExpectedSha256.Trim(), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Artifact SHA-256 does not match the value produced by GitHub Actions"
}

if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
    throw "Windows service '$ServiceName' does not exist. Run the bootstrap deploy first."
}
if (-not (Test-Path -LiteralPath $deployedJar -PathType Leaf)) {
    throw "Currently deployed JAR was not found: $deployedJar"
}

$currentWasBackedUp = $false
$newJarInstalled = $false

# The stopped app's memory can be reused by the isolated restore JVM. Do not stop
# a healthy production service when shared-host memory cannot cover that release gate.
$serviceInfo = Get-CimInstance Win32_Service | Where-Object Name -eq $ServiceName
$appBytes = (Get-CimInstance Win32_Process | Where-Object {
    $_.ParentProcessId -eq $serviceInfo.ProcessId -and $_.Name -eq 'java.exe'
} | Measure-Object WorkingSetSize -Sum).Sum
$availableMiB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024
if (($availableMiB + $appBytes / 1MB) -lt $MinimumReleaseMemoryMiB) {
    throw 'Insufficient release memory; production was not stopped. Free shared-host RAM before retrying.'
}

try {
    Stop-AppService
    if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024 -lt $MinimumReleaseMemoryMiB) {
        throw 'Shared-host RAM dropped below the release budget after stopping the app.'
    }

    # Apply additive constraints only after a read-only legacy-data preflight.
    $preflight=Join-Path $PSScriptRoot 'readiness-preflight.sql'
    $backupScript=Join-Path $PSScriptRoot 'backup-windows-vps.ps1'
    $restoreScript=Join-Path $PSScriptRoot 'restore-drill-windows-vps.ps1'
    foreach($path in @($preflight,$backupScript,$restoreScript)){
        if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw "Release gate is missing: $path"}
    }
    $secrets=Import-Clixml -LiteralPath "$env:ProgramData\DictaLab\database-secrets.xml"
    $env:PGPASSWORD=$secrets.AppPassword
    try {
        $rows=@(& 'C:\DictaLab\postgresql-17\bin\psql.exe' -X -h 127.0.0.1 -U dictalab_app -d dictalab -v ON_ERROR_STOP=1 -At -F '|' -f $preflight)
        if($LASTEXITCODE -ne 0){throw 'Read-only release preflight failed.'}
        $checked=0
        foreach($row in $rows){
            if($row -match '^([a-z_]+)\|(\d+)$' -and $Matches[1] -ne 'enabled_admins'){
                $checked++
                if([long]$Matches[2] -ne 0){throw "Legacy data violates $($Matches[1]); no data was repaired or deleted."}
            }
        }
        if($checked -ne 16){throw 'Preflight did not return all expected invariant checks.'}
    }finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
    & $backupScript -IncludeMedia
    & $restoreScript -SmokeArtifact $ArtifactPath -CompatibilityArtifact $deployedJar

    $archiveDirectory='C:\DictaLab\backups\artifacts'
    New-Item -ItemType Directory -Force -Path $archiveDirectory | Out-Null
    $archiveStamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    Copy-Item -LiteralPath $deployedJar -Destination (Join-Path $archiveDirectory "dictalab-$archiveStamp.jar")
    [IO.File]::WriteAllText((Join-Path $archiveDirectory "dictalab-$archiveStamp.sha256"),
        (Get-FileHash -LiteralPath $deployedJar -Algorithm SHA256).Hash,[Text.UTF8Encoding]::new($false))

    if (Test-Path -LiteralPath $previousJar) {
        Move-Item -LiteralPath $previousJar -Destination (Join-Path $archiveDirectory "dictalab-previous-$archiveStamp.jar")
    }
    Move-Item -LiteralPath $deployedJar -Destination $previousJar
    $currentWasBackedUp = $true

    Move-Item -LiteralPath $ArtifactPath -Destination $deployedJar
    $newJarInstalled = $true

    Start-Service -Name $ServiceName -ErrorAction Stop
    if (-not (Wait-AppHealth -TimeoutSeconds $HealthTimeoutSeconds)) {
        throw "The new version did not become healthy within $HealthTimeoutSeconds seconds"
    }

    Write-Output "DEPLOYMENT_OK"
    Write-Output "SHA256=$actualSha256"
    Write-Output "SERVICE=$((Get-Service -Name $ServiceName).Status)"
    exit 0
} catch {
    $deploymentError = $_.Exception.Message
    Write-Warning "Deployment failed: $deploymentError"
    Write-Warning "Restoring the previously deployed JAR"

    try {
        Stop-AppService
    } catch {
        Write-Warning "Could not stop the failed service cleanly: $($_.Exception.Message)"
    }

    if ($newJarInstalled -and (Test-Path -LiteralPath $deployedJar)) {
        Move-Item -LiteralPath $deployedJar -Destination (Join-Path $archiveDirectory "dictalab-failed-$archiveStamp.jar")
    }
    if ($currentWasBackedUp -and (Test-Path -LiteralPath $previousJar)) {
        Move-Item -LiteralPath $previousJar -Destination $deployedJar -Force
    }

    $rollbackHealthy = $false
    if (Test-Path -LiteralPath $deployedJar) {
        try {
            Start-Service -Name $ServiceName -ErrorAction Stop
            $rollbackHealthy = Wait-AppHealth -TimeoutSeconds $HealthTimeoutSeconds
        } catch {
            Write-Warning "Rollback start failed: $($_.Exception.Message)"
        }
    }

    if ($rollbackHealthy) {
        throw "Deployment failed and the previous JAR was restored. Schema was NOT rolled back: $deploymentError"
    }
    throw "Deployment failed and rollback is not healthy. Check C:\DictaLab\logs. Original error: $deploymentError"
}
