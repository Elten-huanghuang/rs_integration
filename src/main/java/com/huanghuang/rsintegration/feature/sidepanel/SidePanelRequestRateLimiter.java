package com.huanghuang.rsintegration.feature.sidepanel;

import com.huanghuang.rsintegration.util.PlayerRateLimiter;

import java.util.UUID;

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
    private static final long MIN_INTERVAL_MS = 50;
    private static final PlayerRateLimiter LIMITER = new PlayerRateLimiter();

    private SidePanelRequestRateLimiter() {}

    /** Returns true if this request should be dropped (too soon after the last). */
    public static boolean isRateLimited(UUID playerId) {
        return LIMITER.isRateLimited(playerId, MIN_INTERVAL_MS);
    }

    public static void onPlayerLogout(UUID playerId) {
        LIMITER.remove(playerId);
    }
}
