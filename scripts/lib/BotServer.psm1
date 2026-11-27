# Shared code for scripts/bot-server.ps1: a Source RCON client, server.properties editing,
# a rotation-safe log follower, free-port probing and the run/mods manifest.
# Nothing here prints; callers decide what reaches stdout and stderr.

# The node-JVM sweep (Get-NodeJvms, Stop-NodeJvms) lives in MbbNodes.psm1, shared with smoke-server.ps1 and
# gametest.ps1; bot-server.ps1 imports both modules.

# The sweep finds a JVM by command line, so it sees nothing in the first moments of a start, before the Gradle
# client JVM exists. bot-server.ps1 therefore also records the cmd.exe it launched and treats it as alive while the
# same process (same PID and start time) still runs. Used to refuse a second start, to let stop and wait see a start
# still in the Gradle phase, never to pick a process to kill without that identity check.
function Get-LauncherProcess {
    param($State)
    if (-not $State -or -not $State.launcherPid -or -not $State.launcherStartUtc) { return $null }
    $p = Get-Process -Id ([int]$State.launcherPid) -ErrorAction SilentlyContinue
    if (-not $p -or $p.ProcessName -ne 'cmd') { return $null }
    try {
        $drift = [Math]::Abs(($p.StartTime.ToUniversalTime() - ([datetime]$State.launcherStartUtc).ToUniversalTime()).TotalSeconds)
        if ($drift -gt 2) { return $null }
    }
    catch { return $null }
    return $p
}

# ---------------------------------------------------------------------------------------------------------------
# Errors that carry an exit code. The script's top level reads Data['ExitCode'].
# ---------------------------------------------------------------------------------------------------------------

function New-BotError {
    param([Parameter(Mandatory)][string]$Message, [int]$Code = 1)
    $e = [System.InvalidOperationException]::new($Message)
    $e.Data['ExitCode'] = $Code
    return $e
}

# ---------------------------------------------------------------------------------------------------------------
# Source RCON client. Packet: little-endian int32 length (id + type + body + 2 NULs), int32 id, int32 type, body,
# NUL, NUL. Auth is type 3 (the reply is type 2, id -1 on a bad password), exec is type 2. A reply longer than 4096
# bytes arrives as several packets with the request's id, so every command is followed by a sentinel packet of an
# unknown type: the server answers it after the command's last packet, which marks the end of the response.
# Exit codes used with New-BotError: 3 auth failure, 4 cannot connect or no response.
# ---------------------------------------------------------------------------------------------------------------

function ConvertTo-RconPacket {
    param([int]$Id, [int]$Type, [string]$Body)
    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
    $ms = [IO.MemoryStream]::new()
    $w = [IO.BinaryWriter]::new($ms)
    $w.Write([int](10 + $bodyBytes.Length))
    $w.Write([int]$Id)
    $w.Write([int]$Type)
    $w.Write([byte[]]$bodyBytes)
    $w.Write([byte]0)
    $w.Write([byte]0)
    $w.Flush()
    return , $ms.ToArray()
}

function Read-RconBytes {
    param([Net.Sockets.NetworkStream]$Stream, [int]$Count, [datetime]$Deadline)
    $buf = New-Object byte[] $Count
    $got = 0
    while ($got -lt $Count) {
        $leftMs = ($Deadline - [datetime]::UtcNow).TotalMilliseconds
        if ($leftMs -le 0) { throw (New-BotError 'RCON: no response before the timeout.' 4) }
        $Stream.ReadTimeout = [int][Math]::Max(1, [Math]::Min($leftMs, 2000000000))
        try { $n = $Stream.Read($buf, $got, $Count - $got) }
        catch [IO.IOException] {
            $timedOut = ($_.Exception.InnerException -is [Net.Sockets.SocketException]) -and
                $_.Exception.InnerException.SocketErrorCode -eq [Net.Sockets.SocketError]::TimedOut
            if ($timedOut) { throw (New-BotError 'RCON: no response before the timeout.' 4) }
            throw (New-BotError "RCON: connection lost ($($_.Exception.Message))." 4)
        }
        if ($n -le 0) { throw (New-BotError 'RCON: the server closed the connection.' 4) }
        $got += $n
    }
    return , $buf
}

function Read-RconPacket {
    param([Net.Sockets.NetworkStream]$Stream, [datetime]$Deadline)
    $head = Read-RconBytes $Stream 4 $Deadline
    $len = [BitConverter]::ToInt32($head, 0)
    if ($len -lt 10 -or $len -gt 8192) { throw (New-BotError "RCON: malformed packet (length $len)." 4) }
    $data = Read-RconBytes $Stream $len $Deadline
    $body = [byte[]]@()
    if ($len -gt 10) {
        $body = New-Object byte[] ($len - 10)   # without the two trailing NULs
        [Buffer]::BlockCopy($data, 8, $body, 0, $body.Length)
    }
    [pscustomobject]@{
        Id   = [BitConverter]::ToInt32($data, 0)
        Type = [BitConverter]::ToInt32($data, 4)
        Body = $body
    }
}

function Open-Rcon {
    # Connects and authenticates. Returns the TcpClient; the caller closes it.
    param([string]$HostName = '127.0.0.1', [Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Password, [datetime]$Deadline)
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $leftMs = [int][Math]::Max(1, ($Deadline - [datetime]::UtcNow).TotalMilliseconds)
        try {
            $connect = $client.ConnectAsync($HostName, $Port)
            if (-not $connect.Wait($leftMs)) { throw (New-BotError "RCON: connecting to ${HostName}:$Port timed out." 4) }
        }
        catch [AggregateException] {
            throw (New-BotError "RCON: cannot connect to ${HostName}:$Port ($($_.Exception.InnerException.Message))." 4)
        }
        $stream = $client.GetStream()
        $auth = [byte[]](ConvertTo-RconPacket 1 3 $Password)
        $stream.Write($auth, 0, $auth.Length)
        while ($true) {
            $p = Read-RconPacket $stream $Deadline
            if ($p.Type -ne 2) { continue }   # some servers send an empty type-0 packet before the auth reply
            if ($p.Id -eq -1) { throw (New-BotError 'RCON: authentication failed (wrong rcon.password).' 3) }
            break
        }
        return $client
    }
    catch {
        $client.Dispose()
        throw
    }
}

function Invoke-Rcon {
    param([string]$HostName = '127.0.0.1', [Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Command, [int]$TimeoutSec = 30)
    if ([Text.Encoding]::UTF8.GetByteCount($Command) -gt 1446) { throw (New-BotError 'RCON: the command is longer than the 1446-byte packet limit.' 1) }
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSec)
    $client = Open-Rcon -HostName $HostName -Port $Port -Password $Password -Deadline $deadline
    $sent = $false
    try {
        $stream = $client.GetStream()
        $exec = [byte[]](ConvertTo-RconPacket 2 2 $Command)
        $sentinel = [byte[]](ConvertTo-RconPacket 3 100 '')
        $stream.Write($exec, 0, $exec.Length)
        $sent = $true
        $body = [IO.MemoryStream]::new()
        # The sentinel goes out only after the command's first answer. Minecraft reads one packet per socket read and
        # drops the connection, command unrun, when two arrive in the same read; sent back to back they did so about
        # one time in ten on a busy machine ("the server closed the connection"). Every command gets an answer packet,
        # an empty one when it prints nothing, so waiting for it cannot hang short of the deadline.
        $first = Read-RconPacket $stream $deadline
        if ($first.Id -eq -1) { throw (New-BotError 'RCON: authentication was revoked.' 3) }
        if ($first.Id -eq 2 -and $first.Body.Length) { $body.Write([byte[]]$first.Body, 0, $first.Body.Length) }
        $stream.Write($sentinel, 0, $sentinel.Length)
        while ($true) {
            $p = Read-RconPacket $stream $deadline
            if ($p.Id -eq -1) { throw (New-BotError 'RCON: authentication was revoked.' 3) }
            if ($p.Id -eq 3) { break }
            if ($p.Id -eq 2 -and $p.Body.Length) { $body.Write([byte[]]$p.Body, 0, $p.Body.Length) }
        }
        return [Text.Encoding]::UTF8.GetString($body.ToArray())
    }
    catch {
        # Data['Sent'] tells stop whether the server got the command before the connection failed.
        $e = $_.Exception
        if (-not $e.Data.Contains('ExitCode')) { $e = New-BotError "RCON: $($e.Message)" 4 }
        $e.Data['Sent'] = $sent
        throw $e
    }
    finally { $client.Dispose() }
}

# ---------------------------------------------------------------------------------------------------------------
# server.properties. Java reads the file as ISO-8859-1, so it is read and written as Latin-1: bytes the script does
# not touch round-trip unchanged.
# ---------------------------------------------------------------------------------------------------------------

$script:propertyLine = [regex]'^\s*([^#!\s=:][^=:\s]*)\s*[=:]'

function Read-PropertyLines {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{ Lines = @(); NewLine = "`n" } }
    $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::Latin1)
    $nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = $text -split '\r?\n'
    if ($lines.Count -and $lines[-1] -eq '') { $lines = $lines[0..($lines.Count - 2)] }
    [pscustomobject]@{ Lines = @($lines); NewLine = $nl }
}

function Get-PropertyValue {
    # The value of the last line that sets $Key, or $null.
    param([string[]]$Lines, [string]$Key)
    $found = $null
    foreach ($l in $Lines) {
        $m = $script:propertyLine.Match($l)
        if ($m.Success -and $m.Groups[1].Value -ceq $Key) { $found = ($l.Substring($m.Length)).TrimStart() }
    }
    return $found
}

function Set-PropertyLines {
    # Returns the lines with every key in $Values set (all its lines updated, or appended when absent). Other lines
    # and comments are kept as they are.
    param([string[]]$Lines, [System.Collections.IDictionary]$Values)
    $out = [System.Collections.Generic.List[string]]::new()
    $seen = @{}
    foreach ($l in $Lines) {
        $m = $script:propertyLine.Match($l)
        if ($m.Success -and $Values.Contains($m.Groups[1].Value)) {
            $k = $m.Groups[1].Value
            $out.Add("$k=$($Values[$k])")
            $seen[$k] = $true
        }
        else { $out.Add($l) }
    }
    foreach ($k in $Values.Keys) { if (-not $seen.ContainsKey($k)) { $out.Add("$k=$($Values[$k])") } }
    return , $out.ToArray()
}

function Write-PropertyLines {
    param([string]$Path, [string[]]$Lines, [string]$NewLine = "`n")
    [IO.File]::WriteAllText($Path, (($Lines -join $NewLine) + $NewLine), [Text.Encoding]::Latin1)
}

# ---------------------------------------------------------------------------------------------------------------
# Ports and passwords
# ---------------------------------------------------------------------------------------------------------------

function Test-PortFree {
    param([int]$Port)
    foreach ($addr in @([Net.IPAddress]::Any, [Net.IPAddress]::Loopback)) {
        $l = $null
        try { $l = [Net.Sockets.TcpListener]::new($addr, $Port); $l.Start() }
        catch { return $false }
        finally { if ($l) { $l.Stop() } }
    }
    return $true
}

function Get-FreePorts {
    # $Count distinct free TCP ports from 25700-25999 (never 25565 or 25575), skipping $Exclude.
    param([int]$Count, [int[]]$Exclude = @())
    $picked = [System.Collections.Generic.List[int]]::new()
    foreach ($p in (25700..25999 | Get-Random -Count 300)) {
        if ($p -in $Exclude -or $p -in $picked) { continue }
        if (Test-PortFree $p) { $picked.Add($p) }
        if ($picked.Count -eq $Count) { return , $picked.ToArray() }
    }
    throw (New-BotError "Could not find $Count free ports in 25700-25999." 1)
}

function New-RconPassword {
    $alphabet = [char[]]('abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789')
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

# ---------------------------------------------------------------------------------------------------------------
# Log follower. Reads whatever was appended since the last call, whole lines only (a partial last line waits for
# its newline). It notices a log that was truncated, replaced or deleted and restarts from the top of the new one.
# The file is opened with full sharing, so the writer is never blocked.
# ---------------------------------------------------------------------------------------------------------------

function Read-FileRange {
    param([IO.FileStream]$Stream, [long]$Offset, [int]$Count)
    $buf = New-Object byte[] $Count
    $null = $Stream.Seek($Offset, [IO.SeekOrigin]::Begin)
    $got = 0
    while ($got -lt $Count) {
        $n = $Stream.Read($buf, $got, $Count - $got)
        if ($n -le 0) { break }
        $got += $n
    }
    if ($got -lt $Count) { $trim = New-Object byte[] $got; [Buffer]::BlockCopy($buf, 0, $trim, 0, $got); return , $trim }
    return , $buf
}

function New-LogFollower {
    # -From End starts after the current content (the line being written right now is skipped); -From Start reads
    # the whole file. -AtOffset (with End) starts at a length the caller measured earlier, so lines written since
    # then are included; an offset beyond the file's current length falls back to its end.
    param([Parameter(Mandatory)][string]$Path, [ValidateSet('Start', 'End')][string]$From = 'End', [long]$AtOffset = -1)
    $f = @{ Path = $Path; Pos = [long]0; Head = [byte[]]@(); Pending = [byte[]]@(); SkipFirst = $false }
    if ($From -eq 'End' -and (Test-Path -LiteralPath $Path)) {
        try {
            $fs = [IO.File]::Open($Path, 'Open', 'Read', [IO.FileShare]'ReadWrite,Delete')
            try {
                $len = $fs.Length
                $pos = if ($AtOffset -ge 0) { [Math]::Min($AtOffset, $len) } else { $len }
                $f.Pos = $pos
                if ($len -gt 0) { $f.Head = [byte[]](Read-FileRange $fs 0 ([int][Math]::Min(64, $len))) }
                if ($pos -gt 0) {
                    $last = Read-FileRange $fs ($pos - 1) 1
                    $f.SkipFirst = ($last[0] -ne 10)
                }
            }
            finally { $fs.Dispose() }
        }
        catch [IO.IOException] { }
    }
    return $f
}

function Read-LogFollower {
    # Returns the new complete lines (an array, possibly empty). -Flush also returns a final unterminated line.
    param([Parameter(Mandatory)][hashtable]$Follower, [switch]$Flush)
    $f = $Follower
    $fresh = [byte[]]@()
    $reset = {
        $f.Pos = [long]0; $f.Head = [byte[]]@(); $f.Pending = [byte[]]@(); $f.SkipFirst = $false
    }
    if (-not (Test-Path -LiteralPath $f.Path)) {
        & $reset
        return @()
    }
    try { $fs = [IO.File]::Open($f.Path, 'Open', 'Read', [IO.FileShare]'ReadWrite,Delete') }
    catch [IO.IOException] { return @() }
    try {
        $len = $fs.Length
        $rotated = $len -lt $f.Pos
        if (-not $rotated -and $f.Head.Length -gt 0) {
            if ($len -lt $f.Head.Length) { $rotated = $true }
            else {
                $now = Read-FileRange $fs 0 $f.Head.Length
                for ($i = 0; $i -lt $f.Head.Length; $i++) { if ($now[$i] -ne $f.Head[$i]) { $rotated = $true; break } }
            }
        }
        if ($rotated) { & $reset }

        if ($len -gt $f.Pos) {
            $fresh = [byte[]](Read-FileRange $fs $f.Pos ([int][Math]::Min($len - $f.Pos, 1GB)))
            $f.Pos += $fresh.Length
        }
        if ($f.Head.Length -lt 64 -and $len -gt $f.Head.Length) {
            $f.Head = [byte[]](Read-FileRange $fs 0 ([int][Math]::Min(64, $len)))
        }
    }
    finally { $fs.Dispose() }

    $data = New-Object byte[] ($f.Pending.Length + $fresh.Length)
    if ($f.Pending.Length) { [Buffer]::BlockCopy($f.Pending, 0, $data, 0, $f.Pending.Length) }
    if ($fresh.Length) { [Buffer]::BlockCopy($fresh, 0, $data, $f.Pending.Length, $fresh.Length) }

    $cut = [Array]::LastIndexOf($data, [byte]10) + 1   # bytes up to and including the last newline
    $lines = [System.Collections.Generic.List[string]]::new()
    if ($cut -gt 0) {
        $text = [Text.Encoding]::UTF8.GetString($data, 0, $cut)
        $parts = $text.Split("`n")
        for ($i = 0; $i -lt $parts.Length - 1; $i++) { $lines.Add($parts[$i].TrimEnd("`r")) }
    }
    $rest = New-Object byte[] ($data.Length - $cut)
    if ($rest.Length) { [Buffer]::BlockCopy($data, $cut, $rest, 0, $rest.Length) }
    if ($Flush -and $rest.Length) {
        $lines.Add([Text.Encoding]::UTF8.GetString($rest).TrimEnd("`r"))
        $rest = [byte[]]@()
    }
    $f.Pending = $rest

    if ($f.SkipFirst -and $lines.Count) {
        # The first line of an end-anchored follower was already half written when the follower started.
        $lines.RemoveAt(0)
        $f.SkipFirst = $false
    }
    return $lines.ToArray()   # callers wrap the call in @(): no lines emits nothing
}

function Read-LogLines {
    # Every line currently in the file, as an array. Empty when the file does not exist.
    param([Parameter(Mandatory)][string]$Path)
    $f = New-LogFollower -Path $Path -From Start
    return @(Read-LogFollower $f -Flush)
}

# ---------------------------------------------------------------------------------------------------------------
# run/mods manifest. The script only ever deletes or overwrites a jar it copied itself, and only while the file is
# still byte-identical to what it copied. The manifest sits in the run directory, not in the per-start state file,
# because stop removes that file and the next start still has to know what it may remove.
# ---------------------------------------------------------------------------------------------------------------

function Get-ModId {
    # The mod id declared in a jar (NeoForge/Forge mods.toml or Fabric fabric.mod.json), or $null.
    param([string]$JarPath)
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            foreach ($entry in 'META-INF/neoforge.mods.toml', 'META-INF/mods.toml', 'fabric.mod.json') {
                $e = $zip.GetEntry($entry)
                if (-not $e) { continue }
                $reader = [IO.StreamReader]::new($e.Open())
                try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $m = if ($entry -eq 'fabric.mod.json') { [regex]::Match($text, '"id"\s*:\s*"([^"]+)"') }
                else { [regex]::Match($text, '(?m)^\s*modId\s*=\s*"([^"]+)"') }
                if ($m.Success) { return $m.Groups[1].Value }
            }
        }
        finally { $zip.Dispose() }
    }
    catch { }
    return $null
}

function Read-ModManifest {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    try { return @(Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json) }
    catch { return @() }
}

function Sync-RunMods {
    # Makes run/mods hold the jars in $Jars (full paths) plus whatever the script did not copy. Validates everything
    # before changing anything. Returns @{ Copied = names now present; Removed = names deleted; Warnings = ... }.
    param([string]$ModsDir, [string]$ManifestPath, [string[]]$Jars = @())
    $manifest = @(Read-ModManifest $ManifestPath)
    $hashOf = { param($p) (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash }

    # A jar counts as ours only when it is in the manifest and unchanged since the copy.
    $ours = @{}
    $warnings = [System.Collections.Generic.List[string]]::new()
    foreach ($m in $manifest) {
        $p = Join-Path $ModsDir $m.name
        if (-not (Test-Path -LiteralPath $p)) { continue }
        if ((& $hashOf $p) -eq $m.sha256) { $ours[$m.name] = $true }
        else { $warnings.Add("mods/$($m.name) changed since it was copied; leaving it alone.") }
    }

    $wanted = @{}
    foreach ($j in $Jars) {
        $name = Split-Path $j -Leaf
        if ($wanted.ContainsKey($name)) { throw (New-BotError "Two -Mods jars are named '$name'." 1) }
        $wanted[$name] = $j
    }

    foreach ($name in $wanted.Keys) {
        $dest = Join-Path $ModsDir $name
        if ((Test-Path -LiteralPath $dest) -and -not $ours.ContainsKey($name)) {
            throw (New-BotError "mods/$name already exists and was not copied by bot-server; remove it or rename the jar." 1)
        }
    }
    if (Test-Path -LiteralPath $ModsDir) {
        $others = @(Get-ChildItem -LiteralPath $ModsDir -Filter '*.jar' -File | Where-Object { -not $ours.ContainsKey($_.Name) })
        foreach ($name in $wanted.Keys) {
            $id = Get-ModId $wanted[$name]
            if (-not $id) { continue }
            foreach ($o in $others) {
                if ((Get-ModId $o.FullName) -eq $id) {
                    throw (New-BotError "mods/$($o.Name) is another copy of mod '$id' and was not copied by bot-server; remove it before adding $name." 1)
                }
            }
        }
    }

    $removed = [System.Collections.Generic.List[string]]::new()
    New-Item -ItemType Directory -Force $ModsDir | Out-Null
    foreach ($name in @($ours.Keys)) {
        if (-not $wanted.ContainsKey($name)) {
            Remove-Item -LiteralPath (Join-Path $ModsDir $name) -Force
            $removed.Add($name)
        }
    }
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($name in ($wanted.Keys | Sort-Object)) {
        $dest = Join-Path $ModsDir $name
        Copy-Item -LiteralPath $wanted[$name] -Destination $dest -Force
        $entries.Add([ordered]@{ name = $name; sha256 = (& $hashOf $dest); source = $wanted[$name] })
    }
    # Unchanged-but-foreign or modified entries drop out of the manifest: they are no longer the script's.
    ConvertTo-Json -InputObject @($entries) -Depth 4 | Set-Content -LiteralPath $ManifestPath -Encoding utf8
    [pscustomobject]@{ Copied = @($wanted.Keys | Sort-Object); Removed = @($removed); Warnings = @($warnings) }
}

Export-ModuleMember -Function Get-LauncherProcess, New-BotError, Invoke-Rcon, Open-Rcon,
    Read-PropertyLines, Get-PropertyValue, Set-PropertyLines, Write-PropertyLines, Test-PortFree, Get-FreePorts,
    New-RconPassword, New-LogFollower, Read-LogFollower, Read-LogLines, Sync-RunMods
