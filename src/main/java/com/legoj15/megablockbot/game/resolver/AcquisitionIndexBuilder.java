package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.Source;
import com.legoj15.megablockbot.core.acquire.Source.OreRange;
import com.legoj15.megablockbot.game.resolver.LootEval.Route;
import com.legoj15.megablockbot.game.resolver.LootEval.Tool;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Builds the {@link AcquisitionIndex} of a running server (design 4.3). Server thread only; the result is immutable.
 * A table, feature or recipe that fails to read is skipped, counted and named in the index's notes: the build never
 * throws for bad data.
 */
public final class AcquisitionIndexBuilder {
    private static final Set<String> PLANNABLE_RECIPE_TYPES = Set.of(
        "minecraft:crafting", "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
        "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing");

    private static final String FISHING_TABLE = "minecraft:gameplay/fishing";

    // The loot table types the index reads; every other type is counted in the notes.
    private static final Set<String> READ_TABLE_TYPES = Set.of("minecraft:block", "minecraft:entity", "minecraft:chest", "minecraft:fishing");

    private final MinecraftServer server;
    private final BuildNotes notes = new BuildNotes();
    private final AcquisitionIndex.Builder index = AcquisitionIndex.builder();
    private final GameLootEnvironment environment;
    private final LootEval eval;
    // Recipes that report no result item (the special crafting types compute theirs from the ingredients).
    private int noResultRecipes;

    private AcquisitionIndexBuilder(MinecraftServer server) {
        this.server = server;
        this.environment = new GameLootEnvironment(server, notes);
        this.eval = new LootEval(environment, notes);
    }

    /** Builds a new index from the server's loaded data. Runs on the server thread. */
    public static AcquisitionIndex build(MinecraftServer server) {
        long start = System.nanoTime();
        AcquisitionIndex built = new AcquisitionIndexBuilder(server).run();
        long millis = (System.nanoTime() - start) / 1_000_000;
        MegaBlockBot.LOGGER.info("Acquisition index: {} items, {} with a source, sources by kind {}, {} notes, built in {} ms",
            built.items().size(), built.sourced().size(), built.sourceCountsByKind(), built.notes().size(), millis);
        return built;
    }

    private AcquisitionIndex run() {
        items();
        Map<String, OreRange> ores = section("ore ranges", () -> OreRanges.read(server, notes), Map.of());
        section("block loot", () -> blocks(ores));
        section("entity loot", this::entities);
        section("fishing", this::fishing);
        section("chest loot", this::chests);
        section("villager trades", () -> TradeSampler.sample(server, notes).forEach(index::addSource));
        section("recipes", this::recipes);
        standingNotes();
        notes.lines().forEach(index::note);
        return index.build();
    }

    // Each section is isolated, so a failure in one costs that source kind and nothing else.
    private void section(String name, Runnable body) {
        section(name, () -> {
            body.run();
            return null;
        }, null);
    }

    private <T> T section(String name, java.util.function.Supplier<T> body, T fallback) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            MegaBlockBot.LOGGER.error("Acquisition index section '{}' failed", name, e);
            notes.count("section " + name + " failed (" + e.getClass().getSimpleName() + "), its sources are missing");
            return fallback;
        }
    }

    // ---- items and recipes -------------------------------------------------------------------------------------

    private void items() {
        var features = server.getWorldData().enabledFeatures();
        for (Item item : BuiltInRegistries.ITEM) {
            // Air is a placeholder, and an item behind a feature flag the server did not enable cannot exist.
            if (item == Items.AIR || !item.isEnabled(features)) continue;
            index.addItem(ItemKey.of(BuiltInRegistries.ITEM.getKey(item).toString()));
        }
    }

    private void recipes() {
        RegistryAccess access = server.registryAccess();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            ResourceLocation typeKey = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String type = typeKey == null ? "(unregistered recipe type)" : typeKey.toString();
            if (!PLANNABLE_RECIPE_TYPES.contains(type)) index.countUnplannableRecipe(type);
            try {
                ItemStack result = recipe.getResultItem(access);
                if (result.isEmpty()) noResultRecipes++;
                else index.addCraftable(ItemKey.of(BuiltInRegistries.ITEM.getKey(result.getItem()).toString()));
            } catch (RuntimeException e) {
                notes.skipped("recipes whose result could not be read", holder.id().toString());
            }
        }
    }

    // ---- blocks ------------------------------------------------------------------------------------------------

    // Many blocks share a table (drops_like), and the evaluation is per table and per state rule.
    private record TableRule(String table, LootEval.StateRule rule) {}

    private void blocks(Map<String, OreRange> ores) {
        Map<TableRule, List<Route>> routesByTable = new HashMap<>();
        List<Block> blocks = new ArrayList<>();
        BuiltInRegistries.BLOCK.forEach(blocks::add);
        blocks.sort(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));

        for (Block block : blocks) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            // A harvested growable is read in the state that yields the most (the ripe one: a harvest waits for it);
            // a mined block in its default state, the one it is placed in, when that yields the item at all.
            boolean growable = isGrowable(block);
            LootEval.StateRule rule = growable ? LootEval.StateRule.HARVESTED : LootEval.StateRule.MINED;
            try {
                // Both lookups run inside this guard: a registry or codec that throws for one block's table costs that
                // block, not the whole section.
                String table = block.getLootTable().location().toString();
                List<Route> routes = routesByTable.computeIfAbsent(new TableRule(table, rule),
                    k -> environment.table(k.table()) == null ? List.of()
                        : eval.routes(k.table(), EnumSet.allOf(Tool.class), false, k.rule()));
                if (routes.isEmpty()) continue;

                BlockState state = block.defaultBlockState();
                boolean toolRequired = state.requiresCorrectToolForDrops();
                ToolClass toolClass = toolClassOf(state);
                ToolTier tier = toolClass == null && !toolRequired ? ToolTier.HAND : tierOf(state);

                for (Route route : routes) {
                    ItemKey item = ItemKey.of(route.item());
                    if (growable) {
                        if (route.tool() != Tool.PLAIN) continue; // the silk-touch form of a crop is not a harvest
                        index.addSource(new Source.Harvest("harvest:" + blockId, item, blockId, plantOf(block), true,
                            route.expected(), route.random()));
                        continue;
                    }
                    boolean silk = route.tool() == Tool.SILK;
                    boolean shears = route.tool() == Tool.SHEARS;
                    String id = "mine:" + blockId + (silk ? "#silk_touch" : shears ? "#shears" : "");
                    index.addSource(new Source.MineBlock(id, item, blockId,
                        shears ? ToolClass.SHEARS : toolClass, shears ? ToolTier.HAND : tier, toolRequired, silk, shears,
                        route.expected(), route.random(), ores.get(blockId)));
                }
            } catch (RuntimeException e) {
                notes.skipped("blocks whose loot table could not be read", blockId + " (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private static ToolClass toolClassOf(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return ToolClass.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return ToolClass.AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return ToolClass.SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return ToolClass.HOE;
        return null;
    }

    // The lowest vanilla tier whose incorrect_for tag does not hold the block. These tags, not the needs_* ones, are
    // what the game checks. Gold shares wood's tag in vanilla and so is the same tier.
    private static ToolTier tierOf(BlockState state) {
        if (!state.is(BlockTags.INCORRECT_FOR_WOODEN_TOOL)) return ToolTier.WOOD;
        if (!state.is(BlockTags.INCORRECT_FOR_STONE_TOOL)) return ToolTier.STONE;
        if (!state.is(BlockTags.INCORRECT_FOR_IRON_TOOL)) return ToolTier.IRON;
        if (!state.is(BlockTags.INCORRECT_FOR_DIAMOND_TOOL)) return ToolTier.DIAMOND;
        return ToolTier.NETHERITE;
    }

    // Crops and the other plants a player replants. Trees grown from saplings are out of scope, and melons and
    // pumpkins have no class of their own in 1.21.1, so they stay ordinary blocks.
    private static boolean isGrowable(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock
            || block instanceof SweetBerryBushBlock || block instanceof CocoaBlock || block instanceof NetherWartBlock
            || block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock || block instanceof SugarCaneBlock
            || block instanceof CactusBlock || block instanceof KelpBlock || block instanceof KelpPlantBlock
            || block instanceof PitcherCropBlock;
    }

    // What the player plants: the block's own item, or else what pick-block gives (the seed of a crop).
    private ItemKey plantOf(Block block) {
        Item own = block.asItem();
        if (own != Items.AIR) return ItemKey.of(BuiltInRegistries.ITEM.getKey(own).toString());
        try {
            ItemStack clone = block.getCloneItemStack(server.overworld(), BlockPos.ZERO, block.defaultBlockState());
            if (!clone.isEmpty()) return ItemKey.of(BuiltInRegistries.ITEM.getKey(clone.getItem()).toString());
        } catch (RuntimeException e) {
            notes.count("growable blocks whose plant item could not be found");
        }
        return null;
    }

    // ---- entities, fishing, chests -----------------------------------------------------------------------------

    private void entities() {
        List<EntityType<?>> types = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.forEach(types::add);
        types.sort(Comparator.comparing(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        for (EntityType<?> type : types) {
            if (!isMob(type)) continue;
            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            String table = type.getDefaultLootTable().location().toString();
            if (environment.table(table) == null) continue;
            for (Route route : eval.routes(table, EnumSet.of(Tool.PLAIN), true, LootEval.StateRule.MINED)) {
                index.addSource(new Source.KillMob("kill:" + entityId, ItemKey.of(route.item()), entityId, route.byPlayer(),
                    route.expected(), route.random()));
            }
        }
    }

    // A kill source is something a player fights. Only living entities have attributes (the game cannot spawn one
    // that lacks them, modded ones included), but a player and an armor stand are living entities and no mobs.
    private static boolean isMob(EntityType<?> type) {
        return type != EntityType.PLAYER && type != EntityType.ARMOR_STAND && DefaultAttributes.hasSupplier(type);
    }

    private void fishing() {
        if (environment.table(FISHING_TABLE) == null) {
            notes.count("the fishing loot table is missing, fishing sources not indexed");
            return;
        }
        // The per-cast chance of each item comes out of the weights of the table and the tables it nests.
        for (Route route : eval.routes(FISHING_TABLE, EnumSet.of(Tool.PLAIN), false, LootEval.StateRule.MINED)) {
            index.addSource(new Source.Fishing("fish:" + FISHING_TABLE, ItemKey.of(route.item()), FISHING_TABLE, route.expected()));
        }
    }

    private void chests() {
        for (String table : environment.tableIds()) {
            if (!"minecraft:chest".equals(environment.type(table))) continue;
            for (Route route : eval.routes(table, EnumSet.of(Tool.PLAIN), false, LootEval.StateRule.MINED)) {
                index.addSource(new Source.ChestLoot("chest:" + table, ItemKey.of(route.item()), table, route.expected()));
            }
        }
    }

    // ---- notes -------------------------------------------------------------------------------------------------

    private void standingNotes() {
        Map<String, Integer> otherTypes = new TreeMap<>();
        for (String table : environment.tableIds()) {
            String type = environment.type(table);
            if (type != null && !READ_TABLE_TYPES.contains(type) && !type.equals("minecraft:generic")) otherTypes.merge(type, 1, Integer::sum);
        }
        if (!otherTypes.isEmpty()) {
            StringBuilder line = new StringBuilder("loot tables of other types are not indexed, so items only they yield show as unobtainable:");
            otherTypes.forEach((type, count) -> line.append(' ').append(type).append(" x").append(count));
            notes.always(line.toString());
        }
        notes.always("global loot modifiers are not evaluated (" + globalLootModifierCount() + " registered)");
        notes.always("drops a mob's code adds outside its loot table (a wither's nether star) are not seen");
        notes.always("the yield of a block that is mined is that of its default state, the one it is placed in (one slab, one candle); an item that state does not yield at all is read in the state that yields the most of it (glow lichen on every face); the yield of a harvested growable is that of the state a player waits for, the grown one (a ripe carrot, a full cocoa pod)");
        notes.always("a block drops its loot-table items whether or not it can exist in the world, so a block that never generates still counts as a source of its own item");
        notes.always("recipes of special crafting types (armor dyeing, firework rockets, tipped arrows, ...) report no result item ("
            + noResultRecipes + " loaded), so what only they make is not in craftable() and shows as unobtainable");
        if (server.getWorldData().enabledFeatures().contains(FeatureFlags.TRADE_REBALANCE)) {
            notes.always("the trade_rebalance feature is on: villagers and the wandering trader use the experimental per-biome trade lists, which are not sampled; the trades listed are those of the default lists");
        }
        notes.always("villager trades are sampled " + TradeSampler.SAMPLES + " times per listing with a fixed seed: a result rarer than about 1 in 20 can be missed, and chance ignores which of a level's listings a villager rolled");
    }

    // The layered list NeoForge reads from neoforge:loot_modifiers/global_loot_modifiers.json: a pack may replace the
    // list or append to it. Read from the resource manager because the loader keeps the parsed manager package-private.
    private int globalLootModifierCount() {
        ResourceLocation list = ResourceLocation.fromNamespaceAndPath("neoforge", "loot_modifiers/global_loot_modifiers.json");
        List<String> entries = new ArrayList<>();
        try {
            for (Resource resource : server.getResourceManager().getResourceStack(list)) {
                try (Reader reader = resource.openAsReader()) {
                    JsonObject json = GsonHelper.parse(reader);
                    if (GsonHelper.getAsBoolean(json, "replace", false)) entries.clear();
                    for (JsonElement entry : GsonHelper.getAsJsonArray(json, "entries")) {
                        entries.remove(entry.getAsString());
                        entries.add(entry.getAsString());
                    }
                }
            }
        } catch (Exception e) {
            notes.count("the global loot modifier list could not be read");
        }
        return entries.size();
    }

    // ---- loot tables of the running server ---------------------------------------------------------------------

    /** Hands the evaluator the server's loot tables as codec-written JSON, and its item tags. */
    private static final class GameLootEnvironment implements LootEval.Environment {
        private final MinecraftServer server;
        private final BuildNotes notes;
        private final DynamicOps<JsonElement> ops;
        private final Map<String, Optional<JsonObject>> tables = new HashMap<>();

        GameLootEnvironment(MinecraftServer server, BuildNotes notes) {
            this.server = server;
            this.notes = notes;
            this.ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        }

        @Override
        public JsonObject table(String id) {
            return tables.computeIfAbsent(id, this::encode).orElse(null);
        }

        private Optional<JsonObject> encode(String id) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) return Optional.empty();
            LootTable table = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, location));
            if (table == LootTable.EMPTY) return Optional.empty(); // absent, or deliberately empty (noLootTable)
            try {
                // The table's fields are private, but its codec writes the datapack format, which is stable.
                Optional<JsonElement> json = LootTable.DIRECT_CODEC.encodeStart(ops, table).result();
                if (json.isPresent() && json.get().isJsonObject()) return Optional.of(json.get().getAsJsonObject());
            } catch (RuntimeException e) {
                // falls through to the note
            }
            notes.skipped("loot tables that failed to encode", id);
            return Optional.empty();
        }

        /** Every loot table id the server has, sorted. */
        List<String> tableIds() {
            List<String> ids = new ArrayList<>();
            for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) ids.add(id.toString());
            ids.sort(Comparator.naturalOrder());
            return ids;
        }

        /** The table's {@code type}; a table that writes none is the generic type. Null when the table is unreadable. */
        String type(String id) {
            JsonObject json = table(id);
            if (json == null) return null;
            String type = LootEval.string(json, "type");
            return type.isEmpty() ? "minecraft:generic" : type;
        }

        @Override
        public List<String> stateValues(String blockId, String property) {
            ResourceLocation location = ResourceLocation.tryParse(blockId);
            if (location == null) return List.of();
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(location);
            if (block.isEmpty()) return List.of();
            Property<?> found = block.get().getStateDefinition().getProperty(property);
            return found == null ? List.of() : valueNames(found);
        }

        private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
            List<String> names = new ArrayList<>();
            for (T value : property.getPossibleValues()) names.add(property.getName(value));
            return names;
        }

        @Override
        public String defaultStateValue(String blockId, String property) {
            ResourceLocation location = ResourceLocation.tryParse(blockId);
            if (location == null) return null;
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(location);
            if (block.isEmpty()) return null;
            Property<?> found = block.get().getStateDefinition().getProperty(property);
            return found == null ? null : defaultName(block.get().defaultBlockState(), found);
        }

        private static <T extends Comparable<T>> String defaultName(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }

        @Override
        public List<String> tagItems(String tagId) {
            ResourceLocation location = ResourceLocation.tryParse(tagId);
            if (location == null) return List.of();
            List<String> items = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, location))) {
                items.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
            }
            return items;
        }
    }
}
