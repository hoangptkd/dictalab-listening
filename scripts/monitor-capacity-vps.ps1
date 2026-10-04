param([ValidateRange(10,3600)][int]$Seconds=180,[int]$CapacityPid=0,
    [string]$Output='C:\DictaLab\capacity\resources.csv',[switch]$StopAtEnd)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$samples=[Collections.Generic.List[object]]::new()
$highCPU=0
$watch=[Diagnostics.Stopwatch]::StartNew()
$metricValues=@{}
$sampleNumber=0
$previousCPU=@{}
$previousTime=Get-Date
function Stop-CapacityProcess {
    if($CapacityPid -le 0){return}
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$CapacityPid"
    if($process -and $process.Name -eq 'java.exe' -and
        $process.CommandLine -match 'C:\\DictaLab\\staging\\dictalab-(capacity|baseline)\.jar'){
        Stop-Process -Id $CapacityPid
    }else{throw 'Abort target did not match the isolated capacity application.'}
}
try{
    while($watch.Elapsed.TotalSeconds -lt $Seconds){
        $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
        $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $disk=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
        $io=Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'"
        $network=Get-CimInstance Win32_PerfFormattedData_Tcpip_NetworkInterface
        $java=if($CapacityPid -gt 0){Get-Process -Id $CapacityPid -ErrorAction SilentlyContinue}else{$null}
        $sampleTime=Get-Date
        $sampleSeconds=($sampleTime-$previousTime).TotalSeconds
        $currentCPU=@{};$appCPU=0;$generatorCPU=0;$otherCPU=0;$otherName=''
        foreach($process in (Get-Process)){
            try{
                if($null -eq $process.CPU){continue}
                $currentCPU[$process.Id]=$process.CPU
                if(-not $previousCPU.ContainsKey($process.Id) -or $sampleSeconds -le 0){continue}
                $share=[math]::Max(0,100*($process.CPU-$previousCPU[$process.Id])/$sampleSeconds/[Environment]::ProcessorCount)
                if($process.Id -eq $CapacityPid){$appCPU=$share}
                elseif($process.ProcessName -eq 'k6'){$generatorCPU+=$share}
                elseif($share -gt $otherCPU){$otherCPU=$share;$otherName=$process.ProcessName}
            }catch{} # A process may exit between enumeration and reading its CPU counter.
        }
        $previousCPU=$currentCPU;$previousTime=$sampleTime
        if(($sampleNumber++ % 3) -eq 0 -and $java){
            try {
                $scrape=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18283/actuator/prometheus' -TimeoutSec 2).Content
                $metricValues=@{}
                foreach($line in $scrape.Split("`n")){
                    if($line -match '^(hikaricp_connections_active|hikaricp_connections_pending|jvm_gc_pause_seconds_sum|jvm_gc_pause_seconds_count|jvm_memory_used_bytes|jvm_threads_live_threads)(?:\{[^}]*\})?\s+([0-9.eE+-]+)$'){
                        $metricName=$Matches[1]
                        $value=[double]::Parse($Matches[2],[Globalization.CultureInfo]::InvariantCulture)
                        $metricValues[$metricName]+=$value
                    }
                }
                [IO.File]::WriteAllText(($Output+'.prometheus'),$scrape,[Text.UTF8Encoding]::new($false))
            }catch { $metricValues=@{} }
        }
        $samples.Add([pscustomobject]@{Time=(Get-Date).ToString('o');CPUPercent=$cpu.PercentProcessorTime;
            AppCPUPercent=[math]::Round($appCPU,2);GeneratorCPUPercent=[math]::Round($generatorCPU,2);
            TopOtherProcess=$otherName;TopOtherCPUPercent=[math]::Round($otherCPU,2);
            FreeRAMMiB=$memory.AvailableMBytes;PagesInputSec=$memory.PagesInputPersec;
            FreeDiskGiB=[math]::Round($disk.FreeSpace/1GB,2);DiskQueue=$io.CurrentDiskQueueLength;
            NetworkBytesSec=($network|Measure-Object BytesTotalPersec -Sum).Sum;
            AppRSSMiB=if($java){[math]::Round($java.WorkingSet64/1MB,2)}else{0};
            PoolActive=$metricValues['hikaricp_connections_active'];PoolPending=$metricValues['hikaricp_connections_pending'];
            JVMUsedBytes=$metricValues['jvm_memory_used_bytes'];JVMThreads=$metricValues['jvm_threads_live_threads'];
            GCPauseSeconds=$metricValues['jvm_gc_pause_seconds_sum'];GCCount=$metricValues['jvm_gc_pause_seconds_count']})
        if($CapacityPid -gt 0 -and -not $java){Write-Output 'CAPACITY_APPLICATION_EXITED';break}
        if($cpu.PercentProcessorTime -ge 85){$highCPU++}else{$highCPU=0}
        if($highCPU -ge 6 -or $memory.AvailableMBytes -lt 500 -or $disk.FreeSpace -lt 10GB){
            Write-Output 'CAPACITY_ABORT_RESOURCE_THRESHOLD';Stop-CapacityProcess;break
        }
        Start-Sleep -Seconds 5
    }
}finally{
    $samples|Export-Csv -LiteralPath $Output -NoTypeInformation -Encoding UTF8
    if($StopAtEnd -and (Get-Process -Id $CapacityPid -ErrorAction SilentlyContinue)){Stop-CapacityProcess}
    if($samples.Count){
        [pscustomobject]@{Samples=$samples.Count;MaxCPU=($samples|Measure-Object CPUPercent -Maximum).Maximum;
            MinFreeRAMMiB=($samples|Measure-Object FreeRAMMiB -Minimum).Minimum;Output=$Output}|ConvertTo-Json -Compress
    }
}
