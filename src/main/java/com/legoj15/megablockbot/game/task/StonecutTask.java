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
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@code stonecut} task (issue #14, {@link RecipeDef#STONECUT}): runs one stonecutting recipe the way a player
 * does, through the stonecutter's own menu with slot clicks. Everything about the station — a stonecutter put down,
 * walked to, opened, broken and taken up again — is {@link StationTask}'s; this class is the choice of blade.
 *
 * <p>Leaf params: {@code recipe} (the game recipe's id), {@code times} (cuts) and {@code station}
 * ({@code minecraft:stonecutter}). A stonecutter offers several results for one input, so filling the slot is not
 * enough: the menu is asked for the recipes it lists for the input, the leaf's recipe is found among them and pressed
 * the way a client presses the button row ({@code clickMenuButton}, the call the button-click packet ends in). Each
 * cut takes one input and gives the recipe's result count. As in the craft task, the leaf's goal is the absolute count
 * after the step, so a task started again makes only what is still missing.
 *
 * <p><b>The work.</b> One cut is: the first alternative carried goes into the input slot, the recipe is pressed, the
 * result slot must offer the recipe's result, and it is taken into the inventory with room asked for before anything
 * is put in (the craft's rule: whole or not at all). The input slot never holds more than one cut's worth, and on
 * every exit — done, a failure, a pause, an abort — the menu's close hands what is still in it back to the inventory,
 * the way the base class's exits always empty the cursor ({@code AbstractContainerMenu.removed}).
 *
 * <p><b>The station.</b> A stonecutter is a mason's job site, which block safety keeps a body out of when it did not
 * place it: a standing one is used only when the body's memory says the body placed it, and otherwise the carried one
 * is put down. A stonecutter is stone, so it is taken up again only with a tool that makes it drop (one broken by
 * hand drops nothing); without one it is left standing, remembered as the body's, and the task completes all the same
 * (the furnace's rule).
 *
 * <p><b>Failures.</b> {@code TargetGone} for a recipe id the game does not have or one that is no stonecutting recipe;
 * {@code MissingInput} when the ingredient or the stonecutter is not carried (named with the count); {@code NoRoom}
 * when the result cannot fit in the inventory, before anything is put in; {@code Internal} when the menu does not list
 * the leaf's recipe for the input or the result slot offers another item; the station failures are the base class's.
 */
public final class StonecutTask extends StationTask {
    /** The kind this task answers to, registered in {@code BotFeature} where the craft and smelt kinds are. */
    public static final String KIND = RecipeDef.STONECUT;

    /** The stonecutter menu's own slots, in the order {@code StonecutterMenu} adds them: the input, then the result. */
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 1;

    /** Ticks between two cuts: a few, like a player clicking through them (the craft task's period). */
    static final int CUT_PERIOD_TICKS = 3;

    private static final ItemKey STONECUTTER = ItemKey.of("minecraft:stonecutter");
    /** What a {@link Failure.NoPlace} from this task says it could not find a place for. */
    private static final String PUT_STONECUTTER_DOWN = "put a stonecutter down";

    // Read from the leaf and the game in beginStation.
    private StonecutterRecipe recipe;
    private ResourceLocation recipeId;
    private Item resultItem;
    private int resultCount;
    /** The ingredient's distinct items in the recipe's own order, which is the order the planner's slot lists them in. */
    private List<Item> ingredients = List.of();
    private int remaining;

    public StonecutTask(NodeSpec leaf, Map<String, String> hints) {
        super(leaf, hints);
    }

    private StonecutTask(BlockPos standing) {
        super(standing);
    }

    /** The take-up task for a stonecutter this body left standing ({@link StationTask#takeUp} says when it is this one). */
    static Task<BotPlayer> takeUp(BlockPos standing) {
        return new StonecutTask(standing.immutable());
    }

    // ---------------------------------------------------------------------------------------------------------
    // What this task says about its station and its work
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected Item stationItem() {
        return Items.STONECUTTER;
    }

    @Override
    protected Block stationBlock() {
        return Blocks.STONECUTTER;
    }

    @Override
    protected String stationName() {
        return "stonecutter";
    }

    @Override
    protected String stationVerb() {
        return PUT_STONECUTTER_DOWN;
    }

    @Override
    protected boolean stationMenuOpen(BotPlayer body) {
        return body.containerMenu instanceof StonecutterMenu;
    }

    @Override
    protected boolean mayUseStanding(BotPlayer body, ServerLevel level, BlockPos pos) {
        // A stonecutter is a villager's job site, and block safety keeps the body out of one it did not place: only the
        // body's own is used where it stands, and a stranger's makes it put its own down (the furnace's rule).
        return BotMemory.of(body.getUUID()).isPlaced(dimension(level), pos);
    }

    /**
     * A stonecutter is stone: broken without a tool that mines stone it drops nothing, so it is left standing,
     * remembered, and the task completes all the same (the furnace's rule). A block that drops for anything needs
     * no tool, so the question is asked of the block itself.
     */
    @Override
    protected boolean takesUpStation(BotPlayer body) {
        if (station == null) return false;
        BlockState state = body.serverLevel().getBlockState(station);
        return !state.requiresCorrectToolForDrops() || dropsFor(body, state);
    }

    @Override
    @Nullable
    protected Failure missingCarried(BotPlayer body) {
        return remaining <= 0 ? null : missingInput(body, ingredients, remaining, "stonecut");
    }

    @Override
    protected void beginStation(BotPlayer body) {
        if (takeUpOnly) return; // there is no work; the take-up needs nothing but the station's block
        Map<String, String> params = leaf.params();
        String id = params.getOrDefault("recipe", "");
        ResourceLocation key = ResourceLocation.tryParse(id);
        MinecraftServer server = body.getServer();
        Optional<RecipeHolder<?>> holder = key == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(key);
        if (holder.isEmpty() || !(holder.get().value() instanceof StonecutterRecipe found)) {
            fail(new Failure.TargetGone(id, "the game has no stonecutting recipe '" + id + "'"));
            return;
        }
        recipe = found;
        recipeId = key;
        String stationParam = params.getOrDefault("station", "minecraft:stonecutter");
        if (!ItemKey.of(stationParam).equals(STONECUTTER)) {
            fail(new Failure.Internal(null, "the stonecut task can only use a stonecutter as a station, not " + stationParam));
            return;
        }
        ItemStack result = recipe.getResultItem(body.serverLevel().registryAccess());
        if (result.isEmpty()) {
            fail(new Failure.TargetGone(id, "the result of '" + id + "' is empty"));
            return;
        }
        resultItem = result.getItem();
        resultCount = Math.max(1, result.getCount());
        ingredients = alternatives(ingredientOf(recipe));
        if (ingredients.isEmpty()) {
            fail(new Failure.TargetGone(id, "the ingredient of '" + id + "' has no registered item"));
            return;
        }

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the stonecut leaf's times is not a number: " + params.get("times")));
            return;
        }
        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some cuts
        // already made) makes only what is still missing — rounded up, for a cut is whole (the craft task's rule).
        int needed = times;
        if (leaf.goal() instanceof Goal.HaveItem have) {
            needed = Math.floorDiv(have.count() - MenuHand.carriedCount(body, resultItem) + resultCount - 1, resultCount);
        }
        remaining = Math.max(0, Math.min(times, needed));

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
        if (!(body.containerMenu instanceof StonecutterMenu menu)) {
            return stop(body, new Failure.Internal(null, "the stonecutter's menu was not open when the stonecut ticked"));
        }
        if (station != null && !body.canInteractWithBlock(station, 0.0)) {
            // Carried off since the work began: a menu out of reach would refuse every click, so it is closed and the
            // way back is looked for from the top.
            body.closeContainer();
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        Failure failure = cutOnce(body, menu);
        if (failure != null) return stop(body, failure);
        remaining--;
        if (remaining == 0) {
            body.closeContainer();
            phase = Phase.PREPARE;
        }
        cooldown = CUT_PERIOD_TICKS - 1;
        return TickResult.RUNNING;
    }

    // ---------------------------------------------------------------------------------------------------------
    // One cut, through the menu
    // ---------------------------------------------------------------------------------------------------------

    /** Null when the cut was made; otherwise why not. The cursor is empty either way. */
    @Nullable
    private Failure cutOnce(BotPlayer body, StonecutterMenu menu) {
        Item input = firstCarried(body, ingredients);
        if (input == null) {
            return missingInput(body, ingredients, 1, "stonecut");
        }
        // No room for the result is asked before anything is put in, so a cut that cannot be taken puts nothing down.
        ItemStack made = new ItemStack(resultItem, resultCount);
        int room = MenuHand.roomFor(body, made);
        if (room < resultCount) {
            return new Failure.NoRoom(ItemKeys.of(resultItem), "there is no room in the inventory for the " + resultCount
                + " " + ItemKeys.of(resultItem).id() + " this cut makes (room for " + room + ")");
        }
        MenuHand hand = new MenuHand(body, menu);
        try {
            if (!hand.pickUp(input)) {
                return new Failure.MissingInput(ItemKeys.of(input), 1, "it is not in the inventory");
            }
            hand.placeOne(INPUT_SLOT);
            hand.putBack();
            int listed = recipeIndex(menu);
            if (listed < 0) {
                return new Failure.Internal(null, "the stonecutter does not list '" + recipeId + "' among the "
                    + menu.getRecipes().size() + " recipes it offers for " + ItemKeys.of(input).id());
            }
            // The button row, as a client presses it: what ServerboundContainerButtonClickPacket ends in.
            menu.clickMenuButton(body, listed);
            ItemStack offered = menu.getSlot(RESULT_SLOT).getItem();
            if (offered.isEmpty() || !offered.is(resultItem)) {
                return new Failure.Internal(null, "the stonecutter offers "
                    + (offered.isEmpty() ? "nothing" : ItemKeys.of(offered.getItem()).id())
                    + " for '" + recipeId + "', not " + ItemKeys.of(resultItem).id());
            }
            return takeResult(body, menu, RESULT_SLOT, "");
        } finally {
            hand.putBack();
        }
    }

    /** Where the leaf's recipe sits in the menu's own list for the input now in the slot, or -1 when it does not. */
    private int recipeIndex(StonecutterMenu menu) {
        List<RecipeHolder<StonecutterRecipe>> offered = menu.getRecipes();
        for (int i = 0; i < offered.size(); i++) {
            if (recipeId.equals(offered.get(i).id())) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading the game
    // ---------------------------------------------------------------------------------------------------------

    /** The recipe's one ingredient; a blank one (never, for this family) reads as none. */
    private static Ingredient ingredientOf(StonecutterRecipe recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) return ingredient;
        }
        return Ingredient.EMPTY;
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
