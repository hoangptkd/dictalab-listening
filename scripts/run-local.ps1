$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'

if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Không tìm thấy .env. Hãy sao chép .env.example thành .env và đổi POSTGRES_PASSWORD.'
}

Get-Content -LiteralPath $envPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line.Split('=', 2)
        if ($parts.Count -eq 2) {
            Set-Item -Path "Env:$($parts[0].Trim())" -Value $parts[1].Trim()
        }
    }
}

foreach ($requiredName in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT', 'APP_PORT')) {
    if (-not (Test-Path "Env:$requiredName") -or [string]::IsNullOrWhiteSpace((Get-Item "Env:$requiredName").Value)) {
        throw "Thiếu biến $requiredName trong .env."
    }
}

$env:DB_URL = "jdbc:postgresql://localhost:$env:POSTGRES_PORT/$env:POSTGRES_DB"
$env:DB_USERNAME = $env:POSTGRES_USER
$env:DB_PASSWORD = $env:POSTGRES_PASSWORD
$env:PORT = $env:APP_PORT

Push-Location $projectRoot
try {
    $appPort = [int]$env:APP_PORT
    $postgresPort = [int]$env:POSTGRES_PORT

    $appListener = Get-NetTCPConnection -State Listen -LocalPort $appPort -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($appListener) {
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$appPort/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') {
                Write-Host "DictaLab đang chạy tại http://localhost:$appPort"
                return
            }
        }
        catch {
            # Cổng đang do tiến trình khác sử dụng; báo lỗi rõ ở bên dưới.
        }
        throw "Cổng web cố định $appPort đang bị tiến trình $($appListener.OwningProcess) sử dụng."
    }

    $postgresListener = Get-NetTCPConnection -State Listen -LocalPort $postgresPort -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $dictaLabContainer = docker ps --filter 'name=^/dictalab-postgres$' --format '{{.Names}}'
    if ($postgresListener -and $dictaLabContainer -ne 'dictalab-postgres') {
        throw "Cổng PostgreSQL cố định $postgresPort đang bị ứng dụng khác sử dụng."
    }

    docker compose up -d --wait --wait-timeout 60 postgres
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL không khởi động thành công.'
    }

    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Spring Boot đã dừng với mã lỗi $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
