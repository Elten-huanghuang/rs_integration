package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.util.PlayerRateLimiter;

import java.util.UUID;

/** 限制客户端主动请求完整机器绑定快照的频率。 */
public final class BindingSyncRequestRateLimiter {
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final PlayerRateLimiter LIMITER = new PlayerRateLimiter();

    private BindingSyncRequestRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        return LIMITER.isRateLimited(playerId, MIN_INTERVAL_MS);
    }

    public static void onPlayerLogout(UUID playerId) {
        LIMITER.remove(playerId);
    }
}
