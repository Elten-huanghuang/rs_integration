package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StoragePermission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.Set;
import net.minecraft.world.item.Item;

import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import java.util.Optional;

/** Replaceable boundary around native RS calls. This interface deliberately exposes no RS types. */
interface RefinedStorageDriver {
    boolean isAvailable();

    RefinedStorageSnapshotRead snapshotItems();

    default Optional<StorageItemSubscription> subscribeItemChanges(StorageItemChangeListener listener) {
        return Optional.empty();
    }

    /** Optional candidate filter. Drivers may return a superset; matching remains authoritative. */
    default RefinedStorageSnapshotRead snapshotItems(Set<Item> itemTypes) {
        return snapshotItems();
    }

    boolean hasPermission(ServerPlayer player, StoragePermission permission);

    ItemStack extract(ItemStack template, int amount, boolean simulate);

    ItemStack insert(ItemStack stack, boolean simulate);

    /** Records the preflight accepted amount for RS change tracking; never use it for settlement. */
    void recordInsertion(ServerPlayer player, ItemStack accepted);
}
