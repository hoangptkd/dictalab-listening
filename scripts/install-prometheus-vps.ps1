param(
    [Parameter(Mandatory)][string]$ExpectedSHA256,
    [string]$Root='C:\DictaLab\monitoring',
    [ValidateRange(1024,65535)][int]$Port=19190
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Root -ne 'C:\DictaLab\monitoring'){throw 'Only the dedicated DictaLab monitoring directory is supported.'}
if(Get-Service DictaLabPrometheus -ErrorAction SilentlyContinue){throw 'Service exists; inspect it rather than overwrite configuration.'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw 'Monitoring port is occupied.'}
$binary=Join-Path $Root 'prometheus.exe'
if($ExpectedSHA256 -notmatch '^[A-Fa-f0-9]{64}$' -or (Get-FileHash -LiteralPath $binary).Hash -ne $ExpectedSHA256){throw 'Prometheus checksum mismatch.'}
foreach($name in @('prometheus.yml','alerts.yml')){
    if(-not(Test-Path -LiteralPath (Join-Path $Root $name) -PathType Leaf)){throw 'Monitoring configuration is missing.'}
}
if((Invoke-RestMethod http://127.0.0.1:18082/actuator/health -TimeoutSec 3).status -ne 'UP'){throw 'DictaLab is not healthy.'}
$nssm=(Get-Command nssm.exe -ErrorAction Stop).Source
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'data'),(Join-Path $Root 'logs') | Out-Null
# The service reads only this configuration and writes only its dedicated data/log tree.
& icacls.exe $Root /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Cannot grant LocalService monitoring-directory access.'}
$installed=$false
function Set-ServiceOption([string]$Name,[string[]]$Values){
    & $nssm set DictaLabPrometheus $Name @Values | Out-Null
    if($LASTEXITCODE -ne 0){throw "Service configuration failed: $Name"}
}
try{
    & $nssm install DictaLabPrometheus $binary | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Service installation failed.'}
    $installed=$true
    Set-ServiceOption AppDirectory @($Root)
    Set-ServiceOption AppParameters @("--config.file=C:\DictaLab\monitoring\prometheus.yml --storage.tsdb.path=C:\DictaLab\monitoring\data --web.listen-address=127.0.0.1:$Port --storage.tsdb.retention.time=3d --storage.tsdb.retention.size=256MB")
    Set-ServiceOption ObjectName @('NT AUTHORITY\LocalService')
    Set-ServiceOption Start @('SERVICE_AUTO_START')
    Set-ServiceOption AppEnvironmentExtra @('GOMEMLIMIT=256MiB')
    Set-ServiceOption AppStdout @((Join-Path $Root 'logs\prometheus.stdout.log'))
    Set-ServiceOption AppStderr @((Join-Path $Root 'logs\prometheus.stderr.log'))
    Set-ServiceOption AppRotateFiles @('1')
    Set-ServiceOption AppRotateOnline @('1')
    Set-ServiceOption AppRotateBytes @('10485760')
    Set-ServiceOption AppRestartDelay @('5000')
    Start-Service DictaLabPrometheus
    $deadline=(Get-Date).AddSeconds(60);$ready=$false
    do{
        try{
            $result=Invoke-RestMethod ("http://127.0.0.1:$Port/api/v1/query?query="+[Uri]::EscapeDataString('up{job="dictalab"}')) -TimeoutSec 3
            $ready=$result.data.result.Count -eq 1 -and $result.data.result[0].value[1] -eq '1'
        }catch{}
        if(-not $ready){Start-Sleep -Seconds 2}
    }while(-not $ready -and (Get-Date) -lt $deadline)
    if(-not $ready){throw 'Private scrape did not become ready within 60 seconds.'}
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $Port)
    if(@($listeners|Where-Object LocalAddress -ne '127.0.0.1').Count){throw 'Monitoring must bind only loopback.'}
    $rules=Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/rules" -TimeoutSec 3
    $count=(@($rules.data.groups|ForEach-Object {$_.rules})).Count
    if($count -ne 7){throw 'Expected seven operational alert rules.'}
    [pscustomobject]@{At=(Get-Date).ToString('o');Service='DictaLabPrometheus';Up=1;AlertRules=$count;
        Listen="127.0.0.1:$Port";BinarySHA256=$ExpectedSHA256;Receiver='DEFERRED';GrafanaService='NOT_INSTALLED'} |
        ConvertTo-Json | Out-File -LiteralPath (Join-Path $Root 'installation-result.json') -Encoding utf8
    'MONITORING_PASS=private scrape up; seven rules loaded; receiver still deferred'
}catch{
    if($installed){
        Stop-Service DictaLabPrometheus -ErrorAction SilentlyContinue
        & $nssm remove DictaLabPrometheus confirm | Out-Null
    }
    throw
}
