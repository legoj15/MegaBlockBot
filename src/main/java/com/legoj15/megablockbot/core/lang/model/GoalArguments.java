package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.lang.FixedGrammar;
import com.legoj15.megablockbot.core.lang.LanguageContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the arguments of a model's {@code set_goal} call into a typed {@link Goal}, or says what is wrong with
 * them. This is the gate of design 3.3: everything the model names is checked against the game before it becomes a
 * goal, and a goal the checks did not pass never exists. An item id must be one the registry has, an enchantment
 * one the context knows (while that set is not empty), counts, levels and radii must be in the ranges
 * {@link FixedGrammar} holds the owner to, and a blueprint must be listed. The text of a refusal goes back to the
 * model so it can search and correct itself, and, failing that, to the owner.
 */
final class GoalArguments {
    private GoalArguments() {}

    /** The radius of a {@code go_to}: close enough to a spot, and to the owner (the grammar's two defaults). */
    private static final int DEFAULT_RADIUS = 2, DEFAULT_OWNER_RADIUS = 3;

    /** How many known names a refusal lists, so the model can pick one without the message growing without bound. */
    private static final int LISTED_NAMES = 12;

    /** The outcome of checking one call: exactly one of {@code goal} and {@code problem} is non-null. */
    record Outcome(Goal goal, String problem) {
        static Outcome accepted(Goal goal) {
            return new Outcome(goal, null);
        }

        static Outcome refused(String problem) {
            return new Outcome(null, problem);
        }
    }

    /** Thrown inside the checks to unwind to {@link #check}; carries the sentence for the model. No stack trace. */
    private static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message, null, false, false);
        }
    }

    static Outcome check(JsonObject arguments, LanguageContext context) {
        try {
            String kind = text(arguments, "kind");
            if (kind == null) {
                throw new Refusal("set_goal needs 'kind', one of " + String.join(", ", ToolSchemas.GOAL_KINDS));
            }
            switch (kind.strip().toLowerCase(Locale.ROOT)) {
                case "have_item": return Outcome.accepted(haveItem(arguments, context));
                case "tool_tier": return Outcome.accepted(toolTier(arguments));
                case "equip": return Outcome.accepted(equip(arguments, context));
                case "stockpile": return Outcome.accepted(stockpile(arguments, context));
                case "build": return Outcome.accepted(build(arguments, context));
                case "go_to": return Outcome.accepted(goTo(arguments, context));
                default:
                    throw new Refusal("unknown kind '" + kind + "'; use one of " + String.join(", ", ToolSchemas.GOAL_KINDS));
            }
        } catch (Refusal refusal) {
            return Outcome.refused(refusal.getMessage());
        }
    }

    // ── one method per kind ───────────────────────────────────────────────────────────────────────

    private static Goal haveItem(JsonObject arguments, LanguageContext context) {
        ItemKey key = item(arguments, "item", context);
        Integer given = whole(arguments, "count");
        int count = given == null ? 1 : inRange("count", given, FixedGrammar.MIN_COUNT, FixedGrammar.MAX_COUNT);
        String enchantment = text(arguments, "enchantment");
        Integer level = whole(arguments, "level");
        if (enchantment == null) {
            if (level != null) throw new Refusal("'level' only goes with an 'enchantment'");
            return new Goal.HaveItem(key, count);
        }
        String id = namespaced(enchantment.strip().toLowerCase(Locale.ROOT));
        Set<String> known = context.enchantments();
        // An empty set means the game's enchantments are not known yet: the id is taken as named.
        if (!known.isEmpty() && !known.contains(id)) {
            throw new Refusal("enchantment '" + id + "' is not one the game knows; known ones include "
                    + listed(known));
        }
        int checked = level == null ? 1 : inRange("level", level, 1, FixedGrammar.MAX_LEVEL);
        return new Goal.HaveItem(key, count, ComponentCheck.enchantment(id, checked));
    }

    private static Goal toolTier(JsonObject arguments) {
        ToolClass toolClass = named(arguments, "tool_class", ToolClass.values());
        ToolTier tier = named(arguments, "tier", ToolTier.values());
        return new Goal.HaveToolTier(toolClass, tier);
    }

    private static Goal equip(JsonObject arguments, LanguageContext context) {
        List<String> ids = new ArrayList<>();
        JsonElement items = arguments.get("items");
        if (items != null && !items.isJsonNull()) {
            if (!items.isJsonArray()) throw new Refusal("'items' must be a list of item ids");
            for (JsonElement id : items.getAsJsonArray()) ids.add(primitiveText(id, "items"));
        } else if (text(arguments, "item") != null) {
            ids.add(text(arguments, "item"));
        }
        if (ids.isEmpty()) throw new Refusal("equip needs 'items', a list of item ids");
        Map<EquipSlot, ItemKey> slots = new EnumMap<>(EquipSlot.class);
        for (String id : ids) {
            ItemKey key = itemById(id, context);
            EquipSlot slot = FixedGrammar.slotFor(key.path());
            ItemKey earlier = slots.putIfAbsent(slot, key);
            if (earlier != null) {
                throw new Refusal(earlier + " and " + key + " both go in the " + slot.name().toLowerCase(Locale.ROOT)
                        + " slot");
            }
        }
        return new Goal.Equip(slots);
    }

    private static Goal stockpile(JsonObject arguments, LanguageContext context) {
        ItemKey key = item(arguments, "item", context);
        Integer count = whole(arguments, "count");
        if (count == null) throw new Refusal("stockpile needs a 'count'");
        inRange("count", count, FixedGrammar.MIN_COUNT, FixedGrammar.MAX_COUNT);
        return new Goal.Stockpile(key, count, context.dimension(), position(arguments));
    }

    private static Goal build(JsonObject arguments, LanguageContext context) {
        String named = text(arguments, "blueprint");
        if (named == null) throw new Refusal("build needs a 'blueprint', one of " + listed(context.blueprints()));
        Pos anchor = position(arguments);
        String blueprint = blueprintNamed(named, context);
        if (blueprint == null) {
            throw new Refusal("there is no blueprint called '" + named + "'; the known ones are "
                    + (context.blueprints().isEmpty() ? "none" : listed(context.blueprints())));
        }
        return new Goal.Build(blueprint, context.dimension(), anchor);
    }

    private static Goal goTo(JsonObject arguments, LanguageContext context) {
        boolean toOwner = flag(arguments, "to_owner");
        Pos pos = toOwner ? context.ownerPos() : position(arguments);
        Integer radius = whole(arguments, "radius");
        int checked = radius == null ? (toOwner ? DEFAULT_OWNER_RADIUS : DEFAULT_RADIUS)
                : inRange("radius", radius, FixedGrammar.MIN_RADIUS, FixedGrammar.MAX_RADIUS);
        return new Goal.Reach(context.dimension(), pos, checked);
    }

    // ── fields ────────────────────────────────────────────────────────────────────────────────────

    /** An item named in {@code field}, which must exist. */
    private static ItemKey item(JsonObject arguments, String field, LanguageContext context) {
        String id = text(arguments, field);
        if (id == null) throw new Refusal("'" + field + "' is required: an item id like minecraft:stone_pickaxe");
        return itemById(id, context);
    }

    private static ItemKey itemById(String id, LanguageContext context) {
        return context.items().byId(id.strip()).orElseThrow(() -> new Refusal(
                "item '" + id + "' does not exist; call search_items to find its registry id"));
    }

    /** A block position from {@code position: {x, y, z}}, whole numbers. */
    private static Pos position(JsonObject arguments) {
        JsonElement element = arguments.get("position");
        String shape = "'position' is required, an object like {\"x\":100,\"y\":64,\"z\":-20}";
        if (element == null || element.isJsonNull()) throw new Refusal(shape);
        if (!element.isJsonObject()) throw new Refusal(shape);
        JsonObject position = element.getAsJsonObject();
        Integer x = whole(position, "x"), y = whole(position, "y"), z = whole(position, "z");
        if (x == null || y == null || z == null) throw new Refusal(shape);
        return new Pos(x, y, z);
    }

    /** A value of an enum named case-insensitively, e.g. {@code pickaxe} for {@link ToolClass#PICKAXE}. */
    private static <E extends Enum<E>> E named(JsonObject arguments, String field, E[] values) {
        String name = text(arguments, field);
        List<String> options = new ArrayList<>();
        for (E value : values) {
            options.add(value.name().toLowerCase(Locale.ROOT));
            if (name != null && value.name().equalsIgnoreCase(name.strip())) return value;
        }
        if (name == null) throw new Refusal("tool_tier needs '" + field + "', one of " + String.join(", ", options));
        throw new Refusal("unknown " + field + " '" + name + "'; use one of " + String.join(", ", options));
    }

    /**
     * The registry's spelling of a blueprint the model named, ignoring case and extra whitespace the way the
     * grammar does, or null.
     */
    private static String blueprintNamed(String wanted, LanguageContext context) {
        String normalized = wanted.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return null;
        for (String blueprint : context.blueprints()) {
            if (blueprint.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ").equals(normalized)) return blueprint;
        }
        return null;
    }

    private static int inRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new Refusal("'" + field + "' must be from " + min + " to " + max + ", not " + value);
        }
        return value;
    }

    /** A field's text, or null when absent or null; a non-text value is a refusal, never coerced. */
    private static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return null;
        return primitiveText(element, field);
    }

    private static String primitiveText(JsonElement element, String field) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new Refusal("'" + field + "' must be text");
        }
        return element.getAsString();
    }

    /** A whole number in the int range, or null when absent; a fraction, an overflow or a word is a refusal. */
    private static Integer whole(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return null;
        String shape = "'" + field + "' must be a whole number";
        if (!element.isJsonPrimitive()) throw new Refusal(shape);
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            if (primitive.isNumber()) return new java.math.BigDecimal(primitive.getAsString()).intValueExact();
            // A model that quotes its numbers ("64") is still unambiguous.
            if (primitive.isString()) return Integer.valueOf(primitive.getAsString().strip());
        } catch (ArithmeticException | NumberFormatException notWhole) {
            // falls through to the refusal
        }
        throw new Refusal(shape);
    }

    private static boolean flag(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isString()) return primitive.getAsString().strip().equalsIgnoreCase("true");
        }
        throw new Refusal("'" + field + "' must be true or false");
    }

    /** A bare enchantment id gets the {@code minecraft:} namespace, as the grammar and {@link ItemKey#of} do. */
    private static String namespaced(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static String listed(java.util.Collection<String> names) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(names));
        String shown = String.join(", ", sorted.subList(0, Math.min(sorted.size(), LISTED_NAMES)));
        return sorted.size() > LISTED_NAMES ? shown + ", ..." : shown;
    }

    /** A true/false field that defaults to false, also when the model got it wrong (used by {@code cancel}). */
    static boolean flagged(JsonObject arguments, String field) {
        try {
            return flag(arguments, field);
        } catch (Refusal notAFlag) {
            return false;
        }
    }
}
