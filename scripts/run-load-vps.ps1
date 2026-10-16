param([ValidateRange(1,1000)][int]$Users=50,[ValidateRange(10,900)][int]$Seconds=60,
    [ValidateSet('core','listening','rooms','conversation')][string]$Module='core',
    [ValidateSet('reads','arrival','journey','mixed')][string]$Mode='reads',
    [ValidateSet('load','spike')][string]$Profile='load',
    [ValidateRange(1,2000)][int]$Rate=100,[string]$Label='loopback',
    [switch]$AckProviderCalls,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedK6SHA256)
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9_-]{1,40}$'){throw 'Invalid output label.'}
if($Module -eq 'conversation' -and (-not $AckProviderCalls -or $Users -gt 8)){throw 'Real AI smoke requires acknowledgement and at most eight test accounts.'}
$root='C:\DictaLab\capacity'
if((Get-FileHash -LiteralPath (Join-Path $root 'k6.exe') -Algorithm SHA256).Hash -ne $ExpectedK6SHA256){
    throw 'Transferred k6 binary checksum mismatch; do not execute a partial upload.'
}
$prefix=Join-Path $root ("$Label-$Module-$Mode-$Users-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$load=$null
try {
    $health=Invoke-RestMethod 'http://127.0.0.1:18282/actuator/health' -TimeoutSec 5
    if($health.status -ne 'UP'){throw 'Isolated application is not ready.'}
    $env:BASE_URL='http://127.0.0.1:18282';$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
    $env:USERS=[string]$Users;$env:SECONDS=[string]$Seconds;$env:MODE=$Mode;$env:PROFILE=$Profile;$env:RATE=[string]$Rate
    $env:K6_NO_USAGE_REPORT='true'
    if($AckProviderCalls){$env:ACK_PROVIDER_CALLS='true'}
    $load=Start-Process -FilePath (Join-Path $root 'k6.exe') -WindowStyle Hidden -PassThru -ArgumentList @(
        'run','--quiet','--summary-trend-stats','avg,min,med,max,p(90),p(95),p(99)',
        '--summary-export',('"'+$prefix+'.json"'),('"'+(Join-Path $root "load\$Module.js")+'"')) `
        -RedirectStandardOutput ($prefix+'.stdout.log') -RedirectStandardError ($prefix+'.stderr.log')
    $null=$load.Handle
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $samples=[Collections.Generic.List[object]]::new()
    while(-not $load.WaitForExit(5000) -and $watch.Elapsed.TotalSeconds -lt ($Seconds+630)){
        $load.Refresh()
        $free=(Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory).AvailableMBytes
        $samples.Add([pscustomobject]@{Time=(Get-Date).ToString('o');FreeRAMMiB=$free;
            GeneratorRSSMiB=[math]::Round($load.WorkingSet64/1MB,2);GeneratorCPUSeconds=$load.TotalProcessorTime.TotalSeconds})
        if($free -lt 500){throw 'Co-located generator aborted at the free RAM safety threshold.'}
        try {
            $scrape=(Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:18283/actuator/prometheus' -TimeoutSec 2).Content
            [IO.File]::WriteAllText(($prefix+'.prometheus'),$scrape,[Text.UTF8Encoding]::new($false))
        }catch{}
    }
    if(-not $load.HasExited){throw 'Load generator exceeded its declared lifetime.'}
    if($null -eq $load.ExitCode -or $load.ExitCode -ne 0){throw "Load thresholds/checks failed: exit $($load.ExitCode)"}
    Write-Output "LOCAL_VPS_LOAD_PASS=$prefix"
}finally {
    if($load -and -not $load.HasExited){Stop-Process -Id $load.Id}
    if($samples){$samples | Export-Csv -LiteralPath ($prefix+'.generator.csv') -NoTypeInformation -Encoding UTF8}
    foreach($name in @('BASE_URL','CAPACITY_PASSWORD','USERS','SECONDS','MODE','PROFILE','RATE','K6_NO_USAGE_REPORT','ACK_PROVIDER_CALLS')){
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
