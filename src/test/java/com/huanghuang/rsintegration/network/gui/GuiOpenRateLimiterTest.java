package com.huanghuang.rsintegration.network.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer-1 (pure logic) tests for {@link GuiOpenRateLimiter}.
 * No Minecraft bootstrap required.
 */
class GuiOpenRateLimiterTest {

    @Test
    void firstOpenIsAllowed() {
        UUID player = UUID.randomUUID();
        assertFalse(GuiOpenRateLimiter.isRateLimited(player));
    }

    @Test
    void immediateReopenIsDropped() {
        UUID player = UUID.randomUUID();
        GuiOpenRateLimiter.isRateLimited(player);
        assertTrue(GuiOpenRateLimiter.isRateLimited(player),
                "a reopen within the 500ms window must be rate-limited");
    }

    @Test
    void openIsAllowedAfterTheInterval() throws InterruptedException {
        UUID player = UUID.randomUUID();
        GuiOpenRateLimiter.isRateLimited(player);
        Thread.sleep(520); // MIN_INTERVAL_MS is 500ms
        assertFalse(GuiOpenRateLimiter.isRateLimited(player));
    }

    /**
     * Contract nuance vs {@link com.huanghuang.rsintegration.crafting.PreviewRateLimiter}:
     * GuiOpenRateLimiter only refreshes the timestamp when a request is ALLOWED.
     * A dropped request does NOT slide the window, so the gate opens exactly
     * MIN_INTERVAL_MS after the last accepted open — not after the last attempt.
     */
    @Test
    void droppedAttemptsDoNotSlideTheWindow() throws InterruptedException {
        UUID player = UUID.randomUUID();
        assertFalse(GuiOpenRateLimiter.isRateLimited(player)); // accepted at t0
        Thread.sleep(300);
        assertTrue(GuiOpenRateLimiter.isRateLimited(player), "t0+300ms: still within window");
        Thread.sleep(250); // now t0+550ms — past the window measured from t0
        assertFalse(GuiOpenRateLimiter.isRateLimited(player),
                "window is measured from the last ACCEPTED open, not the last attempt");
    }

    @Test
    void limiterIsPerPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        GuiOpenRateLimiter.isRateLimited(a);
        assertFalse(GuiOpenRateLimiter.isRateLimited(b));
    }

    @Test
    void logoutResetsTheWindow() {
        UUID player = UUID.randomUUID();
        GuiOpenRateLimiter.isRateLimited(player);
        GuiOpenRateLimiter.onPlayerLogout(player);
        assertFalse(GuiOpenRateLimiter.isRateLimited(player));
    }
}
