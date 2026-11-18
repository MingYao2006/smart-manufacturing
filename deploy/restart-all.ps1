# =====================================================================
# Restart all 6 SmartMES backend services on this Windows PC.
#
# Why this script exists:
#   `mvn clean package` fails with
#     "Failed to clean project: Failed to delete ...smart-gateway-1.0.0.jar"
#   whenever the services are still running: the JVM keeps the jar open, so
#   the clean plugin cannot delete it. You must stop the services FIRST.
#   This script does stop -> wait -> build -> start in one go.
#
# Usage (normal PowerShell, no admin needed):
#   powershell -ExecutionPolicy Bypass -File "D:\Java\smart-manfancturing\deploy\restart-all.ps1"
#
#   Skip the rebuild (only restart with existing jars):
#   ... restart-all.ps1 -NoBuild
#
# Plain ASCII on purpose: Windows PowerShell 5.1 reads BOM-less .ps1 with the
# system ANSI code page, which mangles non-ASCII characters and can break
# string literals / array syntax.
# =====================================================================

param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Continue'
$root = 'D:\Java\smart-manfancturing'
$mvn = 'D:\maven\apache-maven-3.9.14\bin\mvn.cmd'
$startScript = Join-Path $root '.tmp\startup-logs\start-all.ps1'
$ports = @(9000, 9001, 9002, 9003, 9004, 9005)

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\jdk' }
Write-Output ("JAVA_HOME = " + $env:JAVA_HOME)

# ---------------------------------------------------------------- 1) stop
function Get-ListeningPids {
    param([int[]]$Ports)
    $found = @()
    foreach ($p in $Ports) {
        $lines = netstat -ano 2>$null | Select-String (":" + $p + " ") | Select-String 'LISTENING'
        foreach ($l in $lines) {
            $tok = ($l -split ' ' | Where-Object { $_ -ne '' })
            $candidate = $tok[-1]
            if ($candidate -match '^\d+$') { $found += [int]$candidate }
        }
    }
    return ($found | Sort-Object -Unique)
}

Write-Output ''
Write-Output '--- 1/4 stopping backend services ---'
$pids = Get-ListeningPids -Ports $ports
if (-not $pids -or $pids.Count -eq 0) {
    Write-Output 'no service is listening on 9000-9005 (already stopped)'
} else {
    Write-Output ("stopping PIDs: " + ($pids -join ', '))
    foreach ($procId in $pids) {
        # NOTE: do not use $pid -- it is a read-only automatic variable.
        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
            Write-Output ("  stopped " + $procId)
        } catch {
            Write-Output ("  FAILED to stop " + $procId + " : " + $_.Exception.Message)
            Write-Output '  -> run this script from the SAME Windows session that started the services,'
            Write-Output '     or open Task Manager and end the java.exe processes, then rerun.'
        }
    }
}

# ------------------------------------------------- 2) wait for port release
Write-Output ''
Write-Output '--- 2/4 waiting for ports to be released ---'
$released = $false
for ($i = 0; $i -lt 20; $i++) {
    $left = Get-ListeningPids -Ports $ports
    if (-not $left -or $left.Count -eq 0) { $released = $true; break }
    Start-Sleep -Seconds 1
}
if ($released) {
    Write-Output 'all ports released'
} else {
    $left = Get-ListeningPids -Ports $ports
    Write-Output ("WARNING: ports still in use by " + ($left -join ', '))
    Write-Output 'The build will very likely fail on "Failed to delete ...jar".'
}

# ----------------------------------------------------------------- 3) build
if ($NoBuild) {
    Write-Output ''
    Write-Output '--- 3/4 build skipped (-NoBuild) ---'
} else {
    Write-Output ''
    Write-Output '--- 3/4 mvn clean package -DskipTests ---'
    Push-Location $root
    & $mvn -o -s .mvn/settings.xml clean package -DskipTests
    $buildExit = $LASTEXITCODE
    Pop-Location
    if ($buildExit -ne 0) {
        Write-Output ''
        Write-Output ("BUILD FAILED (exit " + $buildExit + "). Services are NOT started.")
        Write-Output 'If the error is "Failed to delete ...jar", some java process still holds it:'
        Write-Output '  Get-Process java | Select-Object Id,StartTime'
        Write-Output '  Stop-Process -Id <that id> -Force'
        exit 1
    }
    Write-Output 'build OK'
}

# ----------------------------------------------------------------- 4) start
Write-Output ''
Write-Output '--- 4/4 starting backend services ---'
if (-not (Test-Path $startScript)) {
    Write-Output ("start script not found: " + $startScript)
    exit 1
}
# Dot-invoke so the Bypass ExecutionPolicy of this process applies to it.
# If that is blocked for some reason, fall back to a nested powershell.exe.
try {
    & $startScript
} catch {
    Write-Output ("direct invocation failed: " + $_.Exception.Message)
    & powershell -ExecutionPolicy Bypass -File $startScript
}

Write-Output ''
Write-Output 'done. wait ~25s, then verify:'
Write-Output '  netstat -ano | findstr ":900"'
Write-Output '  curl http://127.0.0.1:9000/actuator/health'
Write-Output '  curl http://127.0.0.1:9004/actuator/health'
