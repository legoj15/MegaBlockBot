package com.legoj15.megablockbot.game.resolver;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.acquire.AcquisitionIndex;
import com.legoj15.megablockbot.core.acquire.LintReport;
import com.legoj15.megablockbot.core.planner.RecipeDef;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.legoj15.megablockbot.game.plan.RecipeBooks;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /aiplayer lint [namespace] [write]}: the obtainability lint over the acquisition index — items with no
 * source and no recipe, split into those a player still has a known way for (obtainable, no strategy yet) and those
 * nothing in a survival world yields. Permission level 2 (the console counts). A namespace literally named "write"
 * cannot be linted by name. Returns the unobtainable count.
 */
final class LintCommand {
    /** Item ids shown in chat; the rest are counted, and {@code write} puts every one in a file. */
    static final int CHAT_ITEM_LINES = 40;

    private LintCommand() {}

    static void init() {
        AiPlayerCommand.add(root -> root.then(Commands.literal("lint")
            .requires(source -> source.hasPermission(2))
            .executes(context -> run(context, null, false))
            .then(Commands.literal("write").executes(context -> run(context, null, true)))
            .then(Commands.argument("namespace", StringArgumentType.word())
                .executes(context -> run(context, StringArgumentType.getString(context, "namespace"), false))
                .then(Commands.literal("write")
                    .executes(context -> run(context, StringArgumentType.getString(context, "namespace"), true))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, String namespace, boolean write) {
        CommandSourceStack source = context.getSource();
        AcquisitionIndex index = AcquisitionIndexes.current();
        if (index.items().isEmpty()) {
            source.sendFailure(Component.literal("The acquisition index has not been built yet."));
            return 0;
        }
        LintReport report = LintReport.of(index, assembledResults(), namespace);
        send(source, String.join("\n", report.summaryLines()));
        if (!report.unobtainable().isEmpty()) {
            send(source, "Unobtainable:\n" + String.join("\n", report.itemLines(CHAT_ITEM_LINES)));
        }
        if (write) {
            Path file = source.getServer().getWorldPath(LevelResource.ROOT).resolve("megablockbot").resolve("lint.txt")
                .toAbsolutePath().normalize();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, report.fullText(), StandardCharsets.UTF_8);
                send(source, "Full report written to " + file);
            } catch (IOException e) {
                source.sendFailure(Component.literal("Could not write " + file + ": " + e.getMessage()));
            }
        }
        return report.unobtainable().size();
    }

    /**
     * The results of the assemble recipes the planner's own book holds (BuildCraft's assembly table, whose recipes
     * the recipe manager does not see): the index is built before the book and without it, so the lint asks the book
     * here, at command time. Such an item counts as craftable, not unobtainable.
     */
    private static Set<ItemKey> assembledResults() {
        Set<ItemKey> results = new TreeSet<>();
        for (RecipeDef recipe : RecipeBooks.current().all()) {
            if (RecipeDef.ASSEMBLE.equals(recipe.kind())) results.add(recipe.result());
        }
        return results;
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
