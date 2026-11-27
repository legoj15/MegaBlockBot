<#
.SYNOPSIS
  Gives a bot one goal on a dev server that bot-server.ps1 started, follows it to its result and prints the evidence.

.DESCRIPTION
  Spawns the bot if it is not online, optionally stands it on the surface at -At "x z" (the highest block that is not
  leaves; the chunk is force-loaded for the move), optionally empties its inventory and fixes the time at day, sends
  `aiplayer goal <bot> <goal>`, then reads the server log until the goal's result line or the timeout. Prints the
  result line, the status, the inventory, the position, and every death and reflex-hold line of the run.
  Exit codes: 0 the goal ended SATISFIED, 1 it ended any other way, 2 no result before the timeout, 3 the server is not up.

.EXAMPLE
  pwsh -NoProfile -File scripts/bot-server.ps1 start -Node 1.21.1 -World run1 -Fresh -Seed 1126
  pwsh -NoProfile -File scripts/bot-goal.ps1 -Node 1.21.1 -Goal "make 1 stone pickaxe" -At "476 388" -Empty -Day
#>
param(
    [Parameter(Mandatory)][string]$Node,
    [Parameter(Mandatory)][string]$Goal,
    [string]$Bot = 'Bot1',
    [string]$At,
    [switch]$Empty,
    [switch]$Day,
    [int]$TimeoutSec = 600
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'bot-server.ps1'
$log = Join-Path (Split-Path $PSScriptRoot -Parent) "build/bot-server/$Node.log"

function Send([string]$Command) {
    # RCON drops a connection now and then on a busy machine: one retry, then whatever came back.
    foreach ($try in 1..2) {
        $out = & $server cmd -Node $Node -Command $Command 2>&1 | Select-Object -Last 1
        if ("$out" -notmatch 'RCON:') { break }
    }
    [Console]::Out.WriteLine(('{0} > {1} :: {2}' -f (Get-Date -Format 'HH:mm:ss'), $Command, "$out"))
}

& $server status -Node $Node *> $null
if ($LASTEXITCODE -ne 0) { [Console]::Error.WriteLine("bot-goal: no dev server is running for $Node"); exit 3 }

if ($Day) { Send 'time set day'; Send 'gamerule doDaylightCycle false' }
Send "aiplayer spawn $Bot"
if ($At) {
    $x, $z = $At -split '\s+'
    Send "forceload add $x $z"
    Send "execute positioned $x.5 0 $z.5 positioned over motion_blocking_no_leaves run tp $Bot ~ ~ ~"
}
if ($Empty) { Send "clear $Bot" }

$before = @(Get-Content -LiteralPath $log).Count
Send "aiplayer goal $Bot $Goal"
$result = $null
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while (-not $result -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $result = Get-Content -LiteralPath $log | Select-Object -Skip $before |
        Where-Object { $_ -match "Goal result of ${Bot}:" } | Select-Object -Last 1
}
if ($result) { [Console]::Out.WriteLine("RESULT: $result") } else { [Console]::Out.WriteLine("RESULT: none after $TimeoutSec s") }
Send "aiplayer status $Bot"
Send "data get entity $Bot Inventory"
Send "data get entity $Bot Pos"
Get-Content -LiteralPath $log | Select-Object -Skip $before |
    Where-Object { $_ -match "reflex ${Bot}:|Body $Bot died|\]: $Bot (drowned|was |fell|died|burned|suffocated|starved|blew|hit )" } |
    ForEach-Object { [Console]::Out.WriteLine($_) }
if ($At) { Send "forceload remove $x $z" }

if (-not $result) { exit 2 }
if ($result -match 'status=SATISFIED') { exit 0 }
exit 1
