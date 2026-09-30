param(
    [string]$DatabaseName = "dictalab",
    [string]$DatabaseUser = "dictalab_app",
    [string]$BackupDirectory = "C:\DictaLab\backups"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$postgresBin = "C:\DictaLab\postgresql-17\bin"
$secretPath = Join-Path $env:ProgramData "DictaLab\database-secrets.xml"
$pgDump = Join-Path $postgresBin "pg_dump.exe"
$pgRestore = Join-Path $postgresBin "pg_restore.exe"
$psql = Join-Path $postgresBin "psql.exe"

foreach ($path in @($secretPath, $pgDump, $pgRestore, $psql)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required PostgreSQL backup file was not found: $path"
    }
}

$secrets = Import-Clixml -LiteralPath $secretPath
$env:PGPASSWORD = $secrets.AppPassword
try {
    $databaseBytesText = & $psql -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -tAc "SELECT pg_database_size(current_database())"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect PostgreSQL database size." }
    $databaseBytes = [int64]($databaseBytesText.Trim())
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @("BUILTIN\Administrators", "NT AUTHORITY\SYSTEM")) {
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $identity, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow"
    )
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $BackupDirectory -AclObject $acl
$driveName = (Split-Path -Qualifier $BackupDirectory).TrimEnd(':')
$drive = Get-PSDrive -Name $driveName
$requiredBytes = [int64]($databaseBytes * 1.2) + 536870912
if ($drive.Free -lt $requiredBytes) {
    throw "Insufficient free space for a verified database backup."
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $BackupDirectory "$DatabaseName-$stamp.dump"
$env:PGPASSWORD = $secrets.AppPassword
try {
    & $pgDump -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName -Fc -f $backupPath
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL backup failed." }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf) -or
    (Get-Item -LiteralPath $backupPath).Length -lt 1024) {
    throw "PostgreSQL backup is missing or unexpectedly small."
}
& $pgRestore --list $backupPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
    throw "PostgreSQL backup verification failed."
}

Write-Output "BACKUP_OK"
Write-Output "BACKUP_FILE=$([System.IO.Path]::GetFileName($backupPath))"
Write-Output "BACKUP_BYTES=$((Get-Item -LiteralPath $backupPath).Length)"
