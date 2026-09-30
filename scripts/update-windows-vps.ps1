param(
    [string]$ArtifactPath = "C:\DictaLab\staging\dictalab-new.jar",
    [string]$ExpectedSha256 = "",
    [string]$ServiceName = "DictaLab",
    [string]$HealthUrl = "http://127.0.0.1:18082/actuator/health",
    [int]$HealthTimeoutSeconds = 180
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

try {
    Stop-AppService

    if (Test-Path -LiteralPath $previousJar) {
        Remove-Item -LiteralPath $previousJar -Force
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
        Remove-Item -LiteralPath $deployedJar -Force
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
        throw "Deployment failed and the previous version was restored: $deploymentError"
    }
    throw "Deployment failed and rollback is not healthy. Check C:\DictaLab\logs. Original error: $deploymentError"
}
