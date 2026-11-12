# =====================================================================
# Register frpc as an ONLOGON scheduled task on Windows.
#
# Usage (normal PowerShell, no admin needed, any drive):
#   powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1"
#   powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1" -FrpDir "C:\frp"
#   powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1" -FrpDir "E:\tools\frp"
#
# Default dir: D:\frp. The script file itself can live anywhere.
# Accepts either frpc.toml (frp 0.5x+) or frpc.ini (frp 0.4x); TOML wins.
# If frpc.exe is not directly in -FrpDir but in ONE nested subfolder
# (e.g. the zip extracted to D:\frp\frp_0.72.0_windows_amd64\), it is found
# automatically -- no need to flatten the extracted folder.
#
# NOTE: use an ASCII-only path WITHOUT spaces (e.g. D:\frp).
#       The generated .vbs is written as ASCII, so non-ASCII or spaced
#       paths may silently fail to launch (black window is hidden).
#       Plain ASCII here keeps Windows PowerShell 5.1 happy.
# =====================================================================

param(
    [string]$FrpDir = 'D:\frp'
)

$frpDir   = $FrpDir.TrimEnd('\')

# The release zip usually extracts to one nested folder. If frpc.exe / config
# are not at the top level, look one level down so the user can keep the
# extracted folder as-is.
$base = $frpDir
if (-not (Test-Path (Join-Path $base 'frpc.exe'))) {
    $nested = Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
              Where-Object { Test-Path (Join-Path $_.FullName 'frpc.exe') } |
              Select-Object -First 1
    if ($nested) { $base = $nested.FullName }
}

$frpc = Join-Path $base 'frpc.exe'
# Support both TOML (frp 0.5x+) and INI (frp 0.4x); TOML wins.
$confToml = Join-Path $base 'frpc.toml'
$confIni  = Join-Path $base 'frpc.ini'
if (Test-Path $confToml) { $conf = $confToml }
elseif (Test-Path $confIni) { $conf = $confIni }
else { $conf = $confToml }
# VBS and log stay in $base (writable, next to frpc.exe)
$vbs      = Join-Path $base 'start-frpc.vbs'
$taskName = 'SmartMES-frpc'
# schtasks /TR: wscript.exe "D:\frp\start-frpc.vbs"
$taskRun  = 'wscript.exe "' + $vbs + '"'

if ($frpDir -match '[^\x00-\x7F]') {
    Write-Warning "Path contains non-ASCII chars ($frpDir). The VBS is ASCII; use a plain path like D:\frp."
}
if ($frpDir -match '\s') {
    Write-Warning "Path contains spaces ($frpDir). Task Scheduler may misparse it; use D:\frp."
}

if (-not (Test-Path $frpc)) { throw "Not found: $frpc  -- extract frp into $frpDir first" }
if (-not (Test-Path $conf)) { throw "Not found: $conf  -- put frpc.toml (or frpc.ini) next to frpc.exe first" }

# Launch hidden via VBS so no console window pops up on every logon
@"
Set ws = CreateObject("Wscript.Shell")
ws.Run """$frpc"" -c ""$conf""", 0, False
"@ | Set-Content -Path $vbs -Encoding ASCII

# Re-runnable: drop an existing task with the same name
schtasks /Query /TN $taskName 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { schtasks /Delete /TN $taskName /F | Out-Null }

schtasks /Create `
  /TN $taskName `
  /TR $taskRun `
  /SC ONLOGON `
  /RL LIMITED `
  /F | Out-Null

Write-Output "Scheduled task created: $taskName (starts frpc at logon)"
Write-Output "Config file: $conf"
Write-Output ""
Write-Output "=== Starting once for a test ==="
Start-Process -FilePath 'wscript.exe' -ArgumentList "`"$vbs`"" -WindowStyle Hidden
Start-Sleep -Seconds 3
Get-Process frpc -ErrorAction SilentlyContinue |
  Select-Object Id, ProcessName, StartTime | Format-Table -AutoSize
