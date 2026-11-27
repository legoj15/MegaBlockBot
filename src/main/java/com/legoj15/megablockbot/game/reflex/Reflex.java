package com.legoj15.megablockbot.game.reflex;

/**
 * One reflex (design 3.2): a question, "do I need the body now?", and an action. The layer asks the reflexes in
 * priority order every tick and gives the body to the first that says yes; a reflex that already holds the body
 * answers with its own hysteresis (it needs the body until its cause is clearly over), so the executor's task is not
 * paused and resumed every few ticks. All calls are on the server thread, and an implementation must not throw
 * (the layer quarantines one that does).
 */
interface Reflex {
    /** Short name for the log and the status line. */
    String name();

    /** Whether this reflex needs the body now: to take it when it does not hold it, to keep it when it does. */
    boolean wants(ReflexContext ctx);

    /** Drives the body for this tick: inputs, the navigator, the game mode. Called only after {@link #wants} said yes. */
    void act(ReflexContext ctx);

    /**
     * Lets go: whatever this reflex started (a shield, a meal, a block break) stops, and its state is cleared, because
     * it is called both when the cause is over and when a higher reflex takes the body. The layer clears the body's
     * input, so a reflex need not.
     */
    void release(ReflexContext ctx);

    /** Why this reflex holds the body, one line, for {@code ReflexHold#cause} and the status. Meaningful while it holds it. */
    String cause();

    /** What the hold that has just ended came to ("killed 2, one got away"), for the log line that ends it. Empty when there is nothing to say. */
    default String outcome() {
        return "";
    }
}
