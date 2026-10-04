param([ValidateRange(60,900)][int]$WaitSeconds=600,[switch]$WarmMixedOnly,[switch]$RequireLowBackgroundLoad,
    [ValidateRange(5,1000)][int[]]$MixedUsers=@(100,200,300))
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
if(@($MixedUsers|Where-Object {$_ % 5 -ne 0}).Count){throw 'The exact 80/20 mix requires user counts divisible by five.'}
$root='C:\DictaLab\capacity';$capacityPid=0
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
try {
    if($RequireLowBackgroundLoad){
        if(Get-Process k6 -ErrorAction SilentlyContinue){throw 'A generator is still running; no overlapping load is permitted.'}
        $preflight=Join-Path $root "low-load-preflight-$stamp.csv"
        & (Join-Path $root 'monitor-capacity-vps.ps1') -Seconds 60 -Output $preflight
        $samples=@(Import-Csv -LiteralPath $preflight)
        $averageCPU=($samples|Measure-Object CPUPercent -Average).Average
        $peakCPU=($samples|Measure-Object CPUPercent -Maximum).Maximum
        $freeRAM=($samples|Measure-Object FreeRAMMiB -Minimum).Minimum
        $diskQueue=($samples|Measure-Object DiskQueue -Maximum).Maximum
        $paging=($samples|Measure-Object PagesInputSec -Maximum).Maximum
        if($samples.Count -lt 10 -or $averageCPU -gt 35 -or $peakCPU -gt 60 -or
            $freeRAM -lt 1800 -or $diskQueue -gt 2 -or $paging -gt 100){
            throw "Low-load preflight declined: samples=$($samples.Count), averageCPU=$averageCPU, peakCPU=$peakCPU, freeRAMMiB=$freeRAM, diskQueue=$diskQueue, paging=$paging. No load started; no automatic retry."
        }
        Write-Output 'LOW_LOAD_PREFLIGHT_PASS'
    }
    if(-not $WarmMixedOnly){
    # Wait for the existing bounded batch, never overlap generators.
    $deadline=(Get-Date).AddSeconds($WaitSeconds)
    while((Get-ScheduledTask -TaskName 'DictaLab-Load-OneShot').State -eq 'Running'){
        if((Get-Date) -ge $deadline){throw 'Previous load batch did not finish within the wait budget.'}
        Start-Sleep -Seconds 5
    }
    if(Get-Process k6 -ErrorAction SilentlyContinue){throw 'A generator is still running; no overlapping load is permitted.'}
    $old=Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'java.exe' -and $_.CommandLine -match 'C:\\DictaLab\\staging\\dictalab-capacity\.jar'}
    if(@($old).Count -ne 1){throw 'Expected exactly one previous isolated application.'}
    & (Join-Path $root 'stop-capacity-vps.ps1') -CapacityPid $old.ProcessId
    $deadline=(Get-Date).AddSeconds(60)
    while((Get-ScheduledTask -TaskName 'DictaLab-Capacity-OneShot').State -eq 'Running'){
        if((Get-Date) -ge $deadline){throw 'Previous application controller did not terminate.'}
        Start-Sleep -Seconds 2
    }
    # Let the previous watchdog notice its owned PID has exited before resources.csv is reused.
    Start-Sleep -Seconds 7
    Copy-Item -LiteralPath (Join-Path $root 'resources.csv') -Destination (Join-Path $root "current-batch-resources-$stamp.csv")
    Copy-Item -LiteralPath (Join-Path $root 'resources.csv.prometheus') -Destination (Join-Path $root "current-batch-resources-$stamp.prometheus")
    foreach($name in @('DictaLab-Capacity-OneShot','DictaLab-Load-OneShot')){
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
    }
    }
    $start=@{HeapMB=512;MaxRuntimeSeconds=1500}
    if(-not $WarmMixedOnly){$start.RealProviders=$true}
    $output=& (Join-Path $root 'start-capacity-vps.ps1') @start
    foreach($line in $output){Write-Output $line;if($line -match '^CAPACITY_PID=(\d+)$'){$capacityPid=[int]$Matches[1]}}
    if($capacityPid -le 0){throw 'No isolated application PID was returned.'}
    $ready=$false;$deadline=(Get-Date).AddSeconds(120)
    do {
        try{$ready=(Invoke-RestMethod 'http://127.0.0.1:18282/actuator/health' -TimeoutSec 3).status -eq 'UP'}catch{}
        if(-not $ready){Start-Sleep -Seconds 2}
    }while(-not $ready -and (Get-Date) -lt $deadline)
    if(-not $ready){throw 'Final isolated application startup exceeded 120 seconds.'}
    $checksum='87DFA91BC3E47BC4BD77911D59D7FF79F25CD76FB8322C97072F08F08A0DA5ED'
    $cases=if($WarmMixedOnly){@(
        @{Module='core';Mode='reads';Users=5;Seconds=30;Label='mixed-warmup-reads'},
        @{Module='core';Mode='journey';Users=5;Seconds=30;Label='mixed-warmup-writes'}
    ) + @($MixedUsers|ForEach-Object {@{Module='core';Mode='mixed';Users=$_;Seconds=120;Label='warm-mixed'}})}else{@(
        @{Module='conversation';Users=1;Seconds=600;Label='final-ai';AckProviderCalls=$true},
        @{Module='core';Mode='mixed';Users=100;Seconds=120;Label='final-mixed'},
        @{Module='core';Mode='reads';Profile='spike';Users=200;Seconds=120;Label='final-spike'})}
    foreach($case in $cases){
        try{
            Write-Output "FINAL_CASE_START=$($case.Label), users=$($case.Users), seconds=$($case.Seconds), at=$((Get-Date).ToString('o'))"
            & (Join-Path $root 'run-load-vps.ps1') @case -ExpectedK6SHA256 $checksum
            # Under-one-percent acceptance is not permission to escalate after observed server failures.
            if($WarmMixedOnly -and $case.Label -eq 'warm-mixed'){
                $summary=Get-ChildItem -LiteralPath $root -Filter ("warm-mixed-core-mixed-$($case.Users)-*.json") |
                    Sort-Object LastWriteTime -Descending | Select-Object -First 1
                $result=Get-Content -LiteralPath $summary.FullName -Raw | ConvertFrom-Json
                if($result.metrics.http_req_failed.value -gt 0 -or $result.metrics.business_failed.value -gt 0){
                    throw 'Mixed workload recorded errors; escalation stopped.'
                }
            }
        }
        catch{
            Write-Output "FINAL_CASE_FAILED=$($case.Label): $($_.Exception.Message)"
            throw
        }
    }
} finally {
    if($capacityPid -gt 0){& (Join-Path $root 'stop-capacity-vps.ps1') -CapacityPid $capacityPid}
    Start-Sleep -Seconds 7
    if($capacityPid -gt 0 -and (Test-Path -LiteralPath (Join-Path $root 'resources.csv'))){
        Copy-Item -LiteralPath (Join-Path $root 'resources.csv') -Destination (Join-Path $root "final-batch-resources-$stamp.csv")
    }
    Write-Output 'FINAL_CAPACITY_CONTROLLER_FINISHED'
}
