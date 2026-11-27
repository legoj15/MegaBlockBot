package com.legoj15.megablockbot.core.lang.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.legoj15.megablockbot.core.lang.LanguageContext;
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
import java.util.stream.IntStream;
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

    // ── a clause the grammar understood is never lost ─────────────────────────────────────────────

    /** The grammar reads the first clause and not the second, so the model is asked about the message. */
    private static final String MIXED = "get 2 bread; sing me a song";
    private static final Goal BREAD = new Goal.HaveItem(ItemKey.of("bread"), 2);
    private static final String BREAD_ARGS = "{\"kind\":\"have_item\",\"item\":\"bread\",\"count\":2}";
    private static final String AXE_ARGS = "{\"kind\":\"have_item\",\"item\":\"iron_axe\"}";

    /** Every way a model can answer for the clause the grammar could not read, with what the owner must end up with. */
    static Stream<Arguments> modelAnswersToAHalfUnderstoodMessage() {
        Intent bread = new Intent.SetGoal(BREAD);
        Intent said = new Intent.Say("I cannot sing, sorry.");
        // The model contributed nothing new, so the grammar's own complaint stays, with the note.
        String complaint = ((Intent.Unknown) grammarSays(MIXED).get(1)).reason();
        Intent kept = new Intent.Unknown(complaint + " " + ModelLanguageLayer.UNDECIDED_NOTE);
        List<Arguments> rows = new ArrayList<>();
        for (Wire wire : Wire.values()) {
            rows.add(Arguments.of(wire, "plain text", wire.text("I cannot sing, sorry."), List.of(bread, said)));
            rows.add(Arguments.of(wire, "only say", wire.toolCalls(1, Call.of("say", "{\"text\":\"I cannot sing, sorry.\"}")),
                    List.of(bread, said)));
            rows.add(Arguments.of(wire, "only status", wire.toolCalls(1, Call.of("status", "{}")),
                    List.of(bread, new Intent.Status())));
            rows.add(Arguments.of(wire, "the same goal again", wire.toolCalls(1, setGoal(BREAD_ARGS)), List.of(bread, kept)));
            rows.add(Arguments.of(wire, "a second goal", wire.toolCalls(1, setGoal(AXE_ARGS)),
                    List.of(bread, new Intent.SetGoal(new Goal.HaveItem(ItemKey.of("iron_axe"), 1)))));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("modelAnswersToAHalfUnderstoodMessage")
    void aClauseTheGrammarUnderstoodIsNeverLost(Wire wire, String how, Answer answer, List<Intent> expected)
            throws Exception {
        ModelLanguageLayer layer = layer(wire, answer);

        List<Intent> intents = interpret(layer, MIXED);

        assertEquals(expected, intents, how);
        assertEquals(1, intents.stream().filter(new Intent.SetGoal(BREAD)::equals).count(), how);
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void theModelIsToldWhatWasAlreadyUnderstood(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.text("I cannot sing."));
        assertEquals(new Intent.SetGoal(BREAD), grammarSays(MIXED).get(0));

        interpret(layer, MIXED);
        interpret(layer, "sing me a song");

        // The request must tell the model that the bread goal is queued already, and only for the message that had
        // one. Where in the request it says so is the layer's choice, so the whole body is searched.
        assertTrue(server.requests().get(0).body().contains("minecraft:bread"));
        assertFalse(server.requests().get(1).body().contains("minecraft:bread"));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aControlTheGrammarGaveIsNotQueuedASecondTime(Wire wire) throws Exception {
        // The model repeats the pause and adds a plain cancel, which is a different intent from the grammar's
        // "cancel all" and is kept on purpose: the grammar's two stay, the repeated pause does not.
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1, Call.of("pause", "{}"), Call.of("cancel", "{}"),
                Call.of("status", "{}")));

        List<Intent> intents = interpret(layer, "pause; cancel all; sing me a song");

        // Cancel(false) is not the Cancel(true) the grammar gave, so it stays.
        assertEquals(List.of(new Intent.Pause(), new Intent.Cancel(true), new Intent.Cancel(false), new Intent.Status()),
                intents);
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aSayTheModelFumbledIsNotShownToTheOwner(Wire wire) throws Exception {
        // The say is refused and sent back; the model then answers in plain text. The owner gets the answer, and no
        // line about a tool the owner never heard of.
        ModelLanguageLayer layer = layer(wire,
                wire.toolCalls(1, Call.of("say", "{\"text\":\"<think>hm</think>\"}")),
                wire.text("Hello there."));

        assertEquals(List.of(new Intent.Say("Hello there.")), interpret(layer, LOOSE));
        assertEquals(2, server.requests().size());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void pauseResumePauseFromTheModelAreThreeThings(Wire wire) throws Exception {
        // "hold on; ok carry on; hold on again": dropping the second pause as a repeat would leave the bot running.
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1, Call.of("pause", "{}"), Call.of("resume", "{}"),
                Call.of("pause", "{}")));

        assertEquals(List.of(new Intent.Pause(), new Intent.Resume(), new Intent.Pause()), interpret(layer, LOOSE));
    }

    @Test
    void theGrammarsIntentsComeFirstInTheirOrderThenTheModelsOwn() throws Exception {
        ModelLanguageLayer layer = layer(Wire.OPENAI, Wire.OPENAI.toolCalls(1, setGoal(BREAD_ARGS), setGoal(AXE_ARGS)));

        List<Intent> intents = interpret(layer, "get 2 bread; sing me a song; pause");

        assertEquals(List.of(new Intent.SetGoal(BREAD), new Intent.Pause(),
                new Intent.SetGoal(new Goal.HaveItem(ItemKey.of("iron_axe"), 1))), intents);
    }

    // ── a refusal the model never corrected reaches the owner ─────────────────────────────────────

    private static final String NO_SUCH_ITEM = "{\"kind\":\"have_item\",\"item\":\"minecraft:stone_pick\",\"count\":2}";

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aRefusalTheRoundTripCapLeftUncorrectedReachesTheOwner(Wire wire) throws Exception {
        // What a second refusal tells the owner: the reference the capped run must give as well.
        String second;
        try (FakeModelServer repeating = new FakeModelServer(wire.toolCalls(1, setGoal(NO_SUCH_ITEM)))) {
            second = soleUnknown(interpret(new ModelLanguageLayer(wire.settings(repeating.url())), LOOSE)).reason();
        }
        server = new FakeModelServer(wire.toolCalls(1, setGoal(NO_SUCH_ITEM)));
        ModelLanguageLayer capped = new ModelLanguageLayer(wire.settings(server.url(), Duration.ofSeconds(10), 1));

        List<Intent> intents = interpret(capped, LOOSE);

        assertEquals(1, server.requests().size());
        assertTrue(second.contains("minecraft:stone_pick"), second);
        assertEquals(second, soleUnknown(intents).reason());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aRefusalFollowedByAPlainAnswerStillReachesTheOwner(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1, setGoal(NO_SUCH_ITEM)),
                wire.text("Sorry, there is no such pickaxe."));

        List<Intent> intents = interpret(layer, LOOSE);

        assertEquals(2, intents.size(), intents.toString());
        assertEquals(new Intent.Say("Sorry, there is no such pickaxe."), intents.get(0));
        assertTrue(assertInstanceOf(Intent.Unknown.class, intents.get(1)).reason().contains("minecraft:stone_pick"));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aRefusalFollowedByAFailedRoundTripStillReachesTheOwner(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1, setGoal(NO_SUCH_ITEM)), Answer.status(500, "{}"));

        List<Intent> intents = interpret(layer, LOOSE);

        assertTrue(soleUnknown(intents).reason().contains("minecraft:stone_pick"), intents.toString());
    }

    // ── what the owner reads from the model is treated one way ────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aSayToolsTextLosesItsThinkingLikeAPlainAnswer(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire, wire.toolCalls(1,
                Call.of("say", "{\"text\":\"<think>they want a song</think>I cannot sing.\"}")));

        assertEquals(List.of(new Intent.Say("I cannot sing.")), interpret(layer, LOOSE));
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aSayWithNothingButThinkingInItIsSentBack(Wire wire) throws Exception {
        ModelLanguageLayer layer = layer(wire,
                wire.toolCalls(1, Call.of("say", "{\"text\":\"<think>hm</think>\"}")),
                wire.toolCalls(2, Call.of("say", "{\"text\":\"Hi.\"}")));

        assertEquals(List.of(new Intent.Say("Hi.")), interpret(layer, LOOSE));
        assertEquals(2, server.requests().size());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void textForTheOwnerIsCutAtTheMaximumLength(Wire wire) throws Exception {
        String endless = "x".repeat(ModelLanguageLayer.MAX_OWNER_TEXT + 100);
        for (Answer answer : List.of(wire.text(endless), wire.toolCalls(1, Call.of("say", "{\"text\":\"" + endless + "\"}")))) {
            try (FakeModelServer one = new FakeModelServer(answer)) {
                List<Intent> intents = interpret(new ModelLanguageLayer(wire.settings(one.url())), LOOSE);

                assertEquals(1, intents.size(), intents.toString());
                String said = assertInstanceOf(Intent.Say.class, intents.get(0)).text();
                assertEquals(ModelLanguageLayer.MAX_OWNER_TEXT, said.length());
                assertTrue(endless.startsWith(said));
            }
        }
    }

    @Test
    void aLongBlueprintListIsCappedInThePrompt() throws Exception {
        int total = ModelPrompts.MAX_LISTED_BLUEPRINTS + 150;
        List<String> many = IntStream.range(0, total).mapToObj(i -> "blueprint %03d".formatted(i)).toList();
        LanguageContext context = new LanguageContext(new ModelFixtures.Items(), ModelFixtures.DIMENSION,
                ModelFixtures.OWNER_POS, ModelFixtures.BOT_POS, ModelFixtures.STATUS, many, ModelFixtures.ENCHANTMENTS);
        server = new FakeModelServer(Wire.OPENAI.text("hello"));

        new ModelLanguageLayer(Wire.OPENAI.settings(server.url())).interpret(LOOSE, context).get(20, TimeUnit.SECONDS);

        String system = Wire.OPENAI.systemPrompt(server.requests().get(0).json());
        assertTrue(system.contains("blueprint 000"));
        assertTrue(system.contains("blueprint %03d".formatted(ModelPrompts.MAX_LISTED_BLUEPRINTS - 1)));
        assertFalse(system.contains("blueprint %03d".formatted(ModelPrompts.MAX_LISTED_BLUEPRINTS)));
        assertTrue(system.contains("and 150 more"), "the cut-off list must say how many were left out");
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

    @Test
    void aBlankEnchantmentIsRefusedEvenWhileTheGameKnowsNoneYet() throws Exception {
        // With no known enchantments an id is taken as named, which is why "" must be caught before it is namespaced.
        server = new FakeModelServer(Wire.OPENAI.toolCalls(1, setGoal(
                "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"  \"}")));
        ModelLanguageLayer layer = new ModelLanguageLayer(Wire.OPENAI.settings(server.url()));

        List<Intent> intents = layer.interpret(LOOSE, ModelFixtures.context(Set.of())).get(20, TimeUnit.SECONDS);

        assertTrue(soleUnknown(intents).reason().contains("needs a name"), intents.toString());
    }

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
                // A name that is empty or blank is refused as such, before anything is made of it (never "minecraft:").
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"have_item\",\"item\":\"\"}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"have_item\",\"item\":\"   \"}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"\"}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"have_item\",\"item\":\"diamond_pickaxe\",\"enchantment\":\"  \",\"level\":2}",
                        "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"stockpile\",\"item\":\"\",\"count\":5,\"position\":" + POSITION + "}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"equip\",\"items\":[\"iron_helmet\",\" \"]}",
                        "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal", "{\"kind\":\"equip\",\"item\":\"\"}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"build\",\"blueprint\":\"\",\"position\":" + POSITION + "}", "needs a name"),
                Arguments.of(Wire.OPENAI, "set_goal",
                        "{\"kind\":\"build\",\"blueprint\":\"   \",\"position\":" + POSITION + "}", "needs a name"),
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

    /** {@code answer} with a throw-away JSON field added so that its body is exactly {@code bytes} long (all ASCII). */
    private static Answer paddedTo(Answer answer, int bytes) {
        String body = answer.body();
        String head = body.substring(0, body.length() - 1) + ",\"padding\":\"";
        String tail = "\"}";
        String padded = head + "x".repeat(bytes - head.length() - tail.length()) + tail;
        assertEquals(bytes, padded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return new Answer(answer.status(), padded, answer.delayMillis(), answer.stallMillis());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aResponseBodyOverTheLimitFailsTheRoundTripLikeAnyOther(Wire wire) throws Exception {
        Answer good = wire.toolCalls(1, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}"));
        ModelLanguageLayer layer = layer(wire, paddedTo(good, JsonPost.MAX_RESPONSE_BYTES + 1));

        List<Intent> intents = interpret(layer, LOOSE);

        Intent.Unknown said = soleUnknown(grammarSays(LOOSE));
        Intent.Unknown unknown = soleUnknown(intents);
        assertTrue(unknown.reason().startsWith(said.reason()), unknown.reason());
        assertTrue(unknown.reason().length() > said.reason().length(), "no line said the model could not be reached");

        // The limit is that size and no smaller: a body of exactly that many bytes is read.
        server.script(paddedTo(good, JsonPost.MAX_RESPONSE_BYTES));
        assertEquals(new Goal.HaveItem(ItemKey.of("stone_pickaxe"), 1), soleSetGoal(interpret(layer, LOOSE)).goal());
    }

    @ParameterizedTest
    @EnumSource(Wire.class)
    void aBodyIsNotReadOnPastTheLimit(Wire wire) throws Exception {
        // The server sends well over the limit and then leaves the response open for 8 s. A client that held the
        // whole body before looking at its size would sit there until the stall ends.
        Answer good = wire.toolCalls(1, setGoal("{\"kind\":\"have_item\",\"item\":\"stone_pickaxe\"}"));
        ModelLanguageLayer layer = layer(wire, paddedTo(good, 2 * JsonPost.MAX_RESPONSE_BYTES).thenStalls(8000));
        long start = System.nanoTime();

        List<Intent> intents = layer.interpret(LOOSE, ModelFixtures.context()).get(5, TimeUnit.SECONDS);

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 4000, "the limit did not end the read");
        Intent.Unknown said = soleUnknown(grammarSays(LOOSE));
        assertTrue(soleUnknown(intents).reason().length() > said.reason().length(), intents.toString());
    }

    static Stream<GoalResult> resultsTheBuiltInTextCannotDescribe() {
        Goal goal = new Goal.HaveItem(ItemKey.of("diamond"), 3);
        return Stream.of(
                new GoalResult(goal, GoalResult.Status.FAILED, 0, 3, List.of(),
                        new Failure.Protected(null, "container", "x")),
                new GoalResult(goal, GoalResult.Status.PARTIAL, 1, 3, List.of(),
                        new Failure.Hazard("lava", ModelFixtures.DIMENSION, null, "x")),
                new GoalResult(null, GoalResult.Status.FAILED, 0, 3, List.of(), null));
    }

    @ParameterizedTest
    @MethodSource("resultsTheBuiltInTextCannotDescribe")
    void explainNeverThrowsWhateverTheResultHolds(GoalResult result) throws Exception {
        ModelLanguageLayer up = layer(Wire.OPENAI, Wire.OPENAI.text("It stopped."));
        Optional<String> whileUp = up.explain(result, ModelFixtures.context()).get(5, TimeUnit.SECONDS);
        assertTrue(whileUp.isPresent() && !whileUp.get().isBlank(), whileUp.toString());

        ModelLanguageLayer down = new ModelLanguageLayer(Outage.REFUSED.settingsFor(Wire.OPENAI, this));
        Optional<String> whileDown = down.explain(result, ModelFixtures.context()).get(5, TimeUnit.SECONDS);
        assertTrue(whileDown.isPresent() && !whileDown.get().isBlank(), whileDown.toString());
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
                new Failure.NoPlace("put a crafting table down", 12, "x"),
                new Failure.NoRoom(ItemKey.of("iron_ingot"), "x"),
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
        // The sentence names the goal in its own words, so the owner is not told "failed" and nothing else.
        String partial = layer.explain(endings.get(endings.size() - 1), ModelFixtures.context()).get(10, TimeUnit.SECONDS).get();
        assertTrue(partial.contains("iron ingot"), partial);
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
        for (String text : produced) assertFalse(text.contains(ModelFixtures.SECRET), text);
    }

    @Test
    void settingsNeverPrintTheKeyOrTheSecretsInTheAddress() {
        ModelSettings settings = Wire.ANTHROPIC.settings("https://api.anthropic.com");
        assertFalse(settings.toString().contains(ModelFixtures.SECRET));
        assertFalse(settings.redact("echoed: " + ModelFixtures.SECRET).contains(ModelFixtures.SECRET));

        ModelSettings addressed = new ModelSettings(ModelSettings.Provider.OPENAI, ModelFixtures.SECRET_ADDRESS, "m",
                ModelFixtures.SECRET, Duration.ofSeconds(10), 4, 512);
        String printed = addressed.toString();
        for (String secret : ModelFixtures.ADDRESS_SECRETS) assertFalse(printed.contains(secret), printed);
        // What is left says where the server is: scheme, host, port and path.
        assertTrue(printed.contains("http://models.example.test:8080/v1"), printed);
    }

    @Test
    void anAddressThatCannotBeUsedIsLoggedWithoutItsSecrets() throws Exception {
        // A space in the host makes the address unparseable; the JDK's complaint about it quotes the address.
        ModelSettings settings = new ModelSettings(ModelSettings.Provider.OPENAI,
                "http://uinfo-USER:uinfo-PASS@bad host.example.test/v1?token=QUERY-SECRET", "m", ModelFixtures.SECRET,
                Duration.ofSeconds(10), 4, 512);
        try (LogCapture logs = new LogCapture(ModelLanguageLayer.class.getPackageName())) {
            List<Intent> intents = interpret(new ModelLanguageLayer(settings), LOOSE);

            assertTrue(soleUnknown(intents).reason().length() > soleUnknown(grammarSays(LOOSE)).reason().length());
            assertFalse(logs.lines().isEmpty(), "nothing was logged, so the scan proves nothing");
            List<String> produced = new ArrayList<>(logs.lines());
            produced.add(intents.toString());
            for (String text : produced) {
                for (String secret : ModelFixtures.ADDRESS_SECRETS) assertFalse(text.contains(secret), text);
            }
        }
    }
}
