package com.huanghuang.rsintegration.feature.goety.integration;

import com.huanghuang.rsintegration.api.storage.*;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Cauldron bindings use the public storage API; legacy world bindings remain readable. */
public final class RsiStorageLink {
    private static final String KEY = "goety_rsi_storage";
    private RsiStorageLink() {}
    public static boolean bound(BrewCauldronBlockEntity be) { return be.getPersistentData().contains(KEY); }
    public static boolean bind(ServerPlayer player, BrewCauldronBlockEntity be) {
        var session = RSIStorageApi.get().flatMap(api -> api.defaultStorage(player));
        if (session.isEmpty()) return false;
        var tag = new CompoundTag();
        tag.putString("backend", session.get().backendId()); tag.putString("network", session.get().networkId());
        be.getPersistentData().put(KEY, tag); be.markUpdated(); return true;
    }
    public static void unbind(BrewCauldronBlockEntity be) { be.getPersistentData().remove(KEY); be.markUpdated(); }
    public static Optional<StorageLink> resolve(ServerPlayer player, BrewCauldronBlockEntity be) {
        if (!bound(be)) return Optional.empty();
        var tag = be.getPersistentData().getCompound(KEY);
        return RSIStorageApi.get().flatMap(api -> api.resolve(player, tag.getString("backend"), tag.getString("network")));
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
}
