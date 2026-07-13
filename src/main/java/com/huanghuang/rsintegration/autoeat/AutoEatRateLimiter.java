package com.huanghuang.rsintegration.autoeat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side rate limiter for {@code AutoEatPacket}. Each accepted eat clones
 * the full RS storage list and runs an edibility scan, so unthrottled spam from
 * a hacked client is a CPU-amplification DoS proportional to network size.
 *
 * <p>Dedicated (not shared with the GUI-open limiter) so an eat never falsely
 * throttles an unrelated GUI-open for the same player. {@value #MIN_INTERVAL_MS}ms
 * ≈ 4 eats/s — far above any legitimate click cadence.</p>
 */
public final class AutoEatRateLimiter {
    private static final Map<UUID, Long> LAST_TIME = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 250;

    private AutoEatRateLimiter() {}

    public static boolean isRateLimited(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_TIME.get(playerId);
        if (last != null && (now - last) < MIN_INTERVAL_MS) {
            return true;
        }
        LAST_TIME.put(playerId, now);
        return false;
    }

    public static void onPlayerLogout(UUID playerId) {
        LAST_TIME.remove(playerId);
    }
}
