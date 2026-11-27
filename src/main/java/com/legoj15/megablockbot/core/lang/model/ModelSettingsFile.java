package com.legoj15.megablockbot.core.lang.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The language layer's config file (design 3.3: keys "live in a server-side config file that is never synced to
 * clients or echoed in chat"): reading it into {@link ModelSettings}, and writing a commented default when it is
 * missing. The text parses leniently, so {@code //} comments are allowed. It is Minecraft-free so the plain JUnit
 * run covers it on every node; {@code game.lang.ModelLanguageConfig} only says where the file lives.
 *
 * <p>A config that cannot be used never stops the bot and never turns the model on by accident: any problem is
 * logged (without the key) and the settings come back as provider {@code none}, the fixed grammar.
 */
public final class ModelSettingsFile {
    private ModelSettingsFile() {}

    private static final System.Logger LOG = System.getLogger(ModelSettingsFile.class.getName());

    /** The keys the file may hold; any other is a typo that would otherwise be silently ignored. */
    private static final List<String> KEYS = List.of("provider", "baseUrl", "model", "apiKeyEnv", "apiKey",
            "timeoutSeconds", "maxRoundTrips", "maxOutputTokens");

    /** What is written when the file is missing: provider none, with the options explained in comments. */
    public static final String DEFAULT_TEXT = """
            // MegaBlockBot language layer. Lines that start with // are comments.
            // The default is no language model: only the fixed commands work (say "help" to the bot).
            //
            // provider is "none", "openai" or "anthropic".
            //   openai    - any OpenAI-compatible server: llama.cpp (llama-server), Ollama, LM Studio, or a hosted
            //               gateway. baseUrl is the server's /v1 address, for example
            //                 llama.cpp  http://localhost:8080/v1
            //                 Ollama     http://localhost:11434/v1
            //                 LM Studio  http://localhost:1234/v1
            //               model is the model name that server knows (llama.cpp accepts any name). Only a hosted
            //               server needs a key. The model must support tool calls.
            //   anthropic - the Anthropic API. baseUrl and model may stay empty: they default to
            //               https://api.anthropic.com and claude-sonnet-5-5 (claude-haiku-5-5 is the faster choice).
            //               A key is required.
            //
            // The key: put the NAME of an environment variable in apiKeyEnv (best), or the key itself in apiKey.
            // If both are set and the variable exists, the variable wins. This file stays on the server and the
            // bot never prints the key.
            //
            // timeoutSeconds is how long one request may take before the bot gives up on the model and falls back
            // to the fixed commands. maxRoundTrips is how many requests one chat message may cost.
            // maxOutputTokens caps one reply; a model that thinks first spends part of it on thinking.
            {
              "provider": "none",
              "baseUrl": "",
              "model": "",
              "apiKeyEnv": "",
              "apiKey": "",
              "timeoutSeconds": 30,
              "maxRoundTrips": 4,
              "maxOutputTokens": 4096
            }
            """;

    /**
     * Reads the settings from {@code text}.
     *
     * @param env looks up an environment variable (null when unset); {@code System::getenv} outside tests
     * @throws IllegalArgumentException with a sentence naming what is wrong; never containing the key
     */
    public static ModelSettings parse(String text, UnaryOperator<String> env) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("the file must hold one JSON object");
            root = parsed.getAsJsonObject();
        } catch (JsonParseException notJson) {
            throw new IllegalArgumentException("the file is not valid JSON (" + firstLine(notJson.getMessage()) + ")");
        }
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown setting '" + key + "'; the settings are "
                        + String.join(", ", KEYS));
            }
        }
        ModelSettings.Provider provider = ModelSettings.Provider.parse(string(root, "provider", "none"));
        if (provider == ModelSettings.Provider.NONE) return ModelSettings.none();

        boolean anthropic = provider == ModelSettings.Provider.ANTHROPIC;
        String baseUrl = string(root, "baseUrl", "");
        if (baseUrl.isBlank() && anthropic) baseUrl = ModelSettings.DEFAULT_ANTHROPIC_BASE_URL;
        String model = string(root, "model", "");
        if (model.isBlank() && anthropic) model = ModelSettings.DEFAULT_ANTHROPIC_MODEL;

        String key = apiKey(root, env);
        if (anthropic && key.isEmpty()) {
            throw new IllegalArgumentException("provider anthropic needs a key: set apiKeyEnv or apiKey");
        }
        int timeout = number(root, "timeoutSeconds", (int) ModelSettings.DEFAULT_TIMEOUT.toSeconds(), 1, 600);
        int trips = number(root, "maxRoundTrips", ModelSettings.DEFAULT_MAX_ROUND_TRIPS, 1, 20);
        int tokens = number(root, "maxOutputTokens", ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS, 16, 200_000);
        // ModelSettings refuses a blank base URL or model for a provider that needs them, with its own sentence.
        return new ModelSettings(provider, baseUrl, model, key, Duration.ofSeconds(timeout), trips, tokens);
    }

    /**
     * Reads {@code file}; when it does not exist, writes {@link #DEFAULT_TEXT} there (creating its directory) and
     * returns provider {@code none}. Never throws: an unreadable or invalid file is logged and means {@code none}.
     */
    public static ModelSettings loadOrCreate(Path file, UnaryOperator<String> env) {
        try {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException missing) {
                create(file);
                return ModelSettings.none();
            }
            ModelSettings settings = parse(text, env);
            LOG.log(System.Logger.Level.INFO, "language layer: " + settings);
            return settings;
        } catch (IOException | RuntimeException e) {
            // The message names the problem and the file, never a value from it.
            LOG.log(System.Logger.Level.WARNING, "language layer config " + file
                    + " is not usable, so the bot understands only the fixed commands: " + e.getMessage());
            return ModelSettings.none();
        }
    }

    private static void create(Path file) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        if (directory != null) Files.createDirectories(directory);
        try {
            Files.writeString(file, DEFAULT_TEXT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            LOG.log(System.Logger.Level.INFO, "wrote the default language layer config " + file + " (no model)");
        } catch (FileAlreadyExistsException someoneElseWroteIt) {
            // Written by another thread or process since the read; theirs stands.
        }
    }

    // ── fields ────────────────────────────────────────────────────────────────────────────────────

    /** The environment variable named by {@code apiKeyEnv} when it is set, else the inline {@code apiKey}. */
    private static String apiKey(JsonObject root, UnaryOperator<String> env) {
        String inline = string(root, "apiKey", "").strip();
        String variable = string(root, "apiKeyEnv", "").strip();
        if (variable.isEmpty()) return inline;
        String fromEnv = env.apply(variable);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.strip();
        if (!inline.isEmpty()) return inline;
        throw new IllegalArgumentException("apiKeyEnv names the environment variable " + variable
                + ", which is not set, and there is no apiKey");
    }

    private static String string(JsonObject root, String field, String fallback) {
        JsonElement element = root.get(field);
        if (element == null || element.isJsonNull()) return fallback;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("'" + field + "' must be text");
        }
        return element.getAsString().strip();
    }

    private static int number(JsonObject root, String field, int fallback, int min, int max) {
        JsonElement element = root.get(field);
        if (element == null || element.isJsonNull()) return fallback;
        int value;
        try {
            value = element.getAsJsonPrimitive().getAsBigDecimal().intValueExact();
        } catch (RuntimeException notWhole) {
            throw new IllegalArgumentException("'" + field + "' must be a whole number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("'" + field + "' must be from " + min + " to " + max + ", not " + value);
        }
        return value;
    }

    private static String firstLine(String message) {
        if (message == null) return "unreadable";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
