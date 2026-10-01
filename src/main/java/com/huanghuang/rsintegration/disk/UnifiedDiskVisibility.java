package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.config.RSStorageConfig;

/** 展示状态不依赖 RS 类型；客户端连接服务器后以服务器开关为准。 */
public final class UnifiedDiskVisibility {
    private static volatile Boolean clientOverride;

    private UnifiedDiskVisibility() {}

    public static boolean visible() {
        return ModItems.UNIFIED_STORAGE_DISK != null
                && (clientOverride != null ? clientOverride : RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK));
    }

    public static void fromServer(boolean enabled) { clientOverride = enabled; }
    public static void disconnect() { clientOverride = null; }
}
