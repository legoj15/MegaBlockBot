package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.legoj15.megablockbot.game.reflex.ReflexConstants.NightPolicy;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /aiplayer reflex <name>} prints what the body's reflex layer is doing and why; {@code on} gives the body the
 * dev brain that only ticks the layer ({@link ReflexDevBrain}), {@code off} takes it away again; {@code night
 * ignore|shelter|bed} sets the night policy of that body's layer. Debugging and evidence-run tools, for whoever may
 * command the body.
 */
final class ReflexCommands {
    private ReflexCommands() {}

    static void register() {
        AiPlayerCommand.add(root -> root.then(Commands.literal("reflex").then(nameArgument()
            .executes(ReflexCommands::status)
            .then(Commands.literal("on").executes(ctx -> dev(ctx, true)))
            .then(Commands.literal("off").executes(ctx -> dev(ctx, false)))
            .then(nightBranch()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nightBranch() {
        LiteralArgumentBuilder<CommandSourceStack> night = Commands.literal("night");
        for (NightPolicy policy : NightPolicy.values()) {
            night.then(Commands.literal(policy.name().toLowerCase(Locale.ROOT)).executes(ctx -> night(ctx, policy)));
        }
        return night;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArgument() {
        return Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
            List<String> names = new ArrayList<>();
            for (BotPlayer body : BodyManager.of(ctx.getSource().getServer()).all()) names.add(body.getGameProfile().getName());
            return SharedSuggestionProvider.suggest(names, builder);
        });
    }

    /** The named body if the source may command it; otherwise the failure is sent and empty returned. */
    private static Optional<BotPlayer> body(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        BotPlayer body = BodyManager.of(source.getServer()).get(name);
        if (body == null) {
            source.sendFailure(Component.literal("no body " + name + " is logged in"));
            return Optional.empty();
        }
        if (!body.mayCommand(source)) {
            source.sendFailure(Component.literal("you may not command " + name));
            return Optional.empty();
        }
        return Optional.of(body);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        String text = body.getGameProfile().getName() + ": " + ReflexLayer.of(body).status()
            + (body.brain() instanceof ReflexDevBrain ? " [dev brain on]" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int dev(CommandContext<CommandSourceStack> ctx, boolean on) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        if (on) {
            if (!(body.brain() instanceof ReflexDevBrain)) body.setBrain(new ReflexDevBrain());
        } else if (body.brain() instanceof ReflexDevBrain) {
            body.setBrain(BotBrain.NONE);
            // With no brain there is no next tick to end the hold on, so the body is let go of here.
            ReflexLayer.of(body).stop();
        }
        String name = body.getGameProfile().getName();
        ctx.getSource().sendSuccess(() -> Component.literal(name + ": reflex dev brain " + (on ? "on" : "off")), true);
        return 1;
    }

    private static int night(CommandContext<CommandSourceStack> ctx, NightPolicy policy) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        ReflexLayer.of(body).setNightPolicy(policy);
        ctx.getSource().sendSuccess(() -> Component.literal(body.getGameProfile().getName() + ": night policy " + policy), true);
        return 1;
    }
}
