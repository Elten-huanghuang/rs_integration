package com.huanghuang.rsintegration.magic.route;

import com.huanghuang.rsintegration.magic.MagicResourceKind;
import com.huanghuang.rsintegration.magic.MagicResourceNetworkPool;

import javax.annotation.Nullable;

/**
 * 一个具体上下文里的存储后端。
 *
 * <p>不支持某资源时必须显式失败：把「这个后端没有这种资源」和「这种资源是空的」
 * 混为一谈会让预览少算，所以所有取数/写入方法都先走
 * {@link #requireSupported(MagicResourceKind)}。</p>
 */
public interface MagicStorageBackend {
    MagicStorageBackendDescriptor descriptor();

    default MagicResourceKind unsupported(@Nullable MagicResourceKind kind) {
        return kind;
    }

    /** 该资源在当前上下文里是否可达；与「存量为 0」无关。 */
    boolean reachable(MagicResourceKind kind);

    /** 绑定空间句柄；拿不到时返回后端标识下没有 payload 的句柄。 */
    MagicScopeHandle bind();

    /** 当前可达存量；调用前应确认 {@link #reachable(MagicResourceKind)}。 */
    int amount(MagicResourceKind kind, MagicScopeHandle handle);

    /** 预留；预留不直接扣库存，真正提取时才减少。 */
    MagicResourceNetworkPool reserve(MagicResourceKind kind, int requested,
                                     MagicScopeHandle handle);

    int insert(MagicResourceKind kind, int amount, MagicScopeHandle handle);

    int extract(MagicResourceKind kind, int amount, MagicScopeHandle handle);

    /**
     * 硬规则：资源不在声明范围内就抛，绝不静默返回 0。
     *
     * @throws UnsupportedOperationException 该资源不在 {@link #descriptor()} 的声明范围
     */
    default void requireSupported(MagicResourceKind kind) {
        if (kind == null || !descriptor().supports(kind)) {
            throw new UnsupportedOperationException(
                    descriptor().id() + " 不支持 " + (kind == null ? "null" : kind.id())
                            + "；支持范围=" + descriptor().supportedKinds());
        }
    }
}
