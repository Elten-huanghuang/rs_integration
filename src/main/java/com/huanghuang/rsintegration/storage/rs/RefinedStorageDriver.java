package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StoragePermission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Replaceable boundary around native RS calls. This interface deliberately exposes no RS types. */
interface RefinedStorageDriver {
    RefinedStorageSnapshotRead snapshotItems();

    boolean hasPermission(ServerPlayer player, StoragePermission permission);

    ItemStack extract(ItemStack template, int amount, boolean simulate);

    ItemStack insert(ItemStack stack, boolean simulate);

    /** Records the preflight accepted amount for RS change tracking; never use it for settlement. */
    void recordInsertion(ServerPlayer player, ItemStack accepted);
}
