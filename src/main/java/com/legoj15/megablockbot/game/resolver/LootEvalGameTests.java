package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.legoj15.megablockbot.MegaBlockBot;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The evaluator on tables written for the purpose, for what no vanilla table exercises: a pick chance beside an
 * uncertain entry, reference chains and webs far deeper than any pack writes, a count provider too big to enumerate, a
 * table that names more block states than are tried, the budget of one read, the state a yield is read in for a mined
 * block and for a harvested one, a registry that throws. They sit in this package because
 * {@link LootEval} is package-private, and run as GameTests because the unit-test source set is shared by nodes that
 * do not compile {@code game}. Each builds its own notes, so a test sees only its own.
 */
public final class LootEvalGameTests {
    private LootEvalGameTests() {}

    private static final double TOLERANCE = 1e-9;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static LootEval.Environment tables(Map<String, JsonObject> byId) {
        return new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                return byId.get(id);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }
        };
    }

    private static double expected(GameTestHelper helper, List<LootEval.Route> routes, String item) {
        for (LootEval.Route route : routes) if (route.item().equals(item)) return route.expected();
        helper.fail(item + " is not among the routes " + routes);
        return 0;
    }

    private static String chance(String item, int weight, double chance) {
        return "{\"type\":\"minecraft:item\",\"name\":\"minecraft:" + item + "\",\"weight\":" + weight
            + ",\"conditions\":[{\"condition\":\"minecraft:random_chance\",\"chance\":" + chance + "}]}";
    }

    private static String certain(String item, int weight) {
        return "{\"type\":\"minecraft:item\",\"name\":\"minecraft:" + item + "\",\"weight\":" + weight + "}";
    }

    private static String pool(String... entries) {
        return "{\"rolls\":1,\"entries\":[" + String.join(",", entries) + "]}";
    }

    private static String table(String... pools) {
        return "{\"type\":\"minecraft:chest\",\"pools\":[" + String.join(",", pools) + "]}";
    }

    // A pick takes one entry among those present, by weight, so what a certain entry gets depends on which of its
    // uncertain siblings are there, and the figure is the average of its share over those cases, not its share of the
    // average weight.
    // Pool 1: stick (weight 3, always) beside coal (weight 1, half the time). Coal absent (1/2): stick is 3/3 = 1. Coal
    // present (1/2): 3/4. So 1/2 * 1 + 1/2 * 3/4 = 0.875 (the share of the average weight, 3 / (3 + 0.5), is 0.8571).
    // Coal itself is there half the time and then takes 1/4: 0.125.
    // Pool 2: apple (weight 2, always) beside bone and flint (weight 1 each, half the time each, independently). Neither
    // (1/4): 2/2; one (1/2): 2/3; both (1/4): 2/4. So 1/4 + 1/3 + 1/8 = 17/24 = 0.70833 (the share of the average weight,
    // 2 / (2 + 1), would say 2/3). Bone is there half the time; then apple takes 2 and flint is there half the time: 1/2 * (1/2 * 1/3
    // + 1/2 * 1/4) = 7/48 = 0.14583.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aCertainEntryIsPickedByTheAverageOfItsShareOverTheSiblingsThatAreThere(GameTestHelper helper) {
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(Map.of("test:pick", json(table(
            pool(certain("stick", 3), chance("coal", 1, 0.5)),
            pool(certain("apple", 2), chance("bone", 1, 0.5), chance("flint", 1, 0.5)))))), notes);
        List<LootEval.Route> routes = eval.routes("test:pick", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double stick = expected(helper, routes, "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 0.875) < TOLERANCE, "stick is " + stick + ", expected 0.875");
        double coal = expected(helper, routes, "minecraft:coal");
        helper.assertTrue(Math.abs(coal - 0.125) < TOLERANCE, "coal is " + coal + ", expected 0.125");
        double apple = expected(helper, routes, "minecraft:apple");
        helper.assertTrue(Math.abs(apple - 17.0 / 24) < TOLERANCE, "apple is " + apple + ", expected " + 17.0 / 24);
        double bone = expected(helper, routes, "minecraft:bone");
        helper.assertTrue(Math.abs(bone - 7.0 / 48) < TOLERANCE, "bone is " + bone + ", expected " + 7.0 / 48);
        helper.assertTrue(notes.lines().isEmpty(), "an exact figure is noted as an approximation: " + notes.lines());
        helper.succeed();
    }

    // Past the bound on how many different weights the uncertain siblings can add up to, the share of the average weight
    // stands in and the table is noted. Thirteen siblings of weight 1, 2, 4 ... 4096, each half the time, can total any
    // whole number up to 8191, more than the bound allows; the certain stick (weight 1) beside them is then
    // 1 / (1 + 8191 / 2) = 1 / 4096.5.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aPoolWithTooManyUncertainEntriesFallsBackToTheAverageWeightAndSaysSo(GameTestHelper helper) {
        String[] entries = new String[14];
        entries[0] = certain("stick", 1);
        for (int k = 0; k < 13; k++) entries[k + 1] = chance("item_" + k, 1 << k, 0.5);
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(Map.of("test:many", json(table(pool(entries))))), notes);
        double stick = expected(helper, eval.routes("test:many", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 1 / 4096.5) < TOLERANCE, "stick is " + stick + ", expected " + 1 / 4096.5);
        helper.assertTrue(!notes.lines().isEmpty(), "the approximation is not noted");
        helper.succeed();
    }

    private static String stateEntry(String item, String block, String property, String value) {
        return "{\"type\":\"minecraft:item\",\"name\":\"minecraft:" + item + "\",\"conditions\":[{\"condition\":\"minecraft:block_state_property\","
            + "\"block\":\"" + block + "\",\"properties\":{\"" + property + "\":\"" + value + "\"}}]}";
    }

    // Two pools that test one property of one block for different values are alternatives, as in a sweet berry bush;
    // here without the registry to tell which values the property can have, so the states tried are the two the table
    // names and one for the rest. Apple: 1 in the pool for age 3, 1 in the pool for age 2, and a block is in one of
    // them, so 1, not the 2 of both.
    // A table that names more states than are enumerated (nine properties of two values each, 2^9 = 512, over the
    // bound of 256) has its state conditions tried both ways, as every other condition this class cannot judge: all
    // nine entries are there in the reading where they hold, 1/9 of the picks each, and the table is noted.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aBlockIsInOneStateAndATableNamingTooManyIsNoted(GameTestHelper helper) {
        BuildNotes notes = new BuildNotes();
        String[] faces = new String[9];
        for (int k = 0; k < 9; k++) faces[k] = stateEntry("face_" + k, "test:many_faces", "p" + k, "true");
        LootEval eval = new LootEval(tables(Map.of(
            "test:ages", json(table(pool(stateEntry("apple", "test:bush", "age", "3")), pool(stateEntry("apple", "test:bush", "age", "2")))),
            "test:many_faces", json(table(pool(faces))))), notes);
        double apples = expected(helper, eval.routes("test:ages", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:apple");
        helper.assertTrue(Math.abs(apples - 1.0) < TOLERANCE, "apples are " + apples + ", expected 1.0");
        helper.assertTrue(notes.lines().isEmpty(), "a table with two states is noted: " + notes.lines());
        double face = expected(helper, eval.routes("test:many_faces", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:face_0");
        helper.assertTrue(Math.abs(face - 1.0 / 9) < TOLERANCE, "a face is " + face + ", expected " + 1.0 / 9);
        helper.assertTrue(!notes.lines().isEmpty(), "a table with 512 states is not noted");
        helper.succeed();
    }

    private static JsonObject chainLink(int index, int length, String prefix) {
        if (index >= length) return json(table(pool(certain("diamond", 1))));
        return json(table(pool("{\"type\":\"minecraft:loot_table\",\"value\":\"" + prefix + (index + 1) + "\"}")));
    }

    private static LootEval.Environment chain(int length, String prefix) {
        return new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                if (!id.startsWith(prefix)) return null;
                return chainLink(Integer.parseInt(id.substring(prefix.length())), length, prefix);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }
        };
    }

    // Each reference is a nested evaluation, so a chain long enough ends the thread with a stack overflow, which is no
    // RuntimeException and passes every guard of the build. A chain a pack could write (ten tables) still resolves
    // whole, and says nothing.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aVeryDeepChainOfTableReferencesIsCutAndNoted(GameTestHelper helper) {
        BuildNotes shallowNotes = new BuildNotes();
        List<LootEval.Route> shallow = new LootEval(chain(10, "test:shallow/"), shallowNotes)
            .routes("test:shallow/0", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double diamond = expected(helper, shallow, "minecraft:diamond");
        helper.assertTrue(Math.abs(diamond - 1.0) < TOLERANCE, "a chain of ten tables yields " + diamond + " diamonds, expected 1.0");
        helper.assertTrue(shallowNotes.lines().isEmpty(), "a chain of ten tables is noted: " + shallowNotes.lines());

        BuildNotes deepNotes = new BuildNotes();
        try {
            new LootEval(chain(100_000, "test:deep/"), deepNotes).routes("test:deep/0", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        } catch (StackOverflowError e) {
            helper.fail("a chain of 100000 table references overflowed the stack");
        }
        helper.assertTrue(!deepNotes.lines().isEmpty(), "a chain of 100000 table references is cut without a note");
        helper.succeed();
    }

    // set_count binomial n=2000, p=0.0001 is beyond what is enumerated (n above 1000). Its mean, n * p = 0.2, needs no
    // enumeration; it used to be rounded to the whole number 0, which dropped the item silently.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aBinomialCountBeyondTheSizeGuardKeepsItsMeanAndSaysSo(GameTestHelper helper) {
        String binomial = "{\"type\":\"minecraft:item\",\"name\":\"minecraft:diamond\",\"functions\":[{\"function\":\"minecraft:set_count\","
            + "\"count\":{\"type\":\"minecraft:binomial\",\"n\":2000,\"p\":0.0001}}]}";
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(Map.of("test:binomial", json(table(pool(binomial))))), notes);
        List<LootEval.Route> routes = eval.routes("test:binomial", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double diamonds = expected(helper, routes, "minecraft:diamond");
        helper.assertTrue(Math.abs(diamonds - 0.2) < TOLERANCE, "diamonds are " + diamonds + ", expected 0.2");
        helper.assertTrue(notedFor(notes, "test:binomial"), "a binomial beyond the size guard is not noted under its table: " + notes.lines());
        helper.succeed();
    }

    private static boolean notedFor(BuildNotes notes, String table) {
        return notes.lines().stream().anyMatch(line -> line.contains(table));
    }

    private static String setCount(String count, boolean add) {
        return "{\"function\":\"minecraft:set_count\",\"add\":" + add + ",\"count\":" + count + "}";
    }

    private static String stick(String... functions) {
        return "{\"type\":\"minecraft:item\",\"name\":\"minecraft:stick\",\"functions\":[" + String.join(",", functions) + "]}";
    }

    private static final String UNIFORM_0_TO_3000 = "{\"type\":\"minecraft:uniform\",\"min\":0,\"max\":3000}";

    // The other two silent replacements in a number provider, and the note each now gets, which names the table it is
    // in. A uniform over 0..5000 (5001 values, over the 4096 enumerated) is replaced by its midpoint, 2500. Two uniforms
    // over 0..3000 added by set_count (each 3001 values, together 6001) are replaced by the sum of their means,
    // 1500 + 1500 = 3000.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void everyReplacementOfACountProviderNamesItsTable(GameTestHelper helper) {
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(Map.of(
            "test:wide_uniform", json(table(pool(stick(setCount("{\"type\":\"minecraft:uniform\",\"min\":0,\"max\":5000}", false))))),
            "test:wide_sum", json(table(pool(stick(setCount(UNIFORM_0_TO_3000, false), setCount(UNIFORM_0_TO_3000, true))))))), notes);
        double midpoint = expected(helper, eval.routes("test:wide_uniform", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        helper.assertTrue(Math.abs(midpoint - 2500) < TOLERANCE, "a uniform over 0..5000 is " + midpoint + ", expected 2500");
        helper.assertTrue(notedFor(notes, "test:wide_uniform"), "the replaced uniform is not noted under its table: " + notes.lines());
        double sum = expected(helper, eval.routes("test:wide_sum", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        helper.assertTrue(Math.abs(sum - 3000) < TOLERANCE, "the sum of two uniforms over 0..3000 is " + sum + ", expected 3000");
        helper.assertTrue(notedFor(notes, "test:wide_sum"), "the replaced sum is not noted under its table: " + notes.lines());
        helper.succeed();
    }

    // A table that references itself has the inner reference ignored. It is evaluated once per state, per reading of the
    // conditions that cannot be judged and per tool, and every one of those meets the loop; the note names the table
    // once and does not grow with the scenarios (here three values named for one property and one for the rest, so
    // four states, and then two more tools).
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aLoopIsNotedOncePerTableWhateverTheScenarios(GameTestHelper helper) {
        BuildNotes notes = new BuildNotes();
        String self = "{\"type\":\"minecraft:loot_table\",\"value\":\"test:loop\"}";
        LootEval eval = new LootEval(tables(Map.of("test:loop", json(table(
            pool(stateEntry("a", "test:bush", "age", "1")), pool(stateEntry("b", "test:bush", "age", "2")),
            pool(stateEntry("c", "test:bush", "age", "3")), pool(self))))), notes);
        eval.routes("test:loop", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        List<String> afterOneTool = notes.lines();
        helper.assertTrue(notedFor(notes, "test:loop"), "the loop is not noted under its table: " + afterOneTool);
        eval.routes("test:loop", EnumSet.of(LootEval.Tool.SILK, LootEval.Tool.SHEARS), false, LootEval.StateRule.MINED);
        helper.assertTrue(afterOneTool.equals(notes.lines()), "the loop is counted again for each scenario: " + afterOneTool + " then " + notes.lines());
        helper.succeed();
    }

    // Two pools of every table reference the next, so the top of a chain of n tables reaches the bottom by 2^(n-1) paths.
    // Walked path by path that never ends; evaluated once per table (and depth left) it is a few hundred steps. The
    // diamond at the bottom of each table is counted once per path: 2^(n-1) of them. A chain of 64 and one of 65 tables
    // (a diamond at the bottom of 64 references) are within the depth bound and give their diamonds; one of 200 is
    // past it, is cut and noted, and must still end at once. The work runs on a thread of its own so that a build
    // that never ends fails the test instead of stopping the server.
    private static Map<String, JsonObject> web(int tables, String prefix) {
        Map<String, JsonObject> byId = new HashMap<>();
        for (int i = 0; i < tables; i++) {
            String next = "{\"type\":\"minecraft:loot_table\",\"value\":\"" + prefix + (i + 1) + "\"}";
            byId.put(prefix + i, json(i == tables - 1 ? table(pool(certain("diamond", 1))) : table(pool(next), pool(next))));
        }
        return byId;
    }

    private record Timed(List<LootEval.Route> routes, BuildNotes notes, long millis) {}

    private static Timed timed(GameTestHelper helper, Map<String, JsonObject> byId, String root) {
        BuildNotes notes = new BuildNotes();
        FutureTask<Timed> task = new FutureTask<>(() -> {
            long start = System.nanoTime();
            List<LootEval.Route> routes = new LootEval(tables(byId), notes).routes(root, EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
            return new Timed(routes, notes, (System.nanoTime() - start) / 1_000_000);
        });
        Thread worker = new Thread(task, "loot-eval-web");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            helper.fail("a web of " + byId.size() + " tables did not finish in 20 seconds");
        } catch (InterruptedException | ExecutionException e) {
            helper.fail("a web of " + byId.size() + " tables failed: " + e);
        }
        return null;
    }

    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aWebOfReferencesIsEvaluatedInTimeProportionalToItsSize(GameTestHelper helper) {
        for (int tables : new int[] {64, 65}) {
            Timed run = timed(helper, web(tables, "test:web/"), "test:web/0");
            double diamonds = expected(helper, run.routes(), "minecraft:diamond");
            double paths = Math.pow(2, tables - 1);
            helper.assertTrue(diamonds == paths, "a web of " + tables + " tables yields " + diamonds + " diamonds, expected " + paths);
            helper.assertTrue(run.millis() < 1000, "a web of " + tables + " tables took " + run.millis() + " ms");
            helper.assertTrue(run.notes().lines().isEmpty(), "a web of " + tables + " tables is noted: " + run.notes().lines());
            MegaBlockBot.LOGGER.info("resolver web of {} tables evaluated in {} ms", tables, run.millis());
        }
        Timed deep = timed(helper, web(200, "test:web200/"), "test:web200/0");
        helper.assertTrue(deep.millis() < 1000, "a web of 200 tables took " + deep.millis() + " ms");
        helper.assertTrue(!deep.notes().lines().isEmpty(), "a web of 200 tables is cut without a note");
        MegaBlockBot.LOGGER.info("resolver web of 200 tables evaluated in {} ms", deep.millis());
        helper.succeed();
    }

    // The work of one routes() call is bounded by a budget of table evaluations (LootEval.MAX_TABLE_EVALUATIONS),
    // spent where the memo misses. A ladder of 200 tables (table i references i+1 and i+2) whose root names eight
    // boolean block-state properties, 2^8 = 256 states, walks every state down the whole ladder, over two hundred
    // thousand evaluations; unbounded it exhausts the heap before it ends. With the budget the read ends at once,
    // what was computed stands, and the table asked for is named as cut for size.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aTablePastTheEvaluationBudgetIsCutInTimeAndNoted(GameTestHelper helper) {
        Map<String, JsonObject> byId = new HashMap<>();
        String[] gate = new String[8];
        for (int k = 0; k < 8; k++) gate[k] = stateEntry("apple", "test:gated", "p" + k, "true");
        byId.put("test:ladder/0", json(table(pool(gate), pool("{\"type\":\"minecraft:loot_table\",\"value\":\"test:ladder/1\"}"))));
        for (int i = 1; i < 200; i++) {
            String next = "{\"type\":\"minecraft:loot_table\",\"value\":\"test:ladder/" + (i + 1) + "\"}";
            String after = "{\"type\":\"minecraft:loot_table\",\"value\":\"test:ladder/" + Math.min(i + 2, 199) + "\"}";
            byId.put("test:ladder/" + i, json(i == 199 ? table(pool(certain("diamond", 1))) : table(pool(next), pool(after))));
        }
        Timed run = timed(helper, byId, "test:ladder/0");
        helper.assertTrue(run.millis() < 1000, "a ladder of 200 tables over 256 states took " + run.millis() + " ms");
        helper.assertTrue(notedFor(run.notes(), "test:ladder/0"), "the ladder cut for size is not named: " + run.notes().lines());
        MegaBlockBot.LOGGER.info("resolver ladder of 200 tables over 256 states evaluated in {} ms", run.millis());
        helper.succeed();
    }

    // A read the budget cut is not kept as if it were whole. The root names as many tables as a read can pay for, one
    // stick each, so with the root itself the first read is cut one child short. The children it did read are kept, so
    // a later read of the same table gets through and must then give every stick; a cut result served again from the
    // memo would stay a stick short for good.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void aReadCutForSizeIsFinishedByALaterRead(GameTestHelper helper) {
        int children = LootEval.MAX_TABLE_EVALUATIONS;
        Map<String, JsonObject> byId = new HashMap<>();
        String[] pools = new String[children];
        for (int i = 0; i < children; i++) {
            pools[i] = pool("{\"type\":\"minecraft:loot_table\",\"value\":\"test:wide/" + i + "\"}");
            byId.put("test:wide/" + i, json(table(pool(certain("stick", 1)))));
        }
        byId.put("test:wide", json(table(pools)));
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(byId), notes);
        double first = expected(helper, eval.routes("test:wide", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        helper.assertTrue(first < children, "the first read was not cut: " + first + " sticks of " + children);
        helper.assertTrue(notedFor(notes, "test:wide"), "the cut read is not named: " + notes.lines());
        double last = first;
        for (int read = 0; read < 3; read++) {
            last = expected(helper, eval.routes("test:wide", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        }
        helper.assertTrue(Math.abs(last - children) < TOLERANCE, "a later read gives " + last + " sticks, expected " + children);
        helper.succeed();
    }

    // A table that nests another is rolled for the same block, so a state the nested one tests is one the block can be
    // in, and a pool of the nested table keyed on it is not dropped. The outer table tests the age for 3 (a stick); the
    // table it references tests the same property for 2 (an apple), which is a value only the nested table names.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aStateTestedInsideAReferencedTableIsOneOfTheBlocksStates(GameTestHelper helper) {
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(tables(Map.of(
            "test:outer", json(table(pool(stateEntry("stick", "test:bush", "age", "3")),
                pool("{\"type\":\"minecraft:loot_table\",\"value\":\"test:inner\"}"))),
            "test:inner", json(table(pool(stateEntry("apple", "test:bush", "age", "2")))))), notes);
        List<LootEval.Route> routes = eval.routes("test:outer", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double apple = expected(helper, routes, "minecraft:apple");
        helper.assertTrue(Math.abs(apple - 1.0) < TOLERANCE, "apples are " + apple + ", expected 1.0 (the age-2 state)");
        double stick = expected(helper, routes, "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 1.0) < TOLERANCE, "sticks are " + stick + ", expected 1.0 (the age-3 state, not both)");
        helper.succeed();
    }

    // The registry lookups behind a table's block states run before the table is evaluated. One that throws costs that
    // table, named in the notes, and no other: a throw out of routes() ends the whole section of the index it runs in.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aTableWhoseStatesCannotBeLookedUpCostsOnlyItself(GameTestHelper helper) {
        Map<String, JsonObject> byId = Map.of(
            "test:cursed", json(table(pool(stateEntry("apple", "test:cursed_block", "age", "3")))),
            "test:fine", json(table(pool(certain("stick", 1)))));
        LootEval.Environment env = new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                return byId.get(id);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }

            @Override
            public List<String> stateValues(String block, String property) {
                if (block.equals("test:cursed_block")) throw new IllegalStateException("the registry has no such block");
                return List.of();
            }
        };
        BuildNotes notes = new BuildNotes();
        LootEval eval = new LootEval(env, notes);
        List<LootEval.Route> cursed = List.of();
        try {
            cursed = eval.routes("test:cursed", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        } catch (RuntimeException e) {
            helper.fail("the state lookup of one table threw out of routes(): " + e);
        }
        helper.assertTrue(cursed.isEmpty(), "the table whose states cannot be read has routes: " + cursed);
        helper.assertTrue(notedFor(notes, "test:cursed"), "the table whose states cannot be read is not named: " + notes.lines());
        double stick = expected(helper, eval.routes("test:fine", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 1.0) < TOLERANCE, "sticks are " + stick + ", expected 1.0");
        helper.succeed();
    }

    // A throw while a referenced table's state values are collected costs the referenced table, named by itself, and
    // not the table that references it: the referencer's own pool is still read. Here the referencer gives a stick of
    // its own beside the reference to a table whose block the registry cannot look up. Its own block's default state
    // is still known, so its own state-gated count is still read as placed: one leaf (the five need age 3), not five.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aReferencedTableWhoseStateLookupThrowsCostsOnlyItself(GameTestHelper helper) {
        String leaf = "{\"type\":\"minecraft:item\",\"name\":\"minecraft:leaf\",\"functions\":[{\"function\":\"minecraft:set_count\",\"count\":5,"
            + "\"conditions\":[{\"condition\":\"minecraft:block_state_property\",\"block\":\"test:bush\",\"properties\":{\"age\":\"3\"}}]}]}";
        Map<String, JsonObject> byId = Map.of(
            "test:ref", json(table(pool(certain("stick", 1)), pool(leaf), pool("{\"type\":\"minecraft:loot_table\",\"value\":\"test:bad\"}"))),
            "test:bad", json(table(pool(stateEntry("apple", "test:bad_block", "age", "3")))));
        LootEval.Environment env = new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                return byId.get(id);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }

            @Override
            public List<String> stateValues(String block, String property) {
                if (block.equals("test:bad_block")) throw new IllegalStateException("the registry has no such block");
                return List.of("0", "1", "2", "3");
            }

            @Override
            public String defaultStateValue(String block, String property) {
                return "0";
            }
        };
        BuildNotes notes = new BuildNotes();
        List<LootEval.Route> routes = new LootEval(env, notes)
            .routes("test:ref", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double stick = expected(helper, routes, "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 1.0) < TOLERANCE, "the referencer's own sticks are " + stick + ", expected 1.0");
        double leaves = expected(helper, routes, "minecraft:leaf");
        helper.assertTrue(Math.abs(leaves - 1.0) < TOLERANCE, "the referencer's own leaves are " + leaves
            + ", expected 1.0 (its default state is still known)");
        helper.assertTrue(notedFor(notes, "test:bad"), "the referenced table is not named: " + notes.lines());
        helper.assertTrue(!notedFor(notes, "test:ref"), "the referencer is named for the referenced table's throw: " + notes.lines());
        helper.succeed();
    }

    // A block's yield is read in its default state when that state yields the item at all, and in the state that yields
    // the most only when it yields none: a count a state sets (the leaf, five at age 3) is what the block has as placed,
    // and an item only a state gives (the berry, three at age 3, nothing in the default state) is read in that state.
    // Leaf: 1 in the default state (age 0 is a value the table does not name, the count of 5 needs age 3), against the
    // 5 of the best state. Berry: not in the default state, 3 at age 3.
    // With an environment that cannot say what the default state is, the best state stands in for it: the leaf is 5.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aBlockIsReadInItsDefaultStateUnlessItYieldsNothingThere(GameTestHelper helper) {
        String age3 = "[{\"condition\":\"minecraft:block_state_property\",\"block\":\"test:bush\",\"properties\":{\"age\":\"3\"}}]";
        String leaf = "{\"type\":\"minecraft:item\",\"name\":\"minecraft:leaf\",\"functions\":["
            + "{\"function\":\"minecraft:set_count\",\"count\":5,\"conditions\":" + age3 + "}]}";
        String berry = "{\"type\":\"minecraft:item\",\"name\":\"minecraft:berry\",\"conditions\":" + age3 + ","
            + "\"functions\":[{\"function\":\"minecraft:set_count\",\"count\":3}]}";
        Map<String, JsonObject> byId = Map.of("test:bush", json(table(pool(leaf), pool(berry))));
        for (boolean registry : new boolean[] {true, false}) {
            LootEval.Environment env = new LootEval.Environment() {
                @Override
                public JsonObject table(String id) {
                    return byId.get(id);
                }

                @Override
                public List<String> tagItems(String tagId) {
                    return List.of();
                }

                @Override
                public List<String> stateValues(String block, String property) {
                    return registry ? List.of("0", "1", "2", "3") : List.of();
                }

                @Override
                public String defaultStateValue(String block, String property) {
                    return registry ? "0" : null;
                }
            };
            List<LootEval.Route> routes = new LootEval(env, new BuildNotes()).routes("test:bush", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
            double leaves = expected(helper, routes, "minecraft:leaf");
            double wantedLeaves = registry ? 1.0 : 5.0;
            helper.assertTrue(Math.abs(leaves - wantedLeaves) < TOLERANCE, "leaves are " + leaves + ", expected " + wantedLeaves
                + (registry ? " (the default state)" : " (the best state: the default state is not known)"));
            double berries = expected(helper, routes, "minecraft:berry");
            helper.assertTrue(Math.abs(berries - 3.0) < TOLERANCE, "berries are " + berries + ", expected 3.0 (the best state)");
        }
        helper.succeed();
    }

    // Which state a yield is read in depends on the kind of source the table feeds. A harvested growable is left
    // alone until it is grown, so its yield is the best state's, the ripe one; a mined block keeps the state it is
    // placed in, so its yield is the default state's whenever that state yields the item at all. The same table read
    // both ways: the leaf is 5 at age 3 and 1 in the default state, age 0.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aHarvestedBlockIsReadRipeAndAMinedOneAsPlaced(GameTestHelper helper) {
        String age3 = "[{\"condition\":\"minecraft:block_state_property\",\"block\":\"test:bush\",\"properties\":{\"age\":\"3\"}}]";
        String leaf = "{\"type\":\"minecraft:item\",\"name\":\"minecraft:leaf\",\"functions\":["
            + "{\"function\":\"minecraft:set_count\",\"count\":5,\"conditions\":" + age3 + "}]}";
        Map<String, JsonObject> byId = Map.of("test:bush", json(table(pool(leaf))));
        LootEval.Environment env = new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                return byId.get(id);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }

            @Override
            public List<String> stateValues(String block, String property) {
                return List.of("0", "1", "2", "3");
            }

            @Override
            public String defaultStateValue(String block, String property) {
                return "0";
            }
        };
        LootEval eval = new LootEval(env, new BuildNotes());
        double mined = expected(helper, eval.routes("test:bush", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED), "minecraft:leaf");
        helper.assertTrue(Math.abs(mined - 1.0) < TOLERANCE, "a mined block gives " + mined + " leaves, expected 1.0 (the default state)");
        double harvested = expected(helper, eval.routes("test:bush", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.HARVESTED), "minecraft:leaf");
        helper.assertTrue(Math.abs(harvested - 5.0) < TOLERANCE, "a harvested block gives " + harvested + " leaves, expected 5.0 (the ripe state)");
        helper.succeed();
    }

    // A table that names more state combinations than are enumerated (nine boolean properties, 2^9 = 512, over the
    // bound of 256) is read in the default state alone when the environment knows it, and the table says so: the
    // best-state rule could not be applied. Every default here is false, so the ungated stick reads 1 and the apple
    // gated on p0 true reads nothing at all, where reading every state-gated entry as present would give the apple
    // 1/10 of the picks.
    @GameTest(templateNamespace = MegaBlockBot.MOD_ID, template = "empty", timeoutTicks = 20)
    public static void aTablePastTheStateLimitReadsTheDefaultStateAloneWhenKnown(GameTestHelper helper) {
        String[] entries = new String[10];
        entries[0] = certain("stick", 1);
        for (int k = 0; k < 9; k++) entries[k + 1] = stateEntry("apple", "test:many_faces", "p" + k, "true");
        Map<String, JsonObject> byId = Map.of("test:faces", json(table(pool(entries))));
        LootEval.Environment env = new LootEval.Environment() {
            @Override
            public JsonObject table(String id) {
                return byId.get(id);
            }

            @Override
            public List<String> tagItems(String tagId) {
                return List.of();
            }

            @Override
            public List<String> stateValues(String block, String property) {
                return List.of("true", "false");
            }

            @Override
            public String defaultStateValue(String block, String property) {
                return "false";
            }
        };
        BuildNotes notes = new BuildNotes();
        List<LootEval.Route> routes = new LootEval(env, notes)
            .routes("test:faces", EnumSet.of(LootEval.Tool.PLAIN), false, LootEval.StateRule.MINED);
        double stick = expected(helper, routes, "minecraft:stick");
        helper.assertTrue(Math.abs(stick - 1.0) < TOLERANCE, "sticks are " + stick + ", expected 1.0 (the default state)");
        helper.assertTrue(routes.stream().noneMatch(r -> r.item().equals("minecraft:apple")),
            "the apple of a state the default is not in is yielded: " + routes);
        helper.assertTrue(notedFor(notes, "test:faces"), "a table past the state limit is not noted: " + notes.lines());
        helper.succeed();
    }
}
