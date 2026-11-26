package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Vanilla crafting (design 4.2): every {@code minecraft:crafting} recipe with a fixed result, shaped, shapeless or a
 * mod's own subclass of {@link CraftingRecipe}, as one {@link RecipeDef}. Adapted from SprawlCrafting's
 * {@code CraftPlanner.McRecipe}, without its grid and layout concerns: the planner needs the slots, the station and the
 * result, and the executor lays the grid out itself.
 */
final class CraftingRecipeSource implements RecipeSource {
    /** The one station a recipe that does not fit the inventory's 2x2 grid names. */
    static final ItemKey CRAFTING_TABLE = ItemKey.of("minecraft:crafting_table");

    private static final int NAMES_SHOWN = 5;

    @Override
    public String id() {
        return "crafting";
    }

    @Override
    public Set<String> recipeTypes() {
        return Set.of("minecraft:crafting");
    }

    @Override
    public void collect(MinecraftServer server, Collector out) {
        RegistryAccess access = server.registryAccess();
        List<String> noFixedResult = new ArrayList<>();
        List<String> ingredientWithoutItems = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();

        for (RecipeHolder<CraftingRecipe> holder : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            String id = holder.id().toString();
            try {
                CraftingRecipe recipe = holder.value();
                // CustomRecipe (armor dyeing, firework rockets, map cloning, ...) answers getResultItem with an empty
                // stack: its output depends on what is put in, so there is nothing fixed to plan with.
                if (recipe.isSpecial() || recipe instanceof CustomRecipe) {
                    noFixedResult.add(id);
                    continue;
                }
                ItemStack result = recipe.getResultItem(access);
                if (result.isEmpty()) {
                    noFixedResult.add(id);
                    continue;
                }

                List<RecipeDef.Slot> slots = new ArrayList<>();
                boolean readable = true;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue; // a blank cell of a shaped recipe's grid
                    List<ItemKey> alternatives = alternatives(ingredient);
                    if (alternatives.isEmpty()) {
                        readable = false; // an empty tag, or an ingredient no registered item satisfies
                        break;
                    }
                    slots.add(new RecipeDef.Slot(alternatives, 1));
                }
                if (!readable || slots.isEmpty()) {
                    ingredientWithoutItems.add(id);
                    continue;
                }

                // canCraftInDimensions is the game's own rule for the inventory grid: a shaped recipe at most 2 wide and
                // 2 high, a shapeless one with at most 4 ingredients.
                ItemKey station = recipe.canCraftInDimensions(2, 2) ? null : CRAFTING_TABLE;
                out.recipe(new RecipeDef(id, RecipeDef.CRAFT, slots, station, ItemKeys.of(result.getItem()), result.getCount()));
            } catch (RuntimeException | LinkageError e) {
                // LinkageError: a mod's recipe class built against a class that is not there costs that recipe only.
                unreadable.add(id);
            }
        }

        List<String> noRemainder = remainders(out);
        if (!noFixedResult.isEmpty()) {
            out.note(noFixedResult.size() + " recipes skipped because their result is not fixed (special recipes) " + named(noFixedResult));
        }
        if (!ingredientWithoutItems.isEmpty()) {
            out.note(ingredientWithoutItems.size() + " recipes skipped because an ingredient has no item " + named(ingredientWithoutItems));
        }
        if (!unreadable.isEmpty()) {
            out.note(unreadable.size() + " recipes skipped because reading them threw " + named(unreadable));
        }
        if (!noRemainder.isEmpty()) {
            out.note(noRemainder.size() + " items skipped because reading their crafting remainder threw " + named(noRemainder));
        }
    }

    // The ingredient's distinct items in its own order, so the executor fills a slot from the list the plan was made with.
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

    // Every item that leaves another item behind when crafted with: buckets, bottles. An item that leaves itself
    // (a damaged tool a mod keeps in the grid) is not a remainder in the planner's sense, which counts items made.
    // The item-level accessors are deprecated in favour of NeoForge's stack-sensitive ones; they are the vanilla
    // answer, and the only one that does not tie this class to a loader's patched ItemStack. A mod's item may override
    // the accessors, so each item is read on its own: one that throws is left out and returned, and the rest are kept.
    @SuppressWarnings("deprecation")
    private static List<String> remainders(Collector out) {
        List<String> unreadable = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                if (item == Items.AIR || !item.hasCraftingRemainingItem()) continue;
                Item left = item.getCraftingRemainingItem();
                if (left == null || left == Items.AIR || left == item) continue;
                out.remainder(ItemKeys.of(item), ItemKeys.of(left));
            } catch (RuntimeException | LinkageError e) {
                unreadable.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
        }
        return unreadable;
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
