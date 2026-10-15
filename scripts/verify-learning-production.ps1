param([string]$Evidence='target/production-load-20261011/learning-smoke.json')
$ErrorActionPreference='Stop'
$base='https://184.194.89.90'
$fixture=Get-Content (Join-Path $PSScriptRoot '../target/production-load-20261011/private-fixture.json') -Raw | ConvertFrom-Json
$handler=[Net.Http.HttpClientHandler]::new()
$client=[Net.Http.HttpClient]::new($handler)
$client.Timeout=[TimeSpan]::FromSeconds(35)
$checks=[ordered]@{at=[DateTimeOffset]::UtcNow.ToString('o');realAiCalls=0;tlsValidation=$true}
function Request([string]$Path,[string]$Method='GET',$Body=$null,[string]$Key='',[int]$Expected=200) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$base+$Path)
    try {
        if($Method -ne 'GET'){
            $csrf=Request '/api/auth/csrf'
            $null=$request.Headers.TryAddWithoutValidation($csrf.headerName,$csrf.token)
        }
        if($Key){$null=$request.Headers.TryAddWithoutValidation('Idempotency-Key',$Key)}
        if($null -ne $Body){$request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')}
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if([int]$response.StatusCode -ne $Expected){throw "Learning smoke rejected: $Method $Path HTTP $([int]$response.StatusCode), expected $Expected"}
            if($Expected -lt 300 -and $Expected -ne 204){
                $text=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if($text){return $text | ConvertFrom-Json -Depth 40}
            }
        } finally {$response.Dispose()}
    } finally {$request.Dispose()}
}
function Same($First,$Second) {
    if(($First | ConvertTo-Json -Depth 40 -Compress) -ne ($Second | ConvertTo-Json -Depth 40 -Compress)){
        throw 'Committed replay or recovery result changed'
    }
}
function Audio([string]$Path) {
    if($Path -notmatch '^/(audio|media)/[A-Za-z0-9_./%-]+$' -or $Path.Contains('..')){throw 'Unexpected recorded audio target'}
    $response=$client.GetAsync($base+$Path).GetAwaiter().GetResult()
    try {
        if(-not $response.IsSuccessStatusCode){throw 'Recorded audio download failed'}
        $bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if($bytes.Length -lt 12){throw 'Recorded audio body is empty'}
        return $bytes.Length
    } finally {$response.Dispose()}
}
try {
    $null=Request '/api/v1/listening-learning' 'GET' $null '' 401
    $null=Request '/api/auth/login' 'POST' @{email=$fixture.accounts[0].email;password=$fixture.password}
    $lessons=Request '/api/v1/catalog/lessons?level=A1'
    $lesson=@($lessons | Where-Object previewAudioUrl)[0]
    $session=Request '/api/v1/practice-sessions' 'POST' @{lessonId=$lesson.id}
    $sentence=$session.sentence
    $path="/api/v1/practice-sessions/$($session.sessionId)/sentences/$($sentence.sentenceId)"
    $key='smoke-'+[guid]::NewGuid().ToString('N')
    $null=Request ($path+'/replay-events') 'POST' @{event='STARTED';playbackSpeed=1} ($key+'-play')
    $checks.audioBytes=Audio $sentence.audioUrl
    $word=@($sentence.words | Where-Object {-not $_.prefilled})[0]
    if(-not $word){throw 'No dictation word available for smoke'}
    $body=@{answer='learning-probe';responseTimeMs=1000}
    $first=Request ($path+"/words/$($word.index)/checks") 'POST' $body ($key+'-check')
    Same $first (Request ($path+"/words/$($word.index)/checks") 'POST' $body ($key+'-check'))
    $null=Request ($path+"/words/$($word.index)/checks") 'POST' @{answer='different'} ($key+'-check') 409
    $checks.checkReplayAndConflict=$true
    $null=Request ($path+'/hints') 'POST' @{type='REVEAL_SENTENCE'} ($key+'-hint')
    $null=Request ($path+'/replay-events') 'POST' @{event='STARTED';playbackSpeed=1} ($key+'-replay-start')
    $null=Request ($path+'/replay-events') 'POST' @{event='COMPLETED';playbackSpeed=1} ($key+'-replay-end')
    $body=@{timeSpentSeconds=20}
    $completion=Request ($path+'/complete') 'POST' $body ($key+'-complete')
    Same $completion (Request ($path+'/complete') 'POST' $body ($key+'-complete'))
    Same $completion (Request ($path+'/complete') 'POST' $body ($key+'-another-complete'))
    $checks.completeReplayAndNaturalOutcome=$true
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        $bank=Request '/api/v1/listening-learning'
        if(@($bank.items | Where-Object {$_.session_id -eq $session.sessionId -and $_.sentence_id -eq $sentence.sentenceId}).Count){break}
        Start-Sleep -Seconds 3
    } while([DateTimeOffset]::UtcNow -lt $deadline)
    if(-not @($bank.items | Where-Object {$_.session_id -eq $session.sessionId -and $_.sentence_id -eq $sentence.sentenceId}).Count){throw 'Durable completion did not project evidence within deadline'}
    $checks.rabbitProjection=$true;$checks.pending=$bank.pending;$checks.failed=$bank.failed
    $challenges=Request '/api/v1/listening-learning/reviews' 'POST'
    $challenge=@($challenges)[0]
    if(-not $challenge.id){throw 'No review source indexed yet'}
    $reviewPath='/api/v1/listening-learning/reviews/'+$challenge.id
    $null=Request ($reviewPath+'/answer') 'POST' @{answer='fixture';reveal=$false} '' 409
    $checks.reviewAudioBytes=Audio $challenge.audioUrl
    $null=Request ($reviewPath+'/played') 'POST' $null '' 204
    $body=@{answer='fixture';reveal=$false}
    $result=Request ($reviewPath+'/answer') 'POST' $body
    Same $result (Request ($reviewPath+'/answer') 'POST' $body)
    Same $result (Request $reviewPath).result
    $null=Request ($reviewPath+'/answer') 'POST' @{answer='different';reveal=$false} '' 409
    $checks.reviewPlayedRequiredReplayConflictAndRecovery=$true
    $checks.newContext=$challenge.newContext
    $null=Request '/api/admin/learning/jobs' 'GET' $null '' 403
    $null=Request '/api/auth/logout' 'POST' $null '' 204
    $null=Request '/api/auth/login' 'POST' @{email=$fixture.accounts[1].email;password=$fixture.password}
    $null=Request $reviewPath 'GET' $null '' 404
    $checks.ownershipAndAdminAuthorization=$true
    $null=Request '/api/auth/logout' 'POST' $null '' 204
    $checks.passed=$true
    $checks | ConvertTo-Json | Out-File $Evidence -Encoding utf8
    $checks | ConvertTo-Json
} finally {$client.Dispose();$handler.Dispose()}
