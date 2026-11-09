# Platform layer

Keeps loader code (NeoForge now, Fabric later) out of the bot's common code. A trimmed copy of
BuildCraft's design (`../BuildCraft/docs/platform-layer.md`); read that for the reasoning behind each rule.

## Layout

- `com.legoj15.megablockbot` and everything outside `platform/<loader>/`: common code. Compiles against
  the JDK, vanilla Minecraft, solver-core and `platform` contracts only.
- `com.legoj15.megablockbot.platform`: loader-neutral contracts (one interface per service, e.g.
  `LoaderServices`) and the `Platform` binder. Contracts never name a loader type.
- `com.legoj15.megablockbot.platform.neoforge`: the NeoForge implementation and the `@Mod` entrypoint.
  Each file keeps its `package` line live and wraps the rest in `//? if neoforge {` … `//?}`.
- `platform.fabric`: same shape under `//? if fabric {`, when the first Fabric node lands.

## Binding

`Platform.loader()` (and each later service accessor) reads a `static final` in its own lazy holder.
The holder's loader arms are the only loader directives outside `platform/<loader>/`. No ServiceLoader.

## Rules

- Contracts are Fabric-shaped: plain register calls made during init (`onServerStarted(listener)`).
  NeoForge implementations attach them to the right bus or queue them for the matching event.
- Outside `platform/<loader>/` and the binder holders, directives are about Minecraft versions only.
  Never `!fabric`; write the positive constant.
- Implementations read loader state per call and never cache loader statics in static initialisers.

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
- `scripts/smoke-server.ps1 [-Nodes …]` starts each node's dev server and passes when the log shows
  `MegaBlockBot ready (NeoForge on Minecraft <version>)`. Logs land in `build/smoke/`.
