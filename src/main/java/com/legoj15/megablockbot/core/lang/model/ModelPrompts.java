package com.legoj15.megablockbot.core.lang.model;

import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.lang.FixedGrammar;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The two system prompts (design 3.3). Short on purpose: a local model reads them on every message, and what the
 * model must get right is enforced by {@link GoalArguments}, not asked for here.
 */
final class ModelPrompts {
    private ModelPrompts() {}

    /** Longest enchantment list put in the prompt; the checks do not depend on the model having seen them all. */
    private static final int MAX_LISTED_ENCHANTMENTS = 120;

    /**
     * Who the model is, what the tools mean, and the facts of this moment: the bot's status paragraph, the
     * dimension, where the owner and the bot stand, the blueprints, the enchantments the game knows.
     */
    static String interpret(LanguageContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the language front end of MegaBlockBot, a Minecraft player that its owner controls "
                + "through chat. You do not act in the world yourself: you read the owner's message and call tools, "
                + "and the bot's body carries them out. Call the tools that fit what the owner asked. A message "
                + "can ask for several things (\"X, then Y\"): make one set_goal call for each, all in one reply, in "
                + "the order asked, and leave none out. Reply in plain text only when no tool applies.\n\n");
        prompt.append("Tools:\n"
                + "- set_goal: something for the bot to achieve (have an item, reach a tool tier, equip items, "
                + "stockpile, build, go to a place or to the owner). The planner works out how, so never list steps.\n"
                + "- search_items: find an item's registry id from words. An item id must exist, so when you are not "
                + "sure of one (ids look like minecraft:stone_pickaxe), search before set_goal.\n"
                + "- cancel, status, pause, resume: exactly what they say, and only when the owner clearly asked "
                + "for exactly that. When you are not sure what the owner wants, ask with say instead of guessing.\n"
                + "- say: a short reply to the owner, with no action: answer a question, ask what they meant, or "
                + "say that you cannot do something. Never claim that something is done: the bot acts only through "
                + "your tool calls.\n\n");
        prompt.append("Now:\n");
        prompt.append("Status: ").append(context.statusSummary().strip()).append('\n');
        prompt.append("Dimension: ").append(context.dimension()).append('\n');
        prompt.append("Owner stands at ").append(at(context.ownerPos())).append(". The bot stands at ")
                .append(at(context.botPos())).append(". (x y z block coordinates)\n");
        prompt.append("Blueprints it can build: ")
                .append(context.blueprints().isEmpty() ? "none" : String.join(", ", context.blueprints())).append('\n');
        if (!context.enchantments().isEmpty()) {
            List<String> sorted = new ArrayList<>(new TreeSet<>(context.enchantments()));
            int shown = Math.min(sorted.size(), MAX_LISTED_ENCHANTMENTS);
            prompt.append("Enchantment ids: ").append(String.join(", ", sorted.subList(0, shown)))
                    .append(sorted.size() > shown ? ", and " + (sorted.size() - shown) + " more" : "").append('\n');
        }
        prompt.append("Limits: a count is ").append(FixedGrammar.MIN_COUNT).append(" to ").append(FixedGrammar.MAX_COUNT)
                .append(", an enchantment level 1 to ").append(FixedGrammar.MAX_LEVEL)
                .append(", a go_to radius ").append(FixedGrammar.MIN_RADIUS).append(" to ").append(FixedGrammar.MAX_RADIUS)
                .append(". Keep replies to the owner short.");
        return prompt.toString();
    }

    /** For {@code explain}: no tools, one or two sentences, only the facts given. */
    static final String EXPLAIN = "You speak for MegaBlockBot, a Minecraft player controlled through chat. A goal it was "
            + "working on has ended without being completed. In one or two plain sentences, tell the owner what it "
            + "was doing and why it stopped, using only the facts you are given. Use item names, not registry ids. "
            + "No markdown, no lists, and do not promise to retry.";

    private static String at(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }
}
