// SPDX-License-Identifier: LGPL-3.0-or-later
package com.legoj15.megablockbot.nav;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Module-owned services the ported engine ({@code com.legoj15.megablockbot.nav.engine}) used to take from
 * PlayerEngine's root class: the logger, the mod id and name, the path-search thread pool and the directories the
 * settings file is read from. Loader-neutral: the loader entrypoint ({@link NavModule} on NeoForge) sets the
 * directories at construction and calls {@link #shutdownExecutor()} when a server stops.
 */
public final class NavRuntime {
    public static final String MOD_ID = "megablockbot_nav";
    public static final String MOD_NAME = "MegaBlockBot Navigation";
    /** log4j, as upstream used, so the engine's logging call sites stay as they were. */
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    /** Upper bound on search threads. Tasks beyond it wait in the queue instead of starting more threads. */
    public static final int MAX_THREADS = 4;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final Object EXECUTOR_LOCK = new Object();
    private static ThreadPoolExecutor executor;

    private static volatile Path configDir;
    private static volatile Path gameDir;

    private NavRuntime() {}

    /**
     * The path-search pool: at most {@link #MAX_THREADS} daemon threads named {@code MegaBlockBot nav N}, idle ones
     * end after 60 seconds. Created on first use and again after {@link #shutdownExecutor()}, so a second server in
     * the same JVM (an integrated server reopened) gets a fresh pool.
     */
    public static ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown()) {
                ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    MAX_THREADS, MAX_THREADS, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        Thread thread = new Thread(r, "MegaBlockBot nav " + THREAD_COUNTER.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }) {
                    // Every task is guarded: a throw from a search or a scan is logged here and ends that task only.
                    // Unguarded, it would kill the worker thread and print to stderr with no module context.
                    @Override
                    public void execute(Runnable task) {
                        super.execute(() -> {
                            try {
                                task.run();
                            } catch (Throwable t) {
                                LOGGER.error("Navigation task failed", t);
                            }
                        });
                    }
                };
                pool.allowCoreThreadTimeOut(true);
                executor = pool;
            }
            return executor;
        }
    }

    /** Stops the pool: running searches are interrupted, queued ones dropped. Called when a server stops. */
    public static void shutdownExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    /** Set by the loader entrypoint: the module's own config directory ({@code config/megablockbot_nav}). */
    public static void setConfigDir(Path dir) {
        configDir = dir;
    }

    /** The module's config directory, or null when no entrypoint set one. */
    public static Path configDir() {
        return configDir;
    }

    /** Set by the loader entrypoint: the game directory (where {@code schematics/} is looked up). */
    public static void setGameDir(Path dir) {
        gameDir = dir;
    }

    /** The game directory, or null when no entrypoint set one. */
    public static Path gameDir() {
        return gameDir;
    }
}
