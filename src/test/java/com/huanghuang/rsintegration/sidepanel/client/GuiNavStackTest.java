package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiNavStackTest extends BootstrapTest {
    @Test
    void pushTimeoutExpiresAfterConfiguredBoundary() {
        long startedAt = 10_000;
        long timeoutMs = 5_000;

        assertFalse(GuiNavStack.hasPushTimedOut(startedAt + timeoutMs, startedAt, timeoutMs));
        assertTrue(GuiNavStack.hasPushTimedOut(startedAt + timeoutMs + 1, startedAt, timeoutMs));
    }
}
