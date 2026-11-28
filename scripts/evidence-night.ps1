<#
.SYNOPSIS
  The evidence run for a night outdoors (issue 21): a fresh dev world, a bot with an empty pack and a goal that
  outlasts the night, dusk a few seconds away, and the goal followed to its result.

.DESCRIPTION
  Starts a fresh world for the node (seed 4242), sets the difficulty (the dev server's default is easy), spawns the
  bot with an empty inventory at -BotAt, sets the clock just before dusk with the daylight cycle running, and gives
  the goal. Nothing is supplied: what the body has at dusk is what it gathered in the seconds before it.
  bot-goal.ps1 follows the goal and prints its result, every death and every reflex hold of the run; this script
  then prints the lines the night reflex wrote (the digging in, the shelter going up and coming down) and the clock.
  The run proves a night survived when the result is SATISFIED after a morning, with no death line.
  The server is left running. Exit code: bot-goal.ps1's (0 satisfied, 1 any other result, 2 no result in time).

.EXAMPLE
  pwsh -NoProfile -File scripts/evidence-night.ps1 -Node 1.21.1
  pwsh -NoProfile -File scripts/evidence-night.ps1 -Node 1.21.1 -BotAt "372 217" -Goal "get 48 oak log"
#>
param(
    [Parameter(Mandatory)][string]$Node,
    [string]$World = 'e2e_night',
    [string]$Bot = 'Bot1',
    [string]$BotAt = '80 -350',
    [string]$Goal = 'get 48 spruce log',
    [ValidateSet('easy', 'normal', 'hard')][string]$Difficulty = 'normal',
    [int]$TimeoutSec = 2400
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'bot-server.ps1'
$goalScript = Join-Path $PSScriptRoot 'bot-goal.ps1'
$log = Join-Path (Split-Path $PSScriptRoot -Parent) "build/bot-server/$Node.log"

function Send([string]$Command) {
    $out = pwsh -NoProfile -File $server cmd -Node $Node -Command $Command 2>&1 | Select-Object -Last 1
    [Console]::Out.WriteLine(('{0} > {1} :: {2}' -f (Get-Date -Format 'HH:mm:ss'), $Command, "$out"))
    return "$out"
}

pwsh -NoProfile -File $server start -Node $Node -World $World -Fresh -Seed 4242 | Select-Object -Last 1
$null = Send "difficulty $Difficulty"
$null = Send 'gamerule doDaylightCycle true'
$null = Send "aiplayer spawn $Bot"
$x, $z = $BotAt -split '\s+'
$null = Send "forceload add $x $z"
Start-Sleep -Seconds 3
$null = Send "execute positioned $x.5 0 $z.5 positioned over motion_blocking_no_leaves run tp $Bot ~ ~ ~"
$null = Send "clear $Bot"
# Dusk, by the night reflex (ReflexConstants.NIGHT_BEGIN, 12000), is 600 ticks off: the goal has started and the pack is as good as empty.
$null = Send 'time set 11400'
$before = @(Get-Content -LiteralPath $log).Count

& $goalScript -Node $Node -Bot $Bot -Goal $Goal -TimeoutSec $TimeoutSec
$code = $LASTEXITCODE

[Console]::Out.WriteLine('--- the night reflex ---')
Get-Content -LiteralPath $log | Select-Object -Skip $before |
    Where-Object { $_ -match 'night (digging|shelter)|hold (started|ended) .*night|was slain|was shot|died|drowned|blew up' } |
    ForEach-Object { [Console]::Out.WriteLine($_.Substring(0, [Math]::Min(300, $_.Length))) }
$null = Send 'time query daytime'
$null = Send 'time query day'
exit $code
