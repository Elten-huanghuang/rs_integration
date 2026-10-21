package com.huanghuang.rsintegration.util;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 独立作用域的玩家请求限流器；被拒绝的请求不会滑动时间窗口。 */
public final class PlayerRateLimiter {
    private final Map<UUID, Long> lastAccepted = new ConcurrentHashMap<>();

    public boolean isRateLimited(
            @Nullable UUID playerId, long now, long intervalMillis) {
        if (playerId == null) return true;
        long interval = Math.max(0L, intervalMillis);
        Long previous = lastAccepted.get(playerId);
        if (previous != null && now - previous < interval) return true;
        lastAccepted.put(playerId, now);
        return false;
    }

    public boolean isRateLimited(@Nullable UUID playerId, long intervalMillis) {
        return isRateLimited(
                playerId, System.currentTimeMillis(), intervalMillis);
    }

    public void remove(UUID playerId) {
        if (playerId != null) lastAccepted.remove(playerId);
    }

    public void clear() {
        lastAccepted.clear();
    }
}
