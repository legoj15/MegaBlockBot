package com.legoj15.megablockbot.game.plan;

import com.legoj15.megablockbot.core.ItemKey;
import com.legoj15.megablockbot.core.Pos;
import com.legoj15.megablockbot.core.snapshot.Delivery;
import com.legoj15.megablockbot.core.snapshot.NotedOffer;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What one body remembers between snapshots (design 3.4, 3.5): the blocks it placed, which it may break and take from
 * again, and the containers whose contents it has seen. A snapshot reads the world as it is now and nothing else, so
 * facts the world cannot tell (who placed a table, what was in a chest the bot walked away from) live here.
 *
 * <p>It also holds the containers the owner named as supply containers (design 3.5): ones the body may take from
 * although it did not place them. Those are the owner's word, not something the world can say, so they are saved with
 * the body like the rest. Naming a container records where it is and which block it is, and nothing of what is in it:
 * a player cannot see into a closed chest, so the contents are known only from a note the body makes when it opens it.
 *
 * <p>One instance per body UUID ({@link #of}); a respawned body has the same UUID and so the same memory. Server
 * thread only. The crafting task notes the blocks it places, and the tasks that open a container (withdraw, look
 * inside) note what they find; the brain persists it with {@link #save()} next to its own state, and at spawn
 * the body gets what it saved back with {@link #restore}.
 *
 * <p>The sets are not bounded. A bot that places a block per second for a day has some tens of thousands of longs;
 * the day that matters, the brain forgets what it broke ({@link #forgetPlaced}).
 */
public final class BotMemory {
    private static final int SAVE_SCHEMA = 1;
    private static final Map<UUID, BotMemory> BY_BODY = new ConcurrentHashMap<>();

    /**
     * A container the body has seen the inside of, as it was then: the note it made when it opened it. {@code pos} is the
     * half with the lower {@code BlockPos.asLong} when it is a double chest and {@code other} the second half, else
     * {@code other} is null: a note is per inventory, never per block, so a pair is one note.
     */
    public record RememberedContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block,
                                      Map<ItemKey, Integer> contents) {
        public RememberedContainer {
            pos = pos.immutable();
            other = other == null ? null : other.immutable();
            contents = Map.copyOf(contents);
        }

        /** Whether {@code at} in {@code in} is one of this inventory's blocks. */
        public boolean covers(String in, BlockPos at) {
            return dimension.equals(in) && (pos.equals(at) || at.equals(other));
        }
    }

    /**
     * A container its owner named for the body to take from. {@code pos} is the half with the lower
     * {@code BlockPos.asLong} when it is a double chest and {@code other} the second half, else {@code other} is null:
     * one entry is one inventory, and naming either half names both (design 3.5). {@code block} is the id of the block
     * that stood there when it was named (both halves of a double chest are one block): the owner's word is for that
     * block, and a different one at the position is something nobody named.
     */
    public record SupplyContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block) {
        public SupplyContainer {
            pos = pos.immutable();
            other = other == null ? null : other.immutable();
        }

        /** Whether {@code at} in {@code in} is one of this inventory's blocks. */
        public boolean covers(String in, BlockPos at) {
            return dimension.equals(in) && (pos.equals(at) || at.equals(other));
        }
    }

    private record ContainerKey(String dimension, long pos) {}

    /**
     * Somewhere the body was driven off or killed (issue 29): a centre (where the threats stood, or where the body
     * fell), a radius and the game time it lapses, with a short reason. While it lives the body's plans, tasks and
     * walks keep out of it, and the navigator prices its cells high so a path goes round. Overlapping zones are kept
     * as they are, never merged; one that has lapsed says nothing.
     */
    public record DangerZone(String dimension, BlockPos centre, double radius, long lapsesAt, String reason) {
        public DangerZone {
            centre = centre.immutable();
        }

        /** Whether the zone still counts at {@code now}. */
        public boolean live(long now) {
            return now < lapsesAt;
        }

        /** Whether a position of this dimension lies inside the zone (a sphere, as the pathfinder prices it). */
        public boolean covers(String in, BlockPos at) {
            return dimension.equals(in) && Math.sqrt(at.distSqr(centre)) <= radius;
        }
    }

    /**
     * The offers of one villager, as the body saw them the last time it opened that villager's trade window: the note
     * it made then, replaced each time it opens the window again. Kept whole, with the game time it was made at, so
     * the collector can drop the stale ones; nothing here says where the villager is now.
     */
    public record VillagerOffers(String dimension, UUID villager, BlockPos pos, List<NotedOffer> offers) {
        public VillagerOffers {
            pos = pos.immutable();
            offers = List.copyOf(offers);
        }
    }

    // Dimension id to BlockPos.asLong() of every block placed there.
    private final Map<String, Set<Long>> placed = new HashMap<>();
    private final Map<ContainerKey, RememberedContainer> containers = new LinkedHashMap<>();
    private final Map<ContainerKey, SupplyContainer> supply = new LinkedHashMap<>();
    // The villager whose window was open, by its UUID.
    private final Map<UUID, VillagerOffers> villagerOffers = new LinkedHashMap<>();
    // The last scan of the blocks around the body (BodySight). A cache of the world, so it is never saved.
    private BodySight.Taken sight;
    private int sightScans;
    /** Where the body was driven off or killed, in the order noted. */
    private final List<DangerZone> dangerZones = new ArrayList<>();
    /** What the body has handed to players, in the order noted; the oldest beyond the bound are dropped. */
    private final List<Delivery> deliveries = new ArrayList<>();
    /**
     * How many deliveries are kept: a goal counts only what was handed over after it was given, so a bound well past
     * one goal's asks is enough, and the notes of a body that hands things over all day do not grow without end.
     */
    private static final int MAX_DELIVERIES = 64;

    public static BotMemory of(UUID body) {
        return BY_BODY.computeIfAbsent(body, id -> new BotMemory());
    }

    /** One body's memory if it has one, without making one: for a question that must not leave a trace. */
    @Nullable
    public static BotMemory peek(UUID body) {
        return BY_BODY.get(body);
    }

    /** Drops one body's memory, for a body that is deleted for good. A despawn keeps it: the body comes back. */
    public static void forget(UUID body) {
        BY_BODY.remove(body);
    }

    /** Drops every memory: they belong to the server that made them. */
    static void clearAll() {
        BY_BODY.clear();
    }

    // ---- placed blocks -----------------------------------------------------------------------------------------

    public void notePlaced(String dimension, BlockPos pos) {
        placed.computeIfAbsent(dimension, d -> new HashSet<>()).add(pos.asLong());
    }

    /** For a block the body broke again, or one it saw gone. */
    public void forgetPlaced(String dimension, BlockPos pos) {
        Set<Long> inDimension = placed.get(dimension);
        if (inDimension == null) return;
        inDimension.remove(pos.asLong());
        if (inDimension.isEmpty()) placed.remove(dimension);
    }

    public boolean isPlaced(String dimension, BlockPos pos) {
        Set<Long> inDimension = placed.get(dimension);
        return inDimension != null && inDimension.contains(pos.asLong());
    }

    public int placedCount() {
        int count = 0;
        for (Set<Long> inDimension : placed.values()) count += inDimension.size();
        return count;
    }

    /** Every position the body placed a block at in the dimension, sorted so equal memories read alike. */
    public List<BlockPos> placedIn(String dimension) {
        Set<Long> inDimension = placed.get(dimension);
        if (inDimension == null) return List.of();
        List<BlockPos> positions = new ArrayList<>(inDimension.size());
        for (long at : inDimension) positions.add(BlockPos.of(at));
        positions.sort(Comparator.comparingLong(BlockPos::asLong));
        return positions;
    }

    // ---- containers --------------------------------------------------------------------------------------------

    /**
     * Records what the body saw in the container at that position, replacing what it saw there before. A note is per
     * inventory: whatever was noted of any block of this one (a lone chest that has since become half of a double chest
     * was noted under its own position) is replaced by this note, so one inventory is never counted twice.
     */
    public void noteContainer(String dimension, BlockPos pos, String block, Map<ItemKey, Integer> contents) {
        noteContainer(dimension, pos, null, block, contents);
    }

    /** As above, for an inventory of two blocks ({@code other} is the second half of a double chest, else null). */
    public void noteContainer(String dimension, BlockPos pos, @Nullable BlockPos other, String block,
                              Map<ItemKey, Integer> contents) {
        forgetContainersCovering(dimension, pos, other);
        containers.put(new ContainerKey(dimension, pos.asLong()), new RememberedContainer(dimension, pos, other, block, contents));
    }

    /** Everything remembered, in the order it was first noted. */
    public List<RememberedContainer> containers() {
        return List.copyOf(containers.values());
    }

    /** What was noted of the container one of whose blocks is at that position, or null. */
    @Nullable
    public RememberedContainer containerAt(String dimension, BlockPos pos) {
        for (RememberedContainer note : containers.values()) {
            if (note.covers(dimension, pos)) return note;
        }
        return null;
    }

    /** Forgets what was noted of the container one of whose blocks is at that position: it is gone, or no longer the body's to use. */
    public void forgetContainer(String dimension, BlockPos pos) {
        forgetContainersCovering(dimension, pos, null);
    }

    private void forgetContainersCovering(String dimension, BlockPos pos, @Nullable BlockPos other) {
        containers.values().removeIf(note -> note.covers(dimension, pos) || (other != null && note.covers(dimension, other)));
    }

    // ---- supply containers -------------------------------------------------------------------------------------

    /**
     * Records that the owner named this container, and forgets what was noted of its blocks: that was the container
     * before the owner's word, and the body knows what is in it only when it has been inside since. Naming it again
     * therefore sends the body to look again. An entry that shares a block with it is replaced (the owner named the other
     * half of a double chest, or a chest was joined to another since). Returns false when the very same entry was already
     * there.
     */
    public boolean addSupply(SupplyContainer entry) {
        boolean fresh = putSupply(entry);
        forgetContainersCovering(entry.dimension(), entry.pos(), entry.other());
        return fresh;
    }

    private boolean putSupply(SupplyContainer entry) {
        SupplyContainer same = supply.get(new ContainerKey(entry.dimension(), entry.pos().asLong()));
        if (entry.equals(same)) return false;
        supply.values().removeIf(old -> old.covers(entry.dimension(), entry.pos())
            || (entry.other() != null && old.covers(entry.dimension(), entry.other())));
        supply.put(new ContainerKey(entry.dimension(), entry.pos().asLong()), entry);
        return true;
    }

    /** Takes the container one of whose blocks is {@code at} off the list; false when none is there. */
    public boolean removeSupply(String dimension, BlockPos at) {
        return supply.values().removeIf(entry -> entry.covers(dimension, at));
    }

    /** The entry one of whose blocks is {@code at}, or null: the owner's word for that block. */
    @Nullable
    public SupplyContainer supplyAt(String dimension, BlockPos at) {
        for (SupplyContainer entry : supply.values()) {
            if (entry.covers(dimension, at)) return entry;
        }
        return null;
    }

    public boolean isSupply(String dimension, BlockPos at) {
        return supplyAt(dimension, at) != null;
    }

    /** The containers the owner named, in the order named. */
    public List<SupplyContainer> supply() {
        return List.copyOf(supply.values());
    }

    // ---- villager offers ---------------------------------------------------------------------------------------

    /**
     * Records what the body saw in that villager's trade window, replacing what it saw there before. Offers are noted
     * per villager, not per window: opening the same one again is a fresh look, not a second opinion.
     */
    public void noteOffers(String dimension, UUID villager, BlockPos pos, List<NotedOffer> offers) {
        // What is older than the snapshot would read is dropped as new notes come in, so a body that trades in
        // many villages does not carry, and save, every window it ever opened.
        long newest = Long.MIN_VALUE;
        for (NotedOffer offer : offers) newest = Math.max(newest, offer.gameTime());
        if (newest != Long.MIN_VALUE) {
            long oldestKept = newest - SnapshotCollector.OFFER_AGE_TICKS;
            villagerOffers.values().removeIf(old -> old.offers().stream().allMatch(offer -> offer.gameTime() < oldestKept));
        }
        villagerOffers.put(villager, new VillagerOffers(dimension, villager, pos, offers));
    }

    /** Drops what was noted of a villager that is no longer in the world. */
    public void forgetOffers(UUID villager) {
        villagerOffers.remove(villager);
    }

    /** Everything remembered, in the order it was first noted. */
    public List<VillagerOffers> notedOffers() {
        return List.copyOf(villagerOffers.values());
    }

    // ---- deliveries --------------------------------------------------------------------------------------------

    /**
     * Records that the body dropped {@code count} of {@code item} at the feet of the player {@code recipient}
     * (issue #19). What a player carries is not the body's to read, so this note is the only record a
     * {@link com.legoj15.megablockbot.core.goal.Goal.Deliver} goal is judged on. The oldest notes are dropped as new
     * ones come in, so a body that trades all day does not carry, and save, every stack it ever handed over.
     */
    public void noteDelivery(String recipient, ItemKey item, int count, long gameTime) {
        deliveries.add(new Delivery(recipient, item, count, gameTime));
        if (deliveries.size() <= MAX_DELIVERIES) return;
        deliveries.subList(0, deliveries.size() - MAX_DELIVERIES).clear();
    }

    /** Every delivery noted, oldest first. */
    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** How many of the item the body has handed to that player at or after {@code since} (the goal's own count). */
    public int deliveredSince(String recipient, ItemKey item, long since) {
        int total = 0;
        for (Delivery delivery : deliveries) {
            if (delivery.recipient().equals(recipient) && delivery.item().equals(item)
                    && delivery.gameTime() >= since) {
                total += delivery.count();
            }
        }
        return total;
    }

    // ---- danger zones ------------------------------------------------------------------------------------------

    /**
     * Records somewhere the body should keep out of for a while (issue 29). A zone that already covers the centre is
     * replaced, so the same place noted twice is one zone with the later lapse time; overlapping ones are both kept.
     */
    public void noteDangerZone(String dimension, BlockPos centre, double radius, long lapsesAt, String reason) {
        DangerZone zone = new DangerZone(dimension, centre, radius, lapsesAt, reason);
        dangerZones.removeIf(old -> old.covers(dimension, centre));
        dangerZones.add(zone);
    }

    /** Every zone that still counts at {@code now}, in the order noted; the ones that have lapsed are dropped. */
    public List<DangerZone> liveDangerZones(long now) {
        dangerZones.removeIf(zone -> !zone.live(now));
        return List.copyOf(dangerZones);
    }

    /** Whether a live zone of this dimension covers the position. */
    public boolean inDangerZone(String dimension, BlockPos at, long now) {
        for (DangerZone zone : liveDangerZones(now)) {
            if (zone.covers(dimension, at)) return true;
        }
        return false;
    }

    /** The zones remembered, lapsed ones included: for a status line. */
    public List<DangerZone> dangerZones() {
        return List.copyOf(dangerZones);
    }

    /**
     * The live zones as a phrase for a status line (issue 29), empty when there is none: where each stands, how wide
     * it is, how long it still counts and why it was written. Twenty ticks a second, as every other duration in a
     * status is said.
     */
    public String liveZonePhrase(long now) {
        List<DangerZone> zones = liveDangerZones(now);
        if (zones.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (DangerZone zone : zones) {
            if (text.length() > 0) text.append(", ");
            text.append(zone.centre().toShortString()).append(" radius ").append((int) Math.ceil(zone.radius()))
                .append(", ").append(Math.max(0, zone.lapsesAt() - now) / 20).append("s left (")
                .append(zone.reason()).append(')');
        }
        return text.toString();
    }

    // ---- the sight ---------------------------------------------------------------------------------------------

    /** The scan {@link BodySight} kept for this body, or null when it has taken none. */
    BodySight.Taken sight() {
        return sight;
    }

    void keepSight(BodySight.Taken taken) {
        sight = taken;
        sightScans++;
    }

    /** How many scans this body has had kept; a snapshot that reads the kept one adds none. */
    int sightScans() {
        return sightScans;
    }

    // ---- persistence -------------------------------------------------------------------------------------------

    /** The compact form for the body's save data: schema, placed positions per dimension, containers with contents. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SAVE_SCHEMA);

        CompoundTag placedTag = new CompoundTag();
        // Sorted so that two saves of one memory are equal and a diff of the player data stays readable.
        for (Map.Entry<String, Set<Long>> entry : new TreeMap<>(placed).entrySet()) {
            long[] positions = entry.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            placedTag.putLongArray(entry.getKey(), positions);
        }
        tag.put("placed", placedTag);

        ListTag containerList = new ListTag();
        for (RememberedContainer container : containers.values()) {
            CompoundTag one = new CompoundTag();
            one.putString("dimension", container.dimension());
            one.putLong("pos", container.pos().asLong());
            if (container.other() != null) one.putLong("other", container.other().asLong());
            one.putString("block", container.block());
            ListTag items = new ListTag();
            for (Map.Entry<ItemKey, Integer> item : new TreeMap<>(container.contents()).entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("id", item.getKey().id());
                entry.putInt("count", item.getValue());
                items.add(entry);
            }
            one.put("items", items);
            containerList.add(one);
        }
        tag.put("containers", containerList);

        // Absent when there is none, so a body that was never given one saves what it always did.
        if (!supply.isEmpty()) {
            ListTag supplyList = new ListTag();
            for (SupplyContainer entry : supply.values()) {
                CompoundTag one = new CompoundTag();
                one.putString("dimension", entry.dimension());
                one.putLong("pos", entry.pos().asLong());
                if (entry.other() != null) one.putLong("other", entry.other().asLong());
                one.putString("block", entry.block());
                supplyList.add(one);
            }
            tag.put("supply", supplyList);
        }
        // The same: a body that has never opened a trade window saves what it always did.
        if (!villagerOffers.isEmpty()) {
            ListTag offerList = new ListTag();
            for (VillagerOffers noted : villagerOffers.values()) {
                CompoundTag one = new CompoundTag();
                one.putString("dimension", noted.dimension());
                one.putUUID("villager", noted.villager());
                one.putLong("pos", noted.pos().asLong());
                ListTag offers = new ListTag();
                for (NotedOffer offer : noted.offers()) offers.add(offerTag(offer));
                one.put("offers", offers);
                offerList.add(one);
            }
            tag.put("villagerOffers", offerList);
        }
        // Deliveries are saved like everything else the body knows, and read back oldest first (issue #19).
        if (!deliveries.isEmpty()) {
            ListTag deliveryList = new ListTag();
            for (Delivery delivery : deliveries) {
                CompoundTag one = new CompoundTag();
                one.putString("recipient", delivery.recipient());
                one.putString("item", delivery.item().id());
                one.putInt("count", delivery.count());
                one.putLong("gameTime", delivery.gameTime());
                deliveryList.add(one);
            }
            tag.put("deliveries", deliveryList);
        }
        // Danger zones are saved with the brain like everything else the body knows about the world, and dropped by
        // the readers when they lapse (issue 29). Absent when there is none.
        if (!dangerZones.isEmpty()) {
            ListTag zoneList = new ListTag();
            for (DangerZone zone : dangerZones) {
                CompoundTag one = new CompoundTag();
                one.putString("dimension", zone.dimension());
                one.putLong("centre", zone.centre().asLong());
                one.putDouble("radius", zone.radius());
                one.putLong("lapsesAt", zone.lapsesAt());
                one.putString("reason", zone.reason());
                zoneList.add(one);
            }
            tag.put("dangerZones", zoneList);
        }
        return tag;
    }

    /** One offer as a compound: what it hands over, its facts, what it charges, and when the window was open. */
    private static CompoundTag offerTag(NotedOffer offer) {
        CompoundTag one = new CompoundTag();
        one.putString("result", offer.result().id());
        one.putString("pos", offer.pos().encode());
        one.putLong("gameTime", offer.gameTime());
        CompoundTag facts = new CompoundTag();
        for (Map.Entry<String, Integer> enchantment : new TreeMap<>(offer.facts().enchantments()).entrySet()) {
            facts.putInt(enchantment.getKey(), enchantment.getValue());
        }
        if (!offer.facts().potion().isEmpty()) one.putString("potion", offer.facts().potion());
        one.put("enchantments", facts);
        ListTag charge = new ListTag();
        for (Map.Entry<ItemKey, Integer> entry : new TreeMap<>(offer.charge()).entrySet()) {
            CompoundTag cost = new CompoundTag();
            cost.putString("id", entry.getKey().id());
            cost.putInt("count", entry.getValue());
            charge.add(cost);
        }
        one.put("charge", charge);
        return one;
    }

    /**
     * Reads what {@link #save()} wrote and makes it the memory of that body, replacing any it had: {@link #of} answers
     * with the restored one from then on. A missing or malformed part is skipped, never thrown: a bad save must not
     * keep a body from starting (design 3.4, Persistence), and losing a memory only costs the bot a second look.
     *
     * @return the installed memory
     */
    public static BotMemory restore(UUID body, CompoundTag tag) {
        BotMemory memory = load(tag);
        BY_BODY.put(body, memory);
        return memory;
    }

    private static BotMemory load(CompoundTag tag) {
        BotMemory memory = new BotMemory();
        if (tag.contains("placed", Tag.TAG_COMPOUND)) {
            CompoundTag placedTag = tag.getCompound("placed");
            for (String dimension : placedTag.getAllKeys()) {
                if (!placedTag.contains(dimension, Tag.TAG_LONG_ARRAY)) continue;
                Set<Long> positions = memory.placed.computeIfAbsent(dimension, d -> new HashSet<>());
                for (long pos : placedTag.getLongArray(dimension)) positions.add(pos);
            }
        }
        if (tag.contains("containers", Tag.TAG_LIST)) {
            ListTag list = tag.getList("containers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.contains("pos", Tag.TAG_LONG)
                    || !one.contains("block", Tag.TAG_STRING)) continue;
                Map<ItemKey, Integer> contents = new HashMap<>();
                ListTag items = one.getList("items", Tag.TAG_COMPOUND);
                for (int j = 0; j < items.size(); j++) {
                    CompoundTag entry = items.getCompound(j);
                    if (entry.contains("id", Tag.TAG_STRING) && entry.getInt("count") > 0) {
                        contents.merge(ItemKey.of(entry.getString("id")), entry.getInt("count"), Integer::sum);
                    }
                }
                BlockPos other = one.contains("other", Tag.TAG_LONG) ? BlockPos.of(one.getLong("other")) : null;
                memory.noteContainer(one.getString("dimension"), BlockPos.of(one.getLong("pos")), other, one.getString("block"), contents);
            }
        }
        if (tag.contains("supply", Tag.TAG_LIST)) {
            ListTag list = tag.getList("supply", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                // An entry with no block is not a word about any block: skipped like any other damaged part.
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.contains("pos", Tag.TAG_LONG)
                    || !one.contains("block", Tag.TAG_STRING)) continue;
                BlockPos other = one.contains("other", Tag.TAG_LONG) ? BlockPos.of(one.getLong("other")) : null;
                // Not addSupply: that forgets the notes of the container, which were just restored.
                memory.putSupply(new SupplyContainer(one.getString("dimension"), BlockPos.of(one.getLong("pos")), other,
                    one.getString("block")));
            }
        }
        if (tag.contains("villagerOffers", Tag.TAG_LIST)) {
            ListTag list = tag.getList("villagerOffers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.hasUUID("villager")
                    || !one.contains("pos", Tag.TAG_LONG)) continue;
                List<NotedOffer> offers = new ArrayList<>();
                ListTag offerList = one.getList("offers", Tag.TAG_COMPOUND);
                for (int j = 0; j < offerList.size(); j++) {
                    NotedOffer offer = offerOf(offerList.getCompound(j), one.getUUID("villager"));
                    if (offer != null) offers.add(offer);
                }
                memory.noteOffers(one.getString("dimension"), one.getUUID("villager"),
                    BlockPos.of(one.getLong("pos")), offers);
            }
        }
        if (tag.contains("deliveries", Tag.TAG_LIST)) {
            ListTag list = tag.getList("deliveries", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.contains("recipient", Tag.TAG_STRING) || !one.contains("item", Tag.TAG_STRING)
                    || one.getInt("count") <= 0) continue;
                memory.deliveries.add(new Delivery(one.getString("recipient"), ItemKey.of(one.getString("item")),
                    one.getInt("count"), one.getLong("gameTime")));
            }
        }
        if (tag.contains("dangerZones", Tag.TAG_LIST)) {
            ListTag list = tag.getList("dangerZones", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (!one.contains("dimension", Tag.TAG_STRING) || !one.contains("centre", Tag.TAG_LONG)
                    || !one.contains("reason", Tag.TAG_STRING)) continue;
                memory.dangerZones.add(new DangerZone(one.getString("dimension"), BlockPos.of(one.getLong("centre")),
                    one.contains("radius", Tag.TAG_DOUBLE) ? one.getDouble("radius") : 0.0, one.getLong("lapsesAt"),
                    one.getString("reason")));
            }
        }
        return memory;
    }

    /** One offer read back under its villager's id; null when the compound does not hold one. */
    @Nullable
    private static NotedOffer offerOf(CompoundTag one, UUID villager) {
        if (!one.contains("result", Tag.TAG_STRING) || !one.contains("pos", Tag.TAG_STRING)) return null;
        Pos pos;
        try {
            pos = Pos.parse(one.getString("pos"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Integer> enchantments = new HashMap<>();
        CompoundTag facts = one.getCompound("enchantments");
        for (String id : facts.getAllKeys()) enchantments.put(id, facts.getInt(id));
        Map<ItemKey, Integer> charge = new HashMap<>();
        ListTag chargeList = one.getList("charge", Tag.TAG_COMPOUND);
        for (int i = 0; i < chargeList.size(); i++) {
            CompoundTag cost = chargeList.getCompound(i);
            if (cost.contains("id", Tag.TAG_STRING) && cost.getInt("count") > 0) {
                charge.put(ItemKey.of(cost.getString("id")), cost.getInt("count"));
            }
        }
        StackFacts stackFacts = new StackFacts(1, enchantments,
            one.contains("potion", Tag.TAG_STRING) ? one.getString("potion") : "", Map.of());
        return new NotedOffer(villager, pos, ItemKey.of(one.getString("result")), stackFacts, charge,
            one.getLong("gameTime"));
    }

    /** What the memory holds, for a log line or a failed assertion. */
    @Override
    public String toString() {
        return "BotMemory(" + placedCount() + " placed blocks, " + containers.size() + " containers, " + supply.size()
            + " supply containers, " + villagerOffers.size() + " villagers' offers, " + deliveries.size()
            + " deliveries, " + dangerZones.size() + " danger zones)";
    }
}
