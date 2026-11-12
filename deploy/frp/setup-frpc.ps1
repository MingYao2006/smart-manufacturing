# =====================================================================
# Prepare the Windows frpc folder for the SmartMES tunnel (step 4).
#
# Usage (normal PowerShell, on YOUR Windows PC):
#   powershell -ExecutionPolicy Bypass -File "D:\Java\smart-manfancturing\deploy\frp\setup-frpc.ps1"
#   powershell -ExecutionPolicy Bypass -File "D:\Java\smart-manfancturing\deploy\frp\setup-frpc.ps1" -Install
#   powershell -ExecutionPolicy Bypass -File "...\setup-frpc.ps1" -FrpDir "C:\frp" -Install
#
# What it does:
#   1. Finds frpc.exe in -FrpDir, or in ONE nested folder (zip layout).
#   2. Overwrites frpc.toml there with the repo version (real token).
#   3. Copies install-frpc-task.ps1 to the -FrpDir top level.
#   4. Verifies frpc/frps tokens match.
#   5. With -Install: runs install-frpc-task.ps1 (creates ONLOGON task
#      and starts frpc once).
#
# Plain ASCII on purpose: Windows PowerShell 5.1 reads BOM-less .ps1
# with the system ANSI code page, which mangles non-ASCII characters.
# =====================================================================

param(
    [string]$FrpDir = 'D:\frp',
    [string]$RepoDir = '',
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot is empty when invoked via a scriptblock, so fall back to
# $PSCommandPath / MyInvocation, then to the optional -RepoDir override.
$repo = $RepoDir
if (-not $repo) { $repo = $PSScriptRoot }
if (-not $repo -and $PSCommandPath) { $repo = Split-Path -Parent $PSCommandPath }
if (-not $repo -and $MyInvocation.MyCommand.Path) { $repo = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $repo) { throw "Cannot determine repo path; pass -RepoDir or run as a file." }
$frpDir = $FrpDir.TrimEnd('\')

if (-not (Test-Path -LiteralPath $frpDir)) {
    throw "Directory not found: $frpDir  -- extract the frp Windows zip there first"
}

# The release zip extracts to one nested folder. Keep that layout.
$base = $frpDir
if (-not (Test-Path -LiteralPath (Join-Path $base 'frpc.exe'))) {
    $nested = Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
              Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'frpc.exe') } |
              Select-Object -First 1
    if ($nested) { $base = $nested.FullName }
}
$frpc = Join-Path $base 'frpc.exe'
if (-not (Test-Path -LiteralPath $frpc)) {
    throw "frpc.exe not found under $frpDir (searched top level + one nested folder)"
}

$srcConf = Join-Path $repo 'frpc.toml'
$srcTask = Join-Path $repo 'install-frpc-task.ps1'
$srcSrv  = Join-Path $repo 'frps.toml'
foreach ($f in @($srcConf, $srcTask, $srcSrv)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Repo file missing: $f" }
}

$dstConf = Join-Path $base 'frpc.toml'
$dstTask = Join-Path $frpDir 'install-frpc-task.ps1'
Copy-Item -LiteralPath $srcConf -Destination $dstConf -Force
Copy-Item -LiteralPath $srcTask -Destination $dstTask -Force

# Extract auth.token from both TOMLs and compare (no hard-coded secret).
function Get-Token([string]$path) {
    $m = Select-String -LiteralPath $path -Pattern '^\s*auth\.token\s*=\s*"([^"]+)"'
    if (-not $m) { throw "auth.token not found in $path" }
    return $m[0].Matches[0].Groups[1].Value
}
$srvTok = Get-Token $srcSrv
$cliTok = Get-Token $dstConf
if ($srvTok -ne $cliTok) {
    throw "TOKEN MISMATCH: frps.toml and frpc.toml differ. Fix both to the same value."
}

Write-Output "frpc.exe      : $frpc"
Write-Output "frpc.toml     : $dstConf"
Write-Output "installer     : $dstTask"
Write-Output "auth.token    : $cliTok  (frps.toml MATCH)"
Write-Output ""
Write-Output "Step 4 prepared. Prerequisite: server TCP 7000 reachable."

if ($Install) {
    Write-Output "=== Running install-frpc-task.ps1 ==="
    & $dstTask -FrpDir $frpDir
} else {
    Write-Output "Next: create the logon task and start frpc once:"
    Write-Output ('powershell -ExecutionPolicy Bypass -File "' + $dstTask + '"')
    Write-Output "(or re-run this script with -Install)"
}
