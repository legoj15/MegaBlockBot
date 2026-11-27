package com.legoj15.megablockbot.game.task;

import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.game.body.BotPlayer;
import java.util.Optional;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Blocks;

/**
 * Which tools a body carries, in the planner's terms: a class and a tier (design 4.3). A tool anywhere in the inventory
 * counts; moving the best one to the hotbar is the navigation engine's own business.
 */
final class HeldTools {
    private HeldTools() {}

    /**
     * The best tier among the body's tools of this class, or empty when it carries none. A class whose tools have no
     * tier (shears) reports {@link ToolTier#HAND} when one is carried.
     */
    static Optional<ToolTier> best(BotPlayer body, ToolClass toolClass) {
        ToolTier best = null;
        for (ItemStack stack : body.getInventory().items) {
            best = merge(best, tierOf(stack, toolClass));
        }
        best = merge(best, tierOf(body.getInventory().offhand.get(0), toolClass));
        return Optional.ofNullable(best);
    }

    /** True when the body carries a tool of the class at this tier or better. */
    static boolean has(BotPlayer body, ToolClass toolClass, ToolTier minTier) {
        return best(body, toolClass).map(tier -> tier.atLeast(minTier)).orElse(false);
    }

    private static ToolTier merge(ToolTier a, ToolTier b) {
        if (a == null) return b;
        if (b == null) return a;
        return ToolTier.max(a, b);
    }

    private static ToolTier tierOf(ItemStack stack, ToolClass toolClass) {
        if (stack.isEmpty() || !isOfClass(stack, toolClass)) return null;
        if (!(stack.getItem() instanceof TieredItem tiered)) return ToolTier.HAND;
        return tierOf(tiered.getTier(), stack);
    }

    private static boolean isOfClass(ItemStack stack, ToolClass toolClass) {
        return switch (toolClass) {
            case PICKAXE -> stack.getItem() instanceof PickaxeItem || stack.is(ItemTags.PICKAXES);
            case AXE -> stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES);
            case SHOVEL -> stack.getItem() instanceof ShovelItem || stack.is(ItemTags.SHOVELS);
            case HOE -> stack.getItem() instanceof HoeItem || stack.is(ItemTags.HOES);
            case SWORD -> stack.getItem() instanceof SwordItem || stack.is(ItemTags.SWORDS);
            case SHEARS -> stack.getItem() instanceof ShearsItem;
        };
    }

    /** Vanilla's tiers by name (gold mines like wood, see {@link ToolTier}); a modded tier by what it can harvest. */
    private static ToolTier tierOf(Tier tier, ItemStack stack) {
        if (tier instanceof Tiers vanilla) {
            return switch (vanilla) {
                case WOOD, GOLD -> ToolTier.WOOD;
                case STONE -> ToolTier.STONE;
                case IRON -> ToolTier.IRON;
                case DIAMOND -> ToolTier.DIAMOND;
                case NETHERITE -> ToolTier.NETHERITE;
            };
        }
        // Each probe is a block that needs that tier of tool and no better one: iron ore needs stone, diamond ore iron,
        // obsidian diamond. Netherite harvests what diamond does, so a modded tier tops out at diamond.
        if (stack.isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState())) return ToolTier.DIAMOND;
        if (stack.isCorrectToolForDrops(Blocks.DIAMOND_ORE.defaultBlockState())) return ToolTier.IRON;
        if (stack.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())) return ToolTier.STONE;
        return ToolTier.WOOD;
    }
}
