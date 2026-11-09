<#
.SYNOPSIS
  Starts each node's dev server, waits for the mod's ready line, then stops it.
.DESCRIPTION
  Proves "a dev server starts with the mod loaded" per Stonecutter node. For each node it accepts the EULA in
  versions/<node>/run, runs :<node>:runServer and watches the output for "MegaBlockBot ready (NeoForge on Minecraft
  <node>)". The server's process tree is snapshotted on every poll and killed by PID afterwards, so the game JVM
  dies even if the Gradle client exits first. The smoke runs classes from build/classes, not the shipped jar.
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
$known = Get-ChildItem (Join-Path $root 'versions') -Directory | ForEach-Object Name
if (-not $Nodes) { $Nodes = $known }
$Nodes = @($Nodes | ForEach-Object { $_ -split ',' } | ForEach-Object Trim | Where-Object { $_ })
$logDir = Join-Path $root 'build/smoke'
New-Item -ItemType Directory -Force $logDir | Out-Null
$failed = @()

$unknown = $Nodes | Where-Object { $_ -notin $known }
if ($unknown) { throw "Unknown node(s): $($unknown -join ', '). Known: $($known -join ', ')" }

function Get-Descendants([int]$ParentId) {
    $all = Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId
    $ids = @($ParentId); $frontier = @($ParentId)
    while ($frontier.Count) {
        $frontier = @($all | Where-Object { $_.ParentProcessId -in $frontier } | ForEach-Object { [int]$_.ProcessId })
        $ids += $frontier
    }
    return $ids
}

foreach ($node in $Nodes) {
    $runDir = Join-Path $root "versions/$node/run"
    New-Item -ItemType Directory -Force $runDir | Out-Null
    Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true'

    $log = Join-Path $logDir "$node.log"
    $err = Join-Path $logDir "$node.err.log"
    Remove-Item $log, $err -ErrorAction SilentlyContinue
    Write-Host "[$node] starting dev server"
    $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
        -ArgumentList ":${node}:runServer", '--no-daemon', '--console=plain' `
        -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError $err `
        -NoNewWindow -PassThru

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $result = 'timeout'
    $tree = @($proc.Id)
    try {
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            # Sample the exit state before reading the logs, so output written just before an exit is still seen.
            $exited = $proc.HasExited
            if (-not $exited) { $tree = @($tree + (Get-Descendants $proc.Id) | Select-Object -Unique) }
            $text = (Get-Content $log -Raw -ErrorAction SilentlyContinue) + (Get-Content $err -Raw -ErrorAction SilentlyContinue)
            if ($text -match 'MegaBlockBot ready \((.+)\)') {
                $result = if ($Matches[1] -eq "NeoForge on Minecraft $node") { "ok: $($Matches[1])" } else { "wrong version: $($Matches[1])" }
                break
            }
            if ($text -match 'BUILD FAILED|Crash report saved|Failed to start the minecraft server') { $result = 'failed'; break }
            if ($exited) { $result = 'exited early'; break }
        }
    } finally {
        # Kill the snapshot by PID (children first), not just the live tree: a game JVM orphaned by an early
        # Gradle exit is still in the snapshot.
        [array]::Reverse($tree)
        foreach ($id in $tree) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        $proc.WaitForExit(30000) | Out-Null
        Start-Sleep -Seconds 2
        $survivors = @($tree | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
        if ($survivors) { $result = "$result; processes survived the kill: $($survivors -join ', ')" }
    }

    Write-Host "[$node] $result"
    if (-not $result.StartsWith('ok') -or $result.Contains('survived')) { $failed += $node; Write-Host "[$node] see $log" }
}

if ($failed.Count) { Write-Host "FAILED: $($failed -join ', ')" } else { Write-Host 'All nodes loaded the mod.' }
exit $failed.Count
