package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The native Anthropic Messages API: {@code POST {base}/v1/messages} with {@code tools}, the {@code x-api-key} and
 * {@code anthropic-version} headers. Written against the API's tool-use documentation (platform.claude.com, "Define
 * tools" and "Handle tool calls"), read on 2026-11-26, and checked against a fake server only: no key was available
 * to try the real endpoint.
 *
 * <ul>
 *   <li>Request: {@code model}, {@code max_tokens}, {@code system} (a string), {@code messages}, and
 *       {@code tools} as {@code {name, description, input_schema}}. Nothing else is sent: {@code temperature},
 *       {@code top_p} and {@code top_k} are rejected on API version 2026-10-21, forced {@code tool_choice} is
 *       rejected on Sonnet 5.5, and thinking and effort are left at the model's defaults so the same request works
 *       on older models too.</li>
 *   <li>Response: {@code content} is a list of blocks, {@code text} and {@code tool_use} ({@code id}, {@code name},
 *       {@code input} as an object), with {@code stop_reason} {@code tool_use} when it wants a result.</li>
 *   <li>Tool results go back in the next request: the assistant's {@code content} unchanged, then one user message
 *       holding a {@code tool_result} block ({@code tool_use_id}, {@code content}, {@code is_error} on a refusal)
 *       for every call, all in that single message. The assistant content is the response's own block list
 *       verbatim, so a thinking block comes back exactly as it was sent, as the thinking models require.</li>
 * </ul>
 */
final class AnthropicChatBackend implements ChatBackend {

    /**
     * The API version sent in {@code anthropic-version}. 2026-10-21 is the current one; it removed the sampling
     * parameters (never sent here) and is accepted by every current model.
     */
    static final String API_VERSION = "2026-10-21";

    private final ModelSettings settings;
    private final JsonPost http;
    private final String endpoint;

    AnthropicChatBackend(ModelSettings settings) {
        this.settings = settings;
        this.http = new JsonPost(settings);
        // A base given as ".../v1" (the way the OpenAI side is) must not become ".../v1/v1/messages".
        String base = JsonPost.join(settings.baseUrl(), "");
        this.endpoint = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    @Override
    public CompletableFuture<Reply> send(Request request) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (!settings.apiKey().isEmpty()) headers.put("x-api-key", settings.apiKey());
            headers.put("anthropic-version", API_VERSION);
            return http.post(endpoint, headers, body(request)).thenApply(this::reply);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new ChatException("could not build the request: " + http.describe(e)));
        }
    }

    // ── request ───────────────────────────────────────────────────────────────────────────────────

    private JsonObject body(Request request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_tokens", settings.maxOutputTokens());
        if (!request.system().isBlank()) body.addProperty("system", request.system());

        JsonArray messages = new JsonArray();
        // Every result of one assistant turn goes in one user message: consecutive results are gathered here.
        JsonArray results = null;
        for (Message message : request.messages()) {
            if (message instanceof Message.ToolResult result) {
                if (results == null) results = new JsonArray();
                results.add(toolResult(result));
                continue;
            }
            if (results != null) {
                messages.add(turn("user", results));
                results = null;
            }
            switch (message) {
                case Message.User user -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("role", "user");
                    object.addProperty("content", user.text());
                    messages.add(object);
                }
                case Message.Assistant assistant -> messages.add(turn("assistant", assistantBlocks(assistant)));
                case Message.ToolResult result -> throw new IllegalStateException("handled above: " + result);
            }
        }
        if (results != null) messages.add(turn("user", results));
        body.add("messages", messages);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec tool : request.tools()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", tool.name());
                entry.addProperty("description", tool.description());
                entry.add("input_schema", tool.parameters());
                tools.add(entry);
            }
            body.add("tools", tools);
        }
        return body;
    }

    private static JsonObject turn(String role, JsonArray content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        return message;
    }

    private static JsonObject toolResult(Message.ToolResult result) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", result.callId());
        block.addProperty("content", result.content().isBlank() ? "(no output)" : result.content());
        if (result.error()) block.addProperty("is_error", true);
        return block;
    }

    /** The response's own blocks when the turn came from one, else blocks rebuilt from its text and calls. */
    private static JsonArray assistantBlocks(Message.Assistant turn) {
        if (turn.providerBlocks() != null && turn.providerBlocks().isJsonArray()) {
            return turn.providerBlocks().getAsJsonArray();
        }
        JsonArray blocks = new JsonArray();
        if (!turn.text().isBlank()) {
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", turn.text());
            blocks.add(text);
        }
        for (ToolCall call : turn.toolCalls()) {
            JsonObject use = new JsonObject();
            use.addProperty("type", "tool_use");
            use.addProperty("id", call.id());
            use.addProperty("name", call.name());
            use.add("input", inputObject(call.argumentsJson()));
            blocks.add(use);
        }
        return blocks;
    }

    private static JsonElement inputObject(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) return parsed;
        } catch (JsonParseException notJson) {
            // falls through: a call the model garbled is echoed as an empty input, the result already says so
        }
        return new JsonObject();
    }

    // ── reply ─────────────────────────────────────────────────────────────────────────────────────

    private Reply reply(JsonObject root) {
        try {
            if (root.has("type") && "error".equals(root.get("type").getAsString())) {
                throw new ChatException("the model server answered with an error: " + cut(settings.redact(root.toString())));
            }
            JsonElement content = root.get("content");
            if (content == null || !content.isJsonArray()) {
                throw new ChatException("the model server's answer had no content blocks");
            }
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonElement element : content.getAsJsonArray()) {
                JsonObject block = element.getAsJsonObject();
                String type = block.get("type").getAsString();
                if (type.equals("text")) {
                    text.append(block.get("text").getAsString());
                } else if (type.equals("tool_use")) {
                    JsonElement input = block.get("input");
                    calls.add(new ToolCall(block.get("id").getAsString(), block.get("name").getAsString(),
                            input == null || input.isJsonNull() ? "{}" : input.toString()));
                }
                // thinking and other blocks carry nothing the layer uses; they ride along in providerBlocks.
            }
            String stop = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                    ? root.get("stop_reason").getAsString() : "";
            return new Reply(text.toString(), calls, content, stop);
        } catch (ChatException e) {
            throw e;
        } catch (RuntimeException shapeMismatch) {
            throw new ChatException("the model server's answer was not shaped like a Messages response: "
                    + http.describe(shapeMismatch));
        }
    }

    private static String cut(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
