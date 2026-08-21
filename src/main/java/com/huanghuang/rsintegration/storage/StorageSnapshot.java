package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable point-in-time view of the item identities visible in a session. */
public final class StorageSnapshot {
    public static final long UNKNOWN_REVISION = -1;
    public static final StorageSnapshot EMPTY = new StorageSnapshot(List.of(), UNKNOWN_REVISION);

    private final List<StoredItem> items;
    private final long revision;

    public StorageSnapshot(List<StoredItem> items) {
        this(items, UNKNOWN_REVISION);
    }

    public StorageSnapshot(List<StoredItem> items, long revision) {
        Objects.requireNonNull(items, "items");
        if (revision < UNKNOWN_REVISION) throw new IllegalArgumentException("invalid snapshot revision");
        Map<StorageItemKey, Long> amounts = new LinkedHashMap<>();
        for (StoredItem item : items) {
            Objects.requireNonNull(item, "item");
            amounts.merge(item.key(), item.amount(), StorageSnapshot::saturatedAdd);
        }
        List<StoredItem> normalized = new ArrayList<>(amounts.size());
        for (Map.Entry<StorageItemKey, Long> entry : amounts.entrySet()) {
            normalized.add(new StoredItem(entry.getKey(), entry.getValue()));
        }
        this.items = List.copyOf(normalized);
        this.revision = revision;
    }

    public List<StoredItem> items() {
        return items;
    }

    public long revision() { return revision; }

    public long countExact(StorageItemKey key) {
        Objects.requireNonNull(key, "key");
        long total = 0;
        for (StoredItem item : items) {
            if (item.key().equals(key)) total = saturatedAdd(total, item.amount());
        }
        return total;
    }

    public List<StoredItem> matching(Ingredient ingredient) {
        Objects.requireNonNull(ingredient, "ingredient");
        if (ingredient.isEmpty()) return List.of();
        List<StoredItem> matches = new ArrayList<>();
        for (StoredItem item : items) {
            if (ingredient.test(item.stack())) {
                matches.add(item);
            }
        }
        return List.copyOf(matches);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
