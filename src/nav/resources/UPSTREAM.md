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
- `api/utils/BlockOptionalMeta.drops` passed `null` to `LootContext.Builder.create(Optional)`, which 1.21.1
  dereferences: every `mine` command threw a `NullPointerException`. It passes `Optional.empty()`.

## Driving a player body (MegaBlockBot issue 7)

The engine drives the bot's `ServerPlayer` body as well as plain `LivingEntity`s. What changed for that:

- **Entity interfaces extracted.** Upstream's providers returned its own concrete classes; a player has vanilla
  equivalents, so the engine now reads them through interfaces in `api/entity`:
  - `IEntityInventory` (new): `main()`, `offHand()`, `getSelectedSlot()` / `setSelectedSlot()`, `getItem`, `setItem`,
    `insertStack`. `LivingEntityInventory` implements it (accessor methods added). `IInventoryProvider` returns it.
    Callers moved from the public fields to the methods: `behavior/InventoryBehavior`,
    `pathing/movement/MovementHelper`, `pathing/movement/movements/MovementFall`, `process/BuilderProcess`,
    `process/MineProcess`, `utils/ToolSet`, `api/utils/IEntityContext`, `utils/player/EntityContext`.
  - `IHungerManager` (new): `getFoodLevel`, `getSaturationLevel`. `LivingEntityHungerManager` implements it;
    `IHungerManagerProvider` and `IEntityContext.hungerManager()` return it.
  - `IInteractionManager` (new): the break actions, mining state, progress, `interactBlock`, `interactItem`, and a
    default `continueBreaking(pos, side)` called each held tick (a client counts break progress there).
    `LivingEntityInteractionManager` implements it unchanged; `IInteractionManagerProvider` returns it.
  - `IMovementInputReceiver` (new): an entity that implements it receives the movement keys (player scale, -1 to 1)
    and the look as inputs, and the engine writes nothing into it.
- `utils/InputOverrideHandler`: for an input receiver, pushes the keys every tick at full strength (1, not
  upstream's 0.3) and ticks the break and place helpers every tick; keeps the path executor's sprint decision
  (`setSprinting`) instead of setting the entity's flag, and `clearAllKeys` resets that instead. Other entities: as
  upstream.
- `behavior/LookBehavior`: for an input receiver, the same rotation (scramble and pitch nudge included) is handed over
  as the look input instead of written into the entity.
- `pathing/path/PathExecutor`: the per-tick sprint decision goes through `InputOverrideHandler.setSprinting`.
- `utils/player/EntityInteractionController`: defined behaviour without an interaction manager (no break, no place:
  `clickBlock` false, right clicks `FAIL`) instead of a `NullPointerException`; calls `continueBreaking` before reading
  the progress; `clickBlock` refuses a block the owning engine's break filter forbids (`mayBreak`).
- `pathing/movement/Movement`: `prepared` fails the movement (`UNREACHABLE`) when a block is in the way and the entity
  has no interaction manager, or the break filter forbids it.
- `Baritone`: a break filter (`api/utils/IBlockBreakFilter`, new; `setBreakFilter`, `mayBreak`, a throwing filter
  counts as no), the last failure reason and the last `logDirect` message, for the engine's owner to report.
- `api/Settings.allowFlowingWater` (new, default false: upstream's rule) and `pathing/movement/MovementHelper.canWalkThrough`:
  with the setting on, a flowing water cell is entered under the same rule as still water (cell above free unless
  `allowSwimming`), so a stream or a river's current is crossed instead of being a wall. Landing a fall in flowing water
  (`MovementDescend`) stays excluded.
- `pathing/movement/CalculationContext`: `breakCostMultiplierAt` asks the break filter (on the search thread);
  `canSprint` is false at food 6 or below when the entity has a hunger manager; reads `fallDamagePenalty`.

## Mining: the owner's say over targets (MegaBlockBot issue 27)

- `api/utils/IMineTargetPolicy` (new) and `Baritone.setMineTargetPolicy` / `mineTargetPolicy`: the engine's owner
  chooses, among the blocks the mine process knows of (nearest first), the ones it may head for. `process/MineProcess.prune`
  (the one place the process narrows its targets: every tick, every rescan, the scan of dropped items) calls it after its own
  filters and sort and before it cuts the list to 64; it is handed the engine's own world reader
  (`CalculationContext.get`), because it runs on the search pool as well as the server thread. A policy that throws is
  logged and ignored. The bot uses it to leave aside the blocks the body gave up on and to take blocks in water last.
- `process/MineProcess.updateGoal`: with an empty known list (not in `legitMine`) it schedules a rescan on the search
  pool -- one at a time, the way `onTick` schedules its own (a world scan on the server thread stalls the tick) -- and
  asks the pathing to pause for that tick, where upstream ended the command there and then. A block the policy holds
  back is not in the list, so when the blocks it preferred are gone (mined, set aside) the list is empty while the world
  is not; the rescan fills the list, or -- finding nothing, or the policy holding every block back -- ends the command
  itself as it does when it knows none. The pause keeps the process alive while the pool works; the owner's "look again"
  covers the rest. (An earlier round of issue 27 did this rescan synchronously on the server thread.)
- `process/MineProcess.knownLocations()` and `blacklisted()` (new): copies of the targets it knows now and of the ones it
  gave up on because no path led to them, for the owner to read. The known list is volatile and the blacklist copy-on-write:
  the search pool replaces the one and reads the other while the server thread adds to it. A process that ends keeps its
  blacklist until the next command starts (`mine` with no filter used to wipe it): upstream blacklists the last known block
  and cancels in the same tick, so the owner would never see it.

## Chat, failures and threads

- `api/IBaritone.logDirect` and `api/utils/IEntityContext.logDebug` never write to chat; both log through
  `NavRuntime.LOGGER` at debug (`Baritone.logDirect` also keeps the text as the last message).
- `pathing/calc/AbstractNodeCostSearch.calculate` catches `Throwable` (was `Exception`) and records the reason.
- `process/ExploreProcess.onTick`: a failed path search records a failure reason (`Baritone.setLastFailure`) before the
  process ends. The process also ends with no reason when it has nothing left to explore; the bot's navigator reads
  the difference (reason: failed, none: done).
- `behavior/PathingBehavior.findPathInNewThread`: an `EXCEPTION` result queues `CALC_FAILED` (upstream queued nothing
  and the process re-requested the same failing search every tick); the search task runs in try/finally, so a throw
  anywhere still clears `inProgress` and fails the calculation with a reason.
- `NavRuntime` (module code): every pool task is wrapped, so a throw is logged and ends only that task.

## Hazards (design 2.2)

- `pathing/movement/MovementHelper.isNeverStand` (new): lava, fire and soul fire, cactus, magma, campfires, sweet
  berry bush, wither rose, powder snow, pointed dripstone. `canWalkThrough`, `canWalkOn` and `fullyPassable` reject
  them. Upstream already rejected lava, fire, cactus, magma, campfires and dripstone through other checks; powder snow,
  sweet berry bushes and wither roses were walkable.
- `MovementHelper.getMiningDurationTicks`: with `includeFalling` (the top cell of a move), a falling block above makes
  the cell unbreakable for the search. Upstream added the falling block's own mining time. `process/MineProcess`'s
  plausibility check calls it with `includeFalling` too, so a block with sand or gravel on top is not a mining target.
- `api/Settings.fallDamagePenalty` (new, default 0: upstream's costs) and
  `pathing/movement/movements/MovementDescend.dynamicFallCost`: a landing that deals damage (a fall over 3 blocks
  without water) costs that much more per point of damage.

## Default settings changed

- `randomLooking` 0.01 to 0.0 and `randomLooking113` 2.0 to 0.0. `allowParkour` and `allowSwimming` stay false, as
  upstream. The bot's navigator sets its own per-engine values over these (see `BotNavigator.configure`).
