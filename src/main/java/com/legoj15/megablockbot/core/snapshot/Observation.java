package com.legoj15.megablockbot.core.snapshot;

/**
 * A strategy-declared fact in a {@link Snapshot} (design 3.4: "observations a strategy declares", such as the
 * current offers of the villager a librarian reroll is watching). Implementations are immutable,
 * Minecraft-free records owned by the strategy that reads them.
 */
public interface Observation {}
