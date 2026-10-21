package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.config.GuiTimingConfig;
import com.huanghuang.rsintegration.util.PlayerRateLimiter;

import java.util.UUID;

/**
 * Server-side rate limiter for machine GUI open requests.
 * Prevents a hacked client from sending 1000 open requests/sec
 * and OOM-ing the server with BlockEntity lookups + NetworkHooks.
 */
public final class GuiOpenRateLimiter {
    private static final PlayerRateLimiter LIMITER = new PlayerRateLimiter();

    private GuiOpenRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        return isRateLimited(playerId, System.currentTimeMillis(), configuredIntervalMs());
    }

    static boolean isRateLimited(UUID playerId, long now, long intervalMs) {
        return LIMITER.isRateLimited(playerId, now, intervalMs);
    }

    private static long configuredIntervalMs() {
        try {
            return GuiTimingConfig.loadOpenRateLimitMs();
        } catch (Exception ignored) {
            return GuiTimingConfig.DEFAULT_OPEN_RATE_LIMIT_MS;
        }
    }

    public static void onPlayerLogout(UUID playerId) {
        LIMITER.remove(playerId);
    }
}
