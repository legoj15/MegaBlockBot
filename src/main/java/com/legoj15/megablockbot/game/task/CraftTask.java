package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.task.AbstractTask;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import com.legoj15.megablockbot.game.safety.BlockSafety;
import com.legoj15.megablockbot.game.safety.Verdict;
import com.legoj15.megablockbot.nav.engine.api.entity.IInteractionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code craft} task (design 4.2, {@link RecipeDef#CRAFT}): runs one crafting recipe {@code times} times the way a
 * player does, through the real menus with slot clicks, one craft every few ticks.
 *
 * <p>Leaf params: {@code recipe} (the game recipe's id), {@code times}, {@code station} (the item that must be at hand,
 * or empty) and optionally {@code layout} ({@link CraftLayouts}). With a layout the task puts the items the planner chose
 * in the slots and never re-decides; without one each slot takes the first alternative, in the recipe's own order, that
 * the body carries. The grid positions come from the game's recipe, found again by id: a shaped recipe's shape is
 * not in the planner's book.
 *
 * <p><b>Stations.</b> No station: the inventory's own 2x2 grid. A crafting table: one within reach is used where it
 * stands; otherwise the table item is placed on a free spot beside the body through the player's game mode, used, and
 * broken again as a player breaks it (start, wait out the break time, finish), and the task waits for the item to come
 * back. A table the body did not place is left standing. What the task placed is in the body's {@link BotMemory}, which
 * is saved with the brain, so a task started after a restart finds the table it left and takes it up.
 *
 * <p><b>Exits.</b> The cursor and the grid are empty on every exit: after each craft, on a failure, on pause and on abort.
 * A paused or aborted task closes the table's menu and stops breaking; the table it placed stays, remembered.
 *
 * <p>Failures: {@code MissingInput} when an ingredient is not carried, {@code TargetGone} for a recipe id the game does
 * not have, {@code Protected} when every spot for the table is one the body may not break again, {@code Unreachable}
 * when there is no free spot, {@code TimedOut} when the table never comes back; the executor adds its own budget.
 */
public final class CraftTask extends AbstractTask<BotPlayer> {
    public static final String KIND = RecipeDef.CRAFT;

    /** Ticks between two crafts: a few, like a player clicking through them. */
    static final int CRAFT_PERIOD_TICKS = 3;
    /** How far from the body a standing table is looked for; reach decides whether it is used. */
    static final int TABLE_SEARCH_RADIUS = 5;
    /** Ticks the broken table's item may take to reach the inventory (it has a pickup delay of its own). */
    static final int PICKUP_WAIT_TICKS = 100;
    /**
     * Ticks after the break before the body steps toward a drop that did not reach it: the item has a pickup delay of 10
     * ticks and hops about on its own for a few more, and most of the time that ends inside the body's pickup box.
     */
    static final int PICKUP_PATIENCE_TICKS = 20;
    /** How far from the body a drop is looked for. A body steps toward it by plain input, the way a player walks over. */
    static final double DROP_SEARCH_RADIUS = 4.0;
    /** Ticks a break may take before the task gives it up; a table by hand takes about 75. */
    static final int BREAK_WAIT_TICKS = 400;
    /** How many times the game may refuse to open or start breaking before the task stops asking. */
    static final int REFUSALS_ALLOWED = 3;

    private static final ItemKey CRAFTING_TABLE = ItemKey.of("minecraft:crafting_table");

    private enum Phase { PREPARE, OPEN, CRAFT, BREAK, PICKUP }

    /** One ingredient position: where it goes in the grid and what the recipe accepts there. */
    private record Cell(int gridIndex, Ingredient ingredient) {}

    /** What goes into one cell for one craft. */
    private record Placement(Cell cell, Item item) {}

    private final NodeSpec leaf;
    private final Map<String, String> hints;
    /** True for the task made by {@link #takeUp}: it crafts nothing and only takes a table this body left standing. */
    private final boolean takeUpOnly;

    // Read from the leaf and the game in onStart.
    private CraftingRecipe recipe;
    private boolean needsTable;
    private int gridWidth;
    private List<Cell> cells;
    private Item resultItem;
    private int resultCount;
    private Item tableItem;
    private int remaining;
    /** The planner's items for the crafts still to do, one list per craft in slot order; empty without a layout. */
    private List<List<ItemKey>> layout = List.of();

    private Phase phase = Phase.PREPARE;
    private int cooldown;
    private int phaseTicks;
    private int refusals;
    private int sequence;
    @Nullable
    private BlockPos table;
    private boolean placedHere;
    private int tablesBefore;
    /** The body is walking toward the table's drop on this task's input. */
    private boolean walking;

    public CraftTask(NodeSpec leaf, Map<String, String> hints) {
        this.leaf = leaf;
        this.hints = hints;
        this.takeUpOnly = false;
    }

    private CraftTask(BlockPos standing) {
        this.leaf = null;
        this.hints = Map.of();
        this.takeUpOnly = true;
        this.table = standing;
    }

    /**
     * A task that crafts nothing and takes up the crafting table at {@code standing}, which this body put down and was
     * stopped before it could take back (a restart, a cancel, a failure): the planner counts the table in the inventory,
     * not in the world, so a table left standing would cost a goal its planks. It is the same break and pickup the craft
     * ends with, so it ends the same way: the item back in the inventory, or a failure saying why not.
     */
    public static Task<BotPlayer> takeUp(BlockPos standing) {
        return new CraftTask(standing.immutable());
    }

    /**
     * The nearest crafting table within reach that this body's memory says it placed, or null. What {@link #takeUp} is
     * for: a runtime asks after a restart and when a goal ends.
     */
    @Nullable
    public static BlockPos leftoverTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BotMemory memory = BotMemory.of(body.getUUID());
        String dimension = dimension(level);
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS),
                centre.offset(TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            if (!memory.isPlaced(dimension, pos) || !body.canInteractWithBlock(pos, 0.0)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart(BotPlayer body) {
        if (takeUpOnly) {
            needsTable = true;
            tableItem = Items.CRAFTING_TABLE;
            remaining = 0;
            return;
        }
        Map<String, String> params = leaf.params();
        String recipeId = params.getOrDefault("recipe", "");
        ServerLevel level = body.serverLevel();
        MinecraftServer server = body.getServer();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        Optional<RecipeHolder<?>> holder = id == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(id);
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe found)) {
            fail(new Failure.TargetGone(recipeId, "the game has no crafting recipe '" + recipeId + "'"));
            return;
        }
        recipe = found;
        ItemStack result = recipe.getResultItem(level.registryAccess());
        resultItem = result.getItem();
        resultCount = Math.max(1, result.getCount());

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the craft leaf's times is not a number: " + params.get("times")));
            return;
        }

        String station = params.getOrDefault("station", "");
        needsTable = !station.isEmpty();
        if (needsTable) {
            if (!ItemKey.of(station).equals(CRAFTING_TABLE)) {
                fail(new Failure.Internal(null, "the craft task can only use a crafting table as a station, not " + station));
                return;
            }
            tableItem = Items.CRAFTING_TABLE;
        }
        gridWidth = needsTable ? 3 : 2;
        try {
            cells = cellsOf(recipe, gridWidth);
        } catch (IllegalArgumentException e) {
            fail(new Failure.Internal(null, e.getMessage()));
            return;
        }

        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some crafts
        // already done) makes only what is still missing.
        int needed = times;
        if (leaf.goal() instanceof Goal.HaveItem have) {
            int missing = have.count() - body.getInventory().countItem(resultItem);
            needed = Math.floorDiv(missing + resultCount - 1, resultCount);
        }
        remaining = Math.max(0, Math.min(times, needed));

        List<List<ItemKey>> perCraft;
        try {
            perCraft = CraftLayouts.parse(params.get("layout"));
        } catch (IllegalArgumentException e) {
            fail(new Failure.Internal(e, "the craft leaf's layout is malformed: " + e.getMessage()));
            return;
        }
        if (!perCraft.isEmpty()) {
            if (perCraft.size() != times) {
                fail(new Failure.Internal(null, "the layout covers " + perCraft.size() + " crafts but the leaf asks for " + times));
                return;
            }
            // The layout lists every craft of the leaf; the crafts already done are the first ones.
            layout = perCraft.subList(times - remaining, times);
        }

        String hint = hints.get("table");
        if (hint != null) {
            try {
                table = pos(Pos.parse(hint));
            } catch (RuntimeException e) {
                // A hint is only where to look: a bad one costs a search.
            }
        }
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (cooldown > 0) {
            cooldown--;
            return TickResult.RUNNING;
        }
        return switch (phase) {
            case PREPARE -> prepare(body);
            case OPEN -> open(body);
            case CRAFT -> craft(body);
            case BREAK -> breakTable(body);
            case PICKUP -> pickUp(body);
        };
    }

    @Override
    protected void onPause(BotPlayer body, String reason) {
        release(body);
    }

    @Override
    protected void onResume(BotPlayer body) {
        // The world may have changed while paused: everything is looked at again from the top.
        phase = Phase.PREPARE;
        cooldown = 0;
        phaseTicks = 0;
    }

    @Override
    protected void onAbort(BotPlayer body, String reason) {
        release(body);
    }

    /** True while the task waits on the game and not on the body: a table being broken, an item coming back. */
    @Override
    public boolean waiting() {
        return phase == Phase.BREAK || phase == Phase.PICKUP || cooldown > 0;
    }

    @Override
    public Map<String, String> checkpoint() {
        return table == null ? Map.of() : Map.of("table", new Pos(table.getX(), table.getY(), table.getZ()).encode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Phases
    // ---------------------------------------------------------------------------------------------------------

    private TickResult prepare(BotPlayer body) {
        if (remaining > 0) {
            if (!needsTable) {
                phase = Phase.CRAFT;
                return craft(body);
            }
            BlockPos standing = findTable(body);
            if (standing != null) {
                table = standing;
            } else {
                Failure refused = placeTable(body);
                if (refused != null) return stop(body, refused);
                cooldown = 1;
            }
            phase = Phase.OPEN;
            return TickResult.RUNNING;
        }
        // Every craft is done. A table this body put down comes up again; one it found is left standing.
        if (needsTable && table != null && isOurs(body)) {
            phase = Phase.BREAK;
            phaseTicks = 0;
            refusals = 0;
            return TickResult.RUNNING;
        }
        return TickResult.COMPLETED;
    }

    private TickResult open(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            table = null; // gone since it was chosen: choose again
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        if (!(body.containerMenu instanceof CraftingMenu)) {
            Vec3 top = new Vec3(table.getX() + 0.5, table.getY() + 1.0, table.getZ() + 0.5);
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, table, false));
        }
        if (!(body.containerMenu instanceof CraftingMenu)) {
            if (++refusals > REFUSALS_ALLOWED) {
                return stop(body, new Failure.TargetGone("the crafting table at " + table.toShortString(),
                        "the game would not open it"));
            }
            cooldown = 1;
            return TickResult.RUNNING;
        }
        phase = Phase.CRAFT;
        cooldown = 1;
        return TickResult.RUNNING;
    }

    private TickResult craft(BotPlayer body) {
        AbstractContainerMenu menu;
        if (needsTable) {
            if (!(body.containerMenu instanceof CraftingMenu)) {
                // Closed under the task (the table went, or the body was moved away): open it again.
                phase = Phase.OPEN;
                return TickResult.RUNNING;
            }
            menu = body.containerMenu;
        } else {
            if (body.containerMenu != body.inventoryMenu) body.closeContainer();
            menu = body.inventoryMenu;
        }
        Failure failure = craftOnce(body, menu);
        if (failure != null) return stop(body, failure);
        remaining--;
        if (remaining == 0) {
            if (!needsTable) return TickResult.COMPLETED;
            body.closeContainer();
            phase = Phase.PREPARE;
        }
        cooldown = CRAFT_PERIOD_TICKS - 1;
        return TickResult.RUNNING;
    }

    private TickResult breakTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockState state = level.getBlockState(table);
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            // Broken, by this task or by someone else: either way what is left is picking the item up.
            BotMemory.of(body.getUUID()).forgetPlaced(dimension(level), table);
            phase = Phase.PICKUP;
            phaseTicks = 0;
            return TickResult.RUNNING;
        }
        if (++phaseTicks > BREAK_WAIT_TICKS) {
            return stop(body, new Failure.TimedOut(phaseTicks, "breaking the crafting table took too long"));
        }
        IInteractionManager hand = body.getInteractionManager();
        if (!hand.isMining()) {
            Verdict verdict = BlockSafety.of(level.getServer()).mayBreak(body, level, table, state);
            if (!verdict.allowed()) {
                // It stays where it is: the body may not break it, whoever put it there.
                table = null;
                return TickResult.COMPLETED;
            }
            if (phaseTicks == 1) tablesBefore = body.getInventory().countItem(tableItem);
            if (refusals > REFUSALS_ALLOWED) {
                return stop(body, new Failure.Unreachable("the crafting table at " + table.toShortString(),
                        "the game would not start breaking it", ""));
            }
            selectBreakingTool(body, state);
            hand.processBlockBreakingAction(table, Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
            if (!hand.isMining() && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) refusals++;
            return TickResult.RUNNING;
        }
        hand.continueBreaking(table, Direction.UP);
        // A client sends the finish once its own progress reaches 1; the engine reports progress in tenths.
        if (hand.isMining() && hand.getBlockBreakingProgress() >= 10) {
            hand.processBlockBreakingAction(hand.getMiningPos(), Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), sequence++);
        }
        return TickResult.RUNNING;
    }

    private TickResult pickUp(BotPlayer body) {
        if (body.getInventory().countItem(tableItem) > tablesBefore) {
            if (walking) body.input().reset();
            walking = false;
            table = null;
            return TickResult.COMPLETED;
        }
        if (++phaseTicks > PICKUP_WAIT_TICKS) {
            return stop(body, new Failure.TimedOut(phaseTicks, "the broken crafting table's item did not come back"));
        }
        if (phaseTicks > PICKUP_PATIENCE_TICKS) stepTowardDrop(body);
        return TickResult.RUNNING;
    }

    /**
     * A dropped item hops away from the block it came from, and the pickup box of a standing player is only a block
     * wide on each side, so now and then the table's item comes to rest just out of reach. The body walks over to it,
     * by its movement input like anything else it does (nothing is moved or teleported), and the input is reset the
     * tick the item is in the inventory, and by {@link #release} on every other exit.
     */
    private void stepTowardDrop(BotPlayer body) {
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : body.serverLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(DROP_SEARCH_RADIUS),
                candidate -> !candidate.isRemoved() && candidate.getItem().is(tableItem))) {
            double distance = drop.distanceToSqr(body);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = drop;
            }
        }
        if (nearest == null) {
            if (walking) body.input().reset();
            walking = false;
            return;
        }
        body.input().lookAt(nearest.position());
        body.input().forward = 1.0F;
        walking = true;
    }

    /**
     * A task that fails itself is never aborted afterwards, so it lets go of what it holds first: the table's menu, a
     * break in progress, the cursor and the grid. A table it already put down stays, remembered, and the next craft
     * that needs one finds it.
     */
    private TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    // ---------------------------------------------------------------------------------------------------------
    // One craft, through the menu
    // ---------------------------------------------------------------------------------------------------------

    /** Null when the craft was made; otherwise why not. The cursor and the grid are empty either way. */
    @Nullable
    private Failure craftOnce(BotPlayer body, AbstractContainerMenu menu) {
        List<Placement> placements = new ArrayList<>();
        Failure unchosen = choose(body, placements);
        if (unchosen != null) return unchosen;

        MenuHand hand = new MenuHand(body, menu);
        try {
            for (Placement placement : placements) {
                if (!hand.pickUp(placement.item())) {
                    return new Failure.MissingInput(ItemKeys.of(placement.item()), 1, "it is not in the inventory");
                }
                hand.placeOne(1 + placement.cell().gridIndex());
            }
            hand.putBack();
            ItemStack offered = menu.getSlot(0).getItem();
            if (offered.isEmpty() || !offered.is(resultItem)) {
                return new Failure.Internal(null, "the " + gridWidth + "x" + gridWidth + " grid made "
                        + (offered.isEmpty() ? "nothing" : ItemKeys.of(offered.getItem()).id()) + " from the layout of "
                        + leaf.params().get("recipe") + ", not " + ItemKeys.of(resultItem).id());
            }
            int before = body.getInventory().countItem(resultItem);
            hand.moveOut(0);
            if (body.getInventory().countItem(resultItem) <= before) {
                return new Failure.NoProgress(0, "there is no room in the inventory for " + ItemKeys.of(resultItem).id());
            }
            return null;
        } finally {
            hand.putBack();
            hand.emptyGrid(1, gridWidth * gridWidth);
        }
    }

    /** Picks the item for every cell of this craft from what the body carries, or says what is missing. */
    @Nullable
    private Failure choose(BotPlayer body, List<Placement> into) {
        // What is left to draw from as the cells are filled: a recipe that wants three planks needs three.
        Map<Item, Integer> held = new HashMap<>();
        for (int slot = 0; slot < MenuHand.CARRIED_SLOTS; slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (!stack.isEmpty()) held.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<ItemKey> planned = layout.isEmpty() ? null : layout.get(layout.size() - remaining);
        if (planned != null && planned.size() != cells.size()) {
            return new Failure.Internal(null, "the layout names " + planned.size() + " items for " + cells.size()
                    + " slots of " + leaf.params().get("recipe"));
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            List<Item> alternatives = alternatives(cell.ingredient());
            Item chosen = null;
            if (planned != null) {
                chosen = itemOf(planned.get(i));
                if (chosen == null || !cell.ingredient().test(new ItemStack(chosen))) {
                    return new Failure.Internal(null, "the layout puts " + planned.get(i).id() + " in slot " + (i + 1)
                            + " of " + leaf.params().get("recipe") + ", which does not accept it");
                }
            } else {
                for (Item alternative : alternatives) {
                    if (held.getOrDefault(alternative, 0) > 0) {
                        chosen = alternative;
                        break;
                    }
                }
            }
            if (chosen == null || held.getOrDefault(chosen, 0) <= 0) {
                ItemKey wanted = chosen != null ? ItemKeys.of(chosen)
                        : alternatives.isEmpty() ? ItemKey.of("minecraft:air") : ItemKeys.of(alternatives.get(0));
                return new Failure.MissingInput(wanted, 1, "slot " + (i + 1) + " of " + leaf.params().get("recipe")
                        + " has nothing to take");
            }
            held.merge(chosen, -1, Integer::sum);
            into.add(new Placement(cell, chosen));
        }
        return null;
    }

    /**
     * The grid cells of a recipe in the order of its non-blank ingredients, which is the order of the planner's slots.
     * A shaped recipe keeps its shape, placed at the grid's top left; any other crafting recipe fills cells in order.
     */
    private static List<Cell> cellsOf(CraftingRecipe recipe, int gridWidth) {
        List<Ingredient> ingredients = recipe.getIngredients();
        boolean shaped = recipe instanceof ShapedRecipe;
        int width = shaped ? ((ShapedRecipe) recipe).getWidth() : gridWidth;
        int height = shaped ? ((ShapedRecipe) recipe).getHeight() : gridWidth;
        if (width > gridWidth || height > gridWidth) {
            throw new IllegalArgumentException("the recipe is " + width + "x" + height + " and does not fit the "
                    + gridWidth + "x" + gridWidth + " grid; it needs a crafting table");
        }
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) continue; // a blank cell of a shaped recipe
            int index = shaped ? (i / width) * gridWidth + (i % width) : cells.size();
            cells.add(new Cell(index, ingredient));
        }
        if (cells.size() > gridWidth * gridWidth) {
            throw new IllegalArgumentException("the recipe has " + cells.size() + " ingredients and the " + gridWidth + "x"
                    + gridWidth + " grid has " + gridWidth * gridWidth + " cells");
        }
        return cells;
    }

    /** The registered item for a key, or null when the game has none. */
    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** The ingredient's distinct items in its own order, which is the order the planner's slot lists them in. */
    private static List<Item> alternatives(Ingredient ingredient) {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) items.add(stack.getItem());
        }
        return List.copyOf(items);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The table
    // ---------------------------------------------------------------------------------------------------------

    /** The nearest crafting table the body can use from where it stands, or null. A remembered one is tried first. */
    @Nullable
    private BlockPos findTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (table != null && level.isLoaded(table) && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)
                && body.canInteractWithBlock(table, 0.0)) {
            return table;
        }
        BlockPos centre = body.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS, -TABLE_SEARCH_RADIUS),
                centre.offset(TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS))) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            if (!body.canInteractWithBlock(pos, 0.0)) continue;
            double distance = pos.distToCenterSqr(body.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * Puts the table item down on a free spot beside the body, through the player's own item use. A spot must be
     * replaceable, stand on something that holds a block up, and be one the body may break again (a table it could not
     * pick up would be left behind in someone's protected ground). Null when it is down, otherwise why not.
     */
    @Nullable
    private Failure placeTable(BotPlayer body) {
        ServerLevel level = body.serverLevel();
        if (body.getInventory().countItem(tableItem) == 0) {
            return new Failure.MissingInput(CRAFTING_TABLE, 1, "no crafting table stands within reach and none is carried");
        }
        if (!hold(body, tableItem)) {
            return new Failure.Internal(null, "the crafting table could not be taken into the hand");
        }
        BlockSafety safety = BlockSafety.of(level.getServer());
        BlockPos feet = body.blockPosition();
        Verdict denied = null;
        BlockPos deniedAt = null;
        boolean tried = false;
        for (BlockPos spot : spotsAround(feet)) {
            if (!level.isLoaded(spot) || !level.getBlockState(spot).canBeReplaced()) continue;
            BlockPos support = spot.below();
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) continue;
            Verdict verdict = safety.mayBreak(body, level, spot, Blocks.CRAFTING_TABLE.defaultBlockState());
            if (!verdict.allowed()) {
                if (denied == null) {
                    denied = verdict;
                    deniedAt = spot;
                }
                continue;
            }
            tried = true;
            Vec3 at = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            body.getInteractionManager().interactBlock(body, level, body.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(at, Direction.UP, support, false));
            // Whether it worked is the world's answer: the game refuses a spot an entity (the body itself) is in.
            if (level.getBlockState(spot).is(Blocks.CRAFTING_TABLE)) {
                table = spot.immutable();
                placedHere = true;
                tablesBefore = body.getInventory().countItem(tableItem);
                BotMemory.of(body.getUUID()).notePlaced(dimension(level), table);
                safety.noteBotPlaced(body, level, table);
                return null;
            }
        }
        if (!tried && denied != null) return denied.toFailure(deniedAt);
        return new Failure.Unreachable("a place for the crafting table", "no free spot beside the body",
                "no free, supported spot within two blocks of " + feet.toShortString());
    }

    /** The positions at the body's feet level around it, nearest ring first, straight neighbours before diagonals. */
    private static List<BlockPos> spotsAround(BlockPos feet) {
        List<BlockPos> spots = new ArrayList<>();
        int[][] near = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] d : near) spots.add(feet.offset(d[0], 0, d[1]));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) spots.add(feet.offset(dx, 0, dz));
            }
        }
        return spots;
    }

    /**
     * Takes the item into the main hand as a player would: it is selected when it is on the hotbar, otherwise the
     * inventory menu swaps it into the selected hotbar slot.
     */
    private static boolean hold(BotPlayer body, Item item) {
        var inventory = body.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(item)) {
                inventory.selected = slot;
                return true;
            }
        }
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand hand = new MenuHand(body, body.inventoryMenu);
        int from = hand.findCarried(item);
        if (from < 0) return false;
        hand.click(from, inventory.selected, ClickType.SWAP);
        return inventory.getItem(inventory.selected).is(item);
    }

    /**
     * Takes into the hand the hotbar slot that breaks the block fastest, and of equals one that cannot wear out, as a
     * player reaches for an axe or an empty hand: a table broken with the pickaxe just made would cost it durability for
     * nothing.
     */
    private static void selectBreakingTool(BotPlayer body, BlockState state) {
        var inventory = body.getInventory();
        int best = inventory.selected;
        float bestSpeed = -1.0F;
        boolean bestSafe = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
            boolean safe = stack.isEmpty() || !stack.isDamageableItem();
            if (speed > bestSpeed || (speed == bestSpeed && safe && !bestSafe)) {
                best = slot;
                bestSpeed = speed;
                bestSafe = safe;
            }
        }
        inventory.selected = best;
    }

    /** The table is this body's to take up: this task put it down, or the body's memory says an earlier one did. */
    private boolean isOurs(BotPlayer body) {
        return table != null && (placedHere || BotMemory.of(body.getUUID()).isPlaced(dimension(body.serverLevel()), table));
    }

    /** Lets go of everything: the break, the open menu, the cursor and the grid. Never throws on a body in any state. */
    private void release(@Nullable BotPlayer body) {
        phase = Phase.PREPARE;
        phaseTicks = 0;
        if (body == null) return;
        if (walking) {
            body.input().reset();
            walking = false;
        }
        IInteractionManager hand = body.getInteractionManager();
        if (hand.isMining() && table != null && table.equals(hand.getMiningPos())) {
            hand.processBlockBreakingAction(table, Action.ABORT_DESTROY_BLOCK, Direction.UP,
                    body.level().getMaxBuildHeight(), sequence++);
        }
        // Closing a menu hands back its grid and cursor (AbstractContainerMenu.removed).
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        MenuHand own = new MenuHand(body, body.inventoryMenu);
        own.putBack();
        own.emptyGrid(1, 4);
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static BlockPos pos(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
