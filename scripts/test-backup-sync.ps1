$ErrorActionPreference='Stop'
$root=Join-Path (Split-Path -Parent $PSScriptRoot) ('target\backup-sync-test-'+[guid]::NewGuid().ToString('N'))
$stem='dictalab-20261003-000000-000'
$media=Join-Path $root "$stem.media"
New-Item -ItemType Directory -Path $media | Out-Null
$dump=Join-Path $root "$stem.dump"
$audio=Join-Path $media 'fixture.wav'
[IO.File]::WriteAllBytes($dump,[byte[]](1,2,3))
[IO.File]::WriteAllBytes($audio,[byte[]](4,5,6))
$manifest=[pscustomobject]@{At='2026-10-03T00:00:00Z';Database='dictalab';Dump="$stem.dump";
    DumpSHA256=(Get-FileHash $dump).Hash;MediaSnapshot="$stem.media";Consistency='quiescent-db-media';
    MediaFiles=@([pscustomobject]@{Path='fixture.wav';Bytes=3;SHA256=(Get-FileHash $audio).Hash})}
$manifestPath=Join-Path $root "$stem.manifest.json"
function Save-Manifest { [IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false)) }
function Must-Reject([string]$Reason){
    $rejected=$false
    try{& (Join-Path $PSScriptRoot 'sync-backup-from-vps.ps1') -VerifyDirectory $root}catch{$rejected=$true}
    if(-not $rejected){throw "Verification accepted $Reason"}
}
Save-Manifest
& (Join-Path $PSScriptRoot 'sync-backup-from-vps.ps1') -VerifyDirectory $root
if($LASTEXITCODE -ne 0){throw 'Valid checksum fixture failed.'}
[IO.File]::WriteAllBytes($audio,[byte[]](9,9,9))
Must-Reject 'corrupt media'
[IO.File]::WriteAllBytes($audio,[byte[]](4,5,6))
$manifest.MediaFiles[0].Path='..\..\outside.wav';Save-Manifest
Must-Reject 'path traversal'
$manifest.MediaFiles[0].Path='fixture.wav';$manifest.Consistency='database-only';Save-Manifest
Must-Reject 'database-only snapshot'
$manifest.Consistency='quiescent-db-media';$manifest.DumpSHA256='0'*64;Save-Manifest
Must-Reject 'corrupt dump'
Write-Output "BACKUP_SYNC_TEST_PASS=5 cases; fixtures retained at $root"
