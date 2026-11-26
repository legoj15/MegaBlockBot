package com.legoj15.megablockbot.game.body;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * What drives a body. The executor, the reflexes and navigation plug in here; the body itself knows nothing about
 * goals (design 3.1, 3.4). Everything runs on the server thread. A brain must not throw from {@link #tick}: nothing
 * around the body tick swallows it (design 3.4), so an exception there takes the tick, and the server, down.
 */
public interface BotBrain {
    /** Does nothing and saves nothing. Shared by every body that has no brain of its own. */
    BotBrain NONE = new BotBrain() {
        @Override public void tick(BotPlayer body) {}
        @Override public void onDeath(BotPlayer body, DeathRecord death) {}
        @Override public void onRespawn(BotPlayer newBody) {}
        @Override public boolean wantsToStayLoaded() { return false; }
        @Override public void save(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {}
        @Override public String toString() { return "BotBrain.NONE"; }
    };

    /** Once per body tick, after the vanilla bookkeeping and before the body's input is applied: write {@link BotPlayer#input()}. */
    void tick(BotPlayer body);

    /** The body died. {@code death} is complete (item positions included); the body is dead and about to be replaced. */
    void onDeath(BotPlayer body, DeathRecord death);

    /** The brain moved to a new instance after a respawn. Called on the new body's first tick. */
    void onRespawn(BotPlayer newBody);

    /** True while a goal is active or queued: the body then stays logged in (and keeps its chunks) without its owner nearby. */
    boolean wantsToStayLoaded();

    /** Writes this brain's state into the body's own player data (under the body's {@code megablockbot} compound). */
    void save(CompoundTag tag, HolderLookup.Provider registries);

    /**
     * Reads what {@link #save} wrote. May throw on a bad save: the body logs it and replaces this brain with a fresh
     * one, so one bad save never keeps a body (or the server) from starting.
     */
    void load(CompoundTag tag, HolderLookup.Provider registries);
}
