param(
    [string]$AppJar = "C:\DictaLab\staging\dictalab.jar",
    [string]$AdminEmails = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$appRoot = "C:\DictaLab"
$appDir = Join-Path $appRoot "app"
$logDir = Join-Path $appRoot "logs"
$mediaDir = Join-Path $appRoot "media"
$secretDir = Join-Path $env:ProgramData "DictaLab"
$secretPath = Join-Path $secretDir "database-secrets.xml"

function New-RandomPassword {
    $bytes = New-Object byte[] 36
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd("=")
}

function Invoke-ChocoInstall {
    param(
        [Parameter(Mandatory = $true)][string]$Package,
        [string[]]$ExtraArguments = @()
    )

    $arguments = @("install", $Package, "-y", "--no-progress") + $ExtraArguments
    & choco.exe @arguments
    if ($LASTEXITCODE -notin @(0, 1605, 1614, 1641, 3010)) {
        throw "Chocolatey failed to install $Package (exit $LASTEXITCODE)"
    }
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force -Path $appDir, $logDir, $mediaDir, $secretDir | Out-Null

if (-not (Get-Command choco.exe -ErrorAction SilentlyContinue)) {
    Set-ExecutionPolicy Bypass -Scope Process -Force
    $installScript = (New-Object Net.WebClient).DownloadString(
        "https://community.chocolatey.org/install.ps1"
    )
    Invoke-Expression $installScript
}
$env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
    [Environment]::GetEnvironmentVariable("Path", "User")

if (Test-Path -LiteralPath $secretPath) {
    $secrets = Import-Clixml -LiteralPath $secretPath
} else {
    $secrets = [pscustomobject]@{
        PostgresPassword = New-RandomPassword
        AppPassword = New-RandomPassword
    }
    $secrets | Export-Clixml -LiteralPath $secretPath
    & icacls.exe $secretPath /inheritance:r /grant "Administrator:F" /grant "Administrators:F" /grant "SYSTEM:F" | Out-Null
}

if (-not (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jre-17*\bin\java.exe" -ErrorAction SilentlyContinue)) {
    Invoke-ChocoInstall -Package "temurin17jre"
}
if (-not (Get-Command nssm.exe -ErrorAction SilentlyContinue)) {
    Invoke-ChocoInstall -Package "nssm"
}
if (-not (Get-Command 7z.exe -ErrorAction SilentlyContinue)) {
    Invoke-ChocoInstall -Package "7zip.commandline"
}

$postgresRoot = Join-Path $appRoot "postgresql-17"
$postgresData = Join-Path $appRoot "postgres-data"
$postgresBin = Join-Path $postgresRoot "bin"
$postgresServiceName = "DictaLabPostgres"

if (-not (Test-Path -LiteralPath (Join-Path $postgresBin "postgres.exe"))) {
    $postgresArchive = Join-Path $appRoot "staging\postgresql-17.11-binaries.zip"
    if (-not (Test-Path -LiteralPath $postgresArchive) -or
        (Get-Item -LiteralPath $postgresArchive).Length -ne 340719294) {
        & curl.exe --fail --location --continue-at - --output $postgresArchive "https://get.enterprisedb.com/postgresql/postgresql-17.11-1-windows-x64-binaries.zip"
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL archive download failed (exit $LASTEXITCODE)"
        }
    }
    if ((Get-Item -LiteralPath $postgresArchive).Length -ne 340719294) {
        throw "Unexpected PostgreSQL archive size"
    }
    $postgresStage = Join-Path $appRoot "staging\postgresql-extracted"
    if (Test-Path -LiteralPath $postgresStage) {
        Remove-Item -LiteralPath $postgresStage -Recurse -Force
    }
    & 7z.exe x $postgresArchive "-o$postgresStage" -y | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL archive extraction failed (exit $LASTEXITCODE)"
    }
    Move-Item -LiteralPath (Join-Path $postgresStage "pgsql") -Destination $postgresRoot
}

if (-not (Test-Path -LiteralPath (Join-Path $postgresData "PG_VERSION"))) {
    # Rotate both passwords because failed installer attempts can echo their arguments.
    $secrets = [pscustomobject]@{
        PostgresPassword = New-RandomPassword
        AppPassword = New-RandomPassword
    }
    $secrets | Export-Clixml -LiteralPath $secretPath
    & icacls.exe $secretPath /inheritance:r /grant "Administrator:F" /grant "Administrators:F" /grant "SYSTEM:F" | Out-Null

    $passwordFile = Join-Path $secretDir "initdb-password.txt"
    Set-Content -LiteralPath $passwordFile -Value $secrets.PostgresPassword -Encoding ascii
    & icacls.exe $passwordFile /inheritance:r /grant "Administrator:F" /grant "Administrators:F" /grant "SYSTEM:F" | Out-Null
    if (Test-Path -LiteralPath $postgresData) {
        Remove-Item -LiteralPath $postgresData -Recurse -Force
    }
    & (Join-Path $postgresBin "initdb.exe") -D $postgresData -U postgres -A scram-sha-256 --pwfile=$passwordFile --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL initdb failed (exit $LASTEXITCODE)"
    }
    Remove-Item -LiteralPath $passwordFile -Force
    Add-Content -LiteralPath (Join-Path $postgresData "postgresql.conf") -Value "`nlisten_addresses = '127.0.0.1'`nport = 5432`n"
}

if (-not (Get-Service -Name $postgresServiceName -ErrorAction SilentlyContinue)) {
    & (Join-Path $postgresBin "pg_ctl.exe") register -N $postgresServiceName -D $postgresData -S auto
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL service registration failed (exit $LASTEXITCODE)"
    }
}
$postgresService = Get-Service -Name $postgresServiceName
Set-Service -Name $postgresServiceName -StartupType Automatic
Start-Service -Name $postgresServiceName

$psql = Join-Path $postgresBin "psql.exe"
$postgresReady = $false
for ($attempt = 1; $attempt -le 30; $attempt++) {
    & (Join-Path $postgresBin "pg_isready.exe") -h 127.0.0.1 -p 5432 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $postgresReady = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $postgresReady) {
    throw "PostgreSQL did not become ready on 127.0.0.1:5432"
}
$env:PGPASSWORD = $secrets.PostgresPassword

$roleExists = (& $psql -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'dictalab_app'" | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Could not authenticate to the PostgreSQL superuser (exit $LASTEXITCODE)"
}
$sqlPassword = $secrets.AppPassword.Replace("'", "''")
if ($roleExists -eq "1") {
    & $psql -h 127.0.0.1 -U postgres -d postgres -v ON_ERROR_STOP=1 -c "ALTER ROLE dictalab_app WITH LOGIN PASSWORD '$sqlPassword'" | Out-Null
} else {
    & $psql -h 127.0.0.1 -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE dictalab_app LOGIN PASSWORD '$sqlPassword'" | Out-Null
}

$databaseExists = (& $psql -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'dictalab'" | Out-String).Trim()
if ($databaseExists -ne "1") {
    & $psql -h 127.0.0.1 -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE dictalab OWNER dictalab_app" | Out-Null
}
Remove-Item Env:PGPASSWORD

if (-not (Test-Path -LiteralPath $AppJar)) {
    throw "Application artifact was not found: $AppJar"
}
$deployedJar = Join-Path $appDir "dictalab.jar"
Copy-Item -LiteralPath $AppJar -Destination $deployedJar -Force

$java = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jre-17*\bin\java.exe" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$nssm = (Get-Command nssm.exe).Source

if (Get-Service -Name "DictaLab" -ErrorAction SilentlyContinue) {
    & $nssm stop DictaLab confirm | Out-Null
} else {
    & $nssm install DictaLab $java | Out-Null
}

& $nssm set DictaLab Application $java | Out-Null
& $nssm set DictaLab AppDirectory $appDir | Out-Null
& $nssm set DictaLab AppParameters "-jar `"$deployedJar`"" | Out-Null
$serviceEnvironment = @(
    "DB_URL=jdbc:postgresql://127.0.0.1:5432/dictalab",
    "DB_USERNAME=dictalab_app",
    "DB_PASSWORD=$($secrets.AppPassword)",
    "PORT=80",
    "SESSION_COOKIE_SECURE=false",
    "DICTALAB_MEDIA_ROOT=$mediaDir"
)
if (-not [string]::IsNullOrWhiteSpace($AdminEmails)) {
    $serviceEnvironment += "DICTALAB_ADMIN_EMAILS=$AdminEmails"
}
& $nssm set DictaLab AppEnvironmentExtra $serviceEnvironment | Out-Null
& $nssm set DictaLab AppStdout (Join-Path $logDir "stdout.log") | Out-Null
& $nssm set DictaLab AppStderr (Join-Path $logDir "stderr.log") | Out-Null
& $nssm set DictaLab AppRotateFiles 1 | Out-Null
& $nssm set DictaLab AppRotateBytes 10485760 | Out-Null
& $nssm set DictaLab AppExit Default Restart | Out-Null
& $nssm set DictaLab DependOnService $postgresService.Name | Out-Null
& $nssm set DictaLab Start SERVICE_AUTO_START | Out-Null

if (-not (Get-NetFirewallRule -Name "DictaLab-HTTP" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name "DictaLab-HTTP" -DisplayName "DictaLab HTTP" -Direction Inbound -Protocol TCP -LocalPort 80 -Action Allow | Out-Null
}

Start-Service DictaLab
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    try {
        $health = Invoke-RestMethod -Uri "http://localhost/actuator/health" -TimeoutSec 5
        if ($health.status -eq "UP") {
            Write-Output "DEPLOYMENT_OK"
            Write-Output "URL=http://localhost/"
            Write-Output "SERVICE=$((Get-Service DictaLab).Status)"
            Write-Output "DATABASE=$((Get-Service $postgresService.Name).Status)"
            exit 0
        }
    } catch {
        if ((Get-Service DictaLab).Status -eq "Stopped") {
            throw "DictaLab service stopped during startup. See $logDir"
        }
    }
} while ((Get-Date) -lt $deadline)

throw "DictaLab health check did not become ready before the timeout"
