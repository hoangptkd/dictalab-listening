param(
    [Parameter(Mandatory)][string]$ReferenceArtifact,
    [Parameter(Mandatory)][string]$CandidateArtifact,
    [Parameter(Mandatory)][string]$ReportPath
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-Entries([string]$Path){
    $zip=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    $entries=@{}
    try{
        foreach($entry in $zip.Entries){
            if($entry.FullName.EndsWith('/')){continue}
            if($entries.ContainsKey($entry.FullName)){throw 'Duplicate archive entry.'}
            $stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create()
            try{$entries[$entry.FullName]=[BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','')}
            finally{$stream.Dispose();$hash.Dispose()}
        }
    }finally{$zip.Dispose()}
    return $entries
}
$reference=Get-Entries $ReferenceArtifact
$candidate=Get-Entries $CandidateArtifact
$differences=@(foreach($key in @(@($reference.Keys)+@($candidate.Keys)|Sort-Object -Unique)){
    if($reference[$key] -ne $candidate[$key]){
        [pscustomobject]@{Entry=$key;ReferenceSHA256=$reference[$key];CandidateSHA256=$candidate[$key]}
    }
})
$referenceHash=(Get-FileHash -LiteralPath $ReferenceArtifact -Algorithm SHA256).Hash
$candidateHash=(Get-FileHash -LiteralPath $CandidateArtifact -Algorithm SHA256).Hash
[pscustomobject]@{
    ComparedAt=(Get-Date).ToUniversalTime().ToString('o');ReferenceSHA256=$referenceHash;CandidateSHA256=$candidateHash
    ByteIdentical=($referenceHash -eq $candidateHash);EntryContentIdentical=($differences.Count -eq 0)
    ReferenceEntries=$reference.Count;CandidateEntries=$candidate.Count;Differences=$differences
} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath $ReportPath -Encoding utf8
Write-Output "BYTE_IDENTICAL=$($referenceHash -eq $candidateHash)"
Write-Output "ENTRY_DIFFERENCES=$($differences.Count)"
if($differences.Count){exit 2}
