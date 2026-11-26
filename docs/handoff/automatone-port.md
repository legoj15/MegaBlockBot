# Automatone port: facts for implementers

Source: `E:/GitHub/PlayerEngine`, branch `1.21.1-arch`, commit `aa4ad2d` (2026-01-29). Read-only. No source was edited.
Design context: `AI-PLAYER-DESIGN.md` section 2.2.

Path conventions: all paths are relative to `E:/GitHub/PlayerEngine`. `PE` means `common/src/main/java/com/player2/playerengine/`. `AU` means `PE/automaton/`. Line numbers refer to the file at `aa4ad2d`.
Bytecode facts come from `javap -p -c` on `libs/player2npc-neoforge-1.21.1-1.0.0.jar`, which is a binary and not in the source tree. The full disassembly is in the scratchpad at `C:\Users\legoj\AppData\Local\Temp\claude\9106388de7fb8cf3\6396b123-4ce9-4b54-8689-fcc0d5850820\scratchpad\p2npc\ae.txt`.

## 0. Findings that change the design

1. The entity PlayerEngine drives is not a `ServerPlayer`. It is `com.goodbird.player2npc.companion.AutomatoneEntity`, which `extends net.minecraft.world.entity.LivingEntity` and implements `IAutomatone`, `IInventoryProvider`, `IInteractionManagerProvider` and `IHungerManagerProvider`. It ships as a binary inside `libs/`. The repo has no `FakeServerPlayerEntity` and no class that extends `ServerPlayer`. Design 2.2 says this is unconfirmed; it is now confirmed (section 6).
2. Nothing in the source tree calls `PlayerEngineController.serverTick()`. The only call site is in the binary `AutomatoneEntity.tick()` (section 5). The engine is driven by the entity's own tick, not by an event.
3. Path search runs on `PlayerEngine.getExecutor()` (`PE/PlayerEngine.java:99`). The threaded search still reads live `ServerLevel` chunks through a mixin accessor with no snapshot and no lock (section 7).
4. Default `allowSwimming = false` (`AU/api/Settings.java:57`). With defaults the engine can walk the top layer of a lake, holding JUMP, but cannot path through deep water. Nothing is teleported (section 8).
5. There is no working chunk cache in this copy. `ICachedWorld.getLocationsOf` returns an empty list and `CachedChunk` is never constructed (section 7, adjacent issue 9).
6. Settings: each `Baritone` owns its own `Settings`, and an unset value falls through to the global instance (section 9).
7. `EnchantmentUtils.getEnchantmentLevel` ignores its enchantment argument and always returns the Efficiency level. Callers for Silk Touch, Aqua Affinity and Depth Strider get the wrong value. Do not copy it (adjacent issue 1).
8. The duck and accessor interfaces are load-bearing at runtime. `RayTraceUtils.java:54` casts any `Entity` to `IEntityAccessor`, so the mixin must be applied (section 3).
9. One mixin in the common config (`ClientBlockBreakMixin`) targets a client-only class. That is a likely dedicated-server failure; unverified (adjacent issue 10).

## 1. Inventory

Totals: 278 `.java` files, 27,664 lines, all under `AU`. No non-Java resources. Matches design 2.2.

| Directory (under AU) | Files | Lines | Notes |
|---|---|---|---|
| `.` (root) | 4 | 556 | `Baritone`, `BaritoneProvider`, `AdditionalBaritoneSettings`, `KeepName` |
| `api/` (all subdirs) | 148 | 8732 | interfaces, `Settings`, `entity/`, `utils/`, `component/`, `pathing/goals/` |
| `behavior/` | 5 | 1002 | `PathingBehavior`, `LookBehavior`, `MemoryBehavior`, `InventoryBehavior`, `Behavior` |
| `cache/` | 6 | 670 | `WorldData`, `WorldProvider`, `WorldScanner` (enum), `ContainerMemory`, `CachedChunk`, `WaypointCollection` |
| `client/` | 1 | 151 | `CustomFishingBobberRenderer` (client-only) |
| `command/` | 46 | 4795 | command system, `defaults/` has 37 files |
| `entity/` | 1 | 558 | `CustomFishingBobberEntity` |
| `event/` | 1 | 64 | `GameEventHandler` |
| `pathing/` | 23 | 5481 | `calc/` (7), `movement/` (5 + `movements/` 8), `path/` (3) |
| `process/` | 9 | 2960 | `CustomGoalProcess`, `MineProcess`, `BuilderProcess`, `FarmProcess`, `ExploreProcess`, ... |
| `selection/` | 2 | 248 | |
| `utils/` | 32 | 2447 | `InputOverrideHandler`, `BlockBreakHelper`, `BlockPlaceHelper`, `BlockStateInterface`, `PathingControlManager`, `SettingsLoader`, `ToolSet`, `accessor/` |

## 2. Coupling (imports that leave the allowed set)

Scan: every `import` in `AU` not starting with `java.`, `javax.`, `net.minecraft.`, `com.mojang.`, `org.slf4j`, `org.apache.logging`, `it.unimi`, `com.google`, or `com.player2.playerengine.automaton.`. Only the groups below remain. Nothing in `AU` imports Fabric, NeoForge, mixins, `player2api`, `eventbus`, `tasks`, `trackers` or `chains`.

### 2.1 Root class `PE/PlayerEngine.java` (25 importing files)

Importers: `Baritone.java:20`, `api/Settings.java:20`, `api/entity/LivingEntityInventory.java:48`, `behavior/PathingBehavior.java:20`, `cache/ContainerMemory.java:20`, `command/CommandUnhandledException.java:20`, `command/argument/ArgConsumer.java:20`, `command/defaults/ClickCommand.java:20`, `command/defaults/DefaultCommands.java:20`, `command/defaults/SetCommand.java:20`, `entity/CustomFishingBobberEntity.java:20`, `event/GameEventHandler.java:20`, `pathing/calc/AStarPathFinder.java:20`, `pathing/calc/AbstractNodeCostSearch.java:20`, `pathing/movement/CalculationContext.java:20`, `pathing/movement/movements/MovementFall.java:20`, `pathing/path/PathExecutor.java:20`, `process/BuilderProcess.java:20`, `process/FarmProcess.java:20`, `process/GetToBlockProcess.java:20`, `process/MineProcess.java:20`, `utils/NotificationHelper.java:20`, `utils/SettingsLoader.java:20`, `utils/schematic/MapArtSchematic.java:20`, `utils/schematic/format/defaults/SpongeSchematic.java:20`.

Uses:
- `LOGGER` (log4j `Logger`, `PE/PlayerEngine.java:36`): about 33 call sites, for example `AbstractNodeCostSearch.java:104`, `PathExecutor.java:113`.
- `MOD_ID` (`PE/PlayerEngine.java:38`): tag keys (`api/Settings.java:88,91`), config path (`utils/SettingsLoader.java:33`), command literal (`command/defaults/DefaultCommands.java:182`).
- `MOD_NAME` (`PE/PlayerEngine.java:39`): logger name (`api/entity/LivingEntityInventory.java:54`).
- `getExecutor()` (`PE/PlayerEngine.java:62`): `behavior/PathingBehavior.java:449`, `process/FarmProcess.java:149`, `process/GetToBlockProcess.java:122`, `process/MineProcess.java:127`.
- `EMPTY_BUCKETS`, `WATER_BUCKETS` (`PE/PlayerEngine.java:40-41`, item tags in `src/main/resources/data/playerengine/tags/items/`): `pathing/movement/CalculationContext.java:108`, `movements/MovementFall.java:93,99,120,121`.
- `FISHING_BOBBER` (`PE/PlayerEngine.java:46`): `entity/CustomFishingBobberEntity.java:99`.
- `id(String)` (`PE/PlayerEngine.java:58`): `command/defaults/ClickCommand.java:34`.
- `DefaultCommands.registerAll()` is called from `PlayerEngine.onInitialize()` (`PE/PlayerEngine.java:66-67`). The `Baritone` constructor touches the static `DefaultCommands.controlCommands` (`AU/Baritone.java:95`), so the command system is part of construction.

### 2.2 Other non-automaton packages

- `com.player2.playerengine.util.EnchantmentUtils`: `api/entity/LivingEntityInteractionManager.java:24`, `pathing/movement/CalculationContext.java:30`, `utils/ToolSet.java:26`. The helper is buggy (adjacent issue 1).
- `dev.architectury.event.events.common.CommandRegistrationEvent`: `command/defaults/DefaultCommands.java:44`.
- `dev.architectury.platform.Platform`: `utils/DirUtil.java:22` (`Platform.getConfigFolder()` at line 30).
- `org.apache.commons.io.FilenameUtils`: `command/defaults/BuildCommand.java:35`, `utils/schematic/format/DefaultSchematicFormats.java:30`.
- `org.apache.commons.lang3.ArrayUtils`: `pathing/movement/movements/MovementAscend.java:39`, `MovementDescend.java:42`. `SystemUtils`: `utils/NotificationHelper.java:27`.
- JetBrains annotations (`Nullable`, `NotNull`, `Contract`): about 15 files. Compile-time only.
- `org.joml.Matrix4f`, `Matrix3f`, `Vector4f`: `api/event/events/RenderEvent.java:21`, `client/CustomFishingBobberRenderer.java:38-39`, `utils/GuiClick.java:50-51`. These are client rendering.
- `org.slf4j.Logger`: `api/entity/LivingEntityInteractionManager.java:66` (its logger is built from `com.mojang.logging.LogUtils`, line 69) and `entity/CustomFishingBobberEntity.java:65`.
- `org.apache.logging.log4j.LogManager`, `Logger`: `api/entity/LivingEntityInventory.java:50-51,54`.
- Guava (`com.google.common`): `behavior/LookBehavior.java:25`, `api/entity/LivingEntityInventory.java:21`, `cache/CachedChunk.java:20`, `pathing/movement/movements/Movement{Ascend,Descend,Diagonal,Downward,Pillar,Traverse}.java` (`ImmutableSet`).
- Gson (`com.google.gson`): `process/ExploreProcess.java:32-33`, `command/defaults/ExploreFilterCommand.java:28`, `api/utils/MyChunkPos.java:20`.
- fastutil (`it.unimi.dsi.fastutil`): `api/pathing/goals/GoalNear.java:22-23`, `GoalRunAway.java:21-22`, `api/utils/BlockOptionalMeta.java:21-22`, `api/pathing/calc/Avoidance.java:21`, `utils/pathing/Favoring.java:25`, `pathing/calc/AbstractNodeCostSearch.java:30`, `process/BuilderProcess.java:49`, `process/ExploreProcess.java:34`, `utils/type/VarInt.java:20-21`, `utils/schematic/format/defaults/SpongeSchematic.java:23`.

### 2.3 Client-only code sitting inside `AU`

These must be dropped or isolated in a server-only port. They touch `Minecraft.getInstance()` or the client renderer:
- `client/CustomFishingBobberRenderer.java` (`Minecraft.getInstance()` at line 85).
- `utils/GuiClick.java` (lines 69-210).
- `utils/PathRenderer.java:24`. This is a static initializer that calls `Minecraft.getInstance()`, so loading the class on a server fails.
- `command/defaults/RenderCommand.java:39`.
- `api/Settings.java:217`. The `logger` default is a lambda that calls `Minecraft.getInstance()`. No call site in the repo invokes it.

### 2.4 Reflection

- `api/BaritoneAPI.java:51`: `Class.forName("com.player2.playerengine.automaton.BaritoneProvider").getField("INSTANCE")`. The package name is a string literal and must change with the package.

## 3. Mixins, accessors and access files

No access widener or access transformer exists in the repo (`git ls-files` shows none). Mixin config is the only bytecode-level mechanism.

Mixin config: `common/src/main/resources/playerengine.mixins.json`. Line 2 `"required": true`. Line 3 package `com.player2.playerengine.mixins`. Line 10 `"client": []` (empty). Lines 12-29 `"mixins"`: 9 root mixins and 8 `baritone.*` mixins, 17 in total. Also `"compatibilityLevel": "JAVA_17"`.

Fabric: `fabric/src/main/resources/fabric.mod.json:25-27` lists `playerengine.mixins.json`.

NeoForge: `neoforge/src/main/resources/META-INF/neoforge.mods.toml:37-38` is `[[mixins]] config = "playerengine.mixins.json"`. Line 7 is `modId = "playerengine"`. Line 4 is `license = "GNU LGPL 3.0"`. Dependencies: neoforge `[21.1,)` (around line 14), minecraft `[1.21.1,)`, architectury `[13.0.8,)` ordering AFTER. The description on lines 9-11 is the template text "This is an example description!".

NeoForge bootstrap: `neoforge/src/main/java/com/player2/playerengine/forge/PlayerEngineForge.java:12-19`. `@Mod(PlayerEngine.MOD_ID)`. The constructor calls `PlayerEngine.onInitialize()` and, on the client, `PlayerEngineClient.onInitializeClient()`.

### 3.1 Mixins used by `AU` (the port must re-create these)

Accessor and duck interfaces that `AU` depends on, and where each is implemented:

| Interface (in AU) | Implemented by mixin | Target | Use in AU |
|---|---|---|---|
| `utils/accessor/ILivingEntityAccessor.java:20-23` (`automatone$getNextAirUnderwater`, `automatone$getNextAirOnLand`) | `mixins/baritone/MixinLivingEntity.java:9-17` (`@Invoker decreaseAirSupply`, `increaseAirSupply`) | `LivingEntity` | `pathing/movement/CalculationContext.java:144-145` |
| `api/utils/IEntityAccessor.java:23-25` (`automatone$invokeGetEyeHeight(Pose)`) | `mixins/baritone/MixinEntity.java:19-26` (`@Invoker getEyeHeight`) | `Entity` | `api/utils/RayTraceUtils.java:54` (any entity, not only living) |
| `api/utils/IBucketAccessor.java:22-24` (`getFluid()`) | `mixins/baritone/MixinBucketItem.java:10-20` (`@Shadow content`) | `BucketItem` | `api/entity/LivingEntityInteractionManager.java:348,357,373` |
| `api/utils/accessor/IItemStack.java:20-21` (`getBaritoneHash()`) | `mixins/baritone/MixinItemStack.java:14-49` (`@Unique baritoneHash`, injects `<init>*` RETURN at 29-35 and `setDamageValue` TAIL at 37-43) | `ItemStack` | `api/utils/BlockOptionalMeta.java:88,106` |
| `utils/accessor/ServerChunkManagerAccessor.java:47-49` (`automatone$getChunkNow(int,int)` returns `LevelChunk`) | `mixins/baritone/MixinServerChunkManager.java:12-23` (`@Shadow getVisibleChunkIfPresent`, returns `ChunkHolder.getTickingChunk()`) | `ServerChunkCache` | `utils/BlockStateInterface.java:53,76,92`, `cache/WorldScanner.java:55,80`, `utils/player/EntityContext.java:108` |
| `utils/accessor/ServerCommandSourceAccessor.java:72-74` (`automatone$getOutput()`) | `mixins/baritone/MixinServerCommandSource.java:9-14` (`@Accessor("source")`) | `CommandSourceStack` | `command/defaults/ClickCommand.java:45` |

Behavioural mixins in `mixins/baritone/` that depend on `AU`:
- `MixinMobEntity.java:12-30`: `@Inject serverAiStep` at HEAD, cancellable. If `BaritoneProvider.INSTANCE.isPathing(this)` (`BaritoneProvider.java:50-53`), it saves `zza`, calls `setSpeed(MOVEMENT_SPEED attribute)`, restores `zza` and cancels. This is how vanilla AI is suppressed while the engine drives input.
- `MixinEntity.java:28-36`: `@Inject setRemoved` RETURN. If the level is server-side and the entity has an `IBaritone`, it calls `PathingBehavior.shutdown()`. This is the only removal hook, and it does not delete the component entry (section 6).
- `MixinUtil.java:15-31`: `@Inject Util.shutdownExecutors` RETURN shuts down `PlayerEngine.getExecutor()`, `TTSManager` and `AuthenticationManager` executors. The last two are `player2api`, not `AU`.

### 3.2 Root-level mixins (not used by AU)

Nothing in `AU` references these. All are in `PE/mixins/`:
- `ClientBlockBreakMixin.java:16` targets `net.minecraft.client.multiplayer.MultiPlayerGameMode`, a client-only class (import at line 6). Injects `continueDestroyBlock` HEAD (21) and `stopDestroyBlock` HEAD (29) and publishes to the PE event bus. See adjacent issue 10.
- `PlayerCollidesWithEntityMixin.java:12-21` targets `Player`, and imports `LocalPlayer` (line 5). `@Redirect touch` replaces `Entity.playerTouch`.
- `EntityAnimationSwungMixin.java:12-19` targets `LivingEntity.swing(InteractionHand)` HEAD and publishes `EntitySwungEvent`.
- `PlayerDamageMixin.java:12-19` targets `LivingEntity.hurt` HEAD and publishes `PlayerDamageEvent`.
- `WorldBlockModifiedMixin.java:14-27` targets `Level.onBlockStateChange` HEAD and publishes `BlockPlaceEvent` when a redstone conductor appears. It does not feed any cache.
- `LivingEntityMixin.java:7-10` (interface, `@Accessor attackStrengthTicker`), `ClientConnectionAccessor.java:7-10` (`Connection.tickCount`), `MixinAbstractFurnaceBlockEntity.java:8-11` (`dataAccess`), `PersistentProjectileEntityAccessor.java:7-11` (`inGround`).

## 4. Entry points and call sequence

### 4.1 Obtaining the engine for an entity

- `IBaritone.KEY.get(Object)` where `KEY = new EntityComponentKey<>(Baritone::new)` (`AU/api/IBaritone.java:47`). `EntityComponentKey.get` (`AU/api/component/EntityComponentKey.java:46-52`) takes any `LivingEntity` and keys by UUID in a `HashMap` (line 30). It throws `NoSuchElementException` for non-living objects (line 50). Lazy construction: `new Baritone(entity)`.
- `IAutomatone.getBaritone()` default method (`AU/api/entity/IAutomatone.java:23-25`) returns `IBaritone.KEY.get(this)`.
- `BaritoneAPI.getProvider().getBaritone(LivingEntity)` (`AU/api/BaritoneAPI.java:28-30`; `AU/BaritoneProvider.java:42-48`). It throws `IllegalStateException` on client (line 44). The provider is loaded reflectively in the static initializer of `BaritoneAPI` (lines 49-55).
- `BaritoneProvider.INSTANCE` (`AU/BaritoneProvider.java:34`) owns the global `Settings` (line 35) and reads `config/playerengine/settings.txt` (`AU/utils/SettingsLoader.java:33-60`).

### 4.2 Construction order (`AU/Baritone.java:75-96`)

1. `settings = new Settings()` (line 76). Per-bot instance.
2. `gameEventHandler = new GameEventHandler(this)` (line 77).
3. `playerContext = new EntityContext(player)` (line 78).
4. `PathingBehavior`, `LookBehavior`, `MemoryBehavior`, `InventoryBehavior`, `InputOverrideHandler` (lines 79-83). Each calls `Behavior(baritone)` which calls `registerBehavior` (`AU/behavior/Behavior.java`), so they are registered as listeners in this order.
5. `PathingControlManager` (line 84). It registers an anonymous listener whose `onTickServer` calls `postPathingTick()`.
6. Processes (lines 85-93): Follow, Mine, CustomGoal, GetToBlock, Builder, Explore, Backfill, Farm, Fishing. These are not listeners. `BaritoneProcessHelper` does not extend `Behavior`.
7. `commandManager = new BaritoneCommandManager(this)` (line 94).
8. `execControlProcess = DefaultCommands.controlCommands.registerProcess(this)` (line 95).

### 4.3 Issuing a goal

- `IBaritone.getCustomGoalProcess()` (`AU/api/IBaritone.java:63`) returns `ICustomGoalProcess`.
- `ICustomGoalProcess`: `setGoal(Goal)` (line 23), `path()` (line 25), `getGoal()` (line 27), and default `setGoalAndPath(Goal)` (lines 29-32) which is `setGoal` then `path`.
- `CustomGoalProcess.setGoal` (`AU/process/CustomGoalProcess.java:37-47`) moves state NONE to GOAL_SET, or EXECUTING to PATH_REQUESTED. `path()` (line 49) sets PATH_REQUESTED.
- Goal types (`AU/api/pathing/goals/`): `GoalBlock(BlockPos)` (`GoalBlock.java:29`) and `GoalBlock(int,int,int)` (line 33); `GoalTwoBlocks` (`:29`, `:33`); `GoalNear(BlockPos,int)` (`GoalNear.java:32`); `GoalXZ(int,int)` (`GoalXZ.java:31`) and `GoalXZ(BetterBlockPos)` (line 36); `GoalYLevel(int)` (`GoalYLevel.java:26`); `GoalRunAway(double, BlockPos...)` (`GoalRunAway.java:31`) and `(double, Integer, BlockPos...)` (line 35); `GoalAxis(int)` (`GoalAxis.java:26`); `GoalComposite(Goal...)` (`GoalComposite.java:25`); `GoalInverted(Goal)` (`GoalInverted.java:23`); `GoalStrictDirection(BlockPos, Direction)` (`GoalStrictDirection.java:31`); `GoalGetToBlock(BlockPos)` (`GoalGetToBlock.java:29`).
- `Goal` (`AU/api/pathing/goals/Goal.java`): `isInGoal(int,int,int)` and `heuristic(int,int,int)` are abstract. `isInGoal(BlockPos)`, `heuristic(BlockPos)` and `heuristic()` (returns 0.0) are defaults.
- PlayerEngine's own goal classes are in `PE/util/baritone/` (`GoalAnd`, `GoalBlockSide`, `GoalDodgeProjectiles`). They are outside `AU` and implement `Goal`.

### 4.4 Minimal "walk to x,y,z" as PlayerEngine does it

Evidence in PE: `tasks/movement/GetToBlockTask.java:96-97` builds `new GoalBlock(this.position)`. `tasks/movement/CustomBaritoneGoalTask.java:188` calls `getCustomGoalProcess().setGoalAndPath(cachedGoal)` when `!isActive()` and `isSafeToCancel()`. `tasks/entity/AbstractDoToEntityTask.java:90-91` shows the same pattern with `GoalRunAway`.

Sequence:
1. `IBaritone b = IBaritone.KEY.get(entity)` (or `entity.getBaritone()`).
2. `b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z))`.
3. Each server tick, the entity's tick must call `b.serverTick()` (section 5). PlayerEngine does this through `PlayerEngineController.serverTick()`.
4. Progress: `b.getCustomGoalProcess().isActive()` (`AU/api/process/IBaritoneProcess.java:23`) and `b.getPathingBehavior().isPathing()` (`AU/api/behavior/IPathingBehavior.java:47`).
5. Arrival: `goal.isInGoal(entity.blockPosition())`, which is `CustomBaritoneGoalTask.isFinished` (`tasks/movement/CustomBaritoneGoalTask.java:197-203`). The engine also drops control on arrival when `goal.isInGoal(feetPos) && goal.isInGoal(pathStart())` (`AU/process/CustomGoalProcess.java:77`) and fires `PathEvent.AT_GOAL` (`AU/behavior/PathingBehavior.java:165`).
6. Cancel: `b.getCustomGoalProcess().onLostControl()` (`AU/process/CustomGoalProcess.java:98-101`), then `b.getPathingBehavior().forceCancel()` (`AU/api/behavior/IPathingBehavior.java:55`; impl `AU/behavior/PathingBehavior.java:334-340`), then `b.getInputOverrideHandler().clearAllKeys()`. PlayerEngine's cancel is `PE/PlayerEngineController.java:176-177`. `CustomBaritoneGoalTask.onStop` does the forceCancel at line 207.

### 4.5 Status and failure reporting

- `IBaritone.isActive()` (`AU/api/IBaritone.java:115`; impl `AU/Baritone.java:177-179`) is `PathingControlManager.isActive()`, which is true while any process is active. It is not a path-state query.
- `IPathingBehavior` queries: `isPathing()` (line 47; impl `PathingBehavior.java:256-258`, which is `hasPath() && !pausedThisTick`); `hasPath()` (line 49); `getPath()` (line 57); `getGoal()` (line 45); `isSafeToCancel()` (line 69; impl line 274); `pathStart()` (line 67; impl line 384); `estimatedTicksToGoal()` (line 43; impl line 347); `ticksRemainingInSegment()` (defaults, lines 30-41).
- Failure events: `IBaritone.getGameEventHandler()` (`AU/api/IBaritone.java:75`) returns `IEventBus`, whose `registerEventListener(IGameEventListener)` is at `AU/api/event/listener/IEventBus.java:21`. `IGameEventListener` has `onTickServer()`, `onBlockInteract(BlockInteractEvent)` and `onPathEvent(PathEvent)`. `AbstractGameEventListener` gives no-op defaults.
- `PathEvent` (`AU/api/event/events/PathEvent.java:20-32`): CALC_STARTED, CALC_FINISHED_NOW_EXECUTING, CALC_FAILED, NEXT_SEGMENT_CALC_STARTED, NEXT_SEGMENT_CALC_FINISHED, CONTINUING_ONTO_PLANNED_NEXT, SPLICING_ONTO_NEXT_EARLY, AT_GOAL, PATH_FINISHED_NEXT_STILL_CALCULATING, NEXT_CALC_FAILED, DISCARD_NEXT, CANCELED.
- Events are queued from the pool thread into a `LinkedBlockingQueue` (`AU/behavior/PathingBehavior.java:63`) and dispatched on the server thread by `dispatchEvents()` (line 73), which runs at the start and end of `onTickServer` (lines 84-91).
- `CALC_FAILED` is queued from the pool lambda (`PathingBehavior.java:470`) when there is no path and the result is not CANCELLATION or EXCEPTION. `NEXT_CALC_FAILED` is at line 481.
- A mid-path failure emits no failure event. `PathExecutor.cancel()` sets `failed` (`AU/pathing/path/PathExecutor.java:550-556`). `PathingBehavior.tickPath` then nulls `current` and re-plans, which queues CALC_STARTED (line 159). Only a failed re-plan produces CALC_FAILED.
- `CustomGoalProcess` gives up control when the calc fails during EXECUTING (`CustomGoalProcess.java:73-75`, `calcFailed` then `onLostControl`). Its `isActive()` goes false with no callback.

## 5. Ticking, movement application, block break and place

### 5.1 Driver: `AutomatoneEntity.tick()` (bytecode, `libs/player2npc-neoforge-1.21.1-1.0.0.jar`)

Offsets in the disassembly:
1. Offset 12: `LivingEntityInteractionManager.update()`. Advances block-break progress.
2. Offset 19: `LivingEntityInventory.updateItems()`. Calls `ItemStack.inventoryTick` (`AU/api/entity/LivingEntityInventory.java:248-256`).
3. Offset 24-29: `attackStrengthTicker++`.
4. Offsets 33-46: if `!level().isClientSide`, call `PlayerEngineController.serverTick()`.
5. Offset 50: `super.tick()` (`LivingEntity.tick`). This runs vanilla AI (`serverAiStep`, which `MixinMobEntity` cancels while the engine is active) and then `travel` using `xxa`/`zza`/jump.
6. Offset 54: `updateSwingTime()`. Offset 62: `hungerManager.update(this)`.

`aiStep()` override: if `isInWater() && isShiftKeyDown() && isAffectedByFluids()`, call `goDownInWater()`. Then `super.aiStep()`, then `pickupItems()`.

### 5.2 `PlayerEngineController.serverTick()` (`PE/PlayerEngineController.java:147-158`)

Order: `inputControls.onTickPre()`, `storageTracker.setDirty()`, `miscBlockTracker.tick()`, `trackerManager.tick()`, `blockScanner.tick()`, `taskRunner.tick()` (this is where PlayerEngine tasks choose goals), `cacheTracker.tick()`, `inputControls.onTickPost()`, `baritone.serverTick()`, `player2apiService.trySendHeartbeat()`, `chunkLoader.tick()`.

### 5.3 `Baritone.serverTick()` to `GameEventHandler.onTickServer()`

`AU/Baritone.java:196-198` calls `GameEventHandler.onTickServer()` (`AU/event/GameEventHandler.java:39-48`), which does:
1. `baritone.bsi = new BlockStateInterface(ctx)` (lines 41-44).
2. Each listener's `onTickServer` in registration order: PathingBehavior, LookBehavior, MemoryBehavior (no tick override), InventoryBehavior (`behavior/InventoryBehavior.java:52`), InputOverrideHandler (`utils/InputOverrideHandler.java:67`), then PathingControlManager's postPathingTick.

PathingBehavior's tick (`AU/behavior/PathingBehavior.java:84-91`): dispatch queued events, `expectedSegmentStart = pathStart()`, `PathingControlManager.prePathingTick()` (chooses the process command and may start a calc, `utils/PathingControlManager.java`), `tickPath()` (line 98, calls `current.onTick()`, which updates the movement), dispatch events.

### 5.4 How pathing decisions become entity input

- `PathExecutor.onTick()` (`AU/pathing/path/PathExecutor.java:85`) calls `movement.update()` (line 211). `Movement.update()` (`AU/pathing/movement/Movement.java:114`):
  - Lines 115-117: if the entity is a `Player`, clear `flying`.
  - Lines 119-121: if `!allowSwimming` and the feet cell is a liquid, `setInput(JUMP, true)`.
  - Then it calls `clearAllKeys()` and `setInputForceState(input, forced)` for each input the movement wants, and points the look target through `LookBehavior`.
- `PathExecutor` also calls `setSprinting(shouldSprintNextTick())` every tick (line 222).
- `InputOverrideHandler.setInputForceState` and `clearAllKeys` set `needsUpdate = true` (`utils/InputOverrideHandler.java:46-63`). `onTickServer` (line 67) does nothing unless that flag is set.
- When the flag is set (`InputOverrideHandler.java:68-102`):
  - `entity.xxa = 0`, `entity.zza = 0` (74-75); `setShiftKeyDown(false)` (76); `setJumping(JUMP forced)` (77).
  - MOVE_FORWARD adds 0.3 to `zza`, MOVE_BACK subtracts 0.3 (79-85). MOVE_LEFT adds 0.3 to `xxa`, MOVE_RIGHT subtracts 0.3 (87-93).
  - SNEAK sets `setShiftKeyDown(true)` and scales `xxa` and `zza` by 0.3 (95-99).
  - `blockBreakHelper.tick(CLICK_LEFT)` (101) and `blockPlaceHelper.tick(CLICK_RIGHT)` (102).
- `clearAllKeys()` calls `setSprinting(false)` (line 59) when the entity is sprinting.
- Look: `LookBehavior.onTickServer` (`behavior/LookBehavior.java:61-70`). `updateLook` writes `setYRot` and `setXRot` (lines 93-98), adds random scramble from `randomLooking` (default 0.01), and calls `nudgeToLevel` (lines 147-153).
- Nothing in `AU` calls `setPos`, `teleportTo`, `moveTo` or `setDeltaMovement` for the bot. The only `setDeltaMovement` calls are in the fishing bobber. Movement is vanilla physics driven by `xxa`/`zza`/jump.

### 5.5 Block breaking

- `BlockBreakHelper.tick(isLeftClick)` (`AU/utils/BlockBreakHelper.java:49-69`). The target is the crosshair: `ctx.objectMouseOver()` (`RayTraceUtils.rayTraceTowards`, reach from `EntityInteractionController.getBlockReachDistance()` = 4.5, `EntityInteractionController.java:112-114`). It is not the path target. Break therefore depends on LookBehavior already aiming at the block.
- If the trace hits a block and `lastPos != pos`: `playerController().clickBlock(pos, face)` (`EntityInteractionController.java:92-101`), which issues START_DESTROY_BLOCK through `processBlockBreakingAction`.
- Then `onPlayerDamageBlock(pos, face)` (`EntityInteractionController.java:48-60`): if mining and progress is at least 10, sends STOP_DESTROY_BLOCK.
- `entity.swing(MAIN_HAND)`.
- `stopBreakingBlock()` (`BlockBreakHelper.java:38`) and `resetBlockRemoving()` (`EntityInteractionController.java:66`) send ABORT_DESTROY_BLOCK.

The interaction manager is `LivingEntityInteractionManager` (`AU/api/entity/LivingEntityInteractionManager.java`):
- `update()` (line 107) calls `continueMining` (132-142), which calls `world.destroyBlockProgress` (line 137).
- `processBlockBreakingAction(pos, Action, Direction, worldHeight, seq)` (line 147). It rejects a position more than 6 blocks from the eye (line 148) or above world height (line 150). START computes `calcBlockBreakingDelta` (line 216, which uses `getBlockBreakingSpeed`, lines 230-262). Creative breaks at once. Instant break finishes at once. Otherwise it sets `mining = true`. STOP finishes when progress is at least 0.7 (line 186).
- `finishMining` (line 264) calls `tryBreakBlock` (line 272): `world.gameEvent(BLOCK_DESTROY)`, `world.removeBlock(pos, false)` (line 281), `block.destroy`, `itemStack.getItem().mineBlock(...)` (line 292), `Block.dropResources(...)` (line 294).
- `method_41250(...)` (lines 144-145) is an empty method. Failure and abort branches call it and do nothing.
- Vanilla methods called from this class: `Level.destroyBlockProgress`, `getBlockState`, `getBlockEntity`, `gameEvent`, `removeBlock`, `sendBlockUpdated`, `Block.destroy`, `Item.mineBlock`, `Block.dropResources`, `Block.isEnabled`, `BlockState.useItemOn`, `ItemStack.useOn(UseOnContext)`, `Level.clip` (raycast, line 392-405), `BucketItem.emptyContents` (line 376).

### 5.6 Block placing

- `BlockPlaceHelper.tick(rightClickRequested)` (`AU/utils/BlockPlaceHelper.java:39-65`). A cooldown of `BaritoneAPI.getGlobalSettings().rightClickSpeed` (default 4) applies. It uses `objectMouseOver()` for the target. For each hand: `processRightClickBlock`, then `processRightClick` if the held item is non-empty.
- `EntityInteractionController.processRightClickBlock` (line 82) calls `LivingEntityInteractionManager.interactBlock` (`LivingEntityInteractionManager.java:424-463`). It calls `BlockState.useItemOn(stack, world, null, hand, hit)` first, then `ItemStack.useOn(new UseOnContext(level, null, hand, stack, hit) {...})` (lines 444-447). The `null` player is a deviation to note.
- `processRightClick` (line 87) calls `interactItem` (`LivingEntityInteractionManager.java:302-345`). Buckets go through `useBucket` (lines 346-385), which uses `IBucketAccessor`.

## 6. Entity requirements

### 6.1 The entity PlayerEngine drives (bytecode)

`com.goodbird.player2npc.companion.AutomatoneEntity`:
- `extends net.minecraft.world.entity.LivingEntity`. Not `ServerPlayer`. No dummy connection.
- `implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider`.
- Fields: `manager` (`LivingEntityInteractionManager`), `inventory` (`LivingEntityInventory`), `hungerManager` (`LivingEntityHungerManager`), `controller` (`PlayerEngineController`), `character`, `textureLocation`, `lastVelocity`, `PLAYER2_GAME_ID`.
- Constructor `(EntityType, Level)` calls `init()`. `init()` does `new LivingEntityInteractionManager(this)`, `new LivingEntityInventory(this)`, `new LivingEntityHungerManager()`, then `new PlayerEngineController(IBaritone.KEY.get(this), character, PLAYER2_GAME_ID)` (bytecode offsets 7-82). `setOwner(Player)` is called later.

### 6.2 Requirements imposed by `AU`

- Type: `LivingEntity`. `Baritone(LivingEntity)` (`AU/Baritone.java:75`), `EntityContext(LivingEntity)` (`AU/utils/player/EntityContext.java`).
- Server side only. `EntityContext.world()` (`EntityContext.java:82-89`) throws `IllegalStateException` on client and casts to `ServerLevel`. `BaritoneProvider.getBaritone` throws on client (`BaritoneProvider.java:43-44`). `BlockStateInterface` casts `world.getChunkSource()` to the accessor (`BlockStateInterface.java:47`).
- `IInventoryProvider` (`AU/api/entity/IInventoryProvider.java`): `EntityContext.inventory()` returns null without it. `CalculationContext` sets `player = null`, `toolSet = null` (`CalculationContext.java:104`), `hasWaterBucket = false` (line 106), `canSprint = false`.
- `IInteractionManagerProvider`: `EntityInteractionController.getInteractionManager()` returns null without it (`EntityInteractionController.java:103-105`). Every method dereferences it (lines 44, 48-60, 66-73, 82-88, 92-101). `BlockBreakHelper` calls them whenever CLICK_LEFT is forced. Not audited beyond this.
- `IHungerManagerProvider`: `EntityContext.hungerManager()` returns null without it.
- Vanilla state read: `getAttributeValue(MOVEMENT_SPEED)` (`MixinMobEntity.java:25`), `getMaxAirSupply()`/`getAirSupply()` (`CalculationContext.java:142`), `getDimensions(STANDING)`, `getItemBySlot(FEET)` and `getItemBySlot(HEAD)` (`CalculationContext.java:123`, `LivingEntityInteractionManager.java:253`), `getMainHandItem()`/`getOffhandItem()` (`LivingEntityInteractionManager.java:430`), `getEyePosition()` (`LivingEntityInteractionManager.java:148`), `isSprinting()`/`setSprinting()`, `setYRot`/`setXRot`, `swing(hand)`, `xxa`/`zza`, `setJumping`, `setShiftKeyDown`, `isInWater`, `isEyeInFluid(WATER)`, `isUnderWater`, `onGround`.
- `Player` checks: `Movement.java:115` (flying). `IBaritone.logDirect` (`AU/api/IBaritone.java:81-101`) sends to a `Player` directly, otherwise to creative `ServerPlayer`s via `entity.level().getServer().getPlayerList()` (line 91).
- Game mode: there is none on the entity. `EntityInteractionController.getGameType()` returns SURVIVAL (line 77-79). `LivingEntityInteractionManager.gameMode` defaults to SURVIVAL (line 72). Creative branches are dead for the bot.
- `FakeServerPlayerEntity`: no such class exists in this tree. The "same body shape as section 3.1" statement in design 2.2 refers to upstream Automatone, which is not in this repo.

### 6.3 Component storage

- `EntityComponentKey` (`AU/api/component/EntityComponentKey.java:29-60`): `HashMap<UUID, C>`, no remove method (grep finds no `remove`). Entries stay after the entity is removed. `MixinEntity.setRemoved` only shuts the pathing down. See adjacent issue 8.
- `IInteractionController.KEY` (`AU/api/utils/IInteractionController.java:32`) and `ISelectionManager.KEY` (`AU/api/selection/ISelectionManager.java:26`) use the same storage.

## 7. Threading and caches

### 7.1 Path search

- Pool: `PlayerEngine.getExecutor()` returns a static `ThreadPoolExecutor(4 core, Integer.MAX_VALUE max, 60 s keepalive, SynchronousQueue, names "PlayerEngine Worker N")` (`PE/PlayerEngine.java:62-64`, `99-102`).
- Use: `PathingBehavior.findPathInNewThread` (`AU/behavior/PathingBehavior.java:421`), which submits a lambda at line 449. That lambda calls `AbstractNodeCostSearch.calculate(primaryTimeout, failureTimeout)`, then under `pathPlanLock` installs `current` or `next` and queues events. The search object's `calculate` is `synchronized` (`AU/pathing/calc/AbstractNodeCostSearch.java:64`).
- Timeouts (time-based, not node-based): first plan 500 ms primary and 2000 ms failure (`Settings.java:130-131`). Planning ahead 4000 ms and 5000 ms (`Settings.java:132-133`), used when `current != null`. `AStarPathFinder.calculate0` (`AU/pathing/calc/AStarPathFinder.java:44`) checks these at lines 69-84. Slow path: `slowPath` false (`Settings.java:134`), delay 100 ms, timeout 40000 ms.
- `CalculationContext(IBaritone, boolean forUseOnAnotherThread)` (`CalculationContext.java:96`) is built on the server thread (`PathingBehavior.java:221`, in `secretInternalSetGoalAndPath`). It copies entity state and creates `bsi = new BlockStateInterface(world)` (line 103). `safeForThreadedUse` is checked at `findPathInNewThread` (`PathingBehavior.java:426`).
- Live reads from the pool thread: `BlockStateInterface.get0` calls `automatone$getChunkNow` (`BlockStateInterface.java:76`), which calls `ServerChunkCache.getVisibleChunkIfPresent` (`MixinServerChunkManager.java:18-23`). There is no snapshot and no lock. The server thread mutates the same map. Not measured. See adjacent issue 5.
- Other pool users: `FarmProcess.java:149` (`WorldScanner.scanChunkRadius`), `GetToBlockProcess.java:122` and `MineProcess.java:127` (rescans).

### 7.2 Event queue

- `PathingBehavior.toDispatch` is a `LinkedBlockingQueue<PathEvent>` (`AU/behavior/PathingBehavior.java:63`). Producers are on the pool thread (`queuePathEvent`). The consumer is `dispatchEvents()` on the server thread (line 73).

### 7.3 World and chunk "cache"

- `WorldProvider` per dimension via `WorldComponentKey` (`AU/api/component/WorldComponentKey.java:26-37`, `HashMap<ResourceKey, C>`). Keyed by `IWorldProvider.KEY` (`AU/api/cache/IWorldProvider.java:24`). `Baritone.getWorldProvider()` (`Baritone.java:159-161`).
- `WorldData` (`AU/cache/WorldData.java`): `localChunkCache` is a `HashSet<Long>` of chunk keys. `addBlockPosToCache` (lines 83-87) and `addChunkPosToCache` (lines 89-91) write it.
- Writers: only PlayerEngine's `PE/trackers/CacheTracker.java:17-21`, which records the player's current position each tick.
- Readers: only `ICachedWorld.isCached` (`WorldData.java:54-63`), used by `ExploreProcess.java:199`.
- `ICachedWorld.getLocationsOf` returns `new ArrayList<>()` (`WorldData.java:65-67`).
- `CachedChunk` (`AU/cache/CachedChunk.java:24`) is never constructed. Only its static `BLOCKS_TO_KEEP_TRACK_OF` is used (`MineProcess.java:314`).
- Settings with no effect in the repo (grep only finds the declaration): `chunkCaching` (`Settings.java:137`), `pathThroughCachedOnly` (line 156), `pruneRegionsFromRAM` (line 138). `extendCacheOnThreshold` (line 182) is read by `MineProcess.java:327`.
- Chunk events: none in `AU`. `ChunkEvent.LOAD_DATA` is used only by PE tasks (`PE/tasks/movement/ChunkSearchTask.java:53`, `SearchChunksExploreTask.java:25`).
- Block changes: `WorldBlockModifiedMixin` publishes only `BlockPlaceEvent` (for `BlockScanner`), and nothing updates `WorldData`.
- `GameEventHandler.onTickServer` builds a new `BlockStateInterface` every tick (`GameEventHandler.java:41`).

## 8. Swimming and water

### 8.1 Settings

- `allowSwimming` default false (`AU/api/Settings.java:57`). Copied to `CalculationContext.allowSwimming` (`CalculationContext.java:141`).
- `assumeWalkOnWater` default false (`Settings.java:59`). `assumeWalkOnLava` false (line 60).
- `walkOnWaterOnePenalty` default 3.0 (`Settings.java:55`).
- `allowWaterBucketFall` default true (line 56). The bucket is used by `MovementFall` (`movements/MovementFall.java:89-121`).
- `sprintInWater` default true (`Settings.java:157`).
- `ignoreBreath` default true (`Settings.java:58`). With it on, `breathTime = Integer.MAX_VALUE` (`CalculationContext.java:142`).
- `maxFallHeightNoWater` 3 (`Settings.java:124`), `maxFallHeightBucket` 20 (line 125).

### 8.2 Walk-through rule (can a cell be entered)

`MovementHelper.canWalkThrough(bsi, x, y, z, state, settings)` (`AU/pathing/movement/MovementHelper.java:109`). Water branch (lines 146-152):
- Non-water fluid: `state.isPathfindable(LAND)`.
- Water with `assumeWalkOnWater`: false.
- Otherwise: `(allowSwimming || up.getFluidState().isEmpty()) && !(up.getBlock() instanceof WaterlilyBlock)`.

So with defaults a water cell can be entered only if the cell above has no fluid. Deep water (water above water) is blocked. Lily pads block.

### 8.3 Support rule (can a cell be stood on)

`MovementHelper.canWalkOn(...)` (line 258). Water branch (lines 270-276):
- If the cell above is not a lily pad or carpet: `!isFlowing && up is not flowing water ? isWater(up) XOR assumeWalkOnWater : isWater(up) && !assumeWalkOnWater`.
- Otherwise true.

With defaults, water is a valid support only when the cell above it is water too. So the bot can stand in the water column of a lake that is at least two deep. With `assumeWalkOnWater = true` the rule flips and the bot walks on the surface.

Flowing water fails `isFlowing` (`MovementHelper.java:419-431`).

### 8.4 Floating and jumping

- `Movement.update()` (`AU/pathing/movement/Movement.java:119-121`): while the feet cell is any liquid and `allowSwimming` is false, `setInput(JUMP, true)` every tick. The entity floats by holding jump, not by swimming.
- The entity's own `aiStep` (bytecode) only goes down when sneaking.

### 8.5 Costs

- `CalculationContext.waterWalkSpeed` (`CalculationContext.java:129`): `9.0909 * (1 - mult) + 4.6328 * mult`. `mult` comes from Depth Strider on boots, which is broken (adjacent issue 1), so it is always 9.0909.
- `ActionCosts.WALK_ONE_IN_WATER_COST = 9.09090909090909` (`AU/api/pathing/movement/ActionCosts.java:22`).
- `MovementTraverse.cost` (`AU/pathing/movement/movements/MovementTraverse.java:108-137`): if the destination head is water, `WC = waterWalkSpeed` (lines 122-124); if the feet cell is water, same (127-129). If the destination on-block is `Blocks.WATER`, `WC = walkOnWaterOnePenalty` (136-137).
- Other movements use `waterWalkSpeed` too: `MovementDiagonal.java:152-158`, `MovementAscend.java:169`, `MovementDescend.java:99-102`, `MovementDownward.java:117-121`, `MovementPillar.java:110-140`.
- Oxygen: `CalculationContext.oxygenCost` (line 213-214) uses `airDecreaseInWater` (line 145). The magnitude with `ignoreBreath` on is UNVERIFIED.

### 8.6 Sprint and liquid

- `MovementDiagonal.java:284`: no sprint in liquid when `sprintInWater` is false.
- `MovementDiagonal.java:211`, `MovementTraverse.java:159`, `MovementParkour.java:83`: sprint only when `canSprint && !water`.

### 8.7 Lake verdict (code reading only, not run)

- Default settings: the engine can path along the top layer of a lake (feet in water, air above), holding JUMP, if the cell beneath is water with water above it.
- It cannot path through deep water, because the feet and head cells are both water.
- It cannot stand on the surface of still water (`assumeWalkOnWater` is false).
- Rivers with flowing water are excluded (`isFlowing`).
- With `allowSwimming = true` the deep-water block is lifted. Nothing in the repo tests any of this.

## 9. Settings

- Class: `AU/api/Settings.java`. 178 `public final Settings.Setting` fields (lines 46-243). `Setting<T>` inner class (`Settings.java:291-344`).
- Per-bot vs global: `Baritone` creates `new Settings()` (`Baritone.java:76`). `Setting.get()` returns the value if set, otherwise `defaultValue()` (`Settings.java:306-313`), which for non-global instances returns the **global** instance's current value. So `settings.txt` affects every bot unless a bot sets a value explicitly.
- Global: `BaritoneProvider.java:35`. Loaded from `config/playerengine/settings.txt` (`SettingsLoader.java:33-60`).
- Shared mutable defaults: `acceptableThrowawayItems.get().addAll(...)` (`PE/PlayerEngineController.java:126`) mutates the default list object that every bot shares. `ConstructIronGolemTask.java:34,111` does the same to `blocksToAvoidBreaking`.

Survival-relevant defaults (`AU/api/Settings.java` line numbers):

| Setting | Default | Line |
|---|---|---|
| allowBreak | true | 46 |
| allowSprint | true | 47 |
| allowPlace | true | 48 |
| allowInventory | true | 49 |
| allowWaterBucketFall | true | 56 |
| allowSwimming | false | 57 |
| ignoreBreath | true | 58 |
| assumeWalkOnWater | false | 59 |
| assumeWalkOnLava | false | 60 |
| allowJumpAt256 | false | 63 |
| allowParkourAscend | true | 64 |
| allowDiagonalDescend | false | 65 |
| allowDiagonalAscend | false | 66 |
| allowDownward | true | 67 |
| allowParkour | false | 96 |
| allowParkourPlace | false | 97 |
| allowWalkOnBottomSlab | true | 95 |
| sprintAscends | true | 99 |
| blockPlacementPenalty | 20.0 | 52 |
| blockBreakAdditionalPenalty | 2.0 | 53 |
| jumpPenalty | 2.0 | 54 |
| walkOnWaterOnePenalty | 3.0 | 55 |
| backtrackCostFavoringCoefficient | 0.5 | 107 |
| costHeuristic | 3.563 | 105 |
| breakCorrectBlockPenaltyMultiplier | 10.0 | 195 |
| maxFallHeightNoWater | 3 | 124 |
| maxFallHeightBucket | 20 | 125 |
| avoidance | false | 108 |
| mobAvoidanceCoefficient | 1.5 | 111 |
| mobAvoidanceRadius | 8 | 112 |
| randomLooking | 0.01 | 104 |
| freeLook | false | 154 |
| movementTimeoutTicks | 100 | 129 |
| primaryTimeoutMS | 500 | 130 |
| failureTimeoutMS | 2000 | 131 |
| planAheadPrimaryTimeoutMS | 4000 | 132 |
| planAheadFailureTimeoutMS | 5000 | 133 |
| planningTickLookahead | 150 | 121 |
| slowPath | false | 134 |
| splicePath | true | 168 |
| cancelOnGoalInvalidation | true | 204 |
| disconnectOnArrival | false | 206 |
| sprintInWater | true | 157 |
| rightClickSpeed | 4 | 102 |
| sprintAscends | true | 99 |

Throwaway list (`acceptableThrowawayItems`), default: DIRT, NETHERRACK, BASALT, STONE, BLACKSTONE, GRANITE, DIORITE, ANDESITE, SOUL_SOIL (`Settings.java:68-82`).

`blocksToAvoid` default empty (line 83). `blocksToAvoidBreaking` default: CRAFTING_TABLE, FURNACE, CAMPFIRE, SMOKER, BLAST_FURNACE, CHEST, TRAPPED_CHEST (lines 84-86).

Block tags (`buildIgnoreBlocks` at 87-89, `okIfAir` at 90-92): `playerengine:build/ignored_blocks` and `playerengine:build/ok_if_air`. No data files exist for them in the repo. The item tag files that do exist are `empty_buckets.json`, `water_buckets.json`, `throwaway_blocks.json`. Nothing in Java references `throwaway_blocks`.

PlayerEngine's runtime overrides (`PE/PlayerEngineController.java:180-201`, `initializeBaritoneSettings`): freeLook false (184), overshootTraverse true (185), allowOvershootDiagonalDescend true (186), allowInventory true (187), allowParkour false (188), allowParkourAscend false (189), allowParkourPlace false (190), allowDiagonalDescend false (191), allowDiagonalAscend false (192), fadePath true (193), mineScanDroppedItems false (194), randomLooking 0.0 (197), randomLooking113 0.0 (198), failure and planAhead timeouts reset (199-200), movementTimeoutTicks reset (201). Effective PlayerEngine settings therefore differ from the defaults above.

## 10. External dependencies

`common/build.gradle` declares only:
- `modImplementation "net.fabricmc:fabric-loader:$fabric_loader_version"` (lines 8-9). Comment says it is for `@Environment` only.
- `modImplementation "dev.architectury:architectury:$architectury_api_version"` (lines 11-12).
- `implementation 'net.sandrohc:schematic4j:1.1.0'` (line 15). Not used by `AU`. Only `PE/tasks/construction/build_structure/BuildStructureTask.java` uses it.

Root `build.gradle` (applies to all subprojects): Minecraft 1.21.1, `loom.officialMojangMappings()`, `modImplementation files('../libs/player2npc-neoforge-1.21.1-1.0.0.jar')`, Java 21 (`options.release = 21`).

`neoforge/build.gradle`: `neoForge "net.neoforged:neoforge:21.1.201"` (version in `gradle.properties`), `architectury-neoforge`, `common(project(':common'))`, `shadowBundle` of the common transform.

`AU` uses these without declaring them. They come from Minecraft's bundled libraries, which is UNVERIFIED (no Gradle cache inspected): fastutil, Guava, Gson, commons-io, commons-lang3, JOML, JetBrains annotations, log4j-api, slf4j (2 files), `com.mojang.logging.LogUtils`.

Architectury is used directly (`CommandRegistrationEvent`, `Platform`). A Fabric-free port still needs Architectury or replacements for these two.

## 11. Licence headers

All 278 files under `AU` begin with the same 16-line header (line 1 `/*`, line 16 ` */`, line 17 blank, line 18 `package`). The header reads "This file is part of Baritone." and quotes "GNU Lesser General Public License ... either version 3 of the License ... (at your option) any later version." The header names Baritone, not Automatone or PlayerEngine. Confirmed by grep: 278/278 contain "GNU Lesser General Public License" within the first 20 lines and "This file is part of Baritone".

Repo `LICENSE` (root) is GNU LGPL version 3 (first lines confirmed). `neoforge.mods.toml:4` says `license = "GNU LGPL 3.0"`.

## 12. How the code is exercised

- Tests: none. `git ls-files | grep src/test` returns nothing. No test for pathing, water, breaking or placing exists.
- Build: Gradle wrapper at the repo root (`gradlew`, `settings.gradle` includes `common`, `fabric`, `neoforge`). Loom 1.11-SNAPSHOT, architectury-plugin 3.4-SNAPSHOT, Java 21. Candidate commands: `./gradlew :common:build` and `./gradlew :neoforge:build`. UNVERIFIED: not run in this session. No `build/` or `.gradle/` directories exist in the checkout.
- Runtime exercise is only through the entity tick (section 5.1) on a dedicated server with the player2npc jar.

## Adjacent issues

1. `util/EnchantmentUtils.java:10-14` ignores its `enchantment` parameter. The filter at line 12 compares to `Enchantments.EFFICIENCY` only. Affected: Silk Touch in `utils/ToolSet.java:66` (returns true for any Efficiency tool), Aqua Affinity in `api/entity/LivingEntityInteractionManager.java:253` (wrong value), Depth Strider in `pathing/movement/CalculationContext.java:123` (always 0). Efficiency at `ToolSet.java:128` and `LivingEntityInteractionManager.java:234` is correct by accident. `tasks/speedrun/beatgame/BeatMinecraftTask.java:118` (outside AU) uses it for Binding Curse and is also wrong.
2. `utils/player/EntityContext.java:108` passes block coordinates (`(int)x << 4`) to `automatone$getChunkNow`, which takes chunk coordinates (`ServerChunkManagerAccessor.java:25`). The slab check at lines 108-110 therefore reads the wrong chunk, and `feetPos()` does not rise for slabs.
3. `api/entity/LivingEntityInteractionManager.java:444-447`: the anonymous `UseOnContext` overrides `isSecondaryUseActive()` to return `this.isSecondaryUseActive()`. That recurses until StackOverflowError if reached. Reachability through `ItemStack.useOn` is UNVERIFIED.
4. `LivingEntityInteractionManager.java:144-145` `method_41250` is empty. Failure and abort branches (148-150, 193-211) silently do nothing.
5. Thread safety: the pool-thread search reads `ServerChunkCache` through `getVisibleChunkIfPresent` with no lock (section 7.1). Unmeasured.
6. `PathingBehavior.java:168` and `CustomGoalProcess.java:80` call `this.ctx.world().disconnect()`. `world()` returns `ServerLevel` (`api/utils/IEntityContext.java:61`). No declaration of `disconnect()` on that type exists in the repo, and no build output exists. UNVERIFIED whether this compiles. The call is guarded by `disconnectOnArrival` (default false).
7. Shared mutable defaults: `PE/PlayerEngineController.java:126` and `ConstructIronGolemTask.java:34,111` mutate default list objects that every bot shares (section 9).
8. `api/component/EntityComponentKey.java:30` never removes entries. Every entity UUID that ever touched `IBaritone.KEY` leaks a full `Baritone` object graph. The same applies to `IInteractionController.KEY` and `ISelectionManager.KEY`. `MixinEntity.setRemoved` stops pathing but keeps the entry.
9. No working chunk cache (section 7.3). `WorldData.java:65-67` returns an empty list. `CachedChunk` is never constructed. `chunkCaching`, `pathThroughCachedOnly`, `pruneRegionsFromRAM` have no readers. The design's feature list for Automatone overstates this copy.
10. `mixins/ClientBlockBreakMixin.java:16` targets `MultiPlayerGameMode`, a client-only class, from the common config (`playerengine.mixins.json:12`, `"required": true`, `"client": []` at line 10). `PlayerCollidesWithEntityMixin.java:5` imports `LocalPlayer` (import only; target is `Player`). On a dedicated server the first mixin's target is absent. UNVERIFIED whether this fails the load.
11. Client-only classes inside `AU`: `client/CustomFishingBobberRenderer.java`, `utils/GuiClick.java`, `utils/PathRenderer.java:24` (static init calls `Minecraft.getInstance()`), `command/defaults/RenderCommand.java:39`, `api/Settings.java:217` (`logger` default lambda). `api/event/events/RenderEvent.java:21` imports JOML.
12. `command/defaults/DefaultCommands.java:68` has a literal alias `"com/player2/playerengine/automaton"`. It looks like a find-and-replace artefact.
13. `api/entity/LivingEntityInventory.java:375-436` logs at INFO per item on NBT read and write. Noise.
14. `pathing/path/PathExecutor.java:113` logs at WARN every tick while off path (up to 200 ticks). Noise.
15. `PathingControlManager.java:73` throws `IllegalStateException` from `cancelEverything()` if a non-temporary process is still active after `onLostControl()`. Any port that calls `forceCancel` during a process must keep that invariant.
16. Blocks are broken at the crosshair, not the path target (section 5.5). A path that needs a block broken depends on LookBehavior aiming first. This is a design property the port should know about.
17. Template text left in `neoforge/src/main/resources/META-INF/neoforge.mods.toml:9-11` and `fabric/src/main/resources/fabric.mod.json` (description, homepage and sources point at the Fabric example). Not in `AU`.

## Design doc (AI-PLAYER-DESIGN.md section 2.2) versus the code

- "Which entity class PlayerEngine itself drives is unconfirmed": now confirmed. It is `AutomatoneEntity extends LivingEntity` in the libs jar (section 6.1).
- "Automatone's own FakeServerPlayerEntity extends ServerPlayerEntity on a dummy connection is the same body shape as section 3.1": no such class exists in the repo. The body is a `LivingEntity`.
- "no Fabric or NeoForge imports": confirmed for `AU`. It does import Architectury in two places (`DirUtil.java:22`, `DefaultCommands.java:44`), which the doc does not mention.
- "a chunk cache" in the list of what Automatone has: not present in this copy (adjacent issue 9).
- "segmented long paths with the next segment precomputed": present. Planning starts when `ticksRemainingInSegment` is under `planningTickLookahead` (150) (`PathingBehavior.java:200`).
- "path search off the server thread": present (section 7.1).
- "Movement is input-driven ... no teleports": confirmed (section 5.4).
- "17 references to swimming": confirmed (`grep -i swim` gives 17).
- "278 files, ~27.7K lines", "17 mixins", "two of our six versions have an upstream (1.21.8-arch, 1.21.10-arch)": confirmed by the file count, the mixin count and `git branch -a`.

## Open questions

1. Does `ServerLevel` in the NeoForge 21.1.201 mappings have `disconnect()`? (adjacent issue 6). Needs the mapped jar or a compile.
2. Does `ItemStack.useOn` reach `UseOnContext.isSecondaryUseActive()` for the placement paths the bot uses? (adjacent issue 3).
3. Does the common mixin config fail on a dedicated server because of `ClientBlockBreakMixin`? (adjacent issue 10). Needs a dedicated-server boot.
4. Do fastutil, Guava, Gson, commons-io, commons-lang3, JOML, log4j-api and slf4j resolve on the common compile classpath through Minecraft alone? Needs a Gradle run.
5. Is the lake behaviour in section 8.7 what the running engine does? Needs a run on a lake and a river, with `allowSwimming` both false and true.
6. How much does the pool-thread chunk read (section 7.1) race with the server thread in practice? Needs measurement; no evidence either way in the repo.
7. Which `PlayerEngine` settings in section 9 are actually wanted for MegaBlockBot? The port must choose between the defaults and PlayerEngine's overrides.
8. The bot entity type (`LivingEntity` as in PlayerEngine, or a `ServerPlayer` subclass as in design 3.1) changes which of section 6.2's requirements are free. Design decision, not answered from the repo.
