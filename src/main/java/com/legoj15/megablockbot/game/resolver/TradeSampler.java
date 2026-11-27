package com.legoj15.megablockbot.game.resolver;

import net.minecraft.world.level.block.state.BlockState;
import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.acquire.Source;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Villager and wandering-trader trades. The listings are arbitrary classes with private fields (modded ones too), so
 * they are sampled instead of opened: each listing's {@code getOffer} is called {@link #SAMPLES} times with a fixed
 * seed against a villager that exists but was never added to a level, and every distinct result is recorded with how
 * often it came up. Run after the loader's trade events, so modded listings are in.
 */
final class TradeSampler {
    static final int SAMPLES = 64;
    private static final long SEED = 0x4D42_4254_5241_4445L; // "MBBTRADE"

    private TradeSampler() {}

    // One distinct result of one listing, before ids are settled.
    private record Result(String profession, int level, int listing, ItemKey item, Map<String, Integer> enchantments,
                          Map<ItemKey, Integer> cost, ItemKey jobSite, int appearances) {}

    static List<Source.Trade> sample(MinecraftServer server, BuildNotes notes) {
        ServerLevel level = server.overworld();
        Entity villager = EntityType.VILLAGER.create(level);
        Entity wanderer = EntityType.WANDERING_TRADER.create(level);
        List<Result> results = new ArrayList<>();

        if (villager != null) {
            List<Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>>> professions =
                new ArrayList<>(VillagerTrades.TRADES.entrySet());
            professions.sort(Comparator.comparing(e -> BuiltInRegistries.VILLAGER_PROFESSION.getKey(e.getKey()).toString()));
            for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> p : professions) {
                String profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(p.getKey()).toString();
                ItemKey jobSite = jobSiteOf(p.getKey(), profession, notes);
                sampleLevels(villager, profession, p.getValue(), jobSite, results, notes);
            }
        } else {
            notes.count("no villager could be created, villager trades not indexed");
        }
        if (wanderer != null) {
            sampleLevels(wanderer, "minecraft:wandering_trader", VillagerTrades.WANDERING_TRADER_TRADES, null, results, notes);
        } else {
            notes.count("no wandering trader could be created, its trades not indexed");
        }
        return toSources(results);
    }

    /**
     * The item of the block that gives a villager this profession, or null when the profession names no job site or its
     * block has no item: the profession's job-site point of interest (the predicate a profession is registered with),
     * read against the point-of-interest registry, and the first block it matches whose item exists. A lectern makes a
     * librarian; the wandering trader and the unemployed profession name none.
     */
    private static ItemKey jobSiteOf(VillagerProfession profession, String name, BuildNotes notes) {
        try {
            for (Holder<PoiType> poi : BuiltInRegistries.POINT_OF_INTEREST_TYPE.holders().toList()) {
                if (!profession.heldJobSite().test(poi)) continue;
                // The states come as a set: the block ids are sorted so that the one named is the same every start.
                String first = null;
                for (BlockState state : poi.value().matchingStates()) {
                    Item item = state.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    String id = BuiltInRegistries.ITEM.getKey(item).toString();
                    if (first == null || id.compareTo(first) < 0) first = id;
                }
                if (first != null) return ItemKey.of(first);
            }
        } catch (RuntimeException e) {
            notes.count("professions whose job-site block could not be read (" + name + ")");
            return null;
        }
        notes.count("professions whose job-site block has no item (" + name + ")");
        return null;
    }

    private static void sampleLevels(Entity trader, String profession, Int2ObjectMap<VillagerTrades.ItemListing[]> levels,
                                     @Nullable ItemKey jobSite, List<Result> out, BuildNotes notes) {
        List<Integer> keys = new ArrayList<>(levels.keySet());
        keys.sort(Comparator.naturalOrder());
        for (int level : keys) {
            VillagerTrades.ItemListing[] listings = levels.get(level);
            if (listings == null) {
                notes.skipped("villager trade levels with no listing array", profession + ":" + level);
                continue;
            }
            for (int i = 0; i < listings.length; i++) {
                VillagerTrades.ItemListing listing = listings[i];
                if (listing == null) {
                    // A mod's trade event can leave a hole in the array; it costs that listing, not the other trades.
                    notes.skipped("null villager trade listings", profession + ":" + level + ":" + i);
                    continue;
                }
                if (listing instanceof VillagerTrades.TreasureMapForEmeralds) {
                    // Rolling it searches for a structure and allocates a map id in the world's saved data, once per
                    // sample, so it is left out rather than polluting the save.
                    notes.count("explorer map trades not sampled (each roll would create a world map)");
                    continue;
                }
                // Each listing on its own: one that throws, while it offers or while its offer is read, costs that
                // listing, not the rest of its level or the professions after it.
                try {
                    sampleListing(trader, profession, level, i, listing, jobSite, out);
                } catch (RuntimeException e) {
                    notes.skipped("villager trade listings that threw while sampled",
                        profession + ":" + level + ":" + i + " " + listing.getClass().getName());
                }
            }
        }
    }

    private static void sampleListing(Entity trader, String profession, int level, int index,
                                      VillagerTrades.ItemListing listing, @Nullable ItemKey jobSite, List<Result> out) {
        // Stable across restarts: the seed is built from the position of the listing, never from identity hashes.
        RandomSource random = RandomSource.create(SEED ^ (profession + ":" + level + ":" + index).hashCode());
        Map<String, Result> byKey = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int sample = 0; sample < SAMPLES; sample++) {
            MerchantOffer offer = listing.getOffer(trader, random);
            if (offer == null) continue;
            ItemStack stack = offer.getResult();
            if (stack.isEmpty()) continue;

            ItemKey item = ItemKey.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            // Only an enchanted book's stored enchantments tell results apart; a sword's random enchantments do not.
            Map<String, Integer> enchantments = new TreeMap<>();
            ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored != null) {
                for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
                    String id = e.getKey().unwrapKey().map(k -> k.location().toString()).orElse("?");
                    enchantments.put(id, e.getIntValue());
                }
            }
            Map<ItemKey, Integer> cost = new TreeMap<>();
            addCost(cost, offer.getBaseCostA());
            addCost(cost, offer.getCostB());

            String key = item.id() + enchantments;
            counts.merge(key, 1, Integer::sum);
            Result prior = byKey.get(key);
            // Prices vary by roll; the cheapest seen is the one a planner should expect to be able to get.
            if (prior == null || total(cost) < total(prior.cost())) {
                byKey.put(key, new Result(profession, level, index, item, enchantments, cost, jobSite, 0));
            }
        }
        for (Map.Entry<String, Result> e : byKey.entrySet()) {
            Result r = e.getValue();
            out.add(new Result(r.profession(), r.level(), r.listing(), r.item(), r.enchantments(), r.cost(), r.jobSite(),
                counts.get(e.getKey())));
        }
    }

    private static void addCost(Map<ItemKey, Integer> cost, ItemStack stack) {
        if (stack.isEmpty()) return;
        cost.merge(ItemKey.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()), stack.getCount(), Integer::sum);
    }

    private static int total(Map<ItemKey, Integer> cost) {
        int total = 0;
        for (int n : cost.values()) total += n;
        return total;
    }

    // Ids are "trade:<profession>:<level>", plus the enchantments of a book when it has any, plus the listing index
    // only where two listings of one level would otherwise share an id for the same item.
    private static List<Source.Trade> toSources(List<Result> results) {
        Map<String, Integer> uses = new HashMap<>();
        for (Result r : results) uses.merge(r.item().id() + "|" + baseId(r), 1, Integer::sum);
        List<Source.Trade> trades = new ArrayList<>();
        for (Result r : results) {
            String id = baseId(r);
            if (uses.get(r.item().id() + "|" + id) > 1) id += "@" + r.listing();
            trades.add(new Source.Trade(id, r.item(), r.profession(), r.level(), r.cost(), r.enchantments(),
                (double) r.appearances() / SAMPLES, r.jobSite()));
        }
        return trades;
    }

    private static String baseId(Result r) {
        StringBuilder id = new StringBuilder("trade:").append(r.profession()).append(':').append(r.level());
        if (!r.enchantments().isEmpty()) {
            id.append('#');
            boolean first = true;
            for (Map.Entry<String, Integer> e : r.enchantments().entrySet()) {
                if (!first) id.append(',');
                first = false;
                id.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return id.toString();
    }
}
