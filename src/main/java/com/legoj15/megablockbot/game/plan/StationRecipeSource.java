package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/**
 * The two station recipe families the crafting grid and the furnace cannot make (issue #14):
 * {@code minecraft:stonecutting} and {@code minecraft:smithing}. A stonecutter turns one item into one other item of a
 * kind the grid cannot (slabs and stairs cheaper than the grid makes them); a smithing table upgrades a diamond tool or
 * a piece of armour with a template and an ingot. The tasks that run these are {@code game.task.StonecutTask} and
 * {@code game.task.SmithTask}; this source only says what each recipe consumes and makes.
 *
 * <p>A {@link SmithingTrimRecipe} is skipped and counted in one note: its result is the base item with a trim
 * component, not an item of its own, which the planner's item-count goals cannot ask for. The ingredients of a smithing
 * recipe are not public in 1.21.1 (only the tests its menu's slots make of an item), so each slot's alternatives are
 * the registered items the recipe's own predicate accepts, in registry order.
 */
public final class StationRecipeSource implements RecipeSource {
    /** Where a stonecutting recipe runs: the station the leaf names and the task puts down. */
    static final ItemKey STONECUTTER = ItemKey.of("minecraft:stonecutter");

    /** Where a smithing recipe runs. */
    static final ItemKey SMITHING_TABLE = ItemKey.of("minecraft:smithing_table");

    private static final int NAMES_SHOWN = 5;

    @Override
    public String id() {
        return "station";
    }

    @Override
    public Set<String> recipeTypes() {
        return Set.of("minecraft:stonecutting", "minecraft:smithing");
    }

    @Override
    public void collect(MinecraftServer server, Collector out) {
        RegistryAccess access = server.registryAccess();

        List<String> noResult = new ArrayList<>();
        List<String> ingredientWithoutItems = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (RecipeHolder<StonecutterRecipe> holder : server.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING)) {
            String id = holder.id().toString();
            try {
                StonecutterRecipe recipe = holder.value();
                ItemStack result = recipe.getResultItem(access);
                if (result.isEmpty()) {
                    noResult.add(id);
                    continue;
                }
                List<ItemKey> alternatives = alternatives(ingredientOf(recipe));
                if (alternatives.isEmpty()) {
                    ingredientWithoutItems.add(id); // an empty tag, or an ingredient no registered item satisfies
                    continue;
                }
                out.recipe(new RecipeDef(id, RecipeDef.STONECUT, List.of(new RecipeDef.Slot(alternatives, 1)),
                        STONECUTTER, ItemKeys.of(result.getItem()), Math.max(1, result.getCount())));
            } catch (RuntimeException | LinkageError e) {
                // LinkageError: a mod's recipe class built against a class that is not there costs that recipe only.
                unreadable.add(id);
            }
        }

        List<String> trims = new ArrayList<>();
        List<String> notTransform = new ArrayList<>();
        List<String> emptyIngredient = new ArrayList<>();
        List<String> smithNoResult = new ArrayList<>();
        List<String> smithUnreadable = new ArrayList<>();
        for (RecipeHolder<SmithingRecipe> holder : server.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING)) {
            String id = holder.id().toString();
            try {
                SmithingRecipe recipe = holder.value();
                if (recipe instanceof SmithingTrimRecipe) {
                    trims.add(id);
                    continue;
                }
                if (!(recipe instanceof SmithingTransformRecipe transform)) {
                    notTransform.add(id); // a mod's own SmithingRecipe: no task of ours knows how to run it
                    continue;
                }
                ItemStack result = transform.getResultItem(access);
                if (result.isEmpty()) {
                    smithNoResult.add(id);
                    continue;
                }
                List<ItemKey> template = keysOf(transform::isTemplateIngredient);
                List<ItemKey> base = keysOf(transform::isBaseIngredient);
                List<ItemKey> addition = keysOf(transform::isAdditionIngredient);
                if (template.isEmpty() || base.isEmpty() || addition.isEmpty()) {
                    emptyIngredient.add(id);
                    continue;
                }
                out.recipe(new RecipeDef(id, RecipeDef.SMITH,
                        List.of(new RecipeDef.Slot(template, 1), new RecipeDef.Slot(base, 1),
                                new RecipeDef.Slot(addition, 1)),
                        SMITHING_TABLE, ItemKeys.of(result.getItem()), Math.max(1, result.getCount())));
            } catch (RuntimeException | LinkageError e) {
                smithUnreadable.add(id);
            }
        }

        if (!noResult.isEmpty()) {
            out.note(noResult.size() + " stonecutting recipes skipped because their result is empty " + named(noResult));
        }
        if (!ingredientWithoutItems.isEmpty()) {
            out.note(ingredientWithoutItems.size() + " stonecutting recipes skipped because their ingredient has no item "
                    + named(ingredientWithoutItems));
        }
        if (!unreadable.isEmpty()) {
            out.note(unreadable.size() + " stonecutting recipes skipped because reading them threw " + named(unreadable));
        }
        if (!trims.isEmpty()) {
            out.note(trims.size() + " smithing recipes skipped because their result is not an item of its own (armour "
                    + "trims: the result is the base with a trim component) " + named(trims));
        }
        if (!notTransform.isEmpty()) {
            out.note(notTransform.size() + " smithing recipes skipped because they are neither a transform nor a trim "
                    + named(notTransform));
        }
        if (!emptyIngredient.isEmpty()) {
            out.note(emptyIngredient.size() + " smithing recipes skipped because an ingredient has no item " + named(emptyIngredient));
        }
        if (!smithNoResult.isEmpty()) {
            out.note(smithNoResult.size() + " smithing recipes skipped because their result is empty " + named(smithNoResult));
        }
        if (!smithUnreadable.isEmpty()) {
            out.note(smithUnreadable.size() + " smithing recipes skipped because reading them threw " + named(smithUnreadable));
        }
    }

    // ─── Reading the game ────────────────────────────────────────────────────────────────────────

    /**
     * The registered items the recipe's own predicate accepts, in registry order: the ingredients of a smithing recipe
     * are not public in 1.21.1, and the game's test is the truth. Both the book build and the task that runs a smith
     * leaf ask this, so the two never disagree about what a slot takes.
     */
    public static List<Item> acceptedItems(Predicate<ItemStack> ingredient) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && ingredient.test(new ItemStack(item))) items.add(item);
        }
        return List.copyOf(items);
    }

    private static List<ItemKey> keysOf(Predicate<ItemStack> ingredient) {
        return List.copyOf(acceptedItems(ingredient).stream().map(ItemKeys::of).toList());
    }

    /** The stonecutting recipe's one ingredient; a blank one (never, for this family) reads as none. */
    private static Ingredient ingredientOf(StonecutterRecipe recipe) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) return ingredient;
        }
        return Ingredient.EMPTY;
    }

    // The ingredient's distinct items in its own order, read the way the crafting source reads its ingredients, so
    // a gather leaf fills the slot from the list the plan was made with.
    private static List<ItemKey> alternatives(Ingredient ingredient) {
        // hasNoItems also catches the stand-in NeoForge puts in an empty tag (a barrier named "Empty Tag"), which
        // isEmpty does not: a recipe with such an ingredient can never be made.
        if (ingredient.hasNoItems()) return List.of();
        Set<ItemKey> items = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty() || stack.getItem() == Items.AIR) continue;
            items.add(ItemKeys.of(stack.getItem()));
        }
        return List.copyOf(items);
    }

    private static String named(List<String> names) {
        Set<String> sorted = new TreeSet<>(names);
        StringBuilder text = new StringBuilder("(");
        int shown = 0;
        for (String name : sorted) {
            if (shown == NAMES_SHOWN) break;
            if (shown++ > 0) text.append(", ");
            text.append(name);
        }
        if (sorted.size() > NAMES_SHOWN) text.append(", and ").append(sorted.size() - NAMES_SHOWN).append(" more");
        return text.append(')').toString();
    }
}
