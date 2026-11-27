package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TaskState;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.CookingRecipeSource;
import com.legoj15.megablockbot.game.plan.ItemKeys;
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
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The {@code smelt} task (design 4.2, {@link RecipeDef#SMELT}, issue #14): runs one furnace recipe the way a player
 * does, through the furnace's own menu with slot clicks, and keeps the menu open while the run burns. Everything about
 * the station — a furnace put down, walked to, opened, broken and picked up again — is {@link StationTask}'s; this
 * class is the fire.
 *
 * <p>Leaf params: {@code recipe} (a variant id of {@link CookingRecipeSource}: the game recipe's id, {@code #} and the
 * fuel class its run is sized for), {@code times} (runs) and {@code station} ({@code minecraft:furnace},
 * {@code minecraft:blast_furnace} or {@code minecraft:smoker}, which must be the one the recipe's type runs at). The
 * variant's run counts are read back from the source ({@link CookingRecipeSource#runOf}), never worked out again: one
 * run takes {@code p} of the ingredient and {@code q} of the fuel and makes {@code p} times the recipe's result. As in
 * the craft task, the leaf's goal is the absolute count after the step, so a task started again makes only what is
 * still missing, in whole runs.
 *
 * <p><b>The furnace.</b> A furnace is a container, and block safety keeps the body out of containers it did not
 * place: a standing furnace is used only when the body's memory says the body placed it; otherwise the carried one is
 * put down. The body's own furnace is taken up again at the end only when the body carries a pickaxe that makes it
 * drop — a furnace broken by hand drops nothing — and otherwise it is left standing, remembered as the body's, and the
 * task completes. What is still inside is taken out through the menu before the furnace is broken (an unburnt rest of
 * the fuel never is: a run is whole).
 *
 * <p><b>The work.</b> Nothing is written into a slot or an inventory directly: the ingredient for the runs still to
 * do goes in through clicks, then the fuel, at most a stack of each and more when a slot has emptied, and the result
 * is taken out whenever the result slot holds any (whole or not at all, the craft's rule). While the furnace burns and
 * there is nothing to click the task is {@code waiting()}, its menu kept open, the furnace's own lit state telling
 * "burning" from "nothing happens".
 *
 * <p><b>Interruptions.</b> On pause, abort or failure the cursor is empty and the menu is closed; what the task put
 * into the furnace stays there, the furnace stays remembered, and a resumed task walks back to it, opens it and
 * carries on from what the slots hold — an ingredient already in the furnace counts as carried for the runs still to
 * do. An end that is not a completion — an abort, a failure, the budget — says what is still inside and where the
 * furnace stands (the log, and a failure's detail), for the tidy step is what gets it back: it takes a furnace up
 * whether or not it holds anything, and what drops beside it when it breaks is picked up with the furnace.
 *
 * <p><b>Failures.</b> {@code TargetGone} for a recipe id the game does not have, one that is no cooking recipe, one
 * that does not run at the leaf's station, or one whose fuel class the game no longer knows; {@code MissingInput}
 * when the ingredient, the fuel or the furnace is not carried (named with the count); {@code NoRoom} when a result
 * cannot be taken into the inventory; {@code NoProgress} when the furnace holds the ingredient and the fuel and has
 * not burned for a named number of ticks (something else is in a slot, or the game refuses the recipe); the station
 * failures are the base class's.
 */
public final class SmeltTask extends StationTask {
    /** The kind this task answers to, registered in {@code BotFeature} where the craft kind is. */
    public static final String KIND = RecipeDef.SMELT;

    /** The furnace menu's own slots, in the order {@code AbstractFurnaceMenu} adds them: ingredient, fuel, result. */
    private static final int INGREDIENT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int RESULT_SLOT = 2;

    /**
     * Ticks the furnace may hold both a filled ingredient slot and a filled fuel slot without burning before the task
     * gives up on it: vanilla lights a furnace within a tick or two of both being filled, so this many quiet ticks
     * mean the game refuses the recipe (a blast furnace will not cook food) or something else is in a slot. A hundred
     * is half the shortest cooking time in the game, so a furnace that does burn is never mistaken for a stalled one.
     */
    static final int NO_PROGRESS_TICKS = 100;

    // Read from the leaf and the game in beginStation.
    private Item stationItem;
    private Block stationBlock;
    private String stationText = "furnace";
    private AbstractCookingRecipe recipe;
    private Item resultItem;
    /** The ingredient's distinct items in the recipe's own order, and the fuel class's in its own. */
    private List<Item> ingredients = List.of();
    private List<Item> fuels = List.of();
    /** One run: {@code p} of the ingredient, {@code q} of the fuel, and what it makes. */
    private int runIngredient = 1;
    private int runFuel = 1;
    private int perRunResult = 1;
    /** The count of the result that ends this task: the goal's, or what the runs make from what was carried. */
    private int targetCount;

    private int stallTicks;
    /** Whether the furnace was burning when the work last looked: what {@link #waitingWork} reports. */
    private boolean burning;
    /** Whether the work clicked anything this tick: a click is something happening, so the no-progress count starts again. */
    private boolean clicked;
    /** What the furnace's own three slots held last tick: a change between ticks is the game's doing, and progress too. */
    private int slotsLastTick;

    public SmeltTask(NodeSpec leaf, Map<String, String> hints) {
        super(leaf, hints);
    }

    private SmeltTask(BlockPos standing, Item item, Block block) {
        super(standing);
        this.stationItem = item;
        this.stationBlock = block;
        this.stationText = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }

    /** The take-up task for a furnace this body left standing ({@link StationTask#takeUp} says when it is this one). */
    static Task<BotPlayer> takeUp(BlockPos standing, Item item, Block block) {
        return new SmeltTask(standing.immutable(), item, block);
    }

    // ---------------------------------------------------------------------------------------------------------
    // What this task says about its station and its work
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected Item stationItem() {
        return stationItem;
    }

    @Override
    protected Block stationBlock() {
        return stationBlock;
    }

    @Override
    protected String stationName() {
        return stationText;
    }

    @Override
    protected String stationVerb() {
        return "put a " + stationText + " down";
    }

    @Override
    protected boolean stationMenuOpen(BotPlayer body) {
        return body.containerMenu instanceof AbstractFurnaceMenu;
    }

    @Override
    protected boolean mayUseStanding(BotPlayer body, ServerLevel level, BlockPos pos) {
        // A furnace is a container, and block safety keeps the body out of one it did not place: only the body's own
        // is used where it stands, and a stranger's makes it put its own down.
        return BotMemory.of(body.getUUID()).isPlaced(dimension(level), pos);
    }

    @Override
    protected boolean takesUpStation(BotPlayer body) {
        // A furnace comes up only when breaking it would drop it: broken by hand it drops nothing, so it is left
        // standing, remembered, and the task completes all the same.
        if (station == null) return false;
        return dropsFor(body, body.serverLevel().getBlockState(station));
    }

    /** The take-up of a furnace gathers what it held: the tidy step breaks one with the body's ingredient, fuel or
     *  result still inside, and what drops beside it is picked up before the task ends. */
    @Override
    protected boolean gathersSpills() {
        return takeUpOnly;
    }

    /**
     * The ingredient and the fuel are asked for before anything is put down, so a smelt that cannot run leaves the
     * furnace in the inventory. This is asked only when no furnace of the body's stands, so what is carried is all
     * there is; an ingredient already in the body's own furnace is counted once the menu is open.
     */
    @Override
    @Nullable
    protected Failure missingCarried(BotPlayer body) {
        int runs = runsWanted(body);
        if (runs <= 0) return null;
        Failure ingredient = missingInput(body, ingredients, runs * runIngredient, "smelt");
        if (ingredient != null) return ingredient;
        return missingInput(body, fuels, runs * runFuel, "smelt");
    }

    @Override
    protected void beginStation(BotPlayer body) {
        if (takeUpOnly) return; // the station's item and block came with the constructor, and there is no work
        Map<String, String> params = leaf.params();
        String recipeId = params.getOrDefault("recipe", "");
        Optional<CookingRecipeSource.Variant> variant = CookingRecipeSource.variantOf(recipeId);
        if (variant.isEmpty()) {
            fail(new Failure.TargetGone(recipeId, "'" + recipeId + "' is not a smelt variant: a game recipe id, '#', a fuel class"));
            return;
        }
        MinecraftServer server = body.getServer();
        ResourceLocation id = ResourceLocation.tryParse(variant.get().recipe());
        Optional<RecipeHolder<?>> holder = id == null || server == null ? Optional.empty() : server.getRecipeManager().byKey(id);
        if (holder.isEmpty() || !(holder.get().value() instanceof AbstractCookingRecipe found)) {
            fail(new Failure.TargetGone(recipeId, "the game has no cooking recipe '" + variant.get().recipe() + "'"));
            return;
        }
        recipe = found;
        String stationParam = params.getOrDefault("station", "minecraft:furnace");
        ItemKey family = familyOf(recipe.getType());
        if (family == null || !family.equals(ItemKey.of(stationParam))) {
            fail(new Failure.TargetGone(recipeId, "'" + variant.get().recipe() + "' is not a cooking recipe that runs at " + stationParam));
            return;
        }
        stationText = stationParam.replace("minecraft:", "").replace('_', ' ');
        stationItem = itemOf(family);
        // The block comes from the key the family was checked against, never from the raw parameter: ItemKey.of
        // normalizes a namespace-less id, and the raw string would name the wrong block for one of those.
        stationBlock = blockOf(family);
        if (stationItem == null || stationBlock == null) {
            fail(new Failure.Internal(null, "no registered item or block for the station " + stationParam));
            return;
        }
        Optional<CookingRecipeSource.Fuel> fuelClass = CookingRecipeSource.fuelOf(variant.get().fuelClass());
        if (fuelClass.isEmpty()) {
            fail(new Failure.TargetGone(recipeId, "the game knows no fuel of the class '" + variant.get().fuelClass() + "'"));
            return;
        }
        // The station's share of the burn time, as the book sized the variant: a blast furnace and a smoker halve it.
        int burn = CookingRecipeSource.burnAt(family, fuelClass.get().burnTicks());
        if (burn < 1 || recipe.getCookingTime() < 1) {
            fail(new Failure.TargetGone(recipeId, "'" + variant.get().recipe() + "' cannot be run with " + variant.get().fuelClass()
                + " at " + stationParam));
            return;
        }
        CookingRecipeSource.Run run = CookingRecipeSource.runOf(burn, recipe.getCookingTime());
        runIngredient = run.ingredient();
        runFuel = run.fuel();
        fuels = itemsOf(fuelClass.get().items());
        ingredients = alternatives(ingredientOf(recipe));
        if (ingredients.isEmpty()) {
            fail(new Failure.TargetGone(recipeId, "the ingredient of '" + variant.get().recipe() + "' has no registered item"));
            return;
        }
        ItemStack result = recipe.getResultItem(body.serverLevel().registryAccess());
        resultItem = result.getItem();
        perRunResult = runIngredient * Math.max(1, result.getCount());

        int times;
        try {
            times = Integer.parseInt(params.getOrDefault("times", "1"));
        } catch (NumberFormatException e) {
            fail(new Failure.Internal(e, "the smelt leaf's times is not a number: " + params.get("times")));
            return;
        }
        // The leaf's goal is the absolute count after this step, so a task started again after a restart (some runs
        // already cooked) makes only what is still missing — rounded up, for a run is whole. Both branches work to the
        // clamped runs, never past the leaf's times: a goal above what they make is the planner's to top up, not this
        // task's to spend another node's material on (the craft task's remaining is the same rule).
        int carried = MenuHand.carriedCount(body, resultItem);
        int runs = times;
        if (leaf.goal() instanceof Goal.HaveItem have) {
            runs = Math.floorDiv(have.count() - carried + perRunResult - 1, perRunResult);
        }
        runs = Math.max(0, Math.min(times, runs));
        targetCount = carried + runs * perRunResult;

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
        if (takeUpOnly) return true;
        // Done when the inventory holds the count the goal asks for — and not before the furnace holds nothing, for
        // what is still inside is taken out through the menu before the furnace is broken.
        return MenuHand.carriedCount(body, resultItem) >= targetCount && furnaceEmpty(body);
    }

    @Override
    protected TickResult work(BotPlayer body) {
        if (!(body.containerMenu instanceof AbstractFurnaceMenu menu)) {
            return stop(body, new Failure.Internal(null, "the furnace's menu was not open when the smelt ticked"));
        }
        if (station != null && !body.canInteractWithBlock(station, 0.0)) {
            // Carried off since the work began: a menu out of reach would refuse every click, so it is closed and the
            // way back is looked for from the top.
            body.closeContainer();
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        burning = isLit(body);
        clicked = false;
        // The result comes out whenever the slot holds any, as a player empties a furnace they stand at.
        Failure failure = takeOut(body, menu, RESULT_SLOT);
        if (failure != null) return stop(body, failure);
        if (MenuHand.carriedCount(body, resultItem) >= targetCount) {
            // The runs are done. What is still inside comes out through the menu before the furnace is broken, so
            // nothing is spilled; an unburnt rest of the fuel never is, for a run is whole.
            failure = takeOut(body, menu, INGREDIENT_SLOT);
            if (failure != null) return stop(body, failure);
            failure = takeOut(body, menu, FUEL_SLOT);
            if (failure != null) return stop(body, failure);
            body.closeContainer();
            phase = Phase.PREPARE;
            return TickResult.RUNNING;
        }
        // What is still to cook is counted in items, not in runs: a run in progress has already turned part of itself
        // into results, and asking its slot for a whole run again would call a furnace halfway through one short.
        int missing = targetCount - MenuHand.carriedCount(body, resultItem);
        int perItem = Math.max(1, perRunResult / runIngredient);
        failure = feed(body, menu, INGREDIENT_SLOT, ingredients, (missing + perItem - 1) / perItem, true);
        if (failure != null) return stop(body, failure);
        // The fuel item that is burning has left its slot, and what it still cooks is not the slot's to pay for again:
        // while the furnace burns, the slot is topped up from what is carried, and a count short of the full runs is
        // no missing input. A furnace that has gone out with something left to cook is held to the whole count.
        failure = feed(body, menu, FUEL_SLOT, fuels, runsWanted(body) * runFuel, !burning);
        if (failure != null) return stop(body, failure);
        return watch(body, menu);
    }

    /** While the furnace burns its run with nothing to click, the game — not the body — is who must act next. */
    @Override
    protected boolean waitingWork() {
        return phase == Phase.WORK && burning;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The work, through the menu
    // ---------------------------------------------------------------------------------------------------------

    /** Nothing to click: the run is burning, or nothing is happening at all. */
    private TickResult watch(BotPlayer body, AbstractFurnaceMenu menu) {
        int slotsNow = slotsSignature(menu);
        // The count means "nothing happened for this long", so any tick in which something did starts it again: the
        // furnace burns, the task clicked (a feed, a take-out), or the game moved an item between the slots.
        boolean happened = burning || clicked || slotsNow != slotsLastTick;
        slotsLastTick = slotsNow;
        if (happened) {
            stallTicks = 0;
            return TickResult.RUNNING;
        }
        if (menu.getSlot(INGREDIENT_SLOT).hasItem() && menu.getSlot(FUEL_SLOT).hasItem()) {
            // Both slots are filled and the furnace does not burn: the game refuses the recipe, or something else is
            // in a slot. Vanilla lights a furnace within a tick or two of both being filled, so NO_PROGRESS_TICKS
            // without burning is a smelt that will never run.
            if (++stallTicks > NO_PROGRESS_TICKS) {
                return stop(body, new Failure.NoProgress(stallTicks, "the furnace has held its ingredient and its fuel for "
                    + stallTicks + " ticks without burning, so the game refuses the smelt (something in a slot is not what the recipe takes)"
                    + furnaceWhere()));
            }
        }
        return TickResult.RUNNING;
    }

    /** The furnace's own three slots as one number — the item and the count of each — so a change between ticks is one the game made. */
    private static int slotsSignature(AbstractFurnaceMenu menu) {
        int hash = 1;
        for (int slot = 0; slot <= RESULT_SLOT; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            hash = hash * 31 + BuiltInRegistries.ITEM.getId(stack.getItem());
            hash = hash * 31 + stack.getCount();
        }
        return hash;
    }

    /**
     * Puts enough of one of {@code items} into the furnace's slot for the runs still to do — at most a stack, and
     * more when the slot has emptied — one right-clicked item at a time, as a player fills a furnace. What the slot
     * already holds of them counts towards the runs. Null, or the missing input that stopped it: with {@code strict}
     * a count the slot and the inventory together do not reach; without it what there is goes in and nothing is missed.
     */
    @Nullable
    private Failure feed(BotPlayer body, AbstractFurnaceMenu menu, int menuSlot, List<Item> items, int wanted, boolean strict) {
        if (wanted <= 0) return null;
        Slot slot = menu.getSlot(menuSlot);
        ItemStack there = slot.getItem();
        int held = there.isEmpty() || items.contains(there.getItem()) ? there.getCount() : 0;
        Item carried = firstCarried(body, items);
        int carriedCount = carried == null ? 0 : MenuHand.carriedCount(body, carried);
        if (held + carriedCount < wanted && !strict) wanted = held + carriedCount;
        if (held + carriedCount < wanted) {
            return new Failure.MissingInput(ItemKeys.of(items.get(0)), wanted - held - carriedCount,
                "the runs still to do need " + (wanted - held) + " of " + ItemKeys.of(items.get(0)).id()
                    + ", the furnace holds " + held + " and the body carries " + carriedCount + furnaceWhere());
        }
        int toPlace = Math.min(wanted - held, carriedCount);
        if (toPlace <= 0) return null;
        int room = roomIn(slot, there, carried);
        if (room <= 0) return null; // the slot is full: more goes in when it has emptied
        MenuHand hand = new MenuHand(body, menu);
        if (!hand.pickUp(carried)) return null;
        for (int i = 0; i < Math.min(toPlace, room) && !menu.getCarried().isEmpty(); i++) {
            hand.placeOne(menuSlot);
        }
        clicked = true; // the task clicked: whatever else this tick did, it was not a quiet one
        hand.putBack();
        return null;
    }

    /**
     * Shift-clicks what the slot holds into the inventory, whole or not at all, counting what arrived: the shared take
     * every station task makes of a slot ({@link StationTask#takeResult}), over one of the furnace's own.
     */
    @Nullable
    private Failure takeOut(BotPlayer body, AbstractFurnaceMenu menu, int menuSlot) {
        Failure failure = takeResult(body, menu, menuSlot, furnaceWhere());
        if (failure == null) clicked = true; // the task clicked: whatever else this tick did, it was not a quiet one
        return failure;
    }

    /** The runs still to do: what the inventory still needs of the result, in whole runs. */
    private int runsWanted(BotPlayer body) {
        int missing = targetCount - MenuHand.carriedCount(body, resultItem);
        return missing <= 0 ? 0 : (missing + perRunResult - 1) / perRunResult;
    }

    /** How many more of {@code item} the slot takes: a full stack when empty, the gap to the cap when it holds them. */
    private static int roomIn(Slot slot, ItemStack there, Item item) {
        if (!there.isEmpty() && !there.is(item)) return 0;
        int cap = slot.getMaxStackSize(there.isEmpty() ? new ItemStack(item, 1) : there);
        return there.isEmpty() ? cap : cap - there.getCount();
    }

    /** Whether the furnace is burning: the game's own answer, read from the block it lights while it burns. */
    private boolean isLit(BotPlayer body) {
        if (station == null) return false;
        BlockState state = body.serverLevel().getBlockState(station);
        return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
    }

    private boolean furnaceEmpty(BotPlayer body) {
        if (station == null) return true;
        return !(body.serverLevel().getBlockEntity(station) instanceof AbstractFurnaceBlockEntity furnace) || furnace.isEmpty();
    }

    /**
     * The end of a smelt that is not its completion says what is still in the furnace and where it stands: an abort, a
     * failure or the budget can leave the body's ingredient, fuel and result inside a block nothing else will open, and
     * the log is what says they are there (a completion emptied the furnace through the menu first, and a pause is not
     * an end — a resume carries on from inside).
     */
    @Override
    protected void letGo(BotPlayer body) {
        if (body != null && state() != TaskState.PAUSED) {
            String held = stillInside(body);
            if (held != null) {
                MegaBlockBot.LOGGER.warn("{} ends its smelt with {} still in the furnace at {}: the tidy step and the next smelt take them up",
                    body.getGameProfile().getName(), held, station.toShortString());
            }
        }
        super.letGo(body);
    }

    /** What the furnace still holds, as "2 minecraft:raw_iron, 3 minecraft:stick"; null with no furnace of this task's or nothing inside it. */
    @Nullable
    private String stillInside(BotPlayer body) {
        if (station == null || takeUpOnly) return null;
        if (!(body.serverLevel().getBlockEntity(station) instanceof AbstractFurnaceBlockEntity furnace) || furnace.isEmpty()) return null;
        StringBuilder held = new StringBuilder();
        for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
            ItemStack stack = furnace.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!held.isEmpty()) held.append(", ");
            held.append(stack.getCount()).append(' ').append(ItemKeys.of(stack.getItem()).id());
        }
        return held.isEmpty() ? null : held.toString();
    }

    /** Where the furnace stands, for a failure's detail: what it still holds stays there when the task fails, and the
     *  detail is where a look for it starts. Empty when no station is known. */
    private String furnaceWhere() {
        return station == null ? "" : "; the furnace at " + station.toShortString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading the game
    // ---------------------------------------------------------------------------------------------------------

    /** The family's one ingredient; a blank one (never, for this family) reads as none. */
    private static Ingredient ingredientOf(AbstractCookingRecipe recipe) {
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

    /** The registered item for a key, or null when the game has none. */
    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private static List<Item> itemsOf(List<ItemKey> keys) {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemKey key : keys) {
            Item item = itemOf(key);
            if (item != null) items.add(item);
        }
        return List.copyOf(items);
    }

    /** The station each of the three recipe families runs at, or null for a type that is none of them. */
    @Nullable
    private static ItemKey familyOf(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) return ItemKey.of("minecraft:furnace");
        if (type == RecipeType.BLASTING) return ItemKey.of("minecraft:blast_furnace");
        if (type == RecipeType.SMOKING) return ItemKey.of("minecraft:smoker");
        return null;
    }

    /** The registered block the key's item places, or null when the game has none: the item half of this is {@link #itemOf}. */
    @Nullable
    private static Block blockOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }
}
