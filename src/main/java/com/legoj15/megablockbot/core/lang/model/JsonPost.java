package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One JSON POST with a hard deadline, shared by both backends. Asynchronous throughout: the future completes on
 * the HTTP client's own threads, never the caller's, and it never completes with anything but a JSON object or a
 * {@link ChatBackend.ChatException} whose message is already scrubbed of the API key.
 */
final class JsonPost {

    /** Longest slice of a server's error body that goes into an exception message (and so into a log line). */
    private static final int MAX_BODY_IN_MESSAGE = 300;

    private final ModelSettings settings;
    // HTTP/1.1 only: the JDK client otherwise offers an h2c upgrade on plain http, which local servers
    // (llama.cpp, Ollama, LM Studio) do not all take kindly to, and nothing here needs multiplexing.
    private final HttpClient client;

    JsonPost(ModelSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout())
                .build();
    }

    /** The backend's endpoint: {@code base} without a trailing slash, then {@code path}. */
    static String join(String base, String path) {
        String trimmed = base;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + path;
    }

    CompletableFuture<JsonObject> post(String url, Map<String, String> headers, JsonObject body) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            request = builder.build();
        } catch (RuntimeException badRequest) {
            return CompletableFuture.failedFuture(
                    new ChatBackend.ChatException("the model address is not usable: " + describe(badRequest)));
        }

        CompletableFuture<HttpResponse<String>> http = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // The request timeout stops waiting for headers; this one also covers a body that stalls after them.
        CompletableFuture<JsonObject> parsed = http.thenApply(this::parse)
                .orTimeout(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);

        CompletableFuture<JsonObject> out = new CompletableFuture<>();
        parsed.whenComplete((object, error) -> {
            if (error == null) {
                out.complete(object);
            } else {
                http.cancel(true);
                out.completeExceptionally(asChatException(error));
            }
        });
        return out;
    }

    private JsonObject parse(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status > 299) {
            throw new ChatBackend.ChatException("the model server answered HTTP " + status + ": " + shorten(body), status, null);
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                throw new ChatBackend.ChatException("the model server's answer was not a JSON object: " + shorten(body));
            }
            return element.getAsJsonObject();
        } catch (JsonParseException notJson) {
            throw new ChatBackend.ChatException("the model server's answer was not JSON: " + shorten(body));
        }
    }

    /** Any failure of the exchange as the one exception type the layer understands, scrubbed of the key. */
    private ChatBackend.ChatException asChatException(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ChatBackend.ChatException) return (ChatBackend.ChatException) cause;
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new ChatBackend.ChatException("the model did not answer within " + settings.timeout().toSeconds() + " s");
        }
        if (cause instanceof IOException) {
            return new ChatBackend.ChatException("could not talk to the model server: " + describe(cause), 0, null);
        }
        return new ChatBackend.ChatException("the model request failed: " + describe(cause), 0, null);
    }

    private String shorten(String body) {
        String clean = settings.redact(body.strip().replaceAll("\\s+", " "));
        return clean.length() <= MAX_BODY_IN_MESSAGE ? clean : clean.substring(0, MAX_BODY_IN_MESSAGE) + "...";
    }

    /** The exception's class and message, with the key scrubbed out of the message. */
    String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : " " + settings.redact(message));
    }
}
