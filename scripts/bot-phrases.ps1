<#
.SYNOPSIS
  Sends a list of loosely worded messages to a bot on a running dev server and prints what each one became.

.DESCRIPTION
  For checking the language layer (issue 20): each line of -File (or each of -Phrases) is given to the bot with
  `aiplayer goal <bot> <words>`, the bot's answers in the server log are collected until they stop coming or
  -WaitSec passes, and whatever goal was set is cancelled again before the next line, so nothing is carried out.
  Prints one block per phrase: the words, then every line the bot said. Lines of -File that are empty or start
  with # are skipped. The bot is spawned if it is not online. Use a bot of its own for this (the default is
  Bot2), not one that is busy with a goal.
  Exit codes: 0 every phrase got an answer, 1 one or more got none, 3 the server is not up.

.EXAMPLE
  pwsh -NoProfile -File scripts/bot-phrases.ps1 -Node 1.21.1 -File docs/evidence/loose-phrases.txt
#>
param(
    [Parameter(Mandatory)][string]$Node,
    [string]$Bot = 'Bot2',
    [string]$File,
    [string[]]$Phrases,
    [int]$WaitSec = 45,
    [int]$QuietSec = 4
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'bot-server.ps1'
$log = Join-Path (Split-Path $PSScriptRoot -Parent) "build/bot-server/$Node.log"

function Send([string]$Command) {
    # In a process of its own: the server script writes straight to the console, which a pipeline cannot hold back.
    $out = pwsh -NoProfile -File $server cmd -Node $Node -Command $Command 2>&1 | Select-Object -Last 1
    return "$out"
}

$null = pwsh -NoProfile -File $server status -Node $Node 2>&1
if ($LASTEXITCODE -ne 0) { [Console]::Error.WriteLine("bot-phrases: no dev server is running for $Node"); exit 3 }

$lines = @()
if ($File) { $lines += Get-Content -LiteralPath $File }
if ($Phrases) { $lines += $Phrases }
$lines = @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
if ($lines.Count -eq 0) { [Console]::Error.WriteLine('bot-phrases: nothing to send'); exit 1 }

$null = Send "aiplayer spawn $Bot"
$silent = 0
foreach ($phrase in $lines) {
    $null = Send "aiplayer cancel $Bot all"
    Start-Sleep -Milliseconds 500
    $before = @(Get-Content -LiteralPath $log).Count
    $null = Send "aiplayer goal $Bot $phrase"
    # The bot answers in the log: a model may take several seconds, and may say more than one thing.
    $said = @()
    $deadline = (Get-Date).AddSeconds($WaitSec)
    $lastNew = Get-Date
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $now = @(Get-Content -LiteralPath $log | Select-Object -Skip $before |
            Where-Object { $_ -match "<$Bot> " -and $_ -notmatch 'Rcon:' } |
            ForEach-Object { ($_ -replace "^.*?<$Bot> ", '') })
        if ($now.Count -gt $said.Count) { $said = $now; $lastNew = Get-Date }
        elseif ($said.Count -gt 0 -and ((Get-Date) - $lastNew).TotalSeconds -ge $QuietSec) { break }
    }
    [Console]::Out.WriteLine("> $phrase")
    if ($said.Count -eq 0) { $silent++; [Console]::Out.WriteLine('    (no answer)') }
    foreach ($line in ($said | Select-Object -Unique)) { [Console]::Out.WriteLine("    $line") }
}
$null = Send "aiplayer cancel $Bot all"
exit ($silent -gt 0 ? 1 : 0)
