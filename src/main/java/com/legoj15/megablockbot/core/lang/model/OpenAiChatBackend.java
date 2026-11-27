package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The OpenAI-compatible chat completions wire format: {@code POST {base}/chat/completions} with {@code tools}, which
 * is what llama.cpp's server, Ollama, LM Studio and hosted gateways speak. Tool calls are read from the standard
 * {@code tool_calls} field; a model that answers in plain text instead simply gives a reply with text and no calls,
 * and the layer takes that as something to say.
 *
 * <p>The reading is deliberately forgiving where local servers differ: arguments as a JSON string (the standard) or
 * as an object, a missing call id, and content as a string or as a list of text parts.
 */
final class OpenAiChatBackend implements ChatBackend {

    private final ModelSettings settings;
    private final JsonPost http;
    private final String endpoint;

    OpenAiChatBackend(ModelSettings settings) {
        this.settings = settings;
        this.http = new JsonPost(settings);
        this.endpoint = JsonPost.join(settings.baseUrl(), "/chat/completions");
    }

    @Override
    public CompletableFuture<Reply> send(Request request) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            // A local server needs no key; sending an empty bearer token to one that checks would only confuse it.
            if (!settings.apiKey().isEmpty()) headers.put("Authorization", "Bearer " + settings.apiKey());
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
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.system()));
        for (Message message : request.messages()) {
            messages.add(switch (message) {
                case Message.User user -> message("user", user.text());
                case Message.Assistant turn -> assistant(turn);
                case Message.ToolResult result -> {
                    JsonObject object = message("tool", result.content());
                    object.addProperty("tool_call_id", result.callId());
                    yield object;
                }
            });
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec tool : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", tool.parameters());
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "function");
                entry.add("function", function);
                tools.add(entry);
            }
            body.add("tools", tools);
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject assistant(Message.Assistant turn) {
        JsonObject message = message("assistant", turn.text());
        if (turn.providerBlocks() != null && turn.providerBlocks().isJsonObject()) {
            JsonElement reasoning = turn.providerBlocks().getAsJsonObject().get("reasoning_content");
            if (reasoning != null) message.add("reasoning_content", reasoning);
        }
        if (!turn.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : turn.toolCalls()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                function.addProperty("arguments", call.argumentsJson());
                JsonObject entry = new JsonObject();
                entry.addProperty("id", call.id());
                entry.addProperty("type", "function");
                entry.add("function", function);
                calls.add(entry);
            }
            message.add("tool_calls", calls);
        }
        return message;
    }

    // ── reply ─────────────────────────────────────────────────────────────────────────────────────

    private Reply reply(JsonObject root) {
        try {
            JsonElement choices = root.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
                // Some servers answer 200 with an error object; say so instead of "no choices".
                String why = root.has("error") ? settings.redact(root.get("error").toString()) : "it had no choices";
                throw new ChatException("the model server's answer was unusable: " + cut(why));
            }
            JsonObject choice = choices.getAsJsonArray().get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String finish = string(choice.get("finish_reason"));
            return new Reply(contentText(message.get("content")), toolCalls(message.get("tool_calls")),
                    reasoning(message), finish);
        } catch (ChatException e) {
            throw e;
        } catch (RuntimeException shapeMismatch) {
            throw new ChatException("the model server's answer was not shaped like a chat completion: "
                    + http.describe(shapeMismatch));
        }
    }

    /**
     * What a reasoning model sent beside its answer, kept so it can go back with the tool results: some hosted
     * reasoning APIs refuse the next request of a tool exchange without it. Only what the server itself sent is
     * ever sent back, so a server that has no such field never gets one. Null when there was none.
     */
    private static JsonElement reasoning(JsonObject message) {
        JsonElement reasoning = message.get("reasoning_content");
        if (reasoning == null || !reasoning.isJsonPrimitive() || reasoning.getAsString().isEmpty()) return null;
        JsonObject kept = new JsonObject();
        kept.add("reasoning_content", reasoning);
        return kept;
    }

    /** Content as the standard string, or a list of {@code {type: text, text}} parts, or nothing (null). */
    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        StringBuilder text = new StringBuilder();
        if (content.isJsonArray()) {
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    text.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
        }
        return text.toString();
    }

    private static List<ToolCall> toolCalls(JsonElement element) {
        List<ToolCall> calls = new ArrayList<>();
        if (element == null || element.isJsonNull()) return calls;
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonObject call = array.get(i).getAsJsonObject();
            JsonObject function = call.getAsJsonObject("function");
            // A server that omits the id still needs the result tied to the call, so make one up.
            String id = call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "call_" + i;
            JsonElement arguments = function.get("arguments");
            String json = arguments == null || arguments.isJsonNull() ? "{}"
                    : arguments.isJsonPrimitive() ? arguments.getAsString() : arguments.toString();
            calls.add(new ToolCall(id, function.get("name").getAsString(), json));
        }
        return calls;
    }

    private static String string(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String cut(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
