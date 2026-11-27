package com.legoj15.megablockbot.core.lang.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * How to reach the language model (design 3.3): which wire format, where, which model, and the limits that keep a
 * slow or chatty model from costing the owner anything. Plain data; {@link ModelSettingsFile} reads it from the
 * server's config directory.
 *
 * <p>The API key lives here and nowhere else in the layer's output: {@link #toString()} never prints it, and
 * {@link #redact} replaces every exact occurrence of it in a text (an error body a gateway echoed back) before it is
 * logged. The address is printed as {@link #safeBaseUrl()} only: scheme, host, port and path, no user name, password,
 * query or fragment.
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
            // What was written is not quoted: a key pasted into the wrong field must not reach a log.
            throw new IllegalArgumentException("provider must be none, openai or anthropic");
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

    /** What {@link #safeBaseUrl} prints for an address it cannot make sense of (and so cannot vouch for). */
    private static final String UNUSABLE_ADDRESS = "<not a usable address>";

    /**
     * {@code text} with every exact occurrence of the API key replaced by {@code ***}. That is all it does: the key
     * in any other shape (URL-encoded, quoted differently, cut short, hashed) is not recognised, and nothing else in
     * the text is touched. Callers that may be handed the server's address too use {@link #scrub}.
     */
    public String redact(String text) {
        if (text == null || apiKey.isEmpty()) return text;
        return text.replace(apiKey, "***");
    }

    /**
     * The server address as it may be printed: scheme, host, port and path. The user name and password that can
     * precede the host, and the query and fragment (where some gateways expect a token), are left out, as is
     * everything of an address that cannot be read as {@code scheme://host...}.
     */
    public String safeBaseUrl() {
        if (baseUrl.isEmpty()) return "";
        try {
            URI uri = new URI(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) return UNUSABLE_ADDRESS;
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (URISyntaxException notAnAddress) {
            return UNUSABLE_ADDRESS;
        }
    }

    /**
     * {@code text} made fit for a log line or a message to the owner: the address of the server, where it appears
     * (a JDK complaint about a bad request quotes it), is replaced by {@link #safeBaseUrl}, and the key as
     * {@link #redact} does.
     */
    public String scrub(String text) {
        if (text == null) return redact(null);
        String cleaned = text;
        if (!baseUrl.isEmpty()) {
            // The request URL is the base without trailing slashes plus a path, so that is the form to look for.
            String raw = withoutTrailingSlashes(baseUrl);
            String safe = withoutTrailingSlashes(safeBaseUrl());
            if (!raw.isEmpty() && !raw.equals(safe)) cleaned = cleaned.replace(raw, safe);
        }
        // A server that does not know the model quotes its name back. A name as long as a key may be one, pasted a
        // field too high; a short name is only a name, and replacing it would chew up ordinary words.
        if (model.length() >= KEY_LIKE_LENGTH) cleaned = cleaned.replace(model, "<model>");
        return redact(cleaned);
    }

    /** From this length on, the model setting's text is treated as a possible key in what is logged. */
    static final int KEY_LIKE_LENGTH = 20;

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }

    /**
     * Never prints the key, nor the secrets an address can carry, nor the model name as written: only whether each
     * is set. The model name sits beside the key in the file, and a key pasted one field too high would otherwise be
     * logged on every start. The file itself says which model was asked for.
     */
    @Override
    public String toString() {
        return "ModelSettings[provider=" + provider.id() + ", baseUrl=" + safeBaseUrl() + ", model=" + (model.isEmpty() ? "<none>" : "<set>")
                + ", apiKey=" + (apiKey.isEmpty() ? "<none>" : "<set>") + ", timeout=" + timeout
                + ", maxRoundTrips=" + maxRoundTrips + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}
