package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;

/** Pure validation and conversion of one native RS snapshot response. */
final class RefinedStorageSnapshotMapper {
    private RefinedStorageSnapshotMapper() {}

    static StorageSnapshotResult map(RefinedStorageSnapshotRead read) {
        return map(read, (stack, failure) -> {});
    }

    static StorageSnapshotResult map(RefinedStorageSnapshotRead read,
                                     BiConsumer<ItemStack, IllegalArgumentException> skipped) {
        return map(read, ItemStack::getCount, skipped);
    }

    static StorageSnapshotResult map(RefinedStorageSnapshotRead read,
                                     ToLongFunction<ItemStack> exactAmount,
                                     BiConsumer<ItemStack, IllegalArgumentException> skipped) {
        if (read == null) return StorageSnapshotResult.failure(StorageSnapshotStatus.INVALID_RESPONSE,
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        if (!read.available()) return StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
        if (exactAmount == null) throw new IllegalArgumentException("exactAmount");
        try {
            List<StoredItem> items = new ArrayList<>();
            read.forEachInternalItem(stack -> {
                if (!stack.isEmpty() && stack.getCount() > 0) {
                    try {
                        long amount = exactAmount.applyAsLong(stack);
                        if (amount <= 0L) return;
                        items.add(new StoredItem(
                                RefinedStorageItemKeys.fromStack(stack),
                                amount));
                    } catch (IllegalArgumentException failure) {
                        // 单个物品的 NBT 无法形成稳定身份时，不影响同一网络的其他材料。
                        skipped.accept(stack, failure);
                    }
                }
            });
            return StorageSnapshotResult.success(new StorageSnapshot(RefinedStorageIds.BACKEND, items));
        } catch (IllegalArgumentException e) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.INVALID_RESPONSE,
                    StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        }
    }
}
