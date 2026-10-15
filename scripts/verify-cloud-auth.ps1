param([string]$BaseUrl='https://184.194.89.90')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Net.Http
$handler=[Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect=$false
$client=[Net.Http.HttpClient]::new($handler)
$client.Timeout=[TimeSpan]::FromSeconds(30)
$email='cloud-login-'+[guid]::NewGuid().ToString('N')+'@loadtest.local'
$password='Smoke9'+[guid]::NewGuid().ToString('N')
$accountId=$null
function Send([string]$Path,[string]$Method='GET',$Body=$null) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$BaseUrl+$Path)
    try {
        if($Method -ne 'GET') {
            $csrfResponse=Send '/api/auth/csrf'
            try {
                if([int]$csrfResponse.StatusCode -ne 200){throw 'CSRF endpoint failed'}
                $csrf=$csrfResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                $null=$request.Headers.TryAddWithoutValidation($csrf.headerName,$csrf.token)
            } finally {$csrfResponse.Dispose()}
        }
        if($null -ne $Body){$request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')}
        return $client.SendAsync($request).GetAwaiter().GetResult()
    } finally {$request.Dispose()}
}
function Check([string]$Path,[int]$Expected,[string]$Method='GET',$Body=$null) {
    $response=Send $Path $Method $Body
    try {
        if([int]$response.StatusCode -ne $Expected){throw "Expected $Expected, got $([int]$response.StatusCode): $Method $Path"}
        "AUTH_CHECK=$Method $Path $Expected"
        if($Path -eq '/api/auth/register') {
            $script:accountId=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).id
        }
        if($Path -eq '/api/auth/login') {
            foreach($name in @('DICTALAB_ACCESS','DICTALAB_REFRESH')) {
                $cookie=$handler.CookieContainer.GetCookies([uri]($BaseUrl+'/api/auth/refresh'))[$name]
                if(-not $cookie -or -not $cookie.Secure -or -not $cookie.HttpOnly){throw 'Authentication cookie protection failed'}
            }
            'AUTH_SECURE_HTTPONLY_COOKIES=OK'
        }
    } finally {$response.Dispose()}
}
try {
    Check '/login' 200
    Check '/api/auth/me' 401
    Check '/api/auth/register' 201 'POST' @{displayName='Cloud login smoke';email=$email;password=$password}
    Check '/api/auth/me' 200
    Check '/api/admin/lessons' 403
    Check '/api/v1/catalog/lessons?level=A1' 200
    Check '/api/auth/logout' 204 'POST'
    Check '/api/auth/me' 401
    Check '/api/auth/login' 200 'POST' @{email=$email;password=$password}
    Check '/api/auth/refresh' 204 'POST'
    Check '/api/auth/me' 200
    Check '/api/auth/logout' 204 'POST'
    Check '/api/auth/me' 401
    'CLOUD_HTTPS_AUTH_SMOKE=PASS'
} finally {
    # Revoke only this probe's token families before removing its exact synthetic account.
    try {$response=Send '/api/auth/logout' 'POST';$response.Dispose()} catch {Write-Warning 'Probe logout needs inspection'}
    $cleanup=@'
import importlib.util, json, re, subprocess, sys
spec=importlib.util.spec_from_file_location('backup','/opt/dictalab/tools/cloud-backup.py')
backup=importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
probe=json.load(sys.stdin)
email=probe['email']
if not re.fullmatch(r'cloud-login-[0-9a-f]{32}@loadtest\.local',email):
    raise SystemExit('Invalid synthetic cleanup target')
predicate="email='"+email+"' AND display_name='Cloud login smoke' AND role='USER'"
if probe.get('id') is not None:
    predicate+=' AND id='+str(int(probe['id']))
sql='BEGIN; DELETE FROM app_users WHERE '+predicate+' RETURNING id; COMMIT;'
result=subprocess.run(['/usr/lib/postgresql/18/bin/psql','-X','-qAt','-v','ON_ERROR_STOP=1','-c',sql],env=backup.connection_environment(),capture_output=True,text=True,timeout=30)
if result.returncode:
    raise SystemExit('Synthetic cleanup failed; credentials suppressed')
print('AUTH_SYNTHETIC_ACCOUNT_CLEANUP=OK')
'@
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cleanup))
    $remote='sudo -n python3 -c "exec(__import__(''base64'').b64decode('''+$encoded+'''))"'
    @{email=$email;id=$accountId} | ConvertTo-Json -Compress | & ssh -i C:/Users/HP/.ssh/LightsailDefaultKey-us-east-1.pem -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 ubuntu@184.194.89.90 $remote
    if($LASTEXITCODE -ne 0){throw 'Exact synthetic account cleanup failed'}
    $password=$null
    $client.Dispose();$handler.Dispose()
}
