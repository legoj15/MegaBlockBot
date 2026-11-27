package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /aiplayer goal|cancel|status|pause|resume <name> ...}: the same words as chat, typed. Every one is allowed only
 * to whoever may command the body (its owner, a permission-level-2 source, the console); the root carries no
 * requirement of its own. Each goes through {@link BotTalk}, so a command and a chat line cannot mean different things.
 */
final class BotCommands {
    private BotCommands() {}

    static void register() {
        AiPlayerCommand.add(root -> {
            root.then(Commands.literal("goal").then(nameArgument()
                    .then(Commands.argument("text", StringArgumentType.greedyString()).executes(BotCommands::goal))));
            root.then(Commands.literal("cancel").then(nameArgument()
                    .executes(ctx -> run(ctx, new Intent.Cancel(false)))
                    .then(Commands.literal("all").executes(ctx -> run(ctx, new Intent.Cancel(true))))));
            root.then(Commands.literal("status").then(nameArgument().executes(ctx -> run(ctx, new Intent.Status()))));
            root.then(Commands.literal("pause").then(nameArgument().executes(ctx -> run(ctx, new Intent.Pause()))));
            root.then(Commands.literal("resume").then(nameArgument().executes(ctx -> run(ctx, new Intent.Resume()))));
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArgument() {
        return Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
            List<String> names = new ArrayList<>();
            for (BotPlayer body : BodyManager.of(ctx.getSource().getServer()).all()) names.add(body.getGameProfile().getName());
            return SharedSuggestionProvider.suggest(names, builder);
        });
    }

    private static int goal(CommandContext<CommandSourceStack> ctx) {
        return withRuntime(ctx, (body, runtime) -> {
            BotTalk.interpret(body, runtime, StringArgumentType.getString(ctx, "text"), ctx.getSource());
            return 1;
        });
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Intent intent) {
        return withRuntime(ctx, (body, runtime) -> {
            BotTalk.apply(body, runtime, List.of(intent), ctx.getSource());
            return 1;
        });
    }

    private interface Action {
        int run(BotPlayer body, BotRuntime runtime);
    }

    /** Finds the body the source named and may command, and the runtime driving it; says why not when it cannot. */
    private static int withRuntime(CommandContext<CommandSourceStack> ctx, Action action) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<BotPlayer> found = Optional.ofNullable(BodyManager.of(source.getServer()).get(name));
        if (found.isEmpty()) return fail(source, name + " is not spawned");
        BotPlayer body = found.get();
        if (!body.mayCommand(source)) return fail(source, "You may not command " + body.getGameProfile().getName());
        BotRuntime runtime = BotRuntime.of(body);
        if (runtime == null) return fail(source, body.getGameProfile().getName() + " has no goal runtime (another brain is driving it)");
        return action.run(body, runtime);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
