package com.huanghuang.rsintegration.magic.route;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 资源路由结论。
 *
 * <p>四态是刻意分开的：{@code UNSUPPORTED} 是「世界上没有后端能回答」，
 * {@code UNREACHABLE} 是「有后端但当前上下文读不出来」，两者都不能退化成
 * 「存量 0」，否则预览会把缺料当成免费。</p>
 */
public record BackendResolution(Kind kind,
                                @Nullable MagicStorageBackendDescriptor backend,
                                List<MagicStorageBackendDescriptor> candidates,
                                Channel channel) {

    public enum Kind {
        /** 唯一权威后端已确定。 */
        AVAILABLE,
        /** 没有任何后端声明支持该资源。 */
        UNSUPPORTED,
        /** 有后端声明支持，但当前范围读不出来。 */
        UNREACHABLE,
        /** 多个候选且无法用空间归属或配置消歧；显式失败，不偷选第一个。 */
        AMBIGUOUS
    }

    public BackendResolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        channel = channel == null ? Channel.NONE : channel;
    }

    public static BackendResolution available(MagicStorageBackendDescriptor backend,
                                              Channel channel) {
        return new BackendResolution(Kind.AVAILABLE, backend, List.of(backend), channel);
    }

    public static BackendResolution unsupported(List<MagicStorageBackendDescriptor> candidates) {
        return new BackendResolution(Kind.UNSUPPORTED, null, candidates, Channel.NONE);
    }

    public static BackendResolution unreachable(
            @Nullable MagicStorageBackendDescriptor backend,
            List<MagicStorageBackendDescriptor> candidates) {
        return new BackendResolution(Kind.UNREACHABLE, backend, candidates, Channel.NONE);
    }

    public static BackendResolution ambiguous(List<MagicStorageBackendDescriptor> candidates) {
        return new BackendResolution(Kind.AMBIGUOUS, null, candidates, Channel.NONE);
    }

    public boolean resolved() {
        return kind == Kind.AVAILABLE && backend != null;
    }
}
