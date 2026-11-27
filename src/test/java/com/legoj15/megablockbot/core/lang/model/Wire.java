package com.legoj15.megablockbot.core.lang.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.legoj15.megablockbot.core.lang.model.FakeModelServer.Answer;
import com.legoj15.megablockbot.core.lang.model.FakeModelServer.Recorded;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The two wire formats as the fake server speaks them: the answers it scripts and the reading of what the layer
 * sent back, so one test runs against both backends. The shapes are the providers' documented ones (Anthropic's from
 * its Messages API tool-use pages), not the layer's own.
 */
enum Wire {
    OPENAI("/v1/chat/completions") {
        @Override
        ModelSettings settings(String serverUrl, Duration timeout, int maxRoundTrips) {
            return new ModelSettings(ModelSettings.Provider.OPENAI, serverUrl + "/v1", "test-model",
                    ModelFixtures.SECRET, timeout, maxRoundTrips, 512);
        }

        @Override
        Answer toolCalls(int turn, Call... calls) {
            JsonArray toolCalls = new JsonArray();
            for (int i = 0; i < calls.length; i++) {
                JsonObject function = new JsonObject();
                function.addProperty("name", calls[i].name());
                // The standard shape: arguments is a string holding JSON.
                function.addProperty("arguments", calls[i].argumentsJson());
                JsonObject call = new JsonObject();
                call.addProperty("id", "call_" + turn + "_" + i);
                call.addProperty("type", "function");
                call.add("function", function);
                toolCalls.add(call);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.add("content", com.google.gson.JsonNull.INSTANCE);
            message.add("tool_calls", toolCalls);
            return Answer.ok(completion(message, "tool_calls"));
        }

        @Override
        Answer text(String text) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", text);
            return Answer.ok(completion(message, "stop"));
        }

        private String completion(JsonObject message, String finishReason) {
            JsonObject choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.add("message", message);
            choice.addProperty("finish_reason", finishReason);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject body = new JsonObject();
            body.addProperty("id", "chatcmpl-test");
            body.addProperty("object", "chat.completion");
            body.add("choices", choices);
            return body.toString();
        }

        @Override
        String systemPrompt(JsonObject request) {
            JsonObject first = request.getAsJsonArray("messages").get(0).getAsJsonObject();
            assertEquals("system", first.get("role").getAsString());
            return first.get("content").getAsString();
        }

        @Override
        String firstUserText(JsonObject request) {
            for (JsonElement message : request.getAsJsonArray("messages")) {
                if (message.getAsJsonObject().get("role").getAsString().equals("user")) {
                    return message.getAsJsonObject().get("content").getAsString();
                }
            }
            throw new AssertionError("no user message in " + request);
        }

        @Override
        List<String> toolNames(JsonObject request) {
            List<String> names = new ArrayList<>();
            if (!request.has("tools")) return names;
            for (JsonElement tool : request.getAsJsonArray("tools")) {
                assertEquals("function", tool.getAsJsonObject().get("type").getAsString());
                names.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
            }
            return names;
        }

        @Override
        List<ToolResultSeen> toolResults(JsonObject request) {
            List<ToolResultSeen> results = new ArrayList<>();
            for (JsonElement element : request.getAsJsonArray("messages")) {
                JsonObject message = element.getAsJsonObject();
                if (message.get("role").getAsString().equals("tool")) {
                    // The protocol has no error flag on a tool message; the text carries the refusal.
                    results.add(new ToolResultSeen(message.get("tool_call_id").getAsString(),
                            message.get("content").getAsString(), false));
                }
            }
            return results;
        }

        @Override
        void assertWireFormat(Recorded request) {
            assertEquals("POST", request.method());
            assertEquals(path(), request.path());
            assertEquals("Bearer " + ModelFixtures.SECRET, request.header("authorization"));
            assertTrue(request.header("content-type").startsWith("application/json"));
            JsonObject body = request.json();
            assertEquals("test-model", body.get("model").getAsString());
            assertEquals(512, body.get("max_tokens").getAsInt());
        }
    },

    ANTHROPIC("/v1/messages") {
        @Override
        ModelSettings settings(String serverUrl, Duration timeout, int maxRoundTrips) {
            return new ModelSettings(ModelSettings.Provider.ANTHROPIC, serverUrl, "claude-sonnet-5-5",
                    ModelFixtures.SECRET, timeout, maxRoundTrips, 512);
        }

        @Override
        Answer toolCalls(int turn, Call... calls) {
            JsonArray content = new JsonArray();
            // A thinking block ahead of the calls, as a thinking model sends one: it must be echoed back unchanged.
            content.add(thinkingBlock(turn));
            for (int i = 0; i < calls.length; i++) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", "toolu_" + turn + "_" + i);
                block.addProperty("name", calls[i].name());
                // The documented shape: input is a JSON object, not a string.
                block.add("input", JsonParser.parseString(calls[i].argumentsJson()));
                content.add(block);
            }
            return Answer.ok(message(content, "tool_use"));
        }

        @Override
        Answer text(String text) {
            JsonArray content = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            content.add(block);
            return Answer.ok(message(content, "end_turn"));
        }

        private String message(JsonArray content, String stopReason) {
            JsonObject body = new JsonObject();
            body.addProperty("id", "msg_test");
            body.addProperty("type", "message");
            body.addProperty("role", "assistant");
            body.addProperty("model", "claude-sonnet-5-5");
            body.add("content", content);
            body.addProperty("stop_reason", stopReason);
            return body.toString();
        }

        @Override
        String systemPrompt(JsonObject request) {
            return request.get("system").getAsString();
        }

        @Override
        String firstUserText(JsonObject request) {
            for (JsonElement message : request.getAsJsonArray("messages")) {
                JsonObject object = message.getAsJsonObject();
                if (object.get("role").getAsString().equals("user") && object.get("content").isJsonPrimitive()) {
                    return object.get("content").getAsString();
                }
            }
            throw new AssertionError("no user message in " + request);
        }

        @Override
        List<String> toolNames(JsonObject request) {
            List<String> names = new ArrayList<>();
            if (!request.has("tools")) return names;
            for (JsonElement tool : request.getAsJsonArray("tools")) {
                names.add(tool.getAsJsonObject().get("name").getAsString());
                assertTrue(tool.getAsJsonObject().get("input_schema").getAsJsonObject().has("properties"));
            }
            return names;
        }

        @Override
        List<ToolResultSeen> toolResults(JsonObject request) {
            List<ToolResultSeen> results = new ArrayList<>();
            for (JsonElement element : request.getAsJsonArray("messages")) {
                JsonObject message = element.getAsJsonObject();
                if (!message.get("role").getAsString().equals("user") || !message.get("content").isJsonArray()) continue;
                for (JsonElement block : message.getAsJsonArray("content")) {
                    JsonObject object = block.getAsJsonObject();
                    if (!object.get("type").getAsString().equals("tool_result")) continue;
                    boolean error = object.has("is_error") && object.get("is_error").getAsBoolean();
                    results.add(new ToolResultSeen(object.get("tool_use_id").getAsString(),
                            object.get("content").getAsString(), error));
                }
            }
            return results;
        }

        @Override
        void assertWireFormat(Recorded request) {
            assertEquals("POST", request.method());
            assertEquals(path(), request.path());
            assertEquals(ModelFixtures.SECRET, request.header("x-api-key"));
            assertEquals("2026-10-21", request.header("anthropic-version"));
            assertNotNull(request.header("content-type"));
            JsonObject body = request.json();
            assertEquals("claude-sonnet-5-5", body.get("model").getAsString());
            assertEquals(512, body.get("max_tokens").getAsInt());
            // Removed on this API version: a request that sends them is a 400.
            for (String removed : List.of("temperature", "top_p", "top_k")) assertFalse(body.has(removed), removed);
            // Forced tool use is a 400 on Sonnet 5.5, so the layer must not ask for it.
            if (body.has("tool_choice")) assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").getAsString());
        }
    };


    /** The thinking block the fake Anthropic sends on turn {@code turn}. */
    private static JsonObject thinkingBlock(int turn) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "thinking");
        block.addProperty("thinking", "");
        block.addProperty("signature", "sig-turn-" + turn);
        return block;
    }

    /**
     * The assistant content an Anthropic request carried back for the turn that answered {@code turn}, found by its
     * thinking block, or null when that turn was not echoed.
     */
    static JsonArray echoedAssistantContent(JsonObject request, int turn) {
        for (JsonElement element : request.getAsJsonArray("messages")) {
            JsonObject message = element.getAsJsonObject();
            if (!message.get("role").getAsString().equals("assistant")) continue;
            JsonArray content = message.getAsJsonArray("content");
            if (content.get(0).getAsJsonObject().equals(thinkingBlock(turn))) return content;
        }
        return null;
    }

    private final String path;

    Wire(String path) {
        this.path = path;
    }

    /** The URL path the layer must post to. */
    String path() {
        return path;
    }

    ModelSettings settings(String serverUrl) {
        return settings(serverUrl, Duration.ofSeconds(10), ModelSettings.DEFAULT_MAX_ROUND_TRIPS);
    }

    abstract ModelSettings settings(String serverUrl, Duration timeout, int maxRoundTrips);

    /** A reply that calls these tools; {@code turn} makes the call ids unique across a script. */
    abstract Answer toolCalls(int turn, Call... calls);

    abstract Answer text(String text);

    abstract String systemPrompt(JsonObject request);

    abstract String firstUserText(JsonObject request);

    abstract List<String> toolNames(JsonObject request);

    abstract List<ToolResultSeen> toolResults(JsonObject request);

    /** Asserts the request is shaped and authenticated as the provider documents. */
    abstract void assertWireFormat(Recorded request);

    record Call(String name, String argumentsJson) {
        static Call of(String name, String argumentsJson) {
            return new Call(name, argumentsJson);
        }
    }

    /** One tool answer the layer sent back: which call, what it said, and whether it was flagged as an error. */
    record ToolResultSeen(String callId, String content, boolean flaggedError) {}
}
