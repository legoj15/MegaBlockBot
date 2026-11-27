package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.nav.BotNavigator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** What a reflex sees and may use in one tick: the body, its level, its navigator, and the threats around it (looked for at most once). */
final class ReflexContext {
    final ReflexLayer layer;
    final BotPlayer body;
    final ServerLevel level;
    final BotNavigator nav;
    /** The level's game time, which does not stop or jump when the daylight cycle does. */
    final long time;
    private List<Threat> threats;

    ReflexContext(ReflexLayer layer, BotPlayer body) {
        this.layer = layer;
        this.body = body;
        this.level = body.serverLevel();
        this.nav = BotNavigator.of(body);
        this.time = level.getGameTime();
    }

    /** Hostile mobs that need dealing with now, nearest first. */
    List<Threat> threats() {
        if (threats == null) threats = Threats.scan(body, level, layer.ignoredMobs(), time);
        return threats;
    }

    /** A navigator command of the reflex layer's own: the layer ticks it and stops it when the hold ends. */
    void goTo(BlockPos pos, int radius) {
        layer.markNavigatorUsed();
        nav.goTo(pos, radius);
    }

    /** Ticks the navigator while a command of the layer's own is running; the executor must not tick it while the body is held. */
    void tickNavigator() {
        if (nav.state() == BotNavigator.State.WORKING) nav.tick(body);
    }

    int food() {
        return body.getFoodData().getFoodLevel();
    }
}
