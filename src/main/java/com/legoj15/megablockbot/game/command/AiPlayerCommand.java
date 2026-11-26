package com.legoj15.megablockbot.game.command;

import com.legoj15.megablockbot.platform.Platform;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The {@code /aiplayer} command root. Each feature adds its own sub-commands from its {@code init()}:
 *
 * <pre>{@code
 * AiPlayerCommand.add(root -> root.then(Commands.literal("spawn").then(...)));
 * }</pre>
 *
 * The root itself has no permission requirement and does nothing: each sub-command checks who may run it, because
 * the rules differ (an owner may command their own bot; lint needs permission level 2).
 */
public final class AiPlayerCommand {
    public static final String ROOT = "aiplayer";

    // The command tree is built on a worker thread while data loads, at start and on every /reload.
    private static final List<Consumer<LiteralArgumentBuilder<CommandSourceStack>>> SUB_COMMANDS = new CopyOnWriteArrayList<>();

    private AiPlayerCommand() {}

    /** Adds sub-commands to the root. Call during init; the adder runs again each time the tree is rebuilt. */
    public static void add(Consumer<LiteralArgumentBuilder<CommandSourceStack>> adder) {
        SUB_COMMANDS.add(adder);
    }

    /** Called once from {@code GameBootstrap}. */
    public static void init() {
        Platform.loader().onRegisterCommands(dispatcher -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
            SUB_COMMANDS.forEach(adder -> adder.accept(root));
            dispatcher.register(root);
        });
    }
}
