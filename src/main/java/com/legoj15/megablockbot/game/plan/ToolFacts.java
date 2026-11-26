package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ToolClass;
import com.legoj15.megablockbot.core.ToolTier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;

/**
 * Which tool classes a stack is, and how good a miner it is, read from the game's own data and never from an item's
 * name, so a modded tool counts.
 *
 * <p>Class comes from the item tags the game files its tools under ({@code minecraft:pickaxes}, ...; shears under the
 * {@code c:tools/shear} convention tag, plus the vanilla shears class), and for a tool no tag names, from the
 * {@code minecraft:tool} component's rules: a rule that mines and drops a {@code mineable/<class>} block tag.
 *
 * <p>Tier comes from the same component the game uses to decide whether a block drops (see
 * {@code Item.isCorrectToolForDrops}): the rules that deny drops on a set of blocks, all of them, so the tool's tier is
 * its most restrictive rule. A sword's component has no such rule, so a {@link TieredItem}'s tier supplies it. The
 * denied set is then mapped to {@link ToolTier} the way the enum documents: a vanilla {@code incorrect_for_*_tool} tag
 * that is the only denying rule maps to its own tier (gold's to wood), and any other set (a modded tier, several rules)
 * to the highest vanilla tier whose denied set contains it, meaning the tool can mine everything that tier can.
 */
final class ToolFacts {
    // The convention tag NeoForge files shears under; a TagKey is only a name, so no NeoForge type is needed.
    private static final TagKey<Item> SHEARS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/shear"));

    /** Vanilla tiers best first, each with the tag of blocks a tool of that tier cannot harvest. */
    private static final List<TierTag> LADDER = List.of(
        new TierTag(ToolTier.NETHERITE, BlockTags.INCORRECT_FOR_NETHERITE_TOOL),
        new TierTag(ToolTier.DIAMOND, BlockTags.INCORRECT_FOR_DIAMOND_TOOL),
        new TierTag(ToolTier.IRON, BlockTags.INCORRECT_FOR_IRON_TOOL),
        new TierTag(ToolTier.STONE, BlockTags.INCORRECT_FOR_STONE_TOOL),
        new TierTag(ToolTier.WOOD, BlockTags.INCORRECT_FOR_WOODEN_TOOL));

    private record TierTag(ToolTier tier, TagKey<Block> denied) {}

    private ToolFacts() {}

    /** The classes the stack is a tool of; empty for everything else. A multi-tool may be several. */
    static Set<ToolClass> classesOf(ItemStack stack) {
        Set<ToolClass> classes = EnumSet.noneOf(ToolClass.class);
        if (stack.isEmpty()) return classes;
        if (stack.is(ItemTags.PICKAXES)) classes.add(ToolClass.PICKAXE);
        if (stack.is(ItemTags.AXES)) classes.add(ToolClass.AXE);
        if (stack.is(ItemTags.SHOVELS)) classes.add(ToolClass.SHOVEL);
        if (stack.is(ItemTags.HOES)) classes.add(ToolClass.HOE);
        if (stack.is(ItemTags.SWORDS)) classes.add(ToolClass.SWORD);
        if (stack.is(SHEARS_TAG) || stack.getItem() instanceof ShearsItem) classes.add(ToolClass.SHEARS);
        if (classes.isEmpty()) classes.addAll(classesFromToolComponent(stack.get(DataComponents.TOOL)));
        return classes;
    }

    private static Set<ToolClass> classesFromToolComponent(Tool tool) {
        Set<ToolClass> classes = EnumSet.noneOf(ToolClass.class);
        if (tool == null) return classes;
        for (Tool.Rule rule : tool.rules()) {
            if (!rule.correctForDrops().orElse(false)) continue;
            Optional<TagKey<Block>> tag = rule.blocks().unwrapKey();
            if (tag.isEmpty()) continue;
            if (tag.get().equals(BlockTags.MINEABLE_WITH_PICKAXE)) classes.add(ToolClass.PICKAXE);
            else if (tag.get().equals(BlockTags.MINEABLE_WITH_AXE)) classes.add(ToolClass.AXE);
            else if (tag.get().equals(BlockTags.MINEABLE_WITH_SHOVEL)) classes.add(ToolClass.SHOVEL);
            else if (tag.get().equals(BlockTags.MINEABLE_WITH_HOE)) classes.add(ToolClass.HOE);
        }
        return classes;
    }

    /**
     * How good the stack is at mining: its tier, or {@link ToolTier#HAND} when the game's data names no set of blocks it
     * cannot harvest (shears, and a tool whose data says nothing).
     */
    static ToolTier tierOf(ItemStack stack) {
        // Every rule that denies drops counts, not the first: a block any of them names is one the tool cannot harvest,
        // so the tool's tier is its most restrictive rule.
        List<HolderSet<Block>> denying = new ArrayList<>();
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool != null) {
            for (Tool.Rule rule : tool.rules()) {
                if (rule.correctForDrops().isPresent() && !rule.correctForDrops().get()) denying.add(rule.blocks());
            }
        }

        Set<Block> denied = new HashSet<>();
        if (!denying.isEmpty()) {
            // A lone vanilla tag is its own tier by name; two rules, or any other set, are read by the blocks they name.
            if (denying.size() == 1) {
                ToolTier vanilla = denying.get(0).unwrapKey().map(ToolFacts::vanillaTier).orElse(null);
                if (vanilla != null) return vanilla;
            }
            for (HolderSet<Block> blocks : denying) denied.addAll(blocksDeniedBy(blocks));
        } else if (stack.getItem() instanceof TieredItem tiered) {
            TagKey<Block> tag = tiered.getTier().getIncorrectBlocksForDrops();
            ToolTier vanilla = vanillaTier(tag);
            if (vanilla != null) return vanilla;
            denied = blocksOf(tag);
        } else {
            return ToolTier.HAND;
        }

        for (TierTag step : LADDER) {
            if (blocksOf(step.denied()).containsAll(denied)) return step.tier();
        }
        return ToolTier.HAND;
    }

    // A rule that names a tag is read through the registry, like the tiers' own tags, so that both agree on what the tag holds.
    private static Set<Block> blocksDeniedBy(HolderSet<Block> holders) {
        return holders.unwrapKey().map(ToolFacts::blocksOf).orElseGet(() -> blocksIn(holders));
    }

    // Gold has its own tag with wood's contents in vanilla, and the enum says gold mines like wood.
    private static ToolTier vanillaTier(TagKey<Block> denied) {
        if (denied.equals(BlockTags.INCORRECT_FOR_GOLD_TOOL)) return ToolTier.WOOD;
        for (TierTag step : LADDER) {
            if (denied.equals(step.denied())) return step.tier();
        }
        return null;
    }

    // A tag that no data file defines denies nothing, which is how the game reads it too.
    private static Set<Block> blocksOf(TagKey<Block> tag) {
        return BuiltInRegistries.BLOCK.getTag(tag).map(named -> blocksIn(named)).orElseGet(() -> Set.of());
    }

    private static Set<Block> blocksIn(HolderSet<Block> holders) {
        Set<Block> blocks = new HashSet<>();
        for (Holder<Block> holder : holders) blocks.add(holder.value());
        return blocks;
    }
}
