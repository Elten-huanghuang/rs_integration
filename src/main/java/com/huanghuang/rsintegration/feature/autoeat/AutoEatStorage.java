package com.huanghuang.rsintegration.feature.autoeat;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/** 一键进食使用的通用存储视图。 */
final class AutoEatStorage {
    private final CraftStorageEndpoint endpoint;
    private final StorageSnapshot snapshot;

    private AutoEatStorage(CraftStorageEndpoint endpoint, StorageSnapshot snapshot) {
        this.endpoint = endpoint;
        this.snapshot = snapshot;
    }

    static Optional<AutoEatStorage> resolve(ServerPlayer player) {
        Optional<CraftStorageEndpoint> endpoint = StorageRestockSupport.resolve(player);
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

    List<StoredItem> items() {
        return snapshot.items();
    }

    ItemStack extract(ServerPlayer player, ItemStack template, int amount) {
        return StorageRestockSupport.extract(endpoint, player, template, amount);
    }

    void returnItem(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        var result = CraftStorageEndpoint.insertInternal(endpoint, player, stack, false);
        var remainder = result.remainder();
        if (remainder.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-AutoEat] 入库结果不确定，不重复返还：{}", stack);
            return;
        }
        PlayerUtils.safeGiveToStorage(player, remainder.orElseThrow(), null);
    }
}
