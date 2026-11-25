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
    void immediateReopenIsDropped() {
        UUID player = UUID.randomUUID();
        assertFalse(GuiOpenRateLimiter.isRateLimited(player));
        assertTrue(GuiOpenRateLimiter.isRateLimited(player),
                "a reopen within the 500ms window must be rate-limited");
    }

    @Test
    void droppedAttemptDoesNotSlideTheIntervalBoundary() {
        UUID player = UUID.randomUUID();
        long startedAt = 1_000;
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt, 500));
        assertTrue(GuiOpenRateLimiter.isRateLimited(player, startedAt + 499, 500));
        assertFalse(GuiOpenRateLimiter.isRateLimited(player, startedAt + 500, 500),
                "窗口从上次放行时开始，不因拒绝请求而延长");
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
