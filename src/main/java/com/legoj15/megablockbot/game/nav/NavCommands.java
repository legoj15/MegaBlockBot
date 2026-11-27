package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /aiplayer nav <name> goto <x> <y> <z> [radius] | gotoxz <x> <z> | mine <block> <count> | stop | status}:
 * a debugging tool that drives a body's navigator by hand. It gives the body a small brain ({@link DevBrain}) that
 * ticks the navigator and keeps the numbers an evidence run needs; the real brain replaces it later.
 */
final class NavCommands {
    private NavCommands() {}

    static void register() {
        AiPlayerCommand.add(root -> root.then(Commands.literal("nav").then(nameArgument()
            .then(Commands.literal("goto").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> goTo(ctx, 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                    .executes(ctx -> goTo(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
            // Any height at a column: for a far target whose ground level is not known before the body gets there.
            .then(Commands.literal("gotoxz").then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(NavCommands::goToXZ))))
            .then(Commands.literal("mine").then(Commands.argument("block", ResourceLocationArgument.id())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 2304)).executes(NavCommands::mine))))
            .then(Commands.literal("stop").executes(NavCommands::stop))
            .then(Commands.literal("status").executes(NavCommands::status)))));
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

    private static DevBrain brainOf(BotPlayer body) {
        if (body.brain() instanceof DevBrain dev) return dev;
        DevBrain dev = new DevBrain();
        body.setBrain(dev);
        return dev;
    }

    private static int goTo(CommandContext<CommandSourceStack> ctx, int radius) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        DevBrain brain = brainOf(body);
        BotNavigator.of(body).goTo(target, radius);
        brain.started(body, "goto " + format(target) + (radius > 0 ? " r" + radius : ""));
        ctx.getSource().sendSuccess(() -> Component.literal(body.getGameProfile().getName() + " heading to " + format(target)
            + " from " + format(body.position())), true);
        return 1;
    }

    private static int goToXZ(CommandContext<CommandSourceStack> ctx) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        DevBrain brain = brainOf(body);
        BotNavigator.of(body).goToXZ(x, z);
        brain.started(body, "gotoxz " + x + " " + z);
        ctx.getSource().sendSuccess(() -> Component.literal(body.getGameProfile().getName() + " heading to column " + x + " " + z
            + " from " + format(body.position())), true);
        return 1;
    }

    private static int mine(CommandContext<CommandSourceStack> ctx) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "block");
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("unknown block " + id));
            return 0;
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");
        List<Item> wanted = dropsOf(body.serverLevel(), block.get());
        DevBrain brain = brainOf(body);
        BotNavigator.of(body).mine(List.of(block.get()), wanted, count);
        brain.started(body, "mine " + id + " x" + count);
        ctx.getSource().sendSuccess(() -> Component.literal(body.getGameProfile().getName() + " mining " + count + " " + id
            + " for " + wanted.stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList()), true);
        return 1;
    }

    /**
     * What breaking the block with a bare hand yields, from its loot table: what the command waits for. A block with
     * random drops can yield something this sample misses; this is a debugging command, the tasks name their item.
     */
    private static List<Item> dropsOf(ServerLevel level, Block block) {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : Block.getDrops(block.defaultBlockState(), level, BlockPos.ZERO, null)) items.add(stack.getItem());
        if (items.isEmpty() && block.asItem() != Items.AIR) items.add(block.asItem());
        return List.copyOf(items);
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        BotNavigator.of(body).stop();
        if (body.brain() instanceof DevBrain dev) dev.report(body, "stopped");
        ctx.getSource().sendSuccess(() -> Component.literal(body.getGameProfile().getName() + " stopped"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Optional<BotPlayer> found = body(ctx);
        if (found.isEmpty()) return 0;
        BotPlayer body = found.get();
        BotNavigator navigator = BotNavigator.of(body);
        StringBuilder text = new StringBuilder(body.getGameProfile().getName())
            .append(": ").append(navigator.state())
            .append(" at ").append(format(body.position()))
            .append(" in ").append(body.level().dimension().location());
        if (navigator.failure() != null) text.append(", failure: ").append(navigator.failure());
        if (body.brain() instanceof DevBrain dev) text.append(", ").append(dev.summary()).append(", ").append(dev.activity(body));
        ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
        return 1;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String format(Vec3 pos) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }

    /**
     * Ticks the navigator and measures the command: ticks taken and the largest single-tick move (a teleport would
     * show as one huge step). Logs one line at INFO when a command ends. Keeps the body loaded while working, so a
     * console-spawned body with no owner nearby is not despawned mid-route. Saves nothing.
     */
    static final class DevBrain implements BotBrain {
        private String command = "-";
        private Vec3 start = Vec3.ZERO;
        private Vec3 last;
        private Level lastLevel;
        private int ticks;
        private double maxStep;
        private boolean reported = true;
        private int waterTicks;
        private double minY;
        private double maxY;
        private int brokenAtStart;
        private int placedAtStart;

        void started(BotPlayer body, String what) {
            command = what;
            start = body.position();
            last = null;
            ticks = 0;
            maxStep = 0.0;
            reported = false;
            waterTicks = 0;
            minY = maxY = body.getY();
            brokenAtStart = interaction(body).blocksBroken();
            placedAtStart = interaction(body).blocksPlaced();
        }

        private static BodyInteractionManager interaction(BotPlayer body) {
            return (BodyInteractionManager) body.getInteractionManager();
        }

        @Override
        public void tick(BotPlayer body) {
            BotNavigator navigator = BotNavigator.of(body);
            Vec3 now = body.position();
            // A respawn or a dimension change puts the body somewhere else in one step; that is not a move.
            if (last != null && lastLevel == body.level()) maxStep = Math.max(maxStep, now.distanceTo(last));
            last = now;
            lastLevel = body.level();
            navigator.tick(body);
            if (navigator.state() == BotNavigator.State.WORKING) {
                ticks++;
                // A long route takes minutes: a line every 10 s shows where it is, or where it stalled.
                if (ticks % 200 == 0) {
                    MegaBlockBot.LOGGER.info("nav {}: {} working, {} ticks, at {}", body.getGameProfile().getName(), command, ticks,
                        format(body.position()));
                }
                if (body.isInWater()) waterTicks++;
                minY = Math.min(minY, body.getY());
                maxY = Math.max(maxY, body.getY());
            } else if (!reported) {
                report(body, navigator.state() + (navigator.failure() != null ? " (" + navigator.failure() + ")" : ""));
            }
        }

        void report(BotPlayer body, String outcome) {
            if (reported) return;
            reported = true;
            MegaBlockBot.LOGGER.info("nav {}: {} -> {} after {} ticks; start {}, now {}, largest single-tick move {} blocks; {}",
                body.getGameProfile().getName(), command, outcome, ticks, format(start), format(body.position()),
                String.format(Locale.ROOT, "%.3f", maxStep), activity(body));
        }

        String summary() {
            return command + ", " + ticks + " ticks, largest single-tick move " + String.format(Locale.ROOT, "%.3f", maxStep);
        }

        /** What the route took: water, digging, placing (bridging and pillaring) and the height range covered. */
        private String activity(BotPlayer body) {
            return String.format(Locale.ROOT, "%d ticks in water, %d blocks broken, %d placed, y %.1f to %.1f",
                waterTicks, interaction(body).blocksBroken() - brokenAtStart, interaction(body).blocksPlaced() - placedAtStart,
                minY, maxY);
        }

        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) { last = null; }

        @Override
        public boolean wantsToStayLoaded() {
            return !reported;
        }

        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
    }
}
