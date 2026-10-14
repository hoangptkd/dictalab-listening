param(
    [string]$DatabaseName = 'dictalab',
    [string]$BackupDirectory = 'C:\DictaLab\backups',
    [string]$SmokeArtifact = '',
    [string]$CompatibilityArtifact = '',
    [ValidateRange(30,300)][int]$SmokeTimeoutSeconds = 180,
    [switch]$CheckRedisOnly,
    [switch]$CheckS3Only
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function New-RestoreSmokeConfiguration {
    param([string]$DatabaseUrl,[string]$DatabaseUser,[string]$DatabasePassword,
        [string]$RedisUrl,[string]$SigningKey,[string]$MediaRoot,[int]$Port=18284,[int]$ManagementPort=18285)
    # JSON wins over inherited direct Spring environment properties; secrets never enter command-line arguments.
    @{
        spring=@{
            datasource=@{
                url=$DatabaseUrl;username=$DatabaseUser;password=$DatabasePassword;'driver-class-name'='org.postgresql.Driver'
                hikari=@{'jdbc-url'=$DatabaseUrl;username=$DatabaseUser;password=$DatabasePassword;'driver-class-name'='org.postgresql.Driver';'maximum-pool-size'=3;'minimum-idle'=0}
            }
            data=@{redis=@{url=$RedisUrl;username='';password='';timeout='2s';'connect-timeout'='3s'}}
            mail=@{host=''}
            profiles=@{active='restore-smoke'}
            config=@{location='classpath:/application.yml';'additional-location'='';import=''}
        }
        server=@{address='127.0.0.1';port=$Port}
        management=@{
            server=@{address='127.0.0.1';port=$ManagementPort}
            health=@{rabbit=@{enabled=$false};mail=@{enabled=$false};redis=@{enabled=$false}}
            tracing=@{export=@{otlp=@{enabled=$false}}}
            otlp=@{metrics=@{export=@{enabled=$false}}}
            logging=@{export=@{otlp=@{enabled=$false}}}
        }
        dictalab=@{
            auth=@{'signing-key'=$SigningKey;'previous-signing-key'='';'key-id'='restore';'cookie-secure'=$false}
            media=@{root=$MediaRoot;s3=@{enabled=$false;'cleanup-enabled'=$false}}
            ai=@{queue=@{enabled=$false}}
            listening=@{queue=@{enabled=$false}}
            coach=@{enabled=$false}
            conversation=@{enabled=$false;'cartesia-api-key'=''}
            shopaikey=@{'api-key'=''}
            'mail-from'=''
            'personal-content'=@{enabled=$false}
        }
    } | ConvertTo-Json -Depth 8 -Compress
}

function Get-S3RecoveryGateSql {
    # This gate only rejects unsupported recovery; it never treats URL/catalog metadata as an object backup.
    @'
SET statement_timeout = '15s';
SET lock_timeout = '3s';
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
DO $s3_recovery$
DECLARE
    external_references bigint;
    pending_objects bigint;
BEGIN
    SELECT (SELECT count(*) FROM public.lesson_sentences WHERE audio_url LIKE '/media/s3/%')
         + (SELECT count(*) FROM public.listening_units WHERE audio_url LIKE '/media/s3/%') INTO external_references;
    -- Pre-V27 databases have no catalog; legacy references must still block the release.
    IF to_regclass('public.s3_media_lifecycle') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.s3_media_lifecycle WHERE status IS DISTINCT FROM ''DELETED''' INTO pending_objects;
        external_references := external_references + pending_objects;
    END IF;
    IF external_references > 0 THEN
        RAISE EXCEPTION 'External S3 dependencies require a separately verified object recovery gate';
    END IF;
END;
$s3_recovery$;
SELECT 'RESTORE_S3_DEPENDENCIES_NONE';
COMMIT;
'@
}

function Assert-S3RecoverySupported {
    param([string]$Psql,[string]$DatabaseName,[string]$DatabaseUser)
    try {
        $output=@(& $Psql -X -q -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -v ON_ERROR_STOP=1 -tAc (Get-S3RecoveryGateSql) 2>&1)
        if($LASTEXITCODE -ne 0 -or $output.Count -ne 1 -or $output[0].ToString().Trim() -cne 'RESTORE_S3_DEPENDENCIES_NONE') {
            throw 'S3 check was not accepted.'
        }
    } catch {
        throw 'S3 recovery gate failed: external object recovery is unsupported or the read-only check could not be verified. Production was not repaired; no S3 objects were accessed.'
    }
    Write-Output 'RESTORE_S3_DEPENDENCIES_NONE'
}

function Get-VerifiedRestoreRedisUrl {
    $previousRestore=[Environment]::GetEnvironmentVariable('RESTORE_REDIS_URL')
    $previousProduction=[Environment]::GetEnvironmentVariable('REDIS_URL')
    $previousJvmOptions=@{}
    foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')) {
        $previousJvmOptions[$name]=[Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name,$null)
    }
    try {
        foreach($entry in @(@{Name='RESTORE_REDIS_URL';File='restore-redis-url.xml'},@{Name='REDIS_URL';File='redis-url.xml'})) {
            $secretPath=Join-Path $env:ProgramData ('DictaLab\auth\'+$entry.File)
            if(($entry.Name -eq 'REDIS_URL' -or -not $env:RESTORE_REDIS_URL) -and (Test-Path -LiteralPath $secretPath)) {
                $secure=Import-Clixml -LiteralPath $secretPath
                $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
                try {[Environment]::SetEnvironmentVariable($entry.Name,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer))}
                finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$secure.Dispose()}
            }
        }
        if(-not $env:RESTORE_REDIS_URL){throw 'Dedicated RESTORE_REDIS_URL or protected restore-redis-url.xml is required.'}
        $checker=Join-Path $PSScriptRoot 'RedisIsolationCheck.class'
        if(-not (Test-Path -LiteralPath $checker -PathType Leaf)){throw 'Compiled Redis isolation checker is missing.'}
        & 'C:\Program Files\Eclipse Adoptium\jre-17.0.20.101-hotspot\bin\java.exe' -cp $PSScriptRoot RedisIsolationCheck --restore | ForEach-Object {Write-Host $_}
        if($LASTEXITCODE -ne 0){throw 'Restore Redis isolation preflight failed; nothing was flushed or repaired.'}
        return $env:RESTORE_REDIS_URL
    } finally {
        [Environment]::SetEnvironmentVariable('RESTORE_REDIS_URL',$previousRestore)
        [Environment]::SetEnvironmentVariable('REDIS_URL',$previousProduction)
        foreach($name in $previousJvmOptions.Keys){[Environment]::SetEnvironmentVariable($name,$previousJvmOptions[$name])}
    }
}

if($CheckRedisOnly){$null=Get-VerifiedRestoreRedisUrl;Write-Output 'RESTORE_REDIS_PREFLIGHT_OK';return}
if ($DatabaseName -ne 'dictalab') { throw 'Only the DictaLab source database is supported.' }
$postgresBin = 'C:\DictaLab\postgresql-17\bin'
$psql = Join-Path $postgresBin 'psql.exe'
$secrets = Import-Clixml -LiteralPath (Join-Path $env:ProgramData 'DictaLab\database-secrets.xml')
if($CheckS3Only){
    $previousPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD')
    try {
        $env:PGPASSWORD=$secrets.AppPassword
        Assert-S3RecoverySupported -Psql $psql -DatabaseName $DatabaseName -DatabaseUser 'dictalab_app'
    } finally {[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPassword)}
    return
}
$restoreName = 'dictalab_restore_' + (Get-Date -Format 'yyyyMMddHHmmss')
if ($restoreName -notmatch '^dictalab_restore_\d{14}$') { throw 'Invalid isolated restore target.' }
$backup = Get-ChildItem -LiteralPath $BackupDirectory -Filter 'dictalab-*.dump' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $backup) { throw 'No database backup found.' }
$created = $false
$restoredMedia=$null
$smoke=$null
$browser=$null
$authEnvironment=@{}
foreach($name in @('REDIS_URL','JWT_SIGNING_KEY','JWT_COOKIE_SECURE','SPRING_DATA_REDIS_TIMEOUT','SPRING_DATA_REDIS_CONNECT_TIMEOUT',
        'SPRING_APPLICATION_JSON','DB_URL','DB_USERNAME','DB_PASSWORD','PORT','MANAGEMENT_PORT','DB_POOL_SIZE','SESSION_COOKIE_SECURE',
        'DICTALAB_MEDIA_ROOT','DICTALAB_COACH_ENABLED','DICTALAB_CONVERSATION_ENABLED','DICTALAB_PERSONAL_CONTENT_ENABLED','SMTP_HOST','PGPASSWORD',
        'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){
    $authEnvironment[$name]=[Environment]::GetEnvironmentVariable($name)
}
$manifestPath=[IO.Path]::ChangeExtension($backup.FullName,'manifest.json')
if(Test-Path -LiteralPath $manifestPath){
    $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if((Get-FileHash -LiteralPath $backup.FullName -Algorithm SHA256).Hash -ne $manifest.DumpSHA256){throw 'Dump checksum does not match its manifest.'}
}else{throw 'Backup manifest is required for a checksum-verified restore.'}
$watch = [Diagnostics.Stopwatch]::StartNew()
$env:PGPASSWORD = $secrets.PostgresPassword
try {
    $restoreRedisUrl=$null
    if($SmokeArtifact -or $CompatibilityArtifact){$restoreRedisUrl=Get-VerifiedRestoreRedisUrl}
    & (Join-Path $postgresBin 'createdb.exe') -h 127.0.0.1 -U postgres -O dictalab_app $restoreName
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated restore database.' }
    $created = $true
    & (Join-Path $postgresBin 'pg_restore.exe') -h 127.0.0.1 -U postgres -d $restoreName --exit-on-error $backup.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Restore failed. Source database was not modified.' }
    $verify = @'
SELECT 'RESTORE_TABLES=' || count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';
SELECT 'RESTORE_USERS=' || count(*) FROM app_users;
SELECT 'RESTORE_SESSIONS=' || count(*) FROM practice_sessions;
SELECT 'RESTORE_INVALID_FK=' || count(*) FROM practice_sessions p LEFT JOIN app_users u ON u.id=p.user_id WHERE u.id IS NULL;
SELECT 'RESTORE_SCHEMA=' || max(installed_rank) FROM flyway_schema_history WHERE success;
SELECT 'RESTORE_UNVALIDATED_CONSTRAINTS=' || count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND NOT convalidated;
'@
    $verification=@(& $psql -X -h 127.0.0.1 -U postgres -d $restoreName -v ON_ERROR_STOP=1 -tAc $verify)
    if ($LASTEXITCODE -ne 0) { throw 'Restored database verification failed.' }
    $verification | Write-Output
    foreach($name in @('RESTORE_INVALID_FK','RESTORE_UNVALIDATED_CONSTRAINTS')){
        if($verification -notcontains ($name+'=0')){throw "Restored database failed $name."}
    }
    if($SmokeArtifact -or $CompatibilityArtifact){
        # Recheck the quiescent snapshot; a live preflight alone cannot fence writes committed before service stop.
        Assert-S3RecoverySupported -Psql $psql -DatabaseName $restoreName -DatabaseUser 'postgres'
    }
    if($manifest -and $manifest.MediaSnapshot){
        $snapshotRoot=(Resolve-Path -LiteralPath $BackupDirectory).Path.TrimEnd('\')+'\'
        $snapshot=[IO.Path]::GetFullPath((Join-Path $BackupDirectory $manifest.MediaSnapshot))
        if(-not $snapshot.StartsWith($snapshotRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Snapshot path escapes backup directory.'}
        $restoredMedia=Join-Path $BackupDirectory ($restoreName+'.media')
        New-Item -ItemType Directory -Path $restoredMedia | Out-Null
        & robocopy.exe $snapshot $restoredMedia /E /COPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS | Out-Null
        if($LASTEXITCODE -ge 8){throw 'Isolated media restore failed.'}
        $mediaRoot=[IO.Path]::GetFullPath($restoredMedia).TrimEnd('\')+'\'
        foreach($file in $manifest.MediaFiles){
            $path=[IO.Path]::GetFullPath((Join-Path $restoredMedia $file.Path))
            if(-not $path.StartsWith($mediaRoot,[StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $path -PathType Leaf) -or
                (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.SHA256){throw 'Restored media checksum mismatch.'}
        }
        Write-Output "RESTORE_MEDIA_OK=$(@($manifest.MediaFiles).Count)"
        $references=@(& $psql -X -h 127.0.0.1 -U postgres -d $restoreName -v ON_ERROR_STOP=1 -tAc "SELECT audio_url FROM lesson_sentences WHERE audio_url LIKE '/media/%' UNION SELECT audio_url FROM listening_units WHERE audio_url LIKE '/media/%'")
        if($LASTEXITCODE -ne 0){throw 'Could not inspect restored media references.'}
        foreach($url in $references){
            # S3 URLs are stable application references, not files in the local snapshot.
            # Bucket objects and the service credential need a separate recovery plan.
            if ($url -match '^/media/s3/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(mp3|m4a|wav)$') {
                continue
            }
            $path=[IO.Path]::GetFullPath((Join-Path $restoredMedia $url.Substring(7)))
            if(-not $path.StartsWith($mediaRoot,[StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $path -PathType Leaf)){throw 'Restored DB references missing or unsafe managed media.'}
        }
        Write-Output "RESTORE_MEDIA_REFERENCES_OK=$($references.Count)"
        Write-Output "RESTORE_S3_REFERENCES_EXTERNAL=$(@($references | Where-Object {$_ -like '/media/s3/*'}).Count)"
    }
    foreach($testedArtifact in @($SmokeArtifact,$CompatibilityArtifact) | Where-Object {$_}){
        if(-not (Test-Path -LiteralPath $testedArtifact -PathType Leaf)){throw 'Restore smoke artifact is missing.'}
        if(Get-NetTCPConnection -LocalPort 18284,18285 -State Listen -ErrorAction SilentlyContinue){throw 'Restore smoke ports are occupied.'}
        if(-not $restoredMedia){
            $restoredMedia=Join-Path $BackupDirectory ($restoreName+'.media')
            New-Item -ItemType Directory -Path $restoredMedia | Out-Null
        }
        # Never share production JWT signing keys or token keys with the restored application.
        $env:REDIS_URL=$restoreRedisUrl
        $jwtBytes=New-Object byte[] 64
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        try{$rng.GetBytes($jwtBytes)}finally{$rng.Dispose()}
        $env:JWT_SIGNING_KEY=[Convert]::ToBase64String($jwtBytes)
        $env:JWT_COOKIE_SECURE='false'
        $env:DB_URL="jdbc:postgresql://127.0.0.1:5432/$restoreName"
        $env:DB_USERNAME='dictalab_app';$env:DB_PASSWORD=$secrets.AppPassword
        $env:PORT='18284';$env:MANAGEMENT_PORT='18285';$env:DB_POOL_SIZE='3'
        $env:SESSION_COOKIE_SECURE='false';$env:DICTALAB_MEDIA_ROOT=$restoredMedia
        $env:DICTALAB_COACH_ENABLED='false';$env:DICTALAB_CONVERSATION_ENABLED='false'
        $env:DICTALAB_PERSONAL_CONTENT_ENABLED='false';$env:SMTP_HOST=''
        $env:SPRING_APPLICATION_JSON=New-RestoreSmokeConfiguration -DatabaseUrl $env:DB_URL -DatabaseUser $env:DB_USERNAME `
            -DatabasePassword $env:DB_PASSWORD -RedisUrl $restoreRedisUrl -SigningKey $env:JWT_SIGNING_KEY -MediaRoot $restoredMedia
        foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){[Environment]::SetEnvironmentVariable($name,$null)}
        $smokeLabel=$restoreName+'-'+(Get-FileHash -LiteralPath $testedArtifact -Algorithm SHA256).Hash.Substring(0,12)
        $smoke=Start-Process -FilePath 'C:\Program Files\Eclipse Adoptium\jre-17.0.20.101-hotspot\bin\java.exe' `
            -WindowStyle Hidden -PassThru -WorkingDirectory $BackupDirectory -ArgumentList @('-Xms128m','-Xmx256m','-jar',('"'+$testedArtifact+'"'),'--server.address=127.0.0.1',('--logging.file.name='+$smokeLabel+'.log'),
                '--dictalab.media.s3.enabled=false','--dictalab.media.s3.cleanup-enabled=false',
                '--dictalab.ai.queue.enabled=false','--dictalab.listening.queue.enabled=false',
                '--dictalab.shopaikey.api-key=','--dictalab.conversation.cartesia-api-key=',
                '--dictalab.auth.previous-signing-key=','--dictalab.mail-from=') `
            -RedirectStandardOutput (Join-Path $BackupDirectory ($smokeLabel+'.stdout.log')) `
            -RedirectStandardError (Join-Path $BackupDirectory ($smokeLabel+'.stderr.log'))
        $null=$smoke.Handle
        $deadline=(Get-Date).AddSeconds($SmokeTimeoutSeconds);$ready=$false
        do {
            if($smoke.HasExited){throw 'Restored app exited before readiness; inspect retained smoke logs.'}
            try {$health=Invoke-RestMethod 'http://127.0.0.1:18284/actuator/health' -TimeoutSec 3;$ready=$health.status -eq 'UP'}catch{}
            if(-not $ready){Start-Sleep -Seconds 2}
        }while(-not $ready -and (Get-Date) -lt $deadline)
        if(-not $ready){throw 'Restored app did not become healthy within its smoke budget.'}
        $base='http://127.0.0.1:18284'
        $csrf=Invoke-RestMethod ($base+'/api/auth/csrf') -SessionVariable browser -TimeoutSec 5
        $headers=@{};$headers[$csrf.headerName]=$csrf.token
        $account=Invoke-RestMethod ($base+'/api/auth/register') -WebSession $browser -Headers $headers -Method Post -ContentType 'application/json' `
            -Body (@{displayName='Restore smoke';email=($restoreName+'-'+[guid]::NewGuid().ToString('N')+'@loadtest.local');password=([guid]::NewGuid().ToString('N'))}|ConvertTo-Json) -TimeoutSec 10
        if(-not $account.id){throw 'Restored app registration smoke failed.'}
        $lessons=Invoke-RestMethod ($base+'/api/v1/catalog/lessons?level=A1') -WebSession $browser -TimeoutSec 10
        if(-not @($lessons).Count){throw 'Restored catalog is empty.'}
        $csrf=Invoke-RestMethod ($base+'/api/auth/csrf') -WebSession $browser -TimeoutSec 5
        $headers=@{};$headers[$csrf.headerName]=$csrf.token
        $practice=Invoke-RestMethod ($base+'/api/v1/practice-sessions') -WebSession $browser -Headers $headers -Method Post -ContentType 'application/json' `
            -Body (@{lessonId=[long]$lessons[0].id}|ConvertTo-Json) -TimeoutSec 10
        if(-not $practice.sessionId -or -not $practice.sentence){throw 'Restored practice smoke failed.'}
        Write-Output "RESTORE_APP_SMOKE_OK=$((Get-FileHash -LiteralPath $testedArtifact -Algorithm SHA256).Hash)"
        $csrf=Invoke-RestMethod ($base+'/api/auth/csrf') -WebSession $browser -TimeoutSec 5
        $logoutHeaders=@{};$logoutHeaders[$csrf.headerName]=$csrf.token
        Invoke-WebRequest ($base+'/api/auth/logout') -UseBasicParsing -WebSession $browser `
            -Headers $logoutHeaders -Method Post -TimeoutSec 5 | Out-Null
        Write-Output 'RESTORE_AUTH_LOGOUT_OK'
        $browser=$null
        Stop-Process -Id $smoke.Id;$null=$smoke.WaitForExit(10000);$smoke=$null
    }
    & $psql -X -h 127.0.0.1 -U postgres -d $restoreName -v ON_ERROR_STOP=1 -tAc "SELECT 'RESTORE_FINAL_SCHEMA=' || max(version::int) FROM flyway_schema_history WHERE success"
    if($LASTEXITCODE -ne 0){throw 'Could not verify the final restored schema.'}
    $watch.Stop()
    Write-Output "RESTORE_OK=$restoreName"
    Write-Output 'RESTORE_EXTERNAL_S3_VERIFIED=FALSE'
    Write-Output "RESTORE_SECONDS=$([math]::Round($watch.Elapsed.TotalSeconds,2))"
    Write-Output "BACKUP_SHA256=$((Get-FileHash -LiteralPath $backup.FullName -Algorithm SHA256).Hash)"
} finally {
    try {
    if($browser -and $smoke -and -not $smoke.HasExited){
        try{
            $csrf=Invoke-RestMethod 'http://127.0.0.1:18284/api/auth/csrf' -WebSession $browser -TimeoutSec 5
            $logoutHeaders=@{};$logoutHeaders[$csrf.headerName]=$csrf.token
            Invoke-WebRequest 'http://127.0.0.1:18284/api/auth/logout' -UseBasicParsing -WebSession $browser `
                -Headers $logoutHeaders -Method Post -TimeoutSec 5 | Out-Null
            Write-Output 'RESTORE_AUTH_LOGOUT_OK'
        }catch{Write-Warning 'Restore logout failed; inspect the dedicated restore Redis before the next drill.'}
    }
    if($smoke -and -not $smoke.HasExited){Stop-Process -Id $smoke.Id;$null=$smoke.WaitForExit(10000)}
    if ($created) {
        # Drop only the exact temporary database created by this invocation.
        & (Join-Path $postgresBin 'dropdb.exe') -h 127.0.0.1 -U postgres $restoreName
        if ($LASTEXITCODE -ne 0) { Write-Warning "Temporary database retained: $restoreName" }
    }
    if($restoredMedia){
        $resolved=[IO.Path]::GetFullPath($restoredMedia)
        $expectedRoot=(Resolve-Path -LiteralPath $BackupDirectory).Path.TrimEnd('\')+'\'
        if($resolved.StartsWith($expectedRoot,[StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolved) -match '^dictalab_restore_\d{14}\.media$'){
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
    } finally {
        foreach($name in $authEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$authEnvironment[$name])}
    }
}
