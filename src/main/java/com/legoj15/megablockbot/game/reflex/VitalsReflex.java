package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Priority 3 (design 3.2): hunger and health. Eats the best food the body holds when food is at or below the eat
 * threshold, or when health is low and the food bar has room to heal by; by using the item as a player does and then
 * waiting for as long as eating takes. It only ever runs when no fight is on (the mob reflex is above it and holds the
 * body through its quiet period). Low health with nothing to eat or no room to eat is not a reason to take the body: it
 * is reported in the status and the body carries on.
 *
 * <p>Once it has started it keeps going until food is at {@link ReflexConstants#EAT_UNTIL} (or health at
 * {@link ReflexConstants#HEAL_UNTIL} when it was health that started it), so one hold covers every bite.
 */
final class VitalsReflex implements Reflex {
    private enum Why { HUNGER, HEALTH }

    private boolean active;
    private Why why = Why.HUNGER;
    private String cause = "";
    private String outcome = "";
    private long retryAt;
    private int foodAtStart;
    private int bites;

    @Override
    public String name() {
        return "vitals";
    }

    @Override
    public String cause() {
        return cause;
    }

    @Override
    public String outcome() {
        return outcome;
    }

    @Override
    public boolean wants(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        int food = ctx.food();
        float health = body.getHealth();
        boolean hungry = food <= ReflexConstants.EAT_THRESHOLD;
        boolean hurt = health <= ReflexConstants.LOW_HEALTH && food < 20;
        boolean emergency = food <= ReflexConstants.CRITICAL_FOOD || health <= ReflexConstants.LOW_HEALTH;

        if (active) {
            if (body.isUsingItem()) return true;
            boolean done = why == Why.HUNGER ? food >= ReflexConstants.EAT_UNTIL : health >= ReflexConstants.HEAL_UNTIL || food >= 20;
            return !done && ctx.time >= retryAt && Hands.bestFood(body, emergency) >= 0 && body.canEat(false);
        }

        boolean needs = hungry || hurt;
        if (!needs) return false;
        if (Hands.bestFood(body, emergency) < 0) {
            ctx.layer.note(hungry ? "hungry, nothing to eat" : "low health, nothing to eat");
            return false;
        }
        if (ctx.time < retryAt || !body.canEat(false)) return false;
        active = true;
        why = hungry ? Why.HUNGER : Why.HEALTH;
        foodAtStart = food;
        bites = 0;
        outcome = "";
        return true;
    }

    @Override
    public void act(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        Steer.stand(body);
        if (body.isUsingItem()) {
            cause = "eating " + body.getUseItem().getHoverName().getString() + " (food " + ctx.food() + ")";
            return;
        }
        boolean emergency = ctx.food() <= ReflexConstants.CRITICAL_FOOD || body.getHealth() <= ReflexConstants.LOW_HEALTH;
        int slot = Hands.bestFood(body, emergency);
        if (slot < 0) return;
        Hands.toMainHand(body, slot);
        ItemStack food = body.getMainHandItem();
        body.resetLastActionTime();
        body.gameMode.useItem(body, ctx.level, food, InteractionHand.MAIN_HAND);
        if (body.isUsingItem()) {
            bites++;
            cause = "eating " + food.getHoverName().getString() + " (food " + ctx.food() + ")";
        } else {
            // Refused (a cooldown, an event): not tried again at once.
            retryAt = ctx.time + ReflexConstants.EAT_RETRY_TICKS;
            cause = "could not start eating " + food.getHoverName().getString();
        }
    }

    @Override
    public void release(ReflexContext ctx) {
        BotPlayer body = ctx.body;
        if (body.isUsingItem() && body.getUseItem().has(DataComponents.FOOD)) body.stopUsingItem();
        outcome = String.format(Locale.ROOT, "ate %d, food %d to %d", bites, foodAtStart, ctx.food());
        active = false;
    }
}
