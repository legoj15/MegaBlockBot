package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model server on a free loopback port that plays back scripted answers, one per request, the last one repeating
 * when the script runs out. It records every request so a test can read what the layer sent. Plain
 * {@code com.sun.net.httpserver}: the layer's real HTTP client talks to it, so the wire format is under test too.
 */
final class FakeModelServer implements AutoCloseable {

    /**
     * One scripted answer: an HTTP status, a body, how long to sit on the request first, and how long to leave the
     * response open after the body has been sent (0 for an answer that ends with its body).
     */
    record Answer(int status, String body, long delayMillis, long stallMillis) {
        static Answer ok(String json) {
            return new Answer(200, json, 0, 0);
        }

        static Answer status(int status, String body) {
            return new Answer(status, body, 0, 0);
        }

        Answer after(long millis) {
            return new Answer(status, body, millis, stallMillis);
        }

        /**
         * This answer's body sent as a chunked response that is then left open for {@code millis}: a client that
         * reads to the end of the body waits that long, one that stops at a size limit does not.
         */
        Answer thenStalls(long millis) {
            return new Answer(status, body, delayMillis, millis);
        }
    }

    /** One request as it arrived. */
    record Recorded(String method, String path, Map<String, String> headers, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        /** A header by name, case-insensitive; null when absent. */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Answer> script = new ArrayList<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger served = new AtomicInteger();

    FakeModelServer(Answer... answers) {
        script.addAll(List.of(answers));
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("no loopback port for the fake model server", e);
        }
        // Daemon threads: a request parked on a long delay must not hold the test JVM open.
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-model-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Replaces the script; requests already served stay counted. */
    void script(Answer... answers) {
        synchronized (script) {
            script.clear();
            script.addAll(List.of(answers));
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                new String(body, StandardCharsets.UTF_8)));

        Answer answer;
        synchronized (script) {
            if (script.isEmpty()) throw new IllegalStateException("the fake model server has no script");
            answer = script.get(Math.min(served.getAndIncrement(), script.size() - 1));
        }
        try {
            if (answer.delayMillis() > 0) Thread.sleep(answer.delayMillis());
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // A length of 0 asks for chunked encoding, where the body ends only when the response is closed.
            exchange.sendResponseHeaders(answer.status(), answer.stallMillis() > 0 ? 0 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                if (answer.stallMillis() > 0) {
                    out.flush();
                    Thread.sleep(answer.stallMillis());
                }
            }
        } catch (IOException | InterruptedException clientGaveUp) {
            // A client that timed out has already hung up; nothing to answer.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
