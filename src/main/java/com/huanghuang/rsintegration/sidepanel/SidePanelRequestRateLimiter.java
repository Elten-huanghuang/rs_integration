package com.huanghuang.rsintegration.sidepanel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side rate limiter for the heavyweight side-panel request /
 * inventory-transfer packets. Each accepted packet walks the whole RS storage
 * list plus (for requests) the full crafting-pattern set and Curios reflection,
 * so a hacked client firing them every tick can pin the server main thread.
 *
 * <p>Mirrors {@code GuiOpenRateLimiter} / {@code PreviewRateLimiter}: only
 * refreshes the timestamp when a request is ALLOWED, so a dropped burst does
 * not slide the window. {@value #MIN_INTERVAL_MS}ms ≈ 20 req/s per player,
 * comfortably above the client's own GUI-driven cadence.</p>
 */
public final class SidePanelRequestRateLimiter {
    private static final Map<UUID, Long> LAST_TIME = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 50;

    private SidePanelRequestRateLimiter() {}

    /** Returns true if this request should be dropped (too soon after the last). */
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
