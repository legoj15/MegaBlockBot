package com.legoj15.megablockbot.core.plan;

/** World-knowledge fairness (design 4.5). */
public enum Profile {
    /** Knows only what it has seen or explored: no locate, no hidden-block scans. */
    STRICT_SURVIVAL,
    /** May use structure/biome locate and ore scans. The default for dev-testing runs. */
    ASSISTED
}
