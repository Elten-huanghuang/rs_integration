package com.huanghuang.rsintegration.crafting;

import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CraftFailureNbt {
    static final int MAX_ENTRIES = 8;
    static final int MAX_ENTRY_CHARS = 2048;
    static final int MAX_TOTAL_CHARS = 8192;
    static final int MAX_VISITS = 256;
    static final int MAX_DEPTH = 8;
    static final String TRUNCATED = " [truncated]";
    private static final List<String> PRIORITY_KEYS = List.of("Damage", "Unbreakable", "Enchantments",
            "StoredEnchantments", "ISB_Spells", "Spells", "spell_container", "Food", "food",
            "RepairCost", "itemModifier");
    private final StringBuilder output = new StringBuilder();
    private final int capacity;
    private int visits;
    private boolean truncated;

    private CraftFailureNbt(int limit) { capacity = Math.max(0, limit - TRUNCATED.length()); }

    static String excerpt(Tag tag, int limit) {
        if (limit < TRUNCATED.length()) return "";
        CraftFailureNbt writer = new CraftFailureNbt(Math.min(limit, MAX_ENTRY_CHARS));
        writer.write(tag, 0);
        return writer.output + (writer.truncated ? TRUNCATED : "");
    }

    private boolean full() { return output.length() >= capacity || visits >= MAX_VISITS; }

    private void write(Tag tag, int depth) {
        if (tag == null) { append("null"); return; }
        if (full() || depth > MAX_DEPTH) { truncated = true; return; }
        visits++;
        if (tag instanceof CompoundTag compound) {
            append("{");
            List<String> keys = new ArrayList<>();
            for (String key : PRIORITY_KEYS) if (compound.contains(key)) keys.add(key);
            int scanned = 0;
            for (String key : compound.getAllKeys()) {
                if (scanned++ >= MAX_VISITS) { truncated = true; break; }
                if (!keys.contains(key)) keys.add(key);
            }
            keys.sort(Comparator.comparingInt(CraftFailureNbt::priority).thenComparing(Comparator.naturalOrder()));
            boolean first = true;
            for (String key : keys) {
                if (full()) { truncated = true; break; }
                if (!first) append(",");
                first = false;
                quoted(key);
                append(":");
                write(compound.get(key), depth + 1);
            }
            append("}");
        } else if (tag instanceof CollectionTag<?> collection) {
            append("[");
            for (int index = 0; index < collection.size(); index++) {
                if (full()) { truncated = true; break; }
                if (index > 0) append(",");
                write(collection.get(index), depth + 1);
            }
            append("]");
        } else if (tag instanceof StringTag string) {
            quoted(string.getAsString());
        } else if (tag instanceof NumericTag) {
            append(tag.getAsString());
        } else {
            append("?");
        }
    }

    private static int priority(String key) {
        String lower = key.substring(0, Math.min(key.length(), 128)).toLowerCase(Locale.ROOT);
        return PRIORITY_KEYS.contains(key) || lower.contains("spell") || lower.contains("enchant")
                || lower.contains("food") || lower.contains("damage") ? 0 : 1;
    }

    private void quoted(String value) {
        append("\"");
        for (int offset = 0; offset < value.length();) {
            if (output.length() >= capacity) { truncated = true; break; }
            int code = value.codePointAt(offset);
            if (capacity - output.length() < Character.charCount(code) + (code == '"' || code == '\\' ? 1 : 0)) {
                truncated = true;
                break;
            }
            offset += Character.charCount(code);
            if (code == '"' || code == '\\') append("\\");
            if (code == 0xA7 || Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) append(" ");
            else append(new String(Character.toChars(code)));
        }
        append("\"");
    }

    private void append(String text) {
        int available = capacity - output.length();
        int end = Math.min(available, text.length());
        if (end < text.length()) {
            truncated = true;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        }
        output.append(text, 0, end);
    }
}
