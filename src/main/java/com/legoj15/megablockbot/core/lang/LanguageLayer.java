package com.legoj15.megablockbot.core.lang;

import com.legoj15.megablockbot.core.exec.GoalResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The boundary between the owner's words and the body (design 3.3): turns a chat message into typed
 * {@link Intent}s the body carries out. The fixed-grammar {@link FixedGrammar} implements it through M5; from
 * M6 a model-backed layer replaces it, and the body cannot tell which is in use.
 */
public interface LanguageLayer {

    /**
     * Turns one message from the owner into intents, in the order they were asked. May take a long time (a
     * model call): it is called off the server thread and completes the future from any thread. Never throws;
     * a failure is an {@link Intent.Unknown} intent.
     */
    CompletableFuture<List<Intent>> interpret(String message, LanguageContext context);

    /** A terminal goal result, for layers that explain results in their own words. Default: nothing. */
    default CompletableFuture<Optional<String>> explain(GoalResult result, LanguageContext context) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
