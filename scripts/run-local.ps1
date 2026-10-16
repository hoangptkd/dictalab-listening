param([switch]$WithCoach, [switch]$WithMessaging)
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

foreach ($requiredName in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT', 'APP_PORT', 'JWT_SIGNING_KEY')) {
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

    if ($WithCoach) {
        if ([string]::IsNullOrWhiteSpace($env:SHOPAIKEY_API_KEY)) {
            throw 'Can SHOPAIKEY_API_KEY trong .env de bat Coach.'
        }
        $env:DICTALAB_COACH_ENABLED = 'true'
        Write-Host 'AI Coach: ShopAIKey API (khong can Chrome/ChatGPT web).'
    }

    if ($WithMessaging) {
        foreach ($requiredName in @('RABBITMQ_USER', 'RABBITMQ_PASSWORD')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredName))) {
                throw "Can $requiredName trong .env de bat Redis/RabbitMQ."
            }
        }
        docker compose --profile messaging up -d --wait --wait-timeout 90 redis rabbitmq
        if ($LASTEXITCODE -ne 0) { throw 'Redis/RabbitMQ khong khoi dong thanh cong.' }
        $env:DICTALAB_RATE_LIMIT_BACKEND = 'redis'
        $env:DICTALAB_LISTENING_QUEUE_ENABLED = 'true'
        $env:DICTALAB_AI_QUEUE_ENABLED = 'true'
        if ([string]::IsNullOrWhiteSpace($env:REDIS_URL)) {
            $redisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '16379' }
            $env:REDIS_URL = "redis://localhost:$redisPort"
        }
        if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_URL)) {
            $rabbitPort = if ($env:RABBITMQ_PORT) { $env:RABBITMQ_PORT } else { '15672' }
            $rabbitUser = [Uri]::EscapeDataString($env:RABBITMQ_USER)
            $rabbitPassword = [Uri]::EscapeDataString($env:RABBITMQ_PASSWORD)
            $env:RABBITMQ_URL = "amqp://${rabbitUser}:${rabbitPassword}@localhost:$rabbitPort"
        }
        Write-Host 'Redis rate limit va RabbitMQ Listening, audio, Coach da bat.'
    }
    elseif ([string]::IsNullOrWhiteSpace($env:REDIS_URL) -and
            ([string]::IsNullOrWhiteSpace($env:REDIS_HOST) -or $env:REDIS_HOST -in @('localhost','127.0.0.1'))) {
        docker compose --profile messaging up -d --wait --wait-timeout 60 redis
        if ($LASTEXITCODE -ne 0) { throw 'Redis cho JWT authentication khong khoi dong thanh cong.' }
        $redisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '16379' }
        $env:REDIS_URL = "redis://localhost:$redisPort"
        Write-Host 'Redis JWT authentication da bat.'
    }

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
    if ($env:DICTALAB_PG_CTL) {
        $pgData = Join-Path $projectRoot 'data/postgres-local'
        if (-not (Test-Path -LiteralPath $env:DICTALAB_PG_CTL) -or -not (Test-Path (Join-Path $pgData 'PG_VERSION'))) {
            throw 'DICTALAB_PG_CTL hoac cluster data/postgres-local khong ton tai.'
        }
        & $env:DICTALAB_PG_CTL status -D $pgData
        $nativeRunning = $LASTEXITCODE -eq 0
        if (-not $nativeRunning) {
            if ($postgresListener) { throw "Cong PostgreSQL $postgresPort dang duoc tien trinh khac su dung." }
            & $env:DICTALAB_PG_CTL start -D $pgData -l (Join-Path $projectRoot 'data/postgres-local-start.log') `
                -o "-h 127.0.0.1 -p $postgresPort" -w -t 30
            if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL native khong khoi dong thanh cong.' }
        }
        elseif (-not $postgresListener -or (Get-Process -Id $postgresListener.OwningProcess).ProcessName -ne 'postgres') {
            throw "Cluster native khong listen tren cong $postgresPort."
        }
    }
    else {
        $dictaLabContainer = docker ps --filter 'name=^/dictalab-postgres$' --format '{{.Names}}'
        if ($postgresListener -and $dictaLabContainer -ne 'dictalab-postgres') {
            throw "Cong PostgreSQL co dinh $postgresPort dang bi ung dung khac su dung."
        }
        docker compose up -d --wait --wait-timeout 60 postgres
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL khong khoi dong thanh cong.' }
    }

    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Spring Boot da dung voi ma loi $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
