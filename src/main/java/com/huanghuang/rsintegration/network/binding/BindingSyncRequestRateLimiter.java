package com.huanghuang.rsintegration.network.binding;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 限制客户端主动请求完整机器绑定快照的频率。 */
public final class BindingSyncRequestRateLimiter {
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private BindingSyncRequestRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        long now = System.currentTimeMillis();
        Long previous = LAST_REQUEST.get(playerId);
        if (previous != null && now - previous < MIN_INTERVAL_MS) return true;
        LAST_REQUEST.put(playerId, now);
        return false;
    }

    public static void onPlayerLogout(UUID playerId) {
        if (playerId != null) LAST_REQUEST.remove(playerId);
    }
}
