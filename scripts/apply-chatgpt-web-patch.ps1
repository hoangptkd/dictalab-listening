param([Parameter(Mandatory=$true)][string]$BridgeRoot)
$ErrorActionPreference = 'Stop'
# Legacy rollback tooling. Current application startup/deploy no longer applies this patch.
$bridgePath = (Resolve-Path -LiteralPath $BridgeRoot).Path
$compatPatch = Join-Path $PSScriptRoot 'chatgpt-web-compat.patch'
Push-Location -LiteralPath $bridgePath
try {
    & git apply --reverse --check $compatPatch 2>$null
    if ($LASTEXITCODE -eq 0) { return }
    & git apply --check $compatPatch 2>$null
    if ($LASTEXITCODE -eq 0) {
        & git apply $compatPatch
        if ($LASTEXITCODE -ne 0) { throw 'Could not apply bridge compatibility patch.' }
        return
    }

    # Merge the current file with the new patch against the upstream base.
    # Work in a temporary folder so conflicts never overwrite the installed bridge.
    $gitRead = [Diagnostics.ProcessStartInfo]::new((Get-Command git).Source, 'show HEAD:index.js')
    $gitRead.WorkingDirectory = $bridgePath
    $gitRead.UseShellExecute = $false
    $gitRead.CreateNoWindow = $true
    $gitRead.RedirectStandardOutput = $true
    $gitRead.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $gitProcess = [Diagnostics.Process]::Start($gitRead)
    try {
        $baseSource = $gitProcess.StandardOutput.ReadToEnd()
        $gitProcess.WaitForExit()
        if ($gitProcess.ExitCode -ne 0) { throw 'Could not read the bridge upstream source.' }
    } finally { $gitProcess.Dispose() }
    $patchFolder = Join-Path ([IO.Path]::GetTempPath()) ('dictalab-bridge-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $patchFolder | Out-Null
    try {
        $baseFile = Join-Path $patchFolder 'base.js'
        $patchedFile = Join-Path $patchFolder 'index.js'
        $currentFile = Join-Path $patchFolder 'current.js'
        [IO.File]::WriteAllText($baseFile, $baseSource, [Text.UTF8Encoding]::new($false))
        Copy-Item -LiteralPath $baseFile -Destination $patchedFile
        Copy-Item -LiteralPath (Join-Path $bridgePath 'index.js') -Destination $currentFile
        Push-Location -LiteralPath $patchFolder
        try {
            & git apply $compatPatch
            if ($LASTEXITCODE -ne 0) { throw 'Bridge upstream changed; compatibility patch needs review.' }
            foreach ($mergeFile in @($baseFile, $patchedFile, $currentFile)) {
                $mergeText = [IO.File]::ReadAllText($mergeFile, [Text.Encoding]::UTF8).Replace("`r`n", "`n")
                [IO.File]::WriteAllText($mergeFile, $mergeText, [Text.UTF8Encoding]::new($false))
            }
            & git merge-file $currentFile $baseFile $patchedFile
            if ($LASTEXITCODE -ne 0) { throw 'Bridge patch conflicts with local edits; installed file was preserved.' }
        } finally { Pop-Location }
        Copy-Item -LiteralPath $currentFile -Destination (Join-Path $bridgePath 'index.js')
    } finally {
        foreach ($patchName in @('base.js', 'index.js', 'current.js')) {
            $temporaryFile = Join-Path $patchFolder $patchName
            if (Test-Path -LiteralPath $temporaryFile) { Remove-Item -LiteralPath $temporaryFile }
        }
        Remove-Item -LiteralPath $patchFolder
    }
} finally { Pop-Location }
