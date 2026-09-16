package com.huanghuang.rsintegration.storage;

import net.minecraft.world.item.ItemStack;

/** Receives authoritative absolute item counts from a live storage backend. */
public interface StorageItemChangeListener {
    void onChanged(ItemStack stack, long amount);

    /** The native cache/storage handle was rebuilt and must be rebound. */
    default void onInvalidated() {}
}
