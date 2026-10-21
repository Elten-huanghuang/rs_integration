package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.config.CraftingPreviewPolicy;
import com.huanghuang.rsintegration.util.PlayerRateLimiter;

import java.util.UUID;

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
 * The minimum interval is server-configured and defaults to
 * {@value CraftingPreviewPolicy#DEFAULT_RATE_LIMIT_MS}ms.
 * If a request is rate-limited it is silently dropped — the debounce on the
 * client side ({@code CraftingPlanScreen} repeat-count input) already limits
 * legitimate requests to at most one per 150ms.
 */
public final class PreviewRateLimiter {
    private static final PlayerRateLimiter LIMITER = new PlayerRateLimiter();
    private PreviewRateLimiter() {}

    /** Returns true if this request should be rejected for arriving too soon. */
    public static boolean isRateLimited(UUID playerId) {
        return isRateLimited(playerId, System.currentTimeMillis(), configuredIntervalMs());
    }

    static boolean isRateLimited(UUID playerId, long now, long intervalMs) {
        return LIMITER.isRateLimited(playerId, now, intervalMs);
    }

    private static long configuredIntervalMs() {
        try {
            return CraftingPreviewPolicy.load().rateLimitMs();
        } catch (Exception ignored) {
            return CraftingPreviewPolicy.DEFAULT_RATE_LIMIT_MS;
        }
    }

    /** Cleanup on player logout. */
    public static void onPlayerLogout(UUID playerId) {
        LIMITER.remove(playerId);
    }
}
