package com.legoj15.megablockbot.core.lang;

import com.legoj15.megablockbot.core.goal.Goal;

/**
 * One thing the owner asked for (design 3.3, goal intake). The whole output of a {@link LanguageLayer} is a list
 * of these, in the order the owner asked for them; the body reads them without knowing which layer produced
 * them. Plain data: nothing here acts, the body does.
 */
public sealed interface Intent {

    /** Work on this goal (the executor decides whether it runs now or queues: design 3.4, goal queue). */
    record SetGoal(Goal goal) implements Intent {}

    /** Drop the active goal; {@code all} also clears the queue of waiting goals (design 3.4, cancel). */
    record Cancel(boolean all) implements Intent {}

    /** Ask for the current progress, rendered as text. */
    record Status() implements Intent {}

    /** Owner pause: pauses the active task; the goal stays RUNNING (design 3.4, goal states). */
    record Pause() implements Intent {}

    /** Clears a {@link Pause}. */
    record Resume() implements Intent {}

    /** Something to say back, with no action: an answer, a clarifying question, a refusal. */
    record Say(String text) implements Intent {}

    /** The layer could not turn the text into anything. {@code reason} is shown to the owner. */
    record Unknown(String reason) implements Intent {}
}
