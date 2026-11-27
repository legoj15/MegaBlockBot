package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.game.body.BotBrain;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.body.DeathRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * The dev brain of {@code /aiplayer reflex <name> on}: it only ticks the reflex layer, and keeps the body loaded, so
 * a body with nothing else to do can be left to look after itself for a night (the issue's "survives a night"). The real
 * brain, when it exists, ticks the layer itself and passes the hold to the executor. Saves nothing.
 */
final class ReflexDevBrain implements BotBrain {
    @Override
    public void tick(BotPlayer body) {
        ReflexLayer.of(body).tick(body);
    }

    /** A death is a result of an evidence run: say what did it. */
    @Override
    public void onDeath(BotPlayer body, DeathRecord death) {
        MegaBlockBot.LOGGER.info("reflex {}: DIED: {} (at {}, {}; reflex status: {})", body.getGameProfile().getName(),
            body.getCombatTracker().getDeathMessage().getString(), death.pos().toShortString(), death.dimension(), ReflexLayer.of(body).status());
    }

    @Override public void onRespawn(BotPlayer newBody) {}

    @Override
    public boolean wantsToStayLoaded() {
        return true;
    }

    @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
    @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}

    @Override
    public String toString() {
        return "ReflexDevBrain";
    }
}
