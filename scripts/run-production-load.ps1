param([ValidateRange(1,300)][int]$Users=10,[ValidateRange(10,1800)][int]$Seconds=120,
      [ValidateSet('mixed','dictation-audio')][string]$Workload='mixed',
      [ValidatePattern('^[a-zA-Z0-9_-]{1,30}$')][string]$Label='mixed',[switch]$Learning)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class DictaLabLoadPower {
    [StructLayout(LayoutKind.Sequential)] public struct Status {
        public byte AC, Flag, Percent, Reserved;
        public uint Lifetime, FullLifetime;
    }
    [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out Status status);
    [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);
}
'@
$power=[DictaLabLoadPower+Status]::new()
if(-not [DictaLabLoadPower]::GetSystemPowerStatus([ref]$power)){throw 'Load generator power status unavailable'}
if($Seconds -ge 600 -and $power.AC -ne 1){throw 'Long production stage requires AC power; critical battery protection stays enabled'}
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root 'target/production-load-20261011'
New-Item -ItemType Directory -Force $output | Out-Null
$prefix=Join-Path $output "$Label-$Users"
if(Test-Path ($prefix+'.manifest.json')){throw 'Existing stage evidence must not be overwritten'}
$projectPattern='*'+$root.Replace('\','/')+'/*'
$sameProjectGenerator=Get-CimInstance Win32_Process -Filter "Name='k6.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.Replace('\','/') -like $projectPattern
}
if($sameProjectGenerator){throw 'A DictaLab generator is already running'}
$base='https://184.194.89.90'
$statePath=Join-Path $output 'private-fixture.json'
function PrivateFile([string]$Path,[string]$Content) {
    [IO.File]::WriteAllText($Path,$Content,[Text.UTF8Encoding]::new($false))
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $null=& icacls.exe $Path /inheritance:r /grant:r "${identity}:(F)" 'SYSTEM:(F)'
    if($LASTEXITCODE -ne 0){throw 'Private evidence ACL failed'}
}
if(Test-Path $statePath){$state=Get-Content $statePath -Raw | ConvertFrom-Json -AsHashtable}
else {
    $state=@{prefix='prod-k6-'+[guid]::NewGuid().ToString('N');password='Probe9'+[guid]::NewGuid().ToString('N');accounts=@()}
    PrivateFile $statePath ($state | ConvertTo-Json -Depth 8)
}
Add-Type -AssemblyName System.Net.Http
$handler=[Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect=$false
$client=[Net.Http.HttpClient]::new($handler)
$client.Timeout=[TimeSpan]::FromSeconds(30)
function Request([string]$Path,[string]$Method='GET',$Body=$null) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$base+$Path)
    try {
        if($Method -ne 'GET') {
            $csrf=Request '/api/auth/csrf'
            $null=$request.Headers.TryAddWithoutValidation($csrf.headerName,$csrf.token)
        }
        if($null -ne $Body){$request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')}
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if(-not $response.IsSuccessStatusCode){throw "Probe setup failed: $Method $Path HTTP $([int]$response.StatusCode)"}
            $text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if($text){return $text | ConvertFrom-Json}
        } finally {$response.Dispose()}
    } finally {$request.Dispose()}
}
$tokens=@()
$load=$null;$monitor=$null
try {
    if([DictaLabLoadPower]::SetThreadExecutionState([uint32]2147483649) -eq 0){throw 'Load generator keep-awake request failed'}
    while($state.accounts.Count -lt $Users) {
        $email=$state.prefix+'-'+($state.accounts.Count+1)+'@loadtest.local'
        $account=Request '/api/auth/register' 'POST' @{email=$email;password=$state.password;displayName='Production k6 probe'}
        $state.accounts+=@{email=$email;id=$account.id}
        PrivateFile $statePath ($state | ConvertTo-Json -Depth 8)
        $null=Request '/api/auth/logout' 'POST'
        Start-Sleep -Milliseconds 650
    }
    foreach($account in @($state.accounts | Select-Object -First $Users)) {
        foreach($cookie in $handler.CookieContainer.GetCookies([uri]($base+'/api/auth'))){$cookie.Expired=$true}
        $auth=Request '/api/auth/login' 'POST' @{email=$account.email;password=$state.password}
        if($auth.id -ne $account.id){throw 'Distinct probe identity mismatch'}
        $csrf=Request '/api/auth/csrf'
        $cookies=@{}
        foreach($cookie in $handler.CookieContainer.GetCookies([uri]($base+'/api/auth'))){$cookies[$cookie.Name]=@($cookie.Value)}
        $tokens+=@{id=$auth.id;csrf=$csrf;issuedAt=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();cookies=$cookies}
        Start-Sleep -Milliseconds 650
    }
    $authPath=$prefix+'.private-auth.json'
    PrivateFile $authPath (ConvertTo-Json -InputObject @($tokens) -Depth 8)
    $env:BASE_URL=$base;$env:USERS=[string]$Users;$env:SECONDS=[string]$Seconds
    $env:AUTH_FILE=$authPath;$env:CAPACITY_PASSWORD=$state.password
    $env:THINK_SECONDS='2';$env:PLAYBACK_SECONDS='3';$env:MODE='mixed'
    $env:PRODUCTION_WORKLOAD=$Workload
    $env:TEST_LEARNING=([bool]$Learning).ToString().ToLowerInvariant()
    $env:K6_NO_USAGE_REPORT='true';$env:LOGIN_PAUSE='0.65';$env:INSECURE_TLS='false'
    $k6=(Get-Command k6.exe).Source
    $manifest=@{at=[DateTimeOffset]::UtcNow.ToString('o');users=$Users;seconds=$Seconds;
        generator='HP Windows, separate from VPS; public HTTPS direct';baseUrl=$base;
        workload='80% reader VUs / 20% dictation VUs; 2s think, 3s playback twice';
        k6Version=(& $k6 version);scriptSHA256=(Get-FileHash "$PSScriptRoot/load/production.js").Hash;
        sharedSHA256=(Get-FileHash "$PSScriptRoot/load/shared.js").Hash;coreSHA256=(Get-FileHash "$PSScriptRoot/load/core.js").Hash;
        syntheticAccountsCreated=$state.accounts.Count;realProviderCalls=0;tlsValidation=$true}
    $manifest.workloadKind=$Workload
    $manifest.learningVerification=[bool]$Learning
    if($Workload -eq 'dictation-audio'){
        $manifest.workload='100% dictation VUs; full recorded audio GET, 8-file per-VU LRU, actual segment duration; 2s think; one sentence per journey'
        $manifest.audioScriptSHA256=(Get-FileHash "$PSScriptRoot/load/dictation-audio.js").Hash
        $manifest.businessRoutes=@('catalog','history','start','resume','play','play-complete','check','hint','replay-start','replay-complete','complete')
        $manifest.audioReadyP95BudgetMs=3000;$manifest.audioReadyP99BudgetMs=5000
        if($Learning){$manifest.businessRoutes+=@('learning-bank','review-start','review-answer','review-recovery')}
    }
    $runtime=& ssh.exe -n -i C:/Users/HP/.ssh/LightsailDefaultKey-us-east-1.pem -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 ubuntu@184.194.89.90 `
        "sudo -n sha256sum /opt/dictalab/app/dictalab.jar; curl -fsS --max-time 10 http://127.0.0.1:18183/actuator/prometheus | grep '^hikaricp_connections_max'"
    if($LASTEXITCODE -ne 0){throw 'Runtime provenance check failed'}
    $hashLine=@($runtime | Where-Object {$_ -match '^[a-f0-9]{64} '})
    $poolLine=@($runtime | Where-Object {$_ -match '^hikaricp_connections_max\{.*\}\s+([\d.]+)$'})
    if($hashLine.Count -ne 1 -or $poolLine.Count -ne 1){throw 'Ambiguous runtime provenance'}
    $manifest.artifactSHA256=($hashLine[0] -split ' ')[0]
    $manifest.poolSize=[int]([double](($poolLine[0] -split '\s+')[-1]))
    [IO.File]::WriteAllText(($prefix+'.manifest.json'),($manifest | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    $monitor=Start-Process ssh.exe -WindowStyle Hidden -PassThru -ArgumentList @('-n','-i','C:/Users/HP/.ssh/LightsailDefaultKey-us-east-1.pem',
        '-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','ubuntu@184.194.89.90',
        "sudo -n python3 /home/ubuntu/dictalab-migration/sample-production.py --seconds $($Seconds+55)") `
        -RedirectStandardOutput ($prefix+'.server.jsonl') -RedirectStandardError ($prefix+'.monitor.stderr.log')
    $load=Start-Process $k6 -WindowStyle Hidden -PassThru -ArgumentList @('run','--quiet',
        '--summary-trend-stats','avg,min,med,max,p(90),p(95),p(99)',
        '--summary-export',('"'+$prefix+'.summary.json"'),'--out',('"json='+$prefix+'.raw.json.gz"'),
        ('"'+$PSScriptRoot+'/load/production.js"')) `
        -RedirectStandardOutput ($prefix+'.stdout.log') -RedirectStandardError ($prefix+'.stderr.log')
    # Network failures may include signed redirect URLs; keep raw stderr private.
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $null=& icacls.exe ($prefix+'.stderr.log') /inheritance:r /grant:r "${identity}:(F)" 'SYSTEM:(F)'
    if($LASTEXITCODE -ne 0){throw 'Private diagnostic ACL failed'}
    $null=$load.Handle
    $watch=[Diagnostics.Stopwatch]::StartNew();$samples=@()
    while(-not $load.WaitForExit(5000)) {
        $load.Refresh()
        $cpu=Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"
        $memory=Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory
        $samples+=@{at=[DateTimeOffset]::UtcNow.ToString('o');cpuPercent=$cpu.PercentProcessorTime;
            availableMiB=$memory.AvailableMBytes;k6RSSMiB=$load.WorkingSet64/1MB;k6CPUSeconds=$load.TotalProcessorTime.TotalSeconds}
        if($watch.Elapsed.TotalSeconds -gt $Seconds+$Users*3+120){throw 'Bounded stage runtime exceeded; do not repeat uncertain writes'}
    }
    $load.WaitForExit()
    [IO.File]::WriteAllText(($prefix+'.generator.json'),($samples | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    $exitCode=$load.ExitCode
    'K6_STAGE_EXIT='+$exitCode
    'K6_STAGE_EVIDENCE='+$prefix
    if(-not $monitor.WaitForExit(60000)){throw 'Resource sampler did not finish within its bound'}
    if($monitor.ExitCode -ne 0){throw 'Resource sampler failed; inspect retained stderr'}
    [IO.File]::WriteAllText(($prefix+'.result.json'),(@{exitCode=$exitCode;endedAt=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
} finally {
    $null=[DictaLabLoadPower]::SetThreadExecutionState([uint32]2147483648)
    if($load -and -not $load.HasExited){Stop-Process -Id $load.Id}
    if($monitor -and -not $monitor.HasExited){Stop-Process -Id $monitor.Id}
    $client.Dispose();$handler.Dispose()
    Remove-Item Env:CAPACITY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PRODUCTION_WORKLOAD -ErrorAction SilentlyContinue
    Remove-Item Env:TEST_LEARNING -ErrorAction SilentlyContinue
}
