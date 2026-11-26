package com.legoj15.megablockbot.core.exec;

import com.legoj15.megablockbot.core.task.Task;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a task's checkpoint at the boundary between the task and the executor (design 3.4, Persistence): the one
 * place a map from task code becomes the executor's own. A task is host code, so what it returns is not trusted:
 * a null key or value is dropped and logged so the save form never holds a null, and a throw is logged and treated
 * as "nothing new", so the last stored checkpoint stays. Package-private.
 */
final class Checkpoints {

    private Checkpoints() {}

    /**
     * The task's checkpoint without null keys or values; empty when it has none, or when reading it threw.
     * {@code when} says which boundary this is (release or save), for the log.
     */
    static Map<String, String> read(ExecutorHost<?> host, String identity, Task<?> task, String when) {
        try {
            Map<String, String> raw = task.checkpoint();
            if (raw == null || raw.isEmpty()) return Map.of();
            Map<String, String> clean = new LinkedHashMap<>();
            boolean dropped = false;
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    dropped = true;
                } else {
                    clean.put(e.getKey(), e.getValue());
                }
            }
            if (dropped) {
                host.log("checkpoint of " + identity + " held a null key or value at " + when + "; dropped", null);
            }
            return clean;
        } catch (RuntimeException | Error e) {
            host.log("task checkpoint threw at " + when, e);
            return Map.of();
        }
    }
}
