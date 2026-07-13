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
    void requestIsAllowedAfterTheInterval() throws InterruptedException {
        UUID player = UUID.randomUUID();
        PreviewRateLimiter.isRateLimited(player);
        Thread.sleep(120); // MIN_INTERVAL_MS is 100ms
        assertFalse(PreviewRateLimiter.isRateLimited(player),
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

    /**
     * Contract nuance vs {@link com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter}:
     * PreviewRateLimiter refreshes the timestamp on EVERY call (even a dropped
     * one), so a sustained burst keeps sliding the window forward.
     */
    @Test
    void sustainedBurstStaysDropped() {
        UUID player = UUID.randomUUID();
        assertFalse(PreviewRateLimiter.isRateLimited(player));
        for (int i = 0; i < 5; i++) {
            assertTrue(PreviewRateLimiter.isRateLimited(player),
                    "each rapid follow-up within the window stays dropped");
        }
    }
}
