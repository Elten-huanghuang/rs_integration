package com.huanghuang.rsintegration.api.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Public storage boundary for feature integrations; internal backend sessions never cross it. */
public interface RSIStorageApi {
    static Optional<RSIStorageApi> get() { return RSIStorageServices.get(); }
    int API_MAJOR = 1, API_MINOR = 0;
    Optional<StorageLink> defaultStorage(ServerPlayer player);
    Optional<StorageLink> resolve(ServerPlayer player, String backendId, String networkId);
    boolean isCurrentDefault(ServerPlayer player, StorageLink link);
    Optional<StorageSnapshot> snapshot(ServerPlayer player, StorageLink link, Set<Item> wanted);
    long countExact(ServerPlayer player, StorageLink link, ItemStack stack);
    long countSnapshot(StorageSnapshot snapshot, ItemStack stack);
    boolean hasTool(ServerPlayer player, StorageLink link, Item item);
    List<ItemStack> extractExact(ServerPlayer player, StorageLink link, ItemStack stack, int amount);
}
