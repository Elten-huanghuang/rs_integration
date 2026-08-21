package com.huanghuang.rsintegration.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;
import java.util.Set;

/**
 * Authorized live view of one backend network; implementations own native handles.
 * Every method must be called on the Minecraft server thread. Async planning may only retain
 * immutable {@link StorageSnapshot} data and the session's {@link StorageReference}.
 * Operation results must report the supplied simulate flag through {@link StorageOperationResult#mode()}.
 */
public interface StorageSession {
    StorageReference reference();

    default Set<StorageCapability> capabilities() {
        return Set.of(StorageCapability.ITEM_STORAGE);
    }

    default boolean supports(StorageCapability capability) {
        return capabilities().contains(Objects.requireNonNull(capability, "capability"));
    }

    StorageSnapshotResult snapshotItems(ServerPlayer player);

    StoragePermissionResult checkPermission(ServerPlayer player, StoragePermission permission);

    default boolean hasPermission(ServerPlayer player, StoragePermission permission) {
        return checkPermission(player, permission).allowedAccess();
    }

    default boolean hasExact(ServerPlayer player, StorageItemKey key, long amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        if (amount == 0) return true;
        StorageSnapshotResult result = snapshotItems(player);
        return result.snapshot().map(snapshot -> snapshot.countExact(key) >= amount).orElse(false);
    }

    StorageOperationResult extractExact(ServerPlayer player, StorageItemKey key, long amount, boolean simulate);

    StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient, long amount, boolean simulate);

    StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate);
}
