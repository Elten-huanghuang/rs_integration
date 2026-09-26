package com.huanghuang.rsintegration.storage;
import java.lang.reflect.Method;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.List;
import net.minecraft.world.item.Item;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authorized live view of one backend network; implementations own native handles.
 * Every method must be called on the Minecraft server thread. Async planning may only retain
 * immutable {@link StorageSnapshot} data and the session's {@link StorageReference}.
 * Successful snapshots must use the same backend id as {@link #reference()}.
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

    /** Maps a native stack to this backend's authoritative item identity. */
    default StorageItemKey itemKey(ItemStack stack) {
        return StorageItemKey.fromItemStack(reference().backendId(), stack);
    }

    StorageSnapshotResult snapshotItems(ServerPlayer player);

    /**
     * Subscribes to absolute item-count changes without exposing backend-native types.
     * Unsupported backends return empty and may be handled by a bounded polling fallback.
     */
    default Optional<StorageItemSubscription> subscribeItemChanges(StorageItemChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        return Optional.empty();
    }

    /** Fresh candidates including all NBT variants; null requests all item types. */
    default StorageSnapshotResult snapshotItems(ServerPlayer player,
                                                Set<Item> itemTypes) {
        return snapshotItems(player);
    }

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

    /**
     * Converts stored fluid plus empty containers into filled container items.
     * Backends that do not expose typed fluid storage leave this unsupported.
     */
    default StorageOperationResult extractContainerFluid(ServerPlayer player,
                                                          ItemStack emptyContainer,
                                                          ItemStack filledContainer,
                                                          long amount,
                                                          boolean simulate) {
        return StorageOperationResult.failedExtraction(
                simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                amount, StorageOperationStatus.UNAVAILABLE, List.of(), List.of());
    }

    /** Reverses a previously committed container conversion during rollback. */
    default StorageOperationResult restoreContainerFluid(ServerPlayer player,
                                                          ItemStack emptyContainer,
                                                          ItemStack filledContainer,
                                                          long amount) {
        return StorageOperationResult.failedInsert(StorageOperationMode.PERFORM,
                filledContainer, StorageOperationStatus.UNAVAILABLE);
    }

    /**
     * Returns how many filled containers can be derived from this backend's
     * stored empty containers and typed fluid inventory.  This is a planning
     * hint only; commit-time extraction must still use
     * {@link #extractContainerFluid(ServerPlayer, ItemStack, ItemStack, long, boolean)}.
     */
    default long countDerivedContainer(ServerPlayer player, ItemStack filledContainer) {
        return 0L;
    }

    StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate);
}
