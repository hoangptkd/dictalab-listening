param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$canonical = Join-Path $env:USERPROFILE 'Desktop/English_dictition'
$script = Join-Path $canonical 'scripts/run-service.py'
if (-not (Test-Path -LiteralPath $script)) {
    throw 'Canonical DictaLab checkout is missing from Desktop/English_dictition.'
}
$python = (Get-Command python -ErrorAction Stop).Source
$arguments = @($script, 'local')
if ($CheckOnly) { $arguments += '--check-only' }
& $python @arguments
exit $LASTEXITCODE
