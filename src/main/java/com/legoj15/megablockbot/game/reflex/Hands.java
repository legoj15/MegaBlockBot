package com.legoj15.megablockbot.game.reflex;

import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.task.Weapons;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a player does with the inventory screen and the hotbar keys, for a body: picking the best weapon, food, shield,
 * tool or building block, and bringing it to the hand. Moving an item is a real inventory click (a swap with a hotbar
 * slot, which is what a number key does over a slot), so the menu's own rules and events apply. Indices are the
 * inventory's: 0 to 8 the hotbar, 9 to 35 the rest, and -1 means none.
 */
final class Hands {
    private Hands() {}

    private static final int MAIN_SLOTS = 36;
    /** The number-key button of an inventory swap click that means the off hand. */
    private static final int OFFHAND_BUTTON = 40;

    // ---------------------------------------------------------------------------------------------------------
    // Moving things to the hands
    // ---------------------------------------------------------------------------------------------------------

    /** Makes the stack at {@code index} the one in the main hand: selects its hotbar slot, or swaps it into the selected one. */
    static void toMainHand(BotPlayer body, int index) {
        Weapons.toMainHand(body, index);
    }

    /** Makes the stack at {@code index} the one in the off hand (the stack that was there goes to its slot). */
    static void toOffhand(BotPlayer body, int index) {
        int menuSlot = Inventory.isHotbarSlot(index) ? 36 + index : index;
        if (index < 0 || index >= MAIN_SLOTS) return;
        body.inventoryMenu.clicked(menuSlot, OFFHAND_BUTTON, ClickType.SWAP, body);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Weapons and shields
    // ---------------------------------------------------------------------------------------------------------

    /**
     * What a swing of this stack is worth per second, 0 for anything that is not a sword or an axe with durability to
     * spare (a pickaxe would do more than a fist, and lose two durability for it; AIBot only counted swords and axes too).
     * The choice itself lives in {@link Weapons}, shared with the kill task.
     */
    static double weaponScore(ItemStack stack) {
        return Weapons.score(stack);
    }

    /** The inventory index of the best weapon in the main inventory or the hotbar, or -1 when the body holds none. */
    static int bestWeapon(BotPlayer body) {
        return Weapons.best(body);
    }

    /** True when the main hand holds a weapon the body would pick. */
    static boolean holdsWeapon(BotPlayer body) {
        return weaponScore(body.getMainHandItem()) > 0.0;
    }

    static boolean isShield(ItemStack stack) {
        return stack.getItem() instanceof ShieldItem;
    }

    /** The first shield in the main inventory, or -1. */
    static int findShield(BotPlayer body) {
        Inventory inventory = body.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (isShield(inventory.items.get(i))) return i;
        }
        return -1;
    }

    /** A shield is in the off hand and not on cooldown (an axe hit disables it for five seconds). */
    static boolean shieldReady(BotPlayer body) {
        ItemStack offhand = body.getOffhandItem();
        return isShield(offhand) && !body.getCooldowns().isOnCooldown(offhand.getItem());
    }

    /**
     * Whether the body has a shield to answer a shooter with: one already in the off hand (where a fight moves it), or
     * one anywhere in the main inventory. The question a retreat from a ranged mob asks (issue 29).
     */
    static boolean carriesShield(BotPlayer body) {
        return isShield(body.getOffhandItem()) || findShield(body) >= 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Food
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The food to eat now, or -1. Normally the one that fills the gap without waste, or the biggest when none does.
     * Never a chorus fruit (it teleports), and unless {@code emergency} never one that harms (rotten flesh, a spider
     * eye) or that is kept for fights (golden apples can be eaten at any food level).
     */
    static int bestFood(BotPlayer body, boolean emergency) {
        Inventory inventory = body.getInventory();
        int missing = 20 - body.getFoodData().getFoodLevel();
        int best = -1;
        long bestKey = Long.MAX_VALUE;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.items.get(i);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || stack.is(Items.CHORUS_FRUIT)) continue;
            boolean harmful = food.effects().stream().anyMatch(e -> e.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
            if (!emergency && (harmful || food.canAlwaysEat())) continue;
            // Lowest key wins: no harm first, then no waste (smallest nutrition that fills the gap), then the biggest.
            long waste = food.nutrition() >= missing ? food.nutrition() - missing : 1000 + (20 - food.nutrition());
            long key = (harmful ? 100000L : 0L) + (food.canAlwaysEat() ? 10000L : 0L) + waste;
            if (key < bestKey) {
                bestKey = key;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Tools and building blocks
    // ---------------------------------------------------------------------------------------------------------

    /** The slot whose stack breaks this block fastest (drops the right thing, where the block needs a tool), or -1 for the bare hand. */
    static int bestTool(BotPlayer body, BlockState state) {
        Inventory inventory = body.getInventory();
        float handSpeed = ItemStack.EMPTY.getDestroySpeed(state);
        boolean needsTool = state.requiresCorrectToolForDrops();
        int best = -1;
        float bestSpeed = handSpeed;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.items.get(i);
            if (stack.isEmpty()) continue;
            if (needsTool && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    /** How many blocks of the shelter list the body carries. */
    static int countShelterBlocks(BotPlayer body) {
        int total = 0;
        for (ItemStack stack : body.getInventory().items) {
            if (isShelterBlock(stack)) total += stack.getCount();
        }
        return total;
    }

    static boolean isShelterBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && ReflexConstants.SHELTER_BLOCKS.contains(stack.getItem());
    }

    /** The index of the stack to build with: the first kind in the shelter list the body carries. */
    static int buildingBlock(BotPlayer body) {
        Inventory inventory = body.getInventory();
        for (Item kind : ReflexConstants.SHELTER_BLOCKS) {
            for (int i = 0; i < MAIN_SLOTS; i++) {
                if (inventory.items.get(i).is(kind)) return i;
            }
        }
        return -1;
    }
}
