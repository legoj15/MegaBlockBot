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
import com.legoj15.megablockbot.game.plan.StationRecipeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The {@code smith} task (issue #14, {@link RecipeDef#SMITH}): runs one smithing recipe the way a player does, through
 * the smithing table's own menu with slot clicks. Everything about the station — a smithing table put down, walked to,
 * opened, broken and taken up again — is {@link StationTask}'s; this class is the ingot and the template.
 *
 * <p>Leaf params: {@code recipe} (the game recipe's id), {@code times} (upgrades) and {@code station}
 * ({@code minecraft:smithing_table}). A smithing table needs no recipe chosen: with a template in its first slot, a
 * base in its second and an addition in its third, the result slot shows the first matching recipe's result, and
 * taking it uses up one of each. The task fills the three slots with the first alternative carried in menu order (the
 * craft task's rule without a layout) and checks that what the slot offers is the leaf's recipe's result, for the menu
 * matches against every smithing recipe there is. As in the craft task, the leaf's goal is the absolute count after
 * the step, so a task started again makes only what is still missing.
 *
 * <p><b>The work.</b> One upgrade is: the three inputs go into their slots one right-clicked item at a time, the
 * result slot must offer the recipe's result, and it is taken into the inventory with room asked for before anything
 * is put in (the craft's rule: whole or not at all). The input slots never hold more than one upgrade's worth, and on
 * every exit — done, a failure, a pause, an abort — the menu's close hands what is still in them back to the
 * inventory, the way the base class's exits always empty the cursor ({@code AbstractContainerMenu.removed}).
 *
 * <p><b>The station.</b> A smithing table is a toolsmith's job site, which block safety keeps a body out of when it
 * did not place it: a standing one is used only when the body's memory says the body placed it, and otherwise the
 * carried one is put down. A smithing table is wood, so it is always taken up again: whatever breaks it drops it.
 *
 * <p><b>Failures.</b> {@code TargetGone} for a recipe id the game does not have, one that is no smithing recipe or one
 * of the armour-trim kind (whose result is not an item of its own); {@code MissingInput} when an input or the table is
 * not carried (the first missing one, named with the count); {@code NoRoom} when the result cannot fit in the
 * inventory, before anything is put in; {@code Internal} when the result slot offers another item than the leaf's
 * recipe's; the station failures are the base class's.
 */
public final class SmithTask extends StationTask {
    /** The kind this task answers to, registered in {@code BotFeature} where the craft and smelt kinds are. */
    public static final String KIND = RecipeDef.SMITH;

    /** The smithing menu's own slots, in the order {@code SmithingMenu} adds them: template, base, addition, result. */
    private static final int[] INPUT_SLOTS = {0, 1, 2};
    private static final int RESULT_SLOT = 3;

    /** Ticks between two upgrades: a few, like a player clicking through them (the craft task's period). */
    static final int SMITH_PERIOD_TICKS = 3;

    private static final ItemKey SMITHING_TABLE = ItemKey.of("minecraft:smithing_table");
    /** What a {@link Failure.NoPlace} from this task says it could not find a place for. */
    private static final String PUT_TABLE_DOWN = "put a smithing table down";

    // Read from the leaf and the game in beginStation.
    private SmithingTransformRecipe recipe;
    private String recipeId = "";
    private Item resultItem;
    private int resultCount;
    /** One list per menu slot in menu order: the items the recipe's own predicate accepts (the source's order). */
    private List<List<Item>> inputs = List.of();
    private int remaining;

    public SmithTask(NodeSpec leaf, Map<String, String> hints) {
        super(leaf, hints);
    }

    private SmithTask(BlockPos standing) {
        super(standing);
    }

    /** The take-up task for a smithing table this body left standing ({@link StationTask#takeUp} says when it is this one). */
    static Task<BotPlayer> takeUp(BlockPos standing) {
        return new SmithTask(standing.immutable());
    }

    // ---------------------------------------------------------------------------------------------------------
    // What this task says about its station and its work
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected Item stationItem() {
        return Items.SMITHING_TABLE;
    }

    @Override
    protected Block stationBlock() {
        return Blocks.SMITHING_TABLE;
    }

    @Override
    protected String stationName() {
        return "smithing table";
    }

    @Override
    protected String stationVerb() {
        return PUT_TABLE_DOWN;
    }

    @Override
    protected boolean stationMenuOpen(BotPlayer body) {
        return body.containerMenu instanceof SmithingMenu;
    }

    @Override
    protected boolean mayUseStanding(BotPlayer body, ServerLevel level, BlockPos pos) {
        // A smithing table is a villager's job site, and block safety keeps the body out of one it did not place: only
        // the body's own is used where it stands, and a stranger's makes it put its own down (the furnace's rule).
        return BotMemory.of(body.getUUID()).isPlaced(dimension(level), pos);
    }

    @Override
    @Nullable
    protected Failure missingCarried(BotPlayer body) {
        if (remaining <= 0) return null;
        for (List<Item> alternatives : inputs) {
            Failure missing = missingInput(body, alternatives, remaining, "smith");
            if (missing != null) return missing;
        }
        return null;
    }

    @Override
    protected void beginStation(BotPlayer body) {
        if (takeUpOnly) return; // there is no work; the take-up needs nothing but the station's block
        Map<String, String> params = leaf.params();
        String id = params.getOrDefault("recipe", "");
        ResourceLocation key = ResourceLocation.tryParse(id);
        MinecraftServer server = body.getServer();
        Optional<RecipeHolder<?>> holder = key == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(key);
        if (holder.isEmpty() || !(holder.get().value() instanceof SmithingTransformRecipe found)) {
            fail(new Failure.TargetGone(id, "the game has no smithing recipe that makes an item of its own '" + id + "'"));
            return;
        }
        recipe = found;
        recipeId = id;
        String stationParam = params.getOrDefault("station", "minecraft:smithing_table");
        if (!ItemKey.of(stationParam).equals(SMITHING_TABLE)) {
            fail(new Failure.Internal(null, "the smith task can only use a smithing table as a station, not " + stationParam));
            return;
        }
        ItemStack result = recipe.getResultItem(body.serverLevel().registryAccess());
        if (result.isEmpty()) {
            fail(new Failure.TargetGone(id, "the result of '" + id + "' is empty"));
            return;
        }
        resultItem = result.getItem();
        resultCount = Math.max(1, result.getCount());
        // The three slots' items are read from the recipe's own predicates, the way the book build read them
        // (StationRecipeSource), so what the task puts in is what the plan was made with.
        List<List<Item>> slots = List.of(
                StationRecipeSource.acceptedItems(recipe::isTemplateIngredient),
                StationRecipeSource.acceptedItems(recipe::isBaseIngredient),
                StationRecipeSource.acceptedItems(recipe::isAdditionIngredient));
        if (slots.stream().anyMatch(List::isEmpty)) {
            fail(new Failure.TargetGone(id, "an ingredient of '" + id + "' has no registered item"));
            return;
        }
        inputs = slots;

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the smith leaf's times is not a number: " + params.get("times")));
            return;
        }
        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some
        // upgrades already made) makes only what is still missing (the craft task's rule).
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
        if (!(body.containerMenu instanceof SmithingMenu menu)) {
            return stop(body, new Failure.Internal(null, "the smithing table's menu was not open when the smith ticked"));
        }
        if (station != null && !body.canInteractWithBlock(station, 0.0)) {
            // Carried off since the work began: a menu out of reach would refuse every click, so it is closed and the
            // way back is looked for from the top.
            body.closeContainer();
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        Failure failure = upgradeOnce(body, menu);
        if (failure != null) return stop(body, failure);
        remaining--;
        if (remaining == 0) {
            body.closeContainer();
            phase = Phase.PREPARE;
        }
        cooldown = SMITH_PERIOD_TICKS - 1;
        return TickResult.RUNNING;
    }

    // ---------------------------------------------------------------------------------------------------------
    // One upgrade, through the menu
    // ---------------------------------------------------------------------------------------------------------

    /** Null when the upgrade was made; otherwise why not. The cursor is empty either way. */
    @Nullable
    private Failure upgradeOnce(BotPlayer body, SmithingMenu menu) {
        List<Item> chosen = new ArrayList<>(INPUT_SLOTS.length);
        for (List<Item> alternatives : inputs) {
            Item item = firstCarried(body, alternatives);
            if (item == null) return missingInput(body, alternatives, 1, "smith");
            chosen.add(item);
        }
        // No room for the result is asked before anything is put in, so an upgrade that cannot be taken puts nothing
        // into the slots.
        ItemStack made = new ItemStack(resultItem, resultCount);
        int room = MenuHand.roomFor(body, made);
        if (room < resultCount) {
            return new Failure.NoRoom(ItemKeys.of(resultItem), "there is no room in the inventory for the " + resultCount
                + " " + ItemKeys.of(resultItem).id() + " this upgrade makes (room for " + room + ")");
        }
        MenuHand hand = new MenuHand(body, menu);
        try {
            for (int slot = 0; slot < INPUT_SLOTS.length; slot++) {
                if (!hand.pickUp(chosen.get(slot))) {
                    return new Failure.MissingInput(ItemKeys.of(chosen.get(slot)), 1, "it is not in the inventory");
                }
                hand.placeOne(INPUT_SLOTS[slot]);
                hand.putBack();
            }
            ItemStack offered = menu.getSlot(RESULT_SLOT).getItem();
            if (offered.isEmpty() || !offered.is(resultItem)) {
                return new Failure.Internal(null, "the smithing table offers "
                    + (offered.isEmpty() ? "nothing" : ItemKeys.of(offered.getItem()).id())
                    + " for '" + recipeId + "', not " + ItemKeys.of(resultItem).id());
            }
            return takeResult(body, menu, RESULT_SLOT, "");
        } finally {
            hand.putBack();
        }
    }
}
