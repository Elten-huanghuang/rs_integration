package com.huanghuang.rsintegration.crafting;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer-1 (pure logic) tests for {@link PreviewRateLimiter}.
 * No Minecraft bootstrap required — depends only on UUID + wall clock.
 */
class PreviewRateLimiterTest {

    @Test
    void firstRequestIsAllowed() {
        UUID player = UUID.randomUUID();
        assertFalse(PreviewRateLimiter.isRateLimited(player),
                "the very first request from a player must never be dropped");
    }

    @Test
    void immediateSecondRequestIsDropped() {
        UUID player = UUID.randomUUID();
        PreviewRateLimiter.isRateLimited(player);
        assertTrue(PreviewRateLimiter.isRateLimited(player),
                "a burst within the 100ms window must be rate-limited");
    }

    @Test
    void requestIsAllowedAtTheIntervalBoundary() {
        UUID player = UUID.randomUUID();
        long startedAt = 1_000;
        assertFalse(PreviewRateLimiter.isRateLimited(player, startedAt, 100));
        assertTrue(PreviewRateLimiter.isRateLimited(player, startedAt + 99, 100));
        assertFalse(PreviewRateLimiter.isRateLimited(player, startedAt + 199, 100),
                "a request after the interval elapses must pass");
    }

    @Test
    void limiterIsPerPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PreviewRateLimiter.isRateLimited(a);
        assertFalse(PreviewRateLimiter.isRateLimited(b),
                "one player's traffic must not throttle another's");
    }

    @Test
    void logoutResetsTheWindow() {
        UUID player = UUID.randomUUID();
        PreviewRateLimiter.isRateLimited(player);
        PreviewRateLimiter.onPlayerLogout(player);
        assertFalse(PreviewRateLimiter.isRateLimited(player),
                "after logout the next request is treated as a fresh first request");
    }

    /** Rejected requests must not keep extending the throttle window. */
    @Test
    void rejectedBurstDoesNotSlideTheWindow() {
        UUID player = UUID.randomUUID();
        long startedAt = 1_000;
        assertFalse(PreviewRateLimiter.isRateLimited(player, startedAt, 100));
        for (int i = 0; i < 5; i++) {
            assertTrue(PreviewRateLimiter.isRateLimited(player, startedAt + i + 1, 100),
                    "each rapid follow-up within the window stays dropped");
        }
        assertFalse(PreviewRateLimiter.isRateLimited(player, 1_100, 100),
                "after the original interval, a request must be admitted even if rejected calls arrived in between");
    }
}
