package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import java.util.Set;
import net.minecraft.server.MinecraftServer;

/**
 * One family of recipes turned into {@link RecipeDef}s (design 4.2): vanilla crafting today, smelting, stonecutting and
 * each mod's machines later. A source reads wherever its recipes live (the server's {@code RecipeManager}, or a mod's
 * own registry) and says nothing about how they are run; the task named by {@link RecipeDef#kind()} does that.
 */
public interface RecipeSource {
    /** Names the source in the log and in notes: {@code "crafting"}, later {@code "smelting"}, {@code "buildcraft:assembly"}. */
    String id();

    /**
     * The {@code RecipeManager} recipe types (registry ids such as {@code minecraft:crafting}) this source reads. Only the
     * build's "not read" tally uses it: every recipe of a type that no working source claims is counted by type in the
     * summary line, so the log says what the planner cannot see. A source that reads a private registry claims none.
     */
    default Set<String> recipeTypes() {
        return Set.of();
    }

    /**
     * Reads this family. Runs on the server thread, at server start and after every {@code /reload}. It must not throw
     * for bad data: a recipe it cannot read is skipped and reported through {@link Collector#note}. A source that does
     * throw is logged and left out of the book whole, so the others still build.
     */
    void collect(MinecraftServer server, Collector out);

    /** Where a source puts what it read. One collector serves one source in one build. */
    interface Collector {
        void recipe(RecipeDef recipe);

        /** Using one {@code item} up in a recipe leaves {@code leftBehind} (an empty bucket for a milk bucket). */
        void remainder(ItemKey item, ItemKey leftBehind);

        /** Something this source could not read, for the log; one line per kind of problem, with its count. */
        void note(String what);
    }
}
