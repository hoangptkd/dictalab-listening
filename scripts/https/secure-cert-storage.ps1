$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = 'C:\DictaLab\https'
$service = Get-CimInstance Win32_Service -Filter "Name='DictaLabHttps'"
if ($service.StartName -notin @('LocalSystem', 'NT AUTHORITY\SYSTEM')) {
    throw "Unexpected HTTPS service account: $($service.StartName)"
}

foreach ($name in @('data', 'staging-data')) {
    $path = (Resolve-Path -LiteralPath (Join-Path $root $name)).Path
    if (-not $path.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Certificate path is outside the expected directory: $path"
    }
    $items = @(Get-Item -LiteralPath $path) + @(Get-ChildItem -LiteralPath $path -Recurse -Force)
    foreach ($item in $items) {
        $systemRule = if ($item.PSIsContainer) { 'NT AUTHORITY\SYSTEM:(OI)(CI)F' } else { 'NT AUTHORITY\SYSTEM:F' }
        $adminRule = if ($item.PSIsContainer) { 'BUILTIN\Administrators:(OI)(CI)F' } else { 'BUILTIN\Administrators:F' }
        & icacls.exe $item.FullName /grant:r $systemRule $adminRule | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not grant certificate access: $($item.FullName)" }
        & icacls.exe $item.FullName /inheritance:r /remove:g 'BUILTIN\Users' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not remove inherited access: $($item.FullName)" }
    }
    foreach ($item in $items) {
        $identities = @((Get-Acl -LiteralPath $item.FullName).Access | ForEach-Object { $_.IdentityReference.Value })
        if ($identities -contains 'BUILTIN\Users' -or
            $identities -notcontains 'NT AUTHORITY\SYSTEM' -or
            $identities -notcontains 'BUILTIN\Administrators') {
            throw "Certificate storage permissions are incorrect: $($item.FullName)"
        }
    }
}

Write-Output 'CERT_STORAGE_SECURED'
