package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.Task;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Map;

/** Makes the task that carries out one leaf of a plan (design 3.4). Registered per task kind in {@link TaskRegistry}. */
@FunctionalInterface
public interface TaskFactory {
    /**
     * @param leaf  the plan leaf: its goal is the postcondition, its params are what the planner (or the executor,
     *              for the nodes it inserts itself) wrote for this kind
     * @param hints the checkpoint an earlier task for the same node left, or empty
     */
    Task<BotPlayer> create(NodeSpec leaf, Map<String, String> hints);
}
