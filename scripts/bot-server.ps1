<#
.SYNOPSIS
  Headless dev-server harness for one Stonecutter node: start it, send commands, wait for a log line, stop it.
.DESCRIPTION
  Built for long evidence runs driven from a shell. `start` writes eula.txt and server.properties in
  versions/<node>/run (offline mode, RCON on, free ports, random RCON password), launches :<node>:runServer detached
  and returns once the server printed its `Done (` line and RCON answers. `cmd` runs one Minecraft command over RCON,
  `wait` follows the log for a regex, `log` prints it, `status` lists servers, `stop` shuts one down and leaves no
  java process of this checkout behind.

  State lives in build/bot-server: <node>.log (Gradle and server output) and <node>.json (ports, RCON password, world,
  start time, copied mods; the password is never printed). Server JVMs are found by command line through
  scripts/lib/BotServer.psm1 (the same sweep as smoke-server.ps1), never by a remembered PID. Run from the main checkout or
  from a git worktree; each has its own build/ and versions/, so the same node can run once per checkout and two
  nodes can run at once. `start` runs with --no-daemon so no Gradle daemon outlives the server.

  The server binds 127.0.0.1 only (server-ip, which RCON follows) unless -Property server-ip=... says otherwise.

  Exit codes.  start: 0 ready, 1 failed (log tail on stderr). cmd: 0 response printed, 3 RCON auth failure, 4 cannot
  connect or no response, 1 other errors. wait: 0 matched, 2 -FailPattern matched first, 1 timeout or the server
  exited. status: 0, or with -Node 0 when running and 1 when not. stop: 0 nothing of the node is left, 1 otherwise.
  log: 0, or 1 on an error. Usage errors exit 1.

  Patterns are .NET regexes, case-sensitive unless they start with (?i).
.PARAMETER Action
  start | cmd | wait | log | status | stop.
.PARAMETER Node
  A Stonecutter node under versions/ (unknown names are refused with the known list). Optional only for status.
.PARAMETER World
  start: level-name. Defaults to the level-name already in server.properties, else `world`. A single folder name:
  no path separator, no `..`, no characters Windows forbids in file names.
.PARAMETER Seed
  start: level-seed. Left empty (random) when omitted. Use -Seed:-5 for a negative number.
.PARAMETER Fresh
  start: delete the named world folder under the run directory first. Refuses a folder that is not a world
  (no level.dat) or is a link.
.PARAMETER Mods
  start: jar paths to copy into versions/<node>/run/mods, comma-separated. A jar copied by an earlier start and
  not listed now is removed; a jar this script did not copy (or that changed since) is never deleted or overwritten.
  The list is kept in versions/<node>/run/.bot-server-mods.json.
.PARAMETER Property
  start: key=value pairs for server.properties, overriding the defaults; space-separated words after the switch,
  or one comma-separated string (a comma followed by `word=` starts the next pair, so a value cannot contain that
  sequence). enable-rcon, rcon.port, rcon.password, level-name and level-seed are set by the script (use -World and
  -Seed). Values are written as they are, so a backslash must already be escaped as Java expects. Keys the script
  does not set stay in server.properties, so a value given on one start is still there on the next.
.PARAMETER Command
  cmd: the Minecraft command, without a leading slash.
.PARAMETER Pattern
  wait: regex that ends the wait with exit 0. log: only lines matching it.
.PARAMETER FailPattern
  wait: regex that ends the wait with exit 2 when a line matches it before -Pattern does (on a line that matches
  both, the fail pattern wins).
.PARAMETER Since
  wait: which lines count. Omitted: only lines written after the call started. `start`: the whole log. Any other
  text: lines after the last log line containing that text (an error when no line does).
.PARAMETER Tail
  log: how many of the last lines to print (default 100).
.PARAMETER TimeoutSec
  start 900, cmd 30, wait 600, stop 60.
.EXAMPLE
  ./scripts/bot-server.ps1 start -Node 1.21.1 -World harness_check -Fresh -Seed 12345
  ./scripts/bot-server.ps1 start -Node 1.21.1 -Mods E:/jars/mcdevbridge-1.4.9+mc1.21.1.jar -Property difficulty=peaceful gamemode=creative
.EXAMPLE
  ./scripts/bot-server.ps1 cmd -Node 1.21.1 -Command 'time set day'
.EXAMPLE
  ./scripts/bot-server.ps1 wait -Node 1.21.1 -Pattern 'joined the game' -FailPattern 'Exception' -TimeoutSec 120
.EXAMPLE
  ./scripts/bot-server.ps1 log -Node 1.21.1 -Tail 50 -Pattern 'WARN|ERROR'
.EXAMPLE
  ./scripts/bot-server.ps1 status
.EXAMPLE
  ./scripts/bot-server.ps1 stop -Node 1.21.1
#>
#Requires -Version 7.0
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('start', 'cmd', 'wait', 'log', 'status', 'stop')]
    [string]$Action,
    [string]$Node,
    [string]$World,
    [string]$Seed,
    [switch]$Fresh,
    [string[]]$Mods,
    [string[]]$Property,
    [string]$Command,
    [string]$Pattern,
    [string]$FailPattern,
    [string]$Since,
    [int]$Tail = 100,
    [int]$TimeoutSec,
    # Extra words after -Mods or -Property (pwsh -File binds only the first). Sorted out below.
    [Parameter(ValueFromRemainingArguments)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# wait: "lines written after the call started" is measured here, before the slower setup below, so a command sent
# right after launching a wait (the two pwsh processes start together) still lands inside the window.
$earlyLogLength = -1
if ($Action -eq 'wait' -and $Node) {
    try {
        $earlyPath = Join-Path $root "build/bot-server/$Node.log"
        $earlyLogLength = if (Test-Path -LiteralPath $earlyPath) { (Get-Item -LiteralPath $earlyPath).Length } else { 0 }
    }
    catch { }
}

Import-Module (Join-Path $PSScriptRoot 'lib/MbbNodes.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'lib/BotServer.psm1') -Force

$stateDir = [IO.Path]::GetFullPath((Join-Path $root 'build/bot-server'))
$known = Get-KnownNodes $root

function Write-Out([string]$Text) { [Console]::Out.WriteLine($Text) }
function Write-Err([string]$Text) { [Console]::Error.WriteLine($Text) }

function Get-StatePath([string]$N) { Join-Path $stateDir "$N.json" }
function Get-LogPath([string]$N) { Join-Path $stateDir "$N.log" }

function Read-State([string]$N) {
    $p = Get-StatePath $N
    if (-not (Test-Path -LiteralPath $p)) { return $null }
    try { Get-Content -LiteralPath $p -Raw | ConvertFrom-Json } catch { $null }
}

function Write-State([string]$N, $State) {
    New-Item -ItemType Directory -Force $stateDir | Out-Null
    ConvertTo-Json -InputObject $State -Depth 5 | Set-Content -LiteralPath (Get-StatePath $N) -Encoding utf8
}

function Remove-State([string]$N) { Remove-Item -LiteralPath (Get-StatePath $N) -Force -ErrorAction SilentlyContinue }

function Get-Regex([string]$Text, [string]$What) {
    try { [regex]::new($Text) } catch { throw "$What is not a valid regex: $($_.Exception.InnerException.Message)" }
}

function Get-Timeout([int]$Default) {
    if ($script:TimeoutGiven) { return $script:TimeoutSec }
    return $Default
}

function Write-LogTail([string]$N, [int]$Count = 40) {
    $lines = @(Read-LogLines (Get-LogPath $N))
    if (-not $lines) { Write-Err "[$N] the log is empty or missing: $(Get-LogPath $N)"; return }
    Write-Err "[$N] last $([Math]::Min($Count, $lines.Count)) log lines ($(Get-LogPath $N)):"
    $lines | Select-Object -Last $Count | ForEach-Object { Write-Err "    $_" }
}

# ---------------------------------------------------------------------------------------------------------------
# start
# ---------------------------------------------------------------------------------------------------------------

function Test-WorldName([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) { throw 'World name is empty.' }
    if ($Name -eq '.' -or $Name.Contains('..')) { throw "World name '$Name' must not be '.' or contain '..'." }
    if ($Name -ne $Name.Trim() -or $Name.EndsWith('.')) { throw "World name '$Name' must not start or end with a space or end with a dot." }
    if ($Name.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or $Name.Contains('/') -or $Name.Contains('\')) {
        throw "World name '$Name' must be one folder name: no path separators or characters Windows forbids in file names."
    }
    if ($Name -cmatch '[^\x20-\x7E]') { throw "World name '$Name' must be ASCII (server.properties is read as ISO-8859-1)." }
}

function Invoke-Start {
    $runDir = [IO.Path]::GetFullPath((Join-Path $root "versions/$Node/run"))
    $propPath = Join-Path $runDir 'server.properties'
    $modsDir = Join-Path $runDir 'mods'
    $manifestPath = Join-Path $runDir '.bot-server-mods.json'
    $log = Get-LogPath $Node
    $deadlineSec = Get-Timeout 900
    if ($deadlineSec -lt 1) { throw '-TimeoutSec must be at least 1.' }

    # Parameters first, so a typo fails before anything on disk changes.
    $cur = Read-PropertyLines $propPath
    $worldName = if ($script:WorldGiven) { $script:World }
    else {
        $existing = Get-PropertyValue $cur.Lines 'level-name'
        if ($existing) { $existing } else { 'world' }
    }
    Test-WorldName $worldName
    $seedText = if ($script:Seed) { $script:Seed } else { '' }
    if ($seedText -cmatch '[^\x20-\x7E]' -or $seedText -ne $seedText.Trim()) { throw '-Seed must be printable ASCII without leading or trailing spaces.' }

    $jars = @()
    foreach ($m in $script:ModList) {
        $full = (Resolve-Path -LiteralPath $m -ErrorAction SilentlyContinue).ProviderPath
        if (-not $full -or -not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "-Mods: '$m' is not a file." }
        if ($full -notmatch '\.jar$') { throw "-Mods: '$m' is not a .jar." }
        $jars += $full
    }

    $running = @(Get-NodeJvms -Root $root -Node $Node)
    if ($running) { throw "[$Node] a server for this node is already running (java PID $($running -join ', ')). Run 'stop -Node $Node' first." }
    if (Get-LauncherProcess (Read-State $Node)) { throw "[$Node] a start for this node is still in progress (Gradle is running). Run 'stop -Node $Node' first." }

    # --- server.properties
    $props = [ordered]@{
        'online-mode'          = 'false'
        'enable-rcon'          = 'true'
        'rcon.port'            = ''
        'rcon.password'        = New-RconPassword
        'server-port'          = ''
        'server-ip'            = '127.0.0.1'
        'level-name'           = $worldName
        'level-seed'           = $seedText.Replace('\', '\\')
        'spawn-protection'     = '0'
        'max-tick-time'        = '-1'
    }
    # The key was added in 1.21.2; a node's run directory also tells us when the file already has it.
    $mcVersion = $null
    $pinFile = Join-Path $root "versions/$Node/gradle.properties"
    if (Test-Path -LiteralPath $pinFile) {
        $pin = (Select-String -LiteralPath $pinFile -Pattern '^\s*minecraft_version\s*=\s*([0-9.]+)' | Select-Object -First 1)
        if ($pin) { try { $mcVersion = [version]($pin.Matches[0].Groups[1].Value.TrimEnd('.')) } catch { } }
    }
    if ($null -ne (Get-PropertyValue $cur.Lines 'pause-when-empty-seconds') -or ($mcVersion -and $mcVersion -ge [version]'1.21.2')) {
        $props['pause-when-empty-seconds'] = '0'
    }
    $locked = 'enable-rcon', 'rcon.port', 'rcon.password', 'level-name', 'level-seed'
    foreach ($pair in $script:PropertyPairs) {
        if ($pair.Key -in $locked) { throw "-Property $($pair.Key) is set by the script; use -World / -Seed, or leave RCON alone." }
        $props[$pair.Key] = $pair.Value
    }

    $exclude = @(25565, 25575)
    foreach ($f in @(Get-ChildItem -LiteralPath $stateDir -Filter '*.json' -ErrorAction SilentlyContinue)) {
        try { $s = Get-Content -LiteralPath $f.FullName -Raw | ConvertFrom-Json; $exclude += [int]$s.serverPort, [int]$s.rconPort } catch { }
    }
    if ($props['server-port']) {
        $sp = 0
        if (-not [int]::TryParse($props['server-port'], [ref]$sp) -or $sp -lt 1024 -or $sp -gt 65535) { throw "-Property server-port=$($props['server-port']) is not a port between 1024 and 65535." }
        if ($sp -in 25565, 25575) { throw "Port $sp is reserved for real servers." }
        if (-not (Test-PortFree $sp)) { throw "-Property server-port=$sp is already in use." }
        $props['rcon.port'] = [string](Get-FreePorts 1 ($exclude + $sp))[0]
    }
    else {
        $ports = Get-FreePorts 2 $exclude
        $props['server-port'] = [string]$ports[0]
        $props['rcon.port'] = [string]$ports[1]
    }

    $bind = $props['server-ip']
    $hostName = if ($bind -and $bind -ne '0.0.0.0') { $bind } else { '127.0.0.1' }

    # -Fresh target: only the named folder directly under the run directory, and only if it is a world.
    $worldDir = $null
    if ($Fresh) {
        $candidate = Join-Path $runDir $worldName
        if ([IO.Path]::GetFullPath((Split-Path $candidate -Parent)) -ne $runDir.TrimEnd('\')) { throw "Refusing to delete '$candidate': it is not directly under the run directory." }
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (-not $item.PSIsContainer) { throw "Refusing -Fresh: '$candidate' is a file." }
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing -Fresh: '$candidate' is a link." }
            $hasLevel = Test-Path -LiteralPath (Join-Path $candidate 'level.dat')
            $isEmpty = -not (Get-ChildItem -LiteralPath $candidate -Force | Select-Object -First 1)
            if (-not $hasLevel -and -not $isEmpty) { throw "Refusing -Fresh: '$candidate' has no level.dat, so it does not look like a world." }
            $worldDir = $candidate
        }
    }

    # --- from here on the run directory changes; every check above has passed. The mods sync validates
    # everything itself before it touches a file, and runs before the world is deleted for that reason.
    New-Item -ItemType Directory -Force $runDir | Out-Null
    Set-Content -LiteralPath (Join-Path $runDir 'eula.txt') -Value 'eula=true'

    if ($jars.Count -or (Test-Path -LiteralPath $manifestPath)) {
        $sync = Sync-RunMods -ModsDir $modsDir -ManifestPath $manifestPath -Jars $jars
        foreach ($w in $sync.Warnings) { Write-Err "[$Node] $w" }
        foreach ($r in $sync.Removed) { Write-Out "[$Node] removed mods/$r (copied by an earlier start)" }
        foreach ($c in $sync.Copied) { Write-Out "[$Node] copied mods/$c" }
    }
    else { $sync = [pscustomobject]@{ Copied = @() } }

    if ($worldDir) {
        Remove-Item -LiteralPath $worldDir -Recurse -Force
        Write-Out "[$Node] deleted world folder $worldName"
    }

    Write-PropertyLines -Path $propPath -Lines (Set-PropertyLines $cur.Lines $props) -NewLine $cur.NewLine

    # --- launch. One cmd.exe so stdout and stderr share one log; hidden console so the server outlives this call.
    New-Item -ItemType Directory -Force $stateDir | Out-Null
    if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }
    $gradlew = Join-Path $root 'gradlew.bat'
    $cmdArgs = '/d /s /c ""{0}" :{1}:runServer --no-daemon --console=plain > "{2}" 2>&1 < NUL"' -f $gradlew, $Node, $log
    Write-Out "[$Node] starting dev server (world $worldName, server-port $($props['server-port']), rcon-port $($props['rcon.port']))"
    $proc = Start-Process -FilePath $env:ComSpec -ArgumentList $cmdArgs -WorkingDirectory $root -WindowStyle Hidden -PassThru -ErrorAction Stop
    $null = $proc.Handle   # keeps the exit code readable after exit

    $startedUtc = [datetime]::UtcNow
    $state = [ordered]@{
        node = $Node; ready = $false; world = $worldName; seed = $seedText; host = $hostName
        serverPort = [int]$props['server-port']; rconPort = [int]$props['rcon.port']; rconPassword = $props['rcon.password']
        log = $log; runDir = $runDir; startedUtc = $startedUtc.ToString('o'); readyUtc = $null
        launcherPid = $proc.Id; launcherStartUtc = $proc.StartTime.ToUniversalTime().ToString('o')
        mods = @($sync.Copied)
    }
    Write-State $Node $state

    $ok = $false
    $failure = $null
    $doneRe = [regex]'Done \([\d.,]+s\)!'
    $failRe = [regex]'BUILD FAILED|Crash report saved|Failed to start the minecraft server|FAILED TO BIND'
    $follower = New-LogFollower -Path $log -From Start
    $deadline = $startedUtc.AddSeconds($deadlineSec)
    $phase = 'server'
    try {
        while ($true) {
            Start-Sleep -Milliseconds 1000
            # Sample the exit state before reading the log, so output written just before an exit is still seen.
            $exited = $proc.HasExited
            foreach ($line in @(Read-LogFollower $follower)) {
                if ($phase -ne 'server') { break }
                if ($doneRe.IsMatch($line)) { $phase = 'rcon'; Write-Out "[$Node] server is up, waiting for RCON"; break }
                if ($failRe.IsMatch($line)) { $failure = "server failed to start: $line"; break }
            }
            if ($failure) { break }
            if ($phase -eq 'rcon') {
                try {
                    $c = Open-Rcon -HostName $hostName -Port $state.rconPort -Password $state.rconPassword -Deadline ([datetime]::UtcNow.AddSeconds(5))
                    $c.Dispose()
                    $ok = $true
                    break
                }
                catch {
                    if ($_.Exception.Data['ExitCode'] -eq 3) { $failure = $_.Exception.Message; break }
                }
            }
            if ($exited) { $failure = "Gradle exited (code $($proc.ExitCode)) before the server was ready."; break }
            if ([datetime]::UtcNow -gt $deadline) { $failure = "timed out after $deadlineSec s waiting for the server ($phase phase)."; break }
        }
    }
    finally {
        if (-not $ok) {
            # Failure or Ctrl+C: leave nothing of this node running.
            if (-not $proc.HasExited) { & taskkill.exe /T /F /PID $proc.Id 2>&1 | Out-Null }
            $survivors = Stop-NodeJvms -Root $root -Node $Node
            if ($survivors) { Write-Err "[$Node] java PID $($survivors -join ', ') survived the cleanup; run 'stop -Node $Node'." }
            else { Remove-State $Node }
        }
    }
    if (-not $ok) {
        Write-LogTail $Node
        throw "[$Node] $failure"
    }
    $state.ready = $true
    $state.readyUtc = [datetime]::UtcNow.ToString('o')
    Write-State $Node $state
    Write-Out "[$Node] ready: world=$worldName seed=$(if ($seedText) { $seedText } else { '(not set)' }) server-port=$($state.serverPort) rcon-port=$($state.rconPort) log=$log"
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# cmd
# ---------------------------------------------------------------------------------------------------------------

function Invoke-Cmd {
    if (-not $Command) { throw "cmd needs -Command '<minecraft command>'." }
    $state = Read-State $Node
    if (-not $state) { throw "[$Node] no state file ($(Get-StatePath $Node)); run 'start -Node $Node' first." }
    # Only talk to the port while this node's JVM exists: a dead server's port may belong to someone else.
    if (-not (Get-NodeJvms -Root $root -Node $Node)) { throw "[$Node] the server is not running (stale state file); run 'start -Node $Node'." }
    $t = Get-Timeout 30
    if ($t -lt 1) { throw '-TimeoutSec must be at least 1.' }
    $text = Invoke-Rcon -HostName $state.host -Port $state.rconPort -Password $state.rconPassword -Command $Command.TrimStart('/') -TimeoutSec $t
    Write-Out $text.TrimEnd()
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# wait
# ---------------------------------------------------------------------------------------------------------------

function Invoke-Wait {
    if (-not $Pattern) { throw "wait needs -Pattern '<regex>'." }
    $re = Get-Regex $Pattern '-Pattern'
    $failRe = if ($FailPattern) { Get-Regex $FailPattern '-FailPattern' } else { $null }
    $t = Get-Timeout 600
    if ($t -lt 1) { throw '-TimeoutSec must be at least 1.' }
    if (-not (Read-State $Node)) { throw "[$Node] no state file ($(Get-StatePath $Node)); run 'start -Node $Node' first." }
    $log = Get-LogPath $Node
    $deadline = [datetime]::UtcNow.AddSeconds($t)

    $initial = @()
    if (-not $Since) { $follower = New-LogFollower -Path $log -From End -AtOffset $script:earlyLogLength }
    else {
        $follower = New-LogFollower -Path $log -From Start
        $initial = @(Read-LogFollower $follower)
        if ($Since -ne 'start') {
            $at = -1
            for ($i = 0; $i -lt $initial.Count; $i++) { if ($initial[$i].Contains($Since, [StringComparison]::Ordinal)) { $at = $i } }
            if ($at -lt 0) { throw "[$Node] -Since: no log line contains '$Since'." }
            $initial = if ($at + 1 -lt $initial.Count) { $initial[($at + 1)..($initial.Count - 1)] } else { @() }
        }
    }
    Write-Err "[$Node] waiting for /$Pattern/ (timeout $t s)"

    $liveAt = [Diagnostics.Stopwatch]::StartNew()
    $checkLive = $true
    $first = $true
    while ($true) {
        $exited = $false
        if ($checkLive -or $liveAt.Elapsed.TotalSeconds -ge 2) {
            $exited = -not ((Get-NodeJvms -Root $root -Node $Node) -or (Get-LauncherProcess (Read-State $Node)))
            $liveAt.Restart(); $checkLive = $false
        }
        $lines = if ($first) { $first = $false; @($initial) + @(Read-LogFollower $follower -Flush:$exited) } else { @(Read-LogFollower $follower -Flush:$exited) }
        foreach ($line in $lines) {
            if ($failRe -and $failRe.IsMatch($line)) { Write-Out $line; Write-Err "[$Node] -FailPattern matched."; return 2 }
            if ($re.IsMatch($line)) { Write-Out $line; return 0 }
        }
        if ($exited) { Write-Err "[$Node] the server exited before /$Pattern/ appeared."; return 1 }
        if ([datetime]::UtcNow -gt $deadline) { Write-Err "[$Node] timed out after $t s waiting for /$Pattern/."; return 1 }
        Start-Sleep -Milliseconds 200
    }
}

# ---------------------------------------------------------------------------------------------------------------
# log, status, stop
# ---------------------------------------------------------------------------------------------------------------

function Invoke-Log {
    $log = Get-LogPath $Node
    if (-not (Test-Path -LiteralPath $log)) { throw "[$Node] no log at $log." }
    if ($Tail -lt 1) { throw '-Tail must be at least 1.' }
    $lines = @(Read-LogLines $log)
    if ($Pattern) { $re = Get-Regex $Pattern '-Pattern'; $lines = @($lines | Where-Object { $re.IsMatch($_) }) }
    $lines | Select-Object -Last $Tail | ForEach-Object { Write-Out $_ }
    return 0
}

function Invoke-Status {
    $nodes = if ($Node) { @($Node) }
    else { @(Get-ChildItem -LiteralPath $stateDir -Filter '*.json' -ErrorAction SilentlyContinue | ForEach-Object BaseName | Where-Object { $_ -in $known } | Sort-Object) }
    if (-not $nodes) { Write-Out 'No servers started from this checkout (no state files).'; return 0 }
    $anyDown = $false
    foreach ($n in $nodes) {
        $state = Read-State $n
        $jvms = @(Get-NodeJvms -Root $root -Node $n)
        $alive = [bool]$jvms -or [bool](Get-LauncherProcess $state)
        if (-not $alive) { $anyDown = $true }
        if (-not $state) {
            Write-Out ("{0}  {1}  (no state file)" -f $n, $(if ($jvms) { "running, java PID $($jvms -join ',')" } else { 'not running' }))
            continue
        }
        $up = if ($alive) { if ($state.ready -and $jvms) { 'running' } else { 'starting' } } else { 'not running (stale state file)' }
        $uptime = if ($alive) { ' uptime=' + ([datetime]::UtcNow - ([datetime]$state.startedUtc).ToUniversalTime()).ToString('d\.hh\:mm\:ss') } else { '' }
        $mods = if ($state.mods) { ' mods=' + (@($state.mods) -join ',') } else { '' }
        Write-Out ("{0}  {1}{2}  world={3} server-port={4} rcon-port={5} rcon-password={6}{7}  log={8}" -f
            $n, $up, $uptime, $state.world, $state.serverPort, $state.rconPort,
            $(if ($state.rconPassword) { 'set' } else { 'none' }), $mods, $state.log)
    }
    if ($Node -and $anyDown) { return 1 }
    return 0
}

function Invoke-Stop {
    $state = Read-State $Node
    $t = Get-Timeout 60
    if ($t -lt 1) { throw '-TimeoutSec must be at least 1.' }
    $jvms = @(Get-NodeJvms -Root $root -Node $Node)
    if (-not $jvms -and -not (Get-LauncherProcess $state)) {
        Remove-State $Node
        Write-Out "[$Node] no server running."
        return 0
    }
    $delivered = $false
    if (-not $jvms) { Write-Err "[$Node] the server is not up yet (Gradle is still starting); killing the launch." }
    elseif ($state) {
        # RCON only while the node's own JVM exists: a dead server's port may belong to someone else.
        try {
            $null = Invoke-Rcon -HostName $state.host -Port $state.rconPort -Password $state.rconPassword -Command 'stop' -TimeoutSec 10
            $delivered = $true
        }
        catch {
            # A server that closes the socket while it shuts down still got the command.
            $delivered = [bool]$_.Exception.Data['Sent']
            if (-not $delivered) { Write-Err "[$Node] RCON did not take the stop command ($($_.Exception.Message)); killing the server." }
        }
    }
    else { Write-Err "[$Node] no state file, so no RCON; killing the server." }

    if ($delivered) {
        Write-Out "[$Node] stop sent, waiting for the server to exit"
        $deadline = [datetime]::UtcNow.AddSeconds($t)
        while ([datetime]::UtcNow -lt $deadline -and ((Get-NodeJvms -Root $root -Node $Node) -or (Get-LauncherProcess $state))) { Start-Sleep -Seconds 1 }
    }
    # Whatever is left: the launch's process tree first (so Gradle cannot bring the server back), then the sweep.
    $launcher = Get-LauncherProcess $state
    if ($launcher) {
        Write-Out "[$Node] killing the Gradle launch (PID $($launcher.Id))"
        & taskkill.exe /T /F /PID $launcher.Id 2>&1 | Out-Null
    }
    $leftover = @(Get-NodeJvms -Root $root -Node $Node)
    if ($leftover) { Write-Out "[$Node] killing java PID $($leftover -join ', ')" }
    $survivors = Stop-NodeJvms -Root $root -Node $Node
    if ($survivors) { throw "[$Node] java PID $($survivors -join ', ') could not be stopped." }
    Remove-State $Node
    Write-Out "[$Node] stopped."
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# Argument checks, then dispatch
# ---------------------------------------------------------------------------------------------------------------

try {
    if (-not $Node -and $Action -ne 'status') { throw "-Node is required. Known nodes: $($known -join ', ')" }
    if ($Node -and $Node -notin $known) { throw "Unknown node '$Node'. Known nodes: $($known -join ', ')" }

    $allowed = @{
        start  = 'Node', 'World', 'Seed', 'Fresh', 'Mods', 'Property', 'TimeoutSec'
        cmd    = 'Node', 'Command', 'TimeoutSec'
        wait   = 'Node', 'Pattern', 'FailPattern', 'Since', 'TimeoutSec'
        log    = 'Node', 'Tail', 'Pattern'
        status = 'Node'
        stop   = 'Node', 'TimeoutSec'
    }
    $skip = @('Action', 'Rest') + [Management.Automation.Cmdlet]::CommonParameters + [Management.Automation.Cmdlet]::OptionalCommonParameters
    foreach ($k in $PSBoundParameters.Keys) {
        if ($k -notin $skip -and $k -notin $allowed[$Action]) { throw "-$k is not used by '$Action'. '$Action' takes: $($allowed[$Action] -join ', ')" }
    }
    $script:TimeoutGiven = $PSBoundParameters.ContainsKey('TimeoutSec')
    $script:WorldGiven = $PSBoundParameters.ContainsKey('World')

    # -Mods and -Property accept comma-separated text and extra words (pwsh -File binds only the first word).
    $script:ModList = @()
    $propText = @()
    foreach ($m in @($Mods)) { if ($m) { $script:ModList += @($m -split ',' | ForEach-Object Trim | Where-Object { $_ }) } }
    foreach ($p in @($Property)) { if ($p) { $propText += $p } }
    foreach ($r in @($Rest | Where-Object { $_ })) {
        if ($Action -ne 'start') { throw "Unexpected argument '$r'." }
        if ($r -match '\.jar$') { $script:ModList += $r }
        elseif ($r -match '^[A-Za-z0-9_.\-]+=') { $propText += $r }
        else { throw "Unexpected argument '$r'. Extra words after -Mods must be .jar paths and after -Property key=value pairs." }
    }
    $script:PropertyPairs = @()
    foreach ($text in $propText) {
        # Comma-separated pairs split only where a new key= begins, so a value may contain commas.
        foreach ($part in ($text -split ',(?=[A-Za-z0-9_.\-]+=)')) {
            if ($part -notmatch '^([A-Za-z0-9_.\-]+)=(.*)$') { throw "-Property '$part' is not key=value." }
            if ($Matches[2] -match '[^\x20-\x7E]') { throw "-Property $($Matches[1]): the value must be printable ASCII." }
            $script:PropertyPairs += [pscustomobject]@{ Key = $Matches[1]; Value = $Matches[2] }
        }
    }

    # Each action returns its exit code as the last thing it emits.
    $result = @(switch ($Action) {
            'start' { Invoke-Start }
            'cmd' { Invoke-Cmd }
            'wait' { Invoke-Wait }
            'log' { Invoke-Log }
            'status' { Invoke-Status }
            'stop' { Invoke-Stop }
        })
    exit ([int]$result[-1])
}
catch {
    $exitCode = if ($_.Exception.Data.Contains('ExitCode')) { [int]$_.Exception.Data['ExitCode'] } else { 1 }
    Write-Err "bot-server: $($_.Exception.Message)"
    exit $exitCode
}
