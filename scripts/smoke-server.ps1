<#
.SYNOPSIS
  Starts each node's dev server, waits for the mod's ready line, then stops it.
.DESCRIPTION
  Proves "a dev server starts with the mod loaded" per Stonecutter node. For each node it accepts the EULA in
  versions/<node>/run, runs :<node>:runServer and watches the output for "MegaBlockBot ready (NeoForge on Minecraft
  <node>)". The server's process tree is recorded on every poll (PID plus creation time) and killed afterwards
  by identity, so the game JVM dies even if the Gradle client exits first, and a reused PID is never killed. The smoke runs classes from build/classes, not the shipped jar.
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
$Nodes = @($Nodes | ForEach-Object { $_ -split ',' } | ForEach-Object Trim | Where-Object { $_ } | Select-Object -Unique)
if (-not $Nodes) { throw "No nodes selected. Known: $($known -join ', ')" }
$unknown = $Nodes | Where-Object { $_ -notin $known }
if ($unknown) { throw "Unknown node(s): $($unknown -join ', '). Known: $($known -join ', ')" }

$logDir = Join-Path $root 'build/smoke'
New-Item -ItemType Directory -Force $logDir | Out-Null
$failed = @()

# The server's process tree: $tree.Order lists PIDs in discovery order, $tree.Created maps PID -> creation time.
# (Not an [ordered] map: Windows PowerShell treats an integer index on one as a position, not a key.) The creation time is the process's
# identity: Windows reuses PIDs, so a PID is only killed or counted as a survivor while its creation time still
# matches. A child is accepted only if it was created after its recorded parent, which also rules out children of
# a process that later reused the parent's PID.
function Get-Live {
    $live = @{}
    foreach ($p in Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, CreationDate) {
        $live[[int]$p.ProcessId] = $p
    }
    return $live
}

function Test-Same($live, [int]$id, [datetime]$created) {
    $p = $live[$id]
    return $p -and $p.CreationDate -eq $created
}

function Update-Tree($tree) {
    $live = Get-Live
    $frontier = @($tree.Order | Where-Object { Test-Same $live $_ $tree.Created[$_] })
    while ($frontier.Count) {
        $next = @()
        foreach ($p in $live.Values) {
            $id = [int]$p.ProcessId; $parent = [int]$p.ParentProcessId
            if (-not $tree.Created.ContainsKey($id) -and $parent -in $frontier -and $p.CreationDate -gt $tree.Created[$parent]) {
                $tree.Created[$id] = $p.CreationDate; $tree.Order.Add($id); $next += $id
            }
        }
        $frontier = $next
    }
}

foreach ($node in $Nodes) {
    $runDir = Join-Path $root "versions/$node/run"
    New-Item -ItemType Directory -Force $runDir | Out-Null
    Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true'

    $log = Join-Path $logDir "$node.log"
    $err = Join-Path $logDir "$node.err.log"
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
    $tree = @{ Order = New-Object 'System.Collections.Generic.List[int]'; Created = @{} }
    $self = (Get-Live)[[int]$proc.Id]
    $tree.Created[[int]$proc.Id] = if ($self) { $self.CreationDate } else { [datetime]::MinValue }
    $tree.Order.Add([int]$proc.Id)
    try {
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            # Sample the exit state before reading the logs, so output written just before an exit is still seen.
            $exited = $proc.HasExited
            Update-Tree $tree
            $text = (Get-Content $log -Raw -ErrorAction SilentlyContinue) + (Get-Content $err -Raw -ErrorAction SilentlyContinue)
            if ($text -match 'MegaBlockBot ready \((.+)\)') {
                $result = if ($Matches[1] -eq "NeoForge on Minecraft $node") { "ok: $($Matches[1])" } else { "wrong version: $($Matches[1])" }
                break
            }
            if ($text -match 'BUILD FAILED|Crash report saved|Failed to start the minecraft server|FAILED TO BIND') { $result = 'failed'; break }
            if ($exited) { $result = 'exited early'; break }
        }
    } finally {
        # One last walk catches a JVM spawned since the last poll; Windows keeps an orphan's parent PID, so it is
        # found even after Gradle exits. Then kill children first, by identity.
        Update-Tree $tree
        $ids = $tree.Order.ToArray(); [array]::Reverse($ids)
        $live = Get-Live
        foreach ($id in $ids) {
            if (Test-Same $live $id $tree.Created[$id]) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        }
        $proc.WaitForExit(30000) | Out-Null
        Start-Sleep -Seconds 2
        $live = Get-Live
        $survivors = @($ids | Where-Object { Test-Same $live $_ $tree.Created[$_] })
        if ($survivors) { $result = "$result; processes survived the kill: $($survivors -join ', ')" }
    }

    Write-Host "[$node] $result"
    if (-not $result.StartsWith('ok') -or $result.Contains('survived')) { $failed += $node; Write-Host "[$node] see $log" }
}

if ($failed.Count) { Write-Host "FAILED: $($failed -join ', ')" } else { Write-Host 'All nodes loaded the mod.' }
exit $failed.Count
