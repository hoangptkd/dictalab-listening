param([Parameter(Mandatory)][ValidatePattern('^dictalab_capacity_[a-z0-9]+$')][string]$Database,
    [Parameter(Mandatory)][string]$PostgresBin,[ValidateRange(1024,65535)][int]$Port=15435,
    [string]$Username='dictalab_test',[switch]$Restore)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root ('target\capacity\db-'+$Database+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
$psql=Join-Path $PostgresBin 'psql.exe'
$arguments=@('-X','-q','-h','127.0.0.1','-p',$Port,'-U',$Username,'-v','ON_ERROR_STOP=1','-A','-t')
$savedOptions=$env:PGOPTIONS;$env:PGOPTIONS='-c timezone=UTC'
$result=$null;$failure=$null;$restoreName=$null
function Query([string]$Name,[string]$Sql){
    $rows=@(& $psql @arguments -d $Name -c $Sql)
    if($LASTEXITCODE -ne 0){throw 'Isolated database query failed.'}
    return $rows
}
function Invariants([string]$Name,[string]$File){
    $rows=@(& $psql @arguments -d $Name -f (Join-Path $PSScriptRoot 'load\invariants.sql'))
    if($LASTEXITCODE -ne 0){throw 'Invariant query failed.'}
    [IO.File]::WriteAllLines($File,$rows,[Text.UTF8Encoding]::new($false))
    # The final cardinality rows describe the dataset; only invariant names are required to be zero.
    if(@($rows|Where-Object {$_ -match '^(duplicate_|word_event_count_mismatch|session_.*mismatch|completed_.*mismatch|incomplete_completed_lesson|completed_grade_missing_result|running_grade_missing_lease).*\|[1-9][0-9]*$'}).Count){
        throw 'Data invariant violation found; retain the database and profile before retrying.'
    }
}
function Fingerprint([string]$Name){
    $tables=Query $Name "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"
    $rows=@()
    foreach($table in $tables){
        if($table -notmatch '^[a-z_][a-z0-9_]*$'){throw 'Unexpected table identifier.'}
        $rows+=Query $Name "SELECT '$table',count(*),COALESCE(md5(string_agg(md5(t::text),'' ORDER BY md5(t::text))),'EMPTY') FROM $table t"
    }
    return $rows
}
try{
    Invariants $Database ($output+'.invariants.txt')
    $result=@{At=(Get-Date).ToString('o');Database=$Database;InvariantsPassed=$true;RestoreRequested=[bool]$Restore;
        Scope='SQL invariants and all public-table row fingerprints; no app/media/Redis restoration claim'}
    if($Restore){
        $others=@(Query $Database 'SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()')
        if([int]$others[0] -ne 0){throw 'Stop the exact test app/worker before restore comparison; another DB connection exists.'}
        $before=Fingerprint $Database
        [IO.File]::WriteAllLines(($output+'.before.txt'),$before,[Text.UTF8Encoding]::new($false))
        & (Join-Path $PostgresBin 'pg_dump.exe') -h 127.0.0.1 -p $Port -U $Username -Fc -f ($output+'.dump') $Database
        if($LASTEXITCODE -ne 0){throw 'Test snapshot creation failed.'}
        $restoreName='dictalab_capacity_restore'+[guid]::NewGuid().ToString('N')
        & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U $Username $restoreName
        if($LASTEXITCODE -ne 0){throw 'Fresh restore database creation failed.'}
        & (Join-Path $PostgresBin 'pg_restore.exe') -h 127.0.0.1 -p $Port -U $Username --exit-on-error --no-owner --no-privileges -d $restoreName ($output+'.dump')
        if($LASTEXITCODE -ne 0){throw 'Restore failed; retained source and restored DB for diagnosis.'}
        $after=Fingerprint $restoreName
        [IO.File]::WriteAllLines(($output+'.after.txt'),$after,[Text.UTF8Encoding]::new($false))
        if(Compare-Object $before $after){throw 'Restored public-table row fingerprint mismatch.'}
        Invariants $restoreName ($output+'.restored-invariants.txt')
        $result.RestorePassed=$true;$result.RestoredDatabase=$restoreName;$result.DumpSHA256=(Get-FileHash ($output+'.dump')).Hash
    }
}catch{$failure=$_.Exception.Message}
finally{
    [Environment]::SetEnvironmentVariable('PGOPTIONS',$savedOptions,'Process')
    [IO.File]::WriteAllText(($output+'.result.json'),(@{Passed=($null -eq $failure);Failure=$failure;Evidence=$result;
        RetainedRestoreDatabase=$restoreName;ArtifactPrefix=$output}|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
if($failure){throw $failure}
Write-Output "EVIDENCE_DB_CHECK_PASS=$output"
