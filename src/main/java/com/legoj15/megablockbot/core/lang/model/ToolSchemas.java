package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.lang.FixedGrammar;
import java.util.List;
import java.util.Locale;

/**
 * The tool set the model sees (design 3.3): small and high-level, because the planner and not the model expands
 * goals. One flat {@code set_goal} with a {@code kind} instead of a {@code oneOf} per goal: small local models
 * and grammar-constrained servers handle a flat object far better than a union, and
 * {@link GoalArguments} checks the fields each kind needs.
 */
final class ToolSchemas {
    private ToolSchemas() {}

    static final String SET_GOAL = "set_goal";
    static final String CANCEL = "cancel";
    static final String STATUS = "status";
    static final String PAUSE = "pause";
    static final String RESUME = "resume";
    static final String SEARCH_ITEMS = "search_items";
    static final String SAY = "say";

    /** The kinds {@code set_goal} takes, in the order the prompt lists them. */
    static final List<String> GOAL_KINDS = List.of("have_item", "tool_tier", "equip", "stockpile", "build", "go_to");

    /** The tools of an owner message, in the order the design names them. */
    static final List<ChatBackend.ToolSpec> INTERPRET = List.of(
            new ChatBackend.ToolSpec(SET_GOAL,
                    "Give the bot one thing to achieve; call it once per goal, in the order the owner asked. "
                            + "The bot's planner works out the steps, so never describe steps. Pick a kind and fill "
                            + "only the fields that kind uses. have_item: carry `count` (default 1) of `item`, "
                            + "optionally with an `enchantment` at least `level`. tool_tier: carry a `tool_class` tool "
                            + "of `tier` or better. equip: wear or hold each of `items`. stockpile: put `count` of `item` "
                            + "into the container at `position`. build: build `blueprint` at `position`. go_to: stand "
                            + "within `radius` blocks of `position`, or of the owner with `to_owner`. Item ids must "
                            + "exist: if you are not sure of an id, call search_items first.",
                    goalParameters()),
            new ChatBackend.ToolSpec(CANCEL,
                    "Drop the goal the bot is working on. With all=true also clear the goals waiting behind it.",
                    object(property("all", "boolean", "true to clear the waiting goals too; default false"))),
            new ChatBackend.ToolSpec(STATUS,
                    "Ask the bot to report its progress. Use it when the owner asks what the bot is doing or how far it is.",
                    object()),
            new ChatBackend.ToolSpec(PAUSE,
                    "Pause the current goal until the owner says to resume. Only when the owner asks to pause or wait.",
                    object()),
            new ChatBackend.ToolSpec(RESUME,
                    "Continue a paused goal. Only when the owner asks to carry on.", object()),
            new ChatBackend.ToolSpec(SEARCH_ITEMS,
                    "Look up the registry ids of items from words, best match first. Returns a JSON list of "
                            + "{id, name}. Use it to turn \"stone picks\" into minecraft:stone_pickaxe before set_goal.",
                    object(property("query", "string", "the words to look for, e.g. \"stone pickaxe\""),
                            property("limit", "integer", "how many matches, 1 to 15; default 8"),
                            "query")),
            new ChatBackend.ToolSpec(SAY,
                    "Reply to the owner in words, with no action: an answer, a clarifying question, or a refusal "
                            + "when the request is impossible or unclear. Keep it short.",
                    object(property("text", "string", "what to tell the owner"), "text")));

    private static JsonObject goalParameters() {
        JsonObject kind = property("kind", "string", "which goal");
        kind.add("enum", strings(GOAL_KINDS));
        JsonObject toolClass = property("tool_class", "string", "tool_tier: the tool family");
        toolClass.add("enum", strings(names(ToolClass.values())));
        JsonObject tier = property("tier", "string", "tool_tier: the lowest acceptable tier");
        tier.add("enum", strings(names(ToolTier.values())));
        JsonObject items = property("items", "array", "equip: registry ids; each goes in the slot its id implies");
        JsonObject itemId = new JsonObject();
        itemId.addProperty("type", "string");
        items.add("items", itemId);

        JsonObject position = property("position", "object",
                "build, stockpile, go_to: block coordinates, whole numbers");
        JsonObject axes = new JsonObject();
        for (String axis : List.of("x", "y", "z")) axes.add(axis, type("integer"));
        position.add("properties", axes);
        position.add("required", strings(List.of("x", "y", "z")));

        return object(
                kind,
                property("item", "string", "have_item, stockpile: a registry id like minecraft:stone_pickaxe"),
                property("count", "integer", "have_item (default 1), stockpile: how many, "
                        + FixedGrammar.MIN_COUNT + " to " + FixedGrammar.MAX_COUNT),
                property("enchantment", "string", "have_item: an enchantment id like minecraft:efficiency"),
                property("level", "integer", "have_item: the lowest enchantment level, 1 to "
                        + FixedGrammar.MAX_LEVEL + "; default 1"),
                toolClass, tier, items,
                property("blueprint", "string", "build: one of the blueprint names you were given"),
                position,
                property("to_owner", "boolean", "go_to: go to where the owner stands instead of a position"),
                property("radius", "integer", "go_to: how close is enough, in blocks, "
                        + FixedGrammar.MIN_RADIUS + " to " + FixedGrammar.MAX_RADIUS),
                "kind");
    }

    /**
     * A JSON Schema object from properties (each built by {@link #property}, whose name is its first key) and,
     * as a trailing string, the names of the required ones.
     */
    private static JsonObject object(Object... propertiesThenRequired) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Object part : propertiesThenRequired) {
            if (part instanceof JsonObject) {
                JsonObject property = (JsonObject) part;
                String name = property.remove("name").getAsString();
                properties.add(name, property);
            } else {
                required.add((String) part);
            }
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) schema.add("required", required);
        return schema;
    }

    /** A property schema; {@link #object} lifts its transient {@code name} out into the parent's key. */
    private static JsonObject property(String name, String type, String description) {
        JsonObject property = type(type);
        property.addProperty("name", name);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject type(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        return java.util.Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }
}
