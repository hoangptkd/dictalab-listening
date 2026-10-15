param(
    [string]$DestinationRoot='C:\Users\HP\.codex\backups\DictaLab',
    [string]$SshKey='C:\Users\HP\.ssh\dictalab_vps_ed25519_v2',
    [string]$VerifyDirectory=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Test-Snapshot([string]$Directory){
    $root=(Resolve-Path -LiteralPath $Directory).Path.TrimEnd('\')+'\'
    $manifests=@(Get-ChildItem -LiteralPath $Directory -Filter 'dictalab-*.manifest.json' -File)
    if($manifests.Count -ne 1){throw 'Exactly one source manifest is required.'}
    $manifest=Get-Content -LiteralPath $manifests[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $stem=$manifests[0].Name.Replace('.manifest.json','')
    if($stem -notmatch '^dictalab-\d{8}-\d{6}-\d{3}$' -or $manifest.Database -ne 'dictalab' -or
       $manifest.Consistency -ne 'quiescent-db-media' -or $manifest.Dump -ne "$stem.dump" -or $manifest.MediaSnapshot -ne "$stem.media"){
        throw 'Not a complete quiescent DictaLab DB/media snapshot.'
    }
    if((Get-FileHash -LiteralPath (Join-Path $Directory $manifest.Dump)).Hash -ne $manifest.DumpSHA256){throw 'Dump checksum mismatch.'}
    $mediaRoot=[IO.Path]::GetFullPath((Join-Path $Directory $manifest.MediaSnapshot)).TrimEnd('\')+'\'
    if(-not $mediaRoot.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'Media root escapes snapshot.'}
    $seen=@{}
    foreach($file in $manifest.MediaFiles){
        if([IO.Path]::IsPathRooted($file.Path) -or $file.Path -match '(^|[\\/])\.\.([\\/]|$)' -or $file.Path.Contains(':')){throw 'Unsafe media manifest path.'}
        $path=[IO.Path]::GetFullPath((Join-Path $mediaRoot $file.Path))
        if(-not $path.StartsWith($mediaRoot,[StringComparison]::OrdinalIgnoreCase) -or $seen.ContainsKey($path)){throw 'Duplicate or escaping media path.'}
        $seen[$path]=$true
        if((Get-FileHash -LiteralPath $path).Hash -ne $file.SHA256 -or (Get-Item -LiteralPath $path).Length -ne $file.Bytes){throw 'Media checksum or size mismatch.'}
    }
    return $manifest
}
if($VerifyDirectory){
    $manifest=Test-Snapshot $VerifyDirectory
    Write-Output "SNAPSHOT_VERIFIED=$($manifest.Dump); media=$(@($manifest.MediaFiles).Count)"
    exit 0
}
$common=@('-i',$SshKey,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
    '-o','ConnectTimeout=10','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3')
$query=@'
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$latest=Get-ChildItem -LiteralPath C:\DictaLab\backups -Filter 'dictalab-*.manifest.json' -File | Sort-Object Name -Descending | Where-Object {
    (Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json).Consistency -eq 'quiescent-db-media'
} | Select-Object -First 1
if(-not $latest){throw 'No quiescent DB/media backup exists.'}
$latest.Name
'@
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($query))
$name=(& ssh @common Administrator@160.191.244.27 powershell.exe -NoProfile -NonInteractive -EncodedCommand $encoded) -join ''
if($LASTEXITCODE -ne 0 -or $name -notmatch '^dictalab-\d{8}-\d{6}-\d{3}\.manifest\.json$'){throw 'Cannot select a verified backup group.'}
$stem=$name.Replace('.manifest.json','')
$destination=Join-Path $DestinationRoot ('synced-'+$stem)
if(Test-Path -LiteralPath $destination){
    $null=Test-Snapshot $destination
    Write-Output "ALREADY_VERIFIED=$destination"
    exit 0
}
New-Item -ItemType Directory -Path $destination | Out-Null
$acl=New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-32-544','S-1-5-18')){
    $identity=New-Object Security.Principal.SecurityIdentifier($sid)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
}
Set-Acl -LiteralPath $destination -AclObject $acl
# All remote names were validated above. A per-process wall-clock budget includes stalled transfers.
$arguments=@($common)+@('-r',"Administrator@160.191.244.27:C:/DictaLab/backups/$name",
    "Administrator@160.191.244.27:C:/DictaLab/backups/$stem.dump",
    "Administrator@160.191.244.27:C:/DictaLab/backups/$stem.media",$destination)
$quoted=@($arguments|ForEach-Object {'"'+$_.Replace('"','\"')+'"'})
$copy=Start-Process scp.exe -ArgumentList $quoted -WindowStyle Hidden -PassThru
$null=$copy.Handle
try{
    if(-not $copy.WaitForExit(300000)){throw 'Off-host copy exceeded five minutes; partial snapshot retained without success marker.'}
    if($null -eq $copy.ExitCode -or $copy.ExitCode -ne 0){throw 'Off-host copy failed.'}
    $manifest=Test-Snapshot $destination
    [pscustomobject]@{VerifiedAt=(Get-Date).ToUniversalTime().ToString('o');SourceBackupAt=$manifest.At;
        DumpSHA256=$manifest.DumpSHA256;MediaCount=@($manifest.MediaFiles).Count;Consistency=$manifest.Consistency} |
        ConvertTo-Json | Out-File -LiteralPath (Join-Path $destination 'offhost-success.json') -Encoding utf8
    Write-Output "OFFHOST_BACKUP_VERIFIED=$destination"
    Write-Output "SOURCE_BACKUP_AT=$($manifest.At)"
}finally{if(-not $copy.HasExited){Stop-Process -Id $copy.Id}}
