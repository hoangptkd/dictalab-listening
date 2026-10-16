param([Parameter(Mandatory=$true)][int]$CapacityPid)
$ErrorActionPreference='Stop'
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$CapacityPid"
if(-not $process){Write-Output 'CAPACITY_ALREADY_STOPPED';exit}
if($process.Name -ne 'java.exe' -or $process.CommandLine -notmatch 'C:\\DictaLab\\staging\\dictalab-(capacity|baseline)\.jar'){
    throw 'The PID is not a recognized isolated capacity application.'
}
Stop-Process -Id $CapacityPid
Write-Output "CAPACITY_STOPPED=$CapacityPid"
