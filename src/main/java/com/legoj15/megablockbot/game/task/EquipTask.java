package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.EquipSlot;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.goal.Goal;
import com.legoj15.megablockbot.core.plan.NodeSpec;
import com.legoj15.megablockbot.core.task.AbstractTask;
import com.legoj15.megablockbot.core.task.Failure;
import com.legoj15.megablockbot.core.task.TickResult;
import com.legoj15.megablockbot.game.body.BotPlayer;
import com.legoj15.megablockbot.game.plan.ItemKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The {@code equip} task: puts each item of an {@link Goal.Equip} goal into its slot through the inventory menu, one
 * slot per tick, the way a player drags armor onto the paper doll. Armor and the off hand are placed by a pick-up and a
 * right click (one item, so a stack of helmets gives one); a piece already in the slot is shift-clicked out first. The
 * main hand is the selected hotbar slot: the item is selected where it is on the hotbar, and swapped there otherwise.
 *
 * <p>Failures: {@code MissingInput} when the item is not carried, {@code NoProgress} when the game will not take it (a
 * slot it does not fit, a piece that cannot be taken off, no room to put the old piece).
 */
public final class EquipTask extends AbstractTask<BotPlayer> {
    public static final String KIND = "equip";

    private final NodeSpec leaf;
    private final List<Map.Entry<EquipSlot, ItemKey>> wanted = new ArrayList<>();
    private int next;

    public EquipTask(NodeSpec leaf, Map<String, String> hints) {
        this.leaf = leaf;
    }

    @Override
    protected void onStart(BotPlayer body) {
        if (!(leaf.goal() instanceof Goal.Equip equip)) {
            fail(new Failure.Internal(null, "the equip task was given a " + leaf.goal().getClass().getSimpleName() + " goal"));
            return;
        }
        wanted.addAll(new TreeMap<>(equip.slots()).entrySet());
    }

    @Override
    protected TickResult onTick(BotPlayer body) {
        if (body.containerMenu != body.inventoryMenu) body.closeContainer();
        while (next < wanted.size()) {
            Map.Entry<EquipSlot, ItemKey> entry = wanted.get(next);
            next++;
            Item item = itemOf(entry.getValue());
            if (item == null) {
                return stop(body, new Failure.TargetGone(entry.getValue().id(), "the game has no item " + entry.getValue().id()));
            }
            if (isEquipped(body, entry.getKey(), item)) continue;
            Failure failure = equip(body, entry.getKey(), item);
            if (failure != null) return stop(body, failure);
            // One slot per tick: the clicks of the next one are the next tick's.
            return next < wanted.size() ? TickResult.RUNNING : TickResult.COMPLETED;
        }
        return TickResult.COMPLETED;
    }

    @Override
    protected void onPause(BotPlayer body, String reason) {
        release(body);
    }

    @Override
    protected void onAbort(BotPlayer body, String reason) {
        release(body);
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nullable
    private Failure equip(BotPlayer body, EquipSlot slot, Item item) {
        MenuHand hand = new MenuHand(body, body.inventoryMenu);
        try {
            return slot == EquipSlot.MAINHAND ? hold(body, hand, item) : wear(body, hand, slot, item);
        } finally {
            hand.putBack();
        }
    }

    @Nullable
    private static Failure hold(BotPlayer body, MenuHand hand, Item item) {
        Inventory inventory = body.getInventory();
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            if (inventory.getItem(hotbar).is(item)) {
                inventory.selected = hotbar;
                return null;
            }
        }
        int from = hand.findCarried(item);
        if (from < 0) return new Failure.MissingInput(ItemKeys.of(item), 1, "it is not in the inventory");
        hand.click(from, inventory.selected, ClickType.SWAP);
        return inventory.getItem(inventory.selected).is(item) ? null
                : new Failure.NoProgress(0, "the game would not put " + ItemKeys.of(item).id() + " in the hand");
    }

    @Nullable
    private static Failure wear(BotPlayer body, MenuHand hand, EquipSlot slot, Item item) {
        int target = hand.slotOf(containerSlot(slot));
        if (target < 0) return new Failure.Internal(null, "the inventory menu has no " + slot + " slot");
        Slot targetSlot = hand.slot(target);
        if (targetSlot.hasItem()) {
            // Something else is worn there: it goes back into the inventory first, as a shift click sends it.
            hand.moveOut(target);
            if (targetSlot.hasItem()) {
                return new Failure.NoProgress(0, "the " + ItemKeys.of(targetSlot.getItem().getItem()).id() + " in the "
                        + slot.name().toLowerCase(Locale.ROOT) + " slot could not be taken off (no room, or it will not come off)");
            }
        }
        if (!hand.pickUp(item)) return new Failure.MissingInput(ItemKeys.of(item), 1, "it is not in the inventory");
        hand.placeOne(target);
        return targetSlot.hasItem() && targetSlot.getItem().is(item) ? null
                : new Failure.NoProgress(0, ItemKeys.of(item).id() + " does not go in the " + slot.name().toLowerCase(Locale.ROOT) + " slot");
    }

    private static boolean isEquipped(BotPlayer body, EquipSlot slot, Item item) {
        return body.getItemBySlot(equipmentSlot(slot)).is(item);
    }

    private static EquipmentSlot equipmentSlot(EquipSlot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case MAINHAND -> EquipmentSlot.MAINHAND;
            case OFFHAND -> EquipmentSlot.OFFHAND;
        };
    }

    /** The slot of {@link Inventory} behind each worn place: boots 36 up to helmet 39, the off hand 40. */
    private static int containerSlot(EquipSlot slot) {
        return switch (slot) {
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            case OFFHAND -> 40;
            case MAINHAND -> throw new IllegalArgumentException("the main hand is a hotbar slot, not a worn place");
        };
    }

    @Nullable
    private static Item itemOf(ItemKey key) {
        ResourceLocation id = ResourceLocation.tryParse(key.id());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** A task that fails itself is never aborted afterwards, so it lets go of the cursor first. */
    private TickResult stop(BotPlayer body, Failure failure) {
        release(body);
        return TickResult.failed(failure);
    }

    private static void release(@Nullable BotPlayer body) {
        if (body == null) return;
        new MenuHand(body, body.inventoryMenu).putBack();
    }
}
