$ErrorActionPreference='Stop'
# Exercise the real retention block on disposable groups, without a database or privileged service access.
$tokens=$null;$errors=$null
$source=Join-Path $PSScriptRoot 'backup-windows-vps.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors){throw 'Backup script does not parse.'}
$retention=$ast.Find({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and
    $node.Clauses[0].Item1.Extent.Text -eq '$RetentionDays -gt 0'},$true)
if(-not $retention){throw 'Retention block was not found.'}
$BackupDirectory=Join-Path ([IO.Path]::GetTempPath()) ('dictalab-retention-test-'+[guid]::NewGuid().ToString('N'))
$backupRoot=[IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')+'\'
$DatabaseName='dictalab';$RetentionDays=30
New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
try {
    for($n=1;$n -le 9;$n++){
        $stem='dictalab-20260101-000000-'+$n.ToString('000')
        $dump=Join-Path $BackupDirectory ($stem+'.dump')
        [IO.File]::WriteAllText($dump,'disposable test dump')
        $media=Join-Path $BackupDirectory ($stem+'.media')
        New-Item -ItemType Directory -Path $media | Out-Null
        [IO.File]::WriteAllText((Join-Path $media 'fixture.mp3'),'disposable media')
        $manifest=@{Database='dictalab';Dump=($stem+'.dump');MediaSnapshot=($stem+'.media');DumpSHA256=(Get-FileHash $dump -Algorithm SHA256).Hash}
        $path=Join-Path $BackupDirectory ($stem+'.manifest.json')
        [IO.File]::WriteAllText($path,($manifest|ConvertTo-Json))
        (Get-Item $path).LastWriteTime=(Get-Date).AddDays(-60).AddSeconds($n)
    }
    & ([scriptblock]::Create($retention.Extent.Text))
    if(@(Get-ChildItem $BackupDirectory -Filter '*.dump').Count -ne 7){throw 'Retention must preserve seven complete groups.'}
    if(@(Get-ChildItem $BackupDirectory -Directory).Count -ne 7){throw 'Retention must prune only matching media groups.'}
    if(Test-Path (Join-Path $BackupDirectory 'dictalab-20260101-000000-001.dump')){throw 'Oldest eligible group was not pruned.'}
    'BACKUP_RETENTION_CHECK_PASS'
}finally{
    $resolved=[IO.Path]::GetFullPath($BackupDirectory)
    $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if($resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -match '^dictalab-retention-test-[a-f0-9]{32}$'){
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
