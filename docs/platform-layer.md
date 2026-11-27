# Platform layer

Keeps loader code (NeoForge now, Fabric later) out of the bot's common code. A trimmed copy of
BuildCraft's design (`../BuildCraft/docs/platform-layer.md`); read that for the reasoning behind each rule.

## Layout

- `com.legoj15.megablockbot` and everything outside `platform/<loader>/`: common code. Compiles against
  the JDK, vanilla Minecraft, solver-core and `platform` contracts only.
- `com.legoj15.megablockbot.core`: Minecraft-free contract types (JDK and solver-core only; `core.lang.model` also
  uses Gson, which Minecraft bundles on every node, see `docs/language-layer.md`); plain JUnit tests for it live in
  `src/test/java`.
- `com.legoj15.megablockbot.game`: everything that touches Minecraft for the bot. Bot nodes only (below).
- `com.legoj15.megablockbot.nav`: the navigation module, its own mod and jar (below). Sources in `src/nav/`.
- `com.legoj15.megablockbot.platform`: loader-neutral contracts (one interface per service, e.g.
  `LoaderServices`) and the `Platform` binder. Contracts never name a loader type.
- `com.legoj15.megablockbot.platform.neoforge`: the NeoForge implementation and the `@Mod` entrypoint.
  Each file keeps its `package` line live and wraps the rest in `//? if neoforge {` … `//?}`.
- `platform.fabric`: same shape under `//? if fabric {`, when the first Fabric node lands.

## Binding

`Platform.loader()` (and each later service accessor) reads a `static final` in its own lazy holder.
The holder's loader arms are the only loader directives outside `platform/<loader>/`. No ServiceLoader.
`NeoForgeEntrypoint` takes the mod event bus in its constructor and hands it to `NeoForgeLoaderServices`
(`bindModBus`) before `MegaBlockBot.init()`; only mod-bus events use it (GameTest registration today).

## Rules

- Contracts are Fabric-shaped: plain register calls made during init (`onServerStarted(listener)`).
  NeoForge implementations attach them to the right bus or queue them for the matching event.
- Outside `platform/<loader>/` and the binder holders, directives are about Minecraft versions only,
  with one exception: the `bot` constant (below). Never `!fabric`; write the positive constant. Inside
  `platform/<loader>/` files, `//? if bot {` marks code written and verified for the bot node's version only.
- Implementations read loader state per call and never cache loader statics in static initialisers.
- No NeoForge import outside `platform/neoforge/` and the nav module's own entrypoint (`NavModule`).

### What `LoaderServices` offers bot code

All are register calls made during init. Listeners run on the server thread, except `onRegisterCommands`, which
runs wherever the command tree is built (a worker thread while data loads).

- `configDir()`, `onServerStarted`, `onServerStopping`, `onServerStopped`, `onServerTick` (end of each tick),
  `onRegisterCommands` (the dispatcher).
- `onServerStopping` is skipped when the tick loop crashes (`MinecraftServer.runServer` posts the event only when the
  loop ends normally); `onServerStopped` (`ServerStoppedEvent`) runs on every path, after the server has saved and
  closed, so it is the place to drop per-server state and not one to save or log players out in.
- `onServerDataLoaded`, `onBlockBroken`, `onBlockPlaced`, `onChatMessage` and `onPlayerLoggedIn` name NeoForge events that differ on 26.x
  (`TagsUpdatedEvent` was split, `BlockEvent.BreakEvent` became `BreakBlockEvent`; `ServerChatEvent` and
  `PlayerEvent.PlayerLoggedInEvent` are unchecked there), so `NeoForgeLoaderServices` implements them under
  `//? if bot {` and throws `UnsupportedOperationException` on every other node. Enabling `bot_enabled` on a 26.x node fails to compile there until they are ported.
- `onServerDataLoaded`: once at server start (NeoForge: `ServerStartingEvent`, the first moment after the data
  load that has a server) and after every `/reload` (`TagsUpdatedEvent` `SERVER_DATA_LOAD` at LOWEST priority, so
  after NeoForge rebuilt the villager trades; the server comes from `ServerLifecycleHooks`).
- `onBlockBroken` / `onBlockPlaced` with a `BlockChangeListener`: after the fact, server side, never cancelling.
  NeoForge 21.1 has no after-the-fact block events, so the implementation takes `BreakEvent` /
  `EntityPlaceEvent` at LOWEST priority (cancelled ones are skipped) and delivers at the end of the tick. Player
  breaks only: explosions, pistons and fluids are not reported.
- `onPlayerLoggedIn`: a player has finished joining (NeoForge: `PlayerEvent.PlayerLoggedInEvent`, the last thing
  `placeNewPlayer` does, so the player is listed and in its level), server thread. A body's own join is reported too.
  A listener that throws fails that player's login, so it must catch what it can.
- `onChatMessage` with a `ChatListener`: a player's chat line (the raw text they typed), on the server thread. NeoForge
  takes `ServerChatEvent` at LOWEST priority, so a line another handler cancelled is skipped; it only observes and never
  cancels or edits the line. A body's own chat counts: a body is a player.
- `registerGameTests(Class<?>...)`: vanilla `@GameTest` methods; no-op unless GameTests are enabled.

## The `bot` switch and the `game` package

- `bot_enabled` is a per-node Gradle property: `false` in the root `gradle.properties`, `true` in
  `versions/1.21.1/gradle.properties`. `build.gradle.kts` turns it into the `bot` Stonecutter constant
  (`//? if bot {`). It is never derived from the node name.
- On a node with `bot_enabled=false` the `game` package is not compiled, and the bot-only resources
  (`megablockbot.mixins.json`, `META-INF/accesstransformer.cfg`, `data/megablockbot/structure/`) are not
  packaged. `core`, `platform` and `MegaBlockBot` compile on every node. The `GameTest`, mixin and access
  transformer wiring, the `gameTestServer` run, the nav module and its dependency in `neoforge.mods.toml` exist
  only on bot nodes.
- The only call from common code into `game` is `GameBootstrap.init()` in `MegaBlockBot.init()`, inside
  `//? if bot {`. Bot features register in `GameBootstrap`; code under `game` never names a loader type.
- Each feature has one `XFeature.init()` (`game.body.BodyFeature`, `game.resolver.ResolverFeature`,
  `game.nav.NavFeature`, ...) called from `GameBootstrap.init()`; it registers its hooks, its GameTest classes and
  its `/aiplayer` sub-commands (`game.command.AiPlayerCommand.add`).
- The tree's VCS form is the 26.3 node, where `bot` is false, so the `//? if bot` block is checked in commented
  out. Never edit that form by hand: write the live form, then run `./gradlew "Reset active project"`.
- `neoforge.mods.toml` is a Groovy template (`processResources` expand), not Stonecutter-processed: bot-only
  sections sit in bot scriptlets. Do not write the scriptlet syntax in a comment, it is parsed there too.
- Mixins: `megablockbot.mixins.json` (package `...game.mixin`) is applied by NeoForge on 1.21.1 with no refmap and no
  annotation processor: NeoForge runs Mojang-named classes, so there is nothing to remap. The config carries no
  `refmap` key. Do not add the mixin annotation processor to the build.

## Navigation module

- Port of LGPL-3.0 code, so it ships as its own jar under its own licence, nested in the main jar; the rest of
  the mod stays MIT. Mod id `megablockbot_nav`, package `com.legoj15.megablockbot.nav`, entrypoint `NavModule`,
  its own `neoforge.mods.toml` (`license="LGPL-3.0-or-later"`), mixin config and access transformer.
  `LICENSE` (LGPL-3.0) and `COPYING` (GPL-3.0) sit at the jar root.
- Licence boundary: nav code never calls back into `com.legoj15.megablockbot`; the main mod depends on it
  (required dependency in `neoforge.mods.toml`, bot nodes only) and calls into it.
- Build: a second source set `nav` in the per-node build (Stonecutter processes it like `main`, so version
  directives work in `src/nav/`; the source set exists on every node so a version switch rewrites it, but only a
  bot node compiles and packages it). `navJar` builds it; `jarJar(files(navJar))` nests it. Main compiles
  against `nav`'s output (`compileOnly`); dev runs load both mods.
- Engine: `com.legoj15.megablockbot.nav.engine` is the ported Automatone engine (upstream, commit and every
  modification in `src/nav/resources/UPSTREAM.md`, shipped at the jar root; keep it current when an engine file
  changes). No loader import in it. `NavRuntime` (logger, ids, the bounded `MegaBlockBot nav N` search pool, the
  settings directory) and `NavTags` are its loader-neutral services; `NavModule` sets the directories and shuts the
  pool down on server stop, and registers no tick event: whoever owns a driven entity calls `IBaritone.serverTick()`
  once per server tick. Entry: `IBaritone.KEY.get(entity).getCustomGoalProcess().setGoalAndPath(goal)`.
  `game.gametest.NavEngineGameTests` drives an armor stand with it (why not a zombie: the class comment).
- Bot side: `game.nav.BotNavigator.of(body)` is the only main-mod class that drives the engine (plain commands, a
  state to poll, ticked from the body's brain). The body implements the engine's input receiver and its inventory,
  interaction and hunger providers (`game.nav.Body*` adapters); breaking and placing go through the player's own
  `ServerPlayerGameMode`. `/aiplayer nav <name> goto|gotoxz|mine|stop|status` is the debugging command;
  `game.gametest.BotNavGameTests` is the acceptance check.
- Navigator contract a task relies on: `mine(blocks, wanted items, count)` is done when the body holds `count` more of
  the wanted items (stone drops cobblestone, so the block's own item is not the measure) and the engine is run with no
  count of its own; `explore` that runs out of chunks is DONE, one whose path search fails is FAILED; `failKind()` says
  why a command failed so that a task routes on it, never on the text; a stopped command also aborts a break in
  progress (the engine is no longer ticked to do it); `BotNavigator.sweep` (called every 100 ticks from `NavFeature`)
  drops the navigator of a body that was despawned and not replaced.
- Tasks (`game.task`): `TaskFeature.init()` registers the plan-leaf kinds `navigate`, `reach`, `mine`, `recover_drops`
  and `deposit` with `TaskRegistry` (called from `NavFeature.init()` until the bot runtime takes it over). They drive the
  body only through its navigator, its menus and its game mode, release the body themselves before they report (the
  executor does not abort a task that ended on its own) and take the break filter from `BreakRules`, the one place the
  block-safety feature will install its rules (default: everything may be broken). Failure mapping: unreachable path or
  other dimension `Unreachable`; no block of the kind in range after the bounded search `NoSource` (carrying the leaf's
  source id); a break the rules forbid `Protected`; a missing required tool `MissingTool`; a missing container
  `TargetGone`; too few items to deposit `MissingInput`. `recover_drops` never fails. `game.gametest.MoveAndMineGameTests`
  is their acceptance check; the navigator rules above are in `BotNavGameTests`.
- Evidence the nested jars are in: `unzip -l versions/1.21.1/build/libs/*.jar` lists `META-INF/jarjar/` with the
  solver jar and `megablockbot-nav-<version>.jar`; a non-bot node's jar has the solver only.

## solver-core

- The shared planner (`com.legoj15:solver`) is the sibling repo's standalone `SprawlCrafting/solver-core` build,
  consumed as a composite build (`includeBuild` in `settings.gradle.kts`) and nested with jar-in-jar, never
  shaded (two copies of `com.legoj15.solver` would split a package).
- `settings.gradle.kts` looks for the directory in this order and fails naming all three when none exists:
  the Gradle property or environment variable `MBB_SOLVER_CORE_DIR`; `../SprawlCrafting/solver-core`;
  `../../SprawlCrafting/solver-core` (a git worktree under `<parent>/MegaBlockBot-wt/<name>`). An override that
  points nowhere is skipped with a warning.
- The version is read from the located `solver-core/gradle.properties` (`solver_version`); jarJar range is
  `[<version>, <next major>.0.0)`. Nothing is pinned here.
- The solver builds into `solver-core/build/MegaBlockBot-<hash of this checkout's path>/` (solver-core keys its
  output directory on the including checkout), so the main checkout and each worktree build it side by side.
- `additionalRuntimeClasspath` is added where ModDevGradle's `versionCapabilities.legacyClasspath()` is true
  (1.21.1); MDG refuses it elsewhere. See the comment in `build.gradle.kts`.

## Build wiring

- Root `gradle.properties` sets `loader=neoforge`; a Fabric node sets `loader=fabric` in its own
  `versions/<node>/gradle.properties`. `build.gradle.kts` turns it into the `neoforge` / `fabric`
  Stonecutter constants. The loader is never parsed from the node name.
- Fabric nodes are named `<mc>-fabric` (`version("26.3-fabric", "26.3")` in `settings.gradle.kts`).
- When the first Fabric node lands, move the `neoForge {}` block and mods.toml expansion into a buildSrc
  convention plugin (`mbb.neoforge`), with its Loom twin, as BuildCraft did. Never gate two plugin DSLs
  inline.

## Checks

- `gradlew buildAll` builds every node's jar into `versions/<node>/build/libs/`.
- `gradlew :<node>:test` runs the plain JUnit 5 tests of `core` (`src/test/java`, no Minecraft on the classpath; Gson
  is added to it explicitly at 1.21.1's version). Every node's `check` runs them. `-Dmbb.<name>=...` on the command
  line reaches the test JVM; `-Dmbb.liveProbe=<base URL>` switches on the live language-model probe, which the
  default run skips (`docs/language-layer.md`).
- `scripts/smoke-server.ps1 [-Nodes …]` starts each node's dev server and passes when the log shows
  `MegaBlockBot ready (NeoForge on Minecraft <version>)`. Logs land in `build/smoke/`.
- `scripts/gametest.ps1 [-Node 1.21.1]` runs `:<node>:runGameTestServer` (bot nodes; game directory
  `versions/<node>/run-gameTestServer`, namespace `megablockbot`) and exits 0 only when
  `versions/<node>/run-gameTestServer/logs/latest.log` shows `All N required tests passed` with N > 0 and no failed
  line. The log, not the exit code, is the evidence: on 1.21.1 a startup failure exits 0. It prints the failed
  tests and their messages, and kills any server JVM left behind.
- `scripts/bot-server.ps1 <start|cmd|wait|log|status|stop> -Node <node>` runs one node's dev server headless for
  evidence runs: offline mode, loopback only, RCON on free ports. `cmd` sends a Minecraft command, `wait` blocks on a
  log regex (exit 0 match, 2 fail pattern, 1 timeout or server gone), `stop` ends the server and sweeps what is left.
  State and log are in `build/bot-server/`; `-Mods` jars are copied into `versions/<node>/run/mods` and removed again
  by the next start that does not list them. `Get-Help ./scripts/bot-server.ps1 -Full` has every option.
- `scripts/bot-goal.ps1 -Node <node> -Goal "<words>" [-Bot Bot1] [-At "x z"] [-Empty] [-Day] [-TimeoutSec 600]`
  gives a bot one goal on that running dev server and follows it: it spawns the bot, can stand it on the surface at
  `-At`, empty its inventory and fix daylight, then reads the log to the goal's result line and prints the result,
  status, inventory, position, deaths and reflex holds. Exit 0 satisfied, 1 any other result, 2 no result in time,
  3 no server. Use it for every end-to-end evidence run so runs can be compared.
- All three scripts import `scripts/lib/MbbNodes.psm1` for the command-line sweep that finds and kills a node's
  leftover JVMs (the server JVM by its `versions/<node>/` path, the Gradle client by the wrapper jar and task name).
- GameTest classes live in `com.legoj15.megablockbot.game.gametest`, use vanilla `@GameTest` with
  `templateNamespace = MegaBlockBot.MOD_ID, template = "empty"` (`megablockbot:empty`, 16x16x16 air) and are
  registered in `GameBootstrap`. `NeoForgeGameTests` converts them to test functions without NeoForge's class-name
  prefix on the template, because the prefix switch is a NeoForge annotation common code may not name.
