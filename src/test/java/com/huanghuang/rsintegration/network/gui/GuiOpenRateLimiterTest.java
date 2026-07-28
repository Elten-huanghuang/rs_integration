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
    void openIsAllowedAtTheIntervalBoundary() {
        UUID player = UUID.randomUUID();
        long startedAt = 1_000;
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt, 500));
        assertTrue(GuiOpenRateLimiter.isRateLimited(player, startedAt + 499, 500));
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt + 500, 500));
    }

    /**
     * Contract nuance vs {@link com.huanghuang.rsintegration.crafting.PreviewRateLimiter}:
     * GuiOpenRateLimiter only refreshes the timestamp when a request is ALLOWED.
     * A dropped request does NOT slide the window, so the gate opens exactly
     * the configured interval after the last accepted open — not after the last attempt.
     */
    @Test
    void droppedAttemptsDoNotSlideTheWindow() {
        UUID player = UUID.randomUUID();
        long startedAt = 2_000;
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt, 500));
        assertTrue(GuiOpenRateLimiter.isRateLimited(player, startedAt + 300, 500));
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt + 500, 500),
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
