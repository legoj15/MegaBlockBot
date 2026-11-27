<#
.SYNOPSIS
  Runs a bot node's GameTest server and judges the result from the server's own log.
.DESCRIPTION
  Runs :<node>:runGameTestServer, then reads versions/<node>/run-gameTestServer/logs/latest.log. Exits 0 only when
  that log shows "All N required tests passed" with N > 0 and no "N required tests failed" line, and Gradle itself
  exited 0. The exit code alone proves nothing: on 1.21.1 a startup failure (a mod that did not load, a bad datapack)
  ends the server with exit code 0 and no tests run, so the log is the evidence. latest.log from an earlier run is
  deleted first so it cannot be mistaken for this one.
  On failure it prints the failed tests and their messages (or the log's tail when no test result was reached).
  Afterwards it kills Gradle's process tree and any server JVM the run left behind, by command line (see
  scripts/lib/MbbNodes.psm1, shared with smoke-server.ps1); the same sweep runs before the run starts.
  Gradle's own output goes to build/gametest/<node>.log. Only a bot node (bot_enabled=true) has the run.
.EXAMPLE
  ./scripts/gametest.ps1
  ./scripts/gametest.ps1 -Node 1.21.1 -TimeoutSec 1200
  ./scripts/gametest.ps1 -Only KillHarvestGameTests,FishGameTests
.PARAMETER Only
  Test class names, comma separated: only their tests are registered. For a fix loop; "All N required tests passed"
  then counts that subset, and the script says so. A change is not verified until a run without -Only passes.
#>
[CmdletBinding()]
param(
    [string]$Node = '1.21.1',
    [int]$TimeoutSec = 900,
    [string]$Only = ''
)

$root = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $PSScriptRoot 'lib/MbbNodes.psm1') -Force
$known = Get-KnownNodes $root
if ($Node -notin $known) { throw "Unknown node '$Node'. Known: $($known -join ', ')" }

$serverLog = Join-Path $root "versions/$Node/run-gameTestServer/logs/latest.log"
$logDir = Join-Path $root 'build/gametest'
New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir "$Node.log"
$err = Join-Path $logDir "$Node.err.log"

$stale = Stop-NodeJvms -Root $root -Node $Node
if ($stale) { Write-Host "[$Node] could not stop a leftover server JVM: $($stale -join ', ')"; exit 1 }
Remove-Item $log, $err, $serverLog -ErrorAction SilentlyContinue

$gradleArgs = @(":${Node}:runGameTestServer", '--no-daemon', '--console=plain')
if ($Only) {
    $gradleArgs += "-Dmbb.gametest.only=$Only"
    Write-Host "[$Node] SUBSET RUN: only $Only"
}
Write-Host "[$Node] running the GameTest server"
try {
    $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
        -ArgumentList $gradleArgs `
        -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError $err `
        -NoNewWindow -PassThru -ErrorAction Stop
} catch {
    # Usually a log still locked by a process from an earlier, interrupted run.
    Write-Host "[$Node] failed to start: $($_.Exception.Message)"
    exit 1
}

$timedOut = $false
try {
    if (-not $proc.WaitForExit($TimeoutSec * 1000)) { $timedOut = $true }
} finally {
    $survivors = Stop-GradleRun -Process $proc -Root $root -Node $Node
}
$gradleExit = if ($timedOut) { $null } else { $proc.ExitCode }

$problems = @()
if ($timedOut) { $problems += "timed out after $TimeoutSec s" }
elseif ($gradleExit -ne 0) { $problems += "Gradle exited with code $gradleExit" }
if ($survivors) { $problems += "server JVMs survived the kill: $($survivors -join ', ')" }

$lines = if (Test-Path $serverLog) { @(Get-Content $serverLog) } else { @() }
$passed = $null
$failedRequired = $null
$failedNames = @()
$messages = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -match 'All (\d+) required tests passed') { $passed = [int]$Matches[1] }
    elseif ($line -match '(\d+) required tests failed') {
        $failedRequired = [int]$Matches[1]
        # The failed names follow as "   - <name>" lines.
        for ($j = $i + 1; $j -lt $lines.Count -and $lines[$j] -match '\]:\s+- (.+)$'; $j++) { $failedNames += $Matches[1] }
    }
    elseif ($line -match '\]: (\S+) failed at [^!]*! (.*)$') { $messages += "$($Matches[1]): $($Matches[2])" }
}
$registered = @($lines | ForEach-Object { if ($_ -match 'Registered GameTest (\S+)') { $Matches[1] } })

if (-not $lines.Count) { $problems += "no server log at $serverLog" }
elseif ($null -ne $failedRequired) { $problems += "$failedRequired required tests failed" }
elseif ($null -eq $passed) { $problems += 'the log has no test result (the server did not get as far as running tests)' }
elseif ($passed -le 0) { $problems += "'All $passed required tests passed' proves nothing: no test ran" }

if ($problems) {
    Write-Host "[$Node] FAILED: $($problems -join '; ')"
    foreach ($m in $messages) { Write-Host "  failed $m" }
    foreach ($n in $failedNames) { Write-Host "  failed test: $n" }
    if ($null -eq $passed -and $null -eq $failedRequired) {
        if ($lines.Count) { Write-Host "  last lines of ${serverLog}:"; $lines | Select-Object -Last 30 | ForEach-Object { Write-Host "    $_" } }
        else { Write-Host "  see $log" }
    }
    exit 1
}

Write-Host "[$Node] All $passed required tests passed. Registered: $($registered -join ', ')"
exit 0
