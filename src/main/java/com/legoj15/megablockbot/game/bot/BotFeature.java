package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.core.lang.model.ModelLanguageLayer;
import com.legoj15.megablockbot.game.body.BotBrains;
import com.legoj15.megablockbot.game.gametest.BotRuntimeGameTests;
import com.legoj15.megablockbot.game.gametest.BotRuntimeMiningGameTests;
import com.legoj15.megablockbot.game.gametest.SmeltGameTests;
import com.legoj15.megablockbot.game.gametest.SupplyChestGameTests;
import com.legoj15.megablockbot.game.lang.ModelLanguageConfig;
import com.legoj15.megablockbot.game.task.CraftTask;
import com.legoj15.megablockbot.game.task.EquipTask;
import com.legoj15.megablockbot.game.task.SmeltTask;
import com.legoj15.megablockbot.game.task.TaskRegistry;
import com.legoj15.megablockbot.game.task.WithdrawGameTests;
import com.legoj15.megablockbot.platform.Platform;

/**
 * The bot itself (design 3): the brain that ties a body to the executor, the planner, navigation and the language
 * layer, its tasks and its {@code /aiplayer} sub-commands. Called once from {@code GameBootstrap}.
 *
 * <p>The tasks registered here are the ones that need no movement. Walking, mining, drop recovery and depositing
 * ({@code navigate}, {@code reach}, {@code mine}, {@code recover_drops}, {@code deposit}) register themselves from their
 * own features; until they do, a leaf of those kinds fails at once with a reason naming the kind.
 */
public final class BotFeature {
    private BotFeature() {}

    public static void init() {
        // Every body made from now on thinks with a BotRuntime (a test may still pass its own brain to the manager).
        BotBrains.setFactory(BotRuntime::new);

        TaskRegistry.register(CraftTask.KIND, CraftTask::new);
        TaskRegistry.register(SmeltTask.KIND, SmeltTask::new);
        TaskRegistry.register(EquipTask.KIND, EquipTask::new);

        // The owner's words go through a language model when megablockbot/language.json names one; with none (the
        // default, written when the file is missing) the layer is the fixed grammar and makes no request.
        try {
            LanguageLayers.set(new ModelLanguageLayer(ModelLanguageConfig.load(Platform.loader().configDir())));
        } catch (RuntimeException e) {
            // A bad language config must never stop the server: the fixed grammar stays.
            MegaBlockBot.LOGGER.error("Could not set up the language model; only the fixed commands work", e);
        }

        BotCommands.register();
        // Chat is the owner's voice; the line is read on the server thread, after the loader's own handling.
        Platform.loader().onChatMessage(BotTalk::onChat);
        // The planner thread belongs to the server that started it.
        Platform.loader().onServerStopping(server -> BotPlanner.shutdown());

        Platform.loader().registerGameTests(BotRuntimeGameTests.class, BotRuntimeMiningGameTests.class,
                SupplyChestGameTests.class, WithdrawGameTests.class, SmeltGameTests.class);
    }
}
