param(
    [Parameter(Mandatory)][ValidateSet('Verify','Model','Api','Worker')][string]$Component,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'

if (Test-Path -LiteralPath $envPath) {
    Get-Content -LiteralPath $envPath | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $parts = $line.Split('=', 2)
            if ($parts.Count -eq 2) {
                $value = $parts[1].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or
                        ($value.StartsWith('"') -and $value.EndsWith('"')))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                Set-Item -Path "Env:$($parts[0].Trim())" -Value $value
            }
        }
    }
}

Push-Location $projectRoot
try {
    if ($Component -in @('Verify','Model')) {
        $modelPython = Join-Path $projectRoot 'data/fine-tuning/.venv-cpu/Scripts/python.exe'
        if (-not (Test-Path -LiteralPath $modelPython)) { throw 'Pinned CPU runtime is missing; see services/model-serving/README.md.' }
        if ($Component -eq 'Verify') { & $modelPython services/model-serving/server.py --verify }
        else { & $modelPython services/model-serving/server.py }
    }
    else {
        if ($env:DICTALAB_AI_MODEL_DEMO_ENABLED -ne 'true' -or $env:DICTALAB_AI_JOBS_ENABLED -ne 'true') {
            throw 'Enable DICTALAB_AI_MODEL_DEMO_ENABLED and DICTALAB_AI_JOBS_ENABLED in protected runtime configuration.'
        }
        if ([string]::IsNullOrWhiteSpace($env:DICTALAB_AI_RABBITMQ_URL)) { throw 'Configure DICTALAB_AI_RABBITMQ_URL first.' }
        if ([string]::IsNullOrWhiteSpace($env:DICTALAB_AI_MODEL_SERVING_TOKEN)) {
            $env:DICTALAB_AI_MODEL_SERVING_TOKEN = $env:DICTALAB_MODEL_SERVICE_TOKEN
        }
        if (-not $env:DICTALAB_AI_JOB_RETENTION_SECONDS -or [int]$env:DICTALAB_AI_JOB_RETENTION_SECONDS -lt 3600) {
            $env:DICTALAB_AI_JOB_RETENTION_SECONDS = '86400'
        }
        if ($Component -eq 'Api') {
            & $Python -m uvicorn app.main:app --app-dir services/ai-generation --host 127.0.0.1 --port 8091
        }
        else {
            Set-Location (Join-Path $projectRoot 'services/ai-generation')
            # Use the same absolute SQLite path in API and worker despite different working directories.
            $jobPath = if ($env:DICTALAB_AI_JOBS_DATABASE) { $env:DICTALAB_AI_JOBS_DATABASE } else { 'data/ai-jobs.sqlite3' }
            $env:DICTALAB_AI_JOBS_DATABASE = [IO.Path]::GetFullPath($jobPath, $projectRoot)
            & $Python -m app.worker
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Component exited with code $LASTEXITCODE." }
}
finally { Pop-Location }
