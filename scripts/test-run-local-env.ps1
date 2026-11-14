param([string]$LauncherName = 'run-local.ps1')
$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot $LauncherName
if ($LauncherName -eq 'run-local.ps1') {
    $text = [IO.File]::ReadAllText($launcher)
    if (-not $text.Contains("'Desktop/English_dictition'") -or
        -not $text.Contains("'scripts/run-service.py'") -or
        -not $text.Contains("'local'") -or $text.Contains('Set-Item -Path')) {
        throw 'Local launcher must delegate to the canonical isolated Python configuration reader.'
    }
    Write-Host 'Canonical local launcher contract passed.'
    return
}
$tree = [System.Management.Automation.Language.Parser]::ParseFile($launcher, [ref]$null, [ref]$null)
$import = $tree.Find({ param($node)
    $node -is [System.Management.Automation.Language.PipelineAst] -and
        $node.Extent.Text.StartsWith('Get-Content -LiteralPath $envPath |')
}, $true)
if ($null -eq $import) { throw 'Launcher environment import not found.' }

# Execute only the real importer with fake input, never the app launcher or private .env.
function Get-Content {
    @('QUOTA_IMPORT_TEST_JSON=''[{"id":"a"}]''', 'QUOTA_IMPORT_TEST_RAW=a=b',
      'QUOTA_IMPORT_TEST_DOUBLE=" spaced "', 'QUOTA_IMPORT_TEST_SINGLE=''unmatched',
      'QUOTA_IMPORT_TEST_EMPTY=', '# ignored')
}
try {
    $envPath = 'not-read'
    & ([scriptblock]::Create($import.Extent.Text))
    if ($env:QUOTA_IMPORT_TEST_JSON -cne '[{"id":"a"}]' -or
        $env:QUOTA_IMPORT_TEST_RAW -cne 'a=b' -or
        $env:QUOTA_IMPORT_TEST_DOUBLE -cne ' spaced ' -or
        $env:QUOTA_IMPORT_TEST_SINGLE -cne "'unmatched" -or
        -not [string]::IsNullOrEmpty($env:QUOTA_IMPORT_TEST_EMPTY)) {
        throw 'Launcher changed dotenv quoting or values.'
    }
    Write-Host 'Local dotenv import checks passed.'
}
finally {
    Get-ChildItem Env:QUOTA_IMPORT_TEST_* | Remove-Item
}
