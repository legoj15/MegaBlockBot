package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
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
 * <p>Naming a container is the owner showing it: the body notes what is in it as of that moment, the way it notes what
 * it sees when it opens one, because the plan that follows counts on it and the owner can see inside what they point
 * at. The note is a memory like any other: the task that opens the container writes what it really finds, and the
 * planner counts what is noted, marked as remembered while the container is out of sight.
 */
final class BotSupply {
    private BotSupply() {}

    /** Server thread. Answers with what the body will do, or why it will not. */
    static String use(BotPlayer body, String dimension, Pos pos) {
        ServerLevel level = body.serverLevel();
        String here = level.dimension().location().toString();
        if (!dimension.equals(here)) {
            return "I can only take from containers in the dimension I am in (" + here + "), and that is not " + dimension + ".";
        }
        BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!level.isLoaded(at)) {
            return "I cannot tell what is at " + BotSpeech.position(pos) + ": it is not loaded.";
        }
        SupplyChests.Spot spot = SupplyChests.look(level, at);
        if (spot == null) {
            return "There is no chest or other container at " + BotSpeech.position(pos) + ".";
        }
        BotMemory memory = BotMemory.of(body.getUUID());
        boolean fresh = memory.addSupply(new BotMemory.SupplyContainer(here, spot.pos(), spot.other()));
        memory.noteContainer(here, spot.pos(), spot.block(), spot.contents());
        return (fresh ? "I may take from " : "I already take from ") + describe(spot.pos(), spot.other(), spot.block())
            + ", which holds " + contents(spot.contents()) + ".";
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
        return "I will no longer take from " + describe(entry.pos(), entry.other(), null) + ".";
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
            String where = describe(entry.pos(), entry.other(), noted == null ? null : noted.block());
            String inside = noted == null ? "I have not seen inside it" : "last seen holding " + contents(noted.contents());
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
