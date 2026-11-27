package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.core.plan.Profile;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.build.Blueprint;
import com.legoj15.megablockbot.game.build.Blueprints;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
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
            root.then(Commands.literal("profile").then(nameArgument()
                    .then(Commands.literal("strict").executes(ctx -> profile(ctx, Profile.STRICT_SURVIVAL)))
                    .then(Commands.literal("assisted").executes(ctx -> profile(ctx, Profile.ASSISTED)))
                    .executes(BotCommands::showProfile)));
            root.then(Commands.literal("blueprint").requires(source -> source.hasPermission(2))
                    .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                            .then(Commands.argument("from", BlockPosArgument.blockPos())
                                    .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(BotCommands::saveBlueprint)))))
                    .then(Commands.literal("list").executes(BotCommands::listBlueprints)));
        });
    }

    /**
     * {@code /aiplayer blueprint save <name> <from> <to>} (permission level 2): the blocks of that box of the caller's
     * world as a blueprint, without entities, with the same name and size rules a loaded blueprint is held to. The
     * caller's world is the level the command runs in, so an owner standing in it names the blocks around them.
     */
    private static int saveBlueprint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        BlockPos from = BlockPosArgument.getLoadedBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(ctx, "to");
        String refused = Blueprints.save(source.getLevel(), name, from, to);
        if (refused != null) return fail(source, refused);
        int blocks = countBlocks(source, name);
        source.sendSuccess(() -> Component.literal("Saved blueprint '" + name + "' (" + blocks + " block(s))"), false);
        return 1;
    }

    /** {@code /aiplayer blueprint list}: the names in the blueprint folder. */
    private static int listBlueprints(CommandContext<CommandSourceStack> ctx) {
        List<String> names = Blueprints.names();
        String text = names.isEmpty() ? "No blueprints in " + Blueprints.folder()
                : "Blueprints: " + String.join(", ", names);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /**
     * How many blocks the saved blueprint holds, for the saved line. The blueprint itself counts what it kept; this only
     * reports.
     */
    private static int countBlocks(CommandSourceStack source, String name) {
        Blueprint saved = Blueprints.load(source.getServer(), name);
        return saved == null ? 0 : saved.size();
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

    /**
     * {@code /aiplayer profile <name> strict|assisted}: the body's world-knowledge profile (design 4.5), saved with
     * the brain. Whoever may command the body may set it (its owner, or a permission-level-2 source, the gate every
     * command here goes through); the default stays what it was, strict survival.
     */
    private static int profile(CommandContext<CommandSourceStack> ctx, Profile profile) {
        return withRuntime(ctx, (body, runtime) -> {
            runtime.setProfile(profile);
            ctx.getSource().sendSuccess(() -> Component.literal(
                body.getGameProfile().getName() + "'s profile is now " + profile.name().toLowerCase(java.util.Locale.ROOT)), false);
            return 1;
        });
    }

    /** {@code /aiplayer profile <name>}: what the body's profile is. */
    private static int showProfile(CommandContext<CommandSourceStack> ctx) {
        return withRuntime(ctx, (body, runtime) -> {
            ctx.getSource().sendSuccess(() -> Component.literal(
                body.getGameProfile().getName() + "'s profile is " + runtime.profile().name().toLowerCase(java.util.Locale.ROOT)), false);
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
