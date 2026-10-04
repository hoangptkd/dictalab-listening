param([ValidateSet('core','listening','conversation','rooms')][string]$Module='core',
    [ValidateSet('journey','reads','arrival','mixed')][string]$Mode='reads',
    [ValidateSet('load','spike')][string]$Profile='load',
    [ValidateRange(1,20000)][int]$Users=1,[ValidateRange(10,1800)][int]$Seconds=30,
    [ValidateRange(1,10000)][int]$Rate=10,[string]$Label='new',
    [switch]$AckProviderCalls)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputRoot=Join-Path $projectRoot 'target\capacity'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if($Label -notmatch '^[a-zA-Z0-9_-]{1,40}$'){throw 'Invalid result label.'}
$prefix=Join-Path $outputRoot ("$Label-$Module-$Mode-$Users-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$sshKey='C:\Users\HP\.ssh\dictalab_vps_ed25519_v2'
$tunnel=$null;$load=$null;$failure=$null
$samples=[Collections.Generic.List[object]]::new()
try {
    if(Get-Process k6 -ErrorAction SilentlyContinue){throw 'Another local generator is running; no overlapping load is permitted.'}
    foreach($port in @(17282,17283)){
        if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw 'Capacity tunnel port already in use.'}
    }
    $tunnel=Start-Process ssh.exe -WindowStyle Hidden -PassThru -ArgumentList @('-i',('"'+$sshKey+'"'),
        '-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
        '-o','ExitOnForwardFailure=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3','-N',
        '-L','127.0.0.1:17282:127.0.0.1:18282','-L','127.0.0.1:17283:127.0.0.1:18283','Administrator@160.191.244.27') `
        -RedirectStandardOutput ($prefix+'.ssh.stdout.log') -RedirectStandardError ($prefix+'.ssh.stderr.log')
    $ready=$false
    for($attempt=0;$attempt -lt 40;$attempt++){
        if($tunnel.HasExited){throw 'Capacity tunnel exited.'}
        try {$health=Invoke-RestMethod 'http://127.0.0.1:17282/actuator/health' -TimeoutSec 3
            if($health.status -eq 'UP'){$ready=$true;break}}catch{}
        Start-Sleep -Seconds 2
    }
    if(-not $ready){throw 'Isolated capacity application did not become healthy.'}
    $env:BASE_URL='http://127.0.0.1:17282';$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
    $env:USERS=[string]$Users;$env:SECONDS=[string]$Seconds;$env:MODE=$Mode;$env:PROFILE=$Profile;$env:RATE=[string]$Rate
    $env:ACK_PROVIDER_CALLS=if($AckProviderCalls){'true'}else{'false'}
    $env:K6_NO_USAGE_REPORT='true'
    $load=Start-Process -FilePath (Get-Command k6.exe).Source -WindowStyle Hidden -PassThru -ArgumentList @(
        'run','--quiet','--summary-trend-stats','avg,min,med,max,p(90),p(95),p(99)',
        '--summary-export',('"'+$prefix+'.json"'),('"'+(Join-Path $PSScriptRoot "load\$Module.js")+'"')) `
        -RedirectStandardOutput ($prefix+'.stdout.log') -RedirectStandardError ($prefix+'.stderr.log')
    $null=$load.Handle
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $highCPU=0
    while(-not $load.WaitForExit(5000) -and $watch.Elapsed.TotalSeconds -lt ($Seconds+630)){
        if($tunnel.HasExited){throw "Capacity transport exited before workload completed; inspect $prefix.ssh.stderr.log"}
        $load.Refresh()
        $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
        if($cpu.PercentProcessorTime -ge 85){$highCPU++}else{$highCPU=0}
        $samples.Add([pscustomobject]@{Time=(Get-Date).ToString('o');FreeRAMMiB=$memory.AvailableMBytes;CPUPercent=$cpu.PercentProcessorTime;LoadGeneratorRSSMiB=[math]::Round($load.WorkingSet64/1MB,2);
            LoadGeneratorCPUSeconds=$load.TotalProcessorTime.TotalSeconds})
        if($memory.AvailableMBytes -lt 500 -or $highCPU -ge 6){throw 'HP generator resource guard stopped this run.'}
        try {
            $scrape=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:17283/actuator/prometheus' -TimeoutSec 3).Content
            [IO.File]::WriteAllText(($prefix+'.prometheus'),$scrape,[Text.UTF8Encoding]::new($false))
        }catch{Write-Output 'MANAGEMENT_SNAPSHOT_UNAVAILABLE'}
    }
    if(-not $load.HasExited){
        & taskkill.exe /PID $load.Id /T /F | Out-Null
        throw 'Load generator exceeded its setup/work/cleanup budget.'
    }
    if($null -eq $load.ExitCode -or $load.ExitCode -ne 0){throw "Load thresholds/checks failed: exit $($load.ExitCode)."}
    Write-Output "CAPACITY_CHECK_PASS=$prefix"
}catch{$failure=$_.Exception.Message;Write-Output "CAPACITY_CHECK_FAIL=$failure"}
finally {
    if($load -and -not $load.HasExited){Stop-Process -Id $load.Id}
    if($tunnel -and -not $tunnel.HasExited){Stop-Process -Id $tunnel.Id}
    foreach($name in @('BASE_URL','CAPACITY_PASSWORD','USERS','SECONDS','MODE','PROFILE','RATE','ACK_PROVIDER_CALLS','K6_NO_USAGE_REPORT')){
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    $samples | Export-Csv -LiteralPath ($prefix+'.generator.csv') -NoTypeInformation -Encoding UTF8
    [IO.File]::WriteAllText(($prefix+'.result.json'),([pscustomobject]@{At=(Get-Date).ToString('o');
        Module=$Module;Mode=$Mode;Profile=$Profile;Users=$Users;Seconds=$Seconds;Passed=($null -eq $failure);
        Failure=$failure;Summary=$prefix+'.json'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if($failure){exit 1}
