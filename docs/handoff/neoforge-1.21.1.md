# NeoForge 1.21.1 (21.1.265) and ModDevGradle 2.0.147: facts for implementers

Scope: the 1.21.1 node only (`versions/1.21.1`). Topic list from the brief: GameTests, decompiled sources, events, persistence, chunk tickets, loot and trades, config, mixins and access transformers, jar-in-jar, reload hooks.

## 0. Sources and how they were read

Pins: `versions/1.21.1/gradle.properties:2-3` (minecraft 1.21.1, neo 21.1.265); `stonecutter.gradle.kts:5` (ModDevGradle 2.0.147); `settings.gradle.kts` (Stonecutter 0.9.8).

Short names used below:
- `[srcjar]` = `E:/GitHub/MegaBlockBot/versions/1.21.1/build/moddev/artifacts/neoforge-21.1.265-sources.jar`. Produced by MDG; contains both `net/minecraft/**` (decompiled, NeoForge-patched) and `net/neoforged/**`. Paths `net/...` below are relative to its root. An extracted copy exists in the session scratchpad (`C:\Users\legoj\AppData\Local\Temp\claude\9106388de7fb8cf3\6396b123-4ce9-4b54-8689-fcc0d5850820\scratchpad\src121`, may be cleared).
- The Maven copy of `neoforge-21.1.265-sources.jar` in the Gradle cache (about 1.8 MB) has NO `net/minecraft`. Do not read Minecraft code from it.
- `[branch]` = NeoForge git `remotes/origin/1.21.1` (local checkout is on 26.1.x; do not read its HEAD). Verified byte-identical to `[srcjar]` for `GameTestHooks.java`, `RegisterGameTestsEvent.java`, `ModConfigSpec.java`, `AttachmentType.java`.
- `[fml]` = `net.neoforged.fancymodloader:loader:4.0.45` (not in any sources jar; read with javap).
- `[mdg]` = `net.neoforged:moddev-gradle:2.0.147` (read with javap).
- `[userdev]` = `neoforge-21.1.265-userdev.jar` `config.json` (run definitions).
- Docs: `E:/GitHub/NeoForgeDocumentation/versioned_docs/version-1.21.1/`. Each doc claim below was checked against code; where it is only a doc claim it says so.
- Examples on disk: BuildCraft `E:/GitHub/BuildCraft` (1.21.1 is its live Stonecutter arm; in its source, the `//? else` arm of a `//? if >=X` is the 1.21.1 code, and the other arm sits inside `/* */`), SprawlCrafting `E:/GitHub/SprawlCrafting`. McDevBridge 1.21.1 has no GameTests, access transformers or mixins.

## 1. GameTests

### 1.1 Registration (two routes, both present in 21.1.265)

- Route A, annotation: `@GameTestHolder(String value)`, default `"minecraft"` (`[srcjar] net/neoforged/neoforge/gametest/GameTestHolder.java:21,25`). At startup `GameTestHooks.registerGametests()` scans `ModList.get().getAllScanData()` for classes with that annotation (`[srcjar] net/neoforged/neoforge/gametest/GameTestHooks.java:51-55`). So the holder class must be in a source set that the mod registers in `neoForge { mods { ... sourceSet(...) } }`.
- Route B, event: `RegisterGameTestsEvent` (`[srcjar] net/neoforged/neoforge/event/RegisterGameTestsEvent.java:24`). It is an `IModBusEvent`, so it goes on the mod bus. `register(Class<?>)` at `:39` and `register(Method)` at `:51`. Posted at `GameTestHooks.java:49`.
- Vanilla `GameTestRegistry.register(Method, Set<String>)` is `@Deprecated` (`[srcjar] net/minecraft/gametest/framework/GameTestRegistry.java:42`); the event is the replacement.
- BuildCraft does not use either route for its lambda tests. On 1.21.1 it hands each test to `GameTestRegistry` by reflection on the private `TEST_FUNCTIONS` (`E:/GitHub/BuildCraft/src/test/java/buildcraft/lib/test/LegacyGameTestRegistry.java:80-96`), called from `RegisterGameTestsEvent` (`E:/GitHub/BuildCraft/src/test/java/buildcraft/platform/neoforge/NeoForgeGameTestHooks.java:50-53`, the 1.21.1 arm). It needs that route only because its tests are lambdas, not annotated methods.

Enablement (`[srcjar] net/neoforged/neoforge/gametest/GameTestHooks.java`):
- `isGametestEnabled()` `:32-34` = `!FMLLoader.isProduction() && (IS_RUNNING_IN_IDE || isGametestServer() || Boolean.getBoolean("neoforge.enableGameTest"))`.
- `isGametestServer()` `:36-38` = `Boolean.getBoolean("neoforge.gameTestServer")` (and not production).
- Namespace filter: `System.getProperty("neoforge.enabledGameTestNamespaces")`, comma-separated (`:66-70`). If set, a method registers only when its template namespace is in the set (`GameTestRegistry.java:45`).

### 1.2 Test method shape and names

```java
@GameTestHolder("megablockbot")          // optional if templateNamespace is set on each @GameTest
@PrefixGameTestTemplate(false)           // optional
public class SmokeGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void zombieStaysPut(GameTestHelper helper) { ... }
}
```
- Method: `public static void m(GameTestHelper)`. Static is invoked directly; an instance method needs a public no-arg constructor (`[srcjar] net/minecraft/gametest/framework/GameTestRegistry.java:151-168`).
- `@GameTest` members (`[srcjar] net/minecraft/gametest/framework/GameTest.java`): `timeoutTicks()=100` (:11), `batch()="defaultBatch"` (:13), `skyAccess()=false` (:15), `templateNamespace()=""` (:26), `template()=""` (:28), `setupTicks()` (default 0), `attempts()` (default 1), `requiredSuccesses()=1` (:34), `required()` default true, `manualOnly()` default false.
- Namespace: `GameTest.templateNamespace`, else `@GameTestHolder.value`, else `"minecraft"` (`GameTestHooks.java:85-99`).
- Name: `(@PrefixGameTestTemplate on method, else on class, default true)`. Without `template`, the structure is `ns:[classname.]methodname` (lowercase). With `template`, it is `ns:[classname.]template` (`GameTestRegistry.java:126-132`; `PrefixGameTestTemplate.java:35`).
- Template file: `data/<ns>/structure/<path>.nbt` (`[srcjar] net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager.java:54`). Example: `@GameTestHolder("megablockbot")` on `SmokeGameTests` with no template gives `megablockbot:smokegametests.zombiestayspet` → `data/megablockbot/structure/smokegametests.zombiestayspet.nbt`.

### 1.3 Empty structure

- Vanilla 1.21.1 ships no `minecraft:empty` template. `neoforge-21.1.265-client-extra-aka-minecraft-resources.jar` has `data/minecraft/structure/` entries, but none at the top level (only `bastion/.../empty.nbt`, `trial_chambers/decor/empty_pot.nbt`; checked with `unzip -l`).
- BuildCraft ships its own: `E:/GitHub/BuildCraft/src/test/resources/data/buildcraftunofficial/structure/empty.nbt` (16x16x16 air, 1.21.1 DataVersion, per the header comment at `LegacyGameTestRegistry.java:30-37`). Every test uses it with `skyAccess=true` (`LegacyGameTestRegistry.java:91-92`).
- A template named `template = "empty"` with `@PrefixGameTestTemplate(false)` resolves to `ns:empty` (`GameTestRegistry.java:130-132`), so the file must exist under the test's namespace.

### 1.4 GameTestHelper (`[srcjar] net/minecraft/gametest/framework/GameTestHelper.java`)

- `getLevel()` `:72`; `absolutePos(BlockPos)` `:867`; `relativePos` follows it; `getTick()` `:912`.
- Spawn: `<E extends Entity> E spawn(EntityType<E>, BlockPos)` `:116` (relative pos, converted to absolute by `spawn(EntityType<E>, Vec3)` `:120-129`; mobs get `setPersistenceRequired()`). Also `spawn(EntityType, Vec3)` `:120`, `spawnItem(Item, BlockPos)` `:112`, `spawnWithNoFreeWill(EntityType<E extends Mob>, BlockPos)` `:189`.
- Fake player: `makeMockServerPlayerInLevel()` `:291` returns a `ServerPlayer` in the level.
- Assertions (each throws `GameTestAssertException` on failure): `assertEntityPresent(EntityType<?>, BlockPos)` `:450`, `assertEntityPresent(EntityType<?>)` `:439`, `assertBlockPresent(Block, BlockPos)` `:361`, `assertTrue(boolean, String)` `:894`.
- Explicit end: `succeed()` `:777`; `fail(String)` `:850`.
- Final-check clauses (only one per test; `ensureSingleFinalCheck` `:782-787`):
  - `succeedIf(Runnable)` `:789`: runs at delay 0 from the sequence start (`GameTestSequence.thenWaitUntil(0L, r)`).
  - `succeedWhen(Runnable)` `:794`: retried every tick until it stops throwing.
  - `succeedOnTickWhen(int tick, Runnable)` `:799`: runs at that tick.
- Scheduled actions (not final checks): `runAtTickTime(long, Runnable)` `:804`, `runAfterDelay(long, Runnable)` `:808` (at `getTick()+delay`), `onEachTick(Runnable)` `:930`.
- Semantics, read from `[srcjar] net/minecraft/gametest/framework/GameTestSequence.java:96-115` and `:73-78`: an assertion that throws is retried next tick; when it passes, a non-null expected delay is checked against the tick it passed on, and a mismatch fails with "Succeeded in invalid tick". The doc table in `docs/misc/gametest.md` agrees.
- Timeout: `GameTestInfo.java:142-148` fails with "Didn't succeed or fail within N ticks" or "No sequences finished". A test with no success and no final check fails.
- Ticking only starts when the structure's chunks are entity-ticking (`GameTestInfo.java:103-105`).
- Asserting after N ticks: `helper.runAfterDelay(N, () -> { assert...; helper.succeed(); });`, or `succeedOnTickWhen(N, ...)` if the check must pass on exactly N.

### 1.5 Run type and runtime

ModDevGradle (`[mdg] net/neoforged/moddevgradle/dsl/RunModel`): the DSL has `client()`, `clientData()`, `data()`, `server()`, `serverData()` and no `gameTestServer()` helper. Select the type with the string property `type` (`RunModel.getType()` is `Property<String>`).

```kotlin
neoForge {
    runs {
        register("gameTestServer") {
            type = "gameTestServer"                       // -> task runGameTestServer
            gameDirectory = file("run-gameTestServer")    // optional; keeps it out of run/
            systemProperty("neoforge.enabledGameTestNamespaces", "megablockbot") // optional
        }
    }
    mods { register(modId) { sourceSet(sourceSets["main"]) } }   // tests must be in a registered source set
}
```
- BuildCraft's form: `E:/GitHub/BuildCraft/buildSrc/src/main/kotlin/bc.neoforge.gradle.kts:172-177` (its `neoforge.enableGameTest` property is also set by the run type).
- The run type itself (`[userdev] config.json`, `runs.gameTestServer`, roughly lines 180-226) has `launchTarget forgeserverdev`, `gameTest: true`, `server: true`, and props `neoforge.enableGameTest=true` and `neoforge.gameTestServer=true`.
- Server Main: `[srcjar] net/minecraft/server/Main.java:89` reads `neoforge.gameTestServer`; `:192-197` runs `registerGametests()` then `GameTestServer.create(...)` with `GameTestRegistry.getAllTestFunctions()`. The test world is a flat world (`GameTestServer.java:100`, `WorldPresets.FLAT`).
- No `--tests` option on the 1.21.1 server: the option list at `Main.java:70-91` has none. Filtering is by namespace property or by code (BuildCraft does the latter, `LegacyGameTestRegistry.java:48-75`).
- Pass/fail output: `GameTestServer.java:184` "N required tests failed", `:187` "All N required tests passed", inside `tickServer` (`:167`, done block `:178-199`).
- Exit codes:
  - Normal end: `GameTestServer.onServerExit()` calls `System.exit(failedRequiredCount)` (`GameTestServer.java:221-224`). The count wraps at 256 on POSIX (per BuildCraft's comment).
  - Crash: `onServerCrash` exits 1 (`:228-231`). Datapack-load failure in `create()` exits -1 (`:120-122`).
  - Startup exception on the main thread: `Main.java:96` opens the try, `:309-311` logs "Failed to start the minecraft server" and `main` returns, so the process exits 0. BuildCraft hit this and reads `run-gameTestServer/logs/latest.log` for "GAME TESTS COMPLETE" plus "All N required tests passed" (`E:/GitHub/BuildCraft/buildSrc/src/main/kotlin/bc.neoforge.gradle.kts:395-430` region; the 1.21.1 gate is in the `isPre1_21_10` block).

## 2. Decompiled Minecraft sources

- Read `[srcjar]`. It is produced by MDG's `createMinecraftArtifacts` task (`[mdg] net/neoforged/moddevgradle/internal/ModDevArtifactsWorkflow` names it; BuildCraft calls it at `bc.neoforge.gradle.kts:395`). Run `./gradlew createMinecraftArtifacts` in the node if it is missing. Outputs land in `versions/<node>/build/moddev/artifacts/`: `neoforge-<v>-sources.jar`, `-merged.jar`, `-client-extra-aka-minecraft-resources.jar`, `neoforge-<v>.jar`.
- Extract with `unzip -q <jar> -d <empty dir>` (this worked in this session). Do not use a wildcard extract.
- BuildCraft's `versions/1.21.1/build/moddev/artifacts/` holds 21.1.250 and 21.1.251 only, while its pin is 21.1.265 (`E:/GitHub/BuildCraft/versions/1.21.1/gradle.properties`). Run the task before reading it.

## 3. Commands, lifecycle, tick and game events

Bus rule (checked on class hierarchy): every event below extends `net.neoforged.bus.api.Event` without `IModBusEvent`, so it is a GAME-bus event. Mod-bus events are the ones that implement `IModBusEvent` (e.g. `RegisterGameTestsEvent.java:24`, `RegisterTicketControllersEvent.java:16`, `AddPackFindersEvent`, `RegisterSpawnPlacementsEvent`, `EntityAttributeCreationEvent`, `ModifyDefaultComponentsEvent`).

Subscribing, as used in the repo and BuildCraft:
- `NeoForge.EVENT_BUS` is `[srcjar] net/neoforged/neoforge/common/NeoForge.java:17`.
- MegaBlockBot: `src/main/java/com/legoj15/megablockbot/platform/neoforge/NeoForgeLoaderServices.java:52`: `NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> listener.accept(event.getServer()));`
- Mod bus from the constructor: `NeoForgeMod` takes `(IEventBus modEventBus, Dist dist, ModContainer container)` (`[srcjar] net/neoforged/neoforge/common/NeoForgeMod.java:530`). MegaBlockBot's `NeoForgeEntrypoint.java:10` has a no-arg constructor.

| Event (`[srcjar] net/neoforged/neoforge/event/...`) | Line | Bus | Notes |
|---|---|---|---|
| `RegisterCommandsEvent` | `RegisterCommandsEvent.java:22` | game | `getDispatcher()`, `getCommandSelection()`, `getBuildContext()`. BuildCraft uses `event.getDispatcher()` at `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeGameHooks.java:224`. |
| `ServerStartingEvent` / `ServerStartedEvent` / `ServerStoppingEvent` / `ServerStoppedEvent` | `server/ServerStartingEvent.java:19`, `server/ServerStartedEvent.java:15`, `server/ServerStoppingEvent.java:15` | game | Base `ServerLifecycleEvent.java:11`, `getServer()`. `ServerAboutToStartEvent` also exists. |
| `ServerTickEvent.Pre` / `.Post` | `tick/ServerTickEvent.java:18,48,59` | game | `getServer()`, `hasTime()`. BuildCraft: `NeoForgeGameHooks.java:67,72`. |
| `LevelTickEvent.Pre` / `.Post` | `tick/LevelTickEvent.java:20,51,62` | game | `getLevel()`. BuildCraft: `NeoForgeGameHooks.java:77`. |
| `PlayerEvent.PlayerLoggedInEvent` / `PlayerLoggedOutEvent` | `entity/player/PlayerEvent.java:422,428` (base `:36`) | game | Posted from `EventHooks.firePlayerLoggedIn/Out` (`EventHooks.java:906,910`), which `PlayerList.remove` calls (`[srcjar] net/minecraft/server/players/PlayerList.java:361`). |
| `PlayerEvent.Clone` | `PlayerEvent.java:231` | game | Posted from `ServerPlayer.java:1455` via `EventHooks.onPlayerClone` (`EventHooks.java:606-608`). |
| `LivingDeathEvent` | `entity/living/LivingDeathEvent.java:34`, ctor `:37` | game | Cancellable. `getSource()` returns the `DamageSource`. |
| `EntityJoinLevelEvent` | `entity/EntityJoinLevelEvent.java:30`; ctors `:34` `(Entity, Level)` and `:38` `(Entity, Level, boolean loadedFromDisk)` | game | Cancellable. `getLevel()`, `loadedFromDisk()`. BuildCraft: `NeoForgeGameHooks.java:124`. |
| `BlockEvent.BreakEvent` | `level/BlockEvent.java:69`, ctor `:72` `(Level, BlockPos, BlockState, Player)` | game | Cancellable. `getPlayer()`. |
| `BlockEvent.EntityPlaceEvent` | `level/BlockEvent.java:98`, ctor `:104` `(BlockSnapshot, BlockState placedAgainst, @Nullable Entity)` | game | Cancellable. `getEntity()`, `getPlacedBlock()`, `getPlacedAgainst()`. BuildCraft (priority form): `NeoForgeGameHooks.java:196`. |
| `BlockEvent` base | `level/BlockEvent.java:31` | game | `getLevel()`, `getPos()`, `getState()`. |

## 4. Persistence

### 4.1 SavedData (1.21.1 form)

- `[srcjar] net/minecraft/world/level/saveddata/SavedData.java`: `public abstract CompoundTag save(CompoundTag, HolderLookup.Provider)` `:19`; `setDirty()` `:21`, `setDirty(boolean)` `:25`.
- `SavedData.Factory<T>` is a record `(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer, @Nullable DataFixTypes type)` at `:54`, with a two-argument constructor at `:57` (type null).
- Access: `ServerLevel.getDataStorage()` `[srcjar] net/minecraft/server/level/ServerLevel.java:1349` → `DimensionDataStorage.computeIfAbsent(Factory<T>, String id)` `[srcjar] net/minecraft/world/level/storage/DimensionDataStorage.java:43`. Overworld: `server.overworld().getDataStorage()`.
- File: `<dimension>/data/<id>.dat` (`DimensionDataStorage.java:39-41`; `ServerChunkCache.java:87` builds the storage; `[srcjar] net/minecraft/util/worldupdate/WorldUpgrader.java:85` shows the `resolve("data")` form).
- Saved with the level: `DimensionDataStorage.save()` `:135`, called from `ServerLevel.java:837`.
- On-disk example: `E:/GitHub/BuildCraft/src/main/java/buildcraft/core/marker/PathSavedData.java:45-55` (the 1.21.1 arm, CODEC-based).

```java
public final class BodyRegistry extends SavedData {
    public static final SavedData.Factory<BodyRegistry> FACTORY =
        new SavedData.Factory<>(BodyRegistry::new, BodyRegistry::load);
    private static BodyRegistry load(CompoundTag tag, HolderLookup.Provider p) { ... }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) { ...; return tag; }
}
BodyRegistry reg = server.overworld().getDataStorage().computeIfAbsent(BodyRegistry.FACTORY, "megablockbot_bodies");
reg.setDirty();
```

### 4.2 Data attachments

- Register: `DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, modid)` (BuildCraft `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeAttachmentServices.java:41-42`). The registry is `[srcjar] net/neoforged/neoforge/registries/NeoForgeRegistries.java:43`. Subscribe with `register.register(modBus)` (`NeoForgeAttachmentServices.java:73`).
- Builder (`[srcjar] net/neoforged/neoforge/attachment/AttachmentType.java`): `builder(Supplier<T>)` `:95`; `builder(Function<IAttachmentHolder,T>)` `:109`; `Builder.serialize(Codec<T>)` `:184`; `serialize(Codec, Predicate)` `:196`; `copyOnDeath()` `:224` (throws if no serializer is set).
- Access: `AttachmentHolder.getData(AttachmentType<T>)` `[srcjar] net/neoforged/neoforge/attachment/AttachmentHolder.java:74`; `setData` `:97`; `removeData` `:107`; `hasData` `:68`. `Entity extends AttachmentHolder` (`[srcjar] net/minecraft/world/entity/Entity.java:131`).
- NBT key: `AttachmentHolder.ATTACHMENTS_NBT_KEY = "neoforge:attachments"` `:27`. Written in `Entity.saveWithoutId` (`Entity.java:1719-1720`) and read in the load path (`Entity.java:1804`).
- Player .dat: YES, attachment data is in the player file. `PlayerDataStorage.save(Player)` (`[srcjar] net/minecraft/world/level/storage/PlayerDataStorage.java:33-35`) calls `saveWithoutId`, and writes `<uuid>.dat` (`:39`).
- Copy on death: `AttachmentInternals.onPlayerClone` `[srcjar] net/neoforged/neoforge/attachment/AttachmentInternals.java:57-59` on `PlayerEvent.Clone`. `copyEntityAttachments(from, to, isDeath)` `:52-54` copies only `copyOnDeath` types when `isDeath`, and all types otherwise (returning from the End).

```java
public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
    DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "megablockbot");
public static final DeferredHolder<AttachmentType<?>, AttachmentType<BotState>> BOT_STATE =
    ATTACHMENTS.register("bot_state", () -> AttachmentType.builder(BotState::new)
        .serialize(BotState.CODEC).copyOnDeath().build());
// constructor: ATTACHMENTS.register(modEventBus);
BotState s = player.getData(BOT_STATE.get());
```

### 4.3 Vanilla player save and remove

- `PlayerList.save(ServerPlayer)` `[srcjar] net/minecraft/server/players/PlayerList.java:346-348`: returns early when `p.connection == null` (`:347`), otherwise calls `playerIo.save(p)` (`:348`).
- `PlayerList.remove(ServerPlayer)` `:360`: fires the logged-out event (`:361`), then `save` (`:364`).
- Periodic: `PlayerList.saveAll()` `:676-679` loops over all players calling `save`.
- Load: `PlayerList.java:338-340` (`firePlayerLoadingEvent`, then `playerIo.load`).
- `PlayerDataStorage` (`[srcjar] net/minecraft/world/level/storage/PlayerDataStorage.java`): `save` `:33` writes a temp file and swaps it in, keeping `.dat_old`; `load` `:74` reads `.dat` then `.dat_old`; `getPlayerDir()` `:89`.

## 5. Chunk loading

- Vanilla: `ServerChunkCache.addRegionTicket(TicketType<T>, ChunkPos, int radius, T value)` `[srcjar] net/minecraft/server/level/ServerChunkCache.java:442`, and the overload with `boolean forceTicks` at `:445` → `DistanceManager.addRegionTicket(..., forceTicks)` (`[srcjar] net/minecraft/server/level/DistanceManager.java:194`). `removeRegionTicket` is `:449` / `:452`.
- `TicketType.create(name, Comparator)` `[srcjar] net/minecraft/server/level/TicketType.java:21` and `create(name, Comparator, int timeout)` `:25`. `FORCED` is `:16`.
- Vanilla persistent force-load: `ServerLevel.setChunkForced(x, z, boolean)` `[srcjar] net/minecraft/server/level/ServerLevel.java:1394` (uses `ForcedChunksSavedData`, file id `chunks`). Ticking, no owner.
- NeoForge `TicketController` (`[srcjar] net/neoforged/neoforge/common/world/chunk/TicketController.java:27`): `record TicketController(ResourceLocation id, @Nullable LoadingValidationCallback callback)`, plus a one-argument constructor. `forceChunk(ServerLevel, BlockPos owner, int chunkX, int chunkZ, boolean add, boolean ticking)` `:47`; overloads take an `Entity` or a `UUID` owner. `ticking=true` gives full chunk ticks with no player nearby (javadoc).
- Registration: `RegisterTicketControllersEvent` (`[srcjar] .../common/world/chunk/RegisterTicketControllersEvent.java:16`, mod bus) with `register(TicketController)` `:29`. Posted by `ForcedChunkManager.init()` (`[srcjar] .../ForcedChunkManager.java:49-66`), which throws on a duplicate id. Javadoc (`TicketController.java:9-13`): a controller that is not registered has its tickets discarded on world load, and forceChunk on it throws `IllegalArgumentException`.
- On-disk example: `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeChunkTickets.java:23-37` (registers on the mod bus, forceChunk with `ticking=false`).

```java
public static final TicketController BOT_TICKETS =
    new TicketController(ResourceLocation.fromNamespaceAndPath("megablockbot", "bots"));
modEventBus.addListener((RegisterTicketControllersEvent e) -> e.register(BOT_TICKETS));
BOT_TICKETS.forceChunk(level, ownerPos, chunkX, chunkZ, true, true);
```

## 6. Loot tables, loot modifiers, trades, data maps

### 6.1 Every loot table on the server

- `server.reloadableRegistries()` `[srcjar] net/minecraft/server/MinecraftServer.java:1955` → `ReloadableServerRegistries.Holder` `[srcjar] net/minecraft/server/ReloadableServerRegistries.java:126`.
- `getKeys(ResourceKey<? extends Registry<?>>)` `:141` with `Registries.LOOT_TABLE` lists every table id.
- `getLootTable(ResourceKey<LootTable>)` `:145` returns `LootTable.EMPTY` when absent (`LootTable.java:34`).
- Rolling: `LootTable.getRandomItems(LootParams, RandomSource)` `[srcjar] net/minecraft/world/level/storage/loot/LootTable.java:112` and `getRandomItems(LootParams)` `:120`. `getParamSet()` `:131` gives the required param set.
- Params: `LootParams.Builder(ServerLevel)` `[srcjar] net/minecraft/world/level/storage/loot/LootParams.java:75`; `withParameter` `:83`; `create(LootContextParamSet)` `:126`.

```java
ReloadableServerRegistries.Holder h = server.reloadableRegistries();
for (ResourceLocation id : h.getKeys(Registries.LOOT_TABLE)) {
    LootTable t = h.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
    LootParams p = new LootParams.Builder(level)/* .withParameter(...) */.create(t.getParamSet());
    ObjectArrayList<ItemStack> drops = t.getRandomItems(p);
}
```

### 6.2 Global loot modifiers

- Interface `[srcjar] net/neoforged/neoforge/common/loot/IGlobalLootModifier.java:27`: `ObjectArrayList<ItemStack> apply(ObjectArrayList<ItemStack> generatedLoot, LootContext context)` `:43`; `MapCodec<? extends IGlobalLootModifier> codec()` `:48`. Its codec needs a serializer in `NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS` (javadoc, `:24`).
- Manager: `LootModifierManager extends SimpleJsonResourceReloadListener` `[srcjar] net/neoforged/neoforge/common/loot/LootModifierManager.java:33`; constructor `:40`; folder `"loot_modifiers"` `:38`; `getAllLootMods()` `:94`.
- Created per reload in `[srcjar] net/neoforged/neoforge/common/NeoForgeEventHandler.java:156-159` (game-bus `AddReloadListenerEvent` handler).
- Running: `CommonHooks.modifyLoot(ResourceLocation lootTableId, ObjectArrayList<ItemStack>, LootContext)` `[srcjar] net/neoforged/neoforge/common/CommonHooks.java:1080-1087` (public static; sets the queried id on the context, then applies each modifier in order). Called from `LootTable.java:127`, so rolling a table applies modifiers.

### 6.3 Villager and wandering-trader trades

- Vanilla storage: `VillagerTrades.TRADES` `[srcjar] net/minecraft/world/entity/npc/VillagerTrades.java:81`, type `Map<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>>`, levels 1..5. `VillagerTrades.WANDERING_TRADER_TRADES` `:662`, type `Int2ObjectMap<ItemListing[]>`, key 1 generic and 2 rare. `ItemListing` is at `:1593` with `getOffer(Entity, RandomSource)` at `:1595`.
- Populated on `TagsUpdatedEvent` with cause `SERVER_DATA_LOAD`: `[srcjar] net/neoforged/neoforge/common/VillagerTradingManager.java:37-41` (`loadTrades`), registered on the game bus at `[srcjar] net/neoforged/neoforge/common/NeoForgeMod.java:575`.
- Mechanics: the static block `VillagerTradingManager.java:28-35` snapshots vanilla. Each `SERVER_DATA_LOAD` rebuilds from that snapshot: wanderer at `:47-55`; villagers at `:60-75`, one mutable `List<ItemListing>` per level 1..5 (`:63-66`), `VillagerTradesEvent` posted at `:70`, then written back into `TRADES` at `:71-73`.
- Events (game bus): `VillagerTradesEvent` `[srcjar] net/neoforged/neoforge/event/village/VillagerTradesEvent.java:30`, constructors `:39` and `:44`, `getTrades()` returns `Int2ObjectMap<List<ItemListing>>` `:50`, `getType()` `:54`. `WandererTradesEvent.java:23`, constructors `:32` and `:37`, `getGenericTrades()` / `getRareTrades()` `:43`, `:47`.
- Caveat: `TRADES` is refreshed only on `SERVER_DATA_LOAD`. Before the first data load it holds vanilla values.

### 6.4 Furnace fuels and compostables (NeoForge data maps)

- `NeoForgeDataMaps.FURNACE_FUELS` `[srcjar] net/neoforged/neoforge/registries/datamaps/builtin/NeoForgeDataMaps.java:67-68`: `DataMapType<Item, FurnaceFuel>`, id `furnace_fuels`. Value `FurnaceFuel(int burnTime)` `FurnaceFuel.java:17`.
- `NeoForgeDataMaps.COMPOSTABLES` `:51-52`: `DataMapType<Item, Compostable>`, id `compostables`. Value `Compostable(float chance, boolean canVillagerCompost)` `Compostable.java:17`. Javadoc says it replaces `ComposterBlock.COMPOSTABLES` (`NeoForgeDataMaps.java:42`).
- Read API: `IWithData.getData(DataMapType<R,T>)` `[srcjar] net/neoforged/neoforge/registries/datamaps/IWithData.java:23`; `BaseMappedRegistry.getData(DataMapType<T,A>, ResourceKey<T>)` `[srcjar] net/neoforged/neoforge/registries/BaseMappedRegistry.java:124`; `IRegistryExtension.getDataMap(DataMapType)` `:143`. Javadoc (`DataMapType.java:54-55`) says Holder implementations expose `getData`.
- Data generation: `[srcjar] net/neoforged/neoforge/common/data/internal/NeoForgeDataMapsProvider.java:62-70`.
- JSON location under `data/<ns>/...` is UNVERIFIED here.

## 7. Config

- `ModConfigSpec` is `[srcjar] net/neoforged/neoforge/common/ModConfigSpec.java`. Builder examples: `E:/GitHub/BuildCraft/src/main/java/buildcraft/core/BCUnifiedConfig.java:22-45` (`new ModConfigSpec.Builder()`, `push`/`pop`, `build()`); typed values at `E:/GitHub/BuildCraft/src/main/java/buildcraft/builders/BCBuildersConfig.java:13-29`.
- `int` form: `IntValue defineInRange(String path, int defaultValue, int min, int max)` `ModConfigSpec.java:770`.

```java
public final class MbbConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_BOTS;
    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        MAX_BOTS = b.defineInRange("maxBots", 4, 0, 64);
        SPEC = b.build();
    }
}
// mod constructor (ModContainer container): container.registerConfig(ModConfig.Type.SERVER, MbbConfig.SPEC);
```
- Registration: `ModContainer.registerConfig(ModConfig.Type, IConfigSpec[, String fileName])` (`[fml] net/neoforged/fml/ModContainer`; javap). NeoForge's own uses are `[srcjar] net/neoforged/neoforge/common/NeoForgeMod.java:568-570`. BuildCraft's 1.21.1 arm: `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeConfigRegistrar.java:38`.
- `ModConfig.Type` values (javap of `[fml]`): `COMMON`, `CLIENT`, `SERVER`, `STARTUP`.
- Reading before load: `ConfigValue.get()` `ModConfigSpec.java:1220` reads through `getRaw`, which calls `Preconditions.checkState(loadedConfig != null, "Cannot get config value before config is loaded.")` at `:1235` (IllegalStateException). Guard with `SPEC.isLoaded()` (`:138`). `get()` caches the value after first read (`:1220-1225`). BuildCraft guards like this at `E:/GitHub/BuildCraft/src/main/java/buildcraft/builders/container/LibraryUploadBuffer.java:94`.
- Load and reload hooks, mod bus: `ModConfigEvent.Loading` / `.Reloading` (`[fml] net/neoforged/fml/event/config/ModConfigEvent`). BuildCraft: `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeConfigHooks.java:18-30` (filters on `getConfig().getSpec() == spec`).
- Server config files live under the world's `serverconfig/` folder: MegaBlockBot's run dir has `versions/1.21.1/run/world/serverconfig/readme.txt` (observed; FML naming logic not read).

## 8. Mixins and access transformers

### 8.1 Access transformers (MDG)
- `[mdg] net/neoforged/moddevgradle/dsl/ModDevExtension` has `accessTransformers(Action<DataFileCollection>)`; `DataFileCollection.from(Object...)` (javap). Form:

```kotlin
neoForge {
    accessTransformers.from(file("src/main/resources/META-INF/accesstransformer.cfg"))
}
```
- Examples: `E:/GitHub/BuildCraft/buildSrc/src/main/kotlin/bc.neoforge.gradle.kts:138-139` (the file is at the Tree root, so `rootProject.file`); `E:/GitHub/SprawlCrafting/build.neoforge.gradle.kts:99-100` (`if (at.exists()) accessTransformers.from(at)`).
- Default location: `META-INF/accesstransformer.cfg`. Doc `docs/advanced/accesstransformers.md:23` says so. BuildCraft's `neoforge.mods.toml` declares no `[[accessTransformers]]` and relies on the default (checked). Declaring other files needs `[[accessTransformers]] file="..."` in `neoforge.mods.toml` (doc, `accesstransformers.md:27-30`).
- Syntax example: `E:/GitHub/BuildCraft/src/main/resources/META-INF/accesstransformer.cfg:5`: `public net.minecraft.world.item.crafting.ShapedRecipe result # result`. `#` starts a comment.
- Doc mismatch: `docs/advanced/accesstransformers.md:16-18` shows the NeoGradle form `minecraft { accessTransformers { file(...) } }`. The MDG form above is the one that compiles in this repo.

### 8.2 Mixins
- Declared in `neoforge.mods.toml`: `[[mixins]]` with `config = "<modid>.mixins.json"` (`E:/GitHub/SprawlCrafting/src/main/resources/META-INF/neoforge.mods.toml:15-16`; doc `gettingstarted/modfiles.md:171-177`).
- Config file example: `E:/GitHub/SprawlCrafting/src/main/resources/sprawlcrafting.mixins.json`: `required`, `minVersion "0.8"`, `package`, `refmap` (`:5`), `compatibilityLevel "JAVA_21"`, `client`/`server` lists, `injectors.defaultRequire`.
- Annotation processor: none in SprawlCrafting's NeoForge build (`build.neoforge.gradle.kts`: the only `mixin` hits are excludes at `:229` and `:267-268`). The `refmap` file it names does not exist in its tree (`find` found none). MDG 2.0.147 contains no mixin annotation-processor wiring for NeoForge (its `MixinExtension`/`MixinCompilerArgs` classes are `legacyforge` only).
- Runtime effect of a named but missing refmap: UNVERIFIED.

```toml
[[mixins]]
config = "megablockbot.mixins.json"
```

## 9. Jar-in-jar, local dependency, licences

- Example: `E:/GitHub/SprawlCrafting/build.neoforge.gradle.kts:55-61` (comment: nest, never shade) and `:197-211`:

```kotlin
dependencies {
    implementation("com.legoj15:solver:$solverVersion")
    if (minecraftVersion.startsWith("1.")) {
        "additionalRuntimeClasspath"("com.legoj15:solver:$solverVersion")   // dev-run only, 1.x lines
    }
    jarJar("com.legoj15:solver") {
        version {
            strictly(solverRange)                     // val solverRange is build.neoforge.gradle.kts:63
            prefer(solverVersion)
        }
    }
}
```
- The local dependency is a composite build: `E:/GitHub/SprawlCrafting/settings.gradle.kts:23` (`includeBuild("solver-core") { dependencySubstitution { ... } }`). Version: `solver-core/gradle.properties:4` (2.1.0).
- MDG side: `[mdg] net/neoforged/moddevgradle/internal/jarjar/JarJarPlugin` adds a per-source-set `jarJar` configuration and task (javap); `tasks/JarJar` writes the metadata (`javap`). Output: `META-INF/jarjar/<name>.jar` + `metadata.json` (SprawlCrafting's `docs/solver-library.md` table; UNVERIFIED by me).
- Dev runs: 1.x NeoForge needs the library on the game classpath as well, hence `additionalRuntimeClasspath` (`build.neoforge.gradle.kts:203-205`; `docs/solver-library.md` "Dev runs" section).
- Licences: the solver publishes MIT (`solver-core/build.gradle.kts:67-71`). MegaBlockBot is MIT: `LICENSE` line 1; `src/main/resources/META-INF/neoforge.mods.toml:3`.
- On-disk second source set: none ships as its own jar on NeoForge. BuildCraft merges its test source set into the main mod (`E:/GitHub/BuildCraft/buildSrc/src/main/kotlin/bc.neoforge.gradle.kts:215`). Its gametest mod is a separate jar on Fabric only (`bc.fabric.gradle.kts:112-133`).

## 10. Reload and datapack hooks

- `AddReloadListenerEvent` `[srcjar] net/neoforged/neoforge/event/AddReloadListenerEvent.java:31` (game bus, extends `Event`): `addListener(PreparableReloadListener)` `:44`; `getRegistryAccess()` `:74`; `getServerResources()` `:55`. Example: `NeoForgeEventHandler.java:156-159`.
- `TagsUpdatedEvent` `[srcjar] net/neoforged/neoforge/event/TagsUpdatedEvent.java:14`: `getUpdateCause()` `:35`; `enum UpdateCause` `:50` (`SERVER_DATA_LOAD` is the one used by `VillagerTradingManager.java:38`).
- `OnDatapackSyncEvent` `[srcjar] net/neoforged/neoforge/event/OnDatapackSyncEvent.java:19` (`getPlayer()`, `getRelevantPlayers()`, `getPlayerList()`). BuildCraft uses it at `E:/GitHub/BuildCraft/src/main/java/buildcraft/platform/neoforge/NeoForgeGameHooks.java:229`. Exact firing times: UNVERIFIED.
- Datapack reload entry: `ReloadableServerRegistries.reload(...)` `[srcjar] net/minecraft/server/ReloadableServerRegistries.java:45`.
- Index-rebuild pattern (fact from code): register a `PreparableReloadListener` on `AddReloadListenerEvent`. `LootModifierManager` (`LootModifierManager.java:33`) is the in-tree model for a JSON-backed one.

```java
NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(myIndexListener));
```

## 11. Build wiring facts for MegaBlockBot

- Stonecutter names: `[srcjar]` has `net/minecraft/resources/ResourceLocation.java` and no `Identifier`. BuildCraft's 26.x code uses `Identifier`, and its root `build.gradle.kts:35-66` has a Stonecutter replacement pair for pre-1.21.11 nodes. MegaBlockBot's `stonecutter.gradle.kts` has no such pair (only `constants.match(loader, ...)`). Use `ResourceLocation` in 1.21.1 code, or add the pair.
- MegaBlockBot's `build.gradle.kts` declares only `client` and `server` runs and one mod with `sourceSet(sourceSets["main"])`. The `gameTestServer` run and any test source set are not declared yet.

## 12. Adjacent issues

1. `PlayerList.save` returns early for any player with `connection == null` (`[srcjar] net/minecraft/server/players/PlayerList.java:347`). A body with no stub connection never gets its `.dat` (or its attachments) written, and is not saved by `saveAll`. This bears on `AI-PLAYER-DESIGN.md:381-384` (goal state "in the body's player data"). The design depends on the stub connection being non-null.
2. On 1.21.1 a startup failure in a game-test run exits 0 (`Main.java:309-311`). A green CI exit does not prove tests ran; BuildCraft reads the log for it.
3. Stale docs: `E:/GitHub/SprawlCrafting/docs/solver-library.md` quotes verified production loading of solver 1.0.0 and JitPack 1.0.1, while `solver-core/gradle.properties:4` is 2.1.0.
4. Stale docs: NeoForge 1.21.1 `advanced/accesstransformers.md` uses the NeoGradle `minecraft {}` DSL; MDG uses `neoForge { accessTransformers.from(...) }`.
5. Stale build output: BuildCraft's `versions/1.21.1/build/moddev/artifacts/` has 21.1.250 and 21.1.251, not its pinned 21.1.265.
6. SprawlCrafting's mixin config names `sprawlcrafting.refmap.json`, but no build step produces it. Runtime effect UNVERIFIED.
7. BuildCraft `NeoForgeChunkTickets.java` uses `Identifier` in the source it shares with 1.21.1, relying on the Stonecutter pair (see section 11).
8. The design doc's claim that villager trades are "read from the populated trade lists after the events fire" (`AI-PLAYER-DESIGN.md:523-525`) matches `VillagerTradingManager`, with the caveat in 6.3 that the lists are rebuilt from a snapshot on each `SERVER_DATA_LOAD`.

## 13. Open questions (not settled from the repo)

- FML routing of `@EventBusSubscriber` without a `bus` element. `[fml]` default for `bus()` is `GAME` (javap). BuildCraft says FML picks the bus per method from the event type on 21.1.265 (`NeoForgeGameTestHooks.java:21-23`). Not verified against FML bytecode; needs a run.
- Runtime behaviour of a mixin config whose `refmap` is absent.
- `forceTicks` semantics in `Ticket`/`DistanceManager` beyond the flag name. Chose not to read further.
- `LootParams` required parameters per loot table type (only the builder names above were read).
- Datapack JSON paths for `furnace_fuels` and `compostables` on 21.1.
- Whether MDG 2.0.147's `jarJar(...)` dependency DSL needs anything beyond the SprawlCrafting block.
- Exact firing order of `OnDatapackSyncEvent` relative to `TagsUpdatedEvent` on a reload.
