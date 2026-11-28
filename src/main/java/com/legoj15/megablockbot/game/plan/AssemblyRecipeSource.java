package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * BuildCraft's assembly table (issue #18, design 4.2 "modded machine recipes"): the recipes the mod keeps in its own
 * static registry ({@code buildcraft.lib.recipe.AssemblyRecipeRegistry.REGISTRY}), not in the game's recipe manager.
 * Every BuildCraft class is reached by name through reflection, so this source builds and runs with or without the
 * mod on the classpath: when the registry class cannot be loaded it yields nothing, with no error and no note — a
 * world without BuildCraft is the normal case, not a problem to report.
 *
 * <p>One {@link RecipeDef} per recipe, output and fuel class, kind {@code assemble}, station the assembly table and
 * {@code alsoHeld} the rig the table needs around it (a laser, a Stirling engine, a lever) and the pickaxe it is taken
 * down with, held while the recipe
 * runs and never used up. The recipe's energy (a {@code long} in millionths of an MJ) becomes burn ticks — the
 * Stirling engine makes at most one MJ a tick — with a margin for the engine's warm-up and the laser's losses,
 * rounded up to whole fuel items of the class in the recipe's last slot. A run whose fuel does not fit one stack is
 * left out and noted, and so is an output that is not a plain item (gates and facades carry components of their
 * own), the way the station source counts armour trims.
 */
public final class AssemblyRecipeSource implements RecipeSource {
    /** Where every assembly recipe runs: the station the leaf names and the task puts down. */
    static final ItemKey ASSEMBLY_TABLE = ItemKey.of("buildcraftunofficial:assembly_table");

    /**
     * What every assembly recipe holds besides its station: the laser that powers the table, the Stirling engine that
     * powers the laser, and the lever that signals the engine. Held, never used up.
     */
    static final List<ItemKey> RIG = List.of(
            ItemKey.of("buildcraftunofficial:laser"),
            ItemKey.of("buildcraftunofficial:engine_stone"),
            ItemKey.of("minecraft:lever"),
            // What the rig is taken down with: BuildCraft's machines drop only to a pickaxe (any tier; they are in
            // the pickaxe tag and in no tier tag). The planner reads a held tool as any of its class at its tier
            // or better, so a body with a stone pickaxe makes no wooden one for this. Without it the first live run
            // left its table standing and the next plan set out to build another from obsidian and diamonds.
            ItemKey.of("minecraft:wooden_pickaxe"));

    /**
     * One MJ is this many micro-Joules, the unit BuildCraft's {@code getRequiredMicroJoulesFor} answers in (its
     * {@code MjAPI.MJ}). Named here so no BuildCraft type is named anywhere in this mod.
     */
    static final long MICROS_PER_MINECRAFT_JOULE = 1_000_000L;

    /**
     * A run is sized for this much more burn than the recipe's energy says: the engine warms up before it reaches its
     * one MJ a tick, and the laser loses some of what it sends.
     */
    static final double FUEL_MARGIN = 1.5;

    /** The most fuel one run takes: what one stack holds. A recipe needing more is left out and noted. */
    static final int MAX_FUEL_PER_RUN = 64;

    /**
     * The fuel classes the variants are sized for, the cooking source's own (its {@link CookingRecipeSource#fuelOf}
     * reads each class's items and burn time from the game): the Stirling engine burns anything a furnace does, at
     * the same rate.
     */
    private static final List<String> FUEL_CLASSES = List.of("coal", "planks", "logs", "sticks");

    /** The registry class of the mod, read by name; {@code buildcraft.lib.recipe.AssemblyRecipeRegistry}. */
    private static final String REGISTRY_CLASS = "buildcraft.lib.recipe.AssemblyRecipeRegistry";

    /** The mod's own bootstrap for the registry, read by name; {@code buildcraft.silicon.BCSiliconRecipes}. */
    private static final String RECIPES_CLASS = "buildcraft.silicon.BCSiliconRecipes";

    private static final int NAMES_SHOWN = 5;

    @Override
    public String id() {
        return "buildcraft:assembly";
    }

    @Override
    @SuppressWarnings("unchecked") // the registry's own type is BuildCraft's, reached only by name
    public void collect(MinecraftServer server, Collector out) {
        Class<?> registryClass = load(REGISTRY_CLASS);
        if (registryClass == null) return; // BuildCraft is not here: nothing to read, nothing to say
        fillRegistry();
        Map<String, ?> registry;
        try {
            registry = (Map<String, ?>) registryClass.getField("REGISTRY").get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.note("the assembly registry could not be read (" + e.getClass().getSimpleName()
                    + "), so its recipes are left out");
            return;
        }

        // A HashMap underneath: the entries are sorted so the same registry gives the same recipes and notes.
        List<Map.Entry<String, ?>> entries = new ArrayList<>(registry.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> notPlain = new ArrayList<>();
        List<String> ingredientWithoutItems = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> runTooLarge = new ArrayList<>();
        for (Map.Entry<String, ?> entry : entries) {
            Object recipe = entry.getValue();
            if (recipe == null) continue;
            try {
                collectRecipe(entry.getKey(), recipe, notPlain, ingredientWithoutItems, runTooLarge, out);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // LinkageError: a BuildCraft recipe class built against a class that is not there costs that recipe only.
                unreadable.add(entry.getKey());
            }
        }

        if (!notPlain.isEmpty()) {
            out.note(notPlain.size() + " recipe outputs skipped because they are not a plain item (gates and facades "
                    + "carry components of their own) " + named(notPlain));
        }
        if (!ingredientWithoutItems.isEmpty()) {
            out.note(ingredientWithoutItems.size() + " recipe outputs skipped because an input has no item "
                    + named(ingredientWithoutItems));
        }
        if (!unreadable.isEmpty()) {
            out.note(unreadable.size() + " recipes skipped because reading them threw " + named(unreadable));
        }
        if (!runTooLarge.isEmpty()) {
            out.note(runTooLarge.size() + " fuel variants skipped because one run's fuel does not fit one stack "
                    + named(runTooLarge));
        }
    }

    // ─── Reading one recipe ──────────────────────────────────────────────────────────────────────

    /** One registry entry: one variant per output and fuel class, or the note that says why not. */
    private void collectRecipe(String name, Object recipe, List<String> notPlain, List<String> ingredientWithoutItems,
                               List<String> runTooLarge, Collector out) throws ReflectiveOperationException {
        Method outputs = recipe.getClass().getMethod("getOutputPreviews");
        Method inputsFor = recipe.getClass().getMethod("getInputsFor", ItemStack.class);
        Method microsFor = recipe.getClass().getMethod("getRequiredMicroJoulesFor", ItemStack.class);
        List<ItemStack> previews = new ArrayList<>();
        for (Object each : (Set<?>) outputs.invoke(recipe)) {
            if (each instanceof ItemStack stack) previews.add(stack);
        }
        previews.sort(Comparator.<ItemStack, String>comparing(stack -> ItemKeys.of(stack.getItem()).id())
                .thenComparingInt(ItemStack::getCount));
        for (ItemStack output : previews) {
            if (output.isEmpty() || output.getItem() == Items.AIR) continue;
            ItemKey result = ItemKeys.of(output.getItem());
            // A gate or a facade is the item plus components that say which one it is; the planner's goals count
            // items, so only an output that is the item itself can be planned for.
            if (!ItemStack.matches(output, new ItemStack(output.getItem(), output.getCount()))) {
                notPlain.add(variantId(name, result, ""));
                continue;
            }
            List<RecipeDef.Slot> inputs = new ArrayList<>();
            boolean readable = true;
            for (Object stack : (Set<?>) inputsFor.invoke(recipe, output)) {
                Ingredient ingredient = (Ingredient) stack.getClass().getField("ingredient").get(stack);
                int count = stack.getClass().getField("count").getInt(stack);
                List<ItemKey> alternatives = alternatives(ingredient);
                if (alternatives.isEmpty() || count < 1) {
                    readable = false; // an empty tag, or an ingredient no registered item satisfies
                    break;
                }
                inputs.add(new RecipeDef.Slot(alternatives, count));
            }
            if (!readable || inputs.isEmpty()) {
                ingredientWithoutItems.add(variantId(name, result, ""));
                continue;
            }
            // A set of ingredient stacks has no order of its own; sorted so the same recipe reads the same twice.
            inputs.sort(Comparator.comparing((RecipeDef.Slot slot) -> slot.alternatives().get(0).id())
                    .thenComparingInt(RecipeDef.Slot::count));
            long microJoules = (Long) microsFor.invoke(recipe, output);
            for (String fuelClass : FUEL_CLASSES) {
                Optional<CookingRecipeSource.Fuel> fuel = CookingRecipeSource.fuelOf(fuelClass);
                if (fuel.isEmpty()) continue; // the game knows no fuel of the class; the cooking source notes it
                int items = fuelItems(microJoules, fuel.get().burnTicks());
                if (items > MAX_FUEL_PER_RUN) {
                    runTooLarge.add(variantId(name, result, fuelClass));
                    continue;
                }
                List<RecipeDef.Slot> slots = new ArrayList<>(inputs);
                slots.add(new RecipeDef.Slot(fuel.get().items(), items)); // the fuel is the last slot
                out.recipe(new RecipeDef(variantId(name, result, fuelClass), RecipeDef.ASSEMBLE, slots,
                        ASSEMBLY_TABLE, result, Math.max(1, output.getCount()), RIG));
            }
        }
    }

    /**
     * How many fuel items one run needs: the recipe's energy in burn ticks (the Stirling engine's one MJ a tick),
     * with the margin, rounded up to whole items of the class that burns {@code burnTicks} each. At least one: a run
     * with no fuel to burn never finishes, however little energy the recipe asks.
     */
    static int fuelItems(long microJoules, int burnTicks) {
        double ticks = microJoules / (double) MICROS_PER_MINECRAFT_JOULE * FUEL_MARGIN;
        return Math.max(1, (int) Math.ceil(ticks / burnTicks));
    }

    /**
     * The variant's id: {@code assembly:}, the registry name, the output item, then the fuel class after the variant
     * mark the cooking source uses. An empty fuel class is the id a note names a recipe's output by, not a variant.
     */
    static String variantId(String recipeName, ItemKey output, String fuelClass) {
        return "assembly:" + recipeName + '#' + output.id() + (fuelClass.isEmpty() ? "" : '#' + fuelClass);
    }

    // ─── Reflection ───────────────────────────────────────────────────────────────────────────────

    /** The class by name, or null when it is not there: BuildCraft absent is the normal case, not a failure. */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * BuildCraft fills the registry when the server is about to start, and the book may be read before that; this
     * asks the mod to fill it now. A failure is ignored: whatever the registry holds is read either way.
     */
    private static void fillRegistry() {
        Class<?> silicon = load(RECIPES_CLASS);
        if (silicon == null) return;
        try {
            silicon.getMethod("ensureInitialized").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // ignored: filling it here was only a nudge, and the mod fills it itself when the server is about to start
        }
    }

    // ─── Reading the game ────────────────────────────────────────────────────────────────────────

    // The ingredient's distinct items in its own order, read the way the other sources read their ingredients, so a
    // gather leaf fills the slot from the list the plan was made with.
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
