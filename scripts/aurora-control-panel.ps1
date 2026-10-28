#requires -Version 7.0
param(
    [string]$HostName = '54.254.221.170',
    [string]$IdentityFile = "$env:USERPROFILE/.ssh/LightsailDefaultKey-us-east-1.pem",
    [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'

function Start-PanelProcess([string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh.exe'))
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    # Drain both pipes immediately so a long operation cannot block on a full pipe.
    return @{ Process = $process; Output = $process.StandardOutput.ReadToEndAsync();
        Error = $process.StandardError.ReadToEndAsync(); Started = [datetime]::UtcNow }
}

function Read-PanelResult($Job, [string]$Action) {
    if (-not $Job.Process.HasExited -or -not $Job.Output.IsCompleted -or -not $Job.Error.IsCompleted) {
        throw 'Operation has not finished.'
    }
    if ($Job.Process.ExitCode -ne 0) { throw 'SSH operation failed.' }
    $result = $Job.Output.GetAwaiter().GetResult() | ConvertFrom-Json -AsHashtable
    if ($result -isnot [System.Collections.IDictionary]) { throw 'Invalid operation result.' }
    if ($Action -eq 'status') {
        if ($result.aurora -isnot [string] -or [string]::IsNullOrWhiteSpace($result.aurora) -or
            $result.maintenance -isnot [bool] -or $result.services -isnot [string] -or
            $result.memory -isnot [string]) { throw 'Incomplete status response.' }
        $services = @{}
        foreach ($block in ($result.services -split '\r?\n\s*\r?\n')) {
            $values = ConvertFrom-StringData $block
            if ($values.Id) { $services[$values.Id] = $values.ActiveState }
        }
        foreach ($name in 'dictalab', 'dictalab-ai', 'dictalab-ai-worker', 'dictalab-progress') {
            if (-not $services.ContainsKey("$name.service") -or
                [string]::IsNullOrWhiteSpace($services["$name.service"])) { throw 'Incomplete service status.' }
        }
        $result.serviceStates = $services
    } else {
        $accepted = if ($Action -eq 'start') { @('ready', 'already-running') } else { @('stopped-and-backed-up', 'already-stopped') }
        if ($result.status -notin $accepted -or ($Action -eq 'stop' -and $result.aurora -ne 'stopped')) {
            throw 'Operation was not confirmed.'
        }
    }
    return $result
}

if ($SelfTest) {
    function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
    function New-TestJob([string]$Json, [string]$Action, [int]$ExitCode = 0) {
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Json))
        $job = Start-PanelProcess @('-NoProfile', '-Command', "[Console]::Out.Write([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded'))); exit $ExitCode")
        $job.Action = $Action
        Assert ($null -ne $job.Output -and $null -ne $job.Error) 'Both pipes must be drained asynchronously.'
        $job.Process.WaitForExit()
        return $job
    }
    function Test-Result([string]$Json, [string]$Action, [int]$ExitCode = 0) {
        $job = New-TestJob $Json $Action $ExitCode
        try { return Read-PanelResult $job $Action } finally { $job.Process.Dispose() }
    }
    $states = ('dictalab', 'dictalab-ai', 'dictalab-ai-worker', 'dictalab-progress' | ForEach-Object { "Id=$_.service`nActiveState=active" }) -join "`n`n"
    $fixture = @{aurora='available'; maintenance=$false; services=$states; memory='Mem: 1905 1000 400'} | ConvertTo-Json -Compress
    $parsed = Test-Result $fixture status
    Assert ($parsed.serviceStates.Count -eq 4 -and -not $parsed.maintenance) 'Status parser failed.'
    Assert ((Test-Result '{"status":"ready"}' start).status -eq 'ready') 'Start confirmation failed.'
    Assert ((Test-Result '{"status":"already-stopped","aurora":"stopped"}' stop).aurora -eq 'stopped') 'Stop confirmation failed.'
    foreach ($case in @(
        @{Json=$fixture; Action='status'; ExitCode=5},
        @{Json='not-json'; Action='status'; ExitCode=0},
        @{Json='{"aurora":"available","maintenance":false,"services":"","memory":""}'; Action='status'; ExitCode=0},
        @{Json='{"status":"ready"}'; Action='stop'; ExitCode=0},
        @{Json='{"status":"stopped-and-backed-up","aurora":"stopping"}'; Action='stop'; ExitCode=0}
    )) {
        $rejected = $false
        try { $null = Test-Result $case.Json $case.Action $case.ExitCode } catch { $rejected = $true }
        Assert $rejected 'An invalid or failed operation was accepted.'
    }
}

if (-not $IsWindows) { throw 'Open this panel on Windows with PowerShell 7.' }
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
if (-not $SelfTest -and ($HostName -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]*$' -or -not (Test-Path -LiteralPath $IdentityFile -PathType Leaf))) {
    [Windows.Forms.MessageBox]::Show('Kiểm tra địa chỉ EC2 và đường dẫn khóa SSH. Có thể truyền -HostName và -IdentityFile khi mở bảng.', 'Không mở được bảng Aurora', 'OK', 'Error') | Out-Null
    exit 1
}
[Windows.Forms.Application]::SetHighDpiMode('SystemAware') | Out-Null
[Windows.Forms.Application]::EnableVisualStyles()

$script:jobs = @{}
$script:lastStatus = $null
$script:lastCheck = [datetime]::MinValue
$script:nextCheck = [datetime]::UtcNow
$script:statusValid = $false
$script:statusAfter = [datetime]::MinValue
$script:connectionError = $false
$ink = [Drawing.ColorTranslator]::FromHtml('#132a2b')
$green = [Drawing.ColorTranslator]::FromHtml('#13775d')
$coral = [Drawing.ColorTranslator]::FromHtml('#b63c28')
$form = [Windows.Forms.Form]::new()
$form.Text = 'DictaLab | Aurora'
$form.ClientSize = [Drawing.Size]::new(750, 570)
$form.MinimumSize = [Drawing.Size]::new(766, 609)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.ColorTranslator]::FromHtml('#f4f0e6')
$form.ForeColor = $ink
$form.Font = [Drawing.Font]::new('Segoe UI', 10)

$layout = [Windows.Forms.TableLayoutPanel]::new()
$layout.Dock = 'Fill'
$layout.Padding = [Windows.Forms.Padding]::new(24)
$layout.ColumnCount = 1
$layout.RowCount = 8
foreach ($height in 40, 36, 56, 50, 52, 48) { $layout.RowStyles.Add([Windows.Forms.RowStyle]::new('Absolute', $height)) | Out-Null }
$layout.RowStyles.Add([Windows.Forms.RowStyle]::new('Percent', 100)) | Out-Null
$layout.RowStyles.Add([Windows.Forms.RowStyle]::new('Absolute', 42)) | Out-Null
$form.Controls.Add($layout)

function Add-PanelLabel([string]$Text, [int]$Row, [single]$Size = 10) {
    $label = [Windows.Forms.Label]::new()
    $label.Text = $Text
    $label.Dock = 'Fill'
    $label.TextAlign = 'MiddleLeft'
    $label.Font = [Drawing.Font]::new('Segoe UI', $Size)
    $layout.Controls.Add($label, 0, $Row)
    return $label
}
$null = Add-PanelLabel 'DictaLab / Production' 0 21
$null = Add-PanelLabel "Aurora · Điều khiển local qua SSH · EC2 $HostName" 1
$script:databaseLabel = Add-PanelLabel 'Database: Đang đọc trạng thái…' 2 17
$script:applicationLabel = Add-PanelLabel 'Ứng dụng: Chưa xác minh' 3 13

$buttons = [Windows.Forms.FlowLayoutPanel]::new()
$buttons.Dock = 'Fill'
$layout.Controls.Add($buttons, 0, 4)
function Add-PanelButton([string]$Text, [Drawing.Color]$Color) {
    $button = [Windows.Forms.Button]::new()
    $button.Text = $Text
    $button.AutoSize = $true
    $button.Height = 38
    $button.Padding = [Windows.Forms.Padding]::new(12, 4, 12, 4)
    $button.FlatStyle = 'Flat'
    $button.ForeColor = $Color
    $button.Margin = [Windows.Forms.Padding]::new(0, 0, 12, 0)
    $buttons.Controls.Add($button)
    return $button
}
$script:startButton = Add-PanelButton '&Bật Aurora' $green
$script:stopButton = Add-PanelButton '&Tắt Aurora' $coral
$script:refreshButton = Add-PanelButton '&Làm mới' $ink
$openButton = Add-PanelButton '&Mở ứng dụng' $ink
$script:messageLabel = Add-PanelLabel 'Trạng thái tự cập nhật mỗi 20 giây. Bật/tắt có thể mất 5–15 phút.' 5
$script:details = [Windows.Forms.TextBox]::new()
$details.Multiline = $true
$details.ReadOnly = $true
$details.ScrollBars = 'Vertical'
$details.Dock = 'Fill'
$details.BackColor = [Drawing.ColorTranslator]::FromHtml('#fffdf6')
$details.Font = [Drawing.Font]::new('Consolas', 10)
$layout.Controls.Add($details, 0, 6)
$script:checkLabel = Add-PanelLabel 'Chưa có trạng thái. Quyền AWS và khóa bí mật không được lưu trong bảng này.' 7 9

function Update-PanelButtons {
    $busy = $script:jobs.ContainsKey('operation')
    $fresh = $script:statusValid -and ([datetime]::UtcNow - $script:lastCheck).TotalSeconds -lt 60
    $stable = $fresh -and $script:lastStatus.aurora -in @('available', 'stopped')
    $script:startButton.Enabled = -not $busy -and $stable -and
        ($script:lastStatus.aurora -eq 'stopped' -or $script:lastStatus.maintenance)
    $script:stopButton.Enabled = -not $busy -and $stable -and $script:lastStatus.aurora -eq 'available'
    $script:refreshButton.Enabled = -not $script:jobs.ContainsKey('status')
}

function Start-PanelRequest([ValidateSet('start', 'stop', 'status')][string]$Action) {
    $key = if ($Action -eq 'status') { 'status' } else { 'operation' }
    if ($script:jobs.ContainsKey($key)) { return }
    try {
        $job = Start-PanelProcess @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'ec2-services.ps1'),
            '-Action', $Action, '-HostName', $HostName, '-IdentityFile', $IdentityFile)
        $job.Action = $Action
        $script:jobs[$key] = $job
        if ($key -eq 'operation') {
            $script:messageLabel.ForeColor = $ink
            $script:messageLabel.Text = if ($Action -eq 'start') { 'Đang bật Aurora và kiểm tra ứng dụng… Giữ cửa sổ này mở.' } else { 'Đang bảo trì, sao lưu và tắt Aurora… Giữ cửa sổ này mở.' }
        }
    } catch {
        $script:statusValid = $false
        $script:connectionError = $true
        $script:messageLabel.ForeColor = $coral
        $script:messageLabel.Text = 'Không chạy được SSH. Kiểm tra PowerShell 7, khóa SSH và kết nối mạng.'
    }
    Update-PanelButtons
}

$startButton.Add_Click({ Start-PanelRequest start })
$stopButton.Add_Click({
    $answer = [Windows.Forms.MessageBox]::Show($form,
        "Website sẽ vào bảo trì. Hệ thống dừng ghi, sao lưu có kiểm tra rồi tắt Aurora.`n`nTiếp tục?",
        'Tắt Aurora', 'YesNo', 'Warning', 'Button2')
    if ($answer -eq 'Yes') { Start-PanelRequest stop }
})
$refreshButton.Add_Click({ Start-PanelRequest status })
$openButton.Add_Click({ Start-Process 'https://dictalab.hoangptkdhy.workers.dev/' })

$timer = [Windows.Forms.Timer]::new()
$timer.Interval = 300
$poll = {
    foreach ($key in @($script:jobs.Keys)) {
        $job = $script:jobs[$key]
        if ($key -eq 'status' -and -not $job.Process.HasExited -and
            ([datetime]::UtcNow - $job.Started).TotalSeconds -gt 45) {
            $job.Process.Kill($true)
        }
        if (-not $job.Process.HasExited -or -not $job.Output.IsCompleted -or -not $job.Error.IsCompleted) { continue }
        try {
            $result = Read-PanelResult $job $job.Action
            if ($key -eq 'status') {
                # Discard a status read started before the latest operation completed.
                if ($job.Started -lt $script:statusAfter) {
                    $script:nextCheck = [datetime]::MinValue
                    continue
                }
                $script:lastStatus = $result
                $script:lastCheck = [datetime]::UtcNow
                $script:statusValid = $true
                if ($script:connectionError) {
                    $messageLabel.ForeColor = $ink
                    $messageLabel.Text = if ($script:jobs.ContainsKey('operation')) { 'Lệnh bật/tắt đang chạy… Giữ cửa sổ này mở.' } else { 'Trạng thái tự cập nhật mỗi 20 giây. Bật/tắt có thể mất 5–15 phút.' }
                    $script:connectionError = $false
                }
                $dbText = switch ($result.aurora) {
                    available { 'Sẵn sàng' }; stopped { 'Đã tắt' }; starting { 'Đang bật…' }; stopping { 'Đang tắt…' }; default { $result.aurora }
                }
                $databaseLabel.Text = "Database: $dbText"
                $form.Text = "DictaLab | Aurora · $dbText"
                $databaseLabel.ForeColor = if ($result.aurora -eq 'available') { $green } else { $ink }
                $allActive = @($result.serviceStates.Values | Where-Object { $_ -ne 'active' }).Count -eq 0
                $applicationLabel.Text = if ($result.maintenance) { 'Ứng dụng: Đang bảo trì' } elseif ($allActive -and $result.aurora -eq 'available') { 'Ứng dụng: Đang hoạt động' } else { 'Ứng dụng: Cần kiểm tra dịch vụ' }
                $serviceText = ($result.serviceStates.GetEnumerator() | Sort-Object Name | ForEach-Object { '{0,-29} {1}' -f $_.Name, $_.Value }) -join "`r`n"
                $details.Text = "$serviceText`r`n`r`nRAM EC2 (MiB)`r`n$($result.memory -replace '\r?\n', "`r`n")"
                $checkLabel.Text = 'Đã xác minh lúc ' + (Get-Date -Format 'HH:mm:ss') + ' · Trạng thái không kết nối SQL.'
            } else {
                $messageLabel.ForeColor = $green
                $messageLabel.Text = if ($job.Action -eq 'start') { 'Đã bật. Aurora và ứng dụng đã qua kiểm tra sẵn sàng.' } else { 'Đã tắt Aurora. Website đang bảo trì.' }
                $script:statusValid = $false
                $script:statusAfter = [datetime]::UtcNow
                $script:nextCheck = [datetime]::MinValue
            }
        } catch {
            $script:statusValid = $false
            $script:connectionError = $key -eq 'status'
            if ($key -eq 'status') { $form.Text = 'DictaLab | Aurora · Chưa xác minh' }
            $checkLabel.Text = 'Chưa xác minh trạng thái hiện tại. Thông tin bên trên có thể đã cũ.'
            $messageLabel.ForeColor = $coral
            $messageLabel.Text = if ($key -eq 'status') { 'Không đọc được trạng thái. Kiểm tra mạng, khóa SSH và IP được phép ở cổng 22.' } else { 'Lệnh chưa được xác nhận. Làm mới trạng thái và kiểm tra log EC2 trước khi thử lại.' }
            if ($key -eq 'operation') {
                $script:statusAfter = [datetime]::UtcNow
                $script:nextCheck = [datetime]::MinValue
            }
        } finally {
            $job.Process.Dispose()
            $script:jobs.Remove($key)
        }
    }
    if (-not $script:jobs.ContainsKey('status') -and [datetime]::UtcNow -ge $script:nextCheck) {
        $script:nextCheck = [datetime]::UtcNow.AddSeconds(20)
        Start-PanelRequest status
    }
    Update-PanelButtons
}
$timer.Add_Tick($poll)
$form.Add_FormClosing({
    param($sender, $eventArgs)
    if ($script:jobs.ContainsKey('operation')) {
        $eventArgs.Cancel = $true
        [Windows.Forms.MessageBox]::Show($form, 'Lệnh bật/tắt đang chạy. Hãy chờ hoàn tất để tránh ngắt quy trình sao lưu hoặc khởi động.', 'Đang xử lý', 'OK', 'Information') | Out-Null
    }
})
Update-PanelButtons
if ($SelfTest) {
    # Exercise the actual timer handler with fixture processes; never contact EC2.
    $script:nextCheck = [datetime]::MaxValue
    $script:jobs.status = New-TestJob $fixture status
    & $poll
    Assert ($stopButton.Enabled -and -not $startButton.Enabled) 'Online controls are incorrect.'
    $script:jobs.operation = @{}
    Update-PanelButtons
    Assert (-not $stopButton.Enabled -and -not $startButton.Enabled) 'Concurrent actions must be disabled.'
    $script:jobs.Remove('operation')
    $script:jobs.status = New-TestJob ($fixture.Replace('available', 'stopped').Replace('false', 'true')) status
    & $poll
    Assert ($startButton.Enabled -and -not $stopButton.Enabled) 'Maintenance controls are incorrect.'
    $script:lastCheck = [datetime]::UtcNow.AddMinutes(-2)
    Update-PanelButtons
    Assert (-not $stopButton.Enabled -and -not $startButton.Enabled) 'Stale status must disable actions.'
    $script:jobs.status = New-TestJob '{}' status
    & $poll
    Assert (-not $script:statusValid -and -not $stopButton.Enabled -and -not $startButton.Enabled) 'Failed status must disable actions.'
    $script:jobs.status = New-TestJob $fixture status
    & $poll
    Assert (-not $script:connectionError -and $messageLabel.ForeColor -eq $ink) 'Recovered status must clear the connection error.'
    $form.Show()
    $form.PerformLayout()
    Assert (($buttons.Controls | Measure-Object Right -Maximum).Maximum -le $buttons.ClientSize.Width) 'Buttons are clipped.'
    $image = [Drawing.Bitmap]::new($form.Width, $form.Height)
    try {
        $form.DrawToBitmap($image, [Drawing.Rectangle]::new(0, 0, $form.Width, $form.Height))
        $preview = Join-Path (Split-Path $PSScriptRoot) 'target'
        [IO.Directory]::CreateDirectory($preview) | Out-Null
        $image.Save((Join-Path $preview 'aurora-panel-test.png'))
    } finally { $image.Dispose(); $timer.Dispose(); $form.Dispose() }
    Write-Output 'PASS: process/result validation; online, maintenance, busy, stale and failed UI states; form rendering.'
    return
}
$timer.Start()
try { [Windows.Forms.Application]::Run($form) } finally {
    $timer.Stop()
    $timer.Dispose()
    foreach ($job in $script:jobs.Values) {
        if (-not $job.Process.HasExited) { $job.Process.Kill($true) }
        $job.Process.Dispose()
    }
    $form.Dispose()
}
