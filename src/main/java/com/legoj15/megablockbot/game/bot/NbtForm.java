package com.legoj15.megablockbot.game.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Converts the executor's save form to NBT and back (design 3.4, Persistence). The form is a tree of {@code String},
 * {@code Long}, {@code Double}, {@code Boolean}, {@code List} and {@code Map<String, ?>} with no nulls: the executor
 * never sees NBT, and this is the only place that does.
 *
 * <p>Each type has one tag, so the round trip is exact: String-String, Long-Long, Double-Double, Boolean-Byte, a
 * map is a compound. A list is a list of one-field compounds ({@code {v: element}}) rather than a bare NBT list,
 * because an NBT list holds one tag type and a save-form list may hold maps and strings side by side.
 *
 * <p>Writing is strict: anything outside the form throws {@link IllegalArgumentException}, so a bug in the save form is
 * found when it is saved and not when it is loaded. Reading is lenient about width only (an int or short is a long, a
 * float is a double), because a hand-edited or older file may have narrowed a number; a tag type the form never writes
 * (a byte array, say) throws.
 */
public final class NbtForm {
    private static final String LIST_ELEMENT = "v";

    private NbtForm() {}

    /** The save form's top-level map as a compound. */
    public static CompoundTag toTag(Map<String, Object> form) {
        return (CompoundTag) encode(form, "save");
    }

    /** The save form of a compound written by {@link #toTag}. */
    public static Map<String, Object> toMap(CompoundTag tag) {
        Object decoded = decode(tag, "save");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        return map;
    }

    private static Tag encode(Object value, String where) {
        if (value instanceof String s) return StringTag.valueOf(s);
        if (value instanceof Long l) return LongTag.valueOf(l);
        // Integer is not in the form, but the same number as a long is no loss: it reads back as a Long.
        if (value instanceof Integer i) return LongTag.valueOf(i.longValue());
        if (value instanceof Double d) return DoubleTag.valueOf(d);
        if (value instanceof Boolean b) return ByteTag.valueOf(b);
        if (value instanceof Map<?, ?> map) {
            CompoundTag compound = new CompoundTag();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(where + ": a map key is " + describe(entry.getKey()) + ", not a string");
                }
                compound.put(key, encode(entry.getValue(), where + "." + key));
            }
            return compound;
        }
        if (value instanceof List<?> list) {
            ListTag tags = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag element = new CompoundTag();
                element.put(LIST_ELEMENT, encode(list.get(i), where + "[" + i + "]"));
                tags.add(element);
            }
            return tags;
        }
        throw new IllegalArgumentException(where + ": " + describe(value) + " is not part of the save form");
    }

    private static Object decode(Tag tag, String where) {
        if (tag instanceof StringTag s) return s.getAsString();
        if (tag instanceof LongTag l) return l.getAsLong();
        if (tag instanceof DoubleTag d) return d.getAsDouble();
        if (tag instanceof ByteTag b) return b.getAsByte() != 0;
        if (tag instanceof IntTag || tag instanceof ShortTag) return ((NumericTag) tag).getAsLong();
        if (tag instanceof FloatTag f) return f.getAsDouble();
        if (tag instanceof CompoundTag compound) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : compound.getAllKeys()) {
                map.put(key, decode(compound.get(key), where + "." + key));
            }
            return map;
        }
        if (tag instanceof ListTag list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                if (!(element instanceof CompoundTag wrapper) || !wrapper.contains(LIST_ELEMENT)) {
                    throw new IllegalArgumentException(where + "[" + i + "] is not a list element");
                }
                out.add(decode(wrapper.get(LIST_ELEMENT), where + "[" + i + "]"));
            }
            return out;
        }
        throw new IllegalArgumentException(where + ": a " + tag.getType().getName() + " tag is not part of the save form");
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
