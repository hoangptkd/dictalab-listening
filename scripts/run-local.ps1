$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Khong tim thay .env. Hay sao chep .env.example thanh .env va doi POSTGRES_PASSWORD."
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
        throw "Thieu bien $requiredName trong .env."
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
                Write-Host "DictaLab dang chay tai http://localhost:$appPort"
                return
            }
        }
        catch {
            # Cong dang do tien trinh khac su dung
        }
        throw "Cong web co dinh $appPort dang bi tien trinh $($appListener.OwningProcess) su dung."
    }

    $postgresListener = Get-NetTCPConnection -State Listen -LocalPort $postgresPort -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $dictaLabContainer = docker ps --filter 'name=^/dictalab-postgres$' --format '{{.Names}}'
    if ($postgresListener -and $dictaLabContainer -ne 'dictalab-postgres') {
        throw "Cong PostgreSQL co dinh $postgresPort dang bi ung dung khac su dung."
    }

    docker compose up -d --wait --wait-timeout 60 postgres
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL khong khoi dong thanh cong."
    }

    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Spring Boot da dung voi ma loi $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
