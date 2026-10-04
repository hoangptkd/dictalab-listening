param([string]$Artifact='C:\DictaLab\staging\dictalab-capacity.jar',
    [int]$Accounts=1000,[int]$SessionsPerUser=24,[int]$HeapMB=1024,[int]$PoolSize=10,
    [switch]$Baseline,[switch]$RealProviders,[ValidateRange(60,3600)][int]$MaxRuntimeSeconds=1800)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$capacityRoot='C:\DictaLab\capacity'
New-Item -ItemType Directory -Force -Path $capacityRoot | Out-Null
if(Get-NetTCPConnection -LocalPort 18282 -State Listen -ErrorAction SilentlyContinue){throw 'Capacity port is already occupied. Stop the exact test process first.'}
$secrets=Import-Clixml -LiteralPath "$env:ProgramData\DictaLab\database-secrets.xml"
$bin='C:\DictaLab\postgresql-17\bin'
$env:PGPASSWORD=$secrets.PostgresPassword
try {
    $exists=& "$bin\psql.exe" -X -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT COUNT(*) FROM pg_database WHERE datname='dictalab_capacity'"
    if($LASTEXITCODE -ne 0){throw 'Database inspection failed.'}
    if($exists.Trim() -eq '0'){
        & "$bin\createdb.exe" -h 127.0.0.1 -U postgres -O dictalab_app dictalab_capacity
        if($LASTEXITCODE -ne 0){throw 'Capacity database creation failed.'}
    }
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
$env:DB_URL='jdbc:postgresql://127.0.0.1:5432/dictalab_capacity'
$env:DB_USERNAME='dictalab_app';$env:DB_PASSWORD=$secrets.AppPassword
$env:CAPACITY_ACCOUNTS=[string]$Accounts;$env:CAPACITY_SESSIONS_PER_USER=[string]$SessionsPerUser
$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
$env:PORT='18282';$env:MANAGEMENT_PORT='18283';$env:DB_POOL_SIZE=[string]$PoolSize
$env:SESSION_COOKIE_SECURE='false';$env:RATE_LIMIT_ENABLED='false'
$env:DICTALAB_COACH_ENABLED='false';$env:DICTALAB_CONVERSATION_ENABLED='false'
$env:DICTALAB_MEDIA_ROOT=Join-Path $capacityRoot 'media'
$env:DICTALAB_PERSONAL_CONTENT_ENABLED='false'
$providerNames=@('SHOPAIKEY_API_KEY','SHOPAIKEY_MODEL','SHOPAIKEY_URL','CARTESIA_API_KEY','CARTESIA_VOICE','CARTESIA_MODEL','CARTESIA_VERSION')
$java='C:\Program Files\Eclipse Adoptium\jre-17.0.20.101-hotspot\bin\java.exe'
try {
if($RealProviders){
    $settings=@{}
    $nssm=(Get-Command nssm.exe -ErrorAction Stop).Source
    foreach($entry in @(& $nssm get DictaLab AppEnvironmentExtra)){
        if($entry -match '^([^=]+)=(.*)$' -and $providerNames -contains $Matches[1]){$settings[$Matches[1]]=$Matches[2]}
    }
    if($LASTEXITCODE -ne 0 -or -not $settings['SHOPAIKEY_API_KEY'] -or -not $settings['CARTESIA_API_KEY']){throw 'Real provider smoke requires existing service credentials; none are printed.'}
    foreach($name in $providerNames){if($settings[$name]){[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}}
    $env:DICTALAB_CONVERSATION_ENABLED='true'
}
    $arguments=@("-Xms256m","-Xmx${HeapMB}m",'-jar',('"'+$Artifact+'"'),'--server.address=127.0.0.1')
    $arguments+='--logging.file.name=C:\DictaLab\capacity\dictalab.json'
    $arguments+='--server.tomcat.threads.max=100'
    $arguments+='--server.tomcat.max-connections=4096'
    if(-not $Baseline){$arguments+='--spring.profiles.active=capacity'}
    $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $capacityRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $capacityRoot 'stdout.log') -RedirectStandardError (Join-Path $capacityRoot 'stderr.log')
    Write-Output "CAPACITY_PID=$($process.Id)"
    Write-Output "CAPACITY_DATABASE=dictalab_capacity"
    Write-Output "CAPACITY_PORT=18282"
    Write-Output "CAPACITY_ARTIFACT_SHA256=$((Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash)"
    # One bounded watchdog also cleans up if the SSH connection or load generator dies.
    $monitor=Join-Path $capacityRoot 'monitor-capacity-vps.ps1'
    if(-not (Test-Path -LiteralPath $monitor -PathType Leaf)){Stop-Process -Id $process.Id;throw 'Capacity watchdog is required.'}
    $watchdog=Start-Process -FilePath powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$monitor+'"'),
        '-Seconds',$MaxRuntimeSeconds,'-CapacityPid',$process.Id,'-StopAtEnd') `
        -RedirectStandardOutput (Join-Path $capacityRoot 'watchdog.stdout.log') `
        -RedirectStandardError (Join-Path $capacityRoot 'watchdog.stderr.log')
    Write-Output "CAPACITY_WATCHDOG_PID=$($watchdog.Id)"
} finally {
    foreach($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','CAPACITY_ACCOUNTS','CAPACITY_SESSIONS_PER_USER','CAPACITY_PASSWORD',
            'PORT','MANAGEMENT_PORT','DB_POOL_SIZE','SESSION_COOKIE_SECURE','RATE_LIMIT_ENABLED','DICTALAB_COACH_ENABLED',
            'DICTALAB_CONVERSATION_ENABLED','DICTALAB_MEDIA_ROOT','DICTALAB_PERSONAL_CONTENT_ENABLED')){
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    foreach($name in $providerNames){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
}
