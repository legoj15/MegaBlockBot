/**
 * The executor contract of AI-PLAYER-DESIGN.md section 3.4 as types. Everything under {@code core} is
 * Minecraft-free: it compiles against the JDK and solver-core only, so it runs in plain JUnit and on every
 * version node. Positions are plain ints, dimensions and items are id strings. Minecraft code converts at the
 * edge (the snapshot collector, the task factory) and never leaks a Minecraft type in here.
 */
package com.legoj15.megablockbot.core;
