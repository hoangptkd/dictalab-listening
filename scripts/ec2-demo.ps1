param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('start', 'stop', 'status', 'backup')]
    [string]$Action,
    [string]$HostName = '54.254.221.170',
    [string]$IdentityFile = "$env:USERPROFILE/.ssh/LightsailDefaultKey-us-east-1.pem"
)
$ErrorActionPreference = 'Stop'
if ($HostName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]*$' -or -not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
    throw 'Provide a valid EC2 host and existing SSH identity file.'
}
& ssh -i $IdentityFile -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 "ubuntu@$HostName" "sudo /usr/local/sbin/dictalab-demo $Action"
if ($LASTEXITCODE -ne 0) { throw 'EC2 operation failed; inspect its protected configuration and service logs.' }
