package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable-snapshot reservation view used by recursive planning.
 * Physical extraction is deliberately outside this class.
 */
public final class CraftStorageReservationView {
    private final StorageSnapshot snapshot;
    private final Map<StorageItemKey, Long> reserved = new HashMap<>();

    public CraftStorageReservationView(@Nonnull StorageSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public StorageSnapshot snapshot() {
        return snapshot;
    }

    public long availableExact(@Nonnull StorageItemKey key) {
        return Math.max(0L, snapshot.countExact(key) - reserved.getOrDefault(key, 0L));
    }

    public ItemStack reserveExact(@Nonnull ItemStack template, long amount) {
        if (amount <= 0 || template.isEmpty()) return ItemStack.EMPTY;
        StorageItemKey key = StorageItemKey.fromItemStack(snapshot.backendId(), template);
        if (availableExact(key) < amount) return ItemStack.EMPTY;
        reserve(key, amount);
        return template.copyWithCount((int) Math.min(Integer.MAX_VALUE, amount));
    }

    public ItemStack reserveMatching(@Nonnull Ingredient ingredient, int amount) {
        if (amount <= 0 || ingredient.isEmpty()) return ItemStack.EMPTY;
        for (StoredItem stored : snapshot.match(ingredient).items()) {
            long available = availableExact(stored.key());
            if (available <= 0) continue;
            int take = (int) Math.min((long) amount, available);
            reserve(stored.key(), take);
            return stored.stack().copyWithCount(take);
        }
        return ItemStack.EMPTY;
    }

    public void release(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) return;
        StorageItemKey key = StorageItemKey.fromItemStack(snapshot.backendId(), stack);
        long current = reserved.getOrDefault(key, 0L);
        long next = Math.max(0L, current - stack.getCount());
        if (next == 0) reserved.remove(key); else reserved.put(key, next);
    }

    private void reserve(StorageItemKey key, long amount) {
        reserved.merge(key, amount, (left, right) ->
                Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right);
    }
}
