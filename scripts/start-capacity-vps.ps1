param([string]$Artifact='C:\DictaLab\staging\dictalab-capacity.jar',
    [ValidateRange(1,20000)][int]$Accounts=600,[ValidateRange(0,1000)][int]$SessionsPerUser=6,
    [ValidateRange(256,1024)][int]$HeapMB=512,[ValidateRange(1,30)][int]$PoolSize=10,
    [switch]$Baseline,[switch]$RealProviders,[ValidateRange(60,14400)][int]$MaxRuntimeSeconds=1800,
    [switch]$EvidenceSuite,[switch]$DedicatedRedis,
    [ValidatePattern('^dictalab_capacity(?:_[a-z0-9]+)?$')][string]$DatabaseName='dictalab_capacity',
    [string]$FakeProviderUrl='http://127.0.0.1:18380/v1/chat/completions')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$capacityRoot='C:\DictaLab\capacity'
New-Item -ItemType Directory -Force -Path $capacityRoot | Out-Null
if($RealProviders){throw 'Real-AI smoke needs an enforced call/currency budget. No production provider credentials are copied.'}
if([IO.Path]::GetFullPath($Artifact) -notmatch '^C:\\DictaLab\\staging\\dictalab-(capacity|baseline)\.jar$'){
    throw 'Artifact must match the exact capacity watchdog target.'
}
if(-not $env:CAPACITY_REDIS_URL -or -not $DedicatedRedis){throw 'A dedicated capacity Redis endpoint is required; production Redis is never inherited.'}
$redisUri=[Uri]$env:CAPACITY_REDIS_URL
if($redisUri.Scheme -ne 'rediss' -and -not ($redisUri.Scheme -eq 'redis' -and $redisUri.Host -in @('127.0.0.1','localhost'))){throw 'Capacity Redis requires TLS except on loopback.'}
$secretPath=Join-Path $env:ProgramData 'DictaLab\auth\redis-url.xml'
if(Test-Path -LiteralPath $secretPath){
    $secure=Import-Clixml -LiteralPath $secretPath
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try{$productionUri=[Uri][Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
    finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$secure.Dispose()}
    if($redisUri.Host -eq $productionUri.Host){throw 'Shared production Redis is not an isolated load-test target, even on another logical database.'}
    $productionUri=$null
}
$java='C:\Program Files\Eclipse Adoptium\jre-17.0.20.101-hotspot\bin\java.exe'
& $java -cp $capacityRoot RedisIsolationCheck
if($LASTEXITCODE -ne 0){throw 'Dedicated Redis must be empty and reachable before starting the test app.'}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
if($EvidenceSuite){
    if($Baseline -or $RealProviders -or $DatabaseName -eq 'dictalab_capacity'){throw 'Evidence requires a fresh suffixed database and fake provider, not the historical fixture.'}
    $uri=[Uri]$FakeProviderUrl
    if($uri.Scheme -ne 'http' -or $uri.Host -notin @('127.0.0.1','localhost') -or $uri.AbsolutePath -ne '/v1/chat/completions'){
        throw 'The fake provider must be loopback-only.'
    }
    $fake=Invoke-RestMethod ($uri.GetLeftPart([UriPartial]::Authority)+'/health') -TimeoutSec 3
    if($fake.provider -ne 'fake' -or $fake.mode -ne 'valid'){throw 'Bulk evidence requires the healthy valid-mode fake provider.'}
    $preflight=Join-Path $capacityRoot "evidence-preflight-$stamp.csv"
    & (Join-Path $capacityRoot 'monitor-capacity-vps.ps1') -Seconds 70 -Output $preflight
    $samples=@(Import-Csv -LiteralPath $preflight)
    if($samples.Count -lt 10 -or ($samples|Measure-Object CPUPercent -Average).Average -gt 35 -or
        ($samples|Measure-Object CPUPercent -Maximum).Maximum -gt 60 -or ($samples|Measure-Object FreeRAMMiB -Minimum).Minimum -lt 1800 -or
        ($samples|Measure-Object DiskQueue -Maximum).Maximum -gt 2 -or ($samples|Measure-Object PagesInputSec -Maximum).Maximum -gt 100 -or
        ($samples|Measure-Object FreeDiskGiB -Minimum).Minimum -lt 10){
        throw 'Existing preflight resource guard declined; no app/DB load started.'
    }
}
if(Get-NetTCPConnection -LocalPort 18282 -State Listen -ErrorAction SilentlyContinue){throw 'Capacity port is already occupied. Stop the exact test process first.'}
$secrets=Import-Clixml -LiteralPath "$env:ProgramData\DictaLab\database-secrets.xml"
$bin='C:\DictaLab\postgresql-17\bin'
$previousPGPassword=$env:PGPASSWORD;$env:PGPASSWORD=$secrets.PostgresPassword
try {
    $exists=& "$bin\psql.exe" -X -h 127.0.0.1 -U postgres -d postgres -tAc "SELECT COUNT(*) FROM pg_database WHERE datname='$DatabaseName'"
    if($LASTEXITCODE -ne 0){throw 'Database inspection failed.'}
    if($EvidenceSuite -and $exists.Trim() -ne '0'){throw 'Evidence database already exists; choose a new suffix, never overwrite old evidence.'}
    if($exists.Trim() -eq '0'){
        & "$bin\createdb.exe" -h 127.0.0.1 -U postgres -O dictalab_app $DatabaseName
        if($LASTEXITCODE -ne 0){throw 'Capacity database creation failed.'}
    }
} finally {[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPGPassword,'Process')}
$providerNames=@('GEMINI_API_KEY','GEMINI_MODEL','GEMINI_URL','CARTESIA_API_KEY','CARTESIA_VOICE','CARTESIA_MODEL','CARTESIA_VERSION')
$names=@('DB_URL','DB_USERNAME','DB_PASSWORD','CAPACITY_ACCOUNTS','CAPACITY_SESSIONS_PER_USER','CAPACITY_PASSWORD',
    'PORT','MANAGEMENT_PORT','DB_POOL_SIZE','SESSION_COOKIE_SECURE','RATE_LIMIT_ENABLED','DICTALAB_COACH_ENABLED',
    'DICTALAB_CONVERSATION_ENABLED','DICTALAB_MEDIA_ROOT','DICTALAB_PERSONAL_CONTENT_ENABLED','REDIS_URL',
    'JWT_COOKIE_SECURE','JWT_SIGNING_KEY','JWT_PREVIOUS_SIGNING_KEY','JWT_ACCESS_LIFETIME','JWT_REFRESH_LIFETIME',
    'DICTALAB_RATE_LIMIT_BACKEND','DICTALAB_LISTENING_QUEUE_ENABLED','SMTP_HOST')+$providerNames
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$process=$null
try {
foreach($name in $providerNames){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
$env:DB_URL="jdbc:postgresql://127.0.0.1:5432/$DatabaseName"
$env:DB_USERNAME='dictalab_app';$env:DB_PASSWORD=$secrets.AppPassword
$env:CAPACITY_ACCOUNTS=[string]$Accounts;$env:CAPACITY_SESSIONS_PER_USER=[string]$SessionsPerUser
$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
$env:PORT='18282';$env:MANAGEMENT_PORT='18283';$env:DB_POOL_SIZE=[string]$PoolSize
$env:SESSION_COOKIE_SECURE='false';$env:RATE_LIMIT_ENABLED='true';$env:DICTALAB_RATE_LIMIT_BACKEND='redis'
$env:REDIS_URL=$env:CAPACITY_REDIS_URL;$env:JWT_COOKIE_SECURE='false';$env:JWT_PREVIOUS_SIGNING_KEY=''
$jwtBytes=New-Object byte[] 64
$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
try{$rng.GetBytes($jwtBytes)}finally{$rng.Dispose()}
$env:JWT_SIGNING_KEY=[Convert]::ToBase64String($jwtBytes)
$env:JWT_ACCESS_LIFETIME='10m';$env:JWT_REFRESH_LIFETIME='7d'
$env:DICTALAB_LISTENING_QUEUE_ENABLED='false';$env:SMTP_HOST=''
$env:DICTALAB_COACH_ENABLED='false';$env:DICTALAB_CONVERSATION_ENABLED='false'
$env:DICTALAB_MEDIA_ROOT=Join-Path $capacityRoot 'media'
$env:DICTALAB_PERSONAL_CONTENT_ENABLED='false'
if($EvidenceSuite){$env:GEMINI_URL=$FakeProviderUrl;$env:GEMINI_API_KEY='fake-only-no-external-value'}
    $arguments=@("-Xms256m","-Xmx${HeapMB}m",'-jar',('"'+$Artifact+'"'),'--server.address=127.0.0.1')
    $arguments+="--logging.file.name=C:\DictaLab\capacity\dictalab-$stamp.json"
    $arguments+='--server.tomcat.threads.max=100'
    $arguments+='--server.tomcat.max-connections=4096'
    if(-not $Baseline){$arguments+='--spring.profiles.active=capacity'}
    $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $capacityRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $capacityRoot "stdout-$stamp.log") -RedirectStandardError (Join-Path $capacityRoot "stderr-$stamp.log")
    Write-Output "CAPACITY_PID=$($process.Id)"
    Write-Output "CAPACITY_DATABASE=$DatabaseName"
    Write-Output "CAPACITY_PORT=18282"
    Write-Output "CAPACITY_ARTIFACT_SHA256=$((Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash)"
    $manifest=Join-Path $capacityRoot "runtime-$stamp.json"
    [IO.File]::WriteAllText($manifest,([pscustomobject]@{At=(Get-Date).ToString('o');PID=$process.Id;
        Database=$DatabaseName;Accounts=$Accounts;HistorySessionsPerUser=$SessionsPerUser;HeapMB=$HeapMB;PoolSize=$PoolSize;
        RateLimitEnabled=$true;JwtIsolated=$true;RedisDedicated=$true;ProviderMode=if($EvidenceSuite){'fake'}else{'disabled'};
        ArtifactSHA256=(Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash;MaxRuntimeSeconds=$MaxRuntimeSeconds;
        Resources=(Join-Path $capacityRoot "resources-$stamp.csv");AppLog=(Join-Path $capacityRoot "stdout-$stamp.log")}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "CAPACITY_RUNTIME_MANIFEST=$manifest"
    # One bounded watchdog also cleans up if the SSH connection or load generator dies.
    $monitor=Join-Path $capacityRoot 'monitor-capacity-vps.ps1'
    if(-not (Test-Path -LiteralPath $monitor -PathType Leaf)){Stop-Process -Id $process.Id;throw 'Capacity watchdog is required.'}
    $watchdog=Start-Process -FilePath powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$monitor+'"'),
        '-Seconds',$MaxRuntimeSeconds,'-CapacityPid',$process.Id,'-Output',('"'+(Join-Path $capacityRoot "resources-$stamp.csv")+'"'),'-StopAtEnd') `
        -RedirectStandardOutput (Join-Path $capacityRoot "watchdog-$stamp.stdout.log") `
        -RedirectStandardError (Join-Path $capacityRoot "watchdog-$stamp.stderr.log")
    Write-Output "CAPACITY_WATCHDOG_PID=$($watchdog.Id)"
} catch {
    if($process -and -not $process.HasExited){Stop-Process -Id $process.Id}
    throw
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
