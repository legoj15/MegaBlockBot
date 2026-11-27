package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * The furnace family (design 4.2, issue #14): every {@code minecraft:smelting}, {@code minecraft:blasting} and
 * {@code minecraft:smoking} recipe, as one {@link RecipeDef} per fuel class. A furnace recipe has no fixed fuel cost
 * per item (one coal smelts eight, one plank one and a half) and a slot counts whole items, so the variant is sized to
 * the class's burn time: with cooking time {@code c} and a burn time {@code b}, one fuel item cooks {@code b / c}
 * items, and that fraction in lowest terms {@code p / q} gives a run of {@code p} of the ingredient and {@code q} of
 * the fuel that makes {@code p} of the result. The id is the game recipe's id, {@code #} and the class name, so the
 * task that runs a smelt leaf (a later brief) can ask {@link #variantOf} what its recipe names.
 */
public final class CookingRecipeSource implements RecipeSource {
    /** The fuel classes this source writes variants for, one each: every item of a class must burn the same. */
    private enum FuelKind {
        COAL("coal"), PLANKS("planks"), LOGS("logs"), STICKS("sticks");

        final String name;

        FuelKind(String name) {
            this.name = name;
        }

        /** The class's items, its first the one the burn time is read from. */
        List<Item> items() {
            return switch (this) {
                case COAL -> List.of(Items.COAL, Items.CHARCOAL);
                case PLANKS -> tag(ItemTags.PLANKS);
                case LOGS -> tag(ItemTags.LOGS_THAT_BURN);
                case STICKS -> List.of(Items.STICK);
            };
        }

        /** The class of that name, or empty when no variant id carries it. */
        static Optional<FuelKind> byName(String name) {
            for (FuelKind kind : values()) {
                if (kind.name.equals(name)) return Optional.of(kind);
            }
            return Optional.empty();
        }
    }

    /** One fuel class with its burn time and the items that fill a variant's fuel slot, in the class's own order. */
    private record FuelClass(String name, List<ItemKey> items, int burnTicks) {}

    /** One recipe type this source reads and the station its recipes run at. */
    private record Family(RecipeType<? extends AbstractCookingRecipe> type, ItemKey station) {}

    private static final List<Family> FAMILIES = List.of(
            new Family(RecipeType.SMELTING, ItemKey.of("minecraft:furnace")),
            new Family(RecipeType.BLASTING, ItemKey.of("minecraft:blast_furnace")),
            new Family(RecipeType.SMOKING, ItemKey.of("minecraft:smoker")));

    /** Separates a variant id's two parts; a recipe id cannot contain it (a ResourceLocation's alphabet has no #). */
    private static final char VARIANT_MARK = '#';

    private static final int NAMES_SHOWN = 5;

    @Override
    public String id() {
        return "smelting";
    }

    @Override
    public Set<String> recipeTypes() {
        return Set.of("minecraft:smelting", "minecraft:blasting", "minecraft:smoking");
    }

    @Override
    public void collect(MinecraftServer server, Collector out) {
        RegistryAccess access = server.registryAccess();
        // The burn times are the game's own, so a mod that adds a fuel or changes one is followed without a list here.
        Map<Item, Integer> burnTimes = AbstractFurnaceBlockEntity.getFuel();
        List<String> leftOut = new ArrayList<>();
        List<String> noClassLeft = new ArrayList<>();
        List<FuelClass> classes = fuelClasses(burnTimes, leftOut, noClassLeft);

        List<String> noFixedResult = new ArrayList<>();
        List<String> ingredientWithoutItems = new ArrayList<>();
        List<String> neverDone = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Family family : FAMILIES) {
            for (RecipeHolder<? extends AbstractCookingRecipe> holder
                    : server.getRecipeManager().getAllRecipesFor(family.type())) {
                String id = holder.id().toString();
                try {
                    AbstractCookingRecipe recipe = holder.value();
                    ItemStack result = recipe.getResultItem(access);
                    if (result.isEmpty()) {
                        noFixedResult.add(id);
                        continue;
                    }
                    int cookingTime = recipe.getCookingTime();
                    if (cookingTime < 1) {
                        neverDone.add(id);
                        continue;
                    }
                    List<ItemKey> alternatives = alternatives(ingredientOf(recipe));
                    if (alternatives.isEmpty()) {
                        ingredientWithoutItems.add(id); // an empty tag, or an ingredient no registered item satisfies
                        continue;
                    }
                    for (FuelClass fuelClass : classes) {
                        Run run = runOf(fuelClass.burnTicks(), cookingTime);
                        out.recipe(new RecipeDef(variantId(id, fuelClass.name()), RecipeDef.SMELT,
                                List.of(new RecipeDef.Slot(alternatives, run.ingredient()),
                                        new RecipeDef.Slot(fuelClass.items(), run.fuel())),
                                family.station(), ItemKeys.of(result.getItem()),
                                run.ingredient() * result.getCount()));
                    }
                } catch (RuntimeException | LinkageError e) {
                    // LinkageError: a mod's recipe class built against a class that is not there costs that recipe only.
                    unreadable.add(id);
                }
            }
        }

        if (!noFixedResult.isEmpty()) {
            out.note(noFixedResult.size() + " recipes skipped because their result is empty " + named(noFixedResult));
        }
        if (!ingredientWithoutItems.isEmpty()) {
            out.note(ingredientWithoutItems.size() + " recipes skipped because their ingredient has no item "
                    + named(ingredientWithoutItems));
        }
        if (!neverDone.isEmpty()) {
            out.note(neverDone.size() + " recipes skipped because their cooking time is not positive " + named(neverDone));
        }
        if (!unreadable.isEmpty()) {
            out.note(unreadable.size() + " recipes skipped because reading them threw " + named(unreadable));
        }
        if (!leftOut.isEmpty()) {
            out.note(leftOut.size() + " fuel items left out of their class because they burn differently from the "
                    + "class's first item " + named(leftOut));
        }
        if (!noClassLeft.isEmpty()) {
            out.note(noClassLeft.size() + " fuel classes skipped because none of their items is a fuel the game knows "
                    + named(noClassLeft));
        }
    }

    // ─── The variant id, and the arithmetic behind a run's counts ────────────────────────────────

    /** One variant id taken apart: the game recipe to run and the fuel class its run is sized for. */
    public record Variant(String recipe, String fuelClass) {}

    /**
     * What a variant id names, or empty when the id is not one of this source's: the game recipe's id and one of the
     * fuel classes. The task that runs a smelt leaf (a later brief) asks this of the recipe a leaf carries, so it
     * knows which game recipe to look up and what fuel to put in.
     */
    public static Optional<Variant> variantOf(String id) {
        int mark = id.indexOf(VARIANT_MARK);
        if (mark < 1 || mark == id.length() - 1) return Optional.empty();
        Optional<FuelKind> kind = FuelKind.byName(id.substring(mark + 1));
        return kind.isPresent() ? Optional.of(new Variant(id.substring(0, mark), kind.get().name)) : Optional.empty();
    }

    /** The variant's id: the game recipe's id, the mark, and the fuel class. */
    static String variantId(String recipeId, String fuelClass) {
        return recipeId + VARIANT_MARK + fuelClass;
    }

    /** One variant run's counts: {@code p} of the ingredient and {@code q} of the fuel, both whole items. */
    record Run(int ingredient, int fuel) {
        Run {
            if (ingredient < 1 || fuel < 1) {
                throw new IllegalArgumentException("a run takes at least one ingredient and one fuel, not "
                        + ingredient + " and " + fuel);
            }
        }
    }

    /**
     * The counts of one variant's run: a fuel that burns {@code burnTicks} over a recipe that cooks in
     * {@code cookTicks} cooks {@code burnTicks / cookTicks} items, and the fraction in lowest terms {@code p / q}
     * says a run of {@code q} fuel cooks {@code p} items — coal over smelting, 1600/200 = 8/1, is eight items and
     * one coal; a plank, 300/200 = 3/2; a stick, 100/200 = 1/2.
     */
    static Run runOf(int burnTicks, int cookTicks) {
        int common = gcd(burnTicks, cookTicks);
        return new Run(burnTicks / common, cookTicks / common);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // ─── Reading the game ────────────────────────────────────────────────────────────────────────

    /**
     * The classes with their burn times. An item the game's map does not hold, or holds with a different time from
     * the class's first item, is left out of the class: a variant is one size, and a fuel that burns differently
     * needs one of its own. A class whose first item is no fuel at all (or whose tag holds nothing) is skipped.
     */
    private static List<FuelClass> fuelClasses(Map<Item, Integer> burnTimes, List<String> leftOut,
                                               List<String> noClassLeft) {
        List<FuelClass> classes = new ArrayList<>();
        for (FuelKind kind : FuelKind.values()) {
            List<Item> items = kind.items();
            Integer burn = items.isEmpty() ? null : burnTimes.get(items.get(0));
            if (burn == null || burn < 1) {
                noClassLeft.add(kind.name);
                continue;
            }
            Set<ItemKey> ofClass = new LinkedHashSet<>();
            ofClass.add(ItemKeys.of(items.get(0)));
            for (Item item : items.subList(1, items.size())) {
                Integer ticks = burnTimes.get(item);
                if (ticks == null || !ticks.equals(burn)) {
                    leftOut.add(ItemKeys.of(item).id());
                    continue;
                }
                ofClass.add(ItemKeys.of(item));
            }
            classes.add(new FuelClass(kind.name, List.copyOf(ofClass), burn));
        }
        return classes;
    }

    /** The tag's items in the tag's own order, read where they are used: a static field would freeze it before the
     * datapacks bind the tag. */
    private static List<Item> tag(TagKey<Item> tag) {
        List<Item> items = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) items.add(holder.value());
        return items;
    }

    // The family's one ingredient; a blank one (never, for this family) reads as none and the recipe is skipped.
    private static Ingredient ingredientOf(AbstractCookingRecipe recipe) {
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
