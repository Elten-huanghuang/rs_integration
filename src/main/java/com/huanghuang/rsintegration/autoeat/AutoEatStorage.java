package com.huanghuang.rsintegration.autoeat;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** Backend-neutral storage view used by the auto-eat service. */
final class AutoEatStorage {
    private final CraftStorageEndpoint endpoint;
    private final StorageSnapshot snapshot;

    private AutoEatStorage(CraftStorageEndpoint endpoint, StorageSnapshot snapshot) {
        this.endpoint = endpoint;
        this.snapshot = snapshot;
    }

    static Optional<AutoEatStorage> resolve(ServerPlayer player) {
        Optional<CraftStorageEndpoint> endpoint = resolveEndpoint(player);
        if (endpoint.isEmpty()) return Optional.empty();
        CraftStorageEndpoint value = endpoint.get();
        if (!value.session().hasPermission(player, StoragePermission.VIEW)
                || !value.session().hasPermission(player, StoragePermission.EXTRACT)) {
            return Optional.empty();
        }
        StorageSnapshotResult result = value.snapshot(player);
        if (!result.successful() || result.snapshot().isEmpty()) return Optional.empty();
        return Optional.of(new AutoEatStorage(value, result.snapshot().orElseThrow()));
    }

    private static Optional<CraftStorageEndpoint> resolveEndpoint(ServerPlayer player) {
        // StorageRestockSupport gives a held BD terminal precedence when both
        // RS and BD are installed, then falls back to the registered default.
        // An active RS Grid still resolves to RS because RS is the authenticated
        // current context and remains the first default backend.
        return com.huanghuang.rsintegration.storage.StorageRestockSupport.resolve(player);
    }

    List<StoredItem> items() {
        return snapshot.items();
    }

    ItemStack extract(ServerPlayer player, ItemStack template, int amount, boolean simulate) {
        StorageOperationResult result = endpoint.extractExact(player, template, amount, simulate);
        return merge(result.extractedStacks());
    }

    ItemStack insert(ServerPlayer player, ItemStack stack, boolean simulate) {
        return endpoint.insert(player, stack, simulate).remainder().orElse(stack.copy());
    }

    private static ItemStack merge(List<ItemStack> stacks) {
        ItemStack merged = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (merged.isEmpty()) merged = stack.copy();
            else if (ItemStack.isSameItemSameTags(merged, stack)) merged.grow(stack.getCount());
        }
        return merged;
    }
}
