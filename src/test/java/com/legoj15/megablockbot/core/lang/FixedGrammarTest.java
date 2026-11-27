package com.legoj15.megablockbot.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mapping from an owner's words to the typed goal the executor will run (brief C11). Each grammar form is
 * covered once, plus the ambiguous-item, bad-count, {@code ~}-coordinate and two-command cases. Say, Unknown
 * and help wording is never asserted.
 */
class FixedGrammarTest {

    private static final String DIMENSION = "minecraft:overworld";
    private static final Pos OWNER_POS = new Pos(10, 64, -8);
    private static final Pos BOT_POS = new Pos(11, 64, -8);

    private final FixedGrammar grammar = new FixedGrammar();

    // ── the fake registry ─────────────────────────────────────────────────────────────────────────

    /**
     * A baker's dozen of registry ids with display names. Free-text scoring is the Dice coefficient over underscore-split
     * tokens, counting a token as matched when it equals its query token or starts with it, so "stone pick"
     * reaches "Stone Pickaxe" and "pickaxe" ties between the two pickaxes.
     */
    private static final class FakeItems implements ItemSearch {
        private final Map<String, String> displayNames = new LinkedHashMap<>();

        FakeItems() {
            put("stone_pickaxe", "Stone Pickaxe");
            put("diamond_pickaxe", "Diamond Pickaxe");
            put("diamond_helmet", "Diamond Helmet");
            put("iron_helmet", "Iron Helmet");
            put("iron_chestplate", "Iron Chestplate");
            put("iron_leggings", "Iron Leggings");
            put("iron_boots", "Iron Boots");
            put("iron_horse_armor", "Iron Horse Armor");
            put("andesite", "Andesite");
            put("furnace_minecart", "Minecart with Furnace");
            put("shield", "Shield");
            put("oak_planks", "Oak Planks");
            put("dirt", "Dirt");
            put("enchanted_book", "Enchanted Book");
            put("furnace", "Furnace");
        }

        private void put(String path, String displayName) {
            displayNames.put("minecraft:" + path, displayName);
        }

        @Override
        public Optional<ItemKey> byId(String id) {
            if (id == null) return Optional.empty();
            String normalized = id.toLowerCase(Locale.ROOT).strip();
            if (!normalized.contains(":")) normalized = "minecraft:" + normalized;
            return displayNames.containsKey(normalized) ? Optional.of(new ItemKey(normalized)) : Optional.empty();
        }

        @Override
        public List<ItemMatch> search(String text, int limit) {
            Set<String> query = tokenSet(text);
            List<ItemMatch> matches = new ArrayList<>();
            for (Map.Entry<String, String> entry : displayNames.entrySet()) {
                Set<String> nameTokens = tokenSet(entry.getKey().replace("minecraft:", "") + " " + entry.getValue());
                int matched = 0;
                for (String q : query) {
                    for (String n : nameTokens) {
                        if (n.equals(q) || n.startsWith(q) || q.startsWith(n)) {
                            matched++;
                            break;
                        }
                    }
                }
                double score = 2.0 * matched / (query.size() + nameTokens.size());
                if (score > 0) matches.add(new ItemMatch(new ItemKey(entry.getKey()), entry.getValue(), score));
            }
            matches.sort((a, b) -> a.score() != b.score()
                    ? Double.compare(b.score(), a.score())
                    : a.key().id().compareTo(b.key().id()));
            return matches.size() <= limit ? matches : matches.subList(0, limit);
        }

        private static Set<String> tokenSet(String text) {
            Set<String> tokens = new java.util.LinkedHashSet<>();
            for (String token : text.toLowerCase(Locale.ROOT).strip().split("[\\s_]+")) {
                if (!token.isEmpty()) tokens.add(token);
            }
            return tokens;
        }
    }

    /** The enchantment ids the fake game knows. */
    private static final Set<String> ENCHANTMENTS = Set.of(
            "minecraft:efficiency", "minecraft:mending", "minecraft:sharpness", "minecraft:sweeping_edge");

    /**
     * A search that ignores the text and answers with fixed hits, to put exact scores in front of the margin rule.
     * No exact id is ever known.
     */
    private record ScriptedItems(List<ItemSearch.ItemMatch> hits) implements ItemSearch {
        @Override
        public Optional<ItemKey> byId(String id) {
            return Optional.empty();
        }

        @Override
        public List<ItemMatch> search(String text, int limit) {
            return hits;
        }
    }

    private List<Intent> parse(String message) {
        return grammar.interpret(message, new LanguageContext(new FakeItems(), DIMENSION, OWNER_POS, BOT_POS,
                "Idle. No goal.", List.of("cottage", "watch tower"), ENCHANTMENTS)).join();
    }

    private List<Intent> parse(String message, String... blueprints) {
        return grammar.interpret(message, new LanguageContext(new FakeItems(), DIMENSION, OWNER_POS, BOT_POS,
                "Idle. No goal.", List.of(blueprints), ENCHANTMENTS)).join();
    }

    private List<Intent> parseWithEnchantments(String message, Set<String> enchantments) {
        return grammar.interpret(message, new LanguageContext(new FakeItems(), DIMENSION, OWNER_POS, BOT_POS,
                "Idle. No goal.", List.of(), enchantments)).join();
    }

    private List<Intent> parseWithSearch(String message, ItemSearch items) {
        return grammar.interpret(message, new LanguageContext(items, DIMENSION, OWNER_POS, BOT_POS,
                "Idle. No goal.", List.of(), ENCHANTMENTS)).join();
    }

    private static Intent.SetGoal soleSetGoal(List<Intent> intents) {
        assertEquals(1, intents.size());
        return assertInstanceOf(Intent.SetGoal.class, intents.get(0));
    }

    private static void assertUnknown(List<Intent> intents) {
        assertEquals(1, intents.size());
        assertInstanceOf(Intent.Unknown.class, intents.get(0));
    }

    // ── table rows ────────────────────────────────────────────────────────────────────────────────

    @Test
    void gatherFormBecomesHaveItem() {
        Intent.SetGoal set = soleSetGoal(parse("make 3 stone pickaxe"));
        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 3), set.goal());
    }

    @Test
    void everyGatherVerbMapsTheSameWay() {
        for (String verb : List.of("get", "craft", "have")) {
            assertEquals(new Goal.HaveItem(ItemKey.of("oak_planks"), 2), soleSetGoal(parse(verb + " 2 oak planks")).goal(),
                    verb);
        }
    }

    @Test
    void enchantedGatherFormCarriesTheComponentCheckForEveryGatherVerb() {
        Goal expected = new Goal.HaveItem(ItemKey.of("diamond_pickaxe"), 2,
                ComponentCheck.enchantment("minecraft:efficiency", 4));
        for (String verb : List.of("get", "make", "craft", "have")) {
            assertEquals(expected, soleSetGoal(parse(verb + " 2 diamond pickaxe with efficiency 4")).goal(), verb);
        }
    }

    @Test
    void enchantmentIsOneIdTokenAndAnOptionalLevel() {
        assertUnknown(parse("make diamond pickaxe with efficiency 4 5"));
        assertUnknown(parse("make diamond pickaxe with efficiency one"));
        assertUnknown(parse("make diamond pickaxe with sweeping edge 3"));
        assertUnknown(parse("make diamond pickaxe with"));
        assertUnknown(parse("make diamond pickaxe with efficiency 0"));
    }

    @Test
    void anEnchantmentTheGameDoesNotKnowIsUnknown() {
        // A typo, a namespaced typo and a real name in another namespace: none is in the game's set.
        assertUnknown(parse("make diamond pickaxe with efficiancy 4"));
        assertUnknown(parse("make diamond pickaxe with minecraft:efficiancy 4"));
        assertUnknown(parse("make diamond pickaxe with othermod:efficiency 4"));
        // A known one resolves, bare or with its namespace.
        assertEquals(new Goal.HaveItem(ItemKey.of("diamond_pickaxe"), 1, ComponentCheck.enchantment("minecraft:mending", 1)),
                soleSetGoal(parse("make diamond pickaxe with mending")).goal());
        assertEquals(new Goal.HaveItem(ItemKey.of("diamond_pickaxe"), 1, ComponentCheck.enchantment("minecraft:sharpness", 2)),
                soleSetGoal(parse("make diamond pickaxe with minecraft:sharpness 2")).goal());
    }

    @Test
    void withNoEnchantmentsKnownYetTheIdIsTakenAsTyped() {
        assertEquals(new Goal.HaveItem(ItemKey.of("diamond_pickaxe"), 1, ComponentCheck.enchantment("minecraft:frobnication", 2)),
                soleSetGoal(parseWithEnchantments("make diamond pickaxe with frobnication 2", Set.of())).goal());
    }

    @Test
    void aWordTheGrammarDidNotConsumeIsUnknownNotDropped() {
        // A trailing count would otherwise be eaten by the fuzzy match and the owner get one pickaxe instead of three.
        assertUnknown(parse("make stone pickaxe 3"));
        // An extra word before `at` would otherwise vanish into the oak planks match.
        assertUnknown(parse("stockpile 10 oak planks chest at 1 2 3"));
    }

    @Test
    void enchantedGatherDefaultsToLevelOneAndKeepsNamespaces() {
        Goal expected = new Goal.HaveItem(ItemKey.of("enchanted_book"), 1,
                ComponentCheck.enchantment("minecraft:mending", 1));
        assertEquals(expected, soleSetGoal(parse("get enchanted book with minecraft:mending")).goal());
    }

    @Test
    void toolFormMapsClassAndTier() {
        assertEquals(new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.IRON),
                soleSetGoal(parse("tool pickaxe iron")).goal());
        assertEquals(new Goal.HaveToolTier(ToolClass.SHEARS, ToolTier.HAND),
                soleSetGoal(parse("tool shears hand")).goal());
    }

    @Test
    void equipSendsEachItemToTheSlotItsIdImplies() {
        Goal expected = new Goal.Equip(Map.of(EquipSlot.HEAD, ItemKey.of("iron_helmet"),
                EquipSlot.OFFHAND, ItemKey.of("shield")));
        assertEquals(expected, soleSetGoal(parse("equip iron_helmet shield")).goal());
    }

    @Test
    void equipArmorResolvesAllFourPiecesById() {
        Goal expected = new Goal.Equip(Map.of(
                EquipSlot.HEAD, ItemKey.of("iron_helmet"),
                EquipSlot.CHEST, ItemKey.of("iron_chestplate"),
                EquipSlot.LEGS, ItemKey.of("iron_leggings"),
                EquipSlot.FEET, ItemKey.of("iron_boots")));
        assertEquals(expected, soleSetGoal(parse("equip iron armor")).goal());
    }

    @Test
    void equipOfUnknownArmorMaterialIsUnknown() {
        assertUnknown(parse("equip leather armor"));
    }

    @Test
    void equipTakesMultiWordItemNames() {
        assertEquals(new Goal.Equip(Map.of(EquipSlot.CHEST, ItemKey.of("iron_chestplate"))),
                soleSetGoal(parse("equip iron chestplate")).goal());
        assertEquals(new Goal.Equip(Map.of(EquipSlot.CHEST, ItemKey.of("iron_chestplate"),
                        EquipSlot.OFFHAND, ItemKey.of("shield"))),
                soleSetGoal(parse("equip iron chestplate shield")).goal());
        // "iron horse armor" fails the `<material> armor` form (no iron horse helmet) and is read as one item.
        Goal.Equip horse = assertInstanceOf(Goal.Equip.class, soleSetGoal(parse("equip iron horse armor")).goal());
        assertTrue(horse.slots().containsValue(ItemKey.of("iron_horse_armor")), horse.toString());
        assertEquals(1, horse.slots().size());
    }

    @Test
    void equipReadsAndAsAJoinerAndAbbreviatedNamesAsItems() {
        Goal.Equip joined = (Goal.Equip) soleSetGoal(parse("equip iron_chestplate and shield")).goal();
        assertEquals(Map.of(EquipSlot.CHEST, ItemKey.of("iron_chestplate"), EquipSlot.OFFHAND, ItemKey.of("shield")),
            joined.slots());
        Goal.Equip abbreviated = (Goal.Equip) soleSetGoal(parse("equip iron chest and shield")).goal();
        assertEquals(joined.slots(), abbreviated.slots());
    }

    @Test
    void anItemWhoseNameContainsWithIsNotReadAsAnEnchantedItem() {
        assertEquals(new Goal.HaveItem(ItemKey.of("furnace_minecart"), 1), soleSetGoal(parse("get minecart with furnace")).goal());
    }

    @Test
    void aSemicolonFollowedByThenIsOneSeparator() {
        List<Intent> intents = parse("get dirt ; then get stone pick");
        assertEquals(2, intents.size(), intents.toString());
        assertInstanceOf(Intent.SetGoal.class, intents.get(0));
        assertInstanceOf(Intent.SetGoal.class, intents.get(1));
    }

    @Test
    void twoItemsForOneSlotIsUnknown() {
        assertUnknown(parse("equip iron_helmet diamond_helmet"));
    }

    @Test
    void stockpileFormTargetsTheContextDimension() {
        Goal expected = new Goal.Stockpile(ItemKey.of("oak_planks"), 10, DIMENSION, new Pos(100, 64, -200));
        assertEquals(expected, soleSetGoal(parse("stockpile 10 oak planks at 100 64 -200")).goal());
    }

    @Test
    void buildFormNeedsAKnownBlueprint() {
        assertEquals(new Goal.Build("cottage", DIMENSION, new Pos(0, 64, 0)),
                soleSetGoal(parse("build cottage at 0 64 0", "cottage")).goal());
        assertUnknown(parse("build palace at 0 64 0", "cottage"));
    }

    @Test
    void reachFormTakesTildeCoordinatesAndWithin() {
        assertEquals(new Goal.Reach(DIMENSION, new Pos(100, 64, -200), 5),
                soleSetGoal(parse("goto 100 ~ -200 within 5")).goal());
        assertEquals(new Goal.Reach(DIMENSION, new Pos(12, 64, -11), 2),
                soleSetGoal(parse("go to ~2 ~ ~-3")).goal());
    }

    @Test
    void withinTakesZeroToTwoHundredFiftySix() {
        assertEquals(new Goal.Reach(DIMENSION, new Pos(1, 2, 3), 0), soleSetGoal(parse("goto 1 2 3 within 0")).goal());
        assertEquals(new Goal.Reach(DIMENSION, new Pos(1, 2, 3), 256), soleSetGoal(parse("goto 1 2 3 within 256")).goal());
        assertUnknown(parse("goto 1 2 3 within 257"));
        assertUnknown(parse("goto 1 2 3 within 300"));
        assertUnknown(parse("come to 1 2 3 within 2147483647"));
        assertUnknown(parse("goto 1 2 3 within -1"));
    }

    @Test
    void comeToParsesLikeGoto() {
        assertEquals(soleSetGoal(parse("goto 1 2 3")).goal(), soleSetGoal(parse("come to 1 2 3")).goal());
        assertEquals(soleSetGoal(parse("goto 1 2 3 within 6")).goal(), soleSetGoal(parse("come to 1 2 3 within 6")).goal());
    }

    @Test
    void aTildeOffsetThatOverflowsIsUnknownNotWrapped() {
        // The owner stands at x=10, z=-8: both of these leave the int range.
        assertUnknown(parse("goto ~2147483647 64 0"));
        assertUnknown(parse("goto 0 64 ~-2147483647"));
        assertUnknown(parse("stockpile 5 dirt at ~2147483647 64 0"));
    }

    @Test
    void comeHereTargetsTheOwnerWithinThree() {
        Goal expected = new Goal.Reach(DIMENSION, OWNER_POS, 3);
        assertEquals(expected, soleSetGoal(parse("come here")).goal());
        assertEquals(expected, soleSetGoal(parse("come")).goal());
    }

    @Test
    void useChestNamesAContainerInTheContextDimension() {
        assertEquals(new Intent.UseChest(DIMENSION, new Pos(100, 64, -200)), parse("use chest at 100 64 -200").get(0));
        assertEquals(1, parse("use chest at 100 64 -200").size());
        // The owner's own coordinates stand for themselves, and case does not matter.
        assertEquals(new Intent.UseChest(DIMENSION, new Pos(10, 63, -8)), parse("Use Chest At ~ ~-1 ~").get(0));
    }

    @Test
    void forgetChestTakesItBack() {
        assertEquals(new Intent.ForgetChest(DIMENSION, new Pos(1, 2, 3)), parse("forget chest at 1 2 3").get(0));
        assertEquals(new Intent.ForgetChest(DIMENSION, new Pos(12, 64, -8)), parse("forget chest at ~2 ~ ~").get(0));
    }

    @Test
    void chestsAsksForTheList() {
        assertEquals(new Intent.Chests(), parse("chests").get(0));
        assertEquals(1, parse("chests").size());
    }

    @Test
    void aChestCommandThatIsNotWholeIsUnknownNotGuessed() {
        assertUnknown(parse("use chest"));
        assertUnknown(parse("use chest at 1 2"));
        assertUnknown(parse("use chest 1 2 3"));
        assertUnknown(parse("use chest at 1 2 three"));
        assertUnknown(parse("use chest at 1 2 3 4"));
        assertUnknown(parse("use barrel at 1 2 3"));
        assertUnknown(parse("forget chest at 1 2"));
        assertUnknown(parse("forget everything"));
        assertUnknown(parse("chests please"));
        assertUnknown(parse("use chest at ~2147483647 2 3"));
    }

    @Test
    void chestCommandsFollowOthersInOneMessage() {
        List<Intent> intents = parse("use chest at 1 2 3 then make 4 oak planks");
        assertEquals(2, intents.size());
        assertEquals(new Intent.UseChest(DIMENSION, new Pos(1, 2, 3)), intents.get(0));
        assertEquals(new Goal.HaveItem(ItemKey.of("oak_planks"), 4), assertInstanceOf(Intent.SetGoal.class, intents.get(1)).goal());
    }

    @Test
    void cancelForms() {
        assertEquals(new Intent.Cancel(false), parse("cancel").get(0));
        assertEquals(new Intent.Cancel(true), parse("stop all").get(0));
    }

    @Test
    void statusPauseResumeForms() {
        assertEquals(new Intent.Status(), parse("status").get(0));
        assertEquals(new Intent.Pause(), parse("pause").get(0));
        assertEquals(new Intent.Resume(), parse("resume").get(0));
    }

    @Test
    void helpIsSomethingToSay() {
        assertEquals(1, parse("help").size());
        assertInstanceOf(Intent.Say.class, parse("help").get(0));
    }

    // ── item resolution ───────────────────────────────────────────────────────────────────────────

    @Test
    void aFuzzyMatchIsAnAbbreviationOfTheWholeName() {
        // Each typed word is the start of the word in the same place of the name, and no word is left over.
        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1), soleSetGoal(parse("make stone pick")).goal());
        assertEquals(new Goal.HaveItem(ItemKey.of("oak_planks"), 1), soleSetGoal(parse("make oak plank")).goal());
        // One word of a longer name is not an abbreviation of it, in any command that resolves an item.
        assertUnknown(parse("get stone"));
        assertUnknown(parse("make pickaxe"));
        assertUnknown(parse("get iron armor"));
        assertUnknown(parse("stockpile 5 stone at 1 2 3"));
    }

    @Test
    void equipDoesNotInventAnItemTheOwnerDidNotName() {
        // "armor" alone is not the iron horse armor; "stone" alone is not the stone pickaxe, so the shield after it
        // must not be paired with an item the owner never named.
        assertUnknown(parse("equip armor"));
        assertUnknown(parse("equip stone shield"));
    }

    @Test
    void anAbbreviationMatchesTheDisplayNameWhenTheIdDoesNotFit() {
        ItemSearch steak = new ScriptedItems(List.of(
                new ItemSearch.ItemMatch(ItemKey.of("cooked_beef"), "Steak", 0.8)));
        assertEquals(new Goal.HaveItem(ItemKey.of("cooked_beef"), 1), soleSetGoal(parseWithSearch("get ste", steak)).goal());
        assertEquals(new Goal.HaveItem(ItemKey.of("cooked_beef"), 1), soleSetGoal(parseWithSearch("get coo be", steak)).goal());
        assertUnknown(parseWithSearch("get steak cooked", steak));
    }

    @Test
    void amongAbbreviationsTheBestNeedsAClearMargin() {
        ItemSearch.ItemMatch log = new ItemSearch.ItemMatch(ItemKey.of("oak_log"), "Oak Log", 0.9);
        ItemSearch.ItemMatch leaves = new ItemSearch.ItemMatch(ItemKey.of("oak_leaves"), "Oak Leaves", 0.9);
        ItemSearch.ItemMatch farther = new ItemSearch.ItemMatch(ItemKey.of("oak_leaves"), "Oak Leaves", 0.6);
        // Equal scores: asking is better than guessing between the two.
        assertUnknown(parseWithSearch("get oak l", new ScriptedItems(List.of(log, leaves))));
        // A gap of 0.3 is a clear best.
        assertEquals(new Goal.HaveItem(ItemKey.of("oak_log"), 1),
                soleSetGoal(parseWithSearch("get oak l", new ScriptedItems(List.of(log, farther)))).goal());
    }

    @Test
    void aSingleAbbreviationResolves() {
        assertEquals(new Goal.HaveItem(ItemKey.of("enchanted_book"), 1),
                soleSetGoal(parse("get ench book")).goal());
    }

    // ── counts, unmatched input ───────────────────────────────────────────────────────────────────

    @Test
    void countsOutsideOneToTenThousandAreUnknown() {
        assertUnknown(parse("make 0 stone pickaxe"));
        assertUnknown(parse("make 10001 stone pickaxe"));
        assertUnknown(parse("make -5 stone pickaxe"));
    }

    @Test
    void unmatchedInputIsUnknown() {
        assertUnknown(parse("frobnicate the widgets"));
        assertUnknown(parse("   "));
    }

    @Test
    void aMessageWithOnlySeparatorsIsOneUnknown() {
        assertUnknown(parse(";"));
        assertUnknown(parse(" ; ;  "));
    }

    // ── several commands in one message ───────────────────────────────────────────────────────────

    @Test
    void twoCommandsGiveTwoIntentsInOrder() {
        List<Intent> intents = parse("get 5 dirt; come here");
        assertEquals(2, intents.size());
        assertEquals(new Goal.HaveItem(ItemKey.of("dirt"), 5),
                assertInstanceOf(Intent.SetGoal.class, intents.get(0)).goal());
        assertEquals(new Goal.Reach(DIMENSION, OWNER_POS, 3),
                assertInstanceOf(Intent.SetGoal.class, intents.get(1)).goal());
    }

    @Test
    void theWordThenSeparatesCommandsToo() {
        List<Intent> intents = parse("status then come here");
        assertEquals(2, intents.size());
        assertEquals(new Intent.Status(), intents.get(0));
        assertEquals(new Goal.Reach(DIMENSION, OWNER_POS, 3),
                assertInstanceOf(Intent.SetGoal.class, intents.get(1)).goal());
    }

    // ── the layer contract ────────────────────────────────────────────────────────────────────────

    @Test
    void aFailingItemLookupIsAnUnknownIntentNotAnException() {
        ItemSearch broken = new ItemSearch() {
            @Override
            public Optional<ItemKey> byId(String id) {
                throw new IllegalStateException("registry not ready");
            }

            @Override
            public List<ItemMatch> search(String text, int limit) {
                throw new IllegalStateException("registry not ready");
            }
        };
        List<Intent> intents = grammar.interpret("get 5 dirt",
                new LanguageContext(broken, DIMENSION, OWNER_POS, BOT_POS, "Idle. No goal.", List.of(), Set.of())).join();
        assertUnknown(intents);
    }
}
