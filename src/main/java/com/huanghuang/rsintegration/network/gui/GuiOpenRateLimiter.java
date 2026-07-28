package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.config.GuiTimingConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side rate limiter for machine GUI open requests.
 * Prevents a hacked client from sending 1000 open requests/sec
 * and OOM-ing the server with BlockEntity lookups + NetworkHooks.
 */
public final class GuiOpenRateLimiter {
    private static final Map<UUID, Long> LAST_OPEN_TIME = new ConcurrentHashMap<>();

    private GuiOpenRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        return isRateLimited(playerId, System.currentTimeMillis(), configuredIntervalMs());
    }

    static boolean isRateLimited(UUID playerId, long now, long intervalMs) {
        Long last = LAST_OPEN_TIME.get(playerId);
        if (last != null && (now - last) < intervalMs) {
            return true;
        }
        LAST_OPEN_TIME.put(playerId, now);
        return false;
    }

    private static long configuredIntervalMs() {
        try {
            return GuiTimingConfig.loadOpenRateLimitMs();
        } catch (Exception ignored) {
            return GuiTimingConfig.DEFAULT_OPEN_RATE_LIMIT_MS;
        }
    }

    public static void onPlayerLogout(UUID playerId) {
        LAST_OPEN_TIME.remove(playerId);
    }
}
