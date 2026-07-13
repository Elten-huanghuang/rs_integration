package com.huanghuang.rsintegration.sidepanel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layer-1 pure-logic tests for the heavy-packet throttle. */
class SidePanelRequestRateLimiterTest {

    @Test
    void firstRequestAllowed() {
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(UUID.randomUUID()));
    }

    @Test
    void immediateSecondDropped() {
        UUID p = UUID.randomUUID();
        SidePanelRequestRateLimiter.isRateLimited(p);
        assertTrue(SidePanelRequestRateLimiter.isRateLimited(p));
    }

    @Test
    void allowedAfterInterval() throws InterruptedException {
        UUID p = UUID.randomUUID();
        SidePanelRequestRateLimiter.isRateLimited(p);
        Thread.sleep(70); // MIN_INTERVAL_MS is 50
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(p));
    }

    @Test
    void perPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SidePanelRequestRateLimiter.isRateLimited(a);
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(b));
    }

    @Test
    void logoutResets() {
        UUID p = UUID.randomUUID();
        SidePanelRequestRateLimiter.isRateLimited(p);
        SidePanelRequestRateLimiter.onPlayerLogout(p);
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(p));
    }

    /** Dropped attempts must NOT slide the window (timestamp only refreshes when allowed). */
    @Test
    void droppedAttemptsDoNotSlideWindow() throws InterruptedException {
        UUID p = UUID.randomUUID();
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(p)); // t0 accepted
        Thread.sleep(30);
        assertTrue(SidePanelRequestRateLimiter.isRateLimited(p));  // t0+30 dropped
        Thread.sleep(30); // t0+60 — past 50ms from t0
        assertFalse(SidePanelRequestRateLimiter.isRateLimited(p));
    }
}
