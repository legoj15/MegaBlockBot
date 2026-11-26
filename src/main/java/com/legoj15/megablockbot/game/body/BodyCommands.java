package com.legoj15.megablockbot.game.body;

import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /aiplayer spawn|despawn|list|input}. Every one of them is allowed only to whoever may command the body
 * (its owner, a permission-level-2 source, the console); the root itself carries no permission requirement. A new
 * body also has to pass {@link BodyManager#checkMaySpawn}.
 */
final class BodyCommands {
    private BodyCommands() {}

    static void register() {
        AiPlayerCommand.add(root -> {
            root.then(Commands.literal("spawn").then(nameArgument().executes(BodyCommands::spawn)));
            root.then(Commands.literal("despawn").then(nameArgument().executes(BodyCommands::despawn)));
            root.then(Commands.literal("list").executes(BodyCommands::list));
            // For testing movement: hold an input for some ticks (default 20), then clear it.
            root.then(Commands.literal("input").then(nameArgument()
                .then(Commands.argument("forward", FloatArgumentType.floatArg(-1.0F, 1.0F))
                    .then(Commands.argument("strafe", FloatArgumentType.floatArg(-1.0F, 1.0F))
                        .then(jumpBranch("jump", true))
                        .then(jumpBranch("nojump", false))))));
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArgument() {
        return Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
            List<String> names = new ArrayList<>();
            for (BotPlayer body : BodyManager.of(ctx.getSource().getServer()).all()) names.add(body.getGameProfile().getName());
            return SharedSuggestionProvider.suggest(names, builder);
        });
    }

    private static ArgumentBuilder<CommandSourceStack, ?> jumpBranch(String literal, boolean jump) {
        return Commands.literal(literal)
            .executes(ctx -> input(ctx, jump, 20))
            .then(Commands.argument("ticks", IntegerArgumentType.integer(1, BodyConstants.MAX_HELD_INPUT_TICKS))
                .executes(ctx -> input(ctx, jump, IntegerArgumentType.getInteger(ctx, "ticks"))));
    }

    // ---------------------------------------------------------------------------------------------------------

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        BodyManager manager = BodyManager.of(source.getServer());
        BodyRecords.Entry known = manager.recordFor(name);

        // A body that already exists keeps its owner; only a new one takes the caller as owner.
        if (known != null && !BodyManager.mayCommand(known.ownerUuid(), source)) {
            return fail(source, known.name() + " belongs to " + (known.ownerName() != null ? known.ownerName() : "nobody") + "; you may not spawn it");
        }
        ServerPlayer caller = source.getPlayer();
        GameProfile owner;
        if (known != null) {
            owner = known.ownerUuid() == null ? null : new GameProfile(known.ownerUuid(), known.ownerName() != null ? known.ownerName() : "unknown");
        } else {
            owner = caller == null ? null : caller.getGameProfile();
        }
        // From a player: where they stand, facing the way they face. From the console: the overworld's spawn point.
        ServerLevel level = caller != null ? caller.serverLevel() : manager.overworld();
        Vec3 pos = caller != null ? caller.position() : BodyManager.surfaceSpawn(level);
        float yaw = caller != null ? caller.getYRot() : 0.0F;

        try {
            manager.checkMaySpawn(source, name);
            BotPlayer body = manager.spawn(name, owner, level, pos, yaw, null);
            source.sendSuccess(() -> Component.literal("Spawned " + body.getGameProfile().getName() + " at " + format(body.position())
                + " in " + body.level().dimension().location() + (body.ownerName() != null ? ", owned by " + body.ownerName() : ", with no owner")), true);
            return 1;
        } catch (BodyException e) {
            return fail(source, e.getMessage());
        }
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        BodyManager manager = BodyManager.of(source.getServer());
        BotPlayer body = manager.get(name);
        if (body == null) {
            return fail(source, name + " is not spawned");
        }
        if (!body.mayCommand(source)) return fail(source, "You may not command " + body.getGameProfile().getName());
        manager.despawn(body);
        source.sendSuccess(() -> Component.literal("Despawned " + body.getGameProfile().getName()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BodyManager manager = BodyManager.of(source.getServer());
        List<String> lines = new ArrayList<>();
        for (BotPlayer body : manager.all()) {
            if (!body.mayCommand(source)) continue;
            lines.add(body.getGameProfile().getName() + " [spawned] " + body.level().dimension().location() + " "
                + format(body.position()) + ", owner " + (body.ownerName() != null ? body.ownerName() : "none"));
        }
        for (BodyRecords.Entry entry : manager.recorded()) {
            if (manager.get(entry.uuid()) != null || !BodyManager.mayCommand(entry.ownerUuid(), source)) continue;
            lines.add(entry.name() + (entry.dead() ? " [died in hardcore, gone for good] " : " [not spawned] ") + entry.dimension() + " " + format(new Vec3(entry.x(), entry.y(), entry.z()))
                + ", owner " + (entry.ownerName() != null ? entry.ownerName() : "none"));
        }
        String text = lines.isEmpty() ? "No bodies." : lines.size() + " bod" + (lines.size() == 1 ? "y" : "ies") + ":\n" + String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    private static int input(CommandContext<CommandSourceStack> ctx, boolean jump, int ticks) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        float forward = FloatArgumentType.getFloat(ctx, "forward");
        float strafe = FloatArgumentType.getFloat(ctx, "strafe");
        BotPlayer body = BodyManager.of(source.getServer()).get(name);
        if (body == null) return fail(source, name + " is not spawned");
        if (!body.mayCommand(source)) return fail(source, "You may not command " + body.getGameProfile().getName());
        body.input().hold(forward, strafe, jump, ticks);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Holding forward %.2f, strafe %.2f, %s on %s for %d ticks",
            forward, strafe, jump ? "jump" : "no jump", body.getGameProfile().getName(), ticks)), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, @Nullable String message) {
        source.sendFailure(Component.literal(message == null ? "failed" : message));
        return 0;
    }

    private static String format(Vec3 pos) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }
}
