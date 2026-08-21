package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Application hook run before every native insert attempt; accepted is a preflight estimate only. */
@FunctionalInterface
public interface StorageInsertObserver {
    void beforePerform(ServerPlayer player, StorageReference reference, ItemStack acceptedEstimate);
}
