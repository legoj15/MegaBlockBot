package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The {@code craft} task (design 4.2, {@link RecipeDef#CRAFT}): runs one crafting recipe {@code times} times the way a
 * player does, through the real menus with slot clicks, one craft every few ticks. Everything about the station — a
 * crafting table put down, walked to, opened, broken and picked up again — is {@link StationTask}'s; this class is the
 * crafting grid.
 *
 * <p>Leaf params: {@code recipe} (the game recipe's id), {@code times}, {@code station} (the item that must be at hand,
 * or empty) and optionally {@code layout} ({@link CraftLayouts}). With a layout the task puts the items the planner chose
 * in the slots and never re-decides; without one each slot takes the first alternative, in the recipe's own order, that
 * the body carries. The grid positions come from the game's recipe, found again by id: a shaped recipe's shape is
 * not in the planner's book.
 *
 * <p><b>What it draws from.</b> The main inventory and the hotbar ({@link MenuHand#CARRIED_SLOTS}); an item worn or held
 * in the off hand is in use, and is neither an ingredient nor the table to put down. The result goes to the same slots,
 * and a result that does not wholly fit is not taken at all: the ingredients are given back from the grid, so a craft
 * never turns part of its result into something on the floor.
 *
 * <p><b>Stations.</b> No station: the inventory's own 2x2 grid. A crafting table: one within reach is used where it
 * stands, whatever placed it (a table is no container; anyone may work at one). See {@link StationTask} for the rest:
 * the walks, the place, the break, the pick-up, the interruptions and the failures.
 *
 * <p><b>Failures.</b> {@code MissingInput} when an ingredient or the table is not carried, {@code TargetGone} for a
 * recipe id the game does not have, {@code NoRoom} when the result would not fit in the inventory; the station failures
 * are the base class's. The craft is done once its result is in the inventory, so what happens to the table afterwards
 * cannot fail it.
 */
public final class CraftTask extends StationTask {
    public static final String KIND = RecipeDef.CRAFT;

    /** Ticks between two crafts: a few, like a player clicking through them. */
    static final int CRAFT_PERIOD_TICKS = 3;

    private static final ItemKey CRAFTING_TABLE = ItemKey.of("minecraft:crafting_table");
    /** What a {@link Failure.NoPlace} from this task says it could not find a place for. */
    static final String PUT_TABLE_DOWN = "put a crafting table down";

    /** One ingredient position: where it goes in the grid and what the recipe accepts there. */
    private record Cell(int gridIndex, Ingredient ingredient) {}

    /** What goes into one cell for one craft. */
    private record Placement(Cell cell, Item item) {}

    // Read from the leaf and the game in beginStation.
    private CraftingRecipe recipe;
    private boolean needsTable;
    private int gridWidth;
    private List<Cell> cells;
    private Item resultItem;
    private int resultCount;
    private int remaining;
    /** The planner's items for the crafts still to do, one list per craft in slot order; empty without a layout. */
    private List<List<ItemKey>> layout = List.of();

    public CraftTask(NodeSpec leaf, Map<String, String> hints) {
        super(leaf, hints);
    }

    private CraftTask(BlockPos standing) {
        super(standing);
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
     * The nearest station this body left standing that the tidy step may take up, or null: a crafting table, or one
     * of the body's own furnaces while the body carries a tool that makes it drop, whether or not it holds anything
     * (broken, it drops what it holds beside it, and the take-up picks that up too), or a job site the body put down
     * for a reroll that no villager is to keep (a lectern). The runtime asks this after a load and when a goal ends;
     * the machinery is the base class's ({@link StationTask#leftover}), and this is the public face of it because the
     * base class stays package-private.
     */
    @Nullable
    public static BlockPos leftoverStation(BotPlayer body) {
        return StationTask.leftover(body);
    }

    /** A task that only takes the station at {@code standing} up, whichever kind stands there ({@link StationTask#takeUp}). */
    public static Task<BotPlayer> takeUpStation(ServerLevel level, BlockPos standing) {
        return StationTask.takeUp(level, standing);
    }

    // ---------------------------------------------------------------------------------------------------------
    // What this task says about its station and its work
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected Item stationItem() {
        return needsTable ? Items.CRAFTING_TABLE : null;
    }

    @Override
    protected Block stationBlock() {
        return Blocks.CRAFTING_TABLE;
    }

    @Override
    protected String stationName() {
        return "crafting table";
    }

    @Override
    protected String stationVerb() {
        return PUT_TABLE_DOWN;
    }

    @Override
    protected boolean stationMenuOpen(BotPlayer body) {
        return body.containerMenu instanceof CraftingMenu;
    }

    @Override
    protected void beginStation(BotPlayer body) {
        if (takeUpOnly) {
            needsTable = true;
            remaining = 0;
            return;
        }
        Map<String, String> params = leaf.params();
        String recipeId = params.getOrDefault("recipe", "");
        MinecraftServer server = body.getServer();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        Optional<RecipeHolder<?>> holder = id == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(id);
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe found)) {
            fail(new Failure.TargetGone(recipeId, "the game has no crafting recipe '" + recipeId + "'"));
            return;
        }
        recipe = found;
        ItemStack result = recipe.getResultItem(body.serverLevel().registryAccess());
        resultItem = result.getItem();
        resultCount = Math.max(1, result.getCount());

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the craft leaf's times is not a number: " + params.get("times")));
            return;
        }

        String stationParam = params.getOrDefault("station", "");
        needsTable = !stationParam.isEmpty();
        if (needsTable && !ItemKey.of(stationParam).equals(CRAFTING_TABLE)) {
            fail(new Failure.Internal(null, "the craft task can only use a crafting table as a station, not " + stationParam));
            return;
        }
        gridWidth = needsTable ? 3 : 2;
        try {
            cells = cellsOf(recipe, gridWidth);
        } catch (IllegalArgumentException e) {
            fail(new Failure.Internal(null, e.getMessage()));
            return;
        }

        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some crafts
        // already done) makes only what is still missing. The count is of the slots a craft puts its result in.
        int needed = times;
        if (leaf.goal() instanceof Goal.HaveItem have) {
            int missing = have.count() - MenuHand.carriedCount(body, resultItem);
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

        String hint = hints.get("station");
        if (hint != null) {
            try {
                station = blockPos(Pos.parse(hint));
            } catch (RuntimeException e) {
                // A hint is only where to look: a bad one costs a search.
            }
        }
    }

    @Override
    protected boolean workDone(BotPlayer body) {
        return remaining <= 0;
    }

    @Override
    protected TickResult work(BotPlayer body) {
        AbstractContainerMenu menu = needsTable ? body.containerMenu : body.inventoryMenu;
        Failure failure = craftOnce(body, menu);
        if (failure != null) return stop(body, failure);
        remaining--;
        if (remaining == 0) {
            if (!needsTable) return complete(body);
            body.closeContainer();
            phase = Phase.PREPARE;
        }
        cooldown = CRAFT_PERIOD_TICKS - 1;
        return TickResult.RUNNING;
    }

    /** The craft's grid is given back whenever the task lets go: nothing the body meant to craft is left lying in a menu. */
    @Override
    protected void giveBack(BotPlayer body, MenuHand own) {
        own.emptyGrid(1, 4);
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
            // Taking the result takes the ingredients with it, and a result that does not wholly fit is dropped at the
            // feet by the game (a shift click moves what fits and drops the rest). So the result is taken only when all of
            // it has room; otherwise it stays in its slot, and the finally below gives the ingredients back.
            int made = offered.getCount();
            int room = MenuHand.roomFor(body, offered);
            if (room < made) {
                return new Failure.NoRoom(ItemKeys.of(resultItem), "there is no room in the inventory for the " + made + " "
                        + ItemKeys.of(resultItem).id() + " this craft makes (room for " + room + ")");
            }
            int before = MenuHand.carriedCount(body, resultItem);
            hand.moveOut(0);
            int arrived = MenuHand.carriedCount(body, resultItem) - before;
            if (arrived < made) {
                return new Failure.NoProgress(0, "only " + arrived + " of the " + made + " " + ItemKeys.of(resultItem).id()
                        + " this craft made reached the inventory; the rest was dropped at the feet");
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
}
