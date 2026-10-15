param([ValidateSet('core','listening','conversation','rooms','evidence','practice-ux')][string]$Module='core',
    [ValidateSet('journey','reads','arrival','mixed')][string]$Mode='reads',
    [ValidateSet('load','spike','smoke')][string]$Profile='load',
    [ValidateRange(1,20000)][int]$Users=1,[ValidateRange(10,10800)][int]$Seconds=30,
    [ValidateRange(1,10000)][int]$Rate=10,[string]$Label='new',
    [switch]$AckProviderCalls,[switch]$LocalSmoke,[string]$RuntimeManifest='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputRoot=Join-Path $projectRoot 'target\capacity'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
if($Label -notmatch '^[a-zA-Z0-9_-]{1,40}$'){throw 'Invalid result label.'}
$prefix=Join-Path $outputRoot ("$Label-$Module-$Mode-$Users-"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$sshKey='C:\Users\HP\.ssh\dictalab_vps_ed25519_v2'
$tunnel=$null;$load=$null;$failure=$null
$samples=[Collections.Generic.List[object]]::new()
$names=@('BASE_URL','CAPACITY_PASSWORD','USERS','SECONDS','MODE','PROFILE','RATE','ACK_PROVIDER_CALLS','K6_NO_USAGE_REPORT',
    'EVIDENCE_SUITE','PROVIDER_MODE','RECALL_TEXT','LOGIN_PAUSE','RUNTIME_MANIFEST','HOLD_SECONDS')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$began=(Get-Date).ToString('o');$runtime=$null;$verification=$null
try {
    if($Profile -eq 'smoke' -and $Module -ne 'practice-ux'){throw 'Smoke profile is only defined for practice-ux.'}
    if($Module -eq 'practice-ux'){
        if(-not $LocalSmoke -or -not $RuntimeManifest -or $AckProviderCalls){throw 'Practice UX requires a local isolated runtime manifest and no real provider calls.'}
        if(($Profile -eq 'smoke' -and ($Users -ne 1 -or $Seconds -ne 30)) -or
            ($Profile -eq 'load' -and ($Users -ne 50 -or $Seconds -lt 390 -or $Seconds -gt 2130)) -or $Profile -eq 'spike'){
            throw 'Practice UX uses 1 VU/30 seconds smoke, or 50 peak VUs with total duration 390..2130 seconds.'
        }
        $runtime=Get-Content -LiteralPath $RuntimeManifest -Raw | ConvertFrom-Json
        $env:RUNTIME_MANIFEST=(Resolve-Path -LiteralPath $RuntimeManifest).Path
        $env:HOLD_SECONDS=[string][Math]::Max(60,$Seconds-330)
    }
    if($Module -eq 'evidence'){
        if($Users % 20 -ne 0 -or $Users -gt 1000){throw 'Evidence VUs must be divisible by twenty, maximum 1000.'}
        if($AckProviderCalls){throw 'Bulk evidence never calls real AI. No real-provider budget has been approved.'}
        if(-not $RuntimeManifest){throw 'Evidence requires the isolated runtime manifest from start-capacity-vps.ps1.'}
        $runtime=Get-Content -LiteralPath $RuntimeManifest -Raw | ConvertFrom-Json
        if($runtime.Database -notmatch '^dictalab_capacity_[a-z0-9]+$' -or $runtime.ProviderMode -ne 'fake' -or
            -not $runtime.RateLimitEnabled -or -not $runtime.JwtIsolated -or -not $runtime.RedisDedicated -or
            $runtime.Accounts -lt 2*$Users -or $runtime.ArtifactSHA256 -notmatch '^[A-Fa-f0-9]{64}$'){
            throw 'Evidence runtime must have isolated DB/JWT/dedicated Redis, enabled guards, fake provider and enough distinct accounts.'
        }
        if($LocalSmoke -and ($Users -gt 20 -or $Seconds -gt 120)){throw 'Colocated local smoke is capped at twenty users and 120 seconds.'}
        if(-not $LocalSmoke -and ([DateTimeOffset]::Parse($runtime.At).AddSeconds($runtime.MaxRuntimeSeconds)-[DateTimeOffset]::Now).TotalSeconds -lt $Seconds+3930){
            throw 'Remaining server watchdog budget must cover setup, full workload and cleanup.'
        }
        if(-not $LocalSmoke){& (Join-Path $PSScriptRoot 'preflight-evidence-vps.ps1') -Label $Label}
    }
    if($Module -eq 'conversation'){throw 'Real-AI campaign is deferred until an enforced provider-call and currency budget is configured.'}
    if(Get-Process k6 -ErrorAction SilentlyContinue){throw 'Another local generator is running; no overlapping load is permitted.'}
    if(-not $LocalSmoke){
    foreach($port in @(17282,17283)){
        if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw 'Capacity tunnel port already in use.'}
    }
    $tunnel=Start-Process ssh.exe -WindowStyle Hidden -PassThru -ArgumentList @('-i',('"'+$sshKey+'"'),
        '-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
        '-o','ExitOnForwardFailure=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3','-N',
        '-L','127.0.0.1:17282:127.0.0.1:18282','-L','127.0.0.1:17283:127.0.0.1:18283','Administrator@160.191.244.27') `
        -RedirectStandardOutput ($prefix+'.ssh.stdout.log') -RedirectStandardError ($prefix+'.ssh.stderr.log')
    }
    $base=if($LocalSmoke){'http://127.0.0.1:18282'}else{'http://127.0.0.1:17282'}
    $management=if($LocalSmoke){'http://127.0.0.1:18283'}else{'http://127.0.0.1:17283'}
    $ready=$false
    for($attempt=0;$attempt -lt 40;$attempt++){
        if($tunnel -and $tunnel.HasExited){throw 'Capacity tunnel exited.'}
        try {$health=Invoke-RestMethod ($base+'/actuator/health') -TimeoutSec 3
            if($health.status -eq 'UP'){$ready=$true;break}}catch{}
        Start-Sleep -Seconds 2
    }
    if(-not $ready){throw 'Isolated capacity application did not become healthy.'}
    $env:BASE_URL=$base;$env:CAPACITY_PASSWORD='Capacity-fixture-2026-only'
    $env:USERS=[string]$Users;$env:SECONDS=[string]$Seconds;$env:MODE=$Mode;$env:PROFILE=$Profile;$env:RATE=[string]$Rate
    $env:ACK_PROVIDER_CALLS=if($AckProviderCalls){'true'}else{'false'}
    $env:K6_NO_USAGE_REPORT='true'
    $env:EVIDENCE_SUITE=if($Module -eq 'evidence'){'true'}else{'false'}
    $env:PROVIDER_MODE='fake';$env:RECALL_TEXT='Synthetic fixture recall';$env:LOGIN_PAUSE='0.6'
    $k6=(Get-Command k6.exe).Source
    $manifest=[pscustomobject]@{StartedAt=$began;Module=$Module;Mode=$Mode;Profile=$Profile;Users=$Users;Seconds=$Seconds;
        Topology=if($LocalSmoke){'HP app + DB + generator; NOT VPS capacity evidence'}else{'HP generator; VPS isolated app/DB'};
        GitCommit=(& git -C $projectRoot rev-parse HEAD);Dirty=([bool](& git -C $projectRoot status --porcelain));
        K6Version=(& $k6 version);K6SHA256=(Get-FileHash $k6).Hash;Runtime=$runtime;
        Workload=if($Module -eq 'practice-ux'){'100% dictation: recorded audio, fast word checks, receipt replay and sentence completion; not browser VUs'}else{'60/30/5/5 started journeys per complete 20-iteration block; not HTTP request ratio'};RealProviderCalls=0;
        ScriptHashes=@(Get-ChildItem (Join-Path $PSScriptRoot 'load') -File | ForEach-Object {@{Name=$_.Name;SHA256=(Get-FileHash $_.FullName).Hash}})}
    [IO.File]::WriteAllText(($prefix+'.manifest.json'),($manifest|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    $load=Start-Process -FilePath $k6 -WindowStyle Hidden -PassThru -ArgumentList @(
        'run','--quiet','--summary-trend-stats','avg,min,med,max,p(90),p(95),p(99)',
        '--summary-export',('"'+$prefix+'.json"'),'--out',('"json='+$prefix+'.raw.json.gz"'),('"'+(Join-Path $PSScriptRoot "load\$Module.js")+'"')) `
        -RedirectStandardOutput ($prefix+'.stdout.log') -RedirectStandardError ($prefix+'.stderr.log')
    $null=$load.Handle
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $highCPU=0
    while(-not $load.WaitForExit(5000) -and $watch.Elapsed.TotalSeconds -lt ($Seconds+3930)){
        if($tunnel -and $tunnel.HasExited){throw "Capacity transport exited before workload completed; inspect $prefix.ssh.stderr.log"}
        $load.Refresh()
        $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
        if($cpu.PercentProcessorTime -ge 85){$highCPU++}else{$highCPU=0}
        $samples.Add([pscustomobject]@{Time=(Get-Date).ToString('o');FreeRAMMiB=$memory.AvailableMBytes;CPUPercent=$cpu.PercentProcessorTime;LoadGeneratorRSSMiB=[math]::Round($load.WorkingSet64/1MB,2);
            LoadGeneratorCPUSeconds=$load.TotalProcessorTime.TotalSeconds})
        $freeDisk=(Get-PSDrive -Name ([IO.Path]::GetPathRoot($outputRoot).Substring(0,1))).Free
        if($memory.AvailableMBytes -lt 500 -or $highCPU -ge 6 -or $freeDisk -lt 10GB){throw 'HP generator resource guard stopped this run.'}
        try {
            $scrape=(Invoke-WebRequest -UseBasicParsing ($management+'/actuator/prometheus') -TimeoutSec 3).Content
            [IO.File]::AppendAllText(($prefix+'.prometheus.series'),("# sampled_at="+(Get-Date).ToString('o')+"`n"+$scrape+"`n"),[Text.UTF8Encoding]::new($false))
        }catch{Write-Output 'MANAGEMENT_SNAPSHOT_UNAVAILABLE'}
    }
    if(-not $load.HasExited){
        & taskkill.exe /PID $load.Id /T /F | Out-Null
        throw 'Load generator exceeded its setup/work/cleanup budget.'
    }
    if($null -eq $load.ExitCode -or $load.ExitCode -ne 0){throw "Load thresholds/checks failed: exit $($load.ExitCode)."}
    if($Module -eq 'practice-ux'){
        $summary=Get-Content -LiteralPath ($prefix+'.json') -Raw | ConvertFrom-Json
        $startedCount=$summary.metrics.practice_started.count
        $finishedCount=$summary.metrics.practice_finished.count
        $completedCount=$summary.metrics.practice_sentences_completed.count
        if(-not $startedCount -or $startedCount -ne $finishedCount -or $finishedCount -ne $completedCount){
            throw 'Practice UX journey accounting failed: missing, failed or interrupted journeys.'
        }
    }
    if($Module -eq 'evidence'){
        $verification=(& node (Join-Path $PSScriptRoot 'load\verify-evidence.cjs') ($prefix+'.raw.json.gz') $Users $Seconds) -join "`n"
        [IO.File]::WriteAllText(($prefix+'.accounting.json'),$verification,[Text.UTF8Encoding]::new($false))
        if($LASTEXITCODE -ne 0){throw 'Raw evidence failed full-window/VU/interrupted-journey verification.'}
    }
    Write-Output "CAPACITY_CHECK_PASS=$prefix"
}catch{$failure=$_.Exception.Message;Write-Output "CAPACITY_CHECK_FAIL=$failure"}
finally {
    if($load -and -not $load.HasExited){Stop-Process -Id $load.Id}
    if($tunnel -and -not $tunnel.HasExited){Stop-Process -Id $tunnel.Id}
    if($Module -eq 'evidence' -and -not $verification -and (Test-Path -LiteralPath ($prefix+'.raw.json.gz'))){
        $verification=(& node (Join-Path $PSScriptRoot 'load\verify-evidence.cjs') ($prefix+'.raw.json.gz') $Users $Seconds) -join "`n"
        [IO.File]::WriteAllText(($prefix+'.accounting.json'),$verification,[Text.UTF8Encoding]::new($false))
    }
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    $samples | Export-Csv -LiteralPath ($prefix+'.generator.csv') -NoTypeInformation -Encoding UTF8
    [IO.File]::WriteAllText(($prefix+'.result.json'),([pscustomobject]@{At=(Get-Date).ToString('o');
        Module=$Module;Mode=$Mode;Profile=$Profile;Users=$Users;Seconds=$Seconds;Passed=($null -eq $failure);
        Failure=$failure;Summary=$prefix+'.json';Raw=$prefix+'.raw.json.gz';StartedAt=$began;
        VerdictScope='Generator thresholds/accounting only; DB invariants and server guard artifacts require separate verification'}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
if($failure){exit 1}
