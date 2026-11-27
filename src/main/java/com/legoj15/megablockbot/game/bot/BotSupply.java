package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.planner.LookInside;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.BotMemory;
import com.legoj15.megablockbot.game.plan.SupplyChests;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What the owner's {@code use chest at}, {@code forget chest at} and {@code chests} do (design 3.5, supply containers),
 * and the sentence that answers each. They reach here only through {@link BotTalk}, which is reached only by whoever
 * may command the body, so the same rule that guards a goal guards these.
 *
 * <p>Naming a container records where it is and which block it is, and nothing of what is in it: a player cannot see
 * into a closed chest, and the body does only what a player can. What it holds is unknown until the body has opened it, so
 * naming one queues the body's {@link LookInside} goal for it, ahead of whatever the owner says next ({@code use chest at
 * x y z; make 4 stick} looks first and then plans the sticks knowing what is there). What the body notes then is what the
 * planner counts, marked as remembered, until the body opens the container again.
 */
final class BotSupply {
    private BotSupply() {}

    /** Server thread. Answers with what the body will do, or why it will not. */
    static String use(BotRuntime runtime, String dimension, Pos pos) {
        BotPlayer body = runtime.body();
        ServerLevel level = body.serverLevel();
        String here = level.dimension().location().toString();
        if (!dimension.equals(here)) {
            return "I can only take from containers in the dimension I am in (" + here + "), and that is not " + dimension + ".";
        }
        BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!level.isLoaded(at)) {
            return "I cannot tell what is at " + BotSpeech.position(pos) + ": it is not loaded.";
        }
        SupplyChests.Spot spot = SupplyChests.find(level, at);
        if (spot == null) {
            return "There is no chest or other container at " + BotSpeech.position(pos) + ".";
        }
        // The owner's word, which also forgets any note of it: what it held before is not known to be what it holds now.
        boolean fresh = BotMemory.of(body.getUUID()).addSupply(new BotMemory.SupplyContainer(here, spot.pos(), spot.other(), spot.block()));
        runtime.submit(LookInside.goal(here, new Pos(spot.pos().getX(), spot.pos().getY(), spot.pos().getZ())));
        return (fresh ? "I may take from " : "I already take from ") + describe(spot.pos(), spot.other(), spot.block())
            + ". I will go and look inside it" + (fresh ? "." : " again.");
    }

    /** Server thread. */
    static String forget(BotPlayer body, String dimension, Pos pos) {
        BotMemory memory = BotMemory.of(body.getUUID());
        BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
        BotMemory.SupplyContainer entry = memory.supplyAt(dimension, at);
        if (entry == null) {
            return "I was not told to take from a container at " + BotSpeech.position(pos) + ".";
        }
        memory.removeSupply(dimension, at);
        // What it noted was for taking from it; a container it placed itself keeps its note, since that is still its own.
        if (!memory.isPlaced(dimension, entry.pos())) memory.forgetContainer(dimension, entry.pos());
        return "I will no longer take from " + describe(entry.pos(), entry.other(), entry.block()) + ".";
    }

    /** Server thread. */
    static String list(BotPlayer body) {
        BotMemory memory = BotMemory.of(body.getUUID());
        List<BotMemory.SupplyContainer> named = memory.supply();
        if (named.isEmpty()) {
            return "I have no container to take from. Say 'use chest at <x> <y> <z>' to name one.";
        }
        List<String> lines = new ArrayList<>();
        for (BotMemory.SupplyContainer entry : named) {
            BotMemory.RememberedContainer noted = memory.containerAt(entry.dimension(), entry.pos());
            String where = describe(entry.pos(), entry.other(), entry.block());
            String inside = noted == null ? "I have not looked inside it yet" : "last seen holding " + contents(noted.contents());
            lines.add(where + (entry.dimension().equals("minecraft:overworld") ? "" : " in " + entry.dimension()) + ": " + inside);
        }
        return "I may take from " + named.size() + (named.size() == 1 ? " container" : " containers") + ": "
            + String.join("; ", lines) + ".";
    }

    /** {@code the chest at 1 2 3}, {@code the double chest at 1 2 3 and 2 2 3}; the block's id decides the noun. */
    private static String describe(BlockPos pos, BlockPos other, String block) {
        String noun = block == null || block.isEmpty() ? "container" : BotSpeech.item(ItemKey.of(block));
        String at = BotSpeech.position(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        if (other == null) return "the " + noun + " at " + at;
        return "the double " + noun + " at " + at + " and " + BotSpeech.position(new Pos(other.getX(), other.getY(), other.getZ()));
    }

    private static String contents(Map<ItemKey, Integer> contents) {
        if (contents.isEmpty()) return "nothing";
        List<String> parts = new ArrayList<>();
        new TreeMap<>(contents).forEach((item, count) -> parts.add(count + " " + BotSpeech.item(item)));
        return String.join(", ", parts);
    }
}
