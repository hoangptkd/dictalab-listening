$ErrorActionPreference = 'Stop'
$serviceName = 'DictaLab'
$nssm = (Get-Command nssm.exe -ErrorAction Stop).Source

$original = @(& $nssm get $serviceName AppEnvironmentExtra)
if ($LASTEXITCODE -ne 0) { throw 'Could not read DictaLab service environment.' }
$settings = @{}
foreach ($entry in $original) {
    if ($entry -match '^([^=]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if ([string]::IsNullOrWhiteSpace($settings['GEMINI_API_KEY'])) { throw 'GEMINI_API_KEY is missing from the service environment.' }
$apiUrl = if ($settings['GEMINI_URL']) { $settings['GEMINI_URL'] } else { 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions' }
$model = if ($settings['GEMINI_MODEL']) { $settings['GEMINI_MODEL'] } else { 'gemini-3.5-flash-lite' }

# Verify the API using synthetic input only, never learner evidence or credentials in output.
$probe = @{model=$model;temperature=0;max_tokens=64;response_format=@{type='json_object'};messages=@(@{role='user';content='Return only {"ready":true} as JSON.'})} |
    ConvertTo-Json -Depth 5 -Compress
try {
    $response = Invoke-RestMethod $apiUrl -Method Post -Headers @{Authorization="Bearer $($settings['GEMINI_API_KEY'])"} `
        -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($probe)) -TimeoutSec 60
    $answer = $response.choices[0].message.content | ConvertFrom-Json
    if ($answer.ready -ne $true) { throw 'Unexpected JSON.' }
} catch { throw 'Gemini probe failed; service environment was not changed.' }

$legacyNames = @('DICTALAB_COACH_ENABLED', 'DICTALAB_COACH_URL', 'DICTALAB_COACH_MODEL', 'DICTALAB_COACH_API_KEY', 'DICTALAB_COACH_TIMEOUT')
$updated = @($original | Where-Object {
    $_ -and $legacyNames -notcontains $_.Split('=', 2)[0]
})
$updated += 'DICTALAB_COACH_ENABLED=true'

try {
    & $nssm set $serviceName AppEnvironmentExtra $updated | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not enable AI Coach.' }
    Restart-Service $serviceName -Force
    $deadline = (Get-Date).AddSeconds(150)
    do {
        try {
            if ((Invoke-RestMethod 'http://127.0.0.1:18082/actuator/health' -TimeoutSec 5).status -eq 'UP') {
                Write-Output 'COACH_ENABLED'
                exit 0
            }
        } catch {}
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw 'DictaLab did not become healthy after enabling AI Coach.'
} catch {
    & $nssm set $serviceName AppEnvironmentExtra $original | Out-Null
    Restart-Service $serviceName -Force
    throw
}
