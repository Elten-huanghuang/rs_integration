package com.huanghuang.rsintegration.crafting.endpoint;

import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageNetworkLocation;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage boundary used by recursive crafting.
 *
 * <p>The resolver and ledger must depend on this boundary rather than a native
 * storage-mod network object. A backend owns the concrete {@link StorageSession};
 * recursive crafting only sees validated item operations and structured results.</p>
 */
public interface CraftStorageEndpoint {
    StorageSession session();

    default boolean isAvailable() {
        StorageSession current = session();
        return current != null && current.isAvailable();
    }

    default Optional<StorageNetworkLocation> location() {
        StorageSession current = session();
        return current == null ? Optional.empty() : current.location();
    }

    default StorageOperationResult insert(@Nonnull ItemStack stack, boolean simulate) {
        return StorageOperationResult.failedInsert(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                stack, StorageOperationStatus.UNAVAILABLE);
    }

    default StorageSnapshotResult snapshot(@Nonnull ServerPlayer player) {
        return session().snapshotItems(Objects.requireNonNull(player, "player"));
    }

    default StorageSnapshotResult snapshot(@Nonnull ServerPlayer player,
                                           Set<Item> itemTypes) {
        return itemTypes == null ? snapshot(player)
                : session().snapshotItems(Objects.requireNonNull(player, "player"), itemTypes);
    }

    default StorageOperationResult extractExact(@Nonnull ServerPlayer player,
                                                @Nonnull ItemStack template,
                                                long amount, boolean simulate) {
        Objects.requireNonNull(template, "template");
        return session().extractExact(player, session().itemKey(template), amount, simulate);
    }

    default StorageOperationResult extractMatching(@Nonnull ServerPlayer player,
                                                   @Nonnull Ingredient ingredient,
                                                   long amount, boolean simulate) {
        return session().extractMatching(player, ingredient, amount, simulate);
    }

    default StorageOperationResult extractExact(@Nonnull Player player,
                                                @Nonnull ItemStack template,
                                                long amount, boolean simulate) {
        if (player instanceof ServerPlayer serverPlayer) return extractExact(serverPlayer, template, amount, simulate);
        return StorageOperationResult.failedExtraction(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                amount, StorageOperationStatus.UNAVAILABLE,
                List.of(), List.of());
    }

    default StorageOperationResult insert(@Nonnull ServerPlayer player,
                                          @Nonnull ItemStack stack, boolean simulate) {
        return session().insert(player, stack, simulate);
    }

    /** Generic player context used by backpack/fake-player integrations. */
    default StorageOperationResult insert(@Nonnull Player player,
                                          @Nonnull ItemStack stack, boolean simulate) {
        if (player instanceof ServerPlayer serverPlayer) return insert(serverPlayer, stack, simulate);
        return StorageOperationResult.failedInsert(
                simulate ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM,
                stack, StorageOperationStatus.UNAVAILABLE);
    }
}
