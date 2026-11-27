package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.snapshot.Snapshot;
import com.legoj15.megablockbot.game.body.BodyManager;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.command.AiPlayerCommand;
import com.legoj15.megablockbot.game.resolver.AcquisitionIndexes;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * {@code /aiplayer sight <name> [fresh [full]]}: what the planner is told a body can see ({@link BodySight}), and with
 * {@code fresh} what taking a new scan right now costs ({@code full}: without the pruning of sections). A dev command: it is how the cost of a scan is measured in a
 * real world and how a plan that goes past the trees is explained.
 */
final class SightCommand {
    /** Kinds listed in the reply, nearest first; the rest are counted. */
    static final int LISTED = 20;

    private SightCommand() {}

    static void init() {
        AiPlayerCommand.add(root -> root.then(Commands.literal("sight")
            .then(Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
                    List<String> names = new ArrayList<>();
                    for (BotPlayer body : BodyManager.of(ctx.getSource().getServer()).all()) {
                        names.add(body.getGameProfile().getName());
                    }
                    return SharedSuggestionProvider.suggest(names, builder);
                })
                .executes(ctx -> run(ctx, false, true))
                .then(Commands.literal("fresh").executes(ctx -> run(ctx, true, true))
                    // Every section that holds a wanted block, for comparing what the pruning saves.
                    .then(Commands.literal("full").executes(ctx -> run(ctx, true, false)))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, boolean fresh, boolean prune) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        BotPlayer body = BodyManager.of(source.getServer()).get(name);
        if (body == null) {
            source.sendFailure(Component.literal("no body " + name + " is logged in"));
            return 0;
        }
        if (!body.mayCommand(source)) {
            source.sendFailure(Component.literal("you may not command " + name));
            return 0;
        }
        BodySight.Wanted wanted = BodySight.wantedBy(AcquisitionIndexes.current());
        if (wanted.size() == 0) {
            source.sendFailure(Component.literal("the acquisition index has not been built yet: nothing to look for"));
            return 0;
        }

        StringBuilder text = new StringBuilder();
        Map<String, Integer> nearby;
        if (fresh) {
            BodySight.Scan scan = BodySight.scan(body.serverLevel(), body.blockPosition(), BodySight.RADIUS_CHUNKS,
                BodySight.BELOW, BodySight.ABOVE, wanted, prune);
            Map<String, Integer> found = new HashMap<>();
            scan.nearest().forEach((block, spot) -> found.put(block, spot.distanceFrom(body.blockPosition())));
            nearby = found;
            text.append(String.format(Locale.ROOT,
                "scan of %d wanted blocks, radius %d chunks, %d below to %d above: %d chunks read, %d not loaded, "
                    + "%d sections walked, %d skipped, %.3f ms%n",
                wanted.size(), BodySight.RADIUS_CHUNKS, BodySight.BELOW, BodySight.ABOVE, scan.chunksRead(),
                scan.chunksNotLoaded(), scan.sectionsWalked(), scan.sectionsSkipped(), scan.nanos() / 1_000_000.0));
        } else {
            BotMemory memory = BotMemory.of(body.getUUID());
            int before = memory.sightScans();
            Snapshot snapshot = SnapshotCollector.collect(body, memory);
            nearby = snapshot.nearbyBlocks();
            text.append(String.format(Locale.ROOT, "snapshot sight (%s; %d scans kept for this body)%n",
                memory.sightScans() > before ? "taken now" : "kept from an earlier snapshot", memory.sightScans()));
        }

        List<Map.Entry<String, Integer>> nearest = new ArrayList<>(nearby.entrySet());
        nearest.sort(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        text.append(nearest.size()).append(" kinds seen");
        for (int i = 0; i < Math.min(LISTED, nearest.size()); i++) {
            Map.Entry<String, Integer> entry = nearest.get(i);
            text.append(i == 0 ? ": " : ", ").append(entry.getKey().replace("minecraft:", "")).append(' ').append(entry.getValue());
        }
        if (nearest.size() > LISTED) text.append(", and ").append(nearest.size() - LISTED).append(" farther");
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return nearby.size();
    }
}
