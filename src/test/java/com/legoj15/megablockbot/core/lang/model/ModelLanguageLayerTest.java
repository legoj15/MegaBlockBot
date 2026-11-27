package com.legoj15.megablockbot.core.lang.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.exec.GoalResult;
import com.legoj15.megablockbot.core.goal.ComponentCheck;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.lang.FixedGrammar;
import com.legoj15.megablockbot.core.lang.Intent;
import com.legoj15.megablockbot.core.lang.model.FakeModelServer.Answer;
import com.legoj15.megablockbot.core.lang.model.Wire.Call;
import com.legoj15.megablockbot.core.lang.model.Wire.ToolResultSeen;
import com.legoj15.megablockbot.core.task.Failure;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the model-backed layer protects (brief L20): a model's answer can only become a goal the checks allow, and a
 * bad or missing model never costs the owner their command. The layer's real HTTP client talks to a fake server
 * that plays back scripted answers in each provider's documented wire shape. Say, Unknown and built-in sentence
 * wording is never asserted, only what they carry and which kind of intent they are.
 */
class ModelLanguageLayerTest {

    private static final String LOOSE = "could you grab me a couple of stone picks";
    private static final String POSITION = "{\"x\":10,\"y\":64,\"z\":-20}";

    private FakeModelServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private ModelLanguageLayer layer(Wire wire, Answer... script) {
        server = new FakeModelServer(script);
        return new ModelLanguageLayer(wire.settings(server.url()));
    }

    private static List<Intent> interpret(ModelLanguageLayer layer, String message) throws Exception {
        return layer.interpret(message, ModelFixtures.context()).get(20, TimeUnit.SECONDS);
    }

    private static List<Intent> grammarSays(String message) {
        return new FixedGrammar().interpret(message, ModelFixtures.context()).join();
    }

    private static Call setGoal(String argumentsJson) {
        return Call.of("set_goal", argumentsJson);
    }

    private static Intent.SetGoal soleSetGoal(List<Intent> intents) {
        assertEquals(1, intents.size(), intents.toString());
        return assertInstanceOf(Intent.SetGoal.class, intents.get(0));
    }

    private static Intent.Unknown soleUnknown(List<Intent> intents) {
        assertEquals(1, intents.size(), intents.toString());
        return assertInstanceOf(Intent.Unknown.class, intents.get(0));
    }

    // ── the grammar goes first ────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aMessageTheGrammarUnderstandsMakesNoRequest(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("never asked"));
        for (String message : List.of("make 3 stone pickaxe", "cancel all", "come here", "status", "help",
                "get 2 stone; then pause")) {
            assertEquals(grammarSays(message), interpret(layer, message), message);
        }
        assertEquals(List.of(), server.requests());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aBlankMessageOrNoContextMakesNoRequest(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("never asked"));
        for (String message : new String[] {"", "   ", null}) {
            assertEquals(grammarSays(message), interpret(layer, message), String.valueOf(message));
        }
        // No context means nothing to look items up with, so the model has nothing to work from either.
        assertEquals(new FixedGrammar().interpret(LOOSE, null).join(), layer.interpret(LOOSE, null).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), server.requests());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void providerNoneIsTheFixedGrammarAndNeverReachesOut(Wire wire) throws Exception {
        server = new FakeModelServer(wire.text("never asked"));
        ModelLanguageLayer none = new ModelLanguageLayer(ModelSettings.none());
        for (String message : List.of(LOOSE, "make 3 stone pickaxe", "")) {
            assertEquals(grammarSays(message), interpret(none, message), message);
        }
        assertEquals(Optional.empty(), none.explain(failedResult(), ModelFixtures.context()).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), server.requests());
    }

    // ── a loose message, through the model ────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aLooseMessageSearchesThenSetsTheTypedGoal(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire,
                wire.toolCalls(1, Call.of("search_items", "{\"query\":\"stone pick\"}")),
                wire.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"minecraft:stone_pickaxe\",\"count\":2}")),
                wire.text("not reached"));

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 2), soleSetGoal(intents).goal());
        assertEquals(2, server.requests().size());
        server.requests().forEach(wire::assertWireFormat);

        JsonObject first = server.requests().get(0).json();
        assertEquals(List.of("set_goal", "cancel", "status", "pause", "resume", "search_items", "say"),
                wire.toolNames(first));
        assertEquals(LOOSE, wire.firstUserText(first));
        String system = wire.systemPrompt(first);
        // What the model needs to ground itself: the status paragraph, the dimension, both positions, the blueprints.
        for (String fact : List.of(ModelFixtures.STATUS, ModelFixtures.DIMENSION, "100 64 -200", "103 64 -198",
                "watch tower", "minecraft:efficiency")) {
            assertTrue(system.contains(fact), fact);
        }

        // The local search result went back to the model, keyed to the call it answers.
        List<ToolResultSeen> answered = wire.toolResults(server.requests().get(1).json());
        assertEquals(1, answered.size());
        assertEquals("%s_1_0".formatted(wire == Wire.OPENAI ? "call" : "toolu"), answered.get(0).callId());
        assertTrue(answered.get(0).content().contains("minecraft:stone_pickaxe"), answered.get(0).content());
        assertFalse(answered.get(0).flaggedError());
    }

    @Test
    void anthropicThinkingBlocksComeBackExactlyAsSent() throws Exception {
        ModelLanguageLayer layer = layer(Wire.ANTHROPIC,
                Wire.ANTHROPIC.toolCalls(1, Call.of("search_items", "{\"query\":\"stone pick\"}")),
                Wire.ANTHROPIC.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}")));

        soleSetGoal(interpret(layer, LOOSE));

        JsonObject second = server.requests().get(1).json();
        JsonArray echoed = Wire.echoedAssistantContent(second, 1);
        assertNotNull(echoed, "the first turn's content, thinking block first, was not echoed");
        assertEquals("tool_use", echoed.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("toolu_1_0", echoed.get(1).getAsJsonObject().get("id").getAsString());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void everyToolBecomesItsIntentInTheOrderCalled(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1,
                Call.of("status", "{}"), Call.of("pause", "{}"), Call.of("resume", "{}"),
                Call.of("cancel", "{\"all\":true}"), Call.of("cancel", "{}"),
                Call.of("say", "{\"text\":\"on it\"}"),
                setGoal("{\"kind\":\"go_to\",\"to_owner\":true}")));

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(List.of(new Intent.Status(), new Intent.Pause(), new Intent.Resume(), new Intent.Cancel(true),
                new Intent.Cancel(false), new Intent.Say("on it"),
                new Intent.SetGoal(new Goal.Reach(ModelFixtures.DIMENSION, ModelFixtures.OWNER_POS, 3))), intents);
        // Nothing here needs an answer from the model, so the message costs one round trip.
        assertEquals(1, server.requests().size());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aPlainTextAnswerIsSaidToTheOwner(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("I only follow commands like get, craft and go to."));

        List<Intent> intents = interpret(layer, "what can you do?");

        assertEquals(List.of(new Intent.Say("I only follow commands like get, craft and go to.")), intents);
    }

    @Test
    void anthropicAnswersParallelCallsInOneUserMessage() throws Exception {
        ModelLanguageLayer layer = layer(Wire.ANTHROPIC,
                Wire.ANTHROPIC.toolCalls(1, Call.of("search_items", "{\"query\":\"pick\"}"),
                        Call.of("search_items", "{\"query\":\"axe\"}")),
                Wire.ANTHROPIC.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}")));

        soleSetGoal(interpret(layer, LOOSE));

        // The API wants every result of one assistant turn together: user, assistant, then ONE user message.
        JsonArray messages = server.requests().get(1).json().getAsJsonArray("messages");
        assertEquals(3, messages.size());
        JsonObject results = messages.get(2).getAsJsonObject();
        assertEquals("user", results.get("role").getAsString());
        assertEquals(2, results.getAsJsonArray("content").size());
        results.getAsJsonArray("content").forEach(
                block -> assertEquals("tool_result", block.getAsJsonObject().get("type").getAsString()));
    }

    @Test
    void anAnthropicBaseUrlEndingInV1IsNotDoubled() throws Exception {
        server = new FakeModelServer(Wire.ANTHROPIC.text("hello"));
        ModelSettings settings = new ModelSettings(ModelSettings.Provider.ANTHROPIC, server.url() + "/v1",
                "claude-sonnet-5-5", ModelFixtures.SECRET, Duration.ofSeconds(10), 4, 512);

        interpret(new ModelLanguageLayer(settings), LOOSE);

        assertEquals("/v1/messages", server.requests().get(0).path());
    }

    @Test
    void openAiVariantsLocalServersSendAreTolerated() throws Exception {
        // Arguments as an object instead of a string, no call id, content as a list of text parts.
        String objectArguments = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"ignored beside a call\"}],"
                + "\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"search_items\","
                + "\"arguments\":{\"query\":\"pick\"}}}]},\"finish_reason\":\"tool_calls\"}]}";
        ModelLanguageLayer layer = layer(Wire.OPENAI, Answer.ok(objectArguments),
                Wire.OPENAI.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}")));
        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1), soleSetGoal(interpret(layer, LOOSE)).goal());
        // The search ran on the object arguments, and the result went back under an id the layer made up.
        JsonArray sent = server.requests().get(1).json().getAsJsonArray("messages");
        String madeUpId = null;
        String answeredId = null;
        for (var message : sent) {
            JsonObject object = message.getAsJsonObject();
            if (object.get("role").getAsString().equals("assistant")) {
                madeUpId = object.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString();
            } else if (object.get("role").getAsString().equals("tool")) {
                answeredId = object.get("tool_call_id").getAsString();
                assertTrue(object.get("content").getAsString().contains("minecraft:stone_pickaxe"));
            }
        }
        assertFalse(madeUpId == null || madeUpId.isBlank());
        assertEquals(madeUpId, answeredId);
        server.close();

        String parts = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"hello \"},{\"type\":\"text\",\"text\":\"there\"}]},\"finish_reason\":\"stop\"}]}";
        layer = layer(Wire.OPENAI, Answer.ok(parts));
        assertEquals(List.of(new Intent.Say("hello there")), interpret(layer, "what can you do?"));
    }

    @Test
    void openAiReasoningContentComesBackWithTheToolResults() throws Exception {
        String reasoning = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":\"she wants picks, so search first\","
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search_items\","
                + "\"arguments\":\"{\\\"query\\\":\\\"pick\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        ModelLanguageLayer layer = layer(Wire.OPENAI, Answer.ok(reasoning),
                Wire.OPENAI.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}")));

        soleSetGoal(interpret(layer, LOOSE));

        JsonArray messages = server.requests().get(1).json().getAsJsonArray("messages");
        JsonObject assistant = null;
        for (var message : messages) {
            if (message.getAsJsonObject().get("role").getAsString().equals("assistant")) assistant = message.getAsJsonObject();
        }
        assertNotNull(assistant);
        assertEquals("she wants picks, so search first", assistant.get("reasoning_content").getAsString());
        // A server that sent none gets none (the first request, and every other test, carry no such field).
        assertFalse(server.requests().get(0).body().contains("reasoning_content"));
    }

    @Test
    void thinkingLeftInTheTextIsNotShownToTheOwner() throws Exception {
        ModelLanguageLayer layer = layer(Wire.OPENAI,
                Wire.OPENAI.text("<think>they probably mean the stone one</think>Which pickaxe, stone or iron?"));

        assertEquals(List.of(new Intent.Say("Which pickaxe, stone or iron?")), interpret(layer, LOOSE));
    }

    @Test
    void aRepeatOfAnAcceptedGoalIsNotQueuedTwice() throws Exception {
        String goal = "{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\",\"count\":2}";
        ModelLanguageLayer layer = layer(Wire.OPENAI,
                Wire.OPENAI.toolCalls(1, setGoal(goal), Call.of("search_items", "{\"query\":\"axe\"}")),
                Wire.OPENAI.toolCalls(2, setGoal(goal)));

        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 2), soleSetGoal(interpret(layer, LOOSE)).goal());
    }

    // ── what the model may name: set_goal, kind by kind ───────────────────────────────────────────

    static Stream<Arguments> goalsTheChecksAllow() {
        ItemKey diamondPickaxe = ItemKey.of("diamond_pickaxe");
        Pos container = new Pos(10, 64, -20);
        return Stream.of(
                Arguments.of("{\"kind\":\"have_item\",\"item\":\"iron_pickaxe\"}",
                        new Goal.HaveItem(ItemKey.of("iron_pickaxe"), 1)),
                Arguments.of("{\"kind\":\"have_item\",\"item\":\"minecraft:diamond_pickaxe\",\"count\":2,"
                                + "\"enchantment\":\"efficiency\",\"level\":4}",
                        new Goal.HaveItem(diamondPickaxe, 2, ComponentCheck.enchantment("minecraft:efficiency", 4))),
                Arguments.of("{\"kind\":\"have_item\",\"item\":\"enchanted_book\",\"enchantment\":\"minecraft:mending\"}",
                        new Goal.HaveItem(ItemKey.of("enchanted_book"), 1,
                                ComponentCheck.enchantment("minecraft:mending", 1))),
                Arguments.of("{\"kind\":\"tool_tier\",\"tool_class\":\"pickaxe\",\"tier\":\"iron\"}",
                        new Goal.HaveToolTier(ToolClass.PICKAXE, ToolTier.IRON)),
                Arguments.of("{\"kind\":\"equip\",\"items\":[\"iron_helmet\",\"iron_chestplate\",\"shield\"]}",
                        new Goal.Equip(Map.of(EquipSlot.HEAD, ItemKey.of("iron_helmet"),
                                EquipSlot.CHEST, ItemKey.of("iron_chestplate"),
                                EquipSlot.OFFHAND, ItemKey.of("shield")))),
                Arguments.of("{\"kind\":\"stockpile\",\"item\":\"cobblestone\",\"count\":64,\"position\":" + POSITION + "}",
                        new Goal.Stockpile(ItemKey.of("cobblestone"), 64, ModelFixtures.DIMENSION, container)),
                // The goal carries the blueprint's own spelling, however the model cased it.
                Arguments.of("{\"kind\":\"build\",\"blueprint\":\"Watch  TOWER\",\"position\":" + POSITION + "}",
                        new Goal.Build("watch tower", ModelFixtures.DIMENSION, container)),
                Arguments.of("{\"kind\":\"go_to\",\"position\":" + POSITION + ",\"radius\":5}",
                        new Goal.Reach(ModelFixtures.DIMENSION, container, 5)),
                Arguments.of("{\"kind\":\"go_to\",\"position\":" + POSITION + "}",
                        new Goal.Reach(ModelFixtures.DIMENSION, container, 2)),
                Arguments.of("{\"kind\":\"go_to\",\"to_owner\":true}",
                        new Goal.Reach(ModelFixtures.DIMENSION, ModelFixtures.OWNER_POS, 3)),
                Arguments.of("{\"kind\":\"go_to\",\"to_owner\":true,\"radius\":0}",
                        new Goal.Reach(ModelFixtures.DIMENSION, ModelFixtures.OWNER_POS, 0)));
    }

    @ParameterizedTest
    @MethodSource("goalsTheChecksAllow")
    void aCheckedSetGoalBecomesTheTypedGoal(String arguments, Goal expected) throws Exception {
        ModelLanguageLayer layer = layer(Wire.OPENAI, Wire.OPENAI.toolCalls(1, setGoal(arguments)));

        assertEquals(expected, soleSetGoal(interpret(layer, LOOSE)).goal());
        assertEquals(1, server.requests().size());
    }

    @Test
    void anEnchantmentIsTakenAsNamedWhileTheGameKnowsNoneYet() throws Exception {
        server = new FakeModelServer(Wire.OPENAI.toolCalls(1,
                setGoal("{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"othermod:zoom\",\"level\":2}")));
        ModelLanguageLayer layer = new ModelLanguageLayer(Wire.OPENAI.settings(server.url()));

        Goal goal = soleSetGoal(layer.interpret(LOOSE, ModelFixtures.context(Set.of())).get(20, TimeUnit.SECONDS)).goal();

        assertEquals(new Goal.HaveItem(ItemKey.of("diamond_pickaxe"), 1, ComponentCheck.enchantment("othermod:zoom", 2)),
                goal);
    }

    // ── what the model may not name ───────────────────────────────────────────────────────────────

    /** Each row: the tool, its arguments, and the thing in them that the error and the owner's Unknown must name. */
    static Stream<Arguments> goalsTheChecksRefuse() {
        List<Arguments> rows = new ArrayList<>();
        for (Wire wire : Wire.values()) {
            rows.add(Arguments.of(wire, "set_goal", "{\"kind\":\"have_item\",\"item\":\"minecraft:stone_pick\"}",
                    "minecraft:stone_pick"));
        }
        rows.addAll(List.of(
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"have_item\",\"item\":\"stone\",\"count\":0}", "count"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"have_item\",\"item\":\"stone\",\"count\":-3}", "count"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"have_item\",\"item\":\"stone\",\"count\":10001}", "count"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"minecraft:efficiancy\"}",
                        "minecraft:efficiancy"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"efficiency\",\"level\":0}",
                        "level"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"efficiency\",\"level\":256}",
                        "level"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"build\",\"blueprint\":\"castle\",\"position\":"
                        + POSITION + "}", "castle"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"build\",\"blueprint\":\"cottage\"}", "position"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"go_to\",\"position\":" + POSITION + ",\"radius\":257}",
                        "radius"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"go_to\",\"position\":" + POSITION + ",\"radius\":-1}",
                        "radius"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"go_to\",\"position\":{\"x\":1,\"y\":2}}", "position"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"go_to\"}", "position"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"tool_tier\",\"tool_class\":\"pickaxe\",\"tier\":\"ruby\"}",
                        "ruby"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"tool_tier\",\"tool_class\":\"wand\",\"tier\":\"iron\"}",
                        "wand"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"equip\",\"items\":[\"iron_helmet\",\"tin_helmet\"]}",
                        "tin_helmet"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"equip\",\"items\":[\"iron_helmet\",\"diamond_helmet\"]}",
                        "head"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"equip\",\"items\":[]}", "items"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"stockpile\",\"item\":\"mithril\",\"count\":5,\"position\":" + POSITION + "}", "mithril"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"stockpile\",\"item\":\"cobblestone\",\"position\":" + POSITION + "}", "count"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"stockpile\",\"item\":\"cobblestone\",\"count\":0,\"position\":" + POSITION + "}", "count"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"stockpile\",\"item\":\"cobblestone\",\"count\":10001,\"position\":" + POSITION + "}",
                        "count"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"teleport\"}", "teleport"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"item\":\"stone\"}", "kind"),
                Arguments.of(Wire.OPENAI, "set_goal", "{oops", "arguments"),
                Arguments.of(Wire.OPENAI, "fly", "{}", "fly")));
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("goalsTheChecksRefuse")
    void aCallThatFailsACheckIsSentBackOnceThenIsUnknown(Wire wire, String tool, String arguments, String mention)
            throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1, Call.of(tool, arguments)));

        List<Intent> intents = interpret(layer, LOOSE);

        // The model got one chance to correct itself; it repeated itself, so the owner reads what was wrong.
        assertEquals(2, server.requests().size());
        Intent.Unknown unknown = soleUnknown(intents);
        assertTrue(unknown.reason().contains(mention), unknown.reason());
        List<ToolResultSeen> told = wire.toolResults(server.requests().get(1).json());
        assertEquals(1, told.size());
        assertTrue(told.get(0).content().contains(mention), told.get(0).content());
        if (wire == Wire.ANTHROPIC) assertTrue(told.get(0).flaggedError(), "the refusal must carry is_error");
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aModelThatCorrectsItselfAfterTheErrorGetsItsGoal(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire,
                wire.toolCalls(1, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pick\",\"count\":2}")),
                wire.toolCalls(2, Call.of("search_items", "{\"query\":\"stone pick\"}")),
                wire.toolCalls(3, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\",\"count\":2}")));

        List<Intent> intents = interpret(layer, LOOSE);

        // One correction round only: the search was a second round trip, and the third request is the corrected goal.
        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 2), soleSetGoal(intents).goal());
        assertEquals(3, server.requests().size());
    }

    @Test
    void theGoalsThatPassedStayWhenOneInTheSameReplyFails() throws Exception {
        ModelLanguageLayer layer = layer(Wire.OPENAI,
                Wire.OPENAI.toolCalls(1,
                        setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}"),
                        setGoal("{\"kind\":\"have_item\",\"item\":\"unobtainium\"}")),
                Wire.OPENAI.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"iron_axe\"}")));

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(List.of(new Intent.SetGoal(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1)),
                new Intent.SetGoal(new Goal.HaveItem(ItemKey.of("iron_axe"), 1))), intents);
    }

    // ── a bad or missing model costs the owner nothing ────────────────────────────────────────────

    /** One way a model server can fail to give an answer. */
    enum Outage {
        TIMEOUT, REFUSED, HTTP_500, UNAUTHORIZED_ECHOING_THE_KEY, NOT_JSON, TRUNCATED_JSON, EMPTY_OBJECT, NO_CHOICES;

        /** The settings that reach a server failing this way; the caller owns the fake server it may start. */
        ModelSettings settingsFor(Wire wire, ModelLanguageLayerTest test) throws Exception {
            switch (this) {
                case REFUSED:
                    // A port nothing listens on: bind one, note it, let it go.
                    int port;
                    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                        port = socket.getLocalPort();
                    }
                    return wire.settings("http://127.0.0.1:" + port);
                case TIMEOUT:
                    test.server = new FakeModelServer(wire.text("too late").after(8000));
                    return wire.settings(test.server.url(), Duration.ofMillis(300), 4);
                default:
                    test.server = new FakeModelServer(answer());
                    return wire.settings(test.server.url());
            }
        }

        private Answer answer() {
            switch (this) {
                case HTTP_500: return Answer.status(500, "{\"error\":\"internal\"}");
                case UNAUTHORIZED_ECHOING_THE_KEY:
                    // Hosted gateways do print the key back on a 401.
                    return Answer.status(401, "{\"error\":{\"message\":\"Incorrect API key provided: "
                            + ModelFixtures.SECRET + "\"}}");
                case NOT_JSON: return Answer.ok("this is not json at all");
                case TRUNCATED_JSON: return Answer.ok("{\"choices\":[{\"messa");
                case EMPTY_OBJECT: return Answer.ok("{}");
                case NO_CHOICES: return Answer.ok("{\"choices\":[],\"content\":[]}");
                default: throw new IllegalStateException(name());
            }
        }
    }

    static Stream<Arguments> everyWireFailingEveryWay() {
        List<Arguments> rows = new ArrayList<>();
        for (Wire wire : Wire.values()) {
            for (Outage outage : Outage.values()) rows.add(Arguments.of(wire, outage));
        }
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("everyWireFailingEveryWay")
    void aBadOrMissingModelCompletesWithWhatTheGrammarSaid(Wire wire, Outage outage) throws Exception {
        ModelLanguageLayer layer = new ModelLanguageLayer(outage.settingsFor(wire, this));
        long start = System.nanoTime();

        CompletableFuture<List<Intent>> future = layer.interpret(LOOSE, ModelFixtures.context());
        List<Intent> intents = future.get(5, TimeUnit.SECONDS);

        assertFalse(future.isCompletedExceptionally());
        if (outage == Outage.TIMEOUT) {
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 4000, "the timeout did not end the wait");
        }
        Intent.Unknown said = soleUnknown(grammarSays(LOOSE));
        Intent.Unknown unknown = soleUnknown(intents);
        assertTrue(unknown.reason().startsWith(said.reason()), unknown.reason());
        assertTrue(unknown.reason().length() > said.reason().length(), "no line said the model could not be reached");
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void theUnderstoodPartOfAMessageSurvivesAMissingModel(Wire wire) throws Exception {
        ModelLanguageLayer layer = new ModelLanguageLayer(Outage.REFUSED.settingsFor(wire, this));
        String message = "get 2 stone; sing me a song";

        List<Intent> intents = interpret(layer, message);
        List<Intent> said = grammarSays(message);

        assertEquals(2, intents.size());
        assertEquals(said.get(0), intents.get(0));
        assertInstanceOf(Intent.Unknown.class, intents.get(1));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void goalsTheModelAlreadyGaveSurviveAFailureOnALaterRoundTrip(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire,
                wire.toolCalls(1, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}"),
                        Call.of("search_items", "{\"query\":\"axe\"}")),
                Answer.status(500, "{}"));

        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1), soleSetGoal(interpret(layer, LOOSE)).goal());
    }

    /** A reply that calls these tools, built without a server. */
    private static ChatBackend.Reply calling(ChatBackend.ToolCall... calls) {
        return new ChatBackend.Reply("", List.of(calls), null, "tool_calls");
    }

    @Test
    void aBackendThatThrowsInsteadOfFailingKeepsTheGoalsAlreadyAccepted() throws Exception {
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        ChatBackend broken = request -> {
            if (sends.incrementAndGet() > 1) throw new IllegalStateException("backend bug " + ModelFixtures.SECRET);
            return CompletableFuture.completedFuture(calling(
                    new ChatBackend.ToolCall("c1", "set_goal", "{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}"),
                    new ChatBackend.ToolCall("c2", "search_items", "{\"query\":\"axe\"}")));
        };
        ModelLanguageLayer layer = new ModelLanguageLayer(Wire.OPENAI.settings("http://127.0.0.1:1"), broken);

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1), soleSetGoal(intents).goal());
        assertEquals(2, sends.get());
    }

    @Test
    void aBackendThatThrowsAtOnceMeansTheGrammarsAnswerPlusTheNote() throws Exception {
        ChatBackend broken = request -> {
            throw new IllegalStateException("backend bug");
        };
        ModelLanguageLayer layer = new ModelLanguageLayer(Wire.OPENAI.settings("http://127.0.0.1:1"), broken);

        Intent.Unknown said = soleUnknown(grammarSays(LOOSE));
        Intent.Unknown unknown = soleUnknown(interpret(layer, LOOSE));

        assertTrue(unknown.reason().startsWith(said.reason()), unknown.reason());
        assertTrue(unknown.reason().length() > said.reason().length());
    }

    static Stream<Arguments> caps() {
        List<Arguments> rows = new ArrayList<>();
        for (int cap : List.of(1, 2, 4)) rows.add(Arguments.of(Wire.OPENAI, cap));
        rows.add(Arguments.of(Wire.ANTHROPIC, ModelSettings.DEFAULT_MAX_ROUND_TRIPS));
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("caps")
    void theRoundTripCapEndsAModelThatKeepsSearching(Wire wire, int cap) throws Exception {
        server = new FakeModelServer(wire.toolCalls(1, Call.of("search_items", "{\"query\":\"pick\"}")));
        ModelLanguageLayer layer = new ModelLanguageLayer(wire.settings(server.url(), Duration.ofSeconds(10), cap));

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(cap, server.requests().size());
        Intent.Unknown said = soleUnknown(grammarSays(LOOSE));
        Intent.Unknown unknown = soleUnknown(intents);
        assertTrue(unknown.reason().startsWith(said.reason()), unknown.reason());
    }

    @Test
    void theDefaultCapIsFourRoundTrips() {
        assertEquals(4, ModelSettings.none().maxRoundTrips());
    }

    // ── explain ───────────────────────────────────────────────────────────────────────────────────

    private static GoalResult failedResult() {
        return new GoalResult(new Goal.HaveItem(ItemKey.of("diamond"), 3), GoalResult.Status.PARTIAL, 1, 3,
                List.of("minecraft:diamond"),
                new Failure.NoSource(ItemKey.of("diamond"), "ore:diamond", "nothing in range"));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void explainReturnsTheModelsSentenceForAFailedGoal(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("  I found no diamonds nearby, so I have one of the three.\n"));

        Optional<String> explained = layer.explain(failedResult(), ModelFixtures.context()).get(20, TimeUnit.SECONDS);

        assertEquals(Optional.of("I found no diamonds nearby, so I have one of the three."), explained);
        assertEquals(1, server.requests().size());
        server.requests().forEach(wire::assertWireFormat);
        // The model is given the typed result, not a story: the goal, how far it got, and the failure's own fields.
        String facts = wire.firstUserText(server.requests().get(0).json());
        for (String fact : List.of("minecraft:diamond", "NO_SOURCE", "ore:diamond", "nothing in range")) {
            assertTrue(facts.contains(fact), fact + " missing from: " + facts);
        }
        // Explaining takes no tools.
        assertEquals(List.of(), wire.toolNames(server.requests().get(0).json()));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aSatisfiedGoalNeedsNoModel(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("never asked"));
        GoalResult done = new GoalResult(new Goal.HaveItem(ItemKey.of("diamond"), 3), GoalResult.Status.SATISFIED, 3, 3,
                List.of(), null);

        assertEquals(Optional.empty(), layer.explain(done, ModelFixtures.context()).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), server.requests());
    }

    private static List<GoalResult> everyKindOfEnding() {
        Pos pos = new Pos(1, 2, 3);
        String dimension = ModelFixtures.DIMENSION;
        Goal goal = new Goal.HaveItem(ItemKey.of("iron_ingot"), 3);
        List<Failure> failures = List.of(
                new Failure.Unreachable("the mine", "no path", "x"),
                new Failure.TargetGone("the chest", "x"),
                new Failure.MissingTool(ToolClass.PICKAXE, ToolTier.IRON, "x"),
                new Failure.MissingInput(ItemKey.of("iron_ingot"), 3, "x"),
                new Failure.NoSource(ItemKey.of("iron_ingot"), "ore", "x"),
                new Failure.Protected(pos, "container", "x"),
                new Failure.Hazard("lava", dimension, pos, "x"),
                new Failure.TimedOut(1200, "x"),
                new Failure.NoProgress(600, "x"),
                new Failure.Died(dimension, pos, List.of(pos), 5L, "x"),
                new Failure.PreemptStalled("zombie", "x"),
                new Failure.Internal(new IllegalStateException("boom"), "x"));
        List<GoalResult> results = new ArrayList<>();
        for (Failure failure : failures) {
            results.add(new GoalResult(goal, GoalResult.Status.FAILED, 0, 3, List.of("minecraft:iron_ingot"), failure));
        }
        results.add(new GoalResult(goal, GoalResult.Status.CANCELLED, 1, 3, List.of(), null));
        results.add(new GoalResult(goal, GoalResult.Status.PARTIAL, 1, 3, List.of("minecraft:iron_ingot"), null));
        return results;
    }

    @Test
    void everyFailureKindHasItsOwnBuiltInSentenceWhenTheServerIsDown() throws Exception {
        ModelLanguageLayer layer = new ModelLanguageLayer(Outage.REFUSED.settingsFor(Wire.OPENAI, this));
        Set<String> sentences = new HashSet<>();
        List<GoalResult> endings = everyKindOfEnding();

        for (GoalResult ending : endings) {
            Optional<String> sentence = layer.explain(ending, ModelFixtures.context()).get(10, TimeUnit.SECONDS);
            assertTrue(sentence.isPresent() && !sentence.get().isBlank(), "no sentence for " + ending);
            sentences.add(sentence.get());
        }

        // The failed endings differ in kind, so their sentences must too; two cancelled/partial ones may share a goal.
        assertEquals(endings.size(), sentences.size(), sentences.toString());
        // The sentence says which goal and how far it got, so the owner is not told "failed" and nothing else.
        String partial = layer.explain(endings.get(endings.size() - 1), ModelFixtures.context()).get(10, TimeUnit.SECONDS).get();
        assertTrue(partial.contains("1") && partial.contains("3"), partial);
    }

    @ParameterizedTest
    @EnumSource(value = Outage.class, names = {"TIMEOUT", "HTTP_500", "NOT_JSON", "UNAUTHORIZED_ECHOING_THE_KEY"})
    void explainFallsBackToTheBuiltInSentenceWhenTheModelFails(Outage outage) throws Exception {
        ModelLanguageLayer layer = new ModelLanguageLayer(outage.settingsFor(Wire.OPENAI, this));

        Optional<String> sentence = layer.explain(failedResult(), ModelFixtures.context()).get(5, TimeUnit.SECONDS);

        assertTrue(sentence.isPresent() && !sentence.get().isBlank());
        assertTrue(sentence.get().contains("diamond"), sentence.get());
    }

    @Test
    void anEmptyModelAnswerFallsBackToTheBuiltInSentence() throws Exception {
        ModelLanguageLayer layer = layer(Wire.OPENAI, Wire.OPENAI.text("   "));

        Optional<String> sentence = layer.explain(failedResult(), ModelFixtures.context()).get(5, TimeUnit.SECONDS);

        assertTrue(sentence.isPresent() && sentence.get().contains("diamond"), sentence.toString());
    }

    // ── the key stays out of everything ───────────────────────────────────────────────────────────

    @Test
    void theKeyIsInNoLogLineToStringOrReturnedText() throws Exception {
        List<String> produced = new ArrayList<>();
        try (LogCapture logs = new LogCapture(ModelLanguageLayer.class.getPackageName())) {
            for (Wire wire : Wire.values()) {
                // A good run, with a search in the middle.
                try (FakeModelServer good = new FakeModelServer(
                        wire.toolCalls(1, Call.of("search_items", "{\"query\":\"pick\"}")),
                        wire.toolCalls(2, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}")))) {
                    ModelSettings settings = wire.settings(good.url());
                    produced.add(settings.toString());
                    produced.add(new ModelLanguageLayer(settings).interpret(LOOSE, ModelFixtures.context())
                            .get(20, TimeUnit.SECONDS).toString());
                    // The key travels in a header; no request body (system prompt, tool results) may carry it.
                    good.requests().forEach(request -> {
                        assertFalse(request.body().contains(ModelFixtures.SECRET));
                        assertEquals(ModelFixtures.SECRET, wire == Wire.OPENAI
                                ? request.header("authorization").substring("Bearer ".length())
                                : request.header("x-api-key"));
                    });
                }
                // Every way of failing, including a 401 that echoes the key back in its body.
                for (Outage outage : Outage.values()) {
                    ModelLanguageLayer layer = new ModelLanguageLayer(outage.settingsFor(wire, this));
                    produced.add(layer.interpret(LOOSE, ModelFixtures.context()).get(10, TimeUnit.SECONDS).toString());
                    produced.add(layer.explain(failedResult(), ModelFixtures.context()).get(10, TimeUnit.SECONDS).toString());
                    if (server != null) {
                        server.close();
                        server = null;
                    }
                }
            }
            produced.addAll(logs.lines());
            // Not a vacuous scan: the failures above must have logged something.
            assertFalse(logs.lines().isEmpty(), "nothing was logged, so the scan proves nothing");
        }
        assertFalse(produced.isEmpty());
        for (String text : produced) assertFalse(text.contains(ModelFixtures.SECRET), text);
        assertNotEquals("", ModelSettings.none().toString());
    }

    @Test
    void settingsNeverPrintTheKey() {
        ModelSettings settings = Wire.ANTHROPIC.settings("https://api.anthropic.com");
        assertFalse(settings.toString().contains(ModelFixtures.SECRET));
        assertFalse(settings.redact("echoed: " + ModelFixtures.SECRET).contains(ModelFixtures.SECRET));
    }
}
