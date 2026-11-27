package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * One round trip to a chat model with tools (design 3.3): "send these messages and tool definitions, get back
 * text and tool calls". The two implementations speak {@link OpenAiChatBackend OpenAI's chat completions} and
 * {@link AnthropicChatBackend Anthropic's Messages API}; {@link ModelLanguageLayer} sees only this contract, so
 * the wire format is the backend's business.
 *
 * <p>{@link #send} never throws and never blocks: the future completes on the HTTP client's threads, and a
 * failure (timeout, refused connection, a non-2xx answer, a body that is not what the protocol says) completes
 * it exceptionally with a {@link ChatException} whose message is already scrubbed of the API key.
 */
public interface ChatBackend {

    CompletableFuture<Reply> send(Request request);

    /** The backend for these settings' provider; {@link ModelSettings.Provider#NONE} has none. */
    static ChatBackend of(ModelSettings settings) {
        switch (settings.provider()) {
            case OPENAI: return new OpenAiChatBackend(settings);
            case ANTHROPIC: return new AnthropicChatBackend(settings);
            default: throw new IllegalArgumentException("provider none has no chat backend");
        }
    }

    /** What to send: a system prompt, the conversation so far, and the tools the model may call (maybe none). */
    record Request(String system, List<Message> messages, List<ToolSpec> tools) {
        public Request {
            Objects.requireNonNull(system, "system");
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    /** One turn of the conversation. Backends turn these into their own wire shapes. */
    sealed interface Message {

        /** The owner's words (or, for an explanation, the facts to put into words). */
        record User(String text) implements Message {}

        /**
         * The model's own earlier turn, sent back unchanged next to the tool results that answer it.
         *
         * @param providerBlocks what the backend got on the wire for this turn, kept so it can echo it verbatim
         *                       (Anthropic wants thinking blocks returned exactly as sent); null when the turn was
         *                       not built from a reply, and the backend then rebuilds it from the other two fields
         */
        record Assistant(String text, List<ToolCall> toolCalls, JsonElement providerBlocks) implements Message {
            public Assistant {
                text = text == null ? "" : text;
                toolCalls = List.copyOf(toolCalls);
            }
        }

        /** The answer to one tool call; {@code error} tells the model the call was refused rather than done. */
        record ToolResult(String callId, String content, boolean error) implements Message {}
    }

    /** A tool the model may call: {@code parameters} is a JSON Schema object. */
    record ToolSpec(String name, String description, JsonObject parameters) {}

    /**
     * A tool call as the model made it. {@code argumentsJson} is the text of the arguments object, unparsed:
     * whatever the model wrote is checked in one place, the layer, never trusted on the way through.
     */
    record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * The model's answer: text, tool calls, or both. {@code finishReason} is the protocol's own word for why it
     * stopped ({@code stop}, {@code tool_calls}, {@code end_turn}, {@code max_tokens}...), for logs only.
     */
    record Reply(String text, List<ToolCall> toolCalls, JsonElement providerBlocks, String finishReason) {
        public Reply {
            text = text == null ? "" : text;
            toolCalls = List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
        }

        /** This reply as the assistant turn that goes back into the conversation. */
        public Message.Assistant asMessage() {
            return new Message.Assistant(text, toolCalls, providerBlocks);
        }
    }

    /** A round trip that did not produce a usable answer. The message never contains the API key. */
    final class ChatException extends RuntimeException {
        private final int status;

        /** @param status the HTTP status when one arrived, else 0 */
        public ChatException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public ChatException(String message) {
            this(message, 0, null);
        }

        public int status() {
            return status;
        }
    }
}
