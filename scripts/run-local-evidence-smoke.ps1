param([Parameter(Mandatory)][string]$PostgresBin,
    [ValidateRange(1024,65535)][int]$PostgresPort=15437,
    [ValidateRange(1024,65535)][int]$RedisPort=16480,[ValidateRange(30,2130)][int]$Seconds=60,
    [switch]$PracticeUX)
$ErrorActionPreference='Stop'
if((-not $PracticeUX -and $Seconds -gt 120) -or ($PracticeUX -and $Seconds -ne 30 -and $Seconds -lt 390)){
    throw 'Evidence smoke remains capped at 120 seconds; PracticeUX uses 30-second smoke or 390..2130-second staged load.'
}
$accounts=if($PracticeUX){50}else{40}
$runtimeSeconds=$Seconds+$(if($PracticeUX){1000}else{630})
$root=Split-Path -Parent $PSScriptRoot
$testRoot=Join-Path $root ('target\evidence-smoke-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$database='dictalab_capacity_'+[guid]::NewGuid().ToString('N')
$cluster=Join-Path $testRoot 'cluster'
$names=@('CAPACITY_REDIS_URL','DB_URL','DB_USERNAME','DB_PASSWORD','CAPACITY_ACCOUNTS','CAPACITY_SESSIONS_PER_USER','CAPACITY_PASSWORD',
    'PORT','MANAGEMENT_PORT','DB_POOL_SIZE','SESSION_COOKIE_SECURE','RATE_LIMIT_ENABLED','DICTALAB_COACH_ENABLED',
    'DICTALAB_CONVERSATION_ENABLED','DICTALAB_MEDIA_ROOT','DICTALAB_PERSONAL_CONTENT_ENABLED','REDIS_URL',
    'JWT_COOKIE_SECURE','JWT_SIGNING_KEY','JWT_PREVIOUS_SIGNING_KEY','JWT_ACCESS_LIFETIME','JWT_REFRESH_LIFETIME',
    'DICTALAB_RATE_LIMIT_BACKEND','DICTALAB_LISTENING_QUEUE_ENABLED','SMTP_HOST','GEMINI_URL','GEMINI_API_KEY','CARTESIA_API_KEY',
    'DICTALAB_MEDIA_S3_ENABLED','DICTALAB_MEDIA_S3_CLEANUP_ENABLED','DICTALAB_AI_QUEUE_ENABLED')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$app=$null;$fake=$null;$started=$false;$failure=$null;$databaseReady=$false
try{
    foreach($port in @($PostgresPort,18282,18283,18380)){
        if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Smoke port $port is occupied."}
    }
    if((Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory).AvailableMBytes -lt 1800){throw 'Local smoke memory preflight declined.'}
    $env:CAPACITY_REDIS_URL="redis://127.0.0.1:$RedisPort/0"
    & javac --release 17 -d $testRoot (Join-Path $PSScriptRoot 'load\RedisIsolationCheck.java')
    if($LASTEXITCODE -ne 0){throw 'Redis isolation check compilation failed.'}
    & java -cp $testRoot RedisIsolationCheck
    if($LASTEXITCODE -ne 0){throw 'Start a new empty, dedicated loopback Redis instance for this smoke.'}
    & mvn --batch-mode --no-transfer-progress "-Ddictalab.build.directory=$testRoot/build" '-DskipTests' package
    if($LASTEXITCODE -ne 0){throw 'Smoke application build failed.'}
    & (Join-Path $PostgresBin 'initdb.exe') -D $cluster -U dictalab_test --auth=trust --encoding=UTF8 --locale=C
    if($LASTEXITCODE -ne 0){throw 'Test PostgreSQL initialization failed.'}
    # Redirect the launcher streams: a Windows daemon can keep native pipeline handles open.
    $started=$true
    $postgresStart=Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList @(
        'start','-D',('"'+$cluster+'"'),'-l',('"'+(Join-Path $testRoot 'postgres.log')+'"'),'-w','-o',('"-h 127.0.0.1 -p '+$PostgresPort+'"')) `
        -RedirectStandardOutput (Join-Path $testRoot 'postgres-start.stdout.log') -RedirectStandardError (Join-Path $testRoot 'postgres-start.stderr.log')
    $null=$postgresStart.Handle
    if(-not $postgresStart.WaitForExit(60000)){$postgresStart.Kill();throw 'Test PostgreSQL startup exceeded 60 seconds.'}
    if($postgresStart.ExitCode -ne 0){throw 'Test PostgreSQL start failed.'}
    & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $PostgresPort -U dictalab_test $database
    if($LASTEXITCODE -ne 0){throw 'Test database creation failed.'}
    $databaseReady=$true
    $fake=Start-Process -FilePath (Get-Command python.exe).Source -WindowStyle Hidden -PassThru -ArgumentList @(
        ('"'+(Join-Path $PSScriptRoot 'load\fake-provider.py')+'"'),'--seconds',$runtimeSeconds,'--max-calls','200') `
        -RedirectStandardOutput (Join-Path $testRoot 'fake.stdout.log') -RedirectStandardError (Join-Path $testRoot 'fake.stderr.log')
    $env:DB_URL="jdbc:postgresql://127.0.0.1:$PostgresPort/$database";$env:DB_USERNAME='dictalab_test';$env:DB_PASSWORD=''
    $env:CAPACITY_ACCOUNTS=[string]$accounts;$env:CAPACITY_SESSIONS_PER_USER='6';$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
    $env:PORT='18282';$env:MANAGEMENT_PORT='18283';$env:DB_POOL_SIZE='10'
    $env:SESSION_COOKIE_SECURE='false';$env:RATE_LIMIT_ENABLED='true';$env:DICTALAB_RATE_LIMIT_BACKEND='redis'
    $env:REDIS_URL=$env:CAPACITY_REDIS_URL;$env:JWT_COOKIE_SECURE='false';$env:JWT_PREVIOUS_SIGNING_KEY=''
    $env:JWT_SIGNING_KEY=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
    $env:JWT_ACCESS_LIFETIME='10m';$env:JWT_REFRESH_LIFETIME='7d'
    $env:DICTALAB_LISTENING_QUEUE_ENABLED='false';$env:SMTP_HOST='';$env:CARTESIA_API_KEY=''
    $env:DICTALAB_MEDIA_S3_ENABLED='false';$env:DICTALAB_MEDIA_S3_CLEANUP_ENABLED='false';$env:DICTALAB_AI_QUEUE_ENABLED='false'
    $env:DICTALAB_COACH_ENABLED='false';$env:DICTALAB_CONVERSATION_ENABLED='false';$env:DICTALAB_PERSONAL_CONTENT_ENABLED='false'
    $env:DICTALAB_MEDIA_ROOT=Join-Path $testRoot 'media';$env:GEMINI_API_KEY='fake-only';$env:GEMINI_URL='http://127.0.0.1:18380/v1/chat/completions'
    $artifact=Join-Path $testRoot 'build\english-dictation-0.1.0-SNAPSHOT.jar'
    $app=Start-Process -FilePath (Get-Command java.exe).Source -WindowStyle Hidden -PassThru -WorkingDirectory $testRoot -ArgumentList @(
        '-Xms128m','-Xmx512m','-jar',('"'+$artifact+'"'),'--spring.profiles.active=capacity','--server.address=127.0.0.1') `
        -RedirectStandardOutput (Join-Path $testRoot 'app.stdout.log') -RedirectStandardError (Join-Path $testRoot 'app.stderr.log')
    $ready=$false;$deadline=(Get-Date).AddSeconds(120)
    do{
        if($app.HasExited){throw 'Local test app exited; inspect retained app logs.'}
        try{$ready=(Invoke-RestMethod 'http://127.0.0.1:18282/actuator/health' -TimeoutSec 3).status -eq 'UP'}catch{}
        if(-not $ready){Start-Sleep -Seconds 2}
    }while(-not $ready -and (Get-Date) -lt $deadline)
    if(-not $ready){throw 'Local test startup exceeded its finite budget.'}
    $manifest=Join-Path $testRoot 'runtime.json'
    [IO.File]::WriteAllText($manifest,([pscustomobject]@{At=(Get-Date).ToString('o');PID=$app.Id;Database=$database;
        Accounts=$accounts;HistorySessionsPerUser=6;HeapMB=512;PoolSize=10;RateLimitEnabled=$true;JwtIsolated=$true;RedisDedicated=$true;
        ProviderMode='fake';ArtifactSHA256=(Get-FileHash $artifact).Hash;MaxRuntimeSeconds=$runtimeSeconds;
        JavaVersion=(& java -version 2>&1|Out-String).Trim();PostgresVersion=(& (Join-Path $PostgresBin 'postgres.exe') --version)}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    if($PracticeUX){
        $profile=if($Seconds -eq 30){'smoke'}else{'load'}
        $users=if($Seconds -eq 30){1}else{50}
        & (Join-Path $PSScriptRoot 'run-capacity-check.ps1') -Module practice-ux -Users $users -Seconds $Seconds -Profile $profile -Label practice-ux -LocalSmoke -RuntimeManifest $manifest
    }else{
        & (Join-Path $PSScriptRoot 'run-capacity-check.ps1') -Module evidence -Users 20 -Seconds $Seconds -Label local-smoke -LocalSmoke -RuntimeManifest $manifest
    }
    if($LASTEXITCODE -ne 0){throw 'Smoke thresholds or generator accounting failed; retain raw evidence.'}
    $provider=Invoke-RestMethod 'http://127.0.0.1:18380/stats' -TimeoutSec 3
    [IO.File]::WriteAllText((Join-Path $testRoot 'provider.json'),($provider|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    if($PracticeUX -and $provider.calls -ne 0){throw 'Practice UX must not make AI provider calls.'}
    if(-not $PracticeUX -and $provider.calls -eq 0){throw 'No fake grading request observed; the workload is not validated.'}
}catch{$failure=$_.Exception.Message}
finally{
    if($app -and -not $app.HasExited){Stop-Process -Id $app.Id;$null=$app.WaitForExit(15000)}
    if($fake -and -not $fake.HasExited){Stop-Process -Id $fake.Id}
    if($databaseReady){
        try{& (Join-Path $PSScriptRoot 'verify-evidence-db.ps1') -Database $database -PostgresBin $PostgresBin -Port $PostgresPort -Restore}
        catch{if(-not $failure){$failure=$_.Exception.Message}}
    }
    if($started){
        $postgresStop=Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -WindowStyle Hidden -PassThru -ArgumentList @(
            'stop','-D',('"'+$cluster+'"'),'-m','fast','-w') `
            -RedirectStandardOutput (Join-Path $testRoot 'postgres-stop.stdout.log') -RedirectStandardError (Join-Path $testRoot 'postgres-stop.stderr.log')
        $null=$postgresStop.Handle
        if(-not $postgresStop.WaitForExit(30000)){$postgresStop.Kill();if(-not $failure){$failure='Test PostgreSQL stop exceeded 30 seconds.'}}
        elseif($postgresStop.ExitCode -ne 0 -and -not $failure){$failure='Test PostgreSQL stop failed.'}
    }
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    [IO.File]::WriteAllText((Join-Path $testRoot 'result.json'),(@{Passed=($null -eq $failure);Failure=$failure;Database=$database;
        Scope='Local functional smoke only: HP app/DB/generator, NOT VPS load/soak capacity'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "LOCAL_SMOKE_ARTIFACTS=$testRoot"
}
if($failure){throw $failure}
