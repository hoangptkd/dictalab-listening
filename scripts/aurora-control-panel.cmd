@echo off
setlocal
set "PANEL_PWSH=pwsh.exe"
where pwsh.exe >nul 2>nul
if errorlevel 1 set "PANEL_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not exist "%PANEL_PWSH%" (
  where "%PANEL_PWSH%" >nul 2>nul
  if errorlevel 1 (
    echo PowerShell 7 is required. Install it, then open this launcher again.
    pause
    exit /b 1
  )
)
start "" "%PANEL_PWSH%" -NoProfile -STA -WindowStyle Hidden -File "%~dp0aurora-control-panel.ps1"
