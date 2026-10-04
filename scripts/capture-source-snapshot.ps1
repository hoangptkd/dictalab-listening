param(
    [Parameter(Mandatory)][string]$ReferenceArtifact,
    [Parameter(Mandatory)][string]$Destination
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=Split-Path -Parent $PSScriptRoot
$reference=(Resolve-Path -LiteralPath $ReferenceArtifact).Path
$destinationPath=[IO.Path]::GetFullPath($Destination)
if(Test-Path -LiteralPath $destinationPath){throw 'Snapshot destination must not exist; retained evidence is never overwritten.'}
$source=Join-Path $destinationPath 'source'
New-Item -ItemType Directory -Path $source | Out-Null
# Allowlist build inputs and operating instructions. Never copy .env, databases, profiles or private logs.
$paths=@('pom.xml','src','scripts','ops','.github','README.md')
$manifest=@()
foreach($path in $paths){
    $inputPath=Join-Path $root $path
    if(-not(Test-Path -LiteralPath $inputPath)){continue}
    $files=if(Test-Path -LiteralPath $inputPath -PathType Container){Get-ChildItem -LiteralPath $inputPath -File -Recurse}else{Get-Item -LiteralPath $inputPath}
    foreach($file in $files){
        if($file.FullName -match '[\\/](__pycache__|node_modules|\.git)[\\/]'){continue}
        $relative=$file.FullName.Substring($root.Length+1)
        $before=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        $output=Join-Path $source $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $output
        if((Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash -ne $before){throw "Source changed during capture: $relative"}
        $manifest+= [pscustomobject]@{Path=$relative.Replace('\','/');SHA256=$before}
    }
}
# Second pass detects a concurrent editor changing an earlier file during the copy.
foreach($entry in $manifest){
    if((Get-FileHash -LiteralPath (Join-Path $root $entry.Path) -Algorithm SHA256).Hash -ne $entry.SHA256){throw "Source changed during capture: $($entry.Path)"}
}
Copy-Item -LiteralPath $reference -Destination (Join-Path $destinationPath 'reference.jar')
$artifactHash=(Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash
if((Get-FileHash -LiteralPath (Join-Path $destinationPath 'reference.jar') -Algorithm SHA256).Hash -ne $artifactHash){throw 'Artifact changed during capture.'}
$head=& git -C $root rev-parse HEAD
if($LASTEXITCODE -ne 0){throw 'Cannot identify Git baseline.'}
[pscustomobject]@{
    CapturedAt=(Get-Date).ToUniversalTime().ToString('o');GitBaseline=$head
    ReferenceSHA256=$artifactHash;Status='CAPTURED_NOT_PROVEN_EQUIVALENT'
    BuildCommand='powershell -File scripts/run-readiness-checks.ps1 -Suite H2 -TimeoutSeconds 600'
    Files=$manifest
} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $destinationPath 'manifest.json') -Encoding utf8
& java -version 2>&1 | Out-File -LiteralPath (Join-Path $destinationPath 'java-version.txt') -Encoding utf8
& mvn -version 2>&1 | Out-File -LiteralPath (Join-Path $destinationPath 'maven-version.txt') -Encoding utf8
Write-Output "SNAPSHOT_CAPTURED=$destinationPath"
Write-Output "REFERENCE_SHA256=$artifactHash"
