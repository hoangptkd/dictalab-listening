param(
    [string]$VpsHost = "160.191.244.27",
    [string]$VpsUser = "Administrator",
    [string]$SshKey = "C:\Users\HP\.ssh\dictalab_vps_ed25519_v2",
    [string]$HealthUrl = "https://160.191.244.27:18443/actuator/health"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  DictaLab Automatic VPS Deployer" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Kiem tra file SSH key
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "Khong tim thay SSH private key tai: $SshKey"
}

$sshCommonArgs = @(
    "-i", $SshKey,
    "-o", "BatchMode=yes",
    "-o", "IdentitiesOnly=yes",
    "-o", "StrictHostKeyChecking=accept-new"
)

# 2. Kiem tra ket noi toi VPS
Write-Host "[1/5] Kiem tra ket noi SSH toi $VpsUser@$VpsHost..." -ForegroundColor Yellow
$whoamiResult = & ssh @sshCommonArgs "$VpsUser@$VpsHost" "whoami"
if ($LASTEXITCODE -ne 0) {
    throw "Ket noi SSH that bai (exit code $LASTEXITCODE)."
}
Write-Host "      -> Ket noi thanh cong voi user: $($whoamiResult.Trim())" -ForegroundColor Green

# 3. Dong goi ung dung (mvn clean package)
Write-Host "[2/5] Dong goi ung dung (mvn clean package)..." -ForegroundColor Yellow
Push-Location $projectRoot
try {
    & mvn --batch-mode --no-transfer-progress clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Build Maven that bai (exit code $LASTEXITCODE)."
    }

    $jarFile = Get-ChildItem -Path "target" -Filter "*.jar" |
        Where-Object { $_.Name -notmatch "\.original$" -and $_.Name -notmatch "-sources\.jar$" -and $_.Name -notmatch "-javadoc\.jar$" } |
        Select-Object -First 1

    if (-not $jarFile) {
        throw "Khong tim thay file JAR trong thu muc target."
    }

    $fileHash = (Get-FileHash -LiteralPath $jarFile.FullName -Algorithm SHA256).Hash
    Write-Host "      -> Da tao JAR: $($jarFile.Name) ($([math]::Round($jarFile.Length / 1MB, 1)) MB)" -ForegroundColor Green
    Write-Host "      -> SHA256: $fileHash" -ForegroundColor Green

    # Tao ban sao dictalab-new.jar de tien upload
    $stagingJarLocal = Join-Path $projectRoot "dictalab-new.jar"
    Copy-Item -LiteralPath $jarFile.FullName -Destination $stagingJarLocal -Force

    # 4. Dam bao thu muc staging tren VPS va upload
    Write-Host "[3/5] Tai ban build len VPS (C:\DictaLab\staging)..." -ForegroundColor Yellow
    & ssh @sshCommonArgs "$VpsUser@$VpsHost" "powershell.exe -Command md -Force C:\DictaLab\staging" | Out-Null

    $updateScriptLocal = Join-Path $projectRoot "scripts\update-windows-vps.ps1"
    $configScriptLocal = Join-Path $projectRoot "scripts\configure-admin-windows-vps.ps1"

    $remoteDestination = "${VpsUser}@${VpsHost}:C:/DictaLab/staging/"
    & scp @sshCommonArgs $stagingJarLocal $updateScriptLocal $configScriptLocal $remoteDestination
    if ($LASTEXITCODE -ne 0) {
        throw "Upload file len VPS that bai qua scp (exit code $LASTEXITCODE)."
    }
    Write-Host "      -> Da tai len staging thanh cong." -ForegroundColor Green

    # 5. Kich hoat phien ban moi tren VPS
    Write-Host "[4/5] Kich hoat phien ban moi tren VPS (update-windows-vps.ps1)..." -ForegroundColor Yellow
    $remoteCommand = "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\DictaLab\staging\update-windows-vps.ps1 -ArtifactPath C:\DictaLab\staging\dictalab-new.jar -ExpectedSha256 $fileHash"
    & ssh @sshCommonArgs "$VpsUser@$VpsHost" $remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Kich hoat ban build tren VPS that bai (exit code $LASTEXITCODE)."
    }
    Write-Host "      -> Service da duoc cap nhat va khoi dong lai." -ForegroundColor Green

    # 6. Kiem tra health check tu ben ngoai
    Write-Host "[5/5] Kiem tra trang thai dich vu public..." -ForegroundColor Yellow
    Start-Sleep -Seconds 3
    $healthResponse = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 10
    if ($healthResponse.status -eq "UP") {
        Write-Host "      -> Health check UP thanh cong!" -ForegroundColor Green
    } else {
        Write-Warning "      -> Health check tra ve: $($healthResponse.status)"
    }

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host "  DEPLOY THANH CONG TREN VPS!" -ForegroundColor Green
    Write-Host "  URL: https://${VpsHost}:18443/" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
}
finally {
    $tempJar = Join-Path $projectRoot "dictalab-new.jar"
    if (Test-Path -LiteralPath $tempJar) {
        Remove-Item -LiteralPath $tempJar -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
