package com.huanghuang.rsintegration.mods.goety.magic;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;

import javax.annotation.Nullable;

/**
 * 端点到存储后端的判定转发。
 *
 * <p>{@link CraftStorageEndpoints} 在 crafting 包里，magic 包可以引用它，但把它
 * 包一层能让 magic 侧的判定口径集中在一个地方，后续路由层替换实现时只改这里。</p>
 */
final class CraftStorageEndpointsSupport {
    private CraftStorageEndpointsSupport() {}

    static boolean isRefinedStorageEndpoint(@Nullable CraftStorageEndpoint endpoint) {
        return CraftStorageEndpoints.isRefinedStorage(endpoint);
    }

    static boolean isBeyondDimensionsEndpoint(@Nullable CraftStorageEndpoint endpoint) {
        return CraftStorageEndpoints.isBeyondDimensions(endpoint);
    }
}
