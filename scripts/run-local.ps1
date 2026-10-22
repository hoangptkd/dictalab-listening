param([switch]$WithCoach, [switch]$WithMessaging, [switch]$WithConversationLocal)
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
            $value = $parts[1].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or
                    ($value.StartsWith('"') -and $value.EndsWith('"')))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            Set-Item -Path "Env:$($parts[0].Trim())" -Value $value
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
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DICTALAB_AI_TEXT_PROVIDER = 'ollama'
$env:DICTALAB_AI_PRIMARY_PROVIDER = 'ollama'
$env:DICTALAB_AI_PRODUCTION_MODE = 'false'
if ($env:DICTALAB_CONVERSATION_GENERATION_PROVIDER -ne 'python') {
    $env:DICTALAB_CONVERSATION_GENERATION_PROVIDER = 'ollama'
}
$env:DICTALAB_COACH_PROVIDER = 'ollama'

Push-Location $projectRoot
try {
    $appPort = [int]$env:APP_PORT
    $postgresPort = [int]$env:POSTGRES_PORT

    if ($WithConversationLocal -or ($env:DICTALAB_CONVERSATION_ENABLED -eq 'true' -and $env:DICTALAB_CONVERSATION_GENERATION_PROVIDER -eq 'ollama')) {
        $env:DICTALAB_CONVERSATION_GENERATION_PROVIDER = 'ollama'
        $conversationBase = if ($env:DICTALAB_CONVERSATION_LOCAL_BASE_URL) { $env:DICTALAB_CONVERSATION_LOCAL_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:11434' }
        $conversationUri = [Uri]$conversationBase
        if ($conversationUri.Scheme -notin @('http','https') -or
                $conversationUri.Host -notin @('localhost','127.0.0.1','[::1]') -or
                $conversationUri.UserInfo -or $conversationUri.Query -or $conversationUri.Fragment -or
                $conversationUri.AbsolutePath -ne '/') { throw 'Conversation Ollama URL phai la loopback, khong co credentials/path.' }
        $conversationModel = if ($env:DICTALAB_CONVERSATION_LOCAL_MODEL) { $env:DICTALAB_CONVERSATION_LOCAL_MODEL } else { 'qwen3.5:9b' }
        if ($conversationModel -match ':cloud|-cloud') { throw 'Chi dung model Ollama local.' }
        $conversationTimeout = if ($env:DICTALAB_CONVERSATION_LOCAL_TIMEOUT_SECONDS) { [int]$env:DICTALAB_CONVERSATION_LOCAL_TIMEOUT_SECONDS } else { 180 }
        if ($conversationTimeout -eq 0) { $conversationTimeout = 180 }
        if ($conversationTimeout -lt 1 -or $conversationTimeout -gt 600) { throw 'Conversation local timeout phai tu 1 den 600 giay.' }
        $conversationModels = Invoke-RestMethod "$conversationBase/api/tags" -TimeoutSec 5 -MaximumRedirection 0
        if ($conversationModel -notin @($conversationModels.models.name)) { throw "Ollama chua co model $conversationModel." }
        if ([string]::IsNullOrWhiteSpace($env:CARTESIA_API_KEY)) {
            throw 'Can Cartesia cho audio. AI van ban va danh gia dung Ollama local.'
        }
        $env:DICTALAB_CONVERSATION_ENABLED = 'true'
        # Load weights before serving; the actual generation still has its own bounded deadline.
        $warmup = @{model=$conversationModel; stream=$false; keep_alive='5m'; options=@{num_ctx=8192}} | ConvertTo-Json -Depth 3
        $null = Invoke-RestMethod "$conversationBase/api/generate" -Method Post -ContentType 'application/json' -Body $warmup -TimeoutSec $conversationTimeout -MaximumRedirection 0
        Write-Host "AI van ban va danh gia: Ollama local / $conversationModel. TTS: Cartesia."
    }

    if ($WithCoach) {
        $coachProvider = if ($env:DICTALAB_COACH_PROVIDER) { $env:DICTALAB_COACH_PROVIDER } else { 'gemini' }
        if ($coachProvider -eq 'ollama') {
            $coachBase = if ($env:DICTALAB_COACH_LOCAL_BASE_URL) { $env:DICTALAB_COACH_LOCAL_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:11434' }
            $coachModel = if ($env:DICTALAB_COACH_LOCAL_MODEL) { $env:DICTALAB_COACH_LOCAL_MODEL } else { 'qwen3.5:4b' }
            $coachModels = Invoke-RestMethod "$coachBase/api/tags" -TimeoutSec 5
            if ($coachModel -notin @($coachModels.models.name)) { throw "Ollama chua co model $coachModel. Hay tai model truoc." }
            Write-Host "AI Coach: Ollama local / $coachModel."
        }
        elseif ($coachProvider -ne 'gemini') { throw 'DICTALAB_COACH_PROVIDER chi nhan gemini hoac ollama.' }
        elseif ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
            throw 'Can GEMINI_API_KEY trong .env de bat Coach.'
        }
        $env:DICTALAB_COACH_ENABLED = 'true'
        if ($coachProvider -eq 'gemini') { Write-Host 'AI Coach: Gemini API.' }
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
                if ($env:DICTALAB_CONVERSATION_GENERATION_PROVIDER -eq 'ollama') {
                    Write-Warning 'App dang chay; cau hinh Conversation local chi ap dung sau khi khoi dong lai app.'
                }
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
