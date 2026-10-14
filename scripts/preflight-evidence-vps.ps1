param([ValidatePattern('^[a-zA-Z0-9_-]{1,40}$')][string]$Label='evidence')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputRoot=Join-Path $projectRoot 'target\capacity'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$remote="C:\DictaLab\capacity\$Label-preflight-$stamp.csv"
$local=Join-Path $outputRoot "$Label-preflight-$stamp.csv"
# Observe only: no service, JVM, database or user application is stopped or started.
# The monitor samples after startup work; 70 seconds reliably yields the required
# ten observations without changing any resource threshold.
$command="`$ErrorActionPreference='Stop'; & 'C:\DictaLab\capacity\monitor-capacity-vps.ps1' -Seconds 70 -Output '$remote'"
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
$sshArgs=@('-i','C:/Users/HP/.ssh/dictalab_vps_ed25519_v2','-o','BatchMode=yes','-o','IdentitiesOnly=yes',
    '-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3')
& ssh.exe @sshArgs Administrator@160.191.244.27 "powershell.exe -NoProfile -EncodedCommand $encoded"
if($LASTEXITCODE -ne 0){throw 'VPS preflight observation failed; no load started.'}
& scp.exe @sshArgs "Administrator@160.191.244.27:$($remote.Replace('\','/'))" $local
if($LASTEXITCODE -ne 0){throw 'VPS preflight artifact retrieval failed; no load started.'}
$samples=@(Import-Csv -LiteralPath $local)
$averageCPU=($samples|Measure-Object CPUPercent -Average).Average
$peakCPU=($samples|Measure-Object CPUPercent -Maximum).Maximum
$freeRAM=($samples|Measure-Object FreeRAMMiB -Minimum).Minimum
$diskQueue=($samples|Measure-Object DiskQueue -Maximum).Maximum
$paging=($samples|Measure-Object PagesInputSec -Maximum).Maximum
$freeDisk=($samples|Measure-Object FreeDiskGiB -Minimum).Minimum
$passed=$samples.Count -ge 10 -and $averageCPU -le 35 -and $peakCPU -le 60 -and
    $freeRAM -ge 1800 -and $diskQueue -le 2 -and $paging -le 100 -and $freeDisk -ge 10
$result=[pscustomobject]@{At=(Get-Date).ToString('o');Passed=$passed;LoadStarted=$false;Samples=$samples.Count;
    AverageCPU=$averageCPU;PeakCPU=$peakCPU;MinFreeRAMMiB=$freeRAM;MaxDiskQueue=$diskQueue;
    MaxPagesInputSec=$paging;MinFreeDiskGiB=$freeDisk;Raw=$local;
    Required=@{MinSamples=10;AverageCPU=35;PeakCPU=60;MinRAMMiB=1800;DiskQueue=2;PagesInputSec=100;MinDiskGiB=10}}
[IO.File]::WriteAllText(($local+'.result.json'),($result|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$result|ConvertTo-Json -Depth 4
if(-not $passed){throw 'Existing VPS resource guard declined. No load started; no automatic retry.'}
