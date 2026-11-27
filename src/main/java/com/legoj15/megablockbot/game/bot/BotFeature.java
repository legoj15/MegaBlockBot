package com.legoj15.megablockbot.game.bot;

import com.legoj15.megablockbot.game.body.BotBrains;
import com.legoj15.megablockbot.game.gametest.BotRuntimeGameTests;
import com.legoj15.megablockbot.game.task.CraftTask;
import com.legoj15.megablockbot.game.task.EquipTask;
import com.legoj15.megablockbot.game.task.TaskRegistry;
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
        TaskRegistry.register(EquipTask.KIND, EquipTask::new);

        BotCommands.register();
        // Chat is the owner's voice; the line is read on the server thread, after the loader's own handling.
        Platform.loader().onChatMessage(BotTalk::onChat);
        // The planner thread belongs to the server that started it.
        Platform.loader().onServerStopping(server -> BotPlanner.shutdown());

        Platform.loader().registerGameTests(BotRuntimeGameTests.class);
    }
}
