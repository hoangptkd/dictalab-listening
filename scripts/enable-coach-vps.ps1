$ErrorActionPreference = 'Stop'
$serviceName = 'DictaLab'
$nssm = (Get-Command nssm.exe -ErrorAction Stop).Source

# Check the real account session before sending learner evidence to the bridge.
$probe = @{model='chatgpt-web';stream=$false;messages=@(@{role='user';content='Return only {"ready":true} as JSON.'})} |
    ConvertTo-Json -Depth 5 -Compress
$response = Invoke-RestMethod 'http://127.0.0.1:3000/v1/chat/completions' -Method Post `
    -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($probe)) -TimeoutSec 90
$answer = $response.choices[0].message.content | ConvertFrom-Json
if ($answer.ready -ne $true) { throw 'ChatGPT bridge did not return the expected JSON.' }

$original = @(& $nssm get $serviceName AppEnvironmentExtra)
if ($LASTEXITCODE -ne 0) { throw 'Could not read DictaLab service environment.' }
$updated = @($original | Where-Object {
    $_ -and -not $_.StartsWith('DICTALAB_COACH_ENABLED=', [StringComparison]::OrdinalIgnoreCase) -and
    -not $_.StartsWith('DICTALAB_COACH_URL=', [StringComparison]::OrdinalIgnoreCase)
})
$updated += 'DICTALAB_COACH_ENABLED=true'
$updated += 'DICTALAB_COACH_URL=http://127.0.0.1:3000/v1/chat/completions'

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
