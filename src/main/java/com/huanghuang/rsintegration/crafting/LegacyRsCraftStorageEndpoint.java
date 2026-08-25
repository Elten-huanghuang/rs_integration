package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Transitional adapter for callers that still hold an RS native network.
 * New crafting code must depend on {@link CraftStorageEndpoint}; this class
 * is the only compatibility bridge that retains the native RS handle.
 */
final class LegacyRsCraftStorageEndpoint implements CraftStorageEndpoint {
    private final INetwork network;
    private final StorageSession session;

    LegacyRsCraftStorageEndpoint(INetwork network) {
        this.network = Objects.requireNonNull(network, "network");
        this.session = new LegacyRsStorageSession(network);
    }

    INetwork network() { return network; }

    @Override public StorageSession session() { return session; }

    @Override
    public StorageOperationResult insert(ItemStack stack, boolean simulate) {
        ItemStack remainder = network.insertItem(stack.copy(), stack.getCount(),
                simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                        : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        return StorageOperationResult.inserted(
                simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                        : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                stack, remainder);
    }

    @Override
    public StorageOperationResult insert(Player player, ItemStack stack, boolean simulate) {
        ItemStack remainder = network.insertItem(stack.copy(), stack.getCount(),
                simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                        : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!simulate && player != null) {
            ItemStack accepted = stack.copy();
            accepted.shrink(remainder.getCount());
            if (!accepted.isEmpty()) {
                var tracker = network.getItemStorageTracker();
                if (tracker != null) tracker.changed(player, accepted);
                if (player instanceof ServerPlayer serverPlayer) {
                    com.huanghuang.rsintegration.crafting.MaterialSources.invalidateFor(serverPlayer);
                    serverPlayer.containerMenu.broadcastChanges();
                }
            }
        }
        return StorageOperationResult.inserted(
                simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                        : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                stack, remainder);
    }

    @Override
    public StorageOperationResult extractExact(Player player, ItemStack template,
                                               long amount, boolean simulate) {
        ItemStack result = RSIntegrationNetwork.extractExactFromNetwork(network, template,
                Math.toIntExact(amount), player instanceof ServerPlayer sp ? sp : null, simulate);
        return StorageOperationResult.extracted(
                simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                        : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                amount, result.isEmpty() ? java.util.List.of() : java.util.List.of(result));
    }

    private static final class LegacyRsStorageSession implements StorageSession {
        private final INetwork network;
        LegacyRsStorageSession(INetwork network) { this.network = network; }

        @Override public com.huanghuang.rsintegration.storage.StorageReference reference() {
            // Keep the transitional endpoint on the same canonical reference
            // format as RefinedStorageBackend. Older packets used the raw
            // "dimension@BlockPos{...}" form, which the typed resolver cannot
            // parse and made an otherwise usable RS network appear missing on
            // the next recipe-tree request.
            var level = network.getLevel();
            var position = network.getPosition();
            if (level == null || position == null) {
                throw new IllegalStateException("RS network has no location");
            }
            return new com.huanghuang.rsintegration.storage.StorageReference(
                    new com.huanghuang.rsintegration.storage.StorageBackendId("refinedstorage"),
                    "v1|" + level.dimension().location() + "@"
                            + position.getX() + "," + position.getY() + "," + position.getZ());
        }

        @Override public StorageSnapshotResult snapshotItems(ServerPlayer player) {
            var cache = network.getItemStorageCache();
            if (cache == null || cache.getList() == null) {
                return StorageSnapshotResult.failure(com.huanghuang.rsintegration.storage.StorageSnapshotStatus.UNAVAILABLE);
            }
            java.util.List<com.huanghuang.rsintegration.storage.StoredItem> items = new java.util.ArrayList<>();
            for (var entry : cache.getList().getStacks()) {
                ItemStack stack = entry.getStack();
                if (!stack.isEmpty() && stack.getCount() > 0) {
                    items.add(new com.huanghuang.rsintegration.storage.StoredItem(itemKey(stack), stack.getCount()));
                }
            }
            return StorageSnapshotResult.success(new com.huanghuang.rsintegration.storage.StorageSnapshot(
                    reference().backendId(), items));
        }

        @Override public com.huanghuang.rsintegration.storage.StoragePermissionResult checkPermission(
                ServerPlayer player, com.huanghuang.rsintegration.storage.StoragePermission permission) {
            return com.huanghuang.rsintegration.storage.StoragePermissionResult.allowed();
        }

        @Override public StorageOperationResult extractExact(ServerPlayer player,
                com.huanghuang.rsintegration.storage.StorageItemKey key, long amount, boolean simulate) {
            ItemStack template = key.displayStack();
            ItemStack result = RSIntegrationNetwork.extractExactFromNetwork(network, template,
                    Math.toIntExact(amount), player, simulate);
            return StorageOperationResult.extracted(
                    simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                            : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                    amount, result.isEmpty() ? java.util.List.of() : java.util.List.of(result));
        }

        @Override public StorageOperationResult extractMatching(ServerPlayer player, Ingredient ingredient,
                                                                 long amount, boolean simulate) {
            ItemStack result = RSIntegrationNetwork.extractFromNetwork(network, ingredient,
                    Math.toIntExact(amount), player, simulate);
            return StorageOperationResult.extracted(
                    simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                            : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                    amount, result.isEmpty() ? java.util.List.of() : java.util.List.of(result));
        }

        @Override public StorageOperationResult insert(ServerPlayer player, ItemStack stack, boolean simulate) {
            ItemStack remainder = network.insertItem(stack.copy(), stack.getCount(),
                    simulate ? com.refinedmods.refinedstorage.api.util.Action.SIMULATE
                            : com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!simulate) {
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-Storage-RS] insert {} x{} -> remainder x{} at {}",
                        stack.getHoverName().getString(), stack.getCount(), remainder.getCount(),
                        network.getPosition());
            }
            return StorageOperationResult.inserted(
                    simulate ? com.huanghuang.rsintegration.storage.StorageOperationMode.SIMULATE
                            : com.huanghuang.rsintegration.storage.StorageOperationMode.PERFORM,
                    stack, remainder);
        }
    }
}
