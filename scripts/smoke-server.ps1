<#
.SYNOPSIS
  Starts each node's dev server, waits for the mod's ready line, then stops it.
.DESCRIPTION
  Proves "a dev server starts with the mod loaded" per Stonecutter node. For each node it accepts the EULA in
  versions/<node>/run, runs :<node>:runServer and watches the output for "MegaBlockBot ready (NeoForge on Minecraft
  <node>)". Afterwards it kills Gradle's process tree, then any java.exe whose command line names this repo's
  versions/<node>/ folder or runs :<node>:runServer (or :runGameTestServer) through this repo's wrapper. Matching by command line, not by a
  remembered PID, finds a server JVM orphaned by an early Gradle exit and never touches a process that reused a PID.
  The same sweep runs before each node starts, so a server left by an interrupted run cannot hold the port. The sweep
  lives in scripts/lib/MbbNodes.psm1, shared with gametest.ps1. The smoke runs classes from build/classes, not the
  shipped jar.
  Logs go to build/smoke/<node>.log. Exit code is the number of failed nodes.
.EXAMPLE
  ./scripts/smoke-server.ps1
  ./scripts/smoke-server.ps1 -Nodes 26.3,1.21.1 -TimeoutSec 900
#>
[CmdletBinding()]
param(
    # Defaults to every node under versions/. Accepts a list or one comma-separated string (pwsh -File passes that).
    [string[]]$Nodes,
    [int]$TimeoutSec = 600
)

$root = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $PSScriptRoot 'lib/MbbNodes.psm1') -Force
$known = Get-KnownNodes $root
if (-not $PSBoundParameters.ContainsKey('Nodes')) { $Nodes = $known }
$Nodes = @($Nodes | ForEach-Object { $_ -split ',' } | ForEach-Object Trim | Where-Object { $_ } | Select-Object -Unique)
if (-not $Nodes) { throw "No nodes selected. Known: $($known -join ', ')" }
$unknown = $Nodes | Where-Object { $_ -notin $known }
if ($unknown) { throw "Unknown node(s): $($unknown -join ', '). Known: $($known -join ', ')" }

$logDir = Join-Path $root 'build/smoke'
New-Item -ItemType Directory -Force $logDir | Out-Null
$failed = @()

foreach ($node in $Nodes) {
    $runDir = Join-Path $root "versions/$node/run"
    New-Item -ItemType Directory -Force $runDir | Out-Null
    Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true'

    $log = Join-Path $logDir "$node.log"
    $err = Join-Path $logDir "$node.err.log"
    $stale = Stop-NodeJvms -Root $root -Node $node
    if ($stale) { Write-Host "[$node] could not stop a leftover server JVM: $($stale -join ', ')"; $failed += $node; continue }
    Remove-Item $log, $err -ErrorAction SilentlyContinue
    Write-Host "[$node] starting dev server"
    try {
        $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
            -ArgumentList ":${node}:runServer", '--no-daemon', '--console=plain' `
            -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError $err `
            -NoNewWindow -PassThru -ErrorAction Stop
    } catch {
        # Usually a log still locked by a process from an earlier, interrupted run.
        Write-Host "[$node] failed to start: $($_.Exception.Message)"
        $failed += $node
        continue
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $result = 'timeout'
    try {
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            # Sample the exit state before reading the logs, so output written just before an exit is still seen.
            $exited = $proc.HasExited
            $text = (Get-Content $log -Raw -ErrorAction SilentlyContinue) + (Get-Content $err -Raw -ErrorAction SilentlyContinue)
            if ($text -match 'MegaBlockBot ready \((.+)\)') {
                $result = if ($Matches[1] -eq "NeoForge on Minecraft $node") { "ok: $($Matches[1])" } else { "wrong version: $($Matches[1])" }
                break
            }
            if ($text -match 'BUILD FAILED|Crash report saved|Failed to start the minecraft server|FAILED TO BIND') { $result = 'failed'; break }
            if ($exited) { $result = 'exited early'; break }
        }
    } finally {
        $survivors = Stop-GradleRun -Process $proc -Root $root -Node $node
        if ($survivors) { $result = "$result; server JVMs survived the kill: $($survivors -join ', ')" }
    }

    Write-Host "[$node] $result"
    if (-not $result.StartsWith('ok') -or $result.Contains('survived')) { $failed += $node; Write-Host "[$node] see $log" }
}

if ($failed.Count) { Write-Host "FAILED: $($failed -join ', ')" } else { Write-Host 'All nodes loaded the mod.' }
exit $failed.Count
