package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * 一次合成所面对的存储管辖范围。
 *
 * <p>{@code backendId} 直接来自链路端点，不做任何后端白名单：新增后端只要能被
 * {@link CraftStorageEndpoint} 表达，就自动进入路由。这里只做 null 判断，读不出来
 * 就是「没有空间归属」，交给路由层判不可达。</p>
 *
 * @param player    发起合成的玩家；不可为 null
 * @param endpoint  链路选定的存储端点；纯原生路径时为 null
 * @param backendId 端点后端标识，例如 {@code minecraft:refinedstorage}
 */
public record MagicScope(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint,
                         @Nullable ResourceLocation backendId) {

    /** 由链路端点构造；后端标识从端点的 backendId().value() 直接读。 */
    public static MagicScope of(ServerPlayer player, @Nullable CraftStorageEndpoint endpoint) {
        return new MagicScope(player, endpoint, backendIdOf(endpoint));
    }

    @Nullable
    private static ResourceLocation backendIdOf(@Nullable CraftStorageEndpoint endpoint) {
        if (endpoint == null || endpoint.session() == null) return null;
        try {
            String value = endpoint.session().reference().backendId().value();
            return value == null || value.isEmpty() ? null : ResourceLocation.tryParse(value);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * 该范围是否由名为 {@code name} 的后端提供。
     *
     * <p>只比较路径部分：端点的后端标识是裸名（{@code refinedstorage}），
     * 解析成 {@link ResourceLocation} 后会落到默认命名空间下。</p>
     */
    public boolean hasBackend(@Nullable String name) {
        return name != null && backendId != null && name.equals(backendId.getPath());
    }

    /** 端点后端名；没有端点时为 null。 */
    @Nullable
    public String backendName() {
        return backendId == null ? null : backendId.getPath();
    }
}
