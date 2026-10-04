param(
    [ValidateSet('H2','Postgres17','Postgres18','JavaScript')][string]$Suite='H2',
    [ValidateRange(60,1800)][int]$TimeoutSeconds=600
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$outputRoot=Join-Path $projectRoot 'target\readiness'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$buildRoot=Join-Path $outputRoot ("$Suite-$stamp-build")
$command=switch($Suite){
    'H2' { "& mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=$buildRoot' package; exit `$LASTEXITCODE" }
    'Postgres17' { "& .\scripts\test-vps-postgres.ps1 -TimeoutSeconds $([Math]::Max(30,$TimeoutSeconds-90)) -MavenArgs @('test','-Ddictalab.build.directory=$buildRoot'); if(`$LASTEXITCODE -ne 0){exit 1}" }
    'Postgres18' { "& .\scripts\test-postgres.ps1 -MavenArgs @('test','-Ddictalab.build.directory=$buildRoot'); if(`$LASTEXITCODE -ne 0){exit 1}" }
    'JavaScript' { '& node --test src/test/js/*.test.cjs; exit $LASTEXITCODE' }
}
$script="`$ErrorActionPreference='Stop'; Set-Location -LiteralPath '$($projectRoot.Replace("'","''"))'; $command"
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
$stdout=Join-Path $outputRoot "$Suite-$stamp.stdout.log"
$stderr=Join-Path $outputRoot "$Suite-$stamp.stderr.log"
$process=$null
$failure=$null
try{
    $process=Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -PassThru `
        -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded) `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    # Keep the process handle open: Windows PowerShell can otherwise return a null ExitCode after WaitForExit.
    $null=$process.Handle
    if(-not $process.WaitForExit($TimeoutSeconds*1000)){
        # Only the process tree started for this exact check is terminated.
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        throw "Check exceeded its $TimeoutSeconds second budget."
    }
    if($null -eq $process.ExitCode){throw 'Process exit code could not be read; inspect the retained logs.'}
    if($process.ExitCode -ne 0){throw "Check exited with code $($process.ExitCode)."}
    Write-Output "READINESS_PASS=$Suite"
}catch{
    $failure=$_.Exception.Message
    Write-Output "READINESS_FAIL=$Suite : $failure"
}finally{
    [pscustomobject]@{Suite=$Suite;At=(Get-Date).ToString('o');Passed=($null -eq $failure);Failure=$failure;
        Stdout=$stdout;Stderr=$stderr;BuildDirectory=$buildRoot;TimeoutSeconds=$TimeoutSeconds} |
        ConvertTo-Json | Out-File -LiteralPath (Join-Path $outputRoot 'last-check.json') -Encoding utf8
    Write-Output "READINESS_STDOUT=$stdout"
    Write-Output "READINESS_STDERR=$stderr"
    # Test failures are repaired and rechecked; HP shutdown belongs only to the completed workflow.
}
if($failure){exit 1}
