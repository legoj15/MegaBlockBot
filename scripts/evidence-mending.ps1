<#
.SYNOPSIS
  The evidence run for the librarian reroll (issues 15 and 31): a fresh dev world, unemployed villagers in the
  village, a named chest of supplies or (-Supplies none) nothing at all, then "get 1 enchanted book with mending"
  followed to its result.

.DESCRIPTION
  Starts a fresh world for the node (seed 4242, whose nearest village to spawn is the taiga village at 80 -496),
  fixes the clock at mid-morning (3000: the hours villagers work, which is when an offer that ran out is stocked
  again), spawns the bot with an empty inventory at -BotAt, and sets up what the run is NOT about:
    - with -Supplies chest (the default), a chest two blocks from the bot holding 64 emeralds, 4 leather and 12
      sugar cane, named to the bot with "use chest at"; with -Supplies none, no chest: the emeralds are the bot's
      to earn (issue 31) and the leather and the sugar cane its to find;
    - adult villagers summoned at the village until -Jobless of them stay without a profession (the generated
      village has a free job site or two, which the first ones take). One is enough to roll a librarian; a run
      that also sells for its emeralds needs a second, since a villager that has traded keeps its job.
  What the bot does itself: takes the supplies, cuts wood, crafts the paper, books, bookshelf and lectern, finds the
  village (the game's locate in the assisted profile; by exploring in strict, so start it within sight of the
  village with -BotAt), rolls a villager with the lectern until it offers the book, brings the price and pays.
  The server is left running. Exit code: bot-goal.ps1's (0 satisfied, 1 any other result, 2 no result in time).

.EXAMPLE
  pwsh -NoProfile -File scripts/evidence-mending.ps1 -Node 1.21.1 -Profile assisted
  pwsh -NoProfile -File scripts/evidence-mending.ps1 -Node 1.21.1 -Profile strict -BotAt "80 -440"
  pwsh -NoProfile -File scripts/evidence-mending.ps1 -Node 1.21.1 -Supplies none -Jobless 2 -TimeoutSec 10800
#>
param(
    [Parameter(Mandatory)][string]$Node,
    [ValidateSet('assisted', 'strict')][string]$Profile = 'assisted',
    [ValidateSet('chest', 'none')][string]$Supplies = 'chest',
    [int]$Jobless = 1,
    [string]$World = "e2e_mending_$Profile",
    [string]$Bot = 'Bot1',
    [string]$BotAt = '80 -350',
    [int]$TimeoutSec = 3600
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'bot-server.ps1'
$goal = Join-Path $PSScriptRoot 'bot-goal.ps1'

function Send([string]$Command) {
    $out = pwsh -NoProfile -File $server cmd -Node $Node -Command $Command 2>&1 | Select-Object -Last 1
    [Console]::Out.WriteLine(('{0} > {1} :: {2}' -f (Get-Date -Format 'HH:mm:ss'), $Command, "$out"))
    return "$out"
}

pwsh -NoProfile -File $server start -Node $Node -World $World -Fresh -Seed 4242 | Select-Object -Last 1
$null = Send 'time set 3000'
$null = Send 'gamerule doDaylightCycle false'
$null = Send "aiplayer spawn $Bot"
$null = Send "aiplayer profile $Bot $Profile"
$x, $z = $BotAt -split '\s+'
$null = Send "forceload add $x $z"
Start-Sleep -Seconds 3
$null = Send "execute positioned $x.5 0 $z.5 positioned over motion_blocking_no_leaves run tp $Bot ~ ~ ~"
$null = Send "clear $Bot"
Start-Sleep -Seconds 2

# The supplies, in a chest beside the bot, at its feet's height.
if ($Supplies -eq 'chest') {
$pos = Send "data get entity $Bot Pos"
if ($pos -notmatch '\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]') { [Console]::Error.WriteLine("evidence-mending: no position for ${Bot}: $pos"); exit 3 }
$cx = [int][Math]::Floor([double]$Matches[1]) + 2
$cy = [int][Math]::Floor([double]$Matches[2])
$cz = [int][Math]::Floor([double]$Matches[3])
$null = Send ('setblock {0} {1} {2} minecraft:chest{{Items:[{{Slot:0b,id:"minecraft:emerald",count:64}},{{Slot:1b,id:"minecraft:leather",count:4}},{{Slot:2b,id:"minecraft:sugar_cane",count:12}}]}}' -f $cx, $cy, $cz)
$null = Send ('setblock {0} {1} {2} minecraft:air' -f $cx, ($cy + 1), $cz)
}

# The village: loaded, and with as many adult villagers without a profession as the run asks for.
$null = Send 'forceload add 48 -528 111 -465'
Start-Sleep -Seconds 5
$free = 0
foreach ($n in 1..10) {
    $null = Send ('execute positioned 80.5 0 -495.5 positioned over motion_blocking_no_leaves run summon minecraft:villager ~ ~ ~ {{Tags:["mbbroll{0}"]}}' -f $n)
    Start-Sleep -Seconds 45
    $job = Send ('data get entity @e[type=villager,tag=mbbroll{0},limit=1] VillagerData.profession' -f $n)
    if ($job -match 'minecraft:none') { $free++ }
    if ($free -ge $Jobless) { break }
}
if ($free -lt $Jobless) { [Console]::Error.WriteLine("evidence-mending: only $free summoned villagers stayed without a profession, $Jobless asked for"); exit 3 }

# The chest is named, and looked into before the goal is given, so the plan is made knowing what it holds.
if ($Supplies -eq 'chest') {
$null = Send ("aiplayer goal $Bot use chest at {0} {1} {2}" -f $cx, $cy, $cz)
foreach ($try in 1..30) {
    Start-Sleep -Seconds 2
    $known = pwsh -NoProfile -File $server cmd -Node $Node -Command "aiplayer goal $Bot chests" 2>&1 | Select-Object -Last 1
    if ("$known" -match 'last seen holding') { break }
}
[Console]::Out.WriteLine("chest: $known")
}

& $goal -Node $Node -Bot $Bot -Goal 'get 1 enchanted book with mending' -TimeoutSec $TimeoutSec
exit $LASTEXITCODE
