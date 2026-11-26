# Vanilla 1.21.1 internals (Mojang names) for the acquisition resolver, body and tasks

Scope: design sections 3.1, 4.2, 4.3 of `AI-PLAYER-DESIGN.md`. Sections 4.2 (producer adapters) and 4.3 (resolver) are covered by the loot, recipe, villager and ore parts; section 3.1 (body) by the player-simulation part.

## 0. Source and how to re-read it

- Source of truth: `E:/GitHub/MegaBlockBot/versions/1.21.1/build/moddev/artifacts/neoforge-21.1.265-sources.jar` (NeoForge 21.1.265, MC 1.21.1). It contains 5168 `net/minecraft/**.java` files (Mojang names, NeoForge-patched) and 956 `net/neoforged/**.java` files.
- Version check: `net/minecraft/DetectedVersion.java:31` sets the name to `"1.21.1"`.
- Every path below is relative to the jar root. Line numbers are from the extracted sources and match the jar.
- To read them again, extract into an empty directory with Python zipfile (`python -I`), keeping only `net/minecraft/*` and `net/neoforged/*`. Do not use `unzip` with a wildcard: it extracted only 21 files in our trial.
- Runtime behaviour is UNVERIFIED throughout unless the text says it was checked against a running server.

---

## 1. Loot tables

### 1.1 Containers and their visibility

- `LootTable` `net/minecraft/world/level/storage/loot/LootTable.java:32` (public class).
  - `private final List<LootPool> pools` `:49`. No getter. Public access: `getPool(String name)` `:249`, `addPool(LootPool)` `:267`, `removePool(String)` `:255`.
  - `private final List<LootItemFunction> functions` `:50`; `private final LootContextParamSet paramSet` `:47`; `private final Optional<ResourceLocation> randomSequence` `:48`.
  - `public static final Codec<LootTable> DIRECT_CODEC` `:37` (keys: `type`, `random_sequence`, `pools` via `CommonHooks.lootPoolsCodec` `:41`, `functions`). `CODEC` `:46` is the registry-file codec for `Holder<LootTable>`.
  - `getParamSet()` `:131`; `getLootTableId()` `:243`.
  - Roll entry points: `getRandomItemsRaw(LootParams, Consumer<ItemStack>)` `:80`; `getRandomItemsRaw(LootContext, Consumer<ItemStack>)` `:85`; `getRandomItems(LootParams, RandomSource)` `:112`; `getRandomItems(LootParams, long seed)` `:116`; `getRandomItems(LootParams)` `:120`; `fill(Container, LootParams, long)` `:145`.
  - The private `getRandomItems(LootContext)` `:124` is the only path that calls `CommonHooks.modifyLoot(this.getLootTableId(), list, ctx)` `:127` (NeoForge global loot modifiers). `getRandomItemsRaw` `:80` and `:85` do NOT run global loot modifiers.
  - Builder: `LootTable.lootTable()` `:214`; `Builder` `:274`; `withPool` `:280`; `apply` `:295`; `build` `:304`.
- `LootPool` `net/minecraft/world/level/storage/loot/LootPool.java:29` (public class).
  - `private final List<LootPoolEntryContainer> entries` `:41`. No getter.
  - `private final List<LootItemCondition> conditions` `:42`; `private final List<LootItemFunction> functions` `:44`.
  - `private NumberProvider rolls` `:46` and `private NumberProvider bonusRolls` `:47` (non-final). Public `getRolls()` `:161`, `getBonusRolls()` `:165`, `setRolls` `:169`, `setBonusRolls` `:174`.
  - `private String name` `:146`; public `getName()` `:149`.
  - `public void addRandomItems(Consumer<ItemStack>, LootContext)` `:100`. Roll count: `rolls.getInt(ctx) + floor(bonusRolls.getFloat(ctx) * luck)` `:103`.
  - Codec `CODEC` `:30` (keys `rolls`, `bonus_rolls`, `name`).
  - Builder: `LootPool.lootPool()` `:179`; `Builder` `:183`; `add` `:206`; `when` `:211`; `apply` `:216`; `name` `:221`; `setRolls` `:192`.

### 1.2 Entry containers (package `net.minecraft.world.level.storage.loot.entries`)

- `LootPoolEntryContainer` (abstract) `entries/LootPoolEntryContainer.java:15`. `protected final List<LootItemCondition> conditions` `:16`. `protected final boolean canRun(LootContext)` `:34`. Builder: `when` `:45`, `otherwise` `:58` (makes `AlternativesEntry`), `append` `:62` (makes `EntryGroup`), `then` `:66` (makes `SequentialEntry`).
- `LootPoolEntries.CODEC` `entries/LootPoolEntries.java:10`: public codec over all entry types. Encoding a table through `LootTable.DIRECT_CODEC` `:37` is a public route to the whole tree without access transformers (not runtime-tested).
- `LootPoolSingletonContainer` (abstract; base of `LootItem`, `TagEntry`, `NestedLootTable`, `DynamicLoot`, `EmptyLootItem`) `entries/LootPoolSingletonContainer.java:20`. `protected final int weight` `:23`; `protected final int quality` `:24`; `protected final List<LootItemFunction> functions` `:25`; `compositeFunction` `:26` (package-private). Builder: `apply` `:87`, `setWeight` `:96`, `setQuality` `:101`.
- `CompositeEntryBase` (abstract; base of `AlternativesEntry`, `EntryGroup`, `SequentialEntry`) `entries/CompositeEntryBase.java:12`. `protected final List<LootPoolEntryContainer> children` `:13`. No getter. `public final boolean expand(LootContext, Consumer<LootPoolEntry>)` `:37`.
- `LootItem` `entries/LootItem.java:17`. `private final Holder<Item> item` `:23`. Codec `CODEC` `:18-22` (key `name`). Factory `lootTableItem(ItemLike)` `:40`. `createItemStack` `:36`. No getter.
- `TagEntry` `entries/TagEntry.java:19`. `private final TagKey<Item> tag` `:28`; `private final boolean expand` `:29`. `public boolean expand(LootContext, Consumer<LootPoolEntry>)` `:67`. Factories: `tagContents(TagKey<Item>)` `:71`, `expandTag(TagKey<Item>)` `:75`.
- `NestedLootTable` `entries/NestedLootTable.java:21`. `private final Either<ResourceKey<LootTable>, LootTable> contents` `:29`. Factories: `lootTableReference(ResourceKey<LootTable>)` `:82`, `inlineLootTable(LootTable)` `:88`.
- `AlternativesEntry` `entries/AlternativesEntry.java:13` (extends `CompositeEntryBase`). `alternatives(...)` `:54`; `Builder` `:62`; `otherwise` `:76`.
- `EntryGroup` `entries/EntryGroup.java:10` (extends `CompositeEntryBase`). `list(...)` `:46`.
- `SequentialEntry` `entries/SequentialEntry.java:10` (extends `CompositeEntryBase`). `sequential(...)` `:40`.
- `DynamicLoot` `entries/DynamicLoot.java:14`. `private final ResourceLocation name` `:20`. Factory `dynamicEntry(ResourceLocation)` `:37`.
- `EmptyLootItem` `entries/EmptyLootItem.java:13`. No fields. Factory `emptyItem()` `:31`.

### 1.3 Conditions (package `...loot.predicates`; keys registered in `predicates/LootItemConditions.java:9-29`)

- `MatchTool` `predicates/MatchTool.java:15`: `public record MatchTool(Optional<ItemPredicate> predicate)`. Accessor `predicate()`. Key `match_tool` (`LootItemConditions.java:20`). `toolMatches(ItemPredicate.Builder)` `:35`.
- `LootItemRandomChanceCondition` `predicates/LootItemRandomChanceCondition.java:11`: `public record (NumberProvider chance)`. Key `random_chance` (`LootItemConditions.java:12`). Accessor `chance()`.
- `ExplosionCondition` `predicates/ExplosionCondition.java:11` (class, singleton, `CODEC` `:13`). `survivesExplosion()` `:39`. Key `survives_explosion` (`LootItemConditions.java:22`).
- `LootItemBlockStatePropertyCondition` `predicates/LootItemBlockStatePropertyCondition.java:18`: `public record (Holder<Block> block, Optional<StatePropertiesPredicate> properties)`. Key `block_state_property` (`LootItemConditions.java:19`). `hasBlockStateProperties(Block)` `:50`.
- `BonusLevelTableCondition` `predicates/BonusLevelTableCondition.java:20`: `public record (Holder<Enchantment> enchantment, List<Float> values)`. Key `table_bonus` (`LootItemConditions.java:21`). Codec keys `enchantment`, `chances`.
- `LootItemKilledByPlayerCondition` `predicates/LootItemKilledByPlayerCondition.java:10` (class, singleton, `CODEC` `:12`). Key `killed_by_player` (`LootItemConditions.java:17`).
- Also registered and not read in detail: `LootItemRandomChanceWithEnchantedBonusCondition` (`LootItemConditions.java:13-15`), `ENTITY_PROPERTIES` (`:16`), `DAMAGE_SOURCE_PROPERTIES` (`:23`), `ENCHANTMENT_ACTIVE_CHECK` (`:29`).

### 1.4 Functions that change the produced item (package `...loot.functions`; keys in `functions/LootItemFunctions.java`)

- `LootItemFunction` interface `functions/LootItemFunction.java:9` (extends `BiFunction<ItemStack, LootContext, ItemStack>`). `LootItemConditionalFunction` `:17` with `protected final List<LootItemCondition> predicates` `:18`.
- `set_count` `SetItemCountFunction` `functions/SetItemCountFunction.java:16`. `private final NumberProvider value` `:27`; `private final boolean add` `:28`. Factories `setCount(NumberProvider)` `:53`, `setCount(NumberProvider, boolean)` `:57`. Key registration `LootItemFunctions.java:23`.
- `apply_bonus` `ApplyBonusCount` `functions/ApplyBonusCount.java:27`. `private final Holder<Enchantment> enchantment` `:55`; `private final ApplyBonusCount.Formula formula` `:56`. Factories: `addBonusBinomialDistributionCount` `:90`, `addOreBonusCount(Holder<Enchantment>)` `:94`, `addUniformBonusCount(Holder<Enchantment>)` `:98`, `addUniformBonusCount(Holder<Enchantment>, int)` `:102`. Registration `LootItemFunctions.java:44`.
- `enchant_randomly` `EnchantRandomlyFunction` `functions/EnchantRandomlyFunction.java:28`. `private final Optional<HolderSet<Enchantment>> options` `:40`; `private final boolean onlyCompatible` `:41`. Codec keys `options` (`:30-38`), `only_compatible` (default true). Factories `randomEnchantment()` `:83`, `randomApplicableEnchantment(HolderLookup.Provider)` `:87`. Registration `LootItemFunctions.java:26`.
- `set_enchantments` `SetEnchantmentsFunction` `functions/SetEnchantmentsFunction.java:26`. `private final Map<Holder<Enchantment>, NumberProvider> enchantments` `:39`; `private final boolean add` `:40`. Registration `LootItemFunctions.java:27`.
- `furnace_smelt` `SmeltItemFunction` `functions/SmeltItemFunction.java:18`. `smelted()` `:53`. Looks up `RecipeType.SMELTING` through the level's `RecipeManager` (`SmeltItemFunction.java` around `:38-40`). Registration `LootItemFunctions.java:30`.
- `set_potion` `SetPotionFunction` `functions/SetPotionFunction.java:15`. `private final Holder<Potion> potion` `:19`. `setPotion(Holder<Potion>)` `:37`. Registration `LootItemFunctions.java:52`.
- Also registered: `set_item` (`:24`), `enchant_with_levels` (`:25`), `explosion_decay` (`:46`).

### 1.5 Mapping from a block or entity to its loot table

- Blocks: `public abstract class BlockBehaviour` `net/minecraft/world/level/block/state/BlockBehaviour.java:85`.
  - Default key when nothing is set: `blocks/<registry id>` built from `BuiltInRegistries.BLOCK.getKey(this.asBlock())` `:120-123`. Explicit overrides: `Properties.drops` `:979` (`ResourceKey<LootTable>`), `Properties.dropsLike(Block)` `:1139`, and the supplier set via `:1145`.
  - Use `public final ResourceKey<LootTable> getLootTable()` `:370`. It resolves the supplier `private ... lootTableSupplier` `:411` on first call.
  - `Properties.requiresCorrectToolForDrops()` `:1210` sets the flag; the flag is copied to the state at `:425` (private) and read through `BlockStateBase.requiresCorrectToolForDrops()` `:879` (public).
  - Drops: `protected List<ItemStack> getDrops(BlockState, LootParams.Builder)` `:264`. It calls `getLootTable()` `:265`, then `getLootTable(resourcekey)` `:271`, then `getRandomItems(lootparams)` `:272`. Each of those `getRandomItems` runs global loot modifiers.
  - `Block.getDrops(BlockState, ServerLevel, BlockPos, BlockEntity)` (static) `net/minecraft/world/level/block/Block.java:265`.
- Entities: `EntityType.getDefaultLootTable()` `net/minecraft/world/entity/EntityType.java:1073` (public). Key is `entities/<entity id>` `:1076`.
  - `LivingEntity.getLootTable()` `net/minecraft/world/entity/LivingEntity.java:1459` (public, override point).
  - `protected void dropFromLootTable(DamageSource, boolean)` `:1474`; it rolls with `getRandomItems(lootparams, this.getLootTableSeed(), this::spawnAtLocation)` `:1489`. `getLootTableSeed()` `:1463`.
- Fishing: `BuiltInLootTables.FISHING` = `gameplay/fishing` `net/minecraft/world/level/storage/loot/BuiltInLootTables.java:93`. Used by `FishingHook.retrieve` `FishingHook.java:458-459` with `LootContextParamSets.FISHING`.

---

## 2. Tool requirements

- `BlockTags` `net/minecraft/tags/BlockTags.java`:
  - `MINEABLE_WITH_AXE` `:139`, `MINEABLE_WITH_HOE` `:140`, `MINEABLE_WITH_PICKAXE` `:141`, `MINEABLE_WITH_SHOVEL` `:142` (paths `mineable/<tool>`).
  - `NEEDS_DIAMOND_TOOL` `:144`, `NEEDS_IRON_TOOL` `:145`, `NEEDS_STONE_TOOL` `:146`. There is no `NEEDS_*` for gold or wooden tools.
  - `INCORRECT_FOR_NETHERITE_TOOL` `:147`, `INCORRECT_FOR_DIAMOND_TOOL` `:148`, `INCORRECT_FOR_IRON_TOOL` `:149`, `INCORRECT_FOR_STONE_TOOL` `:150`, `INCORRECT_FOR_GOLD_TOOL` `:151`, `INCORRECT_FOR_WOODEN_TOOL` `:152`.
  - Datagen membership, from `net/minecraft/data/tags/VanillaBlockTagsProvider.java`: `NEEDS_DIAMOND_TOOL` `:1481`, `NEEDS_IRON_TOOL` `:1483`, `NEEDS_STONE_TOOL` `:1498`. `INCORRECT_FOR_IRON_TOOL` includes `NEEDS_DIAMOND_TOOL` `:1587`. `INCORRECT_FOR_STONE_TOOL` includes `NEEDS_DIAMOND_TOOL` and `NEEDS_IRON_TOOL` `:1588`. So the runtime tier is set by the `INCORRECT_FOR_*` tags, and `NEEDS_*` are subsets.
- `Tier` interface `net/minecraft/world/item/Tier.java`: `getUses()` `:10`, `getSpeed()` `:12`, `getIncorrectBlocksForDrops()` `:16` (returns `TagKey<Block>`). `default Tool createToolProperties(TagKey<Block>)` `:22-23` builds `Tool(rules=[deniesDrops(incorrect), minesAndDrops(tag, speed)], defaultMiningSpeed=1.0, damagePerBlock=1)`.
- `Tiers` `net/minecraft/world/item/Tiers.java`: `public enum Tiers implements Tier` `:11`. Constants `WOOD`, `STONE`, `IRON`, `DIAMOND`, `GOLD`, `NETHERITE` `:12-17`. Fields all `private final`: `incorrectBlocksForDrops` `:19`, `uses` `:20`, `speed` `:21`, then damage, enchantment value, repair ingredient. Accessors: `getUses()` `:36`, `getSpeed()` `:41`, `getIncorrectBlocksForDrops()` `:51`. NeoForge adds `getTag()` `:65`.
- `Tool` record `net/minecraft/world/item/component/Tool.java:21`: `Tool(List<Tool.Rule> rules, float defaultMiningSpeed, int damagePerBlock)`.
  - `getMiningSpeed(BlockState)` `:40`: first rule with `speed` present whose blocks match; otherwise the default.
  - `isCorrectForDrops(BlockState)` `:50`: first rule with `correctForDrops` present whose blocks match; otherwise `false`.
  - `Tool.Rule` record `:60` (`HolderSet<Block> blocks`, `Optional<Float> speed`, `Optional<Boolean> correctForDrops`). Factories: `minesAndDrops(TagKey<Block>, float)` `:83`, `deniesDrops(TagKey<Block>)` `:87`, `overrideSpeed(...)` `:91`, `:95`.
- Item level: `Item.isCorrectToolForDrops(ItemStack, BlockState)` `net/minecraft/world/item/Item.java:221-224` reads `DataComponents.TOOL`. `ItemStack.isCorrectToolForDrops(BlockState)` `net/minecraft/world/item/ItemStack.java:544`. `DiggerItem` `net/minecraft/world/item/DiggerItem.java:15` puts `tier.createToolProperties(tag)` into the item's `TOOL` component.
- Harvest decision, position-sensitive:
  - `BlockState.canHarvestBlock(BlockGetter, BlockPos, Player)` is defined in NeoForge `net/neoforged/neoforge/common/extensions/IBlockStateExtension.java:126`. It delegates to `IBlockExtension.canHarvestBlock(BlockState, BlockGetter, BlockPos, Player)` `IBlockExtension.java:218`, which calls `EventHooks.doPlayerHarvestCheck` `net/neoforged/neoforge/event/EventHooks.java:219-224`.
  - `doPlayerHarvestCheck` computes the vanilla value `player.hasCorrectToolForDrops(state)` and posts `PlayerEvent.HarvestCheck`. Listeners can override the result.
  - Vanilla deprecated `Player.hasCorrectToolForDrops(BlockState)` `net/minecraft/world/entity/player/Player.java:784-786` returns `!state.requiresCorrectToolForDrops() || inventory.getSelected().isCorrectToolForDrops(state)`. The position-sensitive `hasCorrectToolForDrops(BlockState, Level, BlockPos)` is at `Player.java:788-790`.
  - `ServerPlayerGameMode.destroyBlock` uses `blockstate.canHarvestBlock(level, pos, player)` at `net/minecraft/server/level/ServerPlayerGameMode.java:269`.

---

## 3. Ore placement

- `PlacedFeature` `net/minecraft/world/level/levelgen/placement/PlacedFeature.java:21`: `public record PlacedFeature(Holder<ConfiguredFeature<?, ?>> feature, List<PlacementModifier> placement)`. Accessors `feature()`, `placement()`. `getFeatures()` `:61`.
- Walk from a placed feature to its height:
  1. `placement()` and filter for `HeightRangePlacement`.
  2. `HeightRangePlacement` `net/minecraft/world/level/levelgen/placement/HeightRangePlacement.java:14`. `private final HeightProvider height` `:19`. There is NO getter. Codec key `height` `:16`.
  3. Factories: `of(HeightProvider)` `:25`; `uniform(VerticalAnchor, VerticalAnchor)` `:29` (builds `UniformHeight`); `triangle(VerticalAnchor, VerticalAnchor)` `:33` (builds `TrapezoidHeight` with plateau 0, see `TrapezoidHeight.java:38-40`).
  4. `HeightProvider` `net/minecraft/world/level/levelgen/heightproviders/HeightProvider.java:10` is abstract. Public `int sample(RandomSource, WorldGenerationContext)` `:19`; `getType()` `:21`.
  5. `UniformHeight` `heightproviders/UniformHeight.java:15`: `private final VerticalAnchor minInclusive` `:24`, `private final VerticalAnchor maxInclusive` `:25`. NO getters. `sample` `:38-50`.
  6. `TrapezoidHeight` `heightproviders/TrapezoidHeight.java:14`: `private final VerticalAnchor minInclusive` `:24`, `private final VerticalAnchor maxInclusive` `:25`, `private final int plateau` `:26`. NO getters. `of(min, max, plateau)` `:34`; `of(min, max)` `:38` (plateau 0). `sample` `:43`.
  7. `ConstantHeight` `heightproviders/ConstantHeight.java:8`: `private final VerticalAnchor value` `:11`; public `getValue()` `:21`.
  8. `VerticalAnchor` `net/minecraft/world/level/levelgen/VerticalAnchor.java:8` is an interface. Records: `AboveBottom(int offset)` `:50`, `Absolute(int y)` `:67`, `BelowTop(int offset)` `:84`. Each has `resolveY(WorldGenerationContext)` at `:57`, `:74`, `:91`.
  9. Resolving needs a `WorldGenerationContext` `net/minecraft/world/level/levelgen/WorldGenerationContext.java:6`: constructor `(ChunkGenerator, LevelHeightAccessor)` `:10`; `getMinGenY()` `:15`; `getGenDepth()` `:19`.
- Walk from an ore configuration to its target states:
  - `OreConfiguration` `net/minecraft/world/level/levelgen/feature/configurations/OreConfiguration.java:11`. `public final List<OreConfiguration.TargetBlockState> targetStates` `:20`; `public final int size` `:21`; `public final float discardChanceOnAirExposure` `:22`. All public.
  - `TargetBlockState` `:46`: `public final RuleTest target` `:54`, `public final BlockState state` `:55`. Factory `target(RuleTest, BlockState)` `:42`.
  - `ConfiguredFeature` `net/minecraft/world/level/levelgen/feature/ConfiguredFeature.java:17`: `public record ConfiguredFeature<FC, F>(F feature, FC config)`. `getFeatures()` `:28` walks nested configs (see `configurations/FeatureConfiguration.java:9`).
  - Ore feature class: `OreFeature` `net/minecraft/world/level/levelgen/feature/OreFeature.java:17` (`Feature<OreConfiguration>`), `place` `:23`.
- Enumerating placed features per biome: `BiomeGenerationSettings` `net/minecraft/world/level/biome/BiomeGenerationSettings.java:33`. `private final List<HolderSet<PlacedFeature>> features` `:54`; `public List<HolderSet<PlacedFeature>> features()` `:85`. Each `HolderSet` is the feature list for one `GenerationStep.Decoration`; the index-to-decoration mapping was not confirmed (UNVERIFIED, see open questions).
- Vanilla examples (datagen bootstrap, which describes the same numbers the registry JSON carries):
  - `net/minecraft/data/worldgen/placement/OrePlacements.java`: `ORE_DIAMOND_MEDIUM` key `:50`, registered with `commonOrePlacement(2, HeightRangePlacement.uniform(absolute(-64), absolute(-4)))` `:216`; `ORE_DIAMOND_LARGE` key `:51`, registered with `rareOrePlacement(9, HeightRangePlacement.triangle(aboveBottom(-80), aboveBottom(80)))` `:222`.
  - `net/minecraft/data/worldgen/BiomeDefaultFeatures.java:66-69`: diamond placed features added to `UNDERGROUND_ORES`.
  - `net/minecraft/data/worldgen/features/OreFeatures.java:109-110`: diamond configs (`size 4, discard 0.5` small; `size 12, discard 0.7` large).
  - Runtime Y ranges come from the registry (datapack JSON), not from these Java bootstraps. Read them from the registry.

---

## 4. Villager trades and professions

### 4.1 Trade tables

- `VillagerTrades` `net/minecraft/world/entity/npc/VillagerTrades.java:66` (public class).
  - `public static final Map<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> TRADES` `:81`. Keys are villager level 1..5. This is a plain mutable map at runtime; NeoForge replaces entries (see 4.4).
  - `WANDERING_TRADER_TRADES` `:662` (`Int2ObjectMap`, keys 1 and 2).
  - `EXPERIMENTAL_TRADES` `:763` (`Map.of`, TRADE_REBALANCE feature only). The per-biome librarian listings are in this map: `commonBooks(int)` `:1361` is used at `:771`, `:778`, `:785`; `TypeSpecificTrade` `:1847`.
  - `Villager.updateTrades()` `net/minecraft/world/entity/npc/Villager.java:861-876` uses `EXPERIMENTAL_TRADES` only when `level().enabledFeatures().contains(FeatureFlags.TRADE_REBALANCE)` `:863`, and falls back to `TRADES` for professions missing from it `:865-866`. Otherwise it reads `TRADES` `:868`.
- `public interface ItemListing` `VillagerTrades.java:1593`. `MerchantOffer getOffer(Entity, RandomSource)` `:1595` (public, the only method). Use it to read any listing's output without access transformers, given an entity in a level.
- Implementations (all `public static class ... implements ItemListing`; fields all `private final`, no getters):
  - `DyedArmorForEmeralds` `:1401`, `EmeraldForItems` `:1444` (`ItemCost itemStack`, `maxUses`, `villagerXp`, `emeraldAmount`, `priceMultiplier`), `EmeraldsForVillagerTypeItem` `:1473`, `EnchantBookForEmeralds` `:1504`, `EnchantedItemForEmeralds` `:1553`, `FailureItemListing` `:1585`, `ItemsAndEmeraldsToItems` `:1598`, `ItemsForEmeralds` `:1670` (`ItemStack itemStack`, `emeraldCost`, `maxUses`, `villagerXp`, `priceMultiplier`, `enchantmentProvider`), `SuspiciousStewForEmerald` `:1744`, `TippedArrowForItemsAndEmeralds` `:1768`, `TreasureMapForEmeralds` `:1805`, `TypeSpecificTrade` record `:1847`.
- `EnchantBookForEmeralds` `:1504`:
  - Fields `:1505-1508`: `int villagerXp`, `TagKey<Enchantment> tradeableEnchantments`, `int minLevel`, `int maxLevel`. All private final.
  - `getOffer` `:1522` calls `p_219688_.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getRandomElementOf(this.tradeableEnchantments, rnd)`. So the picked enchantment is a uniform random choice from the tag, with level clamped to the enchantment's own min/max and to `minLevel`/`maxLevel`. Price: `2 + rnd(5 + l*10) + 3*l`; doubled when the enchantment is in `EnchantmentTags.DOUBLE_TRADE_PRICE`; capped at 64. The offer is `ItemCost(EMERALD, price)` plus `Optional(ItemCost(BOOK))`, `maxUses` 12, xp `villagerXp`, price multiplier 0.2.
  - Librarian tiers in `TRADES` use `new EnchantBookForEmeralds(xp, EnchantmentTags.TRADEABLE)` at `VillagerTrades.java:314`, `:322`, `:330`, `:338` (xp 1, 5, 10, 15).
  - `EnchantmentTags.TRADEABLE` `net/minecraft/tags/EnchantmentTags.java:16`; `DOUBLE_TRADE_PRICE` `:17`.
  - Note the enchanted-book tag: the normal librarian uses `tradeable`; the per-biome `TRADES_*_COMMON` and `TRADES_*_SPECIAL` tags are experimental-only (`VillagerTrades.java:1364-1384`).
- `AbstractVillager` `net/minecraft/world/entity/npc/AbstractVillager.java:48`: `protected MerchantOffers offers`.

### 4.2 Villager state

- `Villager` `net/minecraft/world/entity/npc/Villager.java`:
  - `setVillagerData(VillagerData)` `:587-593`: sets `this.offers = null` when the profession changes `:589-591`.
  - `getVillagerXp()` `:935`, `setVillagerXp(int)` `:939`; field `private int villagerXp` `:128`.
  - `shouldIncreaseLevel()` `:733-735` (level can rise and `villagerXp >= getMaxXpPerLevel`). `rewardTradeXp` `:603-` adds the offer xp; a level-up calls `updateTrades()` `:739-740`.
  - Trading is refused when the offer list is empty: `mobInteract` `:332` returns `CONSUME` instead of opening the trade screen.
  - `customServerAiStep` `:305` stops trading if the profession is `NONE`.
  - `finalizeSpawn` `:769-771` sets profession `NONE` on breeding spawns.
  - Trade start: `private void startTrading(Player)` `:359` (private; use a `MenuProvider` or `setTradingPlayer` instead).
  - POI memory predicates: `POI_MEMORIES` `:176-182` (`JOB_SITE` uses `heldJobSite()`, `POTENTIAL_JOB_SITE` uses `ALL_ACQUIRABLE_JOBS`).
- `VillagerProfession` `net/minecraft/world/entity/npc/VillagerProfession.java`: constructor takes `heldJobSite` `:23` and `acquirableJobSite` `:24` predicates. `ALL_ACQUIRABLE_JOBS` `:29` = `PoiTypeTags.ACQUIRABLE_JOB_SITE`. `NONE` `:30`.

### 4.3 Profession assignment and reset

- Brain wiring, `net/minecraft/world/entity/ai/behavior/VillagerGoalPackages.java`:
  - `AcquirePoi.create(acquirableJobSite, JOB_SITE, POTENTIAL_JOB_SITE, true, empty)` `:43` (priority 6).
  - `AssignProfessionFromJobSite.create()` `:48` (priority 10).
  - `ResetProfession.create()` `:49` (priority 10).
- `AssignProfessionFromJobSite` `ai/behavior/AssignProfessionFromJobSite.java`: needs `POTENTIAL_JOB_SITE` present. If the villager is not within 2.0 of the POI centre `:24` and `assignProfessionWhenSpawned()` is false, it returns false. Otherwise it erases `POTENTIAL_JOB_SITE` `:27` and sets `JOB_SITE`. If the profession is still `NONE`, it takes the first registered profession whose `heldJobSite()` matches the POI type `:39`, then `refreshBrain`.
- `ResetProfession` `ai/behavior/ResetProfession.java:14-24`: needs `JOB_SITE` absent. Resets to `NONE` only when profession is not `NONE` or `NITWIT`, `villagerXp == 0`, and `level <= 1`.
- `AcquirePoi` `ai/behavior/AcquirePoi.java`:
  - `SCAN_RANGE = 48` `:30`. Search is `findAllClosestFirstWithType(..., blockPosition, 48, HAS_SPACE)` `:52`.
  - First attempt scheduled `now + 20 + rnd(20)` ticks `:37`.
  - Retry backoff `markAttempt` `:140-144`: `currentDelay = min(currentDelay + rnd(40) + 40, 400)`; next attempt is `now + currentDelay`.
  - `isStillValid` `:146-148`: valid while the last attempt is under 400 ticks old.
  - `MIN_INTERVAL_INCREASE` and `MAX_INTERVAL_INCREASE` `:127-128` are declared and not used; the code uses literal 40.
  - So: a villager with no job site scans every 20-39 ticks at first, backs off by roughly 40-80 ticks per failed try, and tops out at 400 ticks.
- Lectern specifics (which `PoiType` the librarian holds) were not checked (UNVERIFIED).

### 4.4 NeoForge trade events

- `net/neoforged/neoforge/common/VillagerTradingManager.java`:
  - Static copy of `TRADES` into `VANILLA_TRADES` `:29-31`, and of `WANDERING_TRADER_TRADES` `:34`.
  - `loadTrades(TagsUpdatedEvent)` `:37-43` runs the events only when `UpdateCause == SERVER_DATA_LOAD` `:38`.
  - `postWandererEvent` `:47-54` posts `WandererTradesEvent` `:52`, then writes back `WANDERING_TRADER_TRADES.put(1|2, ...)` `:53-54`.
  - `postVillagerEvents` `:58-74`: for each profession, posts `VillagerTradesEvent` `:70` on a mutable copy, then `VillagerTrades.TRADES.put(prof, newTrades)` `:73`.
  - Consequence: the event sees `TRADES` only, not `EXPERIMENTAL_TRADES`. `TRADES` is final only after the `SERVER_DATA_LOAD` event, so an index of trade listings must be rebuilt on each server data load.

---

## 5. Recipes

- `RecipeManager` `net/minecraft/world/item/crafting/RecipeManager.java`:
  - `RecipeManager(HolderLookup.Provider)` `:44`.
  - `getRecipeFor(RecipeType<T>, I, Level)` `:83` (returns `Optional<RecipeHolder<T>>`).
  - `getAllRecipesFor(RecipeType<T>)` `:106` returns `List<RecipeHolder<T>>`. This is the enumeration to use.
  - `getRecipesFor(RecipeType<T>, I, Level)` `:110`; `getRemainingItemsFor(...)` `:122`.
  - `byKey(ResourceLocation)` `:137` returns `Optional<RecipeHolder<?>>`; `getOrderedRecipes()` `:147`; `getRecipes()` `:151` returns `Collection<RecipeHolder<?>>`; `getRecipeIds()` `:155`; `replaceRecipes(...)` `:165`; `createCheck(...)` `:180`.
- `RecipeHolder` `net/minecraft/world/item/crafting/RecipeHolder.java:7`: `public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id, T value)`.
- `Recipe` interface `net/minecraft/world/item/crafting/Recipe.java`: `getResultItem(HolderLookup.Provider)` `:28`; `default NonNullList<ItemStack> getRemainingItems(T)` `:30-41`; `default NonNullList<Ingredient> getIngredients()` `:43`; `getToastSymbol()` `:59`; `isIncomplete()` `:67`.
- `ShapedRecipe` `ShapedRecipe.java:14`: `public final ShapedRecipePattern pattern` `:15` (public field). `getResultItem` `:49`, `getIngredients()` `:54` (public).
  - `ShapedRecipePattern` `ShapedRecipePattern.java:22` (`public final class`). Fields `private final` `:55-60` (`width`, `height`, `NonNullList<Ingredient> ingredients`, `Optional<Data>`, `ingredientCount`, `symmetrical`). Public accessors `width()` `:226`, `height()` `:230`, `ingredients()` `:234`. `Data(Map<Character, Ingredient> key, List<String> pattern)` record `:238`.
- `ShapelessRecipe` `ShapelessRecipe.java:16`: `ingredients` is package-private `final NonNullList<Ingredient>` `:20`; `result` `:19`. Public `getIngredients()` `:52`, `getResultItem` `:47`.
- `AbstractCookingRecipe` `crafting/AbstractCookingRecipe.java:8` (abstract, `Recipe<SingleRecipeInput>`): `protected final Ingredient ingredient` `:12`, `protected final ItemStack result` `:13`, `protected final float experience` `:14`, `protected final int cookingTime` `:15`. Public: `getIngredients()` `:43`, `getResultItem` `:54`, `getExperience()` `:49`, `getCookingTime()` `:63`, `getType()` `:68`, `category()` `:72`.
- `SingleItemRecipe` (base of `StonecutterRecipe`, `StonecutterRecipe.java:7`): `protected final Ingredient ingredient` `:15`, `protected final ItemStack result` `:16`. Public `getIngredients()` `:50`, `getResultItem` `:45`.
- `SmithingTransformRecipe` `SmithingTransformRecipe.java:13`: `template`, `base`, `addition` and `result` are package-private `final`. Public access is through predicates: `isTemplateIngredient(ItemStack)` `:42`, `isBaseIngredient` `:47`, `isAdditionIngredient` `:52`; `getResultItem` `:37`.
- `Ingredient` `net/minecraft/world/item/crafting/Ingredient.java:30` (`public final class Ingredient implements Predicate<ItemStack>`). `private final Ingredient.Value[] values` `:55`. Public: `getItems()` `:94` (`ItemStack[]`; expands tags), `getValues()` `:181`, `test(ItemStack)` `:107`, `isEmpty()` `:143`, `isSimple()` `:188`, `isCustom()` `:197`. Factories: `of(ItemLike...)` `:210`, `of(ItemStack...)` `:214`, `of(TagKey<Item>)` `:222`, `fromValues` `:201`.
- Crafting remainder: `Item.getCraftingRemainingItem()` `net/minecraft/world/item/Item.java:257` (`public final Item`); `Item.hasCraftingRemainingItem()` `:262`. `Recipe.getRemainingItems(T)` `Recipe.java:30-41` applies it per input slot using the input item, not the recipe.

---

## 6. Player simulation (body and tasks)

### 6.1 How a server player moves

- `ServerPlayer` `net/minecraft/server/level/ServerPlayer.java`:
  - `public ServerGamePacketListenerImpl connection` `:177` (public, not final).
  - `public final ServerPlayerGameMode gameMode` `:179`.
  - Constructor `ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation)` `:284`.
  - `public void tick()` `:495`: calls `gameMode.tick()` `:496` (break progress), `wardenSpawnTracker.tick()`, counters. It does NOT call `super.tick()` and does not call `doTick()` (verified: no other `super` call in `:495-552`).
  - `public void doTick()` `:553`: `super.tick()` `:556` (guarded by `!isSpectator() || !touchingUnloadedChunk()`), then inventory and health sync.
  - `public void travel(Vec3)` `:1236`: calls `super.travel` and records movement stats.
  - `public void setPlayerInput(float xxa, float zza, boolean jumping, boolean sneak)` `:1220-1232`: writes `xxa`, `zza`, `jumping` and shift ONLY if `isPassenger()`. For a non-passenger it does nothing (silently dropped).
- Who calls `doTick()`: only `ServerGamePacketListenerImpl.tick()` at `net/minecraft/server/network/ServerGamePacketListenerImpl.java:260`.
- Connection tick order, `ServerGamePacketListenerImpl.java:250-262`:
  1. `resetPosition()` `:256` sets `firstGood*` and `lastGood*` to the player's current position (`:325-331`).
  2. Copies `xo/yo/zo` `:257-259`.
  3. `player.doTick()` `:260`.
  4. `player.absMoveTo(firstGoodX, firstGoodY, firstGoodZ, yRot, xRot)` `:261`.
  5. `tickCount++` `:262`.
- Consequence (read from this code, runtime UNVERIFIED): a `doTick()` that moves the player is undone by step 4, because `firstGood*` was set to the pre-physics position in step 1. Velocity survives; position does not. A body that uses this connection tick must keep `firstGood*` at its own position, or replace the tick.
- Physics chain inside `doTick()`:
  - `Player.tick()` `net/minecraft/world/entity/player/Player.java:253` calls `super.tick()` `:281`.
  - `LivingEntity.tick()` `net/minecraft/world/entity/LivingEntity.java:2392` calls `aiStep()` `:2432` when not removed.
  - `Player.aiStep()` `Player.java:527` calls `super.aiStep()` at `:548` (the context around `:548` was not read).
  - `LivingEntity.aiStep()` `:2660`. If `isImmobile()`, it zeroes `jumping`, `xxa`, `zza` `:2701-2703`. Else if `isEffectiveAi()`, it calls `serverAiStep()` `:2706`.
  - Movement: `vec31 = (xxa, yya, zza)` `:2749`; then `travel(vec31)` `:2760` (or `travelRidden` when a player is the controlling passenger `:2756-2757`).
  - `LivingEntity.travel(Vec3)` `:2161`: gated by `isControlledByLocalInstance()` `:2162`. `Entity.isControlledByLocalInstance()` `Entity.java:3050` returns `isEffectiveAi()` when there is no controlling player. `Entity.isEffectiveAi()` `:3054` returns `!level().isClientSide`, which is true on the server.
  - So on the server, `travel` does run for a `ServerPlayer` on each `doTick()`.
- `ServerPlayer` has no `aiStep` or `serverAiStep` override (verified by grep).
- Client packets:
  - `ServerGamePacketListenerImpl.handlePlayerInput(ServerboundPlayerInputPacket)` `:371-374` forwards to `player.setPlayerInput(...)`, which drops it for non-passengers (see above).
  - `handleMovePlayer(ServerboundMovePlayerPacket)` `:857`. Validates the packet and, for a non-passenger, sets the player position from the packet. Only the first 40 lines were read; the exact branch that updates `firstGood*` was not (UNVERIFIED).
- Movement input fields on `LivingEntity` (`net/minecraft/world/entity/LivingEntity.java`):
  - `protected boolean jumping` `:217` (protected, use `setJumping`).
  - `public float xxa` `:218`, `public float yya` `:219`, `public float zza` `:220`. Usage in `aiStep` is `(xxa, yya, zza)` passed to `travel`. Which axis is strafe and which is forward is inferred from usage, not stated in the source.
  - `public void setJumping(boolean)` `:2950`.
  - `Entity.setShiftKeyDown(boolean)` `net/minecraft/world/entity/Entity.java:2243` (public).
- Break progress happens in `ServerPlayer.tick()` through `gameMode.tick()` `:496`, not in `doTick()`. The two run from different hooks (see 6.2).

### 6.2 Block breaking, using items, attack

- `ServerPlayerGameMode` `net/minecraft/server/level/ServerPlayerGameMode.java:32`. Fields: `protected ServerLevel level` `:34`, `protected final ServerPlayer player` `:35`; private `isDestroyingBlock` `:39`, `destroyProgressStart` `:40`, `destroyPos` `:41`, `gameTicks` `:42`, `hasDelayedDestroy` `:43`, `delayedDestroyPos` `:44`, `delayedTickStart` `:45`, `lastSentState` `:46`.
- `public void tick()` `:98`: `gameTicks++`. If a delayed destroy is pending, or a break is in progress, it calls `incrementDestroyProgress` `:123` (private). It cancels the break when the block became air.
- `public void handleBlockBreakAction(BlockPos, ServerboundPlayerActionPacket.Action, Direction, int maxBuildHeight, int sequence)` `:138`. Checks, in order: NeoForge `LeftClickBlock` event `:139-141`; reach `canInteractWithBlock(pos, 1.0)`; build height; then START (`mayInteract`, creative instant break `destroyAndAck`, instant break when `getDestroyProgress >= 1`, else `isDestroyingBlock = true`).
- `public void destroyAndAck(BlockPos, int sequence, String reason)` `:238`.
- `public boolean destroyBlock(BlockPos)` `:247`: NeoForge `fireBlockBreak` `:248-249`; game-master and `blockActionRestricted` checks; harvest `canHarvestBlock` `:269`; `removeBlock(pos, state, canHarvest)` `:271`; `block.playerDestroy(...)` `:274`. `private boolean removeBlock(BlockPos, BlockState, boolean)` `:296`.
- `public InteractionResult useItem(ServerPlayer, Level, ItemStack, InteractionHand)` `:303`. Spectator gets `PASS`; cooldown is checked before the item runs.
- `public InteractionResult useItemOn(ServerPlayer, Level, ItemStack, InteractionHand, BlockHitResult)` `:337`. Runs NeoForge `RightClickBlock` `:341-342` before the item's `useOn`.
- `public void setLevel(ServerLevel)` `:400`.
- `public void attack(Entity)` on `Player` `net/minecraft/world/entity/player/Player.java:1183`. Runs `CommonHooks.onPlayerAttackTarget` first `:1184`. Damage uses `Attributes.ATTACK_DAMAGE` and `getWeaponItem()`. The rest was not read.

### 6.3 Eating

- `FoodData` `net/minecraft/world/food/FoodData.java:9`. All fields private: `foodLevel = 20` `:10`, `saturationLevel` `:11`, `exhaustionLevel` `:12`, `tickTimer` `:13`, `lastFoodLevel = 20` `:14`.
  - Public: `eat(int nutrition, float saturation)` `:25`; `eat(FoodProperties)` `:29`; `tick(Player)` `:33`; `getFoodLevel()` `:91`; `needsFood()` `:99`; `addExhaustion(float)` `:103`; `getExhaustionLevel()` `:107`; `getSaturationLevel()` `:111`; `setFoodLevel(int)` `:115`; `setSaturation(float)` `:119`; `setExhaustion(float)` `:123`.
  - `Player` holds `foodData` (used at `Player.java:533`; the accessor name was not confirmed, UNVERIFIED).
- `LivingEntity` item use: `protected ItemStack useItem = ItemStack.EMPTY` `:239`; `public boolean isUsingItem()` `:3053`; `private void updatingUsingItem()` `:3061` (runs from `tick()` `:2394`); `public void startUsingItem(InteractionHand)` `:3116`; `protected void completeUsingItem()` `:3195` (calls `useItem.finishUsingItem(level, this)` `:3204` through NeoForge's `onItemUseFinish`); `public ItemStack getUseItem()` `:3215`.
- The branch that reaches `completeUsingItem` from `updatingUsingItem` was not read (UNVERIFIED). `completeUsingItem` is protected, so a body must reach it through the tick path or a subclass.

### 6.4 Fishing hook

- `FishingHook` `net/minecraft/world/entity/projectile/FishingHook.java`:
  - Constructors: `FishingHook(Player, Level, int lureSpeed, int luck)` `:80`.
  - `private boolean biting` `:52`; `private static final EntityDataAccessor<Boolean> DATA_BITING` `:56` (synced flag, private, no accessor).
  - `private int nibble` `:58` (bite window countdown); `private int timeUntilLured` `:59`; `private int timeUntilHooked` `:60`.
  - `private Entity hookedIn` `:64`; `private FishingHook.FishHookState currentState` `:65`. The enum `static enum FishHookState { FLYING, HOOKED_IN_ENTITY, BOBBING }` `:576-580` is package-private.
  - Bite start: `nibble = nextInt(20, 40)` and `DATA_BITING = true` `:347-348`. Bite end: when `nibble` reaches 0 it sets `DATA_BITING = false` `:294-299`.
  - Public: `getPlayerOwner()` `:545`, `getHookedIn()` `:551`, `retrieve(ItemStack)` `:440`.
  - `retrieve` `:440`: if something is hooked it pulls it; else if `nibble > 0` `:450` it rolls the fishing table `:451-459` (`LootContextParamSets.FISHING`, `BuiltInLootTables.FISHING`) and triggers the advancement `:466`. Do not call `retrieve` as a probe: it awards loot and changes state.
- The bite is therefore readable only through the private `DATA_BITING` synced data or the private `nibble`, both needing an access transformer or reflection. Nothing public exposes the bite (verified).
- The hook is created by `FishingRodItem` `net/minecraft/world/item/FishingRodItem.java:59`.

### 6.5 Containers and trading on the server

- `Player.openMenu(MenuProvider)` `net/minecraft/world/entity/player/Player.java:1040` returns `OptionalInt`.
- `ServerPlayer.openMenu(MenuProvider)` `net/minecraft/server/level/ServerPlayer.java:1115` and `openMenu(MenuProvider, Consumer<RegistryFriendlyByteBuf>)` `:1120`. Closes the current container (`:1122-1126`), increments `containerCounter`, calls `createMenu(containerCounter, inventory, this)`, and returns empty if that returns null (spectators get a message). Use `new SimpleMenuProvider`-style providers.
- `AbstractContainerMenu` `net/minecraft/world/inventory/AbstractContainerMenu.java`: `public final NonNullList<Slot> slots` `:47`; `public Slot getSlot(int)` `:278`; `public void clicked(int slotId, int button, ClickType, Player)` `:284` (server-side equivalent of a container click packet).
- `Slot.onTake(Player, ItemStack)` `net/minecraft/world/inventory/Slot.java:41` (public).
- `CraftingMenu` `net/minecraft/world/inventory/CraftingMenu.java`: `(int containerId, Inventory)` `:34` (uses `ContainerLevelAccess.NULL`); `(int containerId, Inventory, ContainerLevelAccess)` `:38`. `ContainerLevelAccess` `net/minecraft/world/inventory/ContainerLevelAccess.java`: `NULL` `:10`, `create(Level, BlockPos)` `:17`.
- `AbstractFurnaceMenu` `net/minecraft/world/inventory/AbstractFurnaceMenu.java`: `protected` constructors `:33` (`MenuType, RecipeType<? extends AbstractCookingRecipe>, RecipeBookType, int containerId, Inventory`, with new `SimpleContainer(3)` and `SimpleContainerData(4)`) and `:39` (takes `Container` and `ContainerData`). `private final Container container` `:27` and `private final ContainerData data` `:28` (no getter). `FurnaceMenu` `:8` (`(int, Inventory)`) and `:12` (`(int, Inventory, Container, ContainerData)`).
- `MerchantMenu` `net/minecraft/world/inventory/MerchantMenu.java`:
  - Constructor `(int containerId, Inventory, Merchant)` `:38`.
  - `PAYMENT1_SLOT = 0` `:17`, `PAYMENT2_SLOT = 1` `:18`, `protected static final int RESULT_SLOT = 2` `:19` (protected, so use `getSlot(2)`).
  - `public void setSelectionHint(int)` `:67`; `public void tryMoveItems(int)` `:181` (moves the payment items for offer index from the player inventory into the payment slots); `public MerchantOffers getOffers()` `:232`.
- The vanilla trade path, server side:
  - `ServerboundSelectTradePacket` handled by `ServerGamePacketListenerImpl.handleSelectTrade` `:769-781`: `setSelectionHint(i)` then `tryMoveItems(i)`.
  - The trade executes when the result slot is taken: `MerchantResultSlot.onTake` `net/minecraft/world/inventory/MerchantResultSlot.java:49-56`. It calls `merchantoffer.take(...)` `:53`, then `merchant.notifyTrade(merchantoffer)` `:56`, then awards `TRADED_WITH_VILLAGER`.
  - So a server-side trade means: select hint, move payment, then `onTake` on slot 2 (via `clicked` or directly). Whether `clicked` reaches `onTake` for slot 2 was not read (UNVERIFIED).
- `Merchant` interface `net/minecraft/world/item/trading/Merchant.java`: `setTradingPlayer(Player)` `:14`, `getTradingPlayer()` `:17`, `getOffers()` `:19`, `notifyTrade(MerchantOffer)` `:23`, `notifyTradeUpdated(ItemStack)` `:25`.
- `AbstractVillager.setTradingPlayer` `net/minecraft/world/entity/npc/AbstractVillager.java:86`.
- `MerchantOffer` constructors `net/minecraft/world/item/trading/MerchantOffer.java:66`, `:70` (`ItemCost, Optional<ItemCost>, ItemStack, int maxUses, int xp, float priceMultiplier`), `:74`. `take(ItemStack, ItemStack)` signature was not read.

---

## 7. Structure templates (blueprint builds)

- `StructureTemplate` `net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java:54` (public class).
  - `private final List<StructureTemplate.Palette> palettes` `:66`. NO getter. `private final List<StructureEntityInfo> entityInfoList` `:67`.
  - Public: `getSize()` `:71`, `getSize(Rotation)` `:496`, `setAuthor/getAuthor` `:75-81`, `fillFromWorld(Level, BlockPos, Vec3i, boolean includeEntities, @Nullable Block)` `:83`, `save(CompoundTag)` `:609`, `load(HolderGetter<Block>, CompoundTag)` `:706`, `filterBlocks(BlockPos, StructurePlaceSettings, Block)` `:182`, `filterBlocks(BlockPos, StructurePlaceSettings, Block, boolean)` `:186`, `placeInWorld(ServerLevelAccessor, BlockPos, BlockPos, StructurePlaceSettings, RandomSource, int flags)` `:226`, `getBoundingBox(...)` `:593`, `:597`.
- `load` NBT layout (`:706-767`):
  - `size`: list of 3 ints.
  - `palettes` (list of lists, tag type 9) if present, else `palette` (a single list of block-state compounds, tag type 10).
  - `blocks`: list of compounds with `pos` (3 ints), `state` (int index into the palette), optional `nbt`.
  - `entities`: list of compounds with `pos` (6 doubles), `blockPos` (3 ints), optional `nbt`.
  - Block states are read with `NbtUtils.readBlockState` `:741`.
- `StructureTemplate.Palette` `:790` is `public static final class` with `private final List<StructureBlockInfo> blocks` `:791`. Public `blocks()` `:798` and `blocks(Block)` `:802`. Reaching a palette from a template needs the private `palettes` field (access transformer), or `filterBlocks`/`placeInWorld`.
- `StructureBlockInfo` `:839`: `public static record StructureBlockInfo(BlockPos pos, BlockState state, @Nullable CompoundTag nbt)`. `StructureEntityInfo` `:846` (constructor `(Vec3, BlockPos, CompoundTag)` `:851`).
- `StructurePlaceSettings` `net/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings.java`: `copy()` `:29`; setters `setMirror` `:45`, `setRotation` `:50`, `setRotationPivot` `:55`, `setIgnoreEntities` `:60`, `setBoundingBox` `:65`, `setRandom(RandomSource)` `:70`, `setLiquidSettings` `:75`, `setKnownShape` `:80`, `clearProcessors` `:85`, `addProcessor` `:90`, `popProcessor` `:95`, `setFinalizeEntities` `:150`. Getters `getMirror` `:100`, `getRotation` `:104`, `getRotationPivot` `:108`, `getRandom(BlockPos)` `:112`, `isIgnoreEntities` `:120`, `getBoundingBox` `:125`, `getKnownShape` `:129`, `getProcessors` `:133`, `shouldApplyWaterlogging` `:137`, `getRandomPalette(List<Palette>, BlockPos)` `:141`, `shouldFinalizeEntities` `:155`.
- Loading from disk: `StructureTemplateManager` `net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager.java:52`. Constructor `(ResourceManager, LevelStorageAccess, DataFixer, HolderGetter<Block>)` `:66-67`. `getOrCreate(ResourceLocation)` `:83`, `get(ResourceLocation)` `:94` (Optional), `readStructure(CompoundTag)` `:246`, `save(ResourceLocation)` `:253`.
  - `readStructure` runs the tag through `DataFixTypes.STRUCTURE.updateToCurrentVersion(...)` `:249` before `load`. A raw NBT read must do the same, or the version is not upgraded.

---

## 8. Design doc versus code

Read from `AI-PLAYER-DESIGN.md` and checked against the sources above.

1. Section 3.1 says a normal `ServerPlayer` "never moves itself; its position comes from the client's movement packets". This matches `handleMovePlayer` `ServerGamePacketListenerImpl.java:857` and the connection tick snap at `:261`. The brief's premise that "the server never calls aiStep-driven travel for players" is NOT what the code does: `aiStep` and `travel` run for every `ServerPlayer` whose `doTick()` runs (`LivingEntity.java:2432`, `:2760`; `Entity.java:3054`). What actually stops motion is the connection tick snap at `:256-261`, plus the input setter dropping non-passenger input (`ServerPlayer.java:1220-1232`).
2. Section 3.1 says the body must "run the living-entity movement step each tick, as Carpet does". In vanilla that step is reached only through `ServerPlayer.doTick()`, which only the connection tick calls (`ServerGamePacketListenerImpl.java:260`). `ServerPlayer.tick()` (`:495`) does not reach `LivingEntity.tick`. A body driven from `tick()` gets no physics step.
3. Section 3.1 says AIBot's subclass calls `super.tick()` plus `playerTick()` and "a missing travel() step" (section 2.1). The code explains the symptom: in a `ServerPlayer` subclass, `super.tick()` resolves to `ServerPlayer.tick()` (`:495`), which skips `LivingEntity.tick`. The AIBot source was not read; this is UNVERIFIED for AIBot.
4. Section 4.3 says villager trades are hard-coded Java and should be read "after the events fire". Confirmed (`VillagerTradingManager.java:58-74`, `:73`). The event sees only `TRADES`; `EXPERIMENTAL_TRADES` (TRADE_REBALANCE) is not passed through it, and `Villager.updateTrades` (`Villager.java:863`) prefers the experimental map when the feature flag is on. The design's trade index must cover both.
5. Section 4.3 says NeoForge global loot modifiers can be "run over a test context". They run only on the non-raw roll paths (`LootTable.java:124-127`). `getRandomItemsRaw` (`:80`, `:85`) bypasses them. A resolver built on the raw variant will miss modifiers.
6. Section 4.3 says the tier comes from `mineable/*` and `needs_*_tool` tags. At runtime the tier is a `Tool` component with `deniesDrops(INCORRECT_FOR_*_TOOL)` and `minesAndDrops(MINEABLE_WITH_*)` (`Tier.java:22-23`, `Tool.java:50-58`). The `NEEDS_*` tags are members of `INCORRECT_FOR_*` (datagen, `VanillaBlockTagsProvider.java:1587-1588`). The harvest check is also overridable by NeoForge (`EventHooks.java:219-224`).
7. Section 4.3 says the Y range comes from "placed-feature height providers". The bounds are private with no getters (`UniformHeight.java:24-25`, `TrapezoidHeight.java:24-26`, `HeightRangePlacement.java:19`). Only `sample(RandomSource, WorldGenerationContext)` is public.

---

## 9. Adjacent issues (near the change)

1. The connection tick undoes `doTick()` position changes (`ServerGamePacketListenerImpl.java:256-261`). A stub connection that does not refresh `firstGood*` snaps the body back to its pre-physics position each tick. Code-verified; runtime UNVERIFIED.
2. `ServerPlayer.setPlayerInput` silently discards input for non-passengers (`ServerPlayer.java:1220-1232`). Any path that drives a body through `handlePlayerInput` has no effect. The body must write `xxa`/`zza` (public, `LivingEntity.java:218`, `:220`) and call `setJumping` (`:2950`).
3. Break progress advances in `ServerPlayer.tick()` through `gameMode.tick()` `:496`, and movement advances in `doTick()`. Their order relative to each other on a tick was not verified.
4. Fishing bite state is private (`FishingHook.java:52`, `:56`, `:58`), and `FishHookState` is package-private (`:576`). Nothing public reports a bite. `retrieve()` (`:440`) awards loot when `nibble > 0`, so it cannot be used to probe.
5. The loot tree has no public accessors: `LootTable.pools` (`:49`), `LootPool.entries` (`:41`), `CompositeEntryBase.children` (`:13`), `LootItem.item` (`:23`), `TagEntry.tag` (`:28`), `NestedLootTable.contents` (`:29`), `DynamicLoot.name` (`:20`). Public routes: `LootTable.DIRECT_CODEC` (`:37`), `LootPoolEntries.CODEC` (`:10`), or `ItemListing.getOffer`/`LootTable.getRandomItems` for outputs.
6. `EnchantBookForEmeralds.getOffer` needs an `Entity` only for `level().registryAccess()` (`VillagerTrades.java:1522`). A stub entity in a live level is needed.
7. `MerchantMenu.RESULT_SLOT` is `protected` (`MerchantMenu.java:19`); use `getSlot(2)`.
8. `EXPERIMENTAL_TRADES` (`VillagerTrades.java:763`) is ignored by the NeoForge event and by any reader of `TRADES` alone. When TRADE_REBALANCE is on, a `TRADES`-only index is wrong for the librarian.
9. `AcquirePoi` declares `MIN_INTERVAL_INCREASE` and `MAX_INTERVAL_INCREASE` (`AcquirePoi.java:127-128`) and uses literal 40 instead (`:142`). Dead constants.
10. `StructureTemplate.palettes` has no getter (`StructureTemplate.java:66`), so `Palette` (public, `:790`) is unreachable without an access transformer.
11. The deprecated `Player.hasCorrectToolForDrops(BlockState)` (`Player.java:783-786`) gives the vanilla answer only. The position-sensitive form is what the game uses for harvest (`ServerPlayerGameMode.java:269`).
12. `Villager.updateTrades` (`Villager.java:861-876`) reads the live `VillagerTrades` maps. A resolver snapshot taken before `SERVER_DATA_LOAD` goes stale on reload.

---

## 10. Open questions (not answered from the repo)

1. Does `ServerPlayer` sit in `entityTickList` (`ServerLevel.java:1734-1735` adds on `onTickingStart`) so that `ServerPlayer.tick()` runs through the level? Not confirmed for players.
2. Exact branch of `handleMovePlayer` (`ServerGamePacketListenerImpl.java:857`) that updates `firstGood*` for accepted positions. Only the first 40 lines were read.
3. Which runs first on a tick, the connection tick (`doTick`) or the entity tick (`ServerPlayer.tick`). Needs a GameTest or a trace.
4. Whether `BiomeGenerationSettings.features()` index equals `GenerationStep.Decoration` ordinal (`BiomeGenerationSettings.java:85`).
5. `Villager.getOffers()` (defined on `AbstractVillager`) and the second argument of `addOffersFromItemListings(..., 2)` at `Villager.java` around `:873`.
6. Which `PoiType` the librarian holds (lectern). `VillagerProfession` librarian entry was not read.
7. `MerchantOffer.take(ItemStack, ItemStack)` signature, and whether `AbstractContainerMenu.clicked` on slot 2 reaches `MerchantResultSlot.onTake`.
8. Whether `doTick` after `LivingEntity.aiStep` on a `ServerPlayer` differs with `Player.aiStep` branches around `Player.java:548` (not read).
9. The name and accessor of the player's `FoodData` field (`Player.java:533` uses it).
10. AIBot's `ServerPlayer` subclass source (design sections 2.1 and 3.1). Not read; the claims about it are UNVERIFIED.
11. Whether the `ShapelessRecipe.ingredients` line (`:20`) and the `SmithingTransformRecipe` package-private field lines are exactly as cited. Read from grep, not from an open of those lines.
