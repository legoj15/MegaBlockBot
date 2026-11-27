package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.game.body.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * The weapon choice, in one place for every layer that fights: the reflex layer's mob reflex picks with it and so does
 * a {@code kill} task, and both must reach for the same sword. Moved here from {@code game.reflex.Hands} (which answers
 * with these, so what the reflex does is unchanged) when the kill task was added; the reflex layer already depends on
 * this package for its break rules ({@link BreakRules}).
 *
 * <p>Indices are the inventory's: 0 to 8 the hotbar, 9 to 35 the rest, and -1 means none.
 */
public final class Weapons {
    /** A sword or axe with this much durability or less left is not a weapon any more (AIBot: 2, same). */
    public static final int MIN_DURABILITY = 2;

    private Weapons() {}

    /**
     * What a swing of this stack is worth per second, 0 for anything that is not a sword or an axe with durability to
     * spare (a pickaxe would do more than a fist, and lose two durability for it; the reflex layer only counted swords
     * and axes too).
     */
    public static double score(ItemStack stack) {
        if (stack.isEmpty() || !(stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES))) return 0.0;
        if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= MIN_DURABILITY) return 0.0;
        double damage = 1.0; // a player's own, before the item's
        double speed = 4.0;
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot() != EquipmentSlotGroup.MAINHAND && entry.slot() != EquipmentSlotGroup.ANY) continue;
            if (entry.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            if (entry.attribute().equals(Attributes.ATTACK_DAMAGE)) damage += entry.modifier().amount();
            else if (entry.attribute().equals(Attributes.ATTACK_SPEED)) speed += entry.modifier().amount();
        }
        return damage * Math.max(0.1, speed);
    }

    /** The inventory index of the best weapon in the main inventory or the hotbar, or -1 when the body holds none. */
    public static int best(BotPlayer body) {
        Inventory inventory = body.getInventory();
        int best = -1;
        double bestScore = 0.0;
        for (int i = 0; i < MenuHand.CARRIED_SLOTS; i++) {
            double score = score(inventory.items.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Makes the stack at {@code index} the one in the main hand: selects its hotbar slot, or swaps it into the selected one. */
    public static void toMainHand(BotPlayer body, int index) {
        Inventory inventory = body.getInventory();
        if (Inventory.isHotbarSlot(index)) {
            inventory.selected = index;
        } else if (index >= 9 && index < MenuHand.CARRIED_SLOTS) {
            // Menu slot ids 9 to 35 are the inventory's own indices.
            body.inventoryMenu.clicked(index, inventory.selected, ClickType.SWAP, body);
        }
    }
}
