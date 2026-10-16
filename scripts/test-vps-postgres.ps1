param([string]$VpsHost='160.191.244.27',[string]$VpsUser='Administrator',
    [string]$SshKey='C:\Users\HP\.ssh\dictalab_vps_ed25519_v2',[int]$LocalPort=0,
    [string[]]$MavenArgs=@('test'),[ValidateRange(30,1800)][int]$TimeoutSeconds=510)
$ErrorActionPreference='Stop'
$sshArgs=@('-i',$SshKey,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=15')
$database='dictalab_test_'+[guid]::NewGuid().ToString('N')
if($LocalPort -eq 0){
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try {$listener.Start();$LocalPort=$listener.LocalEndpoint.Port} finally {$listener.Stop()}
}
function Invoke-Remote([string]$Script) {
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    $result=& ssh @sshArgs "$VpsUser@$VpsHost" "powershell -NoProfile -EncodedCommand $encoded"
    if($LASTEXITCODE -ne 0){throw 'Remote PostgreSQL test operation failed.'}
    return $result
}
$create=@'
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$s=Import-Clixml -LiteralPath "$env:ProgramData\DictaLab\database-secrets.xml"
$env:PGPASSWORD=$s.PostgresPassword
try {& 'C:\DictaLab\postgresql-17\bin\createdb.exe' -h 127.0.0.1 -U postgres -O dictalab_app __DB__; if($LASTEXITCODE -ne 0){throw 'Create failed'}}
finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
$s.AppPassword
'@
$drop=@'
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$env:PGPASSWORD=(Import-Clixml -LiteralPath "$env:ProgramData\DictaLab\database-secrets.xml").PostgresPassword
try {& 'C:\DictaLab\postgresql-17\bin\dropdb.exe' -h 127.0.0.1 -U postgres __DB__; if($LASTEXITCODE -ne 0){throw 'Drop failed'}}
finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
'@
$tunnel=$null; $created=$false;$maven=$null
try {
    if(Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue){throw 'Test tunnel port is already in use.'}
    $appPassword=Invoke-Remote ($create.Replace('__DB__',$database)); $created=$true
    Write-Output "ISOLATED_TEST_DATABASE=$database"
    $tunnelArgs=@('-i',('"'+$SshKey+'"'),'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
        '-o','ExitOnForwardFailure=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3',
        '-N','-L',"127.0.0.1:${LocalPort}:127.0.0.1:5432","$VpsUser@$VpsHost")
    $tunnel=Start-Process -FilePath ssh.exe -ArgumentList $tunnelArgs -WindowStyle Hidden -PassThru
    $ready=$false
    for($attempt=0;$attempt -lt 60;$attempt++){
        if(Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue){$ready=$true;break}
        if($tunnel.HasExited){throw 'SSH test tunnel failed.'}
        Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw 'SSH test tunnel was not ready within 30 seconds; no Maven tests were started.'}
    $env:TEST_DB_URL="jdbc:postgresql://127.0.0.1:$LocalPort/$database"
    $env:TEST_DB_USERNAME='dictalab_app'; $env:TEST_DB_PASSWORD=($appPassword -join '').Trim()
    $projectRoot=Split-Path -Parent $PSScriptRoot
    $logRoot=Join-Path $projectRoot 'target\readiness'
    New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    $stdout=Join-Path $logRoot ($database+'.maven.stdout.log')
    $stderr=Join-Path $logRoot ($database+'.maven.stderr.log')
    $quotedArgs=($MavenArgs | ForEach-Object {"'"+$_.Replace("'","''")+"'"}) -join ','
    $command="& mvn --batch-mode --no-transfer-progress '-Dspring.profiles.active=postgres-test' @($quotedArgs);exit `$LASTEXITCODE"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $maven=Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -PassThru -WorkingDirectory $projectRoot `
        -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded) -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null=$maven.Handle
    if(-not $maven.WaitForExit($TimeoutSeconds*1000)){
        & taskkill.exe /PID $maven.Id /T /F | Out-Null
        throw 'PostgreSQL Maven check exceeded its bounded budget; cleanup follows.'
    }
    Get-Content -LiteralPath $stdout
    Get-Content -LiteralPath $stderr
    if($null -eq $maven.ExitCode -or $maven.ExitCode -ne 0){throw 'Integration suite on VPS PostgreSQL failed.'}
} finally {
    if($maven -and -not $maven.HasExited){& taskkill.exe /PID $maven.Id /T /F | Out-Null}
    Remove-Item Env:TEST_DB_URL,Env:TEST_DB_USERNAME,Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    if($tunnel -and -not $tunnel.HasExited){Stop-Process -Id $tunnel.Id}
    if($created -and $database -match '^dictalab_test_[a-f0-9]{32}$'){
        Invoke-Remote ($drop.Replace('__DB__',$database)) | Out-Null
    }
}
