package com.huanghuang.rsintegration.storage.rs;

import java.util.Set;

/** 只放宽服务端完整库存快照的传输，不放宽提取/插入请求。 */
public final class GridTransferPolicy {
    private static final Set<String> SNAPSHOTS = Set.of(
            "com.refinedmods.refinedstorage.network.grid.GridItemUpdateMessage",
            "com.refinedmods.refinedstorage.network.grid.GridFluidUpdateMessage",
            "com.refinedmods.refinedstorage.network.grid.PortableGridItemUpdateMessage",
            "com.refinedmods.refinedstorage.network.grid.PortableGridFluidUpdateMessage");

    private GridTransferPolicy() {}

    public static int limit(String messageType, int original, boolean enabled, int configured) {
        if (!enabled || !SNAPSHOTS.contains(messageType)) return original;
        return Math.max(original, Math.max(10, Math.min(2000, configured)));
    }
}
