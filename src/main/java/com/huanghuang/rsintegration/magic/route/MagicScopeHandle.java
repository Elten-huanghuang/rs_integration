package com.huanghuang.rsintegration.magic.route;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 后端自有的空间句柄。
 *
 * <p>刻意不做 sealed：后端数量会随注册增加，每加一个后端就要求改动这个类型会破坏
 * 「扩展靠注册」。外层不解释 {@code payload} 的内容，只用 {@code backendId} 判断
 * 句柄是不是自己签发的。</p>
 *
 * <p>典型 payload：RS 侧 {@code {"disks": List<UnifiedDiskCore>}}；
 * BD 侧 {@code {"networkId": Integer, "sourceKey": String}}。</p>
 */
public record MagicScopeHandle(ResourceLocation backendId, Map<String, Object> payload) {
    public MagicScopeHandle {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static MagicScopeHandle of(ResourceLocation backendId) {
        return new MagicScopeHandle(backendId, Map.of());
    }

    @Nullable
    public Object get(String key) {
        return payload.get(key);
    }

    public int intValue(String key, int fallback) {
        return payload.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    @Nullable
    public String stringValue(String key) {
        return payload.get(key) instanceof String value ? value : null;
    }

    /** 该句柄是否由 {@code backendId} 签发。 */
    public boolean belongsTo(@Nullable ResourceLocation other) {
        return other != null && other.equals(backendId);
    }
}
