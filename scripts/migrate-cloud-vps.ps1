param([Parameter(Mandatory=$true)][Security.SecureString]$NeonPassword,
      [Parameter(Mandatory=$true)][ValidateSet('Rehearsal')][string]$Phase,
      [string]$NeonCaFile='C:/Program Files/Git/mingw64/etc/ssl/certs/ca-bundle.crt')
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
Set-StrictMode -Version Latest
if(-not (Test-Path -LiteralPath $NeonCaFile -PathType Leaf)){throw 'A trusted PostgreSQL CA bundle is required'}
$root=Split-Path -Parent $PSScriptRoot
$old=@('-i','C:/Users/HP/.ssh/dictalab_vps_ed25519_v2','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10')
$new=@('-i','C:/Users/HP/.ssh/LightsailDefaultKey-us-east-1.pem','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10')
$export=@'
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$k=[Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Services\DictaLab\Parameters')
$settings=@{}
foreach($line in [string[]]$k.GetValue('AppEnvironmentExtra')){$parts=$line -split '=',2;if($parts.Count -ne 2){throw 'Malformed configuration'};$settings[$parts[0]]=$parts[1]}
$k.Dispose()
@{environment=$settings;aws=[IO.File]::ReadAllText('C:\ProgramData\DictaLab\auth\s3-credentials');caddy=(& C:\DictaLab\https\caddy.exe version)} | ConvertTo-Json -Compress -Depth 5
'@
$enc=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($export))
$raw=@(& ssh @old Administrator@160.191.244.27 powershell.exe -NoProfile -NonInteractive -EncodedCommand $enc)
if($LASTEXITCODE -ne 0){throw 'Protected source configuration export failed'}
$jsonLine=@($raw | Where-Object { $_ -match '^\s*\{' } | Select-Object -Last 1)
if($jsonLine.Count -ne 1){throw 'Protected source configuration export returned no JSON payload'}
$payload=($jsonLine[0].Trim()) | ConvertFrom-Json -AsHashtable
$settings=$payload.environment
$neonHost='ep-royal-truth-b51aodjz.c-7.us-east-2.aws.neon.tech'
$neonPooledHost='ep-royal-truth-b51aodjz-pooler.c-7.us-east-2.aws.neon.tech'
# Five direct sessions preserve session-level SQL deadlines and Flyway advisory locks.
$settings.DB_URL="jdbc:postgresql://$neonHost/neondb?sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca-certificates.crt&channelBinding=require&connectTimeout=15&socketTimeout=30&tcpKeepAlive=true"
$settings.DB_USERNAME='neondb_owner'
$neonPass=([PSCredential]::new('neon',$NeonPassword)).GetNetworkCredential().Password
$settings.DB_PASSWORD=$neonPass
foreach($key in @('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE','SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE','SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT')){$settings.Remove($key)}
$settings.SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE='5'
$settings.SPRING_DATASOURCE_HIKARI_MINIMUMIDLE='0'
$settings.SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT='3000'
$settings.PORT='18082'
$settings.MANAGEMENT_PORT='18183'
$settings.HTTP_THREADS='40'
$settings.HTTP_MAX_CONNECTIONS='512'
$settings.DICTALAB_MEDIA_ROOT='/var/lib/dictalab/media'
$settings.DICTALAB_PERSONAL_CONTENT_ROOT='/var/lib/dictalab/personal-content'
$settings.DICTALAB_PERSONAL_CONTENT_MANIFEST='/var/lib/dictalab/personal-content/manifest.json'
$settings.BACKUP_SUCCESS_MARKER='/var/lib/dictalab/backups/backup-success.json'
$settings.AWS_SHARED_CREDENTIALS_FILE='/etc/dictalab/aws-credentials'
$settings.DICTALAB_PUBLIC_URL='https://184.194.89.90'
$settings.DICTALAB_ALLOWED_ORIGINS='https://184.194.89.90,https://160.191.244.27:18443'
# Keep provider workers disabled while the rehearsal database is prepared.
$settings.DICTALAB_CONVERSATION_ENABLED='false'
$settings.DICTALAB_COACH_ENABLED='false'
# No provider credentials on the rehearsal writer, including the Listening dispatcher.
$settings.SHOPAIKEY_API_KEY=''
$settings.CARTESIA_API_KEY=''
$settings.LISTENING_DISPATCH_MS='86400000'
$settings.SPRING_TASK_SCHEDULING_POOL_SIZE='1'
$settings.DICTALAB_TURN_URL='turn:160.191.244.27:3478?transport=udp,turn:160.191.244.27:3478?transport=tcp'
$stage=Join-Path $root 'target/cloud-migration-20261010'
New-Item -ItemType Directory -Path $stage -Force | Out-Null

# Take a consistent logical snapshot from the source without exposing the DB password.
$dumpName='dictalab-cloud-migration.dump'
$dumpQuery=@'
$ErrorActionPreference='Stop';$k=[Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Services\DictaLab\Parameters');$pw=$null
foreach($line in [string[]]$k.GetValue('AppEnvironmentExtra')){$p=$line -split '=',2;if($p[0] -eq 'DB_PASSWORD'){$pw=$p[1]}};$k.Dispose();if(-not $pw){throw 'DB password missing'}
$env:PGPASSWORD=$pw;& C:\DictaLab\postgresql-17\bin\pg_dump.exe -h 127.0.0.1 -U dictalab_app -d dictalab -Fc -f (Join-Path 'C:\DictaLab\backups' 'dictalab-cloud-migration.dump');if($LASTEXITCODE -ne 0){throw 'pg_dump failed'};Remove-Item Env:PGPASSWORD
@{
    size=(Get-Item 'C:\DictaLab\backups\dictalab-cloud-migration.dump').Length
    sha256=(Get-FileHash 'C:\DictaLab\backups\dictalab-cloud-migration.dump').Hash
} | ConvertTo-Json -Compress
'@
$dumpEnc=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($dumpQuery))
$dumpRaw=@(& ssh @old Administrator@160.191.244.27 powershell.exe -NoProfile -NonInteractive -EncodedCommand $dumpEnc)
if($LASTEXITCODE -ne 0){throw 'Source database dump failed'}
$dumpJson=@($dumpRaw | Where-Object { $_ -match '^\s*\{' } | Select-Object -Last 1)
if($dumpJson.Count -ne 1){throw 'Source dump did not return verification metadata'}
& scp @old Administrator@160.191.244.27:C:/DictaLab/backups/$dumpName (Join-Path $stage $dumpName)
if($LASTEXITCODE -ne 0){throw 'Database dump download failed'}
if((Get-FileHash (Join-Path $stage $dumpName)).Hash -ne (($dumpJson[0] | ConvertFrom-Json).sha256)){throw 'Database dump checksum mismatch'}

# Neon must be empty before restore; use the direct endpoint for pg_restore.
$directUrl="postgresql://neondb_owner@$neonHost/neondb?sslmode=verify-full&channel_binding=require"
$previousPgPassword=$env:PGPASSWORD
$previousPgCa=$env:PGSSLROOTCERT
$env:PGPASSWORD=$neonPass
$env:PGSSLROOTCERT=$NeonCaFile
try {
    $tables=& psql.exe $directUrl -Atc "SELECT count(*) FROM pg_tables WHERE schemaname='public'"
    if($LASTEXITCODE -ne 0){throw 'Neon connectivity check failed'}
    if([int]$tables -gt 0){throw 'Neon database is not empty; refusing destructive restore'}
    & pg_restore.exe --exit-on-error --no-owner --no-acl --dbname=$directUrl (Join-Path $stage $dumpName)
    if($LASTEXITCODE -ne 0){throw 'Neon restore failed'}
} finally { $env:PGPASSWORD=$previousPgPassword; $env:PGSSLROOTCERT=$previousPgCa }
& scp @old Administrator@160.191.244.27:C:/DictaLab/app/dictalab.jar (Join-Path $stage 'dictalab.jar')
if($LASTEXITCODE -ne 0){throw 'Artifact download failed'}
$hash=(Get-FileHash (Join-Path $stage 'dictalab.jar')).Hash
if($hash -ne 'DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F'){throw 'Unexpected production artifact'}
& ssh @new ubuntu@184.194.89.90 'mkdir -p /home/ubuntu/dictalab-migration'
if($LASTEXITCODE -ne 0){throw 'Target staging preparation failed'}
& scp @new (Join-Path $stage 'dictalab.jar') "$PSScriptRoot/linux/configure-cloud.py" "$PSScriptRoot/linux/prepare-vps.sh" "$PSScriptRoot/linux/dictalab.service" "$PSScriptRoot/linux/Caddyfile" "$PSScriptRoot/linux/activate-vps.sh" "$PSScriptRoot/linux/install-caddy.sh" ubuntu@184.194.89.90:/home/ubuntu/dictalab-migration/
if($LASTEXITCODE -ne 0){throw 'Target upload failed'}
$prepare='sudo bash /home/ubuntu/dictalab-migration/prepare-vps.sh'
& ssh @new ubuntu@184.194.89.90 $prepare
if($LASTEXITCODE -ne 0){throw 'Target runtime preparation failed'}
& ssh @new ubuntu@184.194.89.90 'sudo bash /home/ubuntu/dictalab-migration/install-caddy.sh'
if($LASTEXITCODE -ne 0){throw 'Verified Caddy installation failed'}
$payload | ConvertTo-Json -Compress -Depth 6 | & ssh @new ubuntu@184.194.89.90 'sudo python3 /home/ubuntu/dictalab-migration/configure-cloud.py'
if($LASTEXITCODE -ne 0){throw 'Target credential provisioning failed after runtime preparation'}
foreach($directory in @('media','personal-content')){
    & scp @old -r "Administrator@160.191.244.27:C:/DictaLab/$directory" $stage
    if($LASTEXITCODE -ne 0){throw 'Source media download failed'}
    & scp @new -r (Join-Path $stage $directory) ubuntu@184.194.89.90:/home/ubuntu/dictalab-migration/
    if($LASTEXITCODE -ne 0){throw 'Target media upload failed'}
    & ssh @new ubuntu@184.194.89.90 "sudo cp -a /home/ubuntu/dictalab-migration/$directory/. /var/lib/dictalab/$directory/ && sudo chown -R dictalab:dictalab /var/lib/dictalab/$directory"
    if($LASTEXITCODE -ne 0){throw 'Target media installation failed'}
}
& ssh @new ubuntu@184.194.89.90 'sudo bash /home/ubuntu/dictalab-migration/activate-vps.sh'
if($LASTEXITCODE -ne 0){throw 'DictaLab service did not become healthy on the target'}
$settings.DB_PASSWORD=$null;$neonPass=$null;$payload=$null;$raw=$null
Write-Output "SOURCE_ARTIFACT_VERIFIED=$hash"
Write-Output 'CLOUD_CONFIGURATION_RECEIVED'
Write-Output 'REHEARSAL_ONLY_NOT_A_FINAL_CUTOVER'
