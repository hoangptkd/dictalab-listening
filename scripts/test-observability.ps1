param([Parameter(Mandatory=$true)][string]$GrafanaHome,
    [string]$PrometheusHome='target/tools/prometheus-3.15.0.windows-amd64')
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$project=Split-Path -Parent $PSScriptRoot
$GrafanaHome=(Resolve-Path -LiteralPath $GrafanaHome).Path
$PrometheusHome=(Resolve-Path -LiteralPath $PrometheusHome).Path
$output=Join-Path $project ('target/readiness/observability-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$processes=@()
try {
    foreach($port in @(19083,19090,19091)){
        if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Test port $port is occupied."}
    }
    $tunnel=Start-Process ssh.exe -WindowStyle Hidden -PassThru -ArgumentList @('-i','C:\Users\HP\.ssh\dictalab_vps_ed25519_v2',
        '-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3',
        '-N','-L','127.0.0.1:19083:127.0.0.1:18183','Administrator@160.191.244.27')
    $null=$tunnel.Handle;$processes+=$tunnel
    $ops=Join-Path $project 'ops/observability'
    $config=(Get-Content (Join-Path $ops 'prometheus.yml') -Raw -Encoding UTF8).Replace('127.0.0.1:18183','127.0.0.1:19083')
    $config=$config.Replace('- alerts.yml',("- '"+(Join-Path $ops 'alerts.yml').Replace('\','/')+"'"))
    $configPath=Join-Path $output 'prometheus.yml'
    [IO.File]::WriteAllText($configPath,$config,[Text.UTF8Encoding]::new($false))
    $prom=Start-Process (Join-Path $PrometheusHome 'prometheus.exe') -WindowStyle Hidden -PassThru -ArgumentList @(
        '--web.listen-address=127.0.0.1:19090',('--config.file="'+$configPath+'"'),('--storage.tsdb.path="'+(Join-Path $output 'prometheus-data')+'"'),
        '--storage.tsdb.retention.time=1d','--storage.tsdb.retention.size=64MB') -RedirectStandardOutput (Join-Path $output 'prometheus.stdout.log') -RedirectStandardError (Join-Path $output 'prometheus.stderr.log')
    $null=$prom.Handle;$processes+=$prom
    $env:GF_SERVER_HTTP_ADDR='127.0.0.1';$env:GF_SERVER_HTTP_PORT='19091'
    $env:GF_PATHS_DATA=Join-Path $output 'grafana-data';$env:GF_PATHS_LOGS=Join-Path $output 'grafana-logs'
    New-Item -ItemType Directory -Force -Path $env:GF_PATHS_DATA,$env:GF_PATHS_LOGS | Out-Null
    $env:GF_PATHS_PROVISIONING=Join-Path $ops 'grafana/provisioning'
    $env:DICTALAB_GRAFANA_DASHBOARDS=Join-Path $ops 'grafana/dashboards'
    $env:GF_SECURITY_ADMIN_PASSWORD=[guid]::NewGuid().ToString('N')
    $env:GF_ANALYTICS_REPORTING_ENABLED='false';$env:GF_ANALYTICS_CHECK_FOR_UPDATES='false'
    $env:GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES='false';$env:GF_PLUGINS_PREINSTALL_DISABLED='true'
    $env:GF_AUTH_ANONYMOUS_ENABLED='false';$env:GF_USERS_ALLOW_SIGN_UP='false'
    $headers=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:'+$env:GF_SECURITY_ADMIN_PASSWORD)))}
    $grafana=Start-Process (Join-Path $GrafanaHome 'bin/grafana.exe') -ArgumentList 'server' -WindowStyle Hidden -PassThru -WorkingDirectory $GrafanaHome `
        -RedirectStandardOutput (Join-Path $output 'grafana.stdout.log') -RedirectStandardError (Join-Path $output 'grafana.stderr.log')
    $null=$grafana.Handle;$processes+=$grafana
    $deadline=(Get-Date).AddSeconds(120);$ready=$false
    do {
        if($tunnel.HasExited -or $prom.HasExited -or $grafana.HasExited){throw 'An owned observability test process exited before readiness.'}
        try {
            $health=Invoke-RestMethod 'http://127.0.0.1:19091/api/health' -TimeoutSec 3
            $up=Invoke-RestMethod ('http://127.0.0.1:19090/api/v1/query?query='+[Uri]::EscapeDataString('up{job="dictalab"}')) -TimeoutSec 3
            $ready=$health.database -eq 'ok' -and $up.data.result.Count -eq 1 -and $up.data.result[0].value[1] -eq '1'
        }catch{}
        if(-not $ready){Start-Sleep -Seconds 2}
    }while(-not $ready -and (Get-Date) -lt $deadline)
    if(-not $ready){throw 'Grafana or private production scrape did not become ready within 120 seconds.'}
    # Two additional scrapes provide rate samples. No permanent daemon or public port is installed.
    Start-Sleep -Seconds 35
    $dashboard=Invoke-RestMethod 'http://127.0.0.1:19091/api/dashboards/uid/dictalab-readiness' -Headers $headers -TimeoutSec 5
    if($dashboard.dashboard.panels.Count -ne 12){throw 'Dashboard provisioning did not import all twelve panels.'}
    $datasource=Invoke-RestMethod 'http://127.0.0.1:19091/api/datasources/uid/dictalab-prometheus/health' -Headers $headers -TimeoutSec 10
    if($datasource.status -ne 'OK'){throw 'Provisioned Prometheus datasource is unhealthy.'}
    $results=@()
    foreach($panel in $dashboard.dashboard.panels){
        foreach($query in $panel.targets){
            $result=Invoke-RestMethod ('http://127.0.0.1:19090/api/v1/query?query='+[Uri]::EscapeDataString($query.expr)) -TimeoutSec 5
            if($result.status -ne 'success'){throw "Panel $($panel.id) query failed."}
            if($panel.id -in @(4,6,7,8,9,11,12) -and $result.data.result.Count -eq 0){throw "Panel $($panel.id) has no required gauge series."}
            $results+=@{Panel=$panel.id;Ref=$query.refId;Series=$result.data.result.Count}
        }
    }
    [IO.File]::WriteAllText((Join-Path $output 'result.json'),(@{At=(Get-Date).ToString('o');Grafana=$health.version;
        DashboardPanels=$dashboard.dashboard.panels.Count;Datasource=$datasource.status;Queries=$results}|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    "OBSERVABILITY_TEST_PASS=$output"
}finally{
    foreach($process in $processes){if(-not $process.HasExited){Stop-Process -Id $process.Id}}
    foreach($name in @('GF_SERVER_HTTP_ADDR','GF_SERVER_HTTP_PORT','GF_PATHS_DATA','GF_PATHS_LOGS','GF_PATHS_PROVISIONING',
        'DICTALAB_GRAFANA_DASHBOARDS','GF_SECURITY_ADMIN_PASSWORD','GF_ANALYTICS_REPORTING_ENABLED','GF_ANALYTICS_CHECK_FOR_UPDATES',
        'GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES','GF_PLUGINS_PREINSTALL_DISABLED','GF_AUTH_ANONYMOUS_ENABLED','GF_USERS_ALLOW_SIGN_UP')){
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    "OBSERVABILITY_TEST_LOGS=$output"
}
