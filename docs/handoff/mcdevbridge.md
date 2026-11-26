# McDevBridge as the evidence-run harness (handoff)

Scope: design section 7 of `AI-PLAYER-DESIGN.md`. Facts about `E:/GitHub/McDevBridge` (1.21.1 NeoForge node),
`E:/GitHub/BuildCraft` and `E:/GitHub/SprawlCrafting` as read on 2026-11-26. Every fact has a `path:line`.
"README" means `McDevBridge/README.md`; "doc only" means a doc claim I did not confirm in code or a run.
No token value is recorded here.

## 0. Summary of what changes design decisions

- The bridge is client-only (`NeoForgeEntrypoint.java:24`). It has no way to connect to a server. Joining is either a
  `--quickPlayMultiplayer host:port` program argument added to the dev run, or UI clicks through `/screenclick`.
- `/command` (the README's "run as the server") works only with an integrated server (`CommandHandler.java:83-88`).
  Against a dedicated server, server commands need RCON, or `/chat` with `command` (which runs as the player and needs op).
- The bridge binds 127.0.0.1 only (`BridgeServer.java:87`). The client must run on the same machine as the driver.
- No Gradle dependency exists in either consumer. The jar is copied into the dev run's `mods/` folder by a script.
- `~/.mcdevbridge/bridge.json` is one file per user; the last client to start overwrites it. Locking is by
  `~/.mcdevbridge/smoke.lock` (`tools/SmokeLock.ps1`).
- A 1.21.1 dedicated server needs `online-mode=false` for the dev client's offline session (BuildCraft `mp.py:368`).
- Server1's firewall blocks the Java 21 server JVM. `mp.py` works around it with an ssh tunnel (`mp.py:656-661`).
- MegaBlockBot's own `versions/1.21.1/run/server.properties` uses the ports `mp.py` refuses (25565, 25575) and has
  `enable-rcon=false`. See adjacent issues.

## 1. Endpoints of the 1.21.1 NeoForge node

Registration: `src/main/java/com/legoj/mcdevbridge/bridge/BridgeServer.java:98-187`. All routes are in the one shared
source tree; the 1.21.1 values come from the Stonecutter arms (noted per row).

Common rules (`BridgeServer.java`):
- Bind: `InetSocketAddress(127.0.0.1, port)`, port `-Dmcdevbridge.port` or 0 = ephemeral (`:83`, `:87`).
- Token: `UUID.randomUUID()` without dashes, 32 hex, new every launch (`:88`). Header name `X-MCDB-Token` (`:46`).
- Method check runs first: wrong method -> 405 (`:280-283`). Then token: missing or wrong -> 403 (`:284-288`).
- Body is read as a whole string; the handler parses JSON. An empty body is allowed for most handlers (`ChatHandler.java:47`
  and others use `body.isBlank()`).
- Handler throws any `Throwable` -> HTTP 500 with `{"error": "<Class>: <msg>"}` (`:293-297`). Success -> 200, JSON (`:299`, `:307-314`).
- Parameterised reads are POST with a JSON body; the handler never sees a query string (README:216-217).

| Method & path | Request fields (1.21.1 behaviour) | Response | Source |
|---|---|---|---|
| GET `/ping` | none | `{ok, mod, version, loader, minecraft, interactive, capabilities{...}}` | `BridgeServer.java:98-163` |
| GET `/state` | none | player pos/yaw/pitch/health/held item, `hit`, looked-at `block`, open `screen`, `fps` (README:224; handler not read) | `:164` `StateHandler` |
| POST `/screenshot` | `path` (required), `downscale` (default 1; ignored on 1.21.1) | `{written, path, width, height}`; relative path resolves against the client's run dir | `ScreenshotHandler.java:36-42`, `:84`, `:90-91`; `BridgeServer.java:130-134` |
| POST `/command` | `command` (required), `timeoutMs` (optional, widens only) | `{success, result, output[]}`; `{success:false, error:"no integrated server ..."}` without a single-player server | `CommandHandler.java:75-96` |
| POST `/inventory` | empty ok | `slots` (main 0-35), `equipment`, `selectedSlot`/`selectedItem`, open container `slots`, carried stack (README:225; handler not read) | `:170` `InventoryHandler` |
| POST `/entities` | `radius` (number, default 16.0; non-number refused) | loaded entities within radius, nearest first: id/type/name/pos/distance (+health for living) | `EntitiesHandler.java:30-36` |
| POST `/log` | `level` (min severity), `since` (cursor, default 0), `limit` (default 200) | `{installed, logs:[{id,time,level,logger,thread,message,throwable?}], nextCursor}` | `LogHandler.java:36-40`; javadoc `:13-24` |
| POST `/chat` | `action` (required): `read` (`limit` default 50) / `send` / `command`; `text` required for send and command | read: `{messages:[{text, source?, addedTime}]}`. `source` omitted on 1.21.1 (`ChatHandler.java:88-93`; `BridgeServer.java:120-124`). send -> `sendChat`; command or text starting with `/` -> `connection.sendCommand`, i.e. run as the player | `ChatHandler.java:46-120` |
| POST `/keybind` | `keyName`, `action`: `tap` / `press` / `release` | keybind actuated (tap = `KeyMapping.click`, press/release = `setDown`) | `KeybindHandler.java:33-37`, `:54-58` |
| POST `/look` | `mode` (`absolute` default, or `relative`), `yaw`, `pitch`, `updateOld` | resulting `{yaw,pitch}` | `LookHandler.java:31-34` |
| POST `/worldclick` | `action`: `left_click` / `right_click` / `middle_click` | `success` (entity or block targeted, or startAttack true), `startAttackResult` (left only), `hitType`, `message` | `WorldClickHandler.java:68-75`; `BridgeServer.java:142-146`; verification.md:73-76 |
| POST `/slotclick` | `slotIndex`, `clickType`: `left`, `right`, `shift-left`, `shift-right`, `middle`/`clone`, `throw`, `swap-N` | the carried stack | `SlotClickHandler.java:35-39`, `:62-71` |
| POST `/screenkeys` | `type`: `text` (`value`) or `key` (`key` name or raw GLFW code, `modifiers` default 0) | `nativeKey`, `keycode`, `nativeModifiers` for key presses | `ScreenKeysHandler.java:46-64`, `:86-90`; README:241 |
| POST `/screenclose` | empty ok | closes the open screen (container: close packet first) | `BridgeServer.java:181`; README:242 |
| POST `/screenclick` | one of `list:true`, `slotIndex`, `widgetText` (alias `buttonText`), `x`+`y` (GUI units), `fx`+`fy` (0..1); optional `button` (default 0, 0..7), `modifiers` | `list` returns widgets (text, rect, active, hovered), `guiLeft`/`guiTop`; a click returns `hit`, `slotIndex`, `screenClassAfter`, `nativeButton`, `nativeModifiers`, `overlay` | `ScreenClickHandler.java:124`, `:257-281`, `:291-293`; `:264-270` widget match; README:243 |
| POST `/screenhover` | same targets as screenclick, no `list`/`button` | moves cursor and leaves it (tooltips draw next frames) | `BridgeServer.java:184` -> `ScreenClickHandler.hover` |
| POST `/screenscroll` | same targets as hover, plus `scrollY` (default 1.0), `scrollX` (default 0), `notches` (default 1, 1..200) | `handled` count, echoed deltas | `ScreenClickHandler.java:136-172`; `BridgeServer.java:185` |
| POST `/input` | `{}` report, or `{"interactive": bool}` to switch | `{interactive, source, hooksInstalled, mouseGrabbed, gameFocused, osFocused, pauseOnLostFocus}` (README:246) | `InputMode.java:200-201`; `BridgeServer.java:187`, `:159-160` |

1.21.1 capability values in `GET /ping` (from the active Stonecutter arms, `BridgeServer.java:110-161`):
`saveToggle:false` (`:113-117`, needs 1.21.10), `chatSource:false` (`:120-124`, needs 26.1), `keybindHold:true`,
`screenshotDownscale:false` (`:130-134`), `scrollX:true` (`:137-141`), `attackResult:true`, `itemComponents:true`
(`:149-153`, needs 1.20.5), `namedRuntime` from `LoaderEnv` (true on NeoForge), `inputMode:true`.
Consequences on 1.21.1: `save-off`/`save-on` are not available; `/screenshot` ignores `downscale`.

Not in the bridge: no endpoint connects to a server, sends a server command over RCON, or reads the server log.

## 2. `tools/mcbridge.ps1`

Path: `E:/GitHub/McDevBridge/tools/mcbridge.ps1` (212 lines). Usage: `pwsh tools/mcbridge.ps1 <Action> [params]`.
- `-Action` (positional, default `state`), ValidateSet at `:39-42`: ping, state, command, screenshot, info, inventory,
  entities, log, chat, keybind, look, worldclick, slotclick, screenkeys, screenclose, screenclick, screenhover.
  **No `screenscroll` and no `input` subcommand.**
- Reads `-BridgeFile` (default `$env:USERPROFILE\.mcdevbridge\bridge.json`, `:104`) and fails if absent (`:109-112`).
  It does not check the pid or the version (`:114-116`).
- `Invoke-Bridge` (`:118-142`): `TimeoutSec = 30` (`:120`). An HTTP status is printed as an error and exits 1 (`:127-133`).
  A transport error retries once on `[::1]` (`:134-141`). Output is `ConvertTo-Json -Depth 8` (`:212`).

Examples (one per subcommand, from the script's switch `:144-210`):
- `info` -> `pwsh tools/mcbridge.ps1 info` (prints the whole bridge.json, token included; `:145`)
- `ping` -> `pwsh tools/mcbridge.ps1 ping` (`:146`)
- `state` -> `pwsh tools/mcbridge.ps1 state` (`:147`)
- `command` -> `pwsh tools/mcbridge.ps1 command -Command "save-all flush"` (`:148-151`; no timeoutMs parameter)
- `screenshot` -> `pwsh tools/mcbridge.ps1 screenshot -Path "E:/tmp/frame.png" -Downscale 1` (`:152-155`)
- `inventory` -> `pwsh tools/mcbridge.ps1 inventory` (`:156`)
- `entities` -> `pwsh tools/mcbridge.ps1 entities -Radius 32` (`:157`)
- `log` -> `pwsh tools/mcbridge.ps1 log -Level WARN -Since 0 -Limit 200` (`:158-163`)
- `chat` -> `pwsh tools/mcbridge.ps1 chat -ChatAction read -Limit 20` or `... chat -ChatAction command -Text "kill @s"` (`:164-174`)
- `keybind` -> `pwsh tools/mcbridge.ps1 keybind -KeyName key.inventory -KeyAction tap` (`:175-178`)
- `look` -> `pwsh tools/mcbridge.ps1 look -Yaw 90 -Pitch 20 -Mode absolute` (`:179-183`)
- `worldclick` -> `pwsh tools/mcbridge.ps1 worldclick -ClickAction right_click` (`:184`)
- `slotclick` -> `pwsh tools/mcbridge.ps1 slotclick -SlotIndex 0 -ClickType shift-left` (`:185`)
- `screenkeys` -> `pwsh tools/mcbridge.ps1 screenkeys -KeysType text -Value "hello"` or `-KeysType key -Key enter -Modifiers 0` (`:186-197`)
- `screenclose` -> `pwsh tools/mcbridge.ps1 screenclose` (`:198`)
- `screenclick` -> `pwsh tools/mcbridge.ps1 screenclick -WidgetText "Done" -Button 0` (`:199-209`; targets: `-List`, `-SlotIndex`, `-WidgetText`, `-X -Y`, `-FX -FY`)
- `screenhover` -> `pwsh tools/mcbridge.ps1 screenhover -FX 0.5 -FY 0.25` (`:199-209`; same targets, no button)

## 3. How other projects consume the bridge

No project declares McDevBridge as a Gradle dependency. Each copies the jar into a dev run's `mods/` folder.

Build side (McDevBridge): `./gradlew :1.21.1:build` (settings node name `1.21.1`, NeoForge; the node script is
`build.neoforge.gradle.kts`). Output `versions/1.21.1/build/libs/mcdevbridge-<mod_version>+mc1.21.1.jar`
(`build.neoforge.gradle.kts:101-112`; the `doFirst` deletes old `mcdevbridge-*.jar`). `mod_version` is in
`gradle/version.properties` (currently `1.4.9`, see adjacent issues). `MCDB_NO_BUMP=1` stops the odometer bump
(`stonecutter.gradle.kts:238`). The node's pins are `versions/1.21.1/gradle.properties`: MC 1.21.1, NeoForge 21.1.243
(`:2-3`), Java 21 (`:10`). Built jar present: `versions/1.21.1/build/libs/mcdevbridge-1.4.9+mc1.21.1.jar` (mtime 2026-11-22).

### BuildCraft (`E:/GitHub/BuildCraft`)
- Jar discovery and copy: `scripts/smoke/Invoke-InClientSmoke.ps1:341-353` (`Find-BridgeJar`, looks in
  `$McDevBridgeRoot/versions/<node>/build/libs` and `.claude/worktrees/*`; root from `MCDEVBRIDGE_ROOT` or
  `E:/GitHub/McDevBridge`, `:68-70`). Build when missing: `gradlew.bat :<node>:build` (`:361-366`).
- Copy into the run: `Initialize-RunDir` (`:355-371`) deletes other `mcdevbridge-*.jar` in
  `versions/<node>/run-smoke/mods` and copies the chosen one (`:370-371`).
- Two-client multiplayer runner: `scripts/smoke/multiplayer/mp.py`. `setup` copies the jar to
  `versions/<node>/run-smoke/mp-a/mods` and `mp-b/mods` (`:418-437`), and writes `options.txt` defaults (`:395-399`).
- Gradle switches for NeoForge: `scripts/smoke/multiplayer/mp.init.gradle` adds `--username <name>` and
  `--quickPlayMultiplayer <host:port>` to the client run's `programArguments` (`:12-18`), and sets the server run's
  `gameDirectory` from `-PmpServerDir` (`:20-24`). The game directory of the NeoForge client comes from `-PbcRunDir`
  in `buildSrc/src/main/kotlin/bc.neoforge.gradle.kts:161-163`.
- `mp.py` run switches: `run_switches` (`mp.py:325-336`).

### SprawlCrafting (`E:/GitHub/SprawlCrafting`)
- No script or Gradle file references McDevBridge (grep of `*.ps1`, `*.kts`, `*.psm1`). The only documented copy is
  manual: `docs/sophisticated-check.md:10` (`McDevBridge/versions/1.21.1/build/libs/mcdevbridge-*.jar` into
  `versions/1.21.1-neoforge/run/mods`).
- Node naming differs: SprawlCrafting uses `1.21.1-neoforge` (`testing/config/release.json:53`); McDevBridge uses `1.21.1`.
  The jar must still carry `+mc1.21.1`.
- `devclient.ps1` (65 lines): takes the smoke lock via `tools/SmokeLock.ps1` (`:38-39`, `:56`), starts
  `gradlew.bat :<node>:runClient` with a JDK (`:43`, `:50`, `:58-60`), releases the lock in `finally` (`:64`).
  The tools path defaults to the absolute `E:\GitHub\McDevBridge\tools` (`:38`).
- `runClientAndServer.ps1`: Windows Terminal split pane, `:<node>:runServer` and `:<node>:runClient`, no join arguments
  (`:14-15`); default node `26.1.2-neoforge` (`:9`).
- `build.neoforge.gradle.kts` runs: client `gameDirectory = run` (`:105`); `-Psc.connect=host:port` adds
  `--quickPlayMultiplayer host:port` (`:106-110`); `-Psc.world` adds `--quickPlaySingleplayer` (`:111-115`); server
  `gameDirectory = run-server` (`:122-125`). The directory `run-server` is not in the checkout.
- `testing/Start-PlaySession.ps1`: a production-style server on Server1 over WinRM and a Prism client here.
  `-OpName Dev` ops the gradle dev client (`:22`, `:75`); `-Modless` leaves the SprawlCrafting jar out (`:21`);
  ops.json is written at `:80-88`; `-NoClient` skips Prism (`:20`).

## 4. Attaching a 1.21.1 NeoForge dev client to a dedicated server

### 4a. Server side
- The McDevBridge build has a `server` run (`build.neoforge.gradle.kts:58-61`, no gameDirectory). It does not run the bridge.
- `online-mode=false` is required for the dev client's session. Written by BuildCraft `mp.py` (`:368`, with
  `enable-rcon=true`, `rcon.port`, `rcon.password` on `:368-369`), and by SprawlCrafting `ScServerOps.psm1:162`.
  The vanilla default is `online-mode=true` (not checked here; UNVERIFIED for a bare `runServer`).
- Ops: `ops.json` with the offline UUID, which is the MD5 v3 of `OfflinePlayer:<name>` (`mp.py:357-361`, `:377`;
  SprawlCrafting `Start-PlaySession.ps1:76-79`).
- Default dev username: `Dev` (doc only: `BuildCraft/docs/forge-port.md:344`, `SprawlCrafting/docs/progress.md:421`).
  Not read in moddev sources (UNVERIFIED).
- Default ports in `mp.py`: game 25661, RCON 25662. Refused: 25565, 25566, 25575 (`mp.py:66-89`).

### 4b. How the client is told to join
1. Quick-play program argument (what both projects use):
   - BuildCraft NeoForge: `mp.init.gradle:12-18` (`--quickPlayMultiplayer`), driven by `mp.py run_switches` (`:325-336`).
     `mp.py client` waits for the bridge's `listening on http://127.0.0.1:<port>` log line (`:950`, produced at
     `BridgeServer.java:197`), then matches the port in bridge.json (`:955-966`), then waits for the player via RCON `list`
     (`wait_player_joined`, `:911-934`).
   - SprawlCrafting NeoForge: `-Psc.connect` (`build.neoforge.gradle.kts:106-110`).
   - Evidence: BuildCraft `docs/forge-port.md:416` says quick-play joins worked on **1.21.1-forge** (server log names both
     players). No NeoForge 1.21.1 two-client run is documented in the files I read. SprawlCrafting `testing/README.md:59-66`
     (doc only) says the 26.1.2-neoforge dev launcher drops the flag and that 1.21.1-neoforge forwards it.
2. UI route through the bridge: `/screenclick` with `widgetText` (`ScreenClickHandler.java:264-270`) and `/screenkeys`
   text (`ScreenKeysHandler.java:46-64`). The sweep already clicks Singleplayer > Create New World through the bridge
   (`docs/verification.md:22-23`). No documented run clicks the Multiplayer or Direct Connection screens. UNVERIFIED.
3. There is no bridge endpoint or keybind for joining.

### 4c. Running a server command
- `POST /command` (the README's "run as the server"): refused unless `mc.hasSingleplayerServer()`
  (`CommandHandler.java:83-88`; README:338-339). **It cannot run a server command against a dedicated server.**
- `POST /chat` with `action:"command"`: sent as the player through `sendCommand` (`ChatHandler.java:108-112`). It works
  remotely, but the player needs op and the command runs with that player's permissions and selectors.
- RCON: `mp.py rcon <node> "<cmd>" ...` (`mp.py:1204-1207`) over the class `Rcon` (`:987-1024`, Source RCON, auth type 3,
  command type 2). Needs `enable-rcon=true`, `rcon.port`, `rcon.password` in server.properties.
- So a single McDevBridge command cannot run a server command on a dedicated server. The only single-command route
  found is RCON, which lives in BuildCraft's `mp.py` (not in McDevBridge).

### 4d. Network placement
- The bridge listens on 127.0.0.1 only (`BridgeServer.java:87`). Drive it from the machine running the client.
- The client connects to the server address (`mp.py server_ip`, `:92-105`). `mp.py` defaults to 127.0.0.1 and uses
  `MP_SERVER_HOST` for a remote server.
- Server1's firewall does not admit the Java 21 server JVM (measured 2026-11-22, `mp.py:656-661`). `mp.py` forwards both
  ports over ssh (`tunnel_start`, `:691-707`; `ensure_reachable`, `:719-758`). Whether to change the firewall is the
  user's call (`BuildCraft/docs/HANDOFF.md:17,23`, issue #238, doc only).

### 4e. Locks
- Any client run against the bridge must hold `~/.mcdevbridge/smoke.lock` (README:378-382). The lock format is written
  by `tools/SmokeLock.ps1` (`Enter-SmokeLock` `:104`, `Exit-SmokeLock` `:221`) and by BuildCraft's `SmokeGuard.ps1`
  (`Enter-SmokeLock`). `mp.py` writes the same JSON format (`lock_acquire` `:205-234`, `LOCK_OWNER` `:78`).

### 4f. Client launch arguments that exist (NeoForge node)
- `-Dmcdevbridge.enabled=true` is set by the run itself (`build.neoforge.gradle.kts:51`). The bridge also starts when
  the environment is not production (`BridgeServer.java:266-272`).
- `-Dmcdevbridge.port=<n>` fixes the port (`BridgeServer.java:83`). No build script passes it. How to pass a `-D` to
  `runClient` without editing the build is UNVERIFIED.
- `-Dmcdevbridge.interactive=false` or env `MCDEVBRIDGE_INTERACTIVE=0` selects non-interactive input (`InputMode.java:88-89`;
  README:306-315).
- Quick-play to a save: `--quickPlaySingleplayer <save>` via `-PquickWorld` (`build.neoforge.gradle.kts:52-56`).
- Quick-play to a server: `--quickPlayMultiplayer <host:port>` (see 4b).
- `--username <name>` (BuildCraft `mp.init.gradle:14`).

## 5. docs/verification.md conventions (read whole file)

- It is agent-facing and separates "ran" from "read" (`verification.md:1-6`). Statements of "ran" name the machine (Server1
  via `tools/remote/`) and the git sha (`:8-11`).
- It records every full sweep with its run ID (for example `20261113-185818-5216`, `:24`) and the count per node
  (19 menu steps + 14 world steps on the modern nodes, `:24-25`, `:48-58`).
- Verdict vocabulary from the sweep: PASS / FAIL / SKIP (`tools/Invoke-EndpointSweep.ps1:200-218`, summary `:397-399`).
  Legacy modules add DEVIATION for documented, asserted differences (`verification.md:110`).
- Proof bar in the doc: a fix is shown by a run, then a deliberate mutant is run to show the new check fails
  (`verification.md:95-99`). Findings list what the run caught (`:143-157`).
- A "Not run" section is mandatory and specific (`verification.md:159-169`). It names two clients alive at once, a
  dedicated server, `-Dmcdevbridge.enabled=false`, `-Dmcdevbridge.port`, a production install, and a real surviving client.
- Artefact locations:
  - Sweep result JSON: `build/sweeps/<Node>.json` (`Invoke-EndpointSweep.ps1:113`, `:137`, `:404-405`).
  - Gradle output of a client launch: `%TEMP%\mcdb-sweep-<Node>.log` (`:466`).
  - Screenshots from the sweep: `%TEMP%\mcdb-sweep-<Node>.png` and `-world.png` (`:579`, `:1034`).
  - Game log: `versions/<node>/run/logs/latest.log` (README:385-386; `Invoke-EndpointSweep.ps1:628`).
  - Remote run artefacts under the Server1 checkout: location not read (UNVERIFIED).
- The sweep deletes a throwaway world on exit (`-NewWorld`, `verification.md:22-23`). See adjacent issues.

## Adjacent issues

1. `~/.mcdevbridge/bridge.json` on this machine is stale. At 2026-11-26 it names a client pid that is not running
   (`tasklist` found nothing), port 25841, version 1.4.3. The jar in `build/libs` is 1.4.9. `mcbridge.ps1` reads it
   without checking liveness (`:114-116`). A stale file is removed only by a graceful `BridgeServer.stop()` (`:235-256`).
   Check `GET /ping` `version` against the expected jar before trusting a call.
2. `mcbridge.ps1` fixes the client timeout at 30 s (`:120`), and its `command` branch has no `timeoutMs` (`:148-151`).
   The server accepts up to 120 s (`CommandHandler.java:73`, `:93`). A long server command (`save-all flush` on a large world,
   `spreadplayers`, `place`) cannot get the longer wait through the helper. The raw HTTP API can.
3. `mcbridge.ps1` has no `screenscroll` or `input` subcommand (`:39-42`).
4. `McDevBridge/gradle/version.properties` is modified in the working tree (`git status`), and every build without
   `MCDB_NO_BUMP=1` bumps it (`stonecutter.gradle.kts:238`). Do not use the file's number as a pin.
5. The bridge's `info` action prints the token to stdout (`mcbridge.ps1:145`, `:212`). Do not paste its output into logs or docs.
6. MegaBlockBot `versions/1.21.1/run/server.properties` (git-ignored, `.gitignore:6`) has `server-port=25565`,
   `rcon.port=25575`, `enable-rcon=false`, `online-mode=false`, `level-type=minecraft\:normal`. Both port values are refused by
   `mp.py:87-89` and are real-server ports. `ops.json` is `[]`. Using this run dir for an evidence run means changing it
   first.
7. NeoForge pin mismatch: MegaBlockBot `versions/1.21.1/gradle.properties:2` pins NeoForge `21.1.265`; McDevBridge pins
   `21.1.243` (`versions/1.21.1/gradle.properties:3`). The bridge jar is built against 21.1.243. Loading it under 21.1.265
   is UNVERIFIED. MegaBlockBot's comment says its pins match BuildCraft; BuildCraft's file was not read.
8. `SprawlCrafting/devclient.ps1:38` hard-codes an absolute `E:\GitHub\McDevBridge\tools` default, and the script uses
   Windows-only `Start-Process` and `wt` (`runClientAndServer.ps1:14`). A MegaBlockBot runner that copies it inherits both.
9. `BuildCraft/scripts/smoke/multiplayer/mp.py:1-49` documents a dedicated-server two-client rig with a full lock and
   stop protocol. It is the closest existing precedent for this use. MegaBlockBot would need a copy or a cross-repo call
   (user decision).
10. Delegation ledger (`McDevBridge/docs/delegation-log.md`, the 2026-11-13 entry, final-review item (c)): `-NewWorld`
    clean-up may remove a save folder that appeared during the sweep. Reported open; the cleanup code was not read.
11. The McDevBridge `/ping` `interactive` field and `/input` are the only way to change input mode at runtime. The
    `MCDEVBRIDGE_INTERACTIVE` env var reaches a Gradle daemon's child JVM (README:308-315), which also means any shell
    session that set it affects every later client launch from that daemon (doc only).

## Open questions (not answerable from the repo)

1. Does `--quickPlayMultiplayer` join a 1.21.1 **NeoForge** dev client to a dedicated server here? Evidence: 1.21.1-forge
   (BuildCraft doc) and a doc claim for 1.21.1-neoforge (SprawlCrafting `testing/README.md`). Needs a live run.
2. Is `Dev` the default dev-run username on NeoForge 1.21.1? Not read in moddev.
3. Can the Multiplayer or Direct Connection screen be driven with `/screenclick` widget text and `/screenkeys` text?
   No run covers it.
4. Where does the dedicated server run: this machine (mp.py default, 127.0.0.1), or Server1 (through the ssh tunnel)?
   This is the user's call; Server1's firewall blocks the 1.21.1 server JVM.
5. Does the design (section 7) expect a dedicated server or an integrated one? An integrated server lets `/command` work;
   a dedicated server does not. The design text says only "attached to the same world".
6. Does `/chat` `command` as a non-op player, or a `command` sent over RCON, cover the server-side checks the evidence
   runs need? Depends on the scenarios, not on the bridge.
7. Should MegaBlockBot reuse BuildCraft's `mp.py` (cross-repo), copy it, or write its own RCON and join code?
8. Is `-Dmcdevbridge.port` settable without editing the build (for example via a JVM environment variable)? UNVERIFIED.
