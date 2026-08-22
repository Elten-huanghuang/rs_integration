package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StorageSnapshotStatus;
import com.huanghuang.rsintegration.storage.StoredItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Pure validation and conversion of one native RS snapshot response. */
final class RefinedStorageSnapshotMapper {
    private RefinedStorageSnapshotMapper() {}

    static StorageSnapshotResult map(RefinedStorageSnapshotRead read) {
        if (read == null) return StorageSnapshotResult.failure(StorageSnapshotStatus.INVALID_RESPONSE,
                StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        if (!read.available()) return StorageSnapshotResult.failure(StorageSnapshotStatus.UNAVAILABLE);
        try {
            List<StoredItem> items = new ArrayList<>();
            for (ItemStack stack : read.items()) {
                if (!stack.isEmpty() && stack.getCount() > 0) {
                    items.add(new StoredItem(
                            RefinedStorageItemKeys.fromStack(stack),
                            stack.getCount()));
                }
            }
            return StorageSnapshotResult.success(new StorageSnapshot(RefinedStorageIds.BACKEND, items));
        } catch (IllegalArgumentException e) {
            return StorageSnapshotResult.failure(StorageSnapshotStatus.INVALID_RESPONSE,
                    StorageDiagnosticCode.INVALID_NATIVE_RESPONSE);
        }
    }
}
