package com.legoj15.megablockbot.core.lang.model;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * How to reach the language model (design 3.3): which wire format, where, which model, and the limits that keep a
 * slow or chatty model from costing the owner anything. Plain data; {@link ModelSettingsFile} reads it from the
 * server's config directory.
 *
 * <p>The API key lives here and nowhere else in the layer's output: {@link #toString()} never prints it, and
 * {@link #redact} scrubs it out of any text (an error body a gateway echoed back) before it is logged.
 *
 * @param provider        the wire format, or {@link Provider#NONE} for no model at all
 * @param baseUrl         the server root: {@code http://host:port/v1} for {@code openai} (the layer appends
 *                        {@code /chat/completions}), {@code https://api.anthropic.com} for {@code anthropic} (it appends
 *                        {@code /v1/messages})
 * @param model           the model name sent with every request
 * @param apiKey          the secret, empty for a server that needs none
 * @param timeout         how long one request may take before the layer gives up on the model
 * @param maxRoundTrips   how many requests one owner message may cost: each tool answer sent back is another
 * @param maxOutputTokens the cap on one reply; a model that thinks first spends part of it on thinking, so it is not small
 */
public record ModelSettings(Provider provider, String baseUrl, String model, String apiKey, Duration timeout,
                            int maxRoundTrips, int maxOutputTokens) {

    /** The wire format of the model's server. */
    public enum Provider {
        /** No model: the fixed grammar only (the default). */
        NONE,
        /** {@code POST {base}/chat/completions} with {@code tools}: llama.cpp, Ollama, LM Studio, hosted gateways. */
        OPENAI,
        /** {@code POST {base}/v1/messages} with {@code tools}: the native Anthropic Messages API. */
        ANTHROPIC;

        /** The name used in the config file; the inverse of {@link #parse}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Provider parse(String text) {
            for (Provider provider : values()) {
                if (provider.id().equals(text.strip().toLowerCase(Locale.ROOT))) return provider;
            }
            throw new IllegalArgumentException("provider must be none, openai or anthropic, not '" + text + "'");
        }
    }

    public static final int DEFAULT_MAX_ROUND_TRIPS = 4;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    /**
     * The documented default model of the {@code anthropic} provider. {@code claude-haiku-5-5} is the faster and
     * cheaper choice when a reply takes too long; the owner's commands are short enough for either.
     */
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    public ModelSettings {
        Objects.requireNonNull(provider, "provider");
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        model = model == null ? "" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxRoundTrips < 1) throw new IllegalArgumentException("maxRoundTrips must be at least 1");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be at least 1");
        if (provider != Provider.NONE && baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required for provider " + provider.id());
        }
        if (provider != Provider.NONE && model.isEmpty()) {
            throw new IllegalArgumentException("model is required for provider " + provider.id());
        }
    }

    /** No model: what the config file's default says. */
    public static ModelSettings none() {
        return new ModelSettings(Provider.NONE, "", "", "", DEFAULT_TIMEOUT, DEFAULT_MAX_ROUND_TRIPS,
                DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public boolean enabled() {
        return provider != Provider.NONE;
    }

    /** {@code text} with every occurrence of the API key replaced, for log lines and anything else that may leave. */
    public String redact(String text) {
        if (text == null || apiKey.isEmpty()) return text;
        return text.replace(apiKey, "***");
    }

    /** Never prints the key: only whether one is set. */
    @Override
    public String toString() {
        return "ModelSettings[provider=" + provider.id() + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (apiKey.isEmpty() ? "<none>" : "<set>") + ", timeout=" + timeout
                + ", maxRoundTrips=" + maxRoundTrips + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
