package com.legoj15.megablockbot.game.nav;

import com.legoj15.megablockbot.nav.engine.api.entity.IHungerManager;
import net.minecraft.world.food.FoodData;

/** The navigation engine's view of a body's own {@link FoodData}: sprinting is planned only while food allows it. */
public final class BodyHunger implements IHungerManager {
    private final FoodData food;

    public BodyHunger(FoodData food) {
        this.food = food;
    }

    @Override
    public int getFoodLevel() {
        return food.getFoodLevel();
    }

    @Override
    public float getSaturationLevel() {
        return food.getSaturationLevel();
    }
}
