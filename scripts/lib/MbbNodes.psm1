# Helpers shared by the dev-server scripts (smoke-server.ps1, gametest.ps1, bot-server.ps1): which nodes exist, and finding and
# killing the processes a node's Gradle run leaves behind. Import with:
#   Import-Module (Join-Path $PSScriptRoot 'lib/MbbNodes.psm1') -Force

function Get-KnownNodes {
    param([Parameter(Mandatory)][string]$Root)
    @(Get-ChildItem (Join-Path $Root 'versions') -Directory | ForEach-Object Name)
}

function Get-NodeJvms {
    <# PIDs of java.exe processes belonging to a node's dev run of this repo: any whose command line names
       <root>/versions/<node>/, or runs :<node>:runServer or :<node>:runGameTestServer through this repo's wrapper.
       Matching by command line, not by a remembered PID, finds a server JVM orphaned by an early Gradle exit and
       never touches a process that reused a PID. #>
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Node
    )
    # Normalise slashes and doubled separators: Gradle writes both into command lines (gradlew.bat's wrapper
    # classpath is <root>\\gradle\wrapper\gradle-wrapper.jar). The pattern is a regex: one or more backslashes.
    $norm = { param($t) ($t -replace '/', '\' -replace '\\+', '\').ToLowerInvariant() }
    $rootN = & $norm $Root
    $serverMark = "$rootN\versions\$Node\"
    $clientMark = "$rootN\gradle\wrapper\gradle-wrapper.jar"
    $tasks = ":${Node}:runserver", ":${Node}:rungametestserver"
    # WMI fails now and then on a loaded machine ("A general error occurred"). An empty answer would read as "nothing is
    # running", so retry, and fail loudly rather than report no processes when it keeps failing.
    $processes = $null
    for ($try = 1; $null -eq $processes; $try++) {
        try {
            $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -Property ProcessId, CommandLine -ErrorAction Stop)
        }
        catch {
            # Seen failing for several seconds on end while other Gradle builds and servers run: be patient (about 30 s).
            if ($try -ge 12) { throw "Could not list java processes after $try tries: $($_.Exception.Message)" }
            Start-Sleep -Milliseconds ([Math]::Min(500 * $try, 3000))
        }
    }
    @($processes | Where-Object {
        $c = & $norm "$($_.CommandLine)"
        $c.Contains($serverMark) -or ($c.Contains($clientMark) -and ($tasks | Where-Object { $c.Contains($_) }))
    } | ForEach-Object { [int]$_.ProcessId })
}

function Stop-NodeJvms {
    <# Kills the node's JVMs (see Get-NodeJvms) and waits up to ten seconds. Returns the PIDs that survived. #>
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Node
    )
    foreach ($id in Get-NodeJvms -Root $Root -Node $Node) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 10; $i++) {
        $left = Get-NodeJvms -Root $Root -Node $Node
        if (-not $left) { return @() }
        Start-Sleep -Seconds 1
    }
    return $left
}

function Stop-GradleRun {
    <# Ends a Gradle run started with Start-Process: kills its process tree if it is still going, waits for it, then
       sweeps the node's leftover JVMs. Returns the PIDs that survived the sweep. #>
    param(
        [Parameter(Mandatory)]$Process,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Node
    )
    if (-not $Process.HasExited) { & taskkill.exe /T /F /PID $Process.Id 2>&1 | Out-Null }
    $Process.WaitForExit(30000) | Out-Null
    Stop-NodeJvms -Root $Root -Node $Node
}

Export-ModuleMember -Function Get-KnownNodes, Get-NodeJvms, Stop-NodeJvms, Stop-GradleRun
