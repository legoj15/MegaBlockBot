package com.legoj15.megablockbot.core.lang.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config file protects two things: a missing or broken file never turns a model on or stops the bot, and the
 * key stays out of every message the loader produces. Wording of the messages is not asserted, only that the key is
 * not in them.
 */
class ModelSettingsFileTest {

    private static final String KEY = ModelFixtures.SECRET;
    private static final UnaryOperator<String> NO_ENV = name -> null;

    private static UnaryOperator<String> env(String name, String value) {
        Map<String, String> variables = Map.of(name, value);
        return variables::get;
    }

    @Test
    void theDefaultTextThatIsWrittenIsNoModel() {
        assertEquals(ModelSettings.none(), ModelSettingsFile.parse(ModelSettingsFile.DEFAULT_TEXT, NO_ENV));
    }

    @Test
    void anOpenAiServerIsReadWithItsLimits() {
        ModelSettings settings = ModelSettingsFile.parse("""
                // llama.cpp on the LAN
                { "provider": "openai", "baseUrl": "http://192.168.1.5:8080/v1", "model": "qwen3",
                  "timeoutSeconds": 90, "maxRoundTrips": 6, "maxOutputTokens": 8192 }
                """, NO_ENV);

        assertEquals(new ModelSettings(ModelSettings.Provider.OPENAI, "http://192.168.1.5:8080/v1", "qwen3", "",
                Duration.ofSeconds(90), 6, 8192), settings);
    }

    @Test
    void anthropicDefaultsToItsOwnServerAndTheDocumentedModel() {
        ModelSettings settings = ModelSettingsFile.parse(
                "{ \"provider\": \"anthropic\", \"apiKeyEnv\": \"MBB_KEY\" }", env("MBB_KEY", KEY));

        assertEquals(ModelSettings.Provider.ANTHROPIC, settings.provider());
        assertEquals("https://api.anthropic.com", settings.baseUrl());
        assertEquals("claude-sonnet-5-5", settings.model());
        assertEquals(KEY, settings.apiKey());
        assertEquals(ModelSettings.DEFAULT_MAX_ROUND_TRIPS, settings.maxRoundTrips());
    }

    @Test
    void theEnvironmentVariableWinsOverTheInlineKeyWhenItIsSet() {
        String text = "{ \"provider\": \"openai\", \"baseUrl\": \"http://h/v1\", \"model\": \"m\","
                + " \"apiKeyEnv\": \"MBB_KEY\", \"apiKey\": \"inline-key\" }";

        assertEquals(KEY, ModelSettingsFile.parse(text, env("MBB_KEY", KEY)).apiKey());
        assertEquals("inline-key", ModelSettingsFile.parse(text, NO_ENV).apiKey());
    }

    @Test
    void aKeyThatWasNamedButIsNowhereIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ModelSettingsFile.parse(
                "{ \"provider\": \"openai\", \"baseUrl\": \"http://h/v1\", \"model\": \"m\", \"apiKeyEnv\": \"MBB_KEY\" }",
                NO_ENV));
    }

    @Test
    void settingsThatCannotWorkAreRefusedWithoutEchoingTheKey() {
        String withKey = "\"apiKey\": \"" + KEY + "\"";
        for (String text : new String[] {
                "{ \"provider\": \"anthropic\" }",                                              // no key
                "{ \"provider\": \"openai\", \"model\": \"m\", " + withKey + " }",              // no base URL
                "{ \"provider\": \"openai\", \"baseUrl\": \"http://h/v1\", " + withKey + " }",  // no model
                "{ \"provider\": \"gemini\", " + withKey + " }",
                "{ \"provider\": \"anthropic\", " + withKey + ", \"timeoutSeconds\": 0 }",
                "{ \"provider\": \"anthropic\", " + withKey + ", \"maxRoundTrips\": 0 }",
                "{ \"provider\": \"anthropic\", " + withKey + ", \"maxOutputTokens\": 1.5 }",
                "{ \"provider\": \"anthropic\", " + withKey + ", \"timout\": 5 }",              // a typo
                "{ \"provider\": \"anthropic\", \"apiKeyEnv\": \"" + KEY + "\" }",              // the key where a NAME goes
                "{ \"provider\": \"" + KEY + "\" }",                                            // the key as the provider
                "{ \"provider\": \"anthropic\", \"" + KEY + "\": \"\" }",                       // the key as a setting's name
                "{ \"" + KEY + "\": 1 2 }",                                                    // the same in a file that does not parse
                "{ \"provider\": \"anthropic\", " + withKey + ",",                              // cut off
                "[1, 2]"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ModelSettingsFile.parse(text, NO_ENV), text);
            assertFalse(String.valueOf(refused.getMessage()).contains(KEY), refused.getMessage());
        }
    }

    @Test
    void aMissingFileIsCreatedAsNoModelAndReadBackTheSame(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("megablockbot").resolve("language.json");

        assertEquals(ModelSettings.none(), ModelSettingsFile.loadOrCreate(file, NO_ENV));

        assertEquals(ModelSettingsFile.DEFAULT_TEXT, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(ModelSettings.none(), ModelSettingsFile.loadOrCreate(file, NO_ENV));
    }

    @Test
    void theLogLineNamesTheServerButNotItsSecrets(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("language.json");
        Files.writeString(file, "{ \"provider\": \"openai\", \"baseUrl\": \"" + ModelFixtures.SECRET_ADDRESS
                + "\", \"model\": \"m\", \"apiKey\": \"" + KEY + "\" }");

        try (LogCapture logs = new LogCapture(ModelSettingsFile.class.getName())) {
            assertEquals(ModelSettings.Provider.OPENAI, ModelSettingsFile.loadOrCreate(file, NO_ENV).provider());

            String logged = String.join("\n", logs.lines());
            assertTrue(logged.contains("models.example.test"), "the line should say which server: " + logged);
            for (String secret : ModelFixtures.ADDRESS_SECRETS) assertFalse(logged.contains(secret), logged);
            assertFalse(logged.contains(KEY), logged);
        }

        // The key pasted one field too high, as the model name: the file loads, and the line must still not hold it.
        Files.writeString(file, "{ \"provider\": \"openai\", \"baseUrl\": \"http://models.example.test/v1\", \"model\": \""
                + KEY + "\" }");
        try (LogCapture logs = new LogCapture(ModelSettingsFile.class.getName())) {
            assertEquals(ModelSettings.Provider.OPENAI, ModelSettingsFile.loadOrCreate(file, NO_ENV).provider());
            assertFalse(String.join("\n", logs.lines()).contains(KEY));
        }
    }

    @Test
    void aBrokenFileMeansNoModelAndNeverThrows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("language.json");
        Files.writeString(file, "{ \"provider\": \"anthropic\", \"apiKey\": \"" + KEY + "\", \"maxRoundTrips\": -2 }");

        ModelSettings settings;
        try (LogCapture logs = new LogCapture(ModelSettingsFile.class.getName())) {
            settings = ModelSettingsFile.loadOrCreate(file, NO_ENV);
            // Not vacuous: the problem was logged, and the key was not.
            assertFalse(logs.lines().isEmpty());
            logs.lines().forEach(line -> assertFalse(line.contains(KEY), line));
        }

        assertEquals(ModelSettings.none(), settings);
        // An unreadable path (a directory where the file should be) is the same.
        assertEquals(ModelSettings.none(), ModelSettingsFile.loadOrCreate(dir, NO_ENV));
        assertTrue(Files.readString(file).contains(KEY), "a broken file is left alone, not overwritten");
    }
}
