param([ValidateRange(60,3600)][int]$Seconds=1800,[int]$HeapMB=512,[switch]$Baseline,[switch]$RealProviders)
$ErrorActionPreference='Stop'
$capacityPid=0
try {
    $startArgs=@{HeapMB=$HeapMB;MaxRuntimeSeconds=$Seconds}
    if($RealProviders){$startArgs.RealProviders=$true}
    if($Baseline){$startArgs.Baseline=$true;$startArgs.Artifact='C:\DictaLab\staging\dictalab-baseline.jar'}
    $output=& 'C:\DictaLab\capacity\start-capacity-vps.ps1' @startArgs
    foreach($line in $output){
        Write-Output $line
        if($line -match '^CAPACITY_PID=(\d+)$'){$capacityPid=[int]$Matches[1]}
    }
    if($capacityPid -le 0){throw 'Isolated application PID was not returned.'}
    # Keep the one-shot task alive. Its independent watchdog still protects against controller failure.
    $process=Get-Process -Id $capacityPid -ErrorAction Stop
    $null=$process.Handle
    if(-not $process.WaitForExit(($Seconds+10)*1000)){throw 'Capacity lifetime exceeded the watchdog budget.'}
}finally {
    if($capacityPid -gt 0){& 'C:\DictaLab\capacity\stop-capacity-vps.ps1' -CapacityPid $capacityPid}
}
