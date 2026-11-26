# BuildCraft (1.21.1 NeoForge) facts for MegaBlockBot

Scout handoff. Paths are relative to `E:/GitHub/BuildCraft/` unless they start with another root. Every fact is read from code, not docs, unless marked UNVERIFIED. Where `docs/platform-layer.md` disagrees with the code, the code is cited. Nothing under `.claude/worktrees/` was used: the repo holds copies of the recipe classes there and grep results include them.

## 1. Build, run, and loading a second mod

Node facts (`versions/1.21.1/gradle.properties`):
- `minecraft_version=1.21.1` (line 7), `neo_version=21.1.265` (line 8), `jei_version=19.27.0.340` (line 9), `rei_version=16.0.799` (line 12), `java_version=21` (line 20), `neo_dep_range=[21.1.151,21.2)` (line 30), `mc_dep_range=[1.21.1,1.21.2)` (line 31).
- Node list: `settings.gradle.kts:25` `versions("26.3", ..., "1.21.1")`. The 1.21.1 project dir is `versions/1.21.1/`. The active Stonecutter node is 26.3, so unqualified tasks target it. Always qualify: `:1.21.1:<task>`.
- Loader selection: root `build.gradle.kts:57` `constants.match(loader, "neoforge", "fabric", "forge")`. The `loader` property (root `gradle.properties`, `loader=neoforge`) picks the convention plugin `bc.neoforge` (`build.gradle.kts`, the `apply(plugin = "bc.$loader")` line).
- All NeoForge wiring is in `buildSrc/src/main/kotlin/bc.neoforge.gradle.kts`: `neoForge {}` at :135 (`version = neoVersion`), access transformer at :139, run configs `client` :142, `server` :165, `gameTestServer` :172, mod registration `buildcraftunofficial` (main + test source sets) :213, `unitTest { enable() }` :222, jar `archiveBaseName = "BuildCraftRedux"` :366, `buildAndCollect` :445, `nodeVerification` :454.

Commands (all verified as tasks in `bc.neoforge.gradle.kts`):
- Compile: `./gradlew :1.21.1:compileJava`
- Unit tests (FML-JUnit boots a real loader first): `./gradlew :1.21.1:test`
- Dev client: `./gradlew :1.21.1:runClient`. Run dir `versions/1.21.1/run` (default MDG working dir; `gameDirectory` is set only when `-PbcRunDir` is given, :162). Other flags: `-PbcQuickPlay=<save>`, `-PbcDebug[=tier]`.
- Dev server: `./gradlew :1.21.1:runServer`. Run dir the same default. Sets `buildcraft.dev=true`. No `--nogui` (MegaBlockBot passes `--nogui`, `MegaBlockBot/build.gradle.kts:55`). `-PbcRunDir` does NOT apply to the server run (only :162, the client run).
- GameTest server: `./gradlew :1.21.1:runGameTestServer`. Type `gameTestServer` (:173), property `neoforge.enableGameTest=true` (:174), game dir `versions/1.21.1/run-gameTestServer` (`gameTestRunDir`, :57, used at :177). Filter: `-PbcTests=buildcraftunofficial:engine_*` (:191 for 1.21.10+; on 1.21.1 it is the `buildcraft.gameTestFilter` system property, `LegacyGameTestRegistry`).
- Jar: `./gradlew :1.21.1:buildAndCollect` (:445). Output `versions/1.21.1/build/libs/BuildCraftRedux-<mod_version>+mc1.21.1.jar` (archive name :366, version `$modVersion+mc$minecraftVersion`), copied to root `build/libs/<mod_version>/`. Root `gradle.properties:25` `mod_version=2026.5.0`.
- Whole-node gate: `./gradlew :1.21.1:nodeVerification` (:454) = `test` + `runGameTestServer` + `buildAndCollect`.
- Helper: `runClientAndServer.ps1` (root) takes `-Node` (`param` at :6, default `26.3`), opens a split Windows Terminal pair. Pass `-Node 1.21.1`.
- Toolchain: JDK 21 for this node via the foojay resolver (`build.gradle.kts`, `java_version`). Reference NeoForge sources for this pin are in `.neoforge-ref/sources-21.1.265/` (present).

How a second mod jar gets into the dev workspace:
- `-PwithMods=<alias>` only resolves Maven coordinates defined as `withmod.<alias>=...` in `versions/<node>/gradle.properties`. A local jar is not covered by it (see `buildSrc/src/main/kotlin/buildcraft/gradle/WithMods.kt`).
- The route used for McDevBridge: copy the jar into the node's `run/mods/`. McDevBridge's own build says so (`McDevBridge/build.neoforge.gradle.kts:114-117`), and `versions/1.21.1/run/mods/` already holds `mcdevbridge-0.1.8+mc1.21.1.jar` plus Mekanism, AE2, Modular Machinery, guideme and others. `versions/1.21.1/run-gameTestServer/mods/` exists and is empty.
- UNVERIFIED by launch: that the MDG run reads `run/mods` without extra config. The existing jars are evidence, not a run.

Simplest route to a 1.21.1 server with BuildCraft and MegaBlockBot together (recommended by this scout, not tested):
1. In MegaBlockBot: `./gradlew :1.21.1:build`. Jar name from `MegaBlockBot/build.gradle.kts:37` (`archivesName = modId`, `mod_id=megablockbot`) and `:39` (`version = "$modVersion+mc$minecraftVersion"`, `mod_version=0.1.0`): expected `versions/1.21.1/build/libs/megablockbot-0.1.0+mc1.21.1.jar` (UNVERIFIED: not built).
2. Copy that jar into `E:/GitHub/BuildCraft/versions/1.21.1/run/mods/`. Re-copy after every MegaBlockBot build; stale copies accumulate because nothing clears the folder.
3. In BuildCraft: `./gradlew :1.21.1:runServer`. For game tests, also copy the jar into `versions/1.21.1/run-gameTestServer/mods/`.
- Caveat: the BuildCraft `run/mods` already loads unrelated mods (Mekanism, AE2, Modular Machinery), so the run is not clean. `-PbcRunDir` gives a clean client directory but not a clean server directory.

Compile-time dependency of MegaBlockBot on BuildCraft: BuildCraft has NO publishing block (no `maven-publish` anywhere in `build.gradle.kts` or `buildSrc`). MegaBlockBot can only compile against a jar path (`files(...)`) or the repo must add publishing. See open questions.

## 2. GameTests on 1.21.1

Registration and loading:
- Single source of truth: `src/test/java/buildcraft/BuildCraftGameTests.java`, `registerAll(BiConsumer<String, Supplier<Consumer<GameTestHelper>>>)` (one `reg.accept("buildcraftunofficial:<id>", () -> Class::method)` per test). The 1.21.10+ `register(boolean)` at the top of the class is compiled out on 1.21.1 (`//? if >=1.21.10`).
- 1.21.1 hook: `src/test/java/buildcraft/platform/neoforge/NeoForgeGameTestHooks.java`. The 1.21.1 arm subscribes to `RegisterGameTestsEvent` and calls `buildcraft.lib.test.LegacyGameTestRegistry.registerAll()`; `onConstruct` calls `TestOnlyBlocks.register()` when `neoforge.enableGameTest` is set.
- `LegacyGameTestRegistry.java:38` feeds `GameTestRegistry` by reflection; honours `-Dbuildcraft.gameTestFilter`.
- Wrapping: `buildcraft/lib/test/GameTestTrace.java:34` `wrap(id, sup)` is the identity unless `-Dbuildcraft.gameTestTrace` is set.
- Arena: `src/test/resources/data/buildcraftunofficial/structure/empty.nbt` (16x16x16 air stand-in for `minecraft:empty`, per the add-game-test skill, `.claude/skills/add-game-test/SKILL.md`).
- Manifest (third required part, else the test silently skips): `src/test/resources/data/buildcraftunofficial/test_instance/<id>.json`. Guard: `src/test/java/buildcraft/GameTestManifestTester.java`. Poll budget guard: `GameTestPollBudgetTester`. Rule: `max_ticks` must exceed the largest poll budget.

Pattern test (use this shape):
- Body: `src/test/java/buildcraft/energy/EngineTester.java:238` `testStirlingEngineReleasesStoredPowerWithoutFuel(GameTestHelper helper)`. It forces the cell with `EntityArenaUtil.forceLoadCell(helper)`, `helper.setBlock(...)` for the engine, a `POWER_TESTER` block and a redstone block, charges the engine with `fastForwardEnergy`, then polls for received MJ.
- Registration: `BuildCraftGameTests.java:349` `reg.accept("buildcraftunofficial:engine_stirling_releases_stored_power", () -> buildcraft.energy.EngineTester::testStirlingEngineReleasesStoredPowerWithoutFuel)`.
- Manifest: `src/test/resources/data/buildcraftunofficial/test_instance/engine_stirling_releases_stored_power.json`: type `minecraft:function`, `function`, `environment: minecraft:default`, `structure: minecraft:empty`, `max_ticks: 420`, `setup_ticks: 0`, `required: true`.
- The engine-chain test name `engine_stirling_fuel` (EngineTester:155) asserts nothing on coal use (see adjacent issues); do not copy its assert shape.
- Rules from `.claude/skills/add-game-test/SKILL.md`: gate on observed state, not fixed ticks after placing blocks; keep positions inside the arena cell; `makeMockPlayer` is not a `ServerPlayer`.

Results:
- The run's console is quiet (log4j quiet mirror, `gradle/gameTestServerLog4j2-quiet.xml`, unless `-PbcLoudGameTestLogs`). The full log is `versions/1.21.1/run-gameTestServer/logs/latest.log`.
- Pass means: `GAME TESTS COMPLETE` and `All N required tests passed` with N > 0, and no `N required tests failed` or `Failed to start the minecraft server`. The 1.21.1 `doLast` in `bc.neoforge.gradle.kts:411` onward enforces this, because 1.21.1 exits 0 on failure.
- Last recorded run in that log: `955 GAME TESTS COMPLETE`, `All 955 required tests passed` (log lines 591-592, dated 15 Nov 2026).

## 3. AssemblyRecipeRegistry

- Registry: `src/main/java/buildcraft/lib/recipe/AssemblyRecipeRegistry.java:22` `public static final Map<String, AssemblyRecipe> REGISTRY = new HashMap<>();` (plain HashMap, keyed by registry name string). `:24` `register(AssemblyRecipe)` does `put(recipe.getRegistryName(), recipe)`, silently replacing on duplicate name. `:28-37` `getRecipesFor(NonNullList<ItemStack>)` returns every recipe whose `getOutputs` is non-empty.
- Recipe base: `src/main/java/buildcraft/api/recipes/AssemblyRecipe.java` (`abstract class`, `implements Comparable`). `:20` `Set<ItemStack> getOutputs(NonNullList<ItemStack> inputs)`; `:22` `Set<ItemStack> getOutputPreviews()`; `:24` `Set<IngredientStack> getInputsFor(ItemStack output)`; `:26` `long getRequiredMicroJoulesFor(ItemStack output)`; `:29` `setRegistryName(String)` (final, returns this); `:34` `setRegistryName(Object)` is a no-op stub; `:39` `String getRegistryName()`.
- Concrete class: `src/main/java/buildcraft/api/recipes/AssemblyRecipeBasic.java`. Constructors `:27` `(String name, long requiredMicroJoules, ImmutableSet<IngredientStack> requiredStacks, ItemStack output)` and `:35` the same with `Set<IngredientStack>`. Fields (all private): `requiredMicroJoules` (long), `requiredStacks` (ImmutableSet of IngredientStack), `output` (ImmutableSet of one ItemStack). `getOutputs` `:41-46` returns the output only if EVERY requirement is met by ONE stack (see adjacent issues).
- `src/main/java/buildcraft/api/recipes/IngredientStack.java`: `public final Ingredient ingredient` (vanilla `net.minecraft.world.item.crafting.Ingredient`), `public final int count` (:12-13); ctor `(Ingredient, int)` :15; ctor `(Ingredient)` = count 1 :20.
- Units: `src/main/java/buildcraft/api/mj/MjAPI.java:25` `MJ = ONE_MINECRAFT_JOULE`, which is `getMjValue()` = `1_000_000L` (:120). So one MJ is 1,000,000 micro-MJ; the field name `requiredMicroJoules` is in micro-MJ.
- Visibility: all of `AssemblyRecipe`, `AssemblyRecipeBasic`, `IngredientStack` and `AssemblyRecipeRegistry.REGISTRY` are public. An adapter in MegaBlockBot needs NO access transformer for: `getRegistryName`, `getInputsFor`, `getRequiredMicroJoulesFor`, `getOutputPreviews`, `IngredientStack.ingredient/count`, `REGISTRY`.
- Population (server): `src/main/java/buildcraft/silicon/BCSilicon.java:63` `Platform.lifecycle().onServerAboutToStart(server -> onServerAboutToStart())`, body `:94-97` calling `BCSiliconRecipes.ensureInitialized()`. The lifecycle hook is `src/main/java/buildcraft/platform/neoforge/NeoForgeLifecycleHooks.java:57-58`, which subscribes `ServerAboutToStartEvent` on `NeoForge.EVENT_BUS`. Not client-gated: reachable on a dedicated server.
- Population (client): `src/main/java/buildcraft/silicon/client/BCSiliconClient.java:255-262` `onClientLoggingIn()` calls `BCSiliconRecipes.ensureInitialized()` (:260); hooked at :282 via `ClientPlatform.hooks().onLoggingInEarly`. Also REI plugin `src/main/java/buildcraft/silicon/compat/rei/BCSiliconReiPlugin.java:89`.
- Not populated by class init, datapack reload, or a tag. The contents are hard-coded Java (`BCSiliconRecipes.java`).
- Guard: `src/main/java/buildcraft/silicon/BCSiliconRecipes.java:54-67` `public static synchronized void ensureInitialized()`, once per JVM (`initialized = true` is set BEFORE the register calls, so a throw leaves a partial registry with no retry).
- Consumers: `TileAssemblyTable.java:77` (updateRecipes), `:99` (`extract` simulate), `:260` (readData lookup by name); `AssemblyTableTransfer.java:86`; JEI/REI collectors; `GuideAssemblyRecipes.java`.

Constructor call pattern, from `BCSiliconRecipes.java`:
- Chipset (single input, count 1): `AssemblyRecipeRegistry.register(new AssemblyRecipeBasic("chipset_redstone", 10_000 * MjAPI.MJ, input, new ItemStack(BCSiliconItems.CHIPSET_REDSTONE.get())))` at :142-143.
- Count > 1 uses `new IngredientStack(Ingredient.of(...), 6)`, as in the tank recipe at :286-289.

## 4. Items for M5: ids, recipes, ingredients

Recipe source: crafting JSON under `src/main/resources/data/buildcraftunofficial/recipe/`. Assembly-table recipes are Java (section 3). Tags `#c:*` are NeoForge common tag keys (`.neoforge-ref/sources-21.1.265/net/neoforged/neoforge/common/Tags.java`); membership is NOT in this repo (UNVERIFIED per tag). BuildCraft's only `c:` tags are the gear tags at `src/main/resources/data/c/tags/item/gears/{wooden,stone,iron,gold,diamond}.json`, each listing one BC gear item.

Crafting (plain, no machine):
- Quarry, id `quarry`. Block: `src/main/java/buildcraft/builders/BCBuildersBlocks.java:62`. BE: `BCBuildersBlockEntities.java:48`. Recipe `recipe/quarry.json`, shaped "iri / gig / dpd": iron gear x3, gold gear x2, diamond gear x2, `#c:dusts/redstone` x1, `minecraft:diamond_pickaxe` x1.
- Gears (ids `gear_wood`, `gear_stone`, `gear_iron`, `gear_gold`, `gear_diamond`; items `BCCoreItems.java:75-79`):
  - `gear_wood`: `#c:rods/wooden` in a plus-shape (4 items), `recipe/gear_wood.json`.
  - `gear_stone`: `#minecraft:stone_crafting_materials` x4 + `#c:gears/wooden` x1 (center), `recipe/gear_stone.json`.
  - `gear_iron`: `#c:ingots/iron` x4 + `#c:gears/stone` x1, `recipe/gear_iron.json`.
  - `gear_gold`: `#c:ingots/gold` x4 + `#c:gears/iron` x1, `recipe/gear_gold.json`.
  - `gear_diamond`: `#c:gems/diamond` x4 + `#c:gears/gold` x1, `recipe/gear_diamond.json`.
- Goggles, id `goggles` (`BCCoreItems.java:66-72`, class `buildcraft/core/item/ItemGoggles.java:41`). Recipe `recipe/goggles.json`, shaped "SCS / I I / G G": `#c:strings` x2, `buildcraftunofficial:chipset_redstone` x1, `#c:ingots/iron` x2, `#c:glass_panes/colorless` x2.
- Laser, id `laser`. Block `BCSiliconBlocks.java:22`, BE `BCSiliconBlockEntities.java:27`, class `silicon/block/BlockLaser.java:42`, tile `silicon/tile/TileLaser.java:54`. Recipe `recipe/laser.json`, shaped "rro / rdd / rro": `#c:dusts/redstone` x5, `#c:gems/diamond` x2, `minecraft:obsidian` x2.
- Assembly table, id `assembly_table`. Block `BCSiliconBlocks.java:26`, BE `BCSiliconBlockEntities.java:32`, tile `silicon/tile/TileAssemblyTable.java:47`, menu `BCSiliconMenuTypes.java:26`. Recipe `recipe/assembly_table.json`, shaped "OdO / OrO / OgO": `minecraft:obsidian` x6, `#c:gems/diamond` x1, `#c:dusts/redstone` x1, `buildcraftunofficial:gear_diamond` x1.
- Engines (all three share the layout "www / _g_ / GpG": 3 of the material tag, 1 `#c:glass_blocks/colorless`, 2 gears, 1 `minecraft:piston`):
  - `engine_redstone` = Redstone Engine (lang `block.buildcraftunofficial.engine_redstone`). Block `BCCoreBlocks.java:51`, BE `BCCoreBlockEntities.java:35`, tile `core/tile/TileEngineRedstone_BC8.java:24`. Recipe `recipe/engine_redstone.json`: `#minecraft:planks` x3, `buildcraftunofficial:gear_wood` x2.
  - `engine_stone` = **Stirling Engine** (lang `en_us.json:176`). Block `BCEnergyBlocks.java:24`, tile `energy/tile/TileEngineStone_BC8.java:53`. Recipe `recipe/engine_stone.json`: `#minecraft:stone_tool_materials` x3, `buildcraftunofficial:gear_stone` x2.
  - `engine_iron` = **Combustion Engine** (lang `en_us.json:177`). Block `BCEnergyBlocks.java:28`, tile `energy/tile/TileEngineIron_BC8.java:44`. Recipe `recipe/engine_iron.json`: `#c:ingots/iron` x3, `buildcraftunofficial:gear_iron` x2.
  - Naming trap: the ids are swapped against intuition (`engine_stone` is the Stirling, `engine_iron` is the Combustion). Use ids, not English.
- Kinesis pipes (power pipes). Wooden Kinesis Pipe id `pipe_wood_power` (lang `en_us.json:302`), item `BCTransportItems.java:120`, definition `BCTransportPipes.java:134` (`flowPower`), behaviour `PipeBehaviourWoodPower` at `BCTransportPipes.java:133`. Recipe `recipe/pipe_wood_power.json` (shapeless): `buildcraftunofficial:pipe_wood_item` x1 + `#c:dusts/redstone` x1. Its input `recipe/pipe_wood_item.json` (shaped "lgr", one row): `#minecraft:planks` x2 + `#c:glass_blocks/colorless` x1, yields 8. Stone Kinesis Pipe `pipe_stone_power` (`en_us.json:304`) is the same shape from `pipe_stone_item`. Diamond wood: `pipe_diamond_wood_power`, `BCTransportItems.java:128`.
- Engines and chipsets are NOT plain crafting in this codebase, see below.

Assembly-table only (no crafting recipe; verified: no `recipe/*.json` has a chipset as `result`; chipsets appear only as ingredients in `goggles`, `advanced_crafting_table`, `integration_table`, `programming_table`, `robot`, `robot_station`, `volume_wrench`):
- Redstone chipset `chipset_redstone`: `BCSiliconItems.java:43`, recipe `BCSiliconRecipes.java:142-143`, ingredients: `Items.REDSTONE` x1, cost 10,000 MJ.
- Iron chipset `chipset_iron`: `BCSiliconItems.java:46`, `BCSiliconRecipes.java:148-149`: REDSTONE x1 + `IRON_INGOT` x1, 20,000 MJ.
- Gold `chipset_gold`: `BCSiliconItems.java:49`, `BCSiliconRecipes.java:154-155`: REDSTONE + GOLD_INGOT, 40,000 MJ.
- Quartz `chipset_quartz`: `BCSiliconItems.java:52`, `BCSiliconRecipes.java:160-161`: REDSTONE + QUARTZ, 60,000 MJ.
- Diamond `chipset_diamond`: `BCSiliconItems.java:55`, `BCSiliconRecipes.java:166-167`: REDSTONE + DIAMOND, 80,000 MJ.
- Redstone crystal `redstone_crystal` (`BCSiliconItems.java:60`): REDSTONE_BLOCK x1, 256,000 MJ (`BCSiliconRecipes.java:171-173`).

Note: `BCSiliconRecipes.java:138-174` has no crafting-table counterpart, so every chipset is assembly-only. `AssemblyRecipeBasic` requires the stack count per requirement, not summed (see adjacent issues).

## 5. Powering the assembly table

Assembly table (`TileAssemblyTable.java`, base `TileLaserTableBase.java`):
- Inputs: `inv` = `ItemHandlerSimple` of 12 slots (3 cols x 4 rows, `:50-62`), INSERT-only from pipes.
- Power field: `TileLaserTableBase.java:40` `public long power;` (micro-MJ). `getTarget()` (`TileAssemblyTable.java:179-183`) = `getRequiredMicroJoulesFor(active output)` or 0.
- `getRequiredLaserPower()` = `getTarget() - power` (`TileLaserTableBase.java:71-73`). `receiveLaserPower(long)` (`:76-81`) adds `min(microJoules, required)`.
- Crafting fires in one step when `power >= getTarget()` (`TileAssemblyTable.java:212`): inputs removed (`:215`), output added via `InventoryUtil.addToBestAcceptor` (`:216`), `power -= target` (`:217`). Target must be > 0 for any progress (`:203`).
- Recipe activation happens every server tick in `updateRecipes` (`:74-130`).
- Laser-table block implements `ILaserTargetBlock`: `silicon/block/BlockLaserTable.java:44`. Tile implements `ILaserTarget` (`TileLaserTableBase.java:36`).

Laser (`TileLaser.java`):
- Battery: `battery = new MjBattery(1024 * MjAPI.MJ)` (`:77`). Max output per tick: `getMaxPowerPerTick()` = `4 * MjAPI.MJ` (`:233-235`).
- Transfer per tick (`serverTick`, around :289-310): `max = min(battery.rampedExtractLimit(4 MJ), target.getRequiredLaserPower())`, then `receiveLaserPower`. Battery empty means no target (`:290`).
- Ramp: `MjBattery.rampedExtractLimit` (`api/mj/MjBattery.java:124-136`) scales linearly below half capacity (512 MJ), so a half-empty laser delivers less than 4 MJ/tick.
- Range: `TARGETING_RANGE = 6` (`:57`, a square cone of 6 blocks forward, `Cone.SQUARE` via `VolumeUtil.iterateCone`). Box mode (`laserTargetingBehavior` config) uses `BOX_RADIUS = 5` (`:59`) with no line of sight.
- Line of sight: `VolumeUtil.java:28-47` (`iterateVolume`) marks a cell visible only if every intermediate cell is see-through; `:49-51` `isSeeThrough` = air or a `LiquidBlock` (so water and lava do not block). Any other block blocks the beam.
- Target candidates: block must be `ILaserTargetBlock` and the tile `ILaserTarget` (`TileLaser.java:134-152`, `:197-204`).
- Power IN: `MjCapabilities.registerMjConsumer(registrar, LASER, laser -> laser.getMjReceiver(), ...)` at `BCSilicon.java:69-70`. The laser's receiver is `lib/mj/MjBatteryReceiver.java:18` (implements `IMjReceiver`, `IMjReadable`, NOT `IMjRedstoneReceiver`). Its `getPowerRequested` = capacity - stored (`:30-33`), `canConnect` always true (`:25-28`).
- Debug/sync: a `|| true` at `TileLaser.java:317` forces a sync every tick.

Engines (base `lib/engine/TileEngineBase_BC8.java`):
- Output side: `getReceiverToPower(Direction)` (`:417-440`) looks at the block one step out of `orientation`, via `TransferAccess.find(MjAPI.CAP_RECEIVER, level, pos, side.getOpposite())`. Then BOTH `receiver.canConnect(getMjConnector())` AND `getMjConnector().canConnect(receiver)` must hold (`:421`).
- Connector rules: `lib/engine/EngineConnector.java:17-33`. `EngineConnector(true)` (redstone-only) accepts only `IMjRedstoneReceiver` (`:28-29`). `EngineConnector(false)` accepts any `IMjReceiver` with `canReceive()` (`:25-31`).
- Transmission gate: `serverTick` (`:468`) sends constant power only if `isRedstonePowered && isActive()` (`:575`). An engine needs a redstone signal to output at all. `sendPower` (`:449-461`) sends `extractPower(0, receiver.getPowerRequested())` and stores what the receiver accepted.
- Engine chaining: default `getMaxChainLength()` = 2 (`:186-188`), so up to two same-type engines facing the same way may sit between the engine and its receiver (`walkChain`, `:389-411`). Redstone engine overrides it to 0 (`core/tile/TileEngineRedstone_BC8.java:82-84`).
- Overheat: an overheated engine keeps sending into non-redstone receivers and bleeds 1 MJ/tick (`TileEngineBase_BC8.java` serverTick, around :500-530).

Per-engine numbers:
- Redstone Engine (`core/tile/TileEngineRedstone_BC8.java`): connector `EngineConnector(true)` (:34-36) = redstone-only. `getMaxPower()` = 1 MJ (:87-89). Constant generation: while powered, `power = getMaxPower()` every tick (:49-56). `maxPowerReceived` 4 MJ (:97-99), `maxPowerExtracted` 4 MJ (:102-104), `getCurrentOutput` 0.05 MJ (:112-114). Fuel: none.
  - Consequence (code-verified): the redstone engine CANNOT drive a laser. The laser's receiver is not `IMjRedstoneReceiver` (MjBatteryReceiver.java:18), so `EngineConnector(true).canConnect` fails (EngineConnector.java:28-29). Same for the wooden kinesis pipe: `pipe_wood_power` uses `PipeBehaviourWoodPower` (BCTransportPipes.java:133), which extends `PipeBehaviour` (`PipeBehaviourWoodPower.java:18`), whose `getCapability` returns null (`api/transport/pipe/PipeBehaviour.java:82-84`). The power flow's `Section` implements only `IMjReceiver` (`pipe/flow/PipeFlowPower.java`, class `Section`; no redstone interface in `transport/pipe/flow/`). So the redstone engine cannot push into a kinesis pipe either.
  - Which redstone-engine targets do work: `pipe_wood_item` (`PipeBehaviourWood` implements `IMjRedstoneReceiver`, `PipeBehaviourWood.java:31`, `getCapability` returns itself for CAP_RECEIVER at :123-129), pulsed receivers such as `TilePump`'s `MjRedstoneBatteryReceiver` (`factory/tile/TilePump.java:202`), Stripes/Obsidian pipes, the Pulsar plug.
- Stirling Engine (`engine_stone`, `energy/tile/TileEngineStone_BC8.java`): connector `EngineConnector(false)` (:187-189). Output per tick while burning: PID clamp `[MIN_OUTPUT, MAX_OUTPUT]` = `[1/3 MJ, 1 MJ]` (:59-61; `getCurrentOutput` :290-295). `getMaxPower()` = 1000 MJ (:280-282), `maxPowerExtracted` = 100 MJ (:275-277), `maxPowerReceived` 200 MJ (:270-272). Burn: `addPower(output)` each tick while `burnTime > 0` (:197-205). New fuel only when redstone powered (:208). Fuel: any item with vanilla furnace burn time, resolved by `Platform.extensions().burnTime(stack, level)` on 1.21.1 (`:150-158`); lava bucket leaves an empty bucket (:215). Fuel slot is `fuelItemHandler` (`:77-90`), `isValidFuel` (:137-146).
  - Output is PID-regulated toward 3/8 of max stored. With the laser draining, the buffer stays low, so output sits near MAX_OUTPUT (1 MJ/tick). UNVERIFIED at runtime.
- Combustion Engine (`engine_iron`, `energy/tile/TileEngineIron_BC8.java`): connector `EngineConnector(false)` (:117-119). `getMaxPower()` = 10,000 MJ (:285-287), `maxPowerExtracted` = 500 MJ (:295-297), `maxPowerReceived` = 2,000 MJ (:290-292). Burn: each tick while burning, `addPower(currentFuel.getPowerPerCycle())` (`:137-186`, the addPower at :184). Fuel: liquid fuels from `BuildcraftFuelRegistry.fuel` (fuel_gaseous, fuel_light, fuel_dense, fuel_mixed_light, fuel_mixed_heavy, oil_distilled clean; oil_dense, oil_heavy, oil dirty with residue), registered in `energy/BCEnergyRecipes.java:81-93` and `addFuel` `:218-233`. Power per tick: cool tier = multiplier MJ (gaseous 8, light 6, dense 4, mixed-light 3, mixed-heavy 5, distilled oil 8); hot and searing tiers scale by `POWER_QUARTERS_BY_HEAT` (`BCEnergyRecipes.java:28-30`). Coolant: `addCoolant(Fluids.WATER, ...)` and solid coolants (`BCEnergyRecipes.java:52-59`). Stalls for 10 ticks after a redstone drop (`penaltyCooling = 10`, `TileEngineIron_BC8.java:190`). Overheats without coolant (`tip.block.engine_iron`).
  - At 8 MJ/tick the combustion engine's output is above the laser's 4 MJ/tick cap, so the laser is the bottleneck.

Time to one redstone chipset (10,000 MJ), by source, assuming a continuously powered engine and laser (estimates from the numbers above; UNVERIFIED at runtime):
- Stirling at about 1 MJ/tick: about 10,000 ticks (8 min 20 s), about 6.25 coal (1600 ticks each on vanilla fuel values, UNVERIFIED for this node's fuel table).
- Combustion: laser-limited at 4 MJ/tick: about 2,500 ticks (2 min 5 s), needs coolant and fuel; the fuel rate depends on the chosen fluid.
- Redstone engine: 0 (cannot reach the laser; see above).
- Laser battery holds up to 1024 MJ and is drawn at up to 4 MJ/tick; how much a full battery shortens the first chipset is UNVERIFIED.

Engine -> laser routes: an engine must output into the laser at `orientation` (the laser must sit on the output face, or on the far end of a kinesis pipe that is a receiver). A kinesis pipe is a receiver when its power transfer info has `recv=true`: `pipe_wood_power` (`BCTransportConfig.java:97`, `rate*4` MJ/tick with `rate = basePowerRate`, default 4 per the config comment at `:77`, so 16 MJ/tick; `128` resistance divisor). The Stirling or Combustion can feed the laser directly or through `pipe_wood_power`. The Redstone engine cannot use either route.

## 6. platform layer, mixins, access transformers (NeoForge)

- Platform layer: `docs/platform-layer.md` (450 lines; read to line 142 plus the mixin and Fabric sections). Binder: `src/main/java/buildcraft/platform/Platform.java` (static accessor per service). Contract rule (`platform-layer.md`, "Contract rule"): files outside `platform/<loader>/` name only JDK, vanilla, `buildcraft.platform`, `buildcraft.api.fluids` and `buildcraft.api.capability`.
- NeoForge implementation: `src/main/java/buildcraft/platform/neoforge/` (41 entries, e.g. `BCNeoForgeEntrypoint.java`, `NeoForgeLifecycleHooks.java`, `NeoForgeMenus.java`, `NeoForgeTransfer.java`). Each file's body sits in `//? if neoforge {`.
- Patched-vanilla overrides carry `@LoaderHook` (`src/main/java/buildcraft/platform/LoaderHook.java:31`).
- Directives: Stonecutter constants `neoforge`, `fabric`, `forge` (root `build.gradle.kts:57`). Never a bare `else` after a loader arm (guard `StonecutterDirectiveGuardTester`). Stonecutter 0.9.8 reads `&&`/`||` left to right (see `platform-layer.md`, "Rules for directives").
- Access transformer (NeoForge): `src/main/resources/META-INF/accesstransformer.cfg` (59 lines, one `public` line per member; comments name the reason, e.g. `ShapedRecipe.result`, `ShapelessRecipe.ingredients`, `ItemCooldowns.cooldowns`). Consumed by `bc.neoforge.gradle.kts:139`: `accessTransformers.from(rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"))`. The same file is handed to ForgeGradle's mavenizer (its header says so; `docs/platform-layer.md` agrees on the Forge side).
- NO mixins on NeoForge. There is no NeoForge mixins json in `src/main/resources/`. Mixins exist only on the other loaders: Fabric `src/main/resources-fabric/buildcraftunofficial.mixins.json` (+ `buildcraftunofficial.classtweaker`, a translation of the AT, and `fabric.mod.json`), Forge `src/main/resources-forge/buildcraftunofficial.forge.mixins.json` (package `buildcraft.platform.forge.mixin`), Fabric mixin classes under `src/main/java/buildcraft/platform/fabric/mixin/` (68 `@Mixin` files repo-wide, most of them Fabric).
- mods.toml: `src/main/resources/META-INF/neoforge.mods.toml`, expanded by `processResources` (`bc.neoforge.gradle.kts`, `tasks.processResources` block), `${mod_version}` and `${neo_dep_range}`. The `neo_dep_range` comment (in that toml) names `[21.1.151,...)` for 1.21.1 (IBCMenuProvider needs the backported `writeClientSideData`).
- MegaBlockBot mirror, for reference: its `build.gradle.kts` has the same `neoForge {}` (`:46`), `processResources` expansion (`:66`), `register(modId)` (`:60`), and no AT or mixins. MegaBlockBot's `constants.match` (`build.gradle.kts:22`) lacks the `forge` constant (BuildCraft's has it, `:57`).

Doc vs code:
- `docs/platform-layer.md:36` says `constants.match(loader, "neoforge", "fabric")`. The code (`build.gradle.kts:57`) has three constants (`"forge"` added). The doc is stale on this line. Code wins.

## Adjacent issues

1. Stale comment: `src/main/java/buildcraft/lib/client/guide/parts/recipe/GuideAssemblyRecipes.java:24` says the class is "Currently stubbed — requires AssemblyRecipeRegistry which is not yet ported", but lines 31, 51, 69 and 133 read `AssemblyRecipeRegistry.REGISTRY` directly.
2. Split-stack bug (code-verified, not run): `AssemblyRecipeBasic.getOutputs` (`api/recipes/AssemblyRecipeBasic.java:41-46`) requires ONE slot with `count >= definition.count`. `TileLaserTableBase.planExtraction` (`TileLaserTableBase.java:164-188`) SUMS across slots. `TileAssemblyTable.updateRecipes` (`:77`) lists a recipe only via `getOutputs`, so a count-6 tank recipe (`BCSiliconRecipes.java:286-289`) whose glass is split 3+3 is never listed and never runs. Count > 1 among the current assembly recipes: the tank (6 glass, `BCSiliconRecipes.java:286-289`) and the pulsar plug (2 iron ingots, `:76`). M5 chain recipes (chipsets) all use count 1.
3. `TileLaserTableBase.getRequiredLaserPower()` (`TileLaserTableBase.java:71-73`) returns `target - power`, which is negative when no recipe is active and power is held. `receiveLaserPower` (`:76-81`) would then REDUCE power and return more than it was given. The laser guards with `> 0` before sending (`TileLaser.java:197-204`), so this is latent, not live.
4. Per-tick cost: `TileAssemblyTable.updateRecipes` iterates all of `REGISTRY` every server tick (`:74-130`) calling `getOutputs`, `getInputsFor` and `extract(simulate)` per instruction, plus `recipesStates.hashCode()` every tick (`:189-190`). Registry size: the loops in `BCSiliconRecipes.java` produce about 85 fixed entries (lens 34, wire 16, gate 24 incl. modifiers, plugs 3, chipsets 6, copier 1, tank 1) plus `FacadeAssemblyRecipes.INSTANCE` (count UNVERIFIED). Relevant to M5's deep chains.
5. Always-sync: `TileLaser.java:317` `|| true` sends an update to tracking players every tick.
6. Weak test: `src/test/java/buildcraft/energy/EngineTester.java:185-187`, `testStirlingEngineFuel` (:155), has an empty `if` body where its only fuel-consumption assertion should be. It also runs on a fixed `runAfterDelay(2, ...)` after placing blocks, which the add-game-test skill forbids.
7. Duplicates in grep: `.claude/worktrees/*/` holds full copies of `AssemblyRecipeRegistry.java`, `AssemblyRecipe.java`, and the silicon recipe files. Exclude `.claude/` from any grep.
8. Idempotency: `BCSiliconRecipes.ensureInitialized` sets `initialized = true` (`:58`) before registering, so an exception mid-registration leaves a partial registry and no retry (`:54-67`).
9. Engine naming: `engine_stone` (Stirling) and `engine_iron` (Combustion) are swapped relative to intuition (`BCEnergyBlocks.java:24,28`). Any adapter keyed on English names will be wrong.
10. Assembly table output placement: `InventoryUtil.addToBestAcceptor` (`lib/misc/InventoryUtil.java:45-48`) tries injectable neighbours, then inventories, then presumably drops in world. The tail of that method was not read (UNVERIFIED).
11. `AssemblyRecipe.setRegistryName(Object)` (`AssemblyRecipe.java:34-37`) is a silent no-op stub. A caller passing a non-String loses the name with no error.

## Open questions (not answerable from the repo)

1. Compile dependency: BuildCraft publishes no Maven artifact. How should MegaBlockBot compile against BuildCraft's jar (a `files(...)` path to a built `BuildCraftRedux` jar, or add publishing to BuildCraft)?
2. Design choice for the laser source: the redstone engine cannot power a laser. Stirling (fuel item, redstone-gated) or Combustion (fluid fuel plus coolant, overheats) must be chosen. The design doc does not pick one. Which should the M5 "power a station" strategy assume?
3. Whether the `c:` tags used by the recipes (`#c:rods/wooden`, `#c:strings`, `#c:glass_panes/colorless`, `#c:dusts/redstone`, ...) have members in 1.21.1 NeoForge 21.1.265 (keys exist in `Tags.java`; membership not checked).
4. Runtime confirmation of the redstone engine's "no laser" result and of the Stirling/Combustion chipset times. Both are from code reading only.
5. Whether the MDG run reads `run/mods` for jars dropped in without any extra config (evidence is file presence and McDevBridge's instructions, not a launched run).
7. The `FacadeAssemblyRecipes.INSTANCE` output count (affects the M0 lint scope).
