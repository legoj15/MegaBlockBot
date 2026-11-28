<#
.SYNOPSIS
  The evidence run for the assembly table (issue 18): a fresh BuildCraft dev world, a named chest of supplies, then
  "make 1 goggles" followed to its result.

.DESCRIPTION
  Goggles need a redstone chipset, which only BuildCraft's assembly table makes, and the table makes nothing
  without a laser and an engine behind it. Starts a fresh world for the node with the BuildCraft jar (seed 4242),
  fixes daylight, spawns the bot with an empty inventory at -BotAt, and puts a chest beside it, named to the bot
  with "use chest at", holding what the run is NOT about: the rig (assembly table, laser, Stirling engine, lever),
  coal for the engine, and the raw materials (redstone, string, iron ingots, glass panes).
  What the bot does itself: plans the chain (the chipset through the assembly table, with the rig brought along and
  the fuel counted), takes what it needs, sets the rig up, fuels the engine, queues the recipe, waits about nine
  minutes for 10,000 MJ, takes the chipset, takes the rig down, and crafts the goggles.
  The server is left running. Exit code: bot-goal.ps1's (0 satisfied, 1 any other result, 2 no result in time).

.EXAMPLE
  pwsh -NoProfile -File scripts/evidence-goggles.ps1 -Node 1.21.1
#>
param(
    [Parameter(Mandatory)][string]$Node,
    [string]$World = 'e2e_goggles',
    [string]$Bot = 'Bot1',
    [string]$BotAt = '80 -350',
    [string]$BuildCraftJar = 'E:/GitHub/BuildCraft/build/libs/2026.5.0/BuildCraftRedux-2026.5.0+mc1.21.1.jar',
    [string]$Goal = 'make 1 goggles',
    [int]$TimeoutSec = 2400
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'bot-server.ps1'
$goalScript = Join-Path $PSScriptRoot 'bot-goal.ps1'

function Send([string]$Command) {
    $out = pwsh -NoProfile -File $server cmd -Node $Node -Command $Command 2>&1 | Select-Object -Last 1
    [Console]::Out.WriteLine(('{0} > {1} :: {2}' -f (Get-Date -Format 'HH:mm:ss'), $Command, "$out"))
    return "$out"
}

pwsh -NoProfile -File $server start -Node $Node -World $World -Fresh -Seed 4242 -Mods $BuildCraftJar | Select-Object -Last 1
$null = Send 'time set day'
$null = Send 'gamerule doDaylightCycle false'
$null = Send "aiplayer spawn $Bot"
$x, $z = $BotAt -split '\s+'
$null = Send "forceload add $x $z"
Start-Sleep -Seconds 3
$null = Send "execute positioned $x.5 0 $z.5 positioned over motion_blocking_no_leaves run tp $Bot ~ ~ ~"
$null = Send "clear $Bot"
Start-Sleep -Seconds 2

$pos = Send "data get entity $Bot Pos"
if ($pos -notmatch '\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]') { [Console]::Error.WriteLine("evidence-goggles: no position for ${Bot}: $pos"); exit 3 }
$cx = [int][Math]::Floor([double]$Matches[1]) + 2
$cy = [int][Math]::Floor([double]$Matches[2])
$cz = [int][Math]::Floor([double]$Matches[3])
$items = @(
    'buildcraftunofficial:assembly_table,1', 'buildcraftunofficial:laser,1', 'buildcraftunofficial:engine_stone,1',
    'minecraft:lever,1', 'minecraft:coal,16', 'minecraft:redstone,2', 'minecraft:string,2', 'minecraft:iron_ingot,2',
    'minecraft:glass_pane,2')
$slot = 0
$nbt = foreach ($entry in $items) {
    $id, $count = $entry -split ','
    '{{Slot:{0}b,id:"{1}",count:{2}}}' -f $slot, $id, $count
    $slot++
}
$null = Send ('setblock {0} {1} {2} minecraft:chest{{Items:[{3}]}}' -f $cx, $cy, $cz, ($nbt -join ','))

$null = Send ("aiplayer goal $Bot use chest at {0} {1} {2}" -f $cx, $cy, $cz)
foreach ($try in 1..30) {
    Start-Sleep -Seconds 2
    $known = pwsh -NoProfile -File $server cmd -Node $Node -Command "aiplayer goal $Bot chests" 2>&1 | Select-Object -Last 1
    if ("$known" -match 'last seen holding') { break }
}
[Console]::Out.WriteLine("chest: $known")

& $goalScript -Node $Node -Bot $Bot -Goal $Goal -TimeoutSec $TimeoutSec
exit $LASTEXITCODE
