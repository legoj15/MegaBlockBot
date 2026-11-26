# solver-core: facts for the MegaBlockBot implementer

Collected 2026-11-26 by a read-only scout. Nothing was built or run. Paths are under
`E:/GitHub/SprawlCrafting/` unless prefixed `MBB:` (`E:/GitHub/MegaBlockBot/`). `solver-core/...`
means `E:/GitHub/SprawlCrafting/solver-core/...`. Line numbers are from the files as they stand on disk.

The last recorded JUnit run is `solver-core/build/test-results/test/*.xml`, dated 2026-11-23. The solver
sources were last changed 2026-11-21, so that run postdates them. It is read from XML, not re-run (UNVERIFIED as
a current result).

Design sections read: `MBB:AI-PLAYER-DESIGN.md` 4.1 (lines 430-471) and 4.2 (lines 472-494).

---

## 1. Public API

Package `com.legoj15.solver`, directory `solver-core/src/main/java/com/legoj15/solver/`. Java 8 bytecode, no Minecraft imports.

### RecipeGraphSolver.java

- `public final class RecipeGraphSolver<R, K>` (:84). R = recipe id type, K = item identity type.
- `public interface RecipeInfo<R, K>` (:87)
  - `R id();` (:88)
  - `List<List<K>> ingredientSlots();` (:95). Empty slots removed. Each slot is its alternatives in preference order.
  - `default List<List<K>> craftableIngredientSlots()` (:110). Default returns `ingredientSlots()`.
  - `K result();` (:114)
  - `int resultCount();` (:116)
- `public interface Result<R, K>` (:120)
  - `public static final class Success<R, K> implements Result<R, K>` (:126)
    - `public Success(List<PlannedStep<R>> steps)` (:134). Layouts become empty lists, meaning "no layout".
    - `public Success(List<PlannedStep<R>> steps, List<List<SlotLayout<K>>> layouts)` (:150). Throws `IllegalArgumentException` on misalignment, run arity mismatch, adjacent equal-item runs, or a crafts-sum mismatch.
    - `public List<PlannedStep<R>> steps()` (:181)
    - `public List<List<SlotLayout<K>>> layouts()` (:191)
  - `public static final class Failure<R, K> implements Result<R, K>` (:201)
    - `public Failure(Set<K> missing, boolean budgetExceeded)` (:206)
    - `public Failure(Set<K> missing, boolean budgetExceeded, boolean tooLarge)` (:214)
    - `public Set<K> missing()` (:220); `public boolean budgetExceeded()` (:224); `public boolean tooLarge()` (:229)
- `public static final class ShortfallResult<K>` (:252)
  - `public ShortfallResult(Map<K, Long> demandsLong, Map<K, List<K>> alternatives, boolean approximate, boolean saturated)` (:259)
  - `public ShortfallResult(Map<K, Integer> demands, Map<K, List<K>> alternatives, boolean approximate)` (:270). Legacy form, never saturated.
  - `public Map<K, Integer> demands()` (:281). Clamped to Integer.MAX_VALUE.
  - `public Map<K, Long> demandsLong()` (:285)
  - `public Map<K, List<K>> alternatives()` (:289)
  - `public boolean approximate()` (:293); `public boolean saturated()` (:297)
- Constructors
  - `public RecipeGraphSolver(Function<K, List<? extends RecipeInfo<R, K>>> producers, UnaryOperator<K> remainder, int maxDepth, int maxAttempts)` (:323). Uses `DEFAULT_MAX_STEPS`.
  - `public RecipeGraphSolver(Function<K, List<? extends RecipeInfo<R, K>>> producers, UnaryOperator<K> remainder, int maxDepth, int maxAttempts, int maxSteps)` (:335)
- `public static final int DEFAULT_MAX_STEPS = 16384;` (:348)
- Methods
  - `public Result<R, K> solve(RecipeInfo<R, K> target, Map<K, Integer> inventory)` (:358). Int stock; `Integer.MAX_VALUE` means unbounded.
  - `public Result<R, K> solveIntSized(RecipeInfo<R, K> target, Map<K, Integer> inventory)` (:369). Steps capped at Integer.MAX_VALUE crafts.
  - `public Result<R, K> solveLong(RecipeInfo<R, K> target, Map<K, Long> inventory)` (:379). `Long.MAX_VALUE` means unbounded.
  - `public Result<R, K> solveIntSizedLong(RecipeInfo<R, K> target, Map<K, Long> inventory)` (:389)
  - `public boolean isSolvable(RecipeInfo<R, K> target, Map<K, Integer> inventory)` (:819)
  - `public boolean isSolvableLong(RecipeInfo<R, K> target, Map<K, Long> inventory)` (:827)
  - `public ShortfallResult<K> shortfall(RecipeInfo<R, K> target, Map<K, Integer> inventory)` (:835)
  - `public ShortfallResult<K> shortfallLong(RecipeInfo<R, K> target, Map<K, Long> inventory)` (:861)
- Inventory maps are copied. Null and non-positive entries are dropped (`toLong` :1718, `sanitizeLong` :1734).
- Package-private helper `static long stockCount(int count)` (:1714) maps Integer.MAX_VALUE to Long.MAX_VALUE.

### PlannedStep.java

- `public final class PlannedStep<R>` (:16)
- `public PlannedStep(R recipeId, long crafts)` (:21). Throws if crafts < 1.
- `public R recipeId()` (:29); `public long crafts()` (:33); `public PlannedStep<R> withCrafts(long newCrafts)` (:37); equals, hashCode, toString.

### SlotLayout.java

- `public final class SlotLayout<K>` (:23)
- `public SlotLayout(long crafts, List<K> items)` (:32). Throws if crafts < 1. `items` = one item per non-empty slot, in slot order.
- `public long crafts()` (:47); `public List<K> items()` (:52); equals, hashCode, toString.

### SlotAssignment.java

- `public final class SlotAssignment` (:33), private constructor.
- `public static <K> List<K> assign(List<? extends List<K>> slots, Map<K, ? extends Number> stock)` (:46). Returns null in a slot that nothing can fill.
- `public static <K> long stableRepeats(List<? extends List<K>> slots, ToLongFunction<K> stock, Map<K, Long> use, Map<K, Long> made, long max)` (:76). Returns how many crafts the same assignment repeats, in [1, max].
- `public static <K> List<K> assignCells(List<? extends List<K>> cells, Map<K, ? extends Number> stock)` (:112). Null cells stay null, so output lines up with the ingredient list.
- Package-private `static <K> Object[] choose(List<? extends List<K>> slots, ToLongFunction<K> stock)` (:129). Fill order: most constrained slot first (see class javadoc :20-22).

### ProducedLedger.java

- `public final class ProducedLedger<K, V>` (:27), implicit public constructor.
- `public void produced(K key, long count)` (:33)
- `public long outstanding(K key)` (:42)
- `public void consumed(K key, long count)` (:47)
- `public long preexisting(V variant)` (:60)
- Package-private `void notePreexisting(Map<V, Integer> refusedHeld)` (:66). Freezes the player's own snapshot at the job's first production.

### LedgerTally.java

- `public final class LedgerTally<K, V>` (:40), implicit public constructor.
- `public void held(K key, V variant, int count)` (:54). Call before `open`.
- `public void open(ProducedLedger<K, V> ledger)` (:66)
- `public boolean admits(K key, V variant)` (:77)
- `public long variantHeadroom(V variant)` (:94)
- `public long identityHeadroom(K key)` (:106)
- `public void picked(K key, V variant, boolean refused)` (:121)
- `public void picked(K key, V variant, boolean refused, int count)` (:134)
- `public void chargeTo(ProducedLedger<K, V> ledger)` (:146)

### DepthFirstSchedule.java

- `public final class DepthFirstSchedule<K>` (:36)
- `public static final long MAX_CAP = Integer.MAX_VALUE;` (:39)
- `public DepthFirstSchedule(long[] crafts, List<List<SlotLayout<K>>> layouts, List<K> results, int[] resultCounts, Map<K, Long> held)` (:85). Throws on null or empty `crafts`, and on misaligned lists.
- `public int current()` (:158); `public long batch()` (:169); `public boolean isFinalBatch()` (:178); `public void performed(long n)` (:199); `public boolean noRoom()` (:246); `public boolean finished()` (:281); `public long done(int i)` (:286); `public long totalDone()` (:291); `public long stock(K item)` (:296); `public long cap()` (:302)
- Only consumer: `E:/GitHub/SprawlCrafting/src/main/java/com/legoj15/sprawlcrafting/craft/CraftJob.java:170`.

### PlanSimulator.java

- `public final class PlanSimulator` (:24), private constructor.
- `public static final class Replay<R, K>` (:30). Methods: `public boolean ok()` (:42), `public int failedStep()` (:47), `public R failedRecipe()` (:52), `public K missing()` (:60).
- `public static <R, K> Replay<R, K> replay(List<PlannedStep<R>> plan, Function<? super R, ? extends RecipeGraphSolver.RecipeInfo<R, K>> recipes, UnaryOperator<K> remainder, Map<K, Integer> stock)` (:78-81). It takes no layouts. It replays under `SlotAssignment` choice (`run` calls `SlotAssignment.choose`, :172).
- No SprawlCrafting consumer. Used as the test oracle at `solver-core/src/test/java/com/legoj15/solver/RecipeGraphSolverTest.java:902`.

### UndoMap.java

- `final class UndoMap<K, V>` (:17). NOT public. Listed in `docs/solver-library.md` as if public.

### CountFormat.java

- `public final class CountFormat` (:28). Public statics: `isSaturated(long)` (:44), `clampToInt(long)` (:49), `format(long)` (:54), `compact(long)` (:72). Not in the brief's list, but public.

---

## 2. Planning rule as coded (RecipeGraphSolver.java)

**Producers.** `producers.apply(item)` is iterated in list order. In `produce` (:1411-1463), candidates whose `result()` differs from the item, or whose `resultCount() <= 0`, are skipped (also skipped if not in `allowed`, a probe-only filter). The first candidate that crafts runs while it still supplies. The next candidate covers the rest. The game-side producer list is sorted by recipe id (`CraftPlanner.java:596`). Pin: `RecipeGraphSolverTest.java:196` `firstSatisfiableProducerWins`.

**Slots.** `satisfySlot` (:1231-1266) for one slot group:
1. Take stock across `alternatives` in list order (:1240-1249).
2. Craft the rest from `craftableIngredientSlots()` alternatives in list order via `produce` (:1253-1263).

Groups (:1062-1097). The batched pass merges identical (alternatives, craftable) slot lists into one group with a count (merge key :1081-1091). The unitwise pass keeps one group per slot. No cross-slot backtracking in batched or unitwise passes.

**Passes.** `plan()` (:582-629):
1. Batched pass, `Solve(batched=true, creditRemainders=true, exact=false, capInt)`. Accepted only if `replays(steps, held)` (:703-713) passes. That is a count check under each step's own layouts.
2. Unitwise pass (`batched=false`). Same acceptance.
3. Exact pass `craftExact` (:1285-1343). Runs only when pass 1 or 2 was rejected, or contention was flagged (:609). Replays each batch with `PlanSimulator.run` and sub-crafts shortages up to `EXACT_ROUNDS = 4` (:306).
4. If the budget was hit, or a batch was clipped at Integer.MAX_VALUE, `planByQuantity` (:649-679). It runs a probe (:685-695, no recording, long batches), retrying with `skipFutileReasks` if the first probe runs out of budget. Then a guided batched pass restricted to the probe's recipes. `maxSteps` bounds it (:1171-1177), and exceeding it gives `Failure.tooLarge`.

`budgetExceeded` is set only when no pass failed cleanly and at least one ran out of budget (:606, :624).

**Budgets.** Each `Solve` gets `attemptsLeft = maxAttempts` (:991). `visit()` (:1048-1055) decrements it once per craft node. The shortfall walk also gets `expandLeft = maxAttempts` (:993, `expandVisit` :1467-1473). The probe and guided passes refund a kept step (:1181-1183).

**Depth.** `depth > maxDepth` returns 0 in `craft` (:1134), in `craftExact` (:1294), and in `require`, which records an approximate leaf (:1603-1608). Sub-crafts recurse at depth + 1 (:1257, :1386, :1516).

**Cycles.** A `crafting` set is passed down. `produce` returns 0 for an item already in it (:1417). `require` returns the cycle hit to its ancestor (:1580-1581, :1641-1648).

**Batches.** `craft` (:1112-1221) records one step per batch of `want` crafts. With `capInt` and recorded steps, the batch is capped at Integer.MAX_VALUE (:1120). If a group is short, the batch is rolled back and retried at `supplied` (what the short group got), else at 1 when contended, else it fails (:1203-1219).

**Remainders.** After a consuming step is recorded, `remainder.apply(item)` is credited once per consumed item (:1191-1199). Not credited in the shortfall walk (`creditRemainders=false`, :917). Pins: `RecipeGraphSolverTest.java:279` `remainderItemsReturnToInventory`, `:288` `remainderIsNotAvailableWithinTheConsumingRecipe`, `:299` `remainderIsAvailableToStepsAfterTheConsumingCraft`.

**Output shape.** Adjacent steps with the same recipe id are merged (`mergeAdjacent` :407-422). Their layout runs are concatenated (`concatRuns` :425-444). A layout run is `SlotLayout(crafts, items)`, one item per non-empty slot. Built by `layoutRuns` (:453-513) for batched and unitwise passes, and `chunkRuns` (:542-559) for the exact pass.

**Shortfall (empty inventory).** `shortfallLong` (:861-913) calls `walk` (:916-927). `walk` calls `requireSlot(group, group.count)` for each target slot group (:924).
- `requireSlot` (:1492-1551) takes stock from the `virtual` map across alternatives (:1494-1499).
- If any alternative has an empty producer list, that item is the leaf: `require(item, left, alternatives)` (:1503-1507).
- Otherwise it credits what can be crafted from stock (:1512-1519), then calls `require` on each craftable alternative in order (:1524-1543). The first non-looping one wins.
- Last resort: `recordLeaf(alternatives.get(0))` (:1549).
- `require` (:1572-1649) takes stock, then either records a leaf (no producer with positive yield, :1586-1595) or expands the producers in order, crediting overproduction back (:1624-1627).
- So for an empty inventory, `demandsLong` has one key per gathered leaf. That key is the raw alternative, or the first craftable alternative that bottoms out at a raw leaf, or `alternatives.get(0)` in the last-resort case. Counts are raw units, summed across slots and along the producer chain.
- `alternatives()` has the same keys. Each value is the union, in first-seen order, of the full slot alternative lists of every slot that gathered that leaf (`recordLeaf` :1652-1667). It is per leaf, not per slot. Two slots gathering the same leaf merge into one entry.
- Verification (:868-911). If the target is already feasible with stock, the answer is empty (:873-876). Otherwise up to `TOP_UP_ROUNDS = 2` re-walks run over topped-up stock (:881-901). `approximate` is true if verification fails or a budget or depth limit was hit (:906, and the walk flag at :926).
- Pins: `RecipeGraphSolverTest.java:683` `shortfallPrefersRawLeafOverCraftableAlternative`, `:650` `shortfallFallsThroughToCraftableAlternative`, `:661` `shortfallCapturesSlotAlternativesForCycling`, `:612` `shortfallCollapsesMultiLevelChainToRaws`.

**Failure.** `missing` holds representative items from failed branches. Javadoc calls them UX hints (:196-198).

---

## 3. Consuming the library

### 3a. SprawlCrafting today (what a consumer copies)

- `settings.gradle.kts:23-26` (root): `includeBuild("solver-core") { dependencySubstitution { substitute(module("com.legoj15:solver")).using(project(":")) } }`. Comment at :21.
- `solver-core/settings.gradle.kts`: `:8` foojay resolver plugin `version "1.0.0"`; `:11` `rootProject.name = "solver"`.
- `solver-core/build.gradle.kts`
  - `:9` `group = "com.legoj15"`; `:10` `version = property("solver_version") as String`
  - `:16-19` `gradle.parent?.let` moves the build dir to `build/<includer root name>/`
  - `:23` `toolchain { languageVersion = JavaLanguageVersion.of(8) }`
  - `:24` `withSourcesJar()`
  - `:28` `mavenCentral()`; `:32-33` JUnit BOM 5.10.3 and jupiter (test only)
  - `:42` `useJUnitPlatform()`
  - `:51` `"Automatic-Module-Name" to "com.legoj15.solver"`
  - `publishing` artifactId `solver`, MIT, `from(components["java"])`
- `solver-core/gradle.properties:4` `solver_version=2.1.0`. Line 1-3 is the bump rule.
- `build.neoforge.gradle.kts` (the 1.x and 26.x NeoForge node script)
  - `:4` `id("net.neoforged.moddev")`, version pinned in `stonecutter.gradle.kts:17` as `2.0.148`
  - `:62` `val solverVersion = property("solver_version") as String`
  - `:63` `val solverRange = "[$solverVersion,${solverVersion.substringBefore('.').toInt() + 1}.0.0)"` gives `[2.1.0,3.0.0)`
  - `:76-88` repositories, `mavenLocal()` first at :77, `mavenCentral()` at :87
  - `:202` `implementation("com.legoj15:solver:$solverVersion")`
  - `:203-205` `if (minecraftVersion.startsWith("1.")) { "additionalRuntimeClasspath"("com.legoj15:solver:$solverVersion") }`. Comment at :197-201 says MDG rejects this configuration on 26.x.
  - `:206-211` `jarJar("com.legoj15:solver") { version { strictly(solverRange); prefer(solverVersion) } }`
- 1.21.1 NeoForge node pins: `versions/1.21.1-neoforge/gradle.properties`: `minecraft_version=1.21.1`, `java_version=21`, `neoforge_version=21.1.233`, `neoforge_version_range=[21.1.0,21.2)`.
- `jitpack.yml`: `jdk: openjdk21`; install `./gradlew -p solver-core publishToMavenLocal`. The docs claim JitPack serves it as `com.github.legoj15:SprawlCrafting:<tag>`. UNVERIFIED by scout.

### 3b. What MegaBlockBot must add (not in the repo yet)

`MBB:settings.gradle.kts` has no `includeBuild`. `MBB:build.gradle.kts` has no `dependencies {}` block. It applies `net.neoforged.moddev` at line 6 (version pinned at `MBB:stonecutter.gradle.kts:5` as 2.0.147).

Add to `MBB:settings.gradle.kts`, after the `plugins {}` block (SprawlCrafting puts it there too):

    includeBuild("../SprawlCrafting/solver-core") {
        dependencySubstitution {
            substitute(module("com.legoj15:solver")).using(project(":"))
        }
    }

Gradle resolves the path relative to the settings directory. UNVERIFIED for a sibling checkout (the SprawlCrafting builds use a same-root path).

Add to `MBB:build.gradle.kts`:

    val solverVersion = "2.1.0"   // keep equal to SprawlCrafting solver-core/gradle.properties solver_version
    val solverRange = "[$solverVersion,${solverVersion.substringBefore('.').toInt() + 1}.0.0)"

    dependencies {
        implementation("com.legoj15:solver:$solverVersion")
        if (minecraftVersion.startsWith("1.")) {
            "additionalRuntimeClasspath"("com.legoj15:solver:$solverVersion")
        }
        jarJar("com.legoj15:solver") {
            version {
                strictly(solverRange)
                prefer(solverVersion)
            }
        }
    }

Notes:
- The `additionalRuntimeClasspath` gate covers every `1.x` node. MBB's nodes include 1.21.1, 1.21.10 and 1.21.11 (`MBB:settings.gradle.kts`). The 26.x nodes must not get that line. SprawlCrafting's comment at `build.neoforge.gradle.kts:197-201` says MDG rejects it there. UNVERIFIED for MBB's 1.21.10 and 1.21.11.
- The jar must nest the library (`jarJar`), never shade it. `solver-core/build.gradle.kts:47-49` says so. Two copies of `com.legoj15.solver` otherwise split a package.
- Java: the library is Java 8 bytecode, so the MBB 1.21.x nodes (Java 21, `MBB:versions/1.21.1/gradle.properties`) and 26.x nodes (Java 25) both consume it.
- The foojay plugin is already in `MBB:settings.gradle.kts`. It provisions the JDK 8 toolchain the included build needs (`solver-core/build.gradle.kts:23`), if no local JDK 8 is detected.
- Versions: MBB's MDG pin is 2.0.147. SprawlCrafting's is 2.0.148. `jarJar` behaviour on 2.0.147 is UNVERIFIED.
- JitPack is the alternative for release builds. `docs/solver-library.md` warns that JitPack serves a different group, so the nesting coordinates must be mapped. The composite avoids a publish step during development.

---

## 4. Minecraft-to-solver adapter (SprawlCrafting, 1.21.1 arm)

The adapter sits in SprawlCrafting's shared Stonecutter source tree, not in solver-core. K = `net.minecraft.world.item.Item`. R = `net.minecraft.resources.ResourceLocation`. The 1.21.1 code is the `//? else` arm of each `//? if >=1.21.11 {` marker. The `>=1.20.x` arms named below are also live on 1.21.1. Stonecutter resolves these at `stonecutterGenerate` time (`build.neoforge.gradle.kts:272-276`).

### CraftPlanner.java (`src/main/java/com/legoj15/sprawlcrafting/craft/CraftPlanner.java`)

- `:45` `MAX_DEPTH = 32`; `:47` `MAX_ATTEMPTS = 4096`. Passed at `:227`. `maxSteps` is left at the default (16384).
- `:80-111` `plan(ServerPlayer, RecipeHolder<?>, GridContext, ...)`. `:102-104` cheap reject through `McRecipe.of`. `:109` reads `player.level().getServer().getRecipeManager()`, `player.registryAccess()`, `player.getInventory()`.
- `:147-171` `session(RecipeManager, RegistryAccess, Inventory, GridContext, ...)` overloads. Builds the Session from `ProducerIndex.forGrid` and `snapshotInventory`.
- `:183-185` `invalidateProducerIndex(RecipeManager)`. The 1.21.1 client must call it on reload. The server replaces its RecipeManager, so it self-invalidates.
- `:195-206` `ProducerIndex`. A `WeakHashMap` keyed by RecipeManager identity, then by `GridContext`, then `Map<Item, List<McRecipe>>`.
- `:209-334` `Session`. The solver is built at `:224-227`: `new RecipeGraphSolver<>(item -> producers.getOrDefault(item, List.of()), CraftPlanner::craftingRemainder, MAX_DEPTH, MAX_ATTEMPTS)`.
  - `plan` `:257-285`. Target rejected as `Unsupported` at `:258-261`. `NeedsBiggerGrid` at `:262-264`. `solveLong` at `:266`. Success builds `CraftStep`s with `success.layouts().get(i)` at `:270-276`. Failure: budget gives `TooComplex(failure.tooLarge())` at `:281-283`, otherwise `Unsolvable(missing)` at `:284`.
  - `shortfall` `:293-313`. `shortfallLong` at `:302`. `demandsLong()` at `:305`. Alternatives mapped to registry ids at `:306-308`. Sorted by `toString` at `:311`.
  - `classify` `:248-255`. DIRECT if the plan has one step, else DEFERRED.
- `:360-392` `snapshotInventory`. Loops over `Inventory.INVENTORY_SIZE` main slots (`:362-368`), keeps `CraftExecutor.usableAsIngredient(stack, includeProtected)` (`:366`), and merges `stack.getItem()` with `(long) stack.getCount()` (`:367`). Then the open grid (`:369`, `addOpenGridToPool` `:398`), then station counts (`:370-375`). Counts are `long` with saturating add.
- `:585-599` `indexCraftingRecipes`. `RecipeIds.craftingRecipes(recipes)` at `:589`. Keeps `McRecipe.of` non-null and `info.fits(grid)` (`:590-591`). Groups by `info.result()` (`:592`). Sorts each list by id (`:595-597`).
- `:601-608` `craftingRemainder(Item)`. 1.21.1 arm: `item.hasCraftingRemainingItem() ? item.getCraftingRemainingItem() : null` (`:606`). Item-level only.
- `:614-623` `canCraftInDimensions(CraftingRecipe, int, int)`. 1.21.1 arm: `recipe.canCraftInDimensions(w, h)` (`:621`).
- `:631-640` `static List<Item> slotAlternatives(Ingredient)`. 1.21.1 arm (`:635-638`): `Arrays.stream(ingredient.getItems()).map(ItemStack::getItem).distinct().toList()`. Order is the Ingredient's own order, used as preference. Identity only; NBT and components are ignored.
- `:643-682` `private record McRecipe(ResourceLocation id, List<List<Item>> ingredientSlots, Item result, int resultCount, ItemStack resultStack, boolean fits2x2, boolean fits3x3) implements RecipeGraphSolver.RecipeInfo<ResourceLocation, Item>`.
  - `fits(GridContext)` `:648-650`: INVENTORY uses fits2x2, otherwise fits3x3.
  - `of(RecipeHolder<?>, RegistryAccess)` `:652-681`. Rejects non-`CraftingRecipe` and `isSpecial()` (`:653`). Result from `RecipeIds.resultOf` (`:656`). Ingredients from `recipe.getIngredients()` (`:660`). Skips empty ingredients (`:667-668`, blank shaped cells). Returns null if an ingredient resolves to no items (`:670-672`) or there are no slots (`:676-678`). Sets fits2x2 and fits3x3 from `canCraftInDimensions(recipe, 2, 2)` and `(3, 3)` (`:680`).
  - Does not override `craftableIngredientSlots()`. The default applies: every alternative is craftable.
- 2x2 vs 3x3 gating, three places: (a) `indexCraftingRecipes` `:590-591` keeps only producers that fit the grid, so a 3x3-only recipe is not a producer on the INVENTORY grid. (b) `plan` `:262` returns NeedsBiggerGrid if the target does not fit. (c) `:273` sets each step's needs-table flag to `!fits2x2(step)`.
- Stonecutter markers in this file: `:529-535` (ingredients in `protectedItemsUsed`, 1.21.1 arm `:533-534`), `:602-607`, `:615-622`, `:632-639`, `:657-661`.

### RecipeIds.java (`src/main/java/com/legoj15/sprawlcrafting/craft/RecipeIds.java`)

- `id(RecipeHolder<?>)` `:27-32`. 1.21.1 returns `holder.id()` (`:31`).
- `byId(RecipeManager, ResourceLocation)` `:36-44`. Live arm `>=1.20.2` at `:39-40`: `recipes.byKey(id)`.
- `craftingRecipes(RecipeManager)` `:48-70`. Live arm `>=1.20.2` at `:61-62`: `recipes.getAllRecipesFor(RecipeType.CRAFTING)`.
- `resultOf(Recipe<?>, RegistryAccess)` `:80-102`. Live `else` at `:100-101`: `recipe.getResultItem(registries)`. This is the display result, not `assemble`. Its javadoc (`:72-79`) explains why.

### GridContext.java (`src/main/java/com/legoj15/sprawlcrafting/craft/GridContext.java`)

- `:11-15` enum `INVENTORY(2, 2)`, `CRAFTING_TABLE(3, 3)`.
- `:43-48` `current(ServerPlayer)`: station first, then the open menu's grid size.

### Executor side (consumes the plan; the implementer needs this contract)

- `CraftExecutor.java:1471-1477` `usableAsIngredient(ItemStack)`. `>=1.20.5` live arm at `:1473`. `:1486-1488` is the protected-tier overload.
- `CraftExecutor.java:207-217` remainder crediting. 1.21.1 arm `:213-216`: `new ItemStack(item.getCraftingRemainingItem())`. Same item-level rule as the adapter.
- `CraftExecutor.java:645` `ingredient.test(stack)` is the per-slot check on execution. Planning ignores NBT, so this is the only NBT check.
- `CraftExecutor.java:440`, `:558` call `CraftPlanner.slotAlternatives`.
- `CraftJob.java:154-170` builds the layout lists from `CraftStep` and constructs `new DepthFirstSchedule<>(totals, layouts, results, resultCounts, held)` at `:170`.

### Test coverage of the adapter

- No unit test under `src/test` references `CraftPlanner`, `McRecipe`, or `RecipeGraphSolver`. `grep` of `src/test/java` finds no such reference.
- The only game tests touching the recipe side are `src/test/java/com/legoj15/sprawlcrafting/gametest/CraftGameScenarios.java:35` (every crafting result resolves) and `:54` (chest resolves to chest). They cover `RecipeIds` only. Run by `runGameTestServer` (task named in the comment at `build.neoforge.gradle.kts:126-127`).

---

## 5. JUnit suite (solver-core)

### Commands

- Test: `./gradlew -p solver-core test`. Run from `E:/GitHub/SprawlCrafting`. `solver-core/` has no wrapper of its own; `-p` uses the root wrapper.
- Full check: `./gradlew -p solver-core build`. Local publish: `./gradlew -p solver-core build publishToMavenLocal` (per `docs/solver-library.md`, "Build, test, publish locally").
- Release gate: root task `fullTestSuite` depends on `gradle.includedBuild("solver-core").task(":check")` (`stonecutter.gradle.kts:262-266`).
- Needs a JDK 8 toolchain (`solver-core/build.gradle.kts:23`). JDK 8 directories exist under `C:/Program Files/Java` (jdk1.8.0_261, jdk1.8.0_231). Whether Gradle picks one is UNVERIFIED.

### Classes and counts

| Class | @Test count | Lines |
|---|---|---|
| `RecipeGraphSolverTest` | 68 | 1383 |
| `DepthFirstScheduleTest` | 9 | 435 |
| `ProducedLedgerTest` | 9 | 171 |
| `SlotAssignmentRepeatTest` | 1 | 90 |

Total 87. Last recorded XML: 87 tests, 0 failures, 0 errors (`solver-core/build/test-results/test/`, 2026-11-23). `docs/solver-library.md` names only `RecipeGraphSolverTest` and `ProducedLedgerTest`. That is stale.

### How tests build recipes (RecipeGraphSolverTest.java)

- `:26-100` `private static final class TestRecipe implements RecipeInfo<String, String>`.
- `TestRecipe.of(String id, String result, int resultCount, String... slots)` (`:67`). Each slot string is split on `|` into alternatives in preference order. Craftable = all slots.
- `TestRecipe.ofInventoryOnly(String id, String result, int resultCount, List<String> inventoryOnly, String... slots)` (`:78`). Removes `inventoryOnly` items from the craftable subset.
- `solver()` (`:102-103`) returns `solver(32, 4096)`. The producer is `item -> recipes.stream().filter(r -> r.result().equals(item))` in insertion order. Remainders are `remainders::get` over a `Map<String, String>`.
- Helpers: `inv(Object...)` (Integer stock), `invLong(Object...)`, `solveOk(target, inventory)` (asserts Success).
- Tests also call `solver(...)` with `32, 4096, 64` (`:1350`, `:1377`) for the step cap.

### Tests that pin current default behaviour

- Producer order: `firstSatisfiableProducerWins` (:196).
- Stock before sub-crafts: `stockIsUsedBeforeSubCrafting` (:153), `stockedAlternativeBeatsCraftablePreferredOne` (:186), `slotAlternativesFallBackToCraftableOption` (:176), `inventoryOnlyAlternativeIsNotSubCraftedEvenWhenItHasAProducer` (:211), `inventoryOnlyAlternativeIsStillUsedFromStock` (:223).
- Remainders: `remainderItemsReturnToInventory` (:279), `remainderIsNotAvailableWithinTheConsumingRecipe` (:288), `remainderIsAvailableToStepsAfterTheConsumingCraft` (:299).
- Cycles and depth: `cyclicRecipesDoNotLoopForever` (:258), `compressionCycleStillUsableWhenStockExists` (:268), `depthLimitFailsGracefully` (:338), `depthBoundarySucceedsAtLimitAndFailsOnDepthNotBudgetBeyondIt` (:445).
- Budget: `hostileBranchingGraphIsCutOffByTheAttemptBudget` (:310), `sameSolvableGraphFailsWithBudgetExceededWhenAttemptsRunOut` (:433), `budgetExceededFlagIsFalseForAPlainShortfall` (:423).
- Merging: `consecutiveIdenticalCraftsAreMerged` (:349), `nonAdjacentIdenticalRecipesAreNotMerged` (:387), `resultCountSurplusCarriesAcrossSlots` (:357), `resultCountSurplusCarriesAcrossStepsNotJustSlots` (:406).
- Shortfall: `shortfallPrefersStockedAlternative` (:641), `shortfallFallsThroughToCraftableAlternative` (:650), `shortfallPrefersRawLeafOverCraftableAlternative` (:683), `shortfallCapturesSlotAlternativesForCycling` (:661), `shortfallCreditsOverproductionAcrossSteps` (:630), `shortfallIgnoresCraftingRemainders` (:718), `shortfallOfCoalTagSlotNeedsExactlyOne` (:739), `shortfallBreaksCompressionCycleAndTerminates` (:692), `shortfallExpandsTwoCycleMemberThroughItsRecipeFromRaws` (:1192).
- Saturation: `saturatedStockCountIsUnbounded` (:503), `finiteStockAboveAnIntIsNotUnbounded` (:513), `shortfallBeyondLongRangeSaturates` (:538).
- Random graphs: `randomGraphsOnlyYieldExecutablePlans` (:1074). Fixed seed. Every plan must replay.
- Quantity path: `quantityPathDoesNotCallAnAssignmentConflictAShortfall` (:1296), `intSizedPlanLongerThanTheStepCapIsReportedTooLarge` (:1369), `quantityPlanLengthDoesNotGrowWithQuantity` (:1342).
- Layout contract: `batchedReplayMatchesCraftByCraft` (:974), `subCraftLeavesTheOnlyCopyALaterSlotNeeds` (:1172), `earlierSlotDoesNotEatTheSubCraftALaterSlotNeeds` (:934), `subCraftDoesNotEatStockALaterStepCountedOn` (:921).
- `DepthFirstScheduleTest.java`: `aCompressionChainOnARoomyStorageCompletesEveryTotalInIntSizedBatches` (:201), `aChainOnATightStorageCompletesWithoutFalseFull` (:229), `aRefusedBranchFallsBackToABranchThatDrainsTheStorage` (:349).
- `ProducedLedgerTest.java`: `admitsUpToTheProducedCountAcrossClaimsAndConsumption` (:19), `producedCountSaturatesInsteadOfWrapping` (:48).
- `SlotAssignmentRepeatTest.java:26` `stableRepeatsNeverOutrunsThePerCraftAssignment`.

---

## Adjacent issues (verified by reading; none run)

1. **Null remainder NPE, contradicting the javadoc.** `RecipeGraphSolver.java:315-316` says the remainder may be null. `craft` calls `remainder.apply(...)` with no null check (:1193). The other two sites do check (:734, and `PlanSimulator.java:198`). A null remainder throws on the first consuming craft in a batched, unitwise or exact pass. No test and no SprawlCrafting caller passes null, so nothing catches it.
2. **Pass count understated.** `RecipeGraphSolver.java:319` says `solve` "runs at most three passes". `planByQuantity` (:649-679) adds up to three more runs (two probes and a guided pass). `maxAttempts` bounds each `Solve`, not the total. Worst case is about six runs.
3. **Design section 4.1 planned changes are not in the code.** `MBB:AI-PLAYER-DESIGN.md:437-443` lists `RecipeInfo.station()`, per-slot quantity, byproducts, a pluggable cost function, and memoization. None exist. `RecipeInfo` has only `id`, `ingredientSlots`, `craftableIngredientSlots`, `result`, `resultCount` (:87-117). The constructors take no cost function (:323, :335). The only ordering hook is the producer Function. Design says "planned"; this is a gap, not a contradiction.
4. **Design section 4.1 describes the pre-extraction state.** `MBB:AI-PLAYER-DESIGN.md:465-470` says every SprawlCrafting node adds `solver-core/src/main/java` as a source directory. That is no longer true. The root build uses `includeBuild` (`settings.gradle.kts:23-26`), and `build.neoforge.gradle.kts:56-60` says so. The package rename is done.
5. **`PlanSimulator.replay` is not a layout check.** Its signature takes no layouts (`PlanSimulator.java:78-81`). It replays under `SlotAssignment` choice (:172). A plan the solver accepted under its own layouts (`replays`, `RecipeGraphSolver.java:703-713`) is not checked by it. It is the test oracle (`RecipeGraphSolverTest.java:902`). Any MBB code that validates plans with it checks the wrong contract.
6. **Shortfall alternatives are per leaf, not per slot.** `ShortfallResult.alternatives()` (:289) is keyed by leaf item and unions every slot that gathered it (`recordLeaf` :1652-1667). Design section 4.1 says "per-slot alternatives". Implementers must not expect one list per slot.
7. **`UndoMap` is documented as public.** `docs/solver-library.md` lists it among the library's classes. It is package-private (`UndoMap.java:17`).
8. **`docs/solver-library.md` is stale in places.** It names two test classes, not four. It quotes version 1.0.0 in its "production loading" paragraph, and 1.0.1 in its JitPack paragraph. Current `solver_version` is 2.1.0.
9. **Adapter has no unit coverage.** See section 4, "Test coverage of the adapter". Only `RecipeIds` has game tests. `McRecipe.of`, the 2x2/3x3 gating, and the remainder rule are untested in SprawlCrafting.
10. **Identity-only planning with an item-level remainder.** `CraftPlanner.java:601-608` (and `CraftExecutor.java:213-216`) read only `Item.getCraftingRemainingItem()`. Recipe-specific remainders and NBT-dependent ingredients are not modelled. The design lists identity-only as a known limit (`AI-PLAYER-DESIGN.md:445-448`).
11. **Adapter skips special recipes.** `CraftPlanner.java:653` rejects every `isSpecial()` recipe. That includes all custom crafting recipes, so they never reach the planner.
12. **Adapter reads CRAFTING only.** `RecipeIds.craftingRecipes` (`RecipeIds.java:48-70`) is `RecipeType.CRAFTING`. Smelting, stonecutting and smithing are absent, as `AI-PLAYER-DESIGN.md:478-480` plans.
13. **Unused import.** `CraftPlanner.java:31` imports `RecipeType`. No use found by grep.

---

## Open questions (not answerable from the repo)

1. Does `includeBuild("../SprawlCrafting/solver-core")` resolve from MegaBlockBot's root? Only the same-root form is in use in SprawlCrafting. UNVERIFIED.
2. Does `jarJar` with `strictly`/`prefer` on MDG 2.0.147 behave as on 2.0.148? UNVERIFIED.
3. Do MBB's 1.21.10 and 1.21.11 nodes need the `additionalRuntimeClasspath` line? SprawlCrafting has no such nodes. UNVERIFIED.
4. Release path: composite (sibling checkout) for development, and JitPack or GitHub Packages for releases (design 4.1, `AI-PLAYER-DESIGN.md:450-452`). No decision is recorded in the repo.
5. Is `Ingredient.getItems()` order a stable preference for tag ingredients on 1.21.1? The adapter treats it as one. UNVERIFIED.
6. The design's planned solver changes (section 4.1 table): does MBB need any before SprawlCrafting does? The design says backward-compatible, but no owner or order is recorded.
7. Does the 2026-11-23 test run still reflect the current sources? Re-run with `./gradlew -p solver-core test` before relying on it. Not run by the scout.
8. Does MBB need a smelting, stonecutting or smithing adapter on 1.21.1 for its planned sources (design section 4.2)? Nothing in the SprawlCrafting tree handles those types.
