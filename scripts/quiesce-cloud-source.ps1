param([Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedSha256)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
Set-StrictMode -Version Latest
if((Get-FileHash 'C:\DictaLab\app\dictalab.jar').Hash -ne $ExpectedSha256){throw 'Source artifact changed'}
if(Test-Path 'C:\DictaLab\migrated-to-cloud.json'){throw 'Source already has a migration fence'}
$bin='C:\DictaLab\postgresql-17\bin'
$backup=Join-Path 'C:\DictaLab\backups' ('cloud-cutover-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$originalMode=(Get-CimInstance Win32_Service -Filter "Name='DictaLab'").StartMode
$envKey=[Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Services\DictaLab\Parameters')
$settings=@{}
foreach($line in [string[]]$envKey.GetValue('AppEnvironmentExtra')){$p=$line -split '=',2;$settings[$p[0]]=$p[1]}
$envKey.Dispose()
$env:PGPASSWORD=$settings.DB_PASSWORD
$stopped=$false
$fenced=$false
$proxyChanged=$false
try{
    $rooms=Invoke-RestMethod 'http://127.0.0.1:18183/actuator/metrics/dictalab.rooms.open' -TimeoutSec 10
    if($rooms.measurements[0].value -ne 0){throw 'Rooms are still active'}
    $jobs=& "$bin\psql.exe" -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U dictalab_app -d dictalab -c "SELECT (SELECT count(*) FROM listening_passes WHERE grading_status='RUNNING')+(SELECT count(*) FROM email_outbox WHERE status='RUNNING')"
    if($LASTEXITCODE -ne 0 -or [int]$jobs -ne 0){throw 'Source workers have not drained'}
    & C:\DictaLab\https\caddy.exe validate --adapter caddyfile --config C:\DictaLab\staging\cloud-source-Caddyfile
    if($LASTEXITCODE){throw 'Source proxy configuration is invalid'}
    New-Item -ItemType Directory -Path $backup | Out-Null
    Copy-Item 'C:\DictaLab\https\Caddyfile' (Join-Path $backup 'Caddyfile')
    Stop-Service DictaLab
    (Get-Service DictaLab).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(100))
    $stopped=$true
    $preflight=& "$bin\psql.exe" -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U dictalab_app -d dictalab -f C:\DictaLab\staging\readiness-preflight.sql
    if($LASTEXITCODE){throw 'Source data preflight failed'}
    foreach($row in $preflight | Select-Object -First 16){if($row -notmatch '^[a-z_]+\|0$'){throw 'Source data invariant failed'}}
    & "$bin\pg_dump.exe" -h 127.0.0.1 -U dictalab_app -d dictalab -Fc -f (Join-Path $backup 'database.dump')
    if($LASTEXITCODE){throw 'Final source dump failed'}
    & "$bin\pg_restore.exe" --list (Join-Path $backup 'database.dump') | Out-Null
    if($LASTEXITCODE){throw 'Final source dump is invalid'}
    $fingerprints=& "$bin\psql.exe" -X -qAt -h 127.0.0.1 -U dictalab_app -d dictalab -f C:\DictaLab\staging\cloud-fingerprint.sql
    if($LASTEXITCODE){throw 'Source fingerprints failed'}
    [IO.File]::WriteAllLines((Join-Path $backup 'database-fingerprints.jsonl'),[string[]]$fingerprints,[Text.UTF8Encoding]::new($false))
    $files=@()
    foreach($name in @('media','personal-content')){
        $source=Join-Path 'C:\DictaLab' $name
        $destination=Join-Path $backup $name
        Copy-Item -LiteralPath $source -Destination $destination -Recurse
        $sourceRoot=(Resolve-Path -LiteralPath $source).Path.TrimEnd('\')+'\'
        foreach($file in Get-ChildItem -LiteralPath $source -File -Recurse){
            $relative=$file.FullName.Substring($sourceRoot.Length)
            $hash=(Get-FileHash -LiteralPath $file.FullName).Hash
            $copied=Join-Path $destination $relative
            if((Get-FileHash -LiteralPath $copied).Hash -ne $hash){throw 'Source media copy differs'}
            $files+=@{path=$name+'/'+$relative.Replace('\','/');bytes=$file.Length;sha256=$hash.ToLowerInvariant()}
        }
    }
    if((Get-Service DictaLab).Status -ne 'Stopped'){throw 'Source writer restarted during snapshot'}
    $manifest=@{at=(Get-Date).ToUniversalTime().ToString('o');consistency='quiescent-db-media';
        dumpSHA256=(Get-FileHash (Join-Path $backup 'database.dump')).Hash;media=$files;originalStartMode=$originalMode;destination='https://184.194.89.90'}
    [IO.File]::WriteAllText((Join-Path $backup 'manifest.json'),($manifest | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    # Persist the one-writer fence even if the host reboots or an old deployment is attempted.
    & "$bin\psql.exe" -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U dictalab_app -d dictalab -c "ALTER ROLE dictalab_app SET default_transaction_read_only=on"
    if($LASTEXITCODE){throw 'Source database fence failed'}
    $fenced=$true
    Set-Service DictaLab -StartupType Disabled
    Copy-Item 'C:\DictaLab\staging\cloud-source-Caddyfile' 'C:\DictaLab\https\Caddyfile' -Force
    $proxyChanged=$true
    Restart-Service DictaLabHttps
    [IO.File]::WriteAllText('C:\DictaLab\migrated-to-cloud.json',(@{backup=$backup;destination=$manifest.destination;at=$manifest.at;state='QUIESCENT'} | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    [Console]::Out.WriteLine('SOURCE_FINAL_SNAPSHOT='+$backup)
    [Console]::Out.WriteLine('SOURCE_FINAL_DUMP_SHA256='+$manifest.dumpSHA256)
    [Console]::Out.WriteLine('SOURCE_FINAL_MEDIA_FILES='+$files.Count)
    [Console]::Out.WriteLine('SOURCE_WRITER_FENCED')
}catch{
    # Target ingress is still blocked at this phase, so no target user writes need reverse syncing.
    if($proxyChanged){Copy-Item (Join-Path $backup 'Caddyfile') 'C:\DictaLab\https\Caddyfile' -Force;Restart-Service DictaLabHttps}
    if($fenced){$env:PGOPTIONS='-c default_transaction_read_only=off'; & "$bin\psql.exe" -X -qAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U dictalab_app -d dictalab -c "ALTER ROLE dictalab_app RESET default_transaction_read_only";if($LASTEXITCODE){throw 'Source fence rollback requires recovery'};Remove-Item Env:PGOPTIONS}
    if($stopped){Set-Service DictaLab -StartupType Automatic;Start-Service DictaLab}
    throw
}finally{Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
