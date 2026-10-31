package com.huanghuang.rsintegration.api.storage;

import net.minecraft.world.item.ItemStack;

/** Short-lived server-thread view captured by {@link RSIStorageApi#snapshot}. */
@FunctionalInterface
public interface StorageSnapshot {
    long countExact(ItemStack stack);
}
