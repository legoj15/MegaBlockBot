# Upstream and modifications

The package `com.legoj15.megablockbot.nav.engine` is a modified copy of the Automatone pathing engine
(server-side Baritone) as embedded in PlayerEngine. It is licensed under the GNU Lesser General Public License,
version 3 or (at your option) any later version; see `LICENSE` and `COPYING` next to this file. Every copied file
keeps its original licence header.

- Upstream repository: https://github.com/Goodbird-git/PlayerEngine
- Branch: `1.21.1-arch`
- Commit: `aa4ad2d5ec2a834107c76cdb58af732acb192ecc` (2026-01-29)
- Upstream directory: `common/src/main/java/` + the PlayerEngine package + `/automaton/` (278 files), plus the eight
  mixins under the PlayerEngine package's `mixins/baritone/`.
- Ported: 2026-11-26, for MegaBlockBot's navigation module (`megablockbot_nav`).

**These files are modified.** The changes are listed below. Anything not listed is as upstream has it.

## Mechanical changes (every file)

- Package and import lines moved from the PlayerEngine package to `com.legoj15.megablockbot.nav.engine`
  (sub-packages kept). No other formatting change.
- The PlayerEngine root class is replaced by the module's own `com.legoj15.megablockbot.nav.NavRuntime`: logger
  (log4j, named "MegaBlockBot Navigation"), mod id `megablockbot_nav` and mod name. Files touched only by this:
  `behavior/PathingBehavior`, `pathing/calc/AStarPathFinder`, `pathing/calc/AbstractNodeCostSearch`,
  `process/BuilderProcess`, `process/FarmProcess`, `process/GetToBlockProcess`, `process/MineProcess`,
  `cache/ContainerMemory`, `event/GameEventHandler`, `utils/NotificationHelper`,
  `utils/schematic/MapArtSchematic`, `utils/schematic/format/defaults/SpongeSchematic`.

## Removed

- Client-only code (the module must load on a dedicated server): `client/CustomFishingBobberRenderer`,
  `utils/GuiClick`, `utils/PathRenderer`, `api/event/events/RenderEvent`, and the client-chat default of the
  `logger` setting in `api/Settings` (it now logs through `NavRuntime.LOGGER`).
- The chat command system: `command/` (46 files, `RenderCommand` among them) and `api/command/` (40 files).
  `IBaritone.getCommandManager()`, `IBaritoneProvider.getCommandSystem()` and their implementations in `Baritone`
  and `BaritoneProvider` are gone with it. The pause/resume control process that `Baritone`'s constructor
  registered through `DefaultCommands.controlCommands` is kept, moved out of the command package to
  `process/ExecControlProcess` (with `isPaused()` / `setPaused(boolean)` since no command sets it any more).
- Fishing: `entity/CustomFishingBobberEntity`, its entity type registration, and `process/FishingProcess`
  (with `Baritone.getFishingProcess()`).
- Accessor interfaces that only needed visibility, now covered by the access transformer or by public members:
  `api/utils/IBucketAccessor` (`BucketItem.content` is public on NeoForge 1.21.1), `api/utils/IEntityAccessor`
  (`Entity.getEyeHeight(Pose)` is public), `utils/accessor/ILivingEntityAccessor` (access transformer on
  `LivingEntity.decreaseAirSupply` / `increaseAirSupply`), `utils/accessor/ServerCommandSourceAccessor` (only the
  removed `ClickCommand` used it).
- Mixins not carried over: `MixinBucketItem`, `MixinLivingEntity`, `MixinServerChunkManager`,
  `MixinServerCommandSource` (see above), `MixinUtil` (its pool shutdown is replaced, below). The root-level
  PlayerEngine mixins were never part of the engine and are not ported.
- Dependencies: no Architectury, Fabric, PlayerEngine or schematic4j dependency remains.

## Replaced

- Thread pool: upstream's unbounded `ThreadPoolExecutor` (4 core, `Integer.MAX_VALUE` max) is replaced by
  `NavRuntime.getExecutor()`: at most 4 daemon threads named `MegaBlockBot nav N`, extra tasks queue, shut down when
  the server stops (the module entrypoint listens for it), recreated on next use.
- Item tags `EMPTY_BUCKETS` / `WATER_BUCKETS`: `com.legoj15.megablockbot.nav.NavTags`, with the tag files shipped as
  `data/megablockbot_nav/tags/item/empty_buckets.json` and `water_buckets.json` (same contents as upstream). Upstream
  keeps them under `data/playerengine/tags/items/` (plural, the pre-1.21 folder name); its third tag file,
  `throwaway_blocks.json`, is not carried: no ported Java reads it. Used by
  `pathing/movement/CalculationContext` and `pathing/movement/movements/MovementFall`. The block tags in
  `api/Settings` (`build/ignored_blocks`, `build/ok_if_air`) now use the `megablockbot_nav` namespace; as upstream,
  no data files exist for them.
- `utils/DirUtil`: the game and config directories come from paths the loader entrypoint sets on `NavRuntime`, not
  Architectury's `Platform`.
- `utils/SettingsLoader`: reads `config/megablockbot_nav/settings.txt` only when it exists; it no longer creates an
  empty file when it is missing. `save` creates the directory first.
- `api/BaritoneAPI`: the provider is referenced directly instead of loaded with `Class.forName`.
- `utils/accessor/ServerChunkManagerAccessor`: was a mixin duck interface on `ServerChunkCache`; now a static helper
  over `ServerChunkCache.getVisibleChunkIfPresent`, which the access transformer makes public. Call sites in
  `utils/BlockStateInterface`, `cache/WorldScanner` and `utils/player/EntityContext` call the helper.
- `api/utils/RayTraceUtils`, `pathing/movement/CalculationContext`, `api/entity/LivingEntityInteractionManager`: call
  the now-visible members directly instead of casting to the removed accessor interfaces.
- `EnchantmentUtils.getEnchantmentLevel`: the PlayerEngine helper ignored its enchantment argument and always
  returned the Efficiency level. Replaced by `utils/EnchantmentUtils`, which reads the requested enchantment from the
  stack's enchantment component. Call sites: `api/entity/LivingEntityInteractionManager` (Efficiency, Aqua
  Affinity), `pathing/movement/CalculationContext` (Depth Strider), `utils/ToolSet` (Silk Touch, Efficiency).
  Silk Touch, Aqua Affinity and Depth Strider now take effect where upstream read the Efficiency level.

## Mixins kept (in `com.legoj15.megablockbot.nav.mixin`)

- `MixinItemStack`: unchanged (per-stack hash for `BlockOptionalMeta`).
- `MixinMobEntity`: unchanged (cancels `LivingEntity.serverAiStep` while the engine drives the entity).
- `MixinEntity`: the `getEyeHeight` invoker is removed; the removal hook is kept and extended (below).

## Bugs fixed on the way

- `api/component/EntityComponentKey` never removed an entry, so every entity that touched `IBaritone.KEY`,
  `IInteractionController.KEY` or `ISelectionManager.KEY` leaked its component. It now has `remove`, and
  `MixinEntity`'s removal hook calls it for all three keys after shutting the pathing down.
- `utils/player/EntityContext.feetPos()` passed block coordinates shifted left (`x << 4`) where chunk coordinates
  were expected, so the slab check read the wrong chunk. It now passes `x >> 4` / `z >> 4` of the feet position.
- `api/entity/LivingEntityInteractionManager.interactBlock`: the anonymous `UseOnContext` overrode
  `isSecondaryUseActive()` by calling itself (stack overflow if reached). It now returns whether the entity is
  sneaking.
- `api/entity/LivingEntityInventory` logged at INFO for every item on NBT read and write; now DEBUG.
- `pathing/path/PathExecutor` logged a WARN every tick while off the path; now DEBUG.

## Default settings changed

- `randomLooking` 0.01 to 0.0 and `randomLooking113` 2.0 to 0.0. `allowParkour` and `allowSwimming` stay false, as
  upstream.

## What the driven entity must provide

As ported, the engine only walks. Upstream drives its own entity type, which implements
`IInventoryProvider`, `IInteractionManagerProvider` and `IHungerManagerProvider`; nothing in this tree does yet.
Without them `CalculationContext` plans with no tools, no sprinting, no water bucket and no throwaway blocks, and
`EntityInteractionController` dereferences a null interaction manager on the first forced click (any mining,
placing or farming process, or an entity stuck inside a block). The bot's player body supplies all three, and its
block breaking goes through the player's own game mode, in the change that makes the engine drive it.
