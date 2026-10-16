param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'compose.messaging-test.yaml'
$testProject = 'dictalab-messaging-test-' + [guid]::NewGuid().ToString('N')
$testOutput = 'target/messaging-test-' + [guid]::NewGuid().ToString('N')
$names = @('MESSAGING_TEST_PASSWORD','DICTALAB_MESSAGING_TEST','TEST_REDIS_PORT','TEST_RABBITMQ_PORT',
    'SPRING_DATASOURCE_PASSWORD','SPRING_RABBITMQ_USERNAME','SPRING_RABBITMQ_PASSWORD')
$prior = @{}
foreach ($name in $names) { $prior[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location $projectRoot
try {
    $env:MESSAGING_TEST_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:DICTALAB_MESSAGING_TEST = 'true'
    $env:SPRING_DATASOURCE_PASSWORD = $env:MESSAGING_TEST_PASSWORD
    $env:SPRING_RABBITMQ_USERNAME = 'dictalab_test'
    $env:SPRING_RABBITMQ_PASSWORD = $env:MESSAGING_TEST_PASSWORD
    docker compose -p $testProject -f $composeFile up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Test infrastructure failed to start.' }
    $postgresAddress = docker compose -p $testProject -f $composeFile port postgres 5432
    $redisAddress = docker compose -p $testProject -f $composeFile port redis 6379
    $rabbitAddress = docker compose -p $testProject -f $composeFile port rabbitmq 5672
    foreach ($address in @($postgresAddress,$redisAddress,$rabbitAddress)) {
        if ($address -notmatch '^127\.0\.0\.1:\d+$') { throw 'Unexpected test service binding.' }
    }
    $postgresPort = $postgresAddress.Split(':')[-1]
    $env:TEST_REDIS_PORT = $redisAddress.Split(':')[-1]
    $env:TEST_RABBITMQ_PORT = $rabbitAddress.Split(':')[-1]
    & mvn --batch-mode --no-transfer-progress "-Ddictalab.build.directory=$testOutput" `
        '-Dspring.profiles.active=postgres-test' `
        "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$postgresPort/dictalab_messaging_test" `
        '-Dspring.datasource.username=dictalab_test' `
        '-Dtest=RequestRateLimitFilterTest,RedisRateLimitIntegrationTest,JwtRedisIntegrationTest,ListeningGradingQueueIntegrationTest,AiBackgroundJobsIntegrationTest,AiRabbitQueueIntegrationTest,ListeningComprehensionIntegrationTest,ProductionReliabilityTest' test
    if ($LASTEXITCODE -ne 0) { throw "Messaging tests failed; reports: $testOutput/surefire-reports" }
} finally {
    # Only this randomly named test project is removed, including its disposable DB volumes.
    if ($testProject -notmatch '^dictalab-messaging-test-[a-f0-9]{32}$') { throw 'Invalid cleanup target.' }
    docker compose -p $testProject -f $composeFile down -v --remove-orphans
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$prior[$name]) }
    Pop-Location
    Write-Output "Reports retained at $testOutput/surefire-reports"
}
