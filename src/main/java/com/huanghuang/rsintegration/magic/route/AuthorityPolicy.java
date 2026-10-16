package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 无空间归属时如何指定权威后端。
 *
 * <p>默认 {@link #SCOPE_ONLY}：只认 {@link MagicScope} 自带的空间归属，绝不按
 * priority 偷选。全局池模式必须显式指定后端标识，而不是让路由层按顺序取第一个
 * 可写的后端。</p>
 *
 * <p>返回的是 {@link ResourceLocation} 而不是后端实例：策略是配置对象，
 * 不应该持有与玩家相关的后端实例，也不应该把实例的创建时机从路由层挪走。</p>
 */
@FunctionalInterface
public interface AuthorityPolicy {
    /** 默认策略：不做跨后端聚合，没有空间归属就判不可达。 */
    AuthorityPolicy SCOPE_ONLY = kind -> null;

    /** 全局池模式：该 kind 一律使用 {@code backendId} 指定的后端。 */
    static AuthorityPolicy pinned(ResourceLocation backendId) {
        return kind -> backendId;
    }

    /** 该 kind 被显式指定的权威后端标识；没有指定时返回 null。 */
    @Nullable
    ResourceLocation pinnedBackendId(MagicResourceKind kind);
}
