/**
 * The executor contract of AI-PLAYER-DESIGN.md section 3.4 as types. Everything under {@code core} is
 * Minecraft-free: it compiles against the JDK and solver-core only (plus Gson, in {@code lang.model} alone: Minecraft
 * bundles it on every node, and the test classpath adds it), so it runs in plain JUnit and on every version node. Positions are plain ints, dimensions and items are id strings. Minecraft code converts at the
 * edge (the snapshot collector, the task factory) and never leaks a Minecraft type in here.
 */
package com.legoj15.megablockbot.core;
