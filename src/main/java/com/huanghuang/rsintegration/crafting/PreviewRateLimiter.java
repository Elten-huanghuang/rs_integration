package com.huanghuang.rsintegration.crafting;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side rate limiter for crafting plan preview requests.
 * Prevents a hacked client from sending 1000 preview packets/sec
 * and DDoS-ing the server with CandidateEngine + CraftingResolver computations.
 *
 * <h3>Wiring status</h3>
 * <b>Wired</b> — checked in {@code GenericCraftPacket.handle()} before
 * {@code tryBuildPlan()}. Cleanup wired via {@code RSSidePanelNetworkHandler.onPlayerLogout()}.
 * Mirrors {@code GuiOpenRateLimiter} which guards the machine-GUI path.
 *
 * <h3>Threshold</h3>
 * {@value #MIN_INTERVAL_MS}ms between preview requests per player (max 10 req/s).
 * If a request is rate-limited it is silently dropped — the debounce on the
 * client side ({@code CraftingPlanScreen} repeat-count input) already limits
 * legitimate requests to at most one per 150ms.
 */
public final class PreviewRateLimiter {
    private static final Map<UUID, Long> LAST_PREVIEW_TIME = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 100; // max 10 req/s per player

    private PreviewRateLimiter() {}

    /** Returns true if this request should be silently dropped. */
    public static boolean isRateLimited(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = LAST_PREVIEW_TIME.get(playerId);
        if (last != null && (now - last) < MIN_INTERVAL_MS) {
            return true;
        }
        LAST_PREVIEW_TIME.put(playerId, now);
        return false;
    }

    /** Cleanup on player logout. */
    public static void onPlayerLogout(UUID playerId) {
        LAST_PREVIEW_TIME.remove(playerId);
    }
}
