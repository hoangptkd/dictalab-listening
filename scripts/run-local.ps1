param([switch]$WithCoach)
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

    if ($WithCoach) {
        $bridgeRoot = Join-Path $projectRoot 'data/chatgpt-api-web'
        if (-not (Test-Path (Join-Path $bridgeRoot 'index.js'))) {
            & git clone 'https://github.com/MrBalourd/chatgpt-api-web.git' $bridgeRoot
            if ($LASTEXITCODE -ne 0) { throw 'Khong tai duoc chatgpt-api-web.' }
        }
        Push-Location $bridgeRoot
        try {
            if (-not (Test-Path 'node_modules/playwright')) {
                & npm ci --ignore-scripts
                if ($LASTEXITCODE -ne 0) { throw 'Khong cai duoc bridge dependencies.' }
            }
            $compatPatch = Join-Path $PSScriptRoot 'chatgpt-web-compat.patch'
            & git apply --reverse --check $compatPatch 2>$null
            if ($LASTEXITCODE -ne 0) {
                & git apply --check $compatPatch
                if ($LASTEXITCODE -ne 0) { throw 'Bridge upstream da thay doi; can kiem tra compatibility patch.' }
                & git apply $compatPatch
                if ($LASTEXITCODE -ne 0) { throw 'Khong ap dung duoc bridge compatibility patch.' }
            }
            $bridgeReady = $false
            try { $bridgeReady = (Invoke-RestMethod 'http://127.0.0.1:3000/health' -TimeoutSec 3).success -eq $true } catch {}
            if (-not $bridgeReady) {
                if (Get-NetTCPConnection -State Listen -LocalPort 3000 -ErrorAction SilentlyContinue) {
                    throw 'Cong bridge 3000 dang duoc tien trinh khac su dung.'
                }
                $chromePath = $env:DICTALAB_COACH_CHROME_PATH
                if (-not $chromePath) {
                    $chromePath = @("$env:ProgramFiles/Google/Chrome/Application/chrome.exe", "$env:LOCALAPPDATA/Google/Chrome/Application/chrome.exe") |
                        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
                }
                if (-not $chromePath -or -not (Test-Path -LiteralPath $chromePath)) { throw 'Khong tim thay Chrome. Dat DICTALAB_COACH_CHROME_PATH trong .env.' }
                # Bridge's PORT differs from the web app's PORT. Restore it before Maven starts.
                $env:CHROME_PATH = $chromePath
                $env:CDP_PORT = '9223'
                $env:HOST = '127.0.0.1'
                $env:PORT = '3000'
                try {
                    $bridge = Start-Process -FilePath (Get-Command node).Source -ArgumentList 'index.js' -WorkingDirectory $bridgeRoot `
                        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $bridgeRoot 'bridge.out.log') `
                        -RedirectStandardError (Join-Path $bridgeRoot 'bridge.err.log')
                } finally { $env:PORT = $env:APP_PORT }
                $deadline = (Get-Date).AddSeconds(30)
                do {
                    try { $bridgeReady = (Invoke-RestMethod 'http://127.0.0.1:3000/health' -TimeoutSec 2).success -eq $true } catch {}
                    if ($bridge.HasExited) { throw 'Bridge da dung. Xem data/chatgpt-api-web/bridge.err.log.' }
                    if (-not $bridgeReady) { Start-Sleep -Milliseconds 500 }
                } while (-not $bridgeReady -and (Get-Date) -lt $deadline)
                if (-not $bridgeReady) { throw 'Bridge chua san sang. Xem data/chatgpt-api-web/bridge.out.log.' }
            }
        } finally { Pop-Location }
        $env:DICTALAB_COACH_ENABLED = 'true'
        $env:DICTALAB_COACH_URL = 'http://127.0.0.1:3000/v1/chat/completions'
        Write-Host 'ChatGPT bridge: http://127.0.0.1:3000. Dang nhap ChatGPT trong Chrome neu duoc yeu cau.'
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
