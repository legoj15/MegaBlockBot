package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.nav.engine.api.entity.IHungerManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * The navigation engine's view of a body's own food data: sprinting is planned only while food allows it. It holds the
 * player, not the food data: vanilla replaces a player's food data when a respawn keeps everything (the return from
 * the End), so a reference taken at construction would go on reading the old body's.
 */
public final class BodyHunger implements IHungerManager {
    private final ServerPlayer player;

    public BodyHunger(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public int getFoodLevel() {
        return player.getFoodData().getFoodLevel();
    }

    @Override
    public float getSaturationLevel() {
        return player.getFoodData().getSaturationLevel();
    }
}
