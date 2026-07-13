package com.huanghuang.rsintegration.autoeat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layer-1 pure-logic tests for the AutoEat throttle. */
class AutoEatRateLimiterTest {

    @Test
    void firstAllowedThenDropped() {
        UUID p = UUID.randomUUID();
        assertFalse(AutoEatRateLimiter.isRateLimited(p));
        assertTrue(AutoEatRateLimiter.isRateLimited(p));
    }

    @Test
    void allowedAfterInterval() throws InterruptedException {
        UUID p = UUID.randomUUID();
        AutoEatRateLimiter.isRateLimited(p);
        Thread.sleep(270); // MIN_INTERVAL_MS is 250
        assertFalse(AutoEatRateLimiter.isRateLimited(p));
    }

    @Test
    void perPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AutoEatRateLimiter.isRateLimited(a);
        assertFalse(AutoEatRateLimiter.isRateLimited(b));
    }

    @Test
    void logoutResets() {
        UUID p = UUID.randomUUID();
        AutoEatRateLimiter.isRateLimited(p);
        AutoEatRateLimiter.onPlayerLogout(p);
        assertFalse(AutoEatRateLimiter.isRateLimited(p));
    }

    /**
     * Independence: AutoEat has its OWN limiter, so eating must not throttle
     * an unrelated GUI-open for the same player (the bug avoided by not reusing
     * GuiOpenRateLimiter).
     */
    @Test
    void independentFromGuiOpenLimiter() {
        UUID p = UUID.randomUUID();
        AutoEatRateLimiter.isRateLimited(p); // consume the AutoEat window
        assertFalse(
                com.huanghuang.rsintegration.network.gui.GuiOpenRateLimiter.isRateLimited(p),
                "an eat must not consume the GUI-open window");
    }
}
