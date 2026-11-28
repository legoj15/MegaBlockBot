package com.legoj15.megablockbot.core.lang;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * The fixed-grammar {@link LanguageLayer}: design 3.3 through M5, where the note under design section 6 allows
 * "a thin stub (fixed command parser)". Interpreting is pure string work over the context, so the future
 * completes immediately (already done). Case-insensitive, extra whitespace ignored; the words of one item are
 * the words the owner typed ({@code stone pickaxe}), joined with underscores for the exact lookup.
 *
 * <p>Commands, one line per form (square brackets optional):
 * <ul>
 *   <li>{@code get|make|craft|have [<count>] <item>} — {@code SetGoal(HaveItem(item, count or 1))}</li>
 *   <li>{@code give me [<count>] <item>} / {@code bring me [<count>] <item>} — {@code SetGoal(Deliver(item, count or
 *       1, the speaker))} (issue #19): the same items, handed over at the speaker's feet. An unstated count is one,
 *       and a leading {@code a} or {@code an} is not part of the item's name. There must be a player behind the words:
 *       a message from the console has nobody to give to and is {@code Unknown}. The same for any command that makes
 *       a have-item goal followed by {@code and give it to me}, {@code and give them to me},
 *       {@code and bring it to me} or {@code then give it to me} — {@code craft a quarry and give it to me} is the
 *       sentence the issue names.</li>
 *   <li>{@code get|make|craft|have [<count>] <item> with <enchantment id> [<level>]} — the same with a
 *       {@link ComponentCheck}; the enchantment is one id token (a bare id gets the {@code minecraft:}
 *       namespace) and a missing level is 1; any further word after {@code with} is {@code Unknown}, and so is
 *       an id the context's enchantments do not contain (naming a few that start the same way)</li>
 *   <li>{@code tool <pickaxe|axe|shovel|hoe|sword|shears> <hand|wood|stone|iron|diamond|netherite>}</li>
 *   <li>{@code equip <item> [<item> ...]} — the words are read greedily: at each position the longest run of
 *       words (at most four) that {@link ItemSearch#byId} recognises when joined with underscores is one item
 *       ({@code iron chestplate}, {@code iron horse armor}); a single word nothing recognises goes through the
 *       item resolution below. Each item goes to the slot its registry id implies ({@code *_helmet} head,
 *       {@code *_chestplate} chest, {@code *_leggings} legs, {@code *_boots} feet, {@code shield} offhand,
 *       anything else mainhand)</li>
 *   <li>{@code equip <material> armor} — tried first: that material's helmet, chestplate, leggings and boots,
 *       resolved exactly through {@link ItemSearch#byId}; when any of the four is missing the clause is read as
 *       the item list above instead</li>
 *   <li>{@code stockpile <count> <item> at <x> <y> <z>} — into the context's dimension</li>
 *   <li>{@code use chest at <x> <y> <z>} — {@code UseChest}: the container there, in the context's dimension, is one the
 *       body may take items from (a "supply container", design 3.5); {@code forget chest at <x> <y> <z>} takes it off
 *       that list and {@code chests} asks for the list. Not goals: the body keeps them. The command word is always
 *       {@code chest}; the block at the spot may be any container (a barrel, a furnace, a shulker box, a modded
 *       one), and the runtime, not the grammar, says there is none. Naming a container does not tell the body what is
 *       in it (a closed chest cannot be seen into): the runtime sends the body to look inside, ahead of whatever comes
 *       next in the message, and what it finds counts toward the goals after that</li>
 *   <li>{@code build <blueprint> at <x> <y> <z>} — the blueprint must be in {@link LanguageContext#blueprints()}</li>
 *   <li>{@code goto|go to|come to <x> <y> <z> [within <r>]} — the radius is 0 to 256, default 2</li>
 *   <li>{@code come here|come} — to where the owner stands, within 3</li>
 *   <li>{@code cancel|stop} / {@code cancel all|stop all}; {@code status}; {@code pause}; {@code resume};
 *       {@code help} — the grammar, one line per form</li>
 * </ul>
 *
 * <p>Several commands in one message, separated by {@code ;} or the word {@code then}, give several intents in
 * order. Anything unmatched is an {@link Intent.Unknown} with a pointer to {@code help}; so is a message with
 * nothing in it. No typed word is silently dropped: a word no form accounts for makes the command
 * {@code Unknown}, so the owner never gets a goal they did not ask for.
 *
 * <p><strong>Item resolution rule:</strong> the words are joined with underscores and looked up exactly through
 * {@link ItemSearch#byId} (namespace optional). On a miss, {@link ItemSearch#search} is called and a hit is
 * accepted only when the typed words are <em>an abbreviation of the whole name</em>: the typed words and the
 * words of the hit's name pair up one to one, in order, so there are as many typed words as name words and each
 * typed word is a prefix, ignoring case, of the word in its place. The name is the hit's id path (split on
 * {@code _}) or, failing that, its display name (split on whitespace). So {@code stone pick} is
 * {@code stone_pickaxe}, while {@code stone} is not (a word of the name is left over), {@code armor} is not
 * {@code iron_horse_armor}, {@code stone pickaxe 3} and {@code oak planks chest} are nothing (a typed word has
 * no place), and {@code pickaxe} is not {@code stone_pickaxe}: an item the owner did not name is never guessed,
 * and no typed word is silently dropped. Among the accepted hits, a single one resolves; with several, the best
 * resolves only when its score beats the runner-up by at least 0.15 (scores are 0..1, so 0.15 is a real gap,
 * not a rounding tie), which keeps two equally good abbreviations from being guessed between. Otherwise the
 * command is {@code Unknown}, listing up to three candidates (the accepted hits when there are several, the
 * closest hits when none is accepted) by display name and id so the owner can say one again exactly. This holds
 * wherever an item is resolved, {@code equip} included.
 *
 * <p>An owner online in another dimension has no position the body can walk to (issue #27), so {@code come here},
 * {@code come} and every coordinate that reads {@code ~} are {@code Unknown} saying so, and no goal is set; the
 * deliver forms are too, for the same reason. An owner who is not online is not a barrier: only one who is elsewhere.
 *
 * <p>Counts are 1 to 10000, enchantment levels at least 1, the {@code within} radius 0 to 256, and blueprint
 * names match ignoring case and extra whitespace (the goal carries the registry's spelling). A blueprint name with a
 * colon is one of the server's structure templates by id ({@code build minecraft:igloo/top at ...}), carried as typed
 * and resolved by the loader, which answers "not known" when no template of that id exists. An enchantment id
 * must be one of {@link LanguageContext#enchantments()} when that set is not empty; an empty set means the game's
 * ids are not known yet and the id is taken as typed. Coordinates accept {@code ~}
 * for the owner's own coordinate and {@code ~n} offsets (one that leaves the int range is {@code Unknown}), in
 * every command that takes them (the chest commands included). The {@code at} separator of {@code stockpile} and
 * {@code build} is the token four from the end, so a blueprint or item word of {@code at} before it stays part of the
 * name; {@code use chest at} and {@code forget chest at} are exactly those six words, with nothing before or after.
 *
 * <p>An exception while interpreting is logged at WARNING through {@link System.Logger} with its stack trace
 * and reaches the owner as a plain {@code Unknown}.
 */
public final class FixedGrammar implements LanguageLayer {

    private static final System.Logger LOG = System.getLogger(FixedGrammar.class.getName());

    /** How much better the best item match must be than the runner-up to win without asking (0..1 scores). */
    private static final double MATCH_MARGIN = 0.15;

    /**
     * How many hits to ask the search for: far more than are ever listed, because hits that are not an
     * abbreviation of the typed words are discarded and one that is may rank below a page of looser ones.
     */
    private static final int SEARCH_LIMIT = 50;

    /** How many candidates an {@code Unknown} names. */
    private static final int MAX_CANDIDATES = 3;

    /** Fewest leading characters an unknown enchantment id must share with a known one for it to be suggested. */
    private static final int MIN_SHARED_START = 2;

    /**
     * Inclusive bounds of the radius of {@code within}, in blocks. Public because the model-backed layer holds a
     * model's goals to the same ranges (design 3.3).
     */
    public static final int MIN_RADIUS = 0, MAX_RADIUS = 256;

    /** The longest run of words {@code equip} reads as one item name. */
    private static final int MAX_ITEM_WORDS = 4;

    /** Inclusive bounds of a count the grammar accepts (public: the model-backed layer uses the same ones). */
    public static final int MIN_COUNT = 1, MAX_COUNT = 10000;

    /** The game stores enchantment levels up to 255 (public: the model-backed layer uses the same bound). */
    public static final int MAX_LEVEL = 255;

    /** Separates commands in one message: a semicolon or the standalone word "then". */
    private static final Pattern COMMAND_SPLIT = Pattern.compile("\\s*;\\s*(?:then\\s+)?|\\s+then\\s+");

    private static final Set<String> GATHER_VERBS = Set.of("get", "make", "craft", "have");
    /** The verbs whose object is to be handed over: {@code give me 3 oak planks}, {@code bring me a stone pickaxe}. */
    private static final Set<String> DELIVER_VERBS = Set.of("give", "bring");
    /** The articles the item words of a delivery or a gather may carry without being part of the name. */
    private static final Set<String> ARTICLES = Set.of("a", "an");
    /**
     * The ends that turn a have-item command into a delivery (issue #19): the four wordings the owner has for "and
     * hand it over". Each is five words, at the very end of the clause.
     */
    private static final String[][] DELIVER_TAILS = {
            {"and", "give", "it", "to", "me"},
            {"and", "give", "them", "to", "me"},
            {"and", "bring", "it", "to", "me"},
            {"then", "give", "it", "to", "me"},
    };
    /** The clause the word {@code then} splits off when it is exactly this: it belongs to the clause before it. */
    private static final String THEN_GIVE_TAIL = "give it to me";
    private static final Map<String, ToolClass> TOOL_CLASSES = enumNames(ToolClass.values());
    private static final Map<String, ToolTier> TOOL_TIERS = enumNames(ToolTier.values());

    private static final String[] ARMOR_SUFFIXES = {"helmet", "chestplate", "leggings", "boots"};
    private static final EquipSlot[] ARMOR_SLOTS = {EquipSlot.HEAD, EquipSlot.CHEST, EquipSlot.LEGS, EquipSlot.FEET};

    private static final String HELP =
            "Commands I understand:\n"
            + "get|make|craft|have [count] <item>\n"
            + "get|make|craft|have [count] <item> with <enchantment id> [level]\n"
            + "give me|bring me [count] <item>\n"
            + "get|make|craft|have [count] <item> and give it|them to me\n"
            + "tool <pickaxe|axe|shovel|hoe|sword|shears> <hand|wood|stone|iron|diamond|netherite>\n"
            + "equip <item> [<item> ...]\n"
            + "equip <material> armor\n"
            + "stockpile <count> <item> at <x> <y> <z>\n"
            + "use chest at <x> <y> <z>\n"
            + "forget chest at <x> <y> <z>\n"
            + "chests\n"
            + "build <blueprint> at <x> <y> <z>\n"
            + "goto|go to|come to <x> <y> <z> [within <r>]\n"
            + "come here|come\n"
            + "cancel|stop [all]\n"
            + "status\n"
            + "pause\n"
            + "resume\n"
            + "help\n"
            + "Coordinates accept ~ for my owner's coordinate and ~<n> offsets. Separate commands with ';' or 'then'.";

    @Override
    public CompletableFuture<List<Intent>> interpret(String message, LanguageContext context) {
        try {
            return CompletableFuture.completedFuture(parse(message, context));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "FixedGrammar failed to interpret a message: " + message, e);
            return CompletableFuture.completedFuture(List.of(unknown("Something went wrong while I was reading that")));
        }
    }

    /**
     * The grammar, working on normalized text: lowercased, whitespace runs collapsed to one space, commands
     * split on {@code ;} or {@code then}. A message with nothing in it (blank, or only separators) is one
     * {@link Intent.Unknown}, never an empty list.
     */
    private List<Intent> parse(String message, LanguageContext context) {
        if (message == null || message.strip().isEmpty()) {
            return List.of(unknown("I did not catch anything to do there"));
        }
        if (context == null) {
            return List.of(unknown("I have nothing to look items up with right now"));
        }
        String[] clauses = COMMAND_SPLIT.split(collapse(message.toLowerCase(Locale.ROOT)));
        // "craft a quarry then give it to me": the separator takes "then" for its own, but this clause is the tail of
        // the one before it, so it goes back there before anything is read.
        for (int i = 1; i < clauses.length; i++) {
            if (clauses[i].strip().equals(THEN_GIVE_TAIL) && !clauses[i - 1].isBlank()) {
                clauses[i - 1] = clauses[i - 1] + " then " + THEN_GIVE_TAIL;
                clauses[i] = "";
            }
        }
        List<Intent> intents = new ArrayList<>();
        for (String clause : clauses) {
            if (!clause.isBlank()) intents.add(parseClause(clause.strip(), context));
        }
        if (intents.isEmpty()) return List.of(unknown("I did not catch anything to do there"));
        return intents;
    }

    private Intent parseClause(String clause, LanguageContext context) {
        List<String> tokens = new ArrayList<>(Arrays.asList(clause.split("\\s+")));
        if (!context.ownerInBotDimension() && readsOwnerCoordinate(tokens)) {
            return unknown("My owner is in another dimension, so their ~ is not a place I can name; "
                    + "come here is the same");
        }
        String verb = tokens.get(0);
        if (GATHER_VERBS.contains(verb)) return parseGather(tokens, context);
        if (DELIVER_VERBS.contains(verb)) return parseGive(tokens, context);
        switch (verb) {
            case "tool": return parseTool(tokens);
            case "equip": return parseEquip(tokens, context);
            case "stockpile": return parseStockpile(tokens, context);
            case "use":
            case "forget": return parseChest(tokens, context);
            case "chests": return tokens.size() == 1 ? new Intent.Chests() : unknown("'chests' is on its own");
            case "build": return parseBuild(tokens, context);
            case "goto":
            case "go":
            case "come": return parseMove(tokens, context);
            case "cancel":
            case "stop":
            case "status":
            case "pause":
            case "resume":
            case "help": return parseControl(tokens);
            default: return unknown("'" + clause + "' is not a command I know");
        }
    }

    // ── get|make|craft|have ───────────────────────────────────────────────────────────────────────

    private Intent parseGather(List<String> tokens, LanguageContext context) {
        int tail = deliverTail(tokens);
        if (tail >= 0) {
            // "craft a quarry and give it to me": the same items, handed over (issue #19).
            Intent gather = parseGather(new ArrayList<>(tokens.subList(0, tail)), context);
            if (!(gather instanceof Intent.SetGoal set)) return gather;
            return deliverGoal(set.goal(), context);
        }
        int count = 1;
        int itemStart = 1;
        if (tokens.size() > itemStart && isCountToken(tokens.get(itemStart))) {
            Integer parsed = parseInt(tokens.get(itemStart));
            if (parsed == null || parsed < MIN_COUNT || parsed > MAX_COUNT) {
                return unknown("I can only get between " + MIN_COUNT + " and " + MAX_COUNT + " of something");
            }
            count = parsed;
            itemStart++;
        }
        itemStart = pastArticle(tokens, itemStart);
        // The enchanted form is `<verb> [count] <item> with <enchantment id> [level]`, for all four verbs.
        int with = -1;
        for (int i = itemStart; i < tokens.size(); i++) {
            if (tokens.get(i).equals("with")) {
                with = i;
                break;
            }
        }
        if (with >= 0) {
            // "minecart with furnace" names an item: the whole phrase is tried as one before `with` splits it.
            Resolved whole = resolveItem(joinWords(tokens, itemStart, tokens.size()), context.items());
            if (whole.error() == null) return new Intent.SetGoal(new Goal.HaveItem(whole.key(), count));
        }
        Resolved item = resolveItem(joinWords(tokens, itemStart, with < 0 ? tokens.size() : with), context.items());
        if (item.error() != null) return unknown(item.error());
        if (with < 0) return new Intent.SetGoal(new Goal.HaveItem(item.key(), count));

        // After `with`: exactly one id token and, optionally, a level. Anything else is not guessed at.
        List<String> enchantment = tokens.subList(with + 1, tokens.size());
        String shape = "After 'with' I need one enchantment id and, if you like, a level: 'with efficiency 4'";
        if (enchantment.isEmpty() || enchantment.size() > 2 || isCountToken(enchantment.get(0))) return unknown(shape);
        int level = 1;
        if (enchantment.size() == 2) {
            if (!isCountToken(enchantment.get(1))) return unknown(shape);
            Integer parsed = parseInt(enchantment.get(1));
            if (parsed == null || parsed < 1 || parsed > MAX_LEVEL) {
                return unknown("An enchantment level is a number from 1 to " + MAX_LEVEL);
            }
            level = parsed;
        }
        String enchantmentId = namespaced(enchantment.get(0));
        Set<String> known = context.enchantments();
        if (!known.isEmpty() && !known.contains(enchantmentId)) return unknown(unknownEnchantment(enchantmentId, known));
        ComponentCheck check = ComponentCheck.enchantment(enchantmentId, level);
        return new Intent.SetGoal(new Goal.HaveItem(item.key(), count, check));
    }

    // ── give me / bring me ────────────────────────────────────────────────────────────────────────

    /** {@code give me [<count>] <item>} and {@code bring me [<count>] <item>}: the same items, handed to the speaker. */
    private Intent parseGive(List<String> tokens, LanguageContext context) {
        String verb = tokens.get(0);
        if (tokens.size() < 3 || !tokens.get(1).equals("me")) {
            return unknown("'" + verb + " me' takes a count and an item: 'give me 3 oak planks'");
        }
        int count = 1;
        int itemStart = 2;
        if (isCountToken(tokens.get(itemStart))) {
            Integer parsed = parseInt(tokens.get(itemStart));
            if (parsed == null || parsed < MIN_COUNT || parsed > MAX_COUNT) {
                return unknown("I can only get between " + MIN_COUNT + " and " + MAX_COUNT + " of something");
            }
            count = parsed;
            itemStart++;
        }
        itemStart = pastArticle(tokens, itemStart);
        Resolved item = resolveItem(joinWords(tokens, itemStart, tokens.size()), context.items());
        if (item.error() != null) return unknown(item.error());
        return deliverGoal(new Goal.HaveItem(item.key(), count), context);
    }

    /**
     * The delivery a have-item goal stands for when the words ask for it, or the refusal when they cannot name a
     * player to give to: no player is behind the message (the console), or the owner the speaker is stands in
     * another dimension (issue #27).
     */
    private Intent deliverGoal(Goal goal, LanguageContext context) {
        if (context.speakerId() == null) {
            return unknown("There is nobody behind those words for me to give anything to");
        }
        if (!context.ownerInBotDimension()) {
            return unknown("My owner is in another dimension, so there is nowhere I can bring it to them");
        }
        if (goal instanceof Goal.HaveItem have && have.check() == null) {
            return new Intent.SetGoal(new Goal.Deliver(have.key(), have.count(), context.speakerId()));
        }
        return unknown("I can only hand over plain items, not ones asked for by their enchantments");
    }

    /** Where the words after {@code from} start, past an article that is not part of the item's name ({@code a pickaxe}). */
    private static int pastArticle(List<String> tokens, int from) {
        if (tokens.size() > from + 1 && ARTICLES.contains(tokens.get(from))) return from + 1;
        return from;
    }

    /**
     * Where the delivery tail of a have-item clause starts, or -1 when it has none: the last of the four wordings,
     * whole and at the very end ({@code craft a quarry and give it to me}).
     */
    private static int deliverTail(List<String> tokens) {
        for (int wording = DELIVER_TAILS.length - 1; wording >= 0; wording--) {
            String[] tail = DELIVER_TAILS[wording];
            if (tokens.size() <= tail.length) continue;
            boolean matches = true;
            for (int i = 0; i < tail.length; i++) {
                if (!tokens.get(tokens.size() - tail.length + i).equals(tail[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return tokens.size() - tail.length;
        }
        return -1;
    }

    /** Whether any token reads the owner's own coordinate ({@code ~} or an offset from it), which no dimension of mine can stand in for. */
    private static boolean readsOwnerCoordinate(List<String> tokens) {
        for (String token : tokens) {
            if (token.equals("~") || token.length() > 1 && token.charAt(0) == '~') return true;
        }
        return false;
    }

    // ── tool ──────────────────────────────────────────────────────────────────────────────────────

    private Intent parseTool(List<String> tokens) {
        if (tokens.size() != 3) return unknown("'tool' takes a tool family and a tier, like 'tool pickaxe iron'");
        ToolClass toolClass = TOOL_CLASSES.get(tokens.get(1));
        ToolTier tier = TOOL_TIERS.get(tokens.get(2));
        if (toolClass == null || tier == null) {
            return unknown("'tool' takes a tool family and a tier, like 'tool pickaxe iron'");
        }
        return new Intent.SetGoal(new Goal.HaveToolTier(toolClass, tier));
    }

    // ── equip ─────────────────────────────────────────────────────────────────────────────────────

    private Intent parseEquip(List<String> tokens, LanguageContext context) {
        int size = tokens.size();
        if (size < 2) return unknown("'equip' needs at least one item");
        if (size >= 3 && tokens.get(size - 1).equals("armor")) {
            Map<EquipSlot, ItemKey> armor = armorSet(joinWords(tokens, 1, size - 1).replace(' ', '_'), context.items());
            if (armor != null) return new Intent.SetGoal(new Goal.Equip(armor));
            // Not a full set ("iron horse armor"): read the words as an item list instead.
        }
        Map<EquipSlot, ItemKey> slots = new EnumMap<>(EquipSlot.class);
        int position = 1;
        while (position < size) {
            // `and` only joins the list (`equip helmet and shield`); it is never handed to the item search.
            if (tokens.get(position).equals("and")) {
                position++;
                continue;
            }
            ItemKey key = null;
            int taken = 0;
            for (int length = Math.min(MAX_ITEM_WORDS, size - position); length >= 1 && key == null; length--) {
                key = context.items().byId(String.join("_", tokens.subList(position, position + length))).orElse(null);
                taken = length;
            }
            if (key == null) {
                // No exact id starts here: the longest run of words that abbreviates one item (`iron chest`).
                Resolved item = null;
                for (int length = Math.min(MAX_ITEM_WORDS, size - position); length >= 1; length--) {
                    item = resolveItem(joinWords(tokens, position, position + length), context.items());
                    taken = length;
                    if (item.error() == null) break;
                }
                if (item.error() != null) return unknown(item.error());
                key = item.key();
            }
            position += taken;
            EquipSlot slot = slotFor(key.path());
            if (slots.putIfAbsent(slot, key) != null) {
                return unknown(slots.get(slot) + " and " + key + " both go in the " + slot + " slot");
            }
        }
        if (slots.isEmpty()) return unknown("'equip' needs at least one item");
        return new Intent.SetGoal(new Goal.Equip(slots));
    }

    /** The helmet, chestplate, leggings and boots of a material through {@code byId}, or null if any is missing. */
    private static Map<EquipSlot, ItemKey> armorSet(String material, ItemSearch items) {
        Map<EquipSlot, ItemKey> slots = new EnumMap<>(EquipSlot.class);
        for (int i = 0; i < ARMOR_SUFFIXES.length; i++) {
            Optional<ItemKey> key = items.byId(material + "_" + ARMOR_SUFFIXES[i]);
            if (key.isEmpty()) return null;
            slots.put(ARMOR_SLOTS[i], key.get());
        }
        return slots;
    }

    /**
     * The slot an item's registry id path implies: {@code *_helmet} head, {@code shield} offhand, else mainhand.
     * Public so the model-backed layer files an equip goal's items the way the grammar does.
     */
    public static EquipSlot slotFor(String path) {
        if (path.endsWith("helmet")) return EquipSlot.HEAD;
        if (path.endsWith("chestplate") || path.equals("elytra")) return EquipSlot.CHEST;
        if (path.endsWith("leggings")) return EquipSlot.LEGS;
        if (path.endsWith("boots")) return EquipSlot.FEET;
        if (path.equals("shield")) return EquipSlot.OFFHAND;
        return EquipSlot.MAINHAND;
    }

    // ── stockpile, build ──────────────────────────────────────────────────────────────────────────

    private Intent parseStockpile(List<String> tokens, LanguageContext context) {
        int size = tokens.size();
        // [stockpile, count, item words..., at, x, y, z]
        if (size < 7 || !tokens.get(size - 4).equals("at")) {
            return unknown("'stockpile' takes a count, an item and a spot: 'stockpile 10 cobblestone at 100 64 -200'");
        }
        String countToken = tokens.get(1);
        if (!isCountToken(countToken)) return unknown("'stockpile' starts with how many to store, like 'stockpile 10 ...'");
        Integer count = parseInt(countToken);
        if (count == null || count < MIN_COUNT || count > MAX_COUNT) {
            return unknown("I can only stockpile between " + MIN_COUNT + " and " + MAX_COUNT + " of something");
        }
        Pos container = coords(tokens.subList(size - 3, size), context.ownerPos());
        if (container == null) return unknown("That is not a position: three coordinates, with ~ for mine");
        Resolved item = resolveItem(joinWords(tokens, 2, size - 4), context.items());
        if (item.error() != null) return unknown(item.error());
        return new Intent.SetGoal(new Goal.Stockpile(item.key(), count, context.dimension(), container));
    }

    /** {@code use chest at <x> <y> <z>} and {@code forget chest at <x> <y> <z>}: exactly six words. */
    private Intent parseChest(List<String> tokens, LanguageContext context) {
        String verb = tokens.get(0);
        if (tokens.size() != 6 || !tokens.get(1).equals("chest") || !tokens.get(2).equals("at")) {
            return unknown("'" + verb + "' takes a spot: '" + verb + " chest at 100 64 -200'");
        }
        Pos container = coords(tokens.subList(3, 6), context.ownerPos());
        if (container == null) return unknown("That is not a position: three coordinates, with ~ for mine");
        return verb.equals("use")
                ? new Intent.UseChest(context.dimension(), container)
                : new Intent.ForgetChest(context.dimension(), container);
    }

    private Intent parseBuild(List<String> tokens, LanguageContext context) {
        int size = tokens.size();
        // [build, blueprint words..., at, x, y, z]
        if (size < 6 || !tokens.get(size - 4).equals("at")) {
            return unknown("'build' takes a blueprint and a spot: 'build cottage at 100 64 -200'");
        }
        Pos anchor = coords(tokens.subList(size - 3, size), context.ownerPos());
        if (anchor == null) return unknown("That is not a position: three coordinates, with ~ for mine");
        String blueprint = blueprintNamed(collapse(joinWords(tokens, 1, size - 4)), context);
        if (blueprint == null) return unknown("I don't know a blueprint called '" + joinWords(tokens, 1, size - 4) + "'");
        return new Intent.SetGoal(new Goal.Build(blueprint, context.dimension(), anchor));
    }

    /**
     * The registry spelling of a blueprint named case- and whitespace-insensitively, or null. A name with a colon is a
     * structure template's id ({@code minecraft:igloo/top}), not a file name: it is carried as typed for the loader to
     * resolve, because no list of the server's templates is to hand here, and an id nothing answers to ends "not
     * known" like any other unknown name.
     */
    private static String blueprintNamed(String wanted, LanguageContext context) {
        if (wanted.isEmpty()) return null;
        if (wanted.indexOf(':') >= 0) return wanted;
        for (String blueprint : context.blueprints()) {
            if (collapse(blueprint.toLowerCase(Locale.ROOT)).equals(wanted)) return blueprint;
        }
        return null;
    }

    // ── goto, go to, come ─────────────────────────────────────────────────────────────────────────

    private Intent parseMove(List<String> tokens, LanguageContext context) {
        String verb = tokens.get(0);
        if (verb.equals("goto")) return reach(tokens, 1, context);
        if (verb.equals("go") && tokens.size() >= 2 && tokens.get(1).equals("to")) return reach(tokens, 2, context);
        if (verb.equals("come")) {
            if (tokens.size() == 1 || (tokens.size() == 2 && tokens.get(1).equals("here"))) {
                if (!context.ownerInBotDimension()) {
                    return unknown("My owner is in another dimension, so 'come here' is not a place I can walk to");
                }
                return new Intent.SetGoal(new Goal.Reach(context.dimension(), context.ownerPos(), 3));
            }
            if (tokens.size() >= 2 && tokens.get(1).equals("to")) return reach(tokens, 2, context);
        }
        return unknown("'" + verb + "' is not a way to send me somewhere; 'help' lists them");
    }

    private Intent reach(List<String> tokens, int start, LanguageContext context) {
        Pos pos = tokens.size() >= start + 3 ? coords(tokens.subList(start, start + 3), context.ownerPos()) : null;
        if (pos == null) return unknown("Send me somewhere with three coordinates, with ~ for mine");
        if (tokens.size() == start + 3) return new Intent.SetGoal(new Goal.Reach(context.dimension(), pos, 2));
        if (tokens.size() == start + 5 && tokens.get(start + 3).equals("within")) {
            Integer radius = parseInt(tokens.get(start + 4));
            if (radius != null && radius >= MIN_RADIUS && radius <= MAX_RADIUS) {
                return new Intent.SetGoal(new Goal.Reach(context.dimension(), pos, radius));
            }
            return unknown("'within' takes a radius in blocks, from " + MIN_RADIUS + " to " + MAX_RADIUS);
        }
        return unknown("Send me somewhere with three coordinates, with ~ for mine");
    }

    // ── cancel, status, pause, resume, help ───────────────────────────────────────────────────────

    private Intent parseControl(List<String> tokens) {
        switch (tokens.get(0)) {
            case "cancel":
            case "stop":
                if (tokens.size() == 1) return new Intent.Cancel(false);
                if (tokens.size() == 2 && tokens.get(1).equals("all")) return new Intent.Cancel(true);
                return unknown("'cancel' or 'cancel all'");
            case "status":
                if (tokens.size() == 1) return new Intent.Status();
                return unknown("'status' is on its own");
            case "pause":
                if (tokens.size() == 1) return new Intent.Pause();
                return unknown("'pause' is on its own");
            case "resume":
                if (tokens.size() == 1) return new Intent.Resume();
                return unknown("'resume' is on its own");
            default:
                if (tokens.size() == 1) return new Intent.Say(HELP);
                return unknown("'help' is on its own");
        }
    }

    // ── item resolution ───────────────────────────────────────────────────────────────────────────

    /**
     * One item resolution: {@code key} on a hit, or {@code error} naming the candidates when the owner should
     * say it again. Exactly one of the two is non-null.
     */
    private record Resolved(ItemKey key, String error) {
        static Resolved of(ItemKey key) {
            return new Resolved(key, null);
        }

        static Resolved fail(String error) {
            return new Resolved(null, error);
        }
    }

    /**
     * Words to an item: exact id first (spaces become underscores), then free-text search keeping only the hits
     * the typed words abbreviate (see {@link #abbreviates}), with the rule stated in the class javadoc deciding
     * between a clear best and an ambiguous set.
     */
    private static Resolved resolveItem(String words, ItemSearch items) {
        String text = collapse(words);
        if (text.isEmpty()) return Resolved.fail("I need an item to look for");
        Optional<ItemKey> exact = items.byId(text.replace(' ', '_'));
        if (exact.isPresent()) return Resolved.of(exact.get());
        List<ItemSearch.ItemMatch> matches = items.search(text, SEARCH_LIMIT);
        if (matches.isEmpty()) return Resolved.fail("I don't know any item called '" + text + "'");
        List<ItemSearch.ItemMatch> accepted = new ArrayList<>();
        for (ItemSearch.ItemMatch match : matches) {
            if (abbreviates(text, match)) accepted.add(match);
        }
        if (accepted.isEmpty()) {
            return Resolved.fail("No item is called '" + text + "', or abbreviated that way. " + candidates(matches));
        }
        if (accepted.size() == 1 || accepted.get(0).score() - accepted.get(1).score() >= MATCH_MARGIN) {
            return Resolved.of(accepted.get(0).key());
        }
        return Resolved.fail("Which item? " + candidates(accepted));
    }

    /**
     * Whether the typed words are an abbreviation of the hit's whole name: the same number of words, each typed
     * word a prefix, ignoring case, of the word in its place, in the hit's id path (split on {@code _}) or, failing
     * that, in its display name (split on whitespace). Each spelling is tried as a whole; the two are never mixed
     * word by word.
     */
    private static boolean abbreviates(String text, ItemSearch.ItemMatch match) {
        String[] typed = text.split(" ");
        if (pairsUp(typed, match.key().path().toLowerCase(Locale.ROOT).split("_"))) return true;
        String display = match.displayName();
        return display != null && pairsUp(typed, display.toLowerCase(Locale.ROOT).strip().split("\\s+"));
    }

    private static boolean pairsUp(String[] typed, String[] name) {
        if (typed.length != name.length) return false;
        for (int i = 0; i < typed.length; i++) {
            if (!name[i].startsWith(typed[i])) return false;
        }
        return true;
    }

    /**
     * "I don't know that enchantment", plus up to {@link #MAX_CANDIDATES} known ids whose path starts the way the
     * typed one does: the ones sharing the longest leading run of characters with it, at least
     * {@link #MIN_SHARED_START}, in id order. Nothing is named when no known id starts like it.
     */
    private static String unknownEnchantment(String id, Set<String> known) {
        String path = pathOf(id);
        int best = 0;
        List<String> similar = new ArrayList<>();
        for (String candidate : new TreeSet<>(known)) {
            int shared = sharedStart(path, pathOf(candidate));
            if (shared < MIN_SHARED_START || shared < best) continue;
            if (shared > best) {
                best = shared;
                similar.clear();
            }
            similar.add(candidate);
        }
        String reason = "I don't know an enchantment called '" + id + "'";
        if (similar.isEmpty()) return reason;
        return reason + ". Ones that start like it: "
                + String.join(", ", similar.subList(0, Math.min(similar.size(), MAX_CANDIDATES)));
    }

    private static String pathOf(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static int sharedStart(String a, String b) {
        int length = Math.min(a.length(), b.length());
        int i = 0;
        while (i < length && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** "The closest are Stone Pickaxe (minecraft:stone_pickaxe) ...", at most {@link #MAX_CANDIDATES} of them. */
    private static String candidates(List<ItemSearch.ItemMatch> matches) {
        StringBuilder out = new StringBuilder("The closest are");
        for (ItemSearch.ItemMatch match : matches.subList(0, Math.min(matches.size(), MAX_CANDIDATES))) {
            out.append(' ').append(match.displayName()).append(" (").append(match.key().id()).append(')');
        }
        return out.toString();
    }

    // ── word and number helpers ───────────────────────────────────────────────────────────────────

    /** The words between {@code from} and {@code to} joined with single spaces; an empty range is empty. */
    private static String joinWords(List<String> tokens, int from, int to) {
        return String.join(" ", tokens.subList(Math.max(from, 0), Math.min(to, tokens.size())));
    }

    /** A bare enchantment id gets the {@code minecraft:} namespace, the way {@code ItemKey.of} does for items. */
    private static String namespaced(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /**
     * {@code ~} for the owner's own coordinate and {@code ~n} offsets; null when the token is not a coordinate,
     * or when an offset takes the coordinate out of the int range.
     */
    private static Integer axis(String token, int ownerValue) {
        if (token.equals("~")) return ownerValue;
        if (token.length() > 1 && token.charAt(0) == '~') {
            Integer offset = parseInt(token.substring(1));
            if (offset == null) return null;
            try {
                return Math.addExact(ownerValue, offset);
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        return parseInt(token);
    }

    /** A position from exactly three coordinate tokens, or null. */
    private static Pos coords(List<String> tokens, Pos owner) {
        if (tokens.size() != 3) return null;
        Integer x = axis(tokens.get(0), owner.x());
        Integer y = axis(tokens.get(1), owner.y());
        Integer z = axis(tokens.get(2), owner.z());
        return x == null || y == null || z == null ? null : new Pos(x, y, z);
    }

    /**
     * A token in a count position, signed or not: an attempted count even when out of range or overflowing,
     * so "make -5 stone" is a bad count, not an item called "-5 stone".
     */
    private static boolean isCountToken(String token) {
        return token.matches("-?\\d+");
    }

    private static Integer parseInt(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String collapse(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static Intent unknown(String reason) {
        return new Intent.Unknown(reason + " (say 'help' for the commands I understand)");
    }

    private static <E extends Enum<E>> Map<String, E> enumNames(E[] values) {
        Map<String, E> names = new HashMap<>();
        for (E value : values) names.put(value.name().toLowerCase(Locale.ROOT), value);
        return names;
    }
}
