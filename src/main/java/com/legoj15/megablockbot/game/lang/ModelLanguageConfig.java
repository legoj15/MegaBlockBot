package com.legoj15.megablockbot.game.lang;

import com.legoj15.megablockbot.core.lang.model.ModelSettings;
import com.legoj15.megablockbot.core.lang.model.ModelSettingsFile;
import java.nio.file.Path;

/**
 * Where the language layer's config lives and how it is read (design 3.3). The file is
 * {@code megablockbot/language.json} under the loader's config directory, which is server-side: it is not part of
 * anything synced to clients, so an API key put in it stays on the server. The reading itself, and the commented
 * default written when the file is missing, are in {@link ModelSettingsFile} (Minecraft-free, so plain JUnit
 * covers them on every node).
 *
 * <p>This only reads. The bot runtime that holds the active language layer builds a
 * {@link com.legoj15.megablockbot.core.lang.model.ModelLanguageLayer} from the result (provider {@code none} gives
 * the fixed grammar's behaviour) and is wired separately; nothing here registers anything.
 */
public final class ModelLanguageConfig {
    private ModelLanguageConfig() {}

    /** The config file's path relative to the config directory. */
    public static final String RELATIVE_PATH = "megablockbot/language.json";

    /**
     * Loads the settings from {@code configDir/megablockbot/language.json}, writing a commented default (provider
     * {@code none}) when the file is missing. The key may be inline or the name of an environment variable. Never
     * throws: a file that cannot be used is logged without its key and means provider {@code none}.
     */
    public static ModelSettings load(Path configDir) {
        return ModelSettingsFile.loadOrCreate(configDir.resolve("megablockbot").resolve("language.json"), System::getenv);
    }
}
