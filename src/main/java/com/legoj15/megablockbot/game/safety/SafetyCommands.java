package com.legoj15.megablockbot.game.safety;

import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /aiplayer safety check <x> <y> <z> [name]}: what block safety says about one position, and what the record
 * knows about it. For the named body, or for an ownerless one (world-spawn zone only, no block its own). Permission
 * level 2 (the console counts). The position must be loaded: the answer reads the block there.
 */
final class SafetyCommands {
    private SafetyCommands() {}

    static void init() {
        AiPlayerCommand.add(root -> root.then(Commands.literal("safety")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("check")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(context -> check(context, null))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            List<String> names = new ArrayList<>();
                            for (BotPlayer body : BodyManager.of(context.getSource().getServer()).all()) {
                                names.add(body.getGameProfile().getName());
                            }
                            return SharedSuggestionProvider.suggest(names, builder);
                        })
                        .executes(context -> check(context, StringArgumentType.getString(context, "name"))))))));
    }

    /** Returns 1 when the body may break the block there, 0 when it may not (or the command failed). */
    private static int check(CommandContext<CommandSourceStack> context, @Nullable String name) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        BlockState state = level.getBlockState(pos);
        BlockSafety safety = BlockSafety.of(source.getServer());

        BotPlayer body = null;
        if (name != null) {
            body = BodyManager.of(source.getServer()).get(name);
            if (body == null) {
                source.sendFailure(Component.literal(name + " is not spawned"));
                return 0;
            }
        }
        Verdict mayBreak = body != null ? safety.mayBreak(body, level, pos, state) : safety.evaluateBreakAsOwnerless(level, pos, state);
        Verdict mayUse = body != null ? safety.mayUseContainer(body, level, pos) : safety.evaluateUseAsOwnerless(level, pos);
        String who = body != null
            ? body.getGameProfile().getName() + " (owner " + (body.ownerName() != null ? body.ownerName() : "none") + ")"
            : "an ownerless body";

        String text = BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
            + " in " + level.dimension().location() + ", for " + who
            + "\n  break: " + describe(mayBreak)
            + "\n  use as container: " + describe(mayUse)
            + "\n  record: " + safety.placedBlocks(level).describe(pos.asLong());
        source.sendSuccess(() -> Component.literal(text), false);
        return mayBreak.allowed() ? 1 : 0;
    }

    private static String describe(Verdict verdict) {
        return verdict.allowed() ? "allowed" : "refused by " + verdict.rule() + " (" + verdict.detail() + ")";
    }
}
