package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.Delivery;
import com.legoj15.megablockbot.core.snapshot.NotedOffer;
import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.snapshot.StationSight;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.build.Blueprint;
import com.legoj15.megablockbot.game.build.Blueprints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads one body into the immutable {@link Snapshot} the planner and the postconditions work from (design 3.4). Server
 * thread only, and read-only: it never opens a container, moves an item, loads a chunk or changes any state. What the
 * world cannot say (which table the bot placed, what was in a chest it left) comes from the body's {@link BotMemory}.
 *
 * <p>How often it runs: the executor takes a snapshot at every node boundary (a pre-check, then the goal), at every
 * plan request, when a plan is adopted and when a held body is released, and a command takes one to say what it sees;
 * none while a task is running. Most of what it reads is cheap. The sight of the blocks around the body is not, so
 * {@link BodySight} keeps its scan per body and takes a new one only when it is old or the body has moved.
 */
public final class SnapshotCollector {
    /** How far from the body, in blocks, a station is looked for. */
    public static final int STATION_SCAN_RADIUS = 8;
    /**
     * How old a noted villager offer may be and still reach the planner: three in-game days. A villager's offers
     * change when it restocks (twice a day) and are rerolled when it loses its profession, so a note older than a few
     * days says nothing; a fresh note is the best price the body has.
     */
    public static final long OFFER_AGE_TICKS = 3L * 24L * 1000L;

    // Stations the bot knows without any recipe naming them: the vanilla workstations. A recipe in the book that names
    // another item as its station adds it (stationBlocks below), which is how a mod's machine becomes visible.
    private static final List<ItemKey> VANILLA_STATIONS = List.of(
        ItemKey.of("minecraft:crafting_table"), ItemKey.of("minecraft:furnace"), ItemKey.of("minecraft:blast_furnace"),
        ItemKey.of("minecraft:smoker"), ItemKey.of("minecraft:stonecutter"), ItemKey.of("minecraft:smithing_table"),
        ItemKey.of("minecraft:anvil"));

    /** The blocks that are stations, derived from {@code basis}; rebuilt only when a new book is published. */
    private record StationTable(Set<ItemKey> basis, Map<Block, ItemKey> byBlock) {}

    private static volatile StationTable stationTable = new StationTable(null, Map.of());

    private SnapshotCollector() {}

    /** Collects with the body's own memory. */
    public static Snapshot collect(BotPlayer body) {
        return collect(body, BotMemory.of(body.getUUID()));
    }

    public static Snapshot collect(BotPlayer body, BotMemory memory) {
        return collect(body, memory, List.of());
    }

    /**
     * Collects for {@code wants}: the goals a snapshot is being taken for -- the goal being planned or judged, and the
     * first build goal queued behind it (the caller decides what it wants; see {@code BotRuntime.snapshot}). A
     * {@link Goal.Build} among them gets its structure report; anything a strategy declares it still needs is not
     * collected here.
     */
    public static Snapshot collect(BotPlayer body, BotMemory memory, List<Goal> wants) {
        MinecraftServer server = body.getServer();
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("a snapshot is collected on the server thread, not " + Thread.currentThread().getName());
        }
        ServerLevel level = body.serverLevel();
        String dimension = level.dimension().location().toString();
        BlockPos at = body.blockPosition();

        Snapshot.Builder builder = Snapshot.builder()
            .gameTime(level.getGameTime())
            .dimension(dimension)
            .pos(pos(at))
            .health(body.getHealth())
            .hunger(body.getFoodData().getFoodLevel());

        inventory(body, builder);
        equipment(body, builder);
        stations(level, dimension, at, memory, builder);
        containers(level, dimension, at, memory, builder);
        offers(level, dimension, memory, builder);
        BodySight.fill(level, dimension, at, memory, builder);
        addGoalFacts(level, dimension, wants, builder);
        deliveries(memory, wants, builder);
        return builder.build();
    }

    /**
     * The deliveries a {@link Goal.Deliver} among {@code wants} may count: the notes at or after the earliest time
     * such a goal was given (issue #19). Nothing when no deliver goal is being planned or judged, so a snapshot for
     * any other goal costs nothing here; the notes before that time belong to an earlier goal and are not carried.
     */
    private static void deliveries(BotMemory memory, List<Goal> wants, Snapshot.Builder builder) {
        long earliest = Long.MAX_VALUE;
        for (Goal goal : wants) {
            if (goal instanceof Goal.Deliver deliver) earliest = Math.min(earliest, deliver.since());
        }
        if (earliest == Long.MAX_VALUE) return;
        for (Delivery delivery : memory.deliveries()) {
            if (delivery.gameTime() >= earliest) builder.delivery(delivery);
        }
    }

    // ---- villager offers ---------------------------------------------------------------------------------------

    /**
     * The offers the body noted in trade windows it opened, as fresh notes of villagers that are still in this
     * dimension: a note older than {@link #OFFER_AGE_TICKS} or of a villager that is gone is dropped, exactly as a
     * note of a container that no longer stands is withheld. Nothing is read of the villager itself — a player cannot
     * see a villager's offers without opening its window, and the note is what the body saw when it did.
     */
    private static void offers(ServerLevel level, String dimension, BotMemory memory, Snapshot.Builder builder) {
        long now = level.getGameTime();
        for (BotMemory.VillagerOffers noted : memory.notedOffers()) {
            if (!noted.dimension().equals(dimension)) continue;
            // Gone means not in the world where it was noted and that place is loaded. A villager in chunks that
            // are not loaded is only out of sight: its note stays, or a plan made away from the village would
            // forget the price it went to gather and the villager it means to pay.
            if (level.getEntity(noted.villager()) == null && level.isLoaded(noted.pos())) continue;
            for (NotedOffer offer : noted.offers()) {
                if (now - offer.gameTime() > OFFER_AGE_TICKS) continue;
                builder.offer(offer);
            }
        }
    }

    /**
     * The structure report of every build goal among {@code wants}, under the key its postcondition reads
     * ({@code blueprint@anchor}): how many of the blueprint's positions are required, how many hold the blueprint's
     * block already, and what is missing as the items that place the still-wrong blocks (issue 17). The report is read
     * from the world -- never from a note, since a player can see blocks that stand -- and an unknown blueprint name
     * gives no report at all, so the planner says the goal cannot be planned rather than planning a guess. Each report
     * stats the blueprint's file and walks its footprint, so a caller passes the goal being planned or judged and at
     * most the first build goal queued behind it, never the whole queue.
     */
    private static void addGoalFacts(ServerLevel level, String dimension, List<Goal> wants, Snapshot.Builder builder) {
        for (Goal goal : wants) {
            if (!(goal instanceof Goal.Build build)) continue;
            Blueprint blueprint = Blueprints.load(level.getServer(), build.blueprint());
            if (blueprint == null) continue;
            builder.structure(build.blueprint() + "@" + build.anchor().encode(),
                blueprint.report(level, build.dimension(), dimension, new BlockPos(build.anchor().x(),
                    build.anchor().y(), build.anchor().z())));
        }
    }

    // ---- inventory ---------------------------------------------------------------------------------------------

    private static void inventory(BotPlayer body, Snapshot.Builder builder) {
        Inventory inventory = body.getInventory();
        int foodUnits = 0;
        // getContainerSize covers the main slots, the armour and the offhand, in that order.
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            ItemKey key = ItemKeys.of(stack.getItem());
            builder.item(key, stack.getCount());
            builder.facts(key, factsOf(stack));

            for (ToolClass toolClass : ToolFacts.classesOf(stack)) {
                // Shears have no mining tier of their own: the resolver asks for them at HAND (Source.MineBlock).
                builder.tool(toolClass, toolClass == ToolClass.SHEARS ? ToolTier.HAND : ToolFacts.tierOf(stack));
            }

            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) foodUnits += food.nutrition() * stack.getCount();
        }
        builder.foodUnits(foodUnits);
    }

    private static void equipment(BotPlayer body, Snapshot.Builder builder) {
        equip(builder, EquipSlot.HEAD, body.getItemBySlot(EquipmentSlot.HEAD));
        equip(builder, EquipSlot.CHEST, body.getItemBySlot(EquipmentSlot.CHEST));
        equip(builder, EquipSlot.LEGS, body.getItemBySlot(EquipmentSlot.LEGS));
        equip(builder, EquipSlot.FEET, body.getItemBySlot(EquipmentSlot.FEET));
        equip(builder, EquipSlot.MAINHAND, body.getItemBySlot(EquipmentSlot.MAINHAND));
        equip(builder, EquipSlot.OFFHAND, body.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    private static void equip(Snapshot.Builder builder, EquipSlot slot, ItemStack stack) {
        if (!stack.isEmpty()) builder.equip(slot, ItemKeys.of(stack.getItem()));
    }

    /** The component facts of a stack, as the snapshot reads every carried stack and every noted offer's result. */
    public static StackFacts factsOf(ItemStack stack) {
        Map<String, Integer> enchantments = new TreeMap<>();
        // A book keeps its enchantments apart from the ones it would apply, and the check does not care which.
        mergeEnchantments(enchantments, stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        mergeEnchantments(enchantments, stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));

        String potion = "";
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents != null) {
            potion = contents.potion().flatMap(Holder::unwrapKey).map(k -> k.location().toString()).orElse("");
        }
        return new StackFacts(stack.getCount(), enchantments, potion, Map.of());
    }

    private static void mergeEnchantments(Map<String, Integer> into, ItemEnchantments enchantments) {
        for (var entry : enchantments.entrySet()) {
            // An enchantment that is not in the registry has no id to report.
            entry.getKey().unwrapKey().ifPresent(k -> into.merge(k.location().toString(), entry.getIntValue(), Math::max));
        }
    }

    // ---- stations ----------------------------------------------------------------------------------------------

    private static void stations(ServerLevel level, String dimension, BlockPos at, BotMemory memory, Snapshot.Builder builder) {
        Map<Block, ItemKey> stationBlocks = stationBlocks();
        int radius = STATION_SCAN_RADIUS;
        List<StationSight> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    cursor.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    // getBlockState on an unloaded chunk would load it; a snapshot never changes what is loaded.
                    if (!level.hasChunkAt(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    ItemKey station = stationBlocks.get(state.getBlock());
                    if (station == null) continue;
                    found.add(new StationSight(station, pos(cursor), memory.isPlaced(dimension, cursor)));
                }
            }
        }
        // Nearest first, ties by position, so equal worlds give equal snapshots.
        Pos origin = pos(at);
        found.sort(Comparator.<StationSight>comparingLong(s -> s.pos().distSq(origin))
            .thenComparingInt(s -> s.pos().x()).thenComparingInt(s -> s.pos().y()).thenComparingInt(s -> s.pos().z()));
        found.forEach(builder::station);
    }

    private static Map<Block, ItemKey> stationBlocks() {
        Set<ItemKey> named = RecipeBooks.stations();
        StationTable cached = stationTable;
        if (cached.basis() == named) return cached.byBlock();

        Set<ItemKey> all = new LinkedHashSet<>(VANILLA_STATIONS);
        all.addAll(named);
        Map<Block, ItemKey> byBlock = new HashMap<>();
        for (ItemKey station : all) {
            ResourceLocation id = ResourceLocation.tryParse(station.id());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            // A station that is not a block (a recipe that names a tool, say) cannot stand in the world.
            if (item instanceof BlockItem blockItem) byBlock.put(blockItem.getBlock(), station);
        }
        // A chipped or damaged anvil is still an anvil to every recipe that names one.
        ItemKey anvil = ItemKey.of("minecraft:anvil");
        byBlock.put(Blocks.CHIPPED_ANVIL, anvil);
        byBlock.put(Blocks.DAMAGED_ANVIL, anvil);

        stationTable = new StationTable(named, Map.copyOf(byBlock));
        return stationTable.byBlock();
    }

    // ---- containers --------------------------------------------------------------------------------------------

    /**
     * Only what the body's memory knows: a body cannot see into a closed container, so the contents of every sight are a
     * note the body made when it opened it, never read from the world, and they stay {@link ContainerSight#remembered
     * remembered} until it opens the container again. A container is {@code contentsKnown} when it is within the scan
     * radius, its chunk is loaded and the block that stands there is the one recorded (a note is then not out of reach,
     * though an owner who opened the chest since has made it stale). Otherwise the contents are withheld (an empty map) so
     * that nothing reads old memory as the current state: the postcondition answers UNKNOWN and the executor walks over.
     *
     * <p>A container the body may take from (one it placed, or one its owner named: design 3.5) is shown to the planner
     * even when it is out of sight: its contents are then the remembered ones, because the plan walks over and counts on
     * what the body last saw there. Only a container whose chunk is loaded and which no longer stands as recorded (the
     * owner's word and the note are each held to their block, both blocks for a double chest: {@link SupplyChests#stand})
     * is treated as gone, whatever the distance. Each inventory is one sight, a double chest included (a note and an
     * owner's word are each per inventory, so the pair is listed once, at the half with the lower position hash, with the
     * other half in the sight). A container the owner named and the body has not opened is listed with nothing in it and
     * not {@code remembered}: it is there and may be taken from, and what it holds is unknown, which the planner counts as
     * nothing.
     */
    private static void containers(ServerLevel level, String dimension, BlockPos at, BotMemory memory, Snapshot.Builder builder) {
        long radiusSq = (long) STATION_SCAN_RADIUS * STATION_SCAN_RADIUS;
        Set<BlockPos> listed = new HashSet<>();
        for (BotMemory.RememberedContainer container : memory.containers()) {
            if (!container.dimension().equals(dimension)) continue; // a Pos carries no dimension, so another one's cannot be shown
            BlockPos pos = container.pos();
            BlockPos other = container.other();
            Pos otherPos = other == null ? null : pos(other);
            boolean placed = memory.isPlaced(dimension, pos);
            boolean named = memory.isSupply(dimension, pos) || (other != null && memory.isSupply(dimension, other));
            boolean inRange = pos(pos).distSq(pos(at)) <= radiusSq;
            SupplyChests.Stand stand = SupplyChests.stand(level, memory, pos);
            boolean stillThere = inRange && stand == SupplyChests.Stand.STANDS;
            listed.add(pos);
            if (other != null) listed.add(other);
            if (stillThere) {
                builder.container(new ContainerSight(pos(pos), otherPos, container.block(), true, placed, placed || named, true,
                    container.contents()));
            } else if ((placed || named) && stand != SupplyChests.Stand.CHANGED) {
                // Out of sight (or in range with its chunk not loaded): what the body remembers, marked as such.
                builder.container(new ContainerSight(pos(pos), otherPos, container.block(), false, placed, true, true, container.contents()));
            } else {
                builder.container(new ContainerSight(pos(pos), otherPos, container.block(), false, placed, false, false, Map.of()));
            }
        }
        for (BotMemory.SupplyContainer entry : memory.supply()) {
            if (!entry.dimension().equals(dimension) || listed.contains(entry.pos())
                || (entry.other() != null && listed.contains(entry.other()))) continue;
            // The owner's word is of a block, as a note is: named and never opened, the container is one the body may
            // take from only while it still stands as named. A position that is not loaded is not decided against it,
            // as in the loop above.
            builder.container(new ContainerSight(pos(entry.pos()), entry.other() == null ? null : pos(entry.other()), entry.block(), false,
                memory.isPlaced(dimension, entry.pos()),
                SupplyChests.stand(level, entry.pos(), entry) != SupplyChests.Stand.CHANGED, false, Map.of()));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static Pos pos(BlockPos pos) {
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
