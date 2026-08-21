package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** One exact item/NBT identity and its total quantity in a storage snapshot. */
public final class StoredItem {
    private final StorageItemKey key;
    private final long amount;

    public StoredItem(StorageItemKey key, long amount) {
        this.key = Objects.requireNonNull(key, "key");
        if (amount <= 0) throw new IllegalArgumentException("stored amount must be positive");
        this.amount = amount;
    }

    public StorageItemKey key() { return key; }

    public ItemStack stack() { return key.displayStack(); }

    public long amount() {
        return amount;
    }

}
