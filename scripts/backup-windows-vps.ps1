param(
    [string]$DatabaseName = "dictalab",
    [string]$DatabaseUser = "dictalab_app",
    [string]$BackupDirectory = "C:\DictaLab\backups",
    [switch]$IncludeMedia,
    [string]$MediaDirectory = "C:\DictaLab\media",
    [ValidateRange(0,3650)][int]$RetentionDays = 30,
    [switch]$InstallDailyTask
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if ($DatabaseName -notmatch '^dictalab(?:_[a-z0-9]+)?$') { throw 'Unexpected backup database name.' }
if ($DatabaseUser -notmatch '^[a-z_][a-z0-9_]*$') { throw 'Unexpected database role.' }
$backupRoot=[IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')+'\'
if($backupRoot -eq [IO.Path]::GetPathRoot($backupRoot)){throw 'Backup directory must not be a drive root.'}
if ($IncludeMedia) {
    if ((Get-Service DictaLab).Status -ne 'Stopped') {
        throw 'Consistent DB/media snapshot requires DictaLab to be stopped; this script never stops it implicitly.'
    }
    if (-not (Test-Path -LiteralPath $MediaDirectory -PathType Container)) { throw 'Media directory is missing.' }
}

$postgresBin = "C:\DictaLab\postgresql-17\bin"
$secretPath = Join-Path $env:ProgramData "DictaLab\database-secrets.xml"
$pgDump = Join-Path $postgresBin "pg_dump.exe"
$pgRestore = Join-Path $postgresBin "pg_restore.exe"
$psql = Join-Path $postgresBin "psql.exe"

foreach ($path in @($secretPath, $pgDump, $pgRestore, $psql)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required PostgreSQL backup file was not found: $path"
    }
}

$secrets = Import-Clixml -LiteralPath $secretPath
$env:PGPASSWORD = $secrets.AppPassword
try {
    $databaseBytesText = & $psql -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -tAc "SELECT pg_database_size(current_database())"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect PostgreSQL database size." }
    $databaseBytes = [int64]($databaseBytesText.Trim())
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @("BUILTIN\Administrators", "NT AUTHORITY\SYSTEM")) {
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $identity, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow"
    )
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $BackupDirectory -AclObject $acl
$driveName = (Split-Path -Qualifier $BackupDirectory).TrimEnd(':')
$drive = Get-PSDrive -Name $driveName
$requiredBytes = [int64]($databaseBytes * 1.2) + 536870912
if($IncludeMedia){$requiredBytes+=[long](Get-ChildItem -LiteralPath $MediaDirectory -File -Recurse | Measure-Object Length -Sum).Sum}
if ($drive.Free -lt $requiredBytes) {
    throw "Insufficient free space for a verified database backup."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$backupPath = Join-Path $BackupDirectory "$DatabaseName-$stamp.dump"
$env:PGPASSWORD = $secrets.AppPassword
try {
    & $pgDump -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -Fc -f $backupPath
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL backup failed." }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf) -or
    (Get-Item -LiteralPath $backupPath).Length -lt 1024) {
    throw "PostgreSQL backup is missing or unexpectedly small."
}
& $pgRestore --list $backupPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
    throw "PostgreSQL backup verification failed."
}

$files=@()
$mediaCopy=$null
if ($IncludeMedia) {
    $mediaCopy=Join-Path $BackupDirectory "$DatabaseName-$stamp.media"
    New-Item -ItemType Directory -Path $mediaCopy | Out-Null
    & robocopy.exe $MediaDirectory $mediaCopy /E /COPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'Media snapshot failed; no success marker was updated.' }
    $sourceRoot=(Resolve-Path -LiteralPath $MediaDirectory).Path.TrimEnd('\')+'\'
    $files=@(Get-ChildItem -LiteralPath $MediaDirectory -File -Recurse | ForEach-Object {
        $relative=$_.FullName.Substring($sourceRoot.Length)
        $hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        $copied=Join-Path $mediaCopy $relative
        if (-not (Test-Path -LiteralPath $copied -PathType Leaf) -or
            (Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash -ne $hash) {
            throw 'Media checksum mismatch; no success marker was updated.'
        }
        [pscustomobject]@{Path=$relative;Bytes=$_.Length;SHA256=$hash}
    })
    if ((Get-Service DictaLab).Status -ne 'Stopped') { throw 'DictaLab restarted during the snapshot; reject consistency.' }
}
$manifest=[pscustomobject]@{At=(Get-Date).ToUniversalTime().ToString('o');Database=$DatabaseName;
    Dump=[IO.Path]::GetFileName($backupPath);DumpSHA256=(Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash;
    MediaSnapshot=if($mediaCopy){[IO.Path]::GetFileName($mediaCopy)}else{$null};
    Consistency=if($IncludeMedia){'quiescent-db-media'}else{'database-only'};MediaFiles=$files}
$manifestPath=Join-Path $BackupDirectory "$DatabaseName-$stamp.manifest.json"
[IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
# Only advance freshness after every requested checksum has passed. Restore success is recorded separately.
[IO.File]::WriteAllText((Join-Path $BackupDirectory 'backup-success.json'),($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output "BACKUP_OK"
Write-Output "BACKUP_FILE=$([System.IO.Path]::GetFileName($backupPath))"
Write-Output "BACKUP_BYTES=$((Get-Item -LiteralPath $backupPath).Length)"
Write-Output "BACKUP_SHA256=$($manifest.DumpSHA256)"
Write-Output "BACKUP_MEDIA_FILES=$($files.Count)"
Write-Output "BACKUP_CONSISTENCY=$($manifest.Consistency)"

# Only complete timestamped backup groups are eligible. Always keep at least the latest seven.
# RetentionDays=0 disables pruning; artifacts, restore logs and unmatched/partial files are never removed.
if($RetentionDays -gt 0){
    $cutoff=(Get-Date).AddDays(-$RetentionDays)
    $manifests=@(Get-ChildItem -LiteralPath $BackupDirectory -File -Filter "$DatabaseName-*.manifest.json" |
        Where-Object {$_.Name -match ('^'+[regex]::Escape($DatabaseName)+'-\d{8}-\d{6}-\d{3}\.manifest\.json$')} |
        Sort-Object LastWriteTime -Descending)
    foreach($old in ($manifests | Select-Object -Skip 7 | Where-Object {$_.LastWriteTime -lt $cutoff})){
        $oldManifest=Get-Content -LiteralPath $old.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $stem=$old.Name.Replace('.manifest.json','')
        if($oldManifest.Dump -ne ($stem+'.dump') -or $oldManifest.Database -ne $DatabaseName){throw 'Retention manifest name mismatch.'}
        $oldDump=[IO.Path]::GetFullPath((Join-Path $BackupDirectory $oldManifest.Dump))
        if(-not $oldDump.StartsWith($backupRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Retention target escapes backup directory.'}
        if(-not (Test-Path -LiteralPath $oldDump -PathType Leaf)){continue}
        if((Get-FileHash -LiteralPath $oldDump -Algorithm SHA256).Hash -ne $oldManifest.DumpSHA256){continue}
        if($oldManifest.MediaSnapshot){
            if($oldManifest.MediaSnapshot -ne ($stem+'.media')){throw 'Retention media name mismatch.'}
            $oldMedia=[IO.Path]::GetFullPath((Join-Path $BackupDirectory $oldManifest.MediaSnapshot))
            if(-not $oldMedia.StartsWith($backupRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Retention media target escapes backup directory.'}
            if(Test-Path -LiteralPath $oldMedia -PathType Container){Remove-Item -LiteralPath $oldMedia -Recurse -Force}
        }
        Remove-Item -LiteralPath $oldDump -Force
        Remove-Item -LiteralPath $old.FullName -Force
        Write-Output "BACKUP_PRUNED=$stem"
    }
}
if($InstallDailyTask){
    if($DatabaseName -ne 'dictalab' -or $BackupDirectory -ne 'C:\DictaLab\backups'){throw 'Daily task supports the production default only.'}
    $scriptPath=$PSCommandPath.Replace("'","''")
    $command="`$ErrorActionPreference='Stop';try{& '$scriptPath' -RetentionDays $RetentionDays *>&1 | Out-File 'C:\DictaLab\backups\daily-backup.log' -Encoding UTF8;exit 0}catch{`$_.Exception.Message | Out-File 'C:\DictaLab\backups\daily-backup.log' -Append -Encoding UTF8;exit 1}"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -NonInteractive -EncodedCommand '+$encoded)
    $trigger=New-ScheduledTaskTrigger -Daily -At '02:30'
    $principal=New-ScheduledTaskPrincipal -UserId 'Administrator' -LogonType S4U -RunLevel Highest
    $settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 15) -StartWhenAvailable -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName 'DictaLab-Daily-Backup' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Write-Output 'DAILY_DATABASE_BACKUP_SCHEDULED=02:30 server-local; media snapshots remain explicit quiescent release gates'
}
