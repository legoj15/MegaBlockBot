package com.legoj15.megablockbot.game.build;

import com.legoj15.megablockbot.MegaBlockBot;
import com.legoj15.megablockbot.platform.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Where blueprints come from (issue 17). A name is looked up first as {@code <config dir>/megablockbot/blueprints/
 * <name>.nbt} (compressed NBT, as the game writes structures) and then, when it parses as a resource location, as one
 * of the server's structure templates. A file name has no path separator, {@code ..} or colon, so a name with a colon
 * can only be a template id ({@code minecraft:igloo/top}). A name found nowhere, or a file this brief cannot build
 * from, is unknown: the snapshot then holds no report for it and the planner says the goal cannot be planned.
 *
 * <p>A blueprint keeps the box it was saved from: its size is the template's declared one, air margin included, so a
 * box saved with an empty margin builds back where the owner pointed. Loaded blueprints are cached per server and
 * dropped when the server's data is reloaded, when the server stops and when the file's modification time changes (the
 * file can be saved over while the server runs). Sizes and contents are bounded by {@link #MAX_BLOCKS} and
 * {@link #MAX_SIDE}; a larger one is refused with a message saying so.
 */
public final class Blueprints {
    /** At most this many blocks in a blueprint: what the builder can walk around and place in one goal. */
    public static final int MAX_BLOCKS = 4096;
    /** At most this many blocks along any side: a footprint the loaded chunks around a body still cover. */
    public static final int MAX_SIDE = 48;

    /** The folder under the loader's config directory: {@code <config dir>/megablockbot/blueprints}. */
    private static final String FOLDER = "megablockbot/blueprints";

    private record Key(MinecraftServer server, String name) {}

    private record Cached(Blueprint blueprint, long stamp) {}

    private static final Map<Key, Cached> CACHE = new ConcurrentHashMap<>();
    /** Blueprints a test registered by hand ({@link #register}); consulted before every file and template. */
    private static final Map<String, Blueprint> TESTS = new ConcurrentHashMap<>();

    private Blueprints() {}

    /** A name is a file name only: one path segment, no {@code ..} and no colon (no namespace, no path tricks). */
    public static boolean validName(String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
            return false;
        }
        return name.equals(name.trim());
    }

    /**
     * The blueprint of that name for that server, or null when there is none this brief can build. A refusal says why
     * in the log, naming the blueprint and the block. A name with a colon is no file name (see {@link #validName}), so
     * it goes to the server's structure templates when it parses as a resource location.
     */
    @Nullable
    public static Blueprint load(MinecraftServer server, String name) {
        Blueprint test = TESTS.get(name);
        if (test != null) return test;

        if (validName(name)) {
            Path file = folder().resolve(name + ".nbt");
            if (Files.exists(file)) {
                long modified;
                try {
                    modified = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    modified = -1L;
                }
                Cached cached = CACHE.get(new Key(server, name));
                if (cached != null && cached.stamp() == modified) return cached.blueprint();
                Blueprint blueprint = fromFile(server, file, name);
                if (blueprint != null) CACHE.put(new Key(server, name), new Cached(blueprint, modified));
                return blueprint;
            }
            return null; // a file name is not a template id: no colon, no namespace
        }

        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) return null;
        Optional<StructureTemplate> template = server.getStructureManager().get(id);
        if (template.isEmpty()) return null;
        Blueprint blueprint = fromTemplate(server, template.get(), name);
        if (blueprint != null) CACHE.put(new Key(server, name), new Cached(blueprint, -1L));
        return blueprint;
    }

    /** The blueprint folder, made when it does not exist (the save command is its usual creator). */
    public static Path folder() {
        return Platform.loader().configDir().resolve(FOLDER);
    }

    /** The names of the blueprints in the folder, sorted; what {@code /aiplayer blueprint list} says. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        try (var files = Files.list(folder())) {
            files.forEach(file -> {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".nbt")) names.add(fileName.substring(0, fileName.length() - 4));
            });
        } catch (IOException e) {
            // No folder yet, or one that cannot be read: there is nothing to list.
        }
        return names.stream().sorted().toList();
    }

    /**
     * Saves the blocks of a box of the world as {@code <config dir>/megablockbot/blueprints/<name>.nbt}, in the
     * structure-template format (the game's own {@link StructureTemplate#fillFromWorld} and {@link StructureTemplate#save}),
     * without entities, compressed as the game writes structures. The box is looked at before anything is written: a
     * box the loader would refuse (nothing to build, or a block one item cannot place) is refused here, naming the
     * block and where it stands, and no file is written.
     *
     * @return null on success, else the reason (a bad name, a box past the limits, a box that cannot be built from, a
     *         write that failed)
     */
    @Nullable
    public static String save(ServerLevel level, String name, BlockPos from, BlockPos to) {
        if (!validName(name)) {
            return "'" + name + "' is not a blueprint name: a file name only, no path, '..' or colon";
        }
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()));
        int w = Math.abs(from.getX() - to.getX()) + 1;
        int h = Math.abs(from.getY() - to.getY()) + 1;
        int d = Math.abs(from.getZ() - to.getZ()) + 1;
        String tooBig = sizeProblem(w, h, d);
        if (tooBig != null) return tooBig;
        String unbuildable = firstUnbuildable(level, min, w, h, d);
        if (unbuildable != null) return unbuildable;
        try {
            Files.createDirectories(folder());
        } catch (IOException e) {
            return "could not create the blueprint folder: " + e.getMessage();
        }
        // Without entities, with no block filtered out: a plain box, the corner its anchor.
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, min, new net.minecraft.core.Vec3i(w, h, d), false, null);
        CompoundTag tag = new CompoundTag();
        template.save(tag);
        Path file = folder().resolve(name + ".nbt");
        try {
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            return "could not write " + file + ": " + e.getMessage();
        }
        CACHE.remove(new Key(level.getServer(), name)); // the file's time decides from here on
        return null;
    }

    /**
     * The first block of the box this brief cannot build from and where it stands, or null when the box is buildable.
     * The box is read in order from its min corner, so the first refusal is a stable one.
     */
    @Nullable
    private static String firstUnbuildable(ServerLevel level, BlockPos min, int w, int h, int d) {
        boolean any = false;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    at.setWithOffset(min, x, y, z);
                    BlockState state = level.getBlockState(at);
                    if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
                    any = true;
                    String why = Blueprint.unbuildable(state);
                    if (why != null) return "the box holds " + why + ", at " + at.toShortString();
                }
            }
        }
        return any ? null : "the box holds no block to build";
    }

    /** Why a box of this size cannot be a blueprint, or null when it can. */
    @Nullable
    public static String sizeProblem(int w, int h, int d) {
        int side = Math.max(w, Math.max(h, d));
        if (side > MAX_SIDE) {
            return "a blueprint may be at most " + MAX_SIDE + " blocks along a side; this one is " + side;
        }
        if ((long) w * h * d > MAX_BLOCKS) {
            return "a blueprint may hold at most " + MAX_BLOCKS + " blocks; this box is " + w + "x" + h + "x" + d;
        }
        return null;
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    /** The most bytes of NBT a blueprint file may unpack to: 32 MiB, a few times the largest blueprint allowed. */
    private static final long MAX_NBT_BYTES = 1L << 25;

    @Nullable
    private static Blueprint fromFile(MinecraftServer server, Path file, String name) {
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
            // The game's own read is inside the same catch: whatever it throws on a file that is not a structure
            // becomes "not known", not an exception in the snapshot or the save command.
            return fromTemplate(server, read(server, tag), name);
        } catch (IOException | RuntimeException e) {
            MegaBlockBot.LOGGER.warn("Blueprint '{}' could not be read from {}: {}", name, file, e.toString());
            return null;
        }
    }

    /** The game's own reader: the compound is loaded with the registries the way the game loads a structure. */
    private static StructureTemplate read(MinecraftServer server, CompoundTag tag) {
        HolderGetter<net.minecraft.world.level.block.Block> blocks =
                server.registryAccess().lookupOrThrow(Registries.BLOCK);
        StructureTemplate template = new StructureTemplate();
        template.load(blocks, tag);
        return template;
    }

    @Nullable
    private static Blueprint fromTemplate(MinecraftServer server, StructureTemplate template, String name) {
        // The declared size is what the blueprint spans (the box it was saved from, margin included), so this is the
        // limit check on what it really spans.
        String sizeProblem = sizeProblem(template.getSize().getX(), template.getSize().getY(), template.getSize().getZ());
        if (sizeProblem != null) {
            MegaBlockBot.LOGGER.warn("Blueprint '{}': {}", name, sizeProblem);
            return null;
        }
        try {
            List<Blueprint.Entry> entries = entries(server, template.save(new CompoundTag()), template.getSize());
            if (entries.size() > MAX_BLOCKS) {
                MegaBlockBot.LOGGER.warn("Blueprint '{}': it holds {} blocks, more than the {} allowed", name,
                        entries.size(), MAX_BLOCKS);
                return null;
            }
            return new Blueprint(name, entries, template.getSize().getX(), template.getSize().getY(),
                    template.getSize().getZ());
        } catch (IllegalArgumentException e) {
            MegaBlockBot.LOGGER.warn("Blueprint '{}' is not a blueprint this brief can build: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * The blocks of a template, read from the lists of its own save form. The template keeps its palettes private, and
     * its public filter answers for one named block only (asked for none it finds none, and NeoForge's palette cache
     * throws on the null): the save form is the stable datapack format, so it is read as data. Positions are relative
     * to the template's corner; air and structure void are left out, since a blueprint is the blocks that stand.
     *
     * @throws IllegalArgumentException when the file is not a structure this brief can build: a palette that yields no
     *                                  state, a block list that yields no block, or a block outside the declared size
     */
    private static List<Blueprint.Entry> entries(MinecraftServer server, CompoundTag tag, Vec3i size) {
        HolderGetter<net.minecraft.world.level.block.Block> blocks =
                server.registryAccess().lookupOrThrow(Registries.BLOCK);
        // A template with several palettes (vanilla's shipwrecks) saves them as a list of lists; the first is the one
        // a blueprint is built from.
        ListTag palette = tag.contains(StructureTemplate.PALETTE_LIST_TAG, Tag.TAG_LIST)
                ? tag.getList(StructureTemplate.PALETTE_LIST_TAG, Tag.TAG_LIST).getList(0)
                : tag.getList(StructureTemplate.PALETTE_TAG, Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("it holds no palette of block states, so it is not a structure");
        }
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) states.add(NbtUtils.readBlockState(blocks, palette.getCompound(i)));
        List<Blueprint.Entry> entries = new ArrayList<>();
        ListTag saved = tag.getList(StructureTemplate.BLOCKS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag block = saved.getCompound(i);
            ListTag pos = block.getList(StructureTemplate.BLOCK_TAG_POS, Tag.TAG_INT);
            int index = block.getInt(StructureTemplate.BLOCK_TAG_STATE);
            if (pos.size() != 3 || index < 0 || index >= states.size()) continue;
            BlockState state = states.get(index);
            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
            BlockPos at = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            if (at.getX() < 0 || at.getX() >= size.getX() || at.getY() < 0 || at.getY() >= size.getY()
                    || at.getZ() < 0 || at.getZ() >= size.getZ()) {
                throw new IllegalArgumentException("it holds a block at " + at.toShortString() + ", outside the "
                        + size.getX() + "x" + size.getY() + "x" + size.getZ() + " it declares");
            }
            entries.add(new Blueprint.Entry(at, state));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("none of its blocks is one to build (air and structure void throughout)");
        }
        return entries;
    }

    // ---- the cache's life ---------------------------------------------------------------------------------------

    /** Takes the file of that name away and forgets what was cached for it; what the tests that save one call. */
    public static void remove(String name) {
        CACHE.keySet().removeIf(key -> key.name().equals(name));
        try {
            Files.deleteIfExists(folder().resolve(name + ".nbt"));
        } catch (IOException e) {
            // A file already gone is what was asked for; a folder that cannot be read holds nothing either.
        }
    }

    /** Drops every cached blueprint; called when the server's data is reloaded and when a server stops. */
    public static void clear() {
        CACHE.clear();
    }

    // ---- test hook ----------------------------------------------------------------------------------------------

    /**
     * A blueprint registered by hand, so a test needs no file: a name and the positions and states, refused by the same
     * rules as a loaded one. Removed with {@link #forget}, which the test calls however it ends.
     */
    public static Blueprint register(String name, List<Blueprint.Entry> entries) {
        Blueprint blueprint = new Blueprint(name, entries);
        TESTS.put(name, blueprint);
        return blueprint;
    }

    /** Takes a blueprint registered by hand out again. */
    public static void forget(String name) {
        TESTS.remove(name);
    }
}
