package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.AbstractTask;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task kind to factory. Each feature registers its kinds from its own {@code init()}; the bot's runtime asks here when
 * the executor wants a task for a leaf. A task acts only through the body it is handed each tick.
 */
public final class TaskRegistry {
    private static final Map<String, TaskFactory> FACTORIES = new ConcurrentHashMap<>();

    private TaskRegistry() {}

    /** @throws IllegalStateException when the kind already has a factory: two features must not fight over one. */
    public static void register(String kind, TaskFactory factory) {
        if (FACTORIES.putIfAbsent(kind, factory) != null) {
            throw new IllegalStateException("task kind '" + kind + "' is already registered");
        }
    }

    public static Set<String> kinds() {
        return Set.copyOf(FACTORIES.keySet());
    }

    /**
     * The task for a leaf. A kind nobody registered gets a task that fails on its first tick, so the goal ends with a
     * reason the owner can read instead of waiting on nothing.
     */
    public static Task<BotPlayer> create(NodeSpec leaf, Map<String, String> hints) {
        TaskFactory factory = FACTORIES.get(leaf.taskKind());
        if (factory != null) return factory.create(leaf, hints);
        String kind = leaf.taskKind();
        return new AbstractTask<>() {
            @Override
            protected TickResult onTick(BotPlayer body) {
                return TickResult.failed(new Failure.Internal(null, "no task can do '" + kind + "' yet"));
            }
        };
    }
}
