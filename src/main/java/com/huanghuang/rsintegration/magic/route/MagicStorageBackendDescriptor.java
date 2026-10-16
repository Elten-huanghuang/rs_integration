package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * 一个存储后端的声明与工厂。
 *
 * <p>支持范围由 {@link #supportedKinds()} 显式声明，不靠「现在能不能读出来」反推：
 * 暂时读不出的资源会被误判成不支持，进而静默少算。{@link MagicResourceRouter}
 * 只持有描述符，真正访问库存时才用 {@link #instantiate} 生成实例。</p>
 */
public interface MagicStorageBackendDescriptor {
    ResourceLocation id();

    Set<MagicResourceKind> supportedKinds();

    /** 越小越优先；只在全局池模式下当并列兜底，正常路径按空间归属决定。 */
    int priority();

    /** 该后端是否管辖这个范围；命中即为权威后端。 */
    boolean ownsScope(MagicScope scope);

    /** 该资源在这个后端下的路径类型；不支持时返回 {@link Channel#NONE}。 */
    Channel channel(MagicResourceKind kind);

    /**
     * 为一次操作生成后端实例。
     *
     * <p>返回值只对当前 {@code scope} 有效；调用方不应长期缓存。</p>
     */
    MagicStorageBackend instantiate(MagicScope scope);

    default boolean supports(MagicResourceKind kind) {
        return supportedKinds().contains(kind);
    }

    @Nullable
    default MagicResourceKind unsupportedViolation(MagicResourceKind kind) {
        return supports(kind) ? null : kind;
    }
}
