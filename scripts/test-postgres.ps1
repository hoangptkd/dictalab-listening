param(
    [string]$PostgresBin = "",
    [ValidateRange(1024, 65535)][int]$Port = 15434,
    [string[]]$MavenArgs = @("test")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PostgresBin) {
    $PostgresBin = Split-Path -Parent (Get-Command pg_ctl -ErrorAction Stop).Source
}
$testRoot = Join-Path $projectRoot ("target/postgres-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$cluster = Join-Path $testRoot "cluster"
$started = $false
try {
    & (Join-Path $PostgresBin "initdb.exe") -D $cluster -U dictalab_test --auth=trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw "Could not initialize the isolated test cluster." }
    $startProcess = Start-Process -FilePath (Join-Path $PostgresBin "pg_ctl.exe") -WindowStyle Hidden -PassThru `
        -ArgumentList @('start', '-D', ('"' + $cluster + '"'), '-l', ('"' + (Join-Path $testRoot 'postgres.log') + '"'), '-w', '-o', ('"-h 127.0.0.1 -p ' + $Port + '"')) `
        -RedirectStandardOutput (Join-Path $testRoot 'start.out.log') -RedirectStandardError (Join-Path $testRoot 'start.err.log')
    $null=$startProcess.Handle
    if(-not $startProcess.WaitForExit(60000)){throw 'Test PostgreSQL startup exceeded 60 seconds.'}
    if ($startProcess.ExitCode -ne 0) { throw "Could not start test PostgreSQL on port $Port. See $testRoot" }
    $started = $true
    & (Join-Path $PostgresBin "createdb.exe") -h 127.0.0.1 -p $Port -U dictalab_test dictalab_test
    if ($LASTEXITCODE -ne 0) { throw "Could not create the isolated test database." }
    Push-Location $projectRoot
    try {
        & mvn --batch-mode --no-transfer-progress '-Dspring.profiles.active=postgres-test' `
            "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$Port/dictalab_test" `
            '-Dspring.datasource.username=dictalab_test' '-Dspring.datasource.password=' @MavenArgs
        if ($LASTEXITCODE -ne 0) { throw "PostgreSQL test run failed." }
    } finally { Pop-Location }
} finally {
    if ($started) {
        $stopProcess = Start-Process -FilePath (Join-Path $PostgresBin "pg_ctl.exe") -WindowStyle Hidden -PassThru `
            -ArgumentList @('stop', '-D', ('"' + $cluster + '"'), '-m', 'fast', '-w')
        $null=$stopProcess.Handle
        if(-not $stopProcess.WaitForExit(60000)){Write-Warning "Test PostgreSQL stop exceeded 60 seconds: $cluster"}
        if ($stopProcess.ExitCode -ne 0) { Write-Warning "Test PostgreSQL did not stop; cluster: $cluster" }
    }
    Write-Output "Test database and logs retained at $testRoot"
}
