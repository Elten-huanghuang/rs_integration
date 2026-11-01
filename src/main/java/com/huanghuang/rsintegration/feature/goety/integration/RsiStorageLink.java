package com.huanghuang.rsintegration.feature.goety.integration;

import com.huanghuang.rsintegration.api.storage.*;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** 每次操作使用玩家当前默认网络，坩埚不保存独立存储绑定。 */
public final class RsiStorageLink {
    private RsiStorageLink() {}
    public static Optional<StorageLink> resolve(ServerPlayer player) {
        return RSIStorageApi.get().flatMap(api -> api.defaultStorage(player));
    }
    public static CraftStorageEndpoint endpoint(ServerPlayer player, StorageLink link) {
        if (link == null || !isCurrentDefault(player, link)) return null;
        return CraftStorageEndpoints.resolve(
                new StorageReference(new StorageBackendId(link.backendId()), link.networkId()), player).orElse(null);
    }
    public static boolean isCurrentDefault(ServerPlayer player, StorageLink link) {
        return RSIStorageApi.get().map(api -> api.isCurrentDefault(player, link)).orElse(false);
    }
    public static Optional<StorageSnapshot> snapshot(ServerPlayer player, StorageLink link, Set<Item> wanted) {
        return RSIStorageApi.get().flatMap(api -> api.snapshot(player, link, wanted));
    }
    public static long count(ServerPlayer player, StorageLink link, ItemStack item) {
        return RSIStorageApi.get().map(api -> api.countExact(player, link, item)).orElse(0L);
    }
    public static long countSnapshot(StorageLink link, Optional<StorageSnapshot> snapshot, ItemStack item) {
        if (link == null || snapshot.isEmpty()) return 0;
        return RSIStorageApi.get().map(api -> api.countSnapshot(snapshot.get(), item)).orElse(0L);
    }
    public static boolean hasTool(ServerPlayer player, StorageLink link, Item item) {
        return RSIStorageApi.get().map(api -> api.hasTool(player, link, item)).orElse(false);
    }
    public static List<ItemStack> extract(ServerPlayer player, StorageLink link, ItemStack item, int amount) {
        return RSIStorageApi.get().map(api -> api.extractExact(player, link, item, amount)).orElse(List.of());
    }
    public static void give(ServerPlayer player, ItemStack stack) {
        var remainder = stack.copy(); if (!player.getInventory().add(remainder)) player.drop(remainder, false);
    }
    public static int inventoryCount(ServerPlayer player, ItemStack item) {
        int count = 0;
        for (var stack : player.getInventory().items) {
            if (ItemStack.isSameItemSameTags(stack, item)) count += stack.getCount();
        }
        return count;
    }
}
