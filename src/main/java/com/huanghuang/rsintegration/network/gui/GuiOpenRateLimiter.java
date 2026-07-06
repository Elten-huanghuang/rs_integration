package com.huanghuang.rsintegration.network.gui;

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
    private static final long MIN_INTERVAL_MS = 500;

    private GuiOpenRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_OPEN_TIME.get(playerId);
        if (last != null && (now - last) < MIN_INTERVAL_MS) {
            return true;
        }
        LAST_OPEN_TIME.put(playerId, now);
        return false;
    }

    public static void onPlayerLogout(UUID playerId) {
        LAST_OPEN_TIME.remove(playerId);
    }
}
